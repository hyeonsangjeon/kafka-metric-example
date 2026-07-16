package dev.hyeonsangjeon.observatory.runner;

import dev.hyeonsangjeon.observatory.model.EventType;
import dev.hyeonsangjeon.observatory.model.RunRequest;
import dev.hyeonsangjeon.observatory.model.TelemetryEvent;
import dev.hyeonsangjeon.observatory.provider.AiProvider;
import dev.hyeonsangjeon.observatory.provider.ModelProfile;
import dev.hyeonsangjeon.observatory.provider.ProviderFailure;
import dev.hyeonsangjeon.observatory.provider.ProviderRequest;
import dev.hyeonsangjeon.observatory.provider.ProviderResult;
import dev.hyeonsangjeon.observatory.transport.EventTransport;
import dev.hyeonsangjeon.observatory.util.Hashing;
import io.vertx.core.Vertx;

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
    private final AtomicReference<RunExecution> active = new AtomicReference<>();

    public WorkloadRunner(
            Vertx vertx,
            AiProvider provider,
            EventTransport transport,
            Supplier<String> sessionIdSupplier) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.sessionIdSupplier = Objects.requireNonNull(sessionIdSupplier, "sessionIdSupplier");
    }

    public StartResult start(RunRequest request) {
        ModelProfile profile = provider.requireModelProfile(request.modelProfile());
        RunExecution execution = new RunExecution(
                UUID.randomUUID().toString(), sessionIdSupplier.get(), request, profile);
        if (!active.compareAndSet(null, execution)) {
            RunExecution current = active.get();
            return new StartResult(false, current == null ? null : current.runId, "already_running");
        }

        TelemetryEvent started = event(
                execution, EventType.RUN_STARTED, null,
                TelemetryEvent.EventDetails.run(
                        request.traffic(), provider.synthetic(), profile.id(), profile.strategy()));
        transport.publish(started);
        int workers = Math.min(MAX_CONCURRENCY, request.traffic());
        for (int index = 0; index < workers; index++) {
            processNext(execution);
        }
        return new StartResult(true, execution.runId, "running");
    }

    public boolean stop(String runId) {
        RunExecution execution = active.get();
        if (execution == null || !execution.runId.equals(runId)
                || !execution.stopped.compareAndSet(false, true)) {
            return false;
        }
        TelemetryEvent stopped = event(execution, EventType.RUN_STOPPED, null,
                TelemetryEvent.EventDetails.empty(
                        provider.synthetic(), execution.profile.id(), execution.profile.strategy()));
        transport.publish(stopped);
        active.compareAndSet(execution, null);
        return true;
    }

    public void cancelForReset() {
        RunExecution execution = active.getAndSet(null);
        if (execution != null) {
            execution.stopped.set(true);
        }
    }

    public String activeRunId() {
        RunExecution execution = active.get();
        return execution == null ? null : execution.runId;
    }

    private void processNext(RunExecution execution) {
        if (execution.stopped.get()) {
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
                    1, "MODEL_RATE_LIMITED", true));
            return;
        }
        ProviderRequest providerRequest = new ProviderRequest(
                traceId,
                ordinal,
                execution.request.workload(),
                execution.request.prompt(),
                execution.profile.id());
        provider.invoke(providerRequest).whenComplete((providerResult, providerFailure) ->
                providerCompleted(execution, traceId, ordinal, promptHash,
                        startedNanos, 1, providerResult, providerFailure));
    }

    private void throttleAndRetry(
            RunExecution execution,
            String traceId,
            int ordinal,
            String promptHash,
            long startedNanos,
            int attempt,
            String errorCode,
            boolean syntheticSignal) {
        if (execution.stopped.get()) {
            terminal(execution);
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
        vertx.setTimer(260L, ignoredTimer -> {
            if (execution.stopped.get()) {
                terminal(execution);
                return;
            }
            ProviderRequest retry = new ProviderRequest(
                    traceId,
                    ordinal,
                    execution.request.workload(),
                    execution.request.prompt(),
                    execution.profile.id());
            provider.invoke(retry).whenComplete((providerResult, providerFailure) ->
                    providerCompleted(execution, traceId, ordinal, promptHash,
                            startedNanos, attempt + 1, providerResult, providerFailure));
        });
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
        if (execution.stopped.get()) {
            terminal(execution);
            return;
        }
        long latencyMs = elapsedMillis(startedNanos);
        TelemetryEvent terminalEvent;
        if (failure == null) {
            ProviderResult safeResult = result == null
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
                throttleAndRetry(execution, traceId, ordinal, promptHash, startedNanos,
                        attempt, providerFailure.safeCode(), provider.synthetic());
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
            if (execution.stopped.compareAndSet(false, true)) {
                transport.publish(event(
                        execution,
                        EventType.RUN_COMPLETED,
                        null,
                        TelemetryEvent.EventDetails.empty(
                                provider.synthetic(),
                                execution.profile.id(),
                                execution.profile.strategy())));
                active.compareAndSet(execution, null);
            }
            return;
        }
        processNext(execution);
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

    private static final class RunExecution {
        private final String runId;
        private final String sessionId;
        private final RunRequest request;
        private final ModelProfile profile;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicInteger nextOrdinal = new AtomicInteger(1);
        private final AtomicInteger terminals = new AtomicInteger();
        private final AtomicBoolean stopped = new AtomicBoolean();

        private RunExecution(
                String runId,
                String sessionId,
                RunRequest request,
                ModelProfile profile) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.request = request;
            this.profile = profile;
        }
    }
}
