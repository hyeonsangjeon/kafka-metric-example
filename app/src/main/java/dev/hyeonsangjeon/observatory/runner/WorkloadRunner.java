package dev.hyeonsangjeon.observatory.runner;

import dev.hyeonsangjeon.observatory.model.ComparisonRequest;
import dev.hyeonsangjeon.observatory.model.EventType;
import dev.hyeonsangjeon.observatory.model.RunRequest;
import dev.hyeonsangjeon.observatory.model.TelemetryEvent;
import dev.hyeonsangjeon.observatory.projection.ComparisonProjection;
import dev.hyeonsangjeon.observatory.provider.AiProvider;
import dev.hyeonsangjeon.observatory.provider.ModelProfile;
import dev.hyeonsangjeon.observatory.provider.ProviderFailure;
import dev.hyeonsangjeon.observatory.provider.ProviderRequest;
import dev.hyeonsangjeon.observatory.provider.ProviderResult;
import dev.hyeonsangjeon.observatory.transport.EventTransport;
import dev.hyeonsangjeon.observatory.util.Hashing;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class WorkloadRunner {
    private static final int MAX_CONCURRENCY = 4;

    private final Vertx vertx;
    private final AiProvider provider;
    private final EventTransport transport;
    private final Supplier<String> sessionIdSupplier;
    private final int providerInvocationLimit;
    private final AtomicReference<ActiveExecution> active = new AtomicReference<>();
    private final AtomicReference<ComparisonProjection> latestComparison = new AtomicReference<>();

    public WorkloadRunner(
            Vertx vertx,
            AiProvider provider,
            EventTransport transport,
            Supplier<String> sessionIdSupplier) {
        this(vertx, provider, transport, sessionIdSupplier, RunRequest.MAX_TRAFFIC);
    }

    public WorkloadRunner(
            Vertx vertx,
            AiProvider provider,
            EventTransport transport,
            Supplier<String> sessionIdSupplier,
            int providerInvocationLimit) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.sessionIdSupplier = Objects.requireNonNull(sessionIdSupplier, "sessionIdSupplier");
        if (providerInvocationLimit < 1 || providerInvocationLimit > RunRequest.MAX_TRAFFIC) {
            throw new IllegalArgumentException("providerInvocationLimit must be between 1 and 100");
        }
        this.providerInvocationLimit = providerInvocationLimit;
    }

    public StartResult start(RunRequest request) {
        ModelProfile profile = provider.requireModelProfile(request.modelProfile());
        boolean cloudBudgeted = "foundry".equals(provider.alias());
        if (cloudBudgeted && request.traffic() > providerInvocationLimit) {
            throw new IllegalArgumentException(
                    "traffic exceeds the configured provider invocation limit of "
                            + providerInvocationLimit);
        }
        RunExecution execution = new RunExecution(
                UUID.randomUUID().toString(),
                sessionIdSupplier.get(),
                request,
                profile,
                null,
                -1,
                cloudBudgeted ? providerInvocationLimit : Integer.MAX_VALUE);
        if (!active.compareAndSet(null, execution)) {
            ActiveExecution current = active.get();
            return new StartResult(false, current == null ? null : current.id(), "already_running");
        }

        startRunExecution(execution);
        return new StartResult(true, execution.runId, "running");
    }

    public ComparisonStartResult startComparison(ComparisonRequest request) {
        List<ModelProfile> profiles = List.copyOf(provider.modelProfiles());
        if (profiles.size() < 2) {
            return new ComparisonStartResult(
                    false, null, "unavailable", "profiles_unavailable", null);
        }
        long planned = (long) request.trafficPerProfile() * profiles.size();
        if (planned > providerInvocationLimit) {
            return new ComparisonStartResult(
                    false, null, "rejected", "provider_invocation_limit", null);
        }

        String comparisonId = UUID.randomUUID().toString();
        ComparisonProjection projection = new ComparisonProjection(
                comparisonId, request, profiles, providerInvocationLimit);
        ComparisonExecution comparison = new ComparisonExecution(
                comparisonId,
                sessionIdSupplier.get(),
                request,
                profiles,
                projection,
                (int) planned,
                providerInvocationLimit);
        if (!active.compareAndSet(null, comparison)) {
            ActiveExecution current = active.get();
            return new ComparisonStartResult(
                    false,
                    current == null ? null : current.id(),
                    "already_running",
                    "already_running",
                    null);
        }

        latestComparison.set(projection);
        startComparisonPhase(comparison, 0);
        return new ComparisonStartResult(
                true, comparisonId, "running", null, projection.receiptJson());
    }

    public boolean stop(String runId) {
        ActiveExecution current = active.get();
        if (!(current instanceof RunExecution execution)
                || !execution.runId.equals(runId)
                || !execution.stopped.compareAndSet(false, true)) {
            return false;
        }
        publishStopped(execution);
        active.compareAndSet(execution, null);
        return true;
    }

    public boolean stopComparison(String comparisonId) {
        ActiveExecution current = active.get();
        if (!(current instanceof ComparisonExecution comparison)
                || !comparison.comparisonId.equals(comparisonId)) {
            return false;
        }
        synchronized (comparison.lifecycleLock) {
            if (active.get() != comparison
                    || !comparison.stopped.compareAndSet(false, true)) {
                return false;
            }
            comparison.projection.stop();
            RunExecution child = comparison.currentPhase.getAndSet(null);
            if (child != null && child.stopped.compareAndSet(false, true)) {
                publishStopped(child);
            }
            active.compareAndSet(comparison, null);
        }
        return true;
    }

    public void cancelForReset() {
        ActiveExecution current = active.getAndSet(null);
        if (current instanceof RunExecution execution) {
            execution.stopped.set(true);
        } else if (current instanceof ComparisonExecution comparison) {
            comparison.stopped.set(true);
            synchronized (comparison.lifecycleLock) {
                comparison.projection.stop();
                RunExecution child = comparison.currentPhase.getAndSet(null);
                if (child != null) {
                    child.stopped.set(true);
                }
            }
        }
        latestComparison.set(null);
    }

    public String activeRunId() {
        ActiveExecution current = active.get();
        if (current instanceof RunExecution execution) {
            return execution.runId;
        }
        if (current instanceof ComparisonExecution comparison) {
            RunExecution child = comparison.currentPhase.get();
            return child == null ? null : child.runId;
        }
        return null;
    }

    public JsonObject comparison(String comparisonId) {
        ComparisonProjection projection = latestComparison.get();
        if (projection == null || !projection.comparisonId().equals(comparisonId)) {
            return null;
        }
        return projection.toJson();
    }

    public JsonObject comparisonSnapshot() {
        ComparisonProjection projection = latestComparison.get();
        return projection == null ? null : projection.toJson();
    }

    public int providerInvocationLimit() {
        return providerInvocationLimit;
    }

    private void startComparisonPhase(ComparisonExecution comparison, int phaseIndex) {
        synchronized (comparison.lifecycleLock) {
            if (comparison.stopped.get()) {
                return;
            }
            ModelProfile profile = comparison.profiles.get(phaseIndex);
            RunRequest phaseRequest = new RunRequest(
                    comparison.request.workload(),
                    comparison.request.trafficPerProfile(),
                    comparison.request.scenario(),
                    comparison.request.prompt(),
                    profile.id());
            RunExecution phase = new RunExecution(
                    UUID.randomUUID().toString(),
                    comparison.sessionId,
                    phaseRequest,
                    profile,
                    comparison,
                    phaseIndex,
                    Integer.MAX_VALUE);
            comparison.currentPhase.set(phase);
            comparison.projection.startPhase(phaseIndex, phase.runId);
            startRunExecution(phase);
        }
    }

    private void startRunExecution(RunExecution execution) {
        TelemetryEvent started = event(
                execution, EventType.RUN_STARTED, null,
                TelemetryEvent.EventDetails.run(
                        execution.request.traffic(),
                        provider.synthetic(),
                        execution.profile.id(),
                        execution.profile.strategy()));
        transport.publish(started);
        int workers = Math.min(MAX_CONCURRENCY, execution.request.traffic());
        for (int index = 0; index < workers; index++) {
            processNext(execution);
        }
    }

    private void processNext(RunExecution execution) {
        if (execution.stopped.get() || ownerStopped(execution)) {
            return;
        }
        int ordinal = execution.nextOrdinal.getAndIncrement();
        if (ordinal > execution.request.traffic()) {
            return;
        }

        String traceId = UUID.randomUUID().toString();
        String promptHash = Hashing.sha256(execution.request.prompt());
        TelemetryEvent requestStarted = event(
                execution,
                EventType.REQUEST_STARTED,
                traceId,
                TelemetryEvent.EventDetails.request(
                        promptHash,
                        execution.request.prompt().length(),
                        1,
                        provider.synthetic(),
                        execution.profile.id(),
                        execution.profile.strategy()));

        transport.publish(requestStarted);
        long startedNanos = System.nanoTime();
        if (ScenarioPolicy.shouldThrottle(execution.request.scenario(), ordinal)) {
            vertx.setTimer(140L, ignoredTimer -> throttleAndRetry(
                    execution, traceId, ordinal, promptHash, startedNanos,
                    1, "MODEL_RATE_LIMITED", true, true));
            return;
        }
        invokeProvider(execution, traceId, ordinal, promptHash, startedNanos, 1, true);
    }

    private void throttleAndRetry(
            RunExecution execution,
            String traceId,
            int ordinal,
            String promptHash,
            long startedNanos,
            int attempt,
            String errorCode,
            boolean syntheticSignal,
            boolean firstProviderInvocationPending) {
        if (execution.stopped.get() || ownerStopped(execution)) {
            return;
        }
        long latencyMs = elapsedMillis(startedNanos);
        TelemetryEvent throttled = event(
                execution,
                EventType.RESPONSE_THROTTLED,
                traceId,
                TelemetryEvent.EventDetails.failure(
                        promptHash,
                        execution.request.prompt().length(),
                        latencyMs,
                        attempt,
                        errorCode,
                        syntheticSignal,
                        execution.profile.id(),
                        execution.profile.strategy(),
                        null,
                        null));
        transport.publish(throttled);
        if (execution.comparison != null) {
            execution.comparison.projection.retried(execution.phaseIndex);
        }
        vertx.setTimer(260L, ignoredTimer -> {
            if (execution.stopped.get() || ownerStopped(execution)) {
                return;
            }
            invokeProvider(
                    execution,
                    traceId,
                    ordinal,
                    promptHash,
                    startedNanos,
                    attempt + 1,
                    firstProviderInvocationPending);
        });
    }

    private void invokeProvider(
            RunExecution execution,
            String traceId,
            int ordinal,
            String promptHash,
            long startedNanos,
            int attempt,
            boolean firstProviderInvocation) {
        if (execution.stopped.get() || ownerStopped(execution)) {
            return;
        }
        boolean acquired = execution.comparison != null
                ? execution.comparison.acquireProviderInvocation(firstProviderInvocation)
                : execution.acquireProviderInvocation(firstProviderInvocation);
        if (!acquired) {
            providerCompleted(
                    execution,
                    traceId,
                    ordinal,
                    promptHash,
                    startedNanos,
                    attempt,
                    null,
                    new ProviderFailure("MODEL_REQUEST_BUDGET_EXHAUSTED", false, null));
            return;
        }

        ProviderRequest providerRequest = new ProviderRequest(
                traceId,
                ordinal,
                execution.request.workload(),
                execution.request.prompt(),
                execution.profile.id());
        CompletableFuture<ProviderResult> invocation;
        try {
            invocation = provider.invoke(providerRequest);
            if (invocation == null) {
                throw new IllegalStateException("provider returned no completion stage");
            }
        } catch (RuntimeException failure) {
            providerCompleted(
                    execution,
                    traceId,
                    ordinal,
                    promptHash,
                    startedNanos,
                    attempt,
                    null,
                    failure);
            return;
        }
        invocation.whenComplete((providerResult, providerFailure) ->
                providerCompleted(
                        execution,
                        traceId,
                        ordinal,
                        promptHash,
                        startedNanos,
                        attempt,
                        providerResult,
                        providerFailure));
    }

    private void providerCompleted(
            RunExecution execution,
            String traceId,
            int ordinal,
            String promptHash,
            long startedNanos,
            int attempt,
            ProviderResult result,
            Throwable failure) {
        if (execution.stopped.get() || ownerStopped(execution)) {
            return;
        }
        long latencyMs = elapsedMillis(startedNanos);
        TelemetryEvent terminalEvent;
        ProviderResult safeResult = null;
        if (failure == null) {
            safeResult = result == null
                    ? new ProviderResult(
                            "",
                            execution.profile.strategy(),
                            "unresolved",
                            "Unresolved",
                            null,
                            null)
                    : result;
            String output = safeResult.output();
            terminalEvent = event(
                    execution,
                    EventType.RESPONSE_COMPLETED,
                    traceId,
                    TelemetryEvent.EventDetails.response(
                            promptHash,
                            execution.request.prompt().length(),
                            Hashing.sha256(output),
                            output.length(),
                            latencyMs,
                            attempt,
                            provider.synthetic(),
                            execution.profile.id(),
                            safeResult.routeStrategy(),
                            safeResult.selectedRoute(),
                            safeResult.modelFamily(),
                            safeResult.inputTokens(),
                            safeResult.outputTokens()));
        } else {
            ProviderFailure providerFailure = asProviderFailure(failure);
            if (providerFailure.throttled() && attempt == 1) {
                throttleAndRetry(
                        execution,
                        traceId,
                        ordinal,
                        promptHash,
                        startedNanos,
                        attempt,
                        providerFailure.safeCode(),
                        provider.synthetic(),
                        false);
                return;
            }
            terminalEvent = event(
                    execution,
                    EventType.RESPONSE_FAILED,
                    traceId,
                    TelemetryEvent.EventDetails.failure(
                            promptHash,
                            execution.request.prompt().length(),
                            latencyMs,
                            attempt,
                            providerFailure.throttled()
                                    ? "MODEL_RATE_LIMIT_RETRY_EXHAUSTED"
                                    : providerFailure.safeCode(),
                            provider.synthetic(),
                            execution.profile.id(),
                            execution.profile.strategy(),
                            "unresolved",
                            "Unresolved"));
        }

        if (execution.comparison != null) {
            if (failure == null) {
                execution.comparison.projection.succeeded(
                        execution.phaseIndex, latencyMs, safeResult);
            } else {
                execution.comparison.projection.failed(execution.phaseIndex, latencyMs);
            }
        }
        publishTerminal(execution, ordinal, terminalEvent);
    }

    private void publishTerminal(RunExecution execution, int ordinal, TelemetryEvent terminalEvent) {
        CompletableFuture<Void> published = transport.publish(terminalEvent);
        if (ScenarioPolicy.shouldDuplicate(execution.request.scenario(), ordinal)) {
            published.thenCompose(ignored -> transport.publish(terminalEvent));
        }
        terminal(execution);
    }

    private void terminal(RunExecution execution) {
        if (execution.terminals.incrementAndGet() == execution.request.traffic()) {
            if (!execution.stopped.compareAndSet(false, true)) {
                return;
            }
            transport.publish(event(
                    execution,
                    EventType.RUN_COMPLETED,
                    null,
                    TelemetryEvent.EventDetails.empty(
                            provider.synthetic(),
                            execution.profile.id(),
                            execution.profile.strategy())));
            if (execution.comparison == null) {
                active.compareAndSet(execution, null);
            } else {
                completeComparisonPhase(execution.comparison, execution);
            }
            return;
        }
        processNext(execution);
    }

    private void completeComparisonPhase(
            ComparisonExecution comparison,
            RunExecution phase) {
        synchronized (comparison.lifecycleLock) {
            if (comparison.stopped.get()
                    || !comparison.currentPhase.compareAndSet(phase, null)) {
                return;
            }
            comparison.projection.completePhase(phase.phaseIndex);
            int nextPhase = phase.phaseIndex + 1;
            if (nextPhase >= comparison.profiles.size()) {
                comparison.projection.complete();
                active.compareAndSet(comparison, null);
                return;
            }
            startComparisonPhase(comparison, nextPhase);
        }
    }

    private void publishStopped(RunExecution execution) {
        transport.publish(event(
                execution,
                EventType.RUN_STOPPED,
                null,
                TelemetryEvent.EventDetails.empty(
                        provider.synthetic(),
                        execution.profile.id(),
                        execution.profile.strategy())));
    }

    private static boolean ownerStopped(RunExecution execution) {
        return execution.comparison != null && execution.comparison.stopped.get();
    }

    private TelemetryEvent event(
            RunExecution execution,
            EventType eventType,
            String traceId,
            TelemetryEvent.EventDetails details) {
        return TelemetryEvent.create(
                eventType,
                execution.sessionId,
                execution.runId,
                traceId,
                provider.alias(),
                execution.request.workload(),
                execution.request.scenario(),
                execution.sequence.getAndIncrement(),
                ScenarioPolicy.partitionHint(execution.request.scenario()),
                details);
    }

    private static ProviderFailure asProviderFailure(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof ProviderFailure providerFailure) {
            return providerFailure;
        }
        return new ProviderFailure("MODEL_REQUEST_FAILED", false, current);
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    public record StartResult(boolean accepted, String runId, String status) {
    }

    public record ComparisonStartResult(
            boolean accepted,
            String comparisonId,
            String status,
            String reason,
            JsonObject receipt) {
    }

    private interface ActiveExecution {
        String id();
    }

    private static final class RunExecution implements ActiveExecution {
        private final String runId;
        private final String sessionId;
        private final RunRequest request;
        private final ModelProfile profile;
        private final ComparisonExecution comparison;
        private final int phaseIndex;
        private final int providerInvocationLimit;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicInteger nextOrdinal = new AtomicInteger(1);
        private final AtomicInteger terminals = new AtomicInteger();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private int providerInvocations;
        private int remainingFirstInvocations;

        private RunExecution(
                String runId,
                String sessionId,
                RunRequest request,
                ModelProfile profile,
                ComparisonExecution comparison,
                int phaseIndex,
                int providerInvocationLimit) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.request = request;
            this.profile = profile;
            this.comparison = comparison;
            this.phaseIndex = phaseIndex;
            this.providerInvocationLimit = providerInvocationLimit;
            this.remainingFirstInvocations = request.traffic();
        }

        @Override
        public String id() {
            return runId;
        }

        private synchronized boolean acquireProviderInvocation(boolean firstInvocation) {
            if (stopped.get()) {
                return false;
            }
            if (providerInvocationLimit == Integer.MAX_VALUE) {
                return true;
            }
            if (firstInvocation) {
                if (remainingFirstInvocations <= 0
                        || providerInvocations >= providerInvocationLimit) {
                    return false;
                }
                remainingFirstInvocations--;
            } else if (providerInvocations
                    >= providerInvocationLimit - remainingFirstInvocations) {
                return false;
            }
            providerInvocations++;
            return true;
        }
    }

    private static final class ComparisonExecution implements ActiveExecution {
        private final String comparisonId;
        private final String sessionId;
        private final ComparisonRequest request;
        private final List<ModelProfile> profiles;
        private final ComparisonProjection projection;
        private final int providerInvocationLimit;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicReference<RunExecution> currentPhase = new AtomicReference<>();
        private final Object lifecycleLock = new Object();

        private int providerInvocations;
        private int remainingFirstInvocations;

        private ComparisonExecution(
                String comparisonId,
                String sessionId,
                ComparisonRequest request,
                List<ModelProfile> profiles,
                ComparisonProjection projection,
                int plannedRequests,
                int providerInvocationLimit) {
            this.comparisonId = comparisonId;
            this.sessionId = sessionId;
            this.request = request;
            this.profiles = profiles;
            this.projection = projection;
            this.remainingFirstInvocations = plannedRequests;
            this.providerInvocationLimit = providerInvocationLimit;
        }

        @Override
        public String id() {
            return comparisonId;
        }

        private synchronized boolean acquireProviderInvocation(boolean firstInvocation) {
            if (stopped.get()) {
                return false;
            }
            if (firstInvocation) {
                if (remainingFirstInvocations <= 0
                        || providerInvocations >= providerInvocationLimit) {
                    return false;
                }
                remainingFirstInvocations--;
            } else if (providerInvocations
                    >= providerInvocationLimit - remainingFirstInvocations) {
                return false;
            }
            providerInvocations++;
            projection.providerInvoked();
            return true;
        }
    }
}
