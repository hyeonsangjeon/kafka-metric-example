package dev.hyeonsangjeon.observatory.runner;

import dev.hyeonsangjeon.observatory.model.ComparisonRequest;
import dev.hyeonsangjeon.observatory.model.EventType;
import dev.hyeonsangjeon.observatory.model.RunRequest;
import dev.hyeonsangjeon.observatory.model.Scenario;
import dev.hyeonsangjeon.observatory.model.TelemetryEvent;
import dev.hyeonsangjeon.observatory.model.Workload;
import dev.hyeonsangjeon.observatory.projection.ComparisonProjection;
import dev.hyeonsangjeon.observatory.provider.AiProvider;
import dev.hyeonsangjeon.observatory.provider.ModelProfile;
import dev.hyeonsangjeon.observatory.provider.ProviderFailure;
import dev.hyeonsangjeon.observatory.provider.ProviderRequest;
import dev.hyeonsangjeon.observatory.provider.ProviderResult;
import dev.hyeonsangjeon.observatory.transport.EventDelivery;
import dev.hyeonsangjeon.observatory.transport.EventTransport;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComparisonWorkloadRunnerTest {
    private static final List<ModelProfile> PROFILES = List.of(
            new ModelProfile("fixed", "Fixed model", "fixed", "Fixed.", false),
            new ModelProfile("router-default", "Router default", "balanced", "Default.", true),
            new ModelProfile("router-advanced", "Router advanced", "cost", "Advanced.", true));

    private Vertx vertx;

    @BeforeEach
    void createVertx() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void closeVertx() throws InterruptedException {
        CountDownLatch closed = new CountDownLatch(1);
        vertx.close().onComplete(ignored -> closed.countDown());
        assertTrue(closed.await(2, TimeUnit.SECONDS), "Vert.x did not close in time");
    }

    @Test
    void phasesAreStrictlySequentialAndExposeOnlyTheSafeComparisonContract() throws Exception {
        ControlledProvider provider = new ControlledProvider();
        WorkloadRunner runner = runner(provider, 4);

        WorkloadRunner.ComparisonStartResult start = runner.startComparison(request(1));

        assertTrue(start.accepted());
        assertEquals(Set.of(
                        "comparisonId", "status", "profiles", "trafficPerProfile",
                        "plannedRequests", "providerInvocationLimit"),
                start.receipt().fieldNames());
        assertEquals(1, provider.invocations.size());
        assertEquals("fixed", provider.invocations.get(0).request().modelProfile());

        WorkloadRunner.StartResult conflictingRun = runner.start(new RunRequest(
                Workload.CHAT, 1, Scenario.HEALTHY, "different", "fixed"));
        WorkloadRunner.ComparisonStartResult conflictingComparison =
                runner.startComparison(request(1));
        assertFalse(conflictingRun.accepted());
        assertFalse(conflictingComparison.accepted());
        assertEquals("already_running", conflictingComparison.reason());

        provider.invocations.get(0).completion().complete(result("Fixed", 2L, 3L));
        await(() -> provider.invocations.size() == 2);
        assertEquals("router-default", provider.invocations.get(1).request().modelProfile());
        provider.invocations.get(1).completion().complete(result("General", 4L, 6L));
        await(() -> provider.invocations.size() == 3);
        assertEquals("router-advanced", provider.invocations.get(2).request().modelProfile());
        provider.invocations.get(2).completion().complete(result("Fast", 3L, 4L));

        await(() -> "completed".equals(
                runner.comparison(start.comparisonId()).getString("status")));
        JsonObject comparison = runner.comparison(start.comparisonId());
        assertEquals(Set.of(
                        "comparisonId", "status", "workload", "scenario", "trafficPerProfile",
                        "profiles", "currentProfile", "plannedRequests", "providerInvocations",
                        "providerInvocationLimit", "startedAt", "completedAt", "phases"),
                comparison.fieldNames());
        assertEquals(3, comparison.getInteger("providerInvocations"));
        assertEquals(3, comparison.getInteger("plannedRequests"));
        assertNull(comparison.getValue("currentProfile"));
        assertNotNull(comparison.getString("completedAt"));

        JsonArray phases = comparison.getJsonArray("phases");
        assertEquals(3, phases.size());
        assertEquals(List.of("fixed", "router-default", "router-advanced"),
                phases.stream().map(value -> ((JsonObject) value).getString("profile")).toList());
        JsonObject baseline = phases.getJsonObject(0);
        JsonObject defaultRouter = phases.getJsonObject(1);
        assertEquals(1, baseline.getInteger("completed"));
        assertEquals(0, baseline.getInteger("failed"));
        assertEquals(5L, defaultRouter.getLong("totalTokensDelta"));
        assertEquals(100.0, defaultRouter.getDouble("totalTokensDeltaPercent"));
        assertEquals(Set.of(
                        "profile", "label", "kind", "routeStrategy", "status", "runId",
                        "requested", "completed", "failed", "retries", "successRate",
                        "p50LatencyMs", "p95LatencyMs", "inputTokens", "outputTokens",
                        "tokenSamples", "models", "p95DeltaMs", "p95DeltaPercent",
                        "totalTokensDelta", "totalTokensDeltaPercent"),
                baseline.fieldNames());

        String encoded = comparison.encode();
        assertFalse(encoded.contains("same private prompt"));
        assertFalse(encoded.contains("private provider output"));
        assertFalse(encoded.contains("traceId"));
        assertFalse(encoded.contains("promptHash"));
        assertFalse(encoded.contains("endpoint"));
        assertFalse(encoded.contains("deployment"));
        assertTrue(java.util.Arrays.stream(ComparisonProjection.class.getDeclaredFields())
                .noneMatch(field -> field.getType() == ComparisonRequest.class));
    }

    @Test
    void comparisonOverBudgetIsRejectedBeforeTelemetryOrProviderCalls() {
        ControlledProvider provider = new ControlledProvider();
        RecordingTransport transport = new RecordingTransport();
        WorkloadRunner runner = new WorkloadRunner(
                vertx, provider, transport, () -> "session-budget", 5);

        WorkloadRunner.ComparisonStartResult result = runner.startComparison(request(2));

        assertFalse(result.accepted());
        assertEquals("provider_invocation_limit", result.reason());
        assertTrue(provider.invocations.isEmpty());
        assertTrue(transport.events.isEmpty());
        assertNull(runner.comparisonSnapshot());
    }

    @Test
    void retryBudgetReservesEveryFutureFirstCallAndNeverExceedsTheLimit() throws Exception {
        Map<String, AtomicInteger> callsByTrace = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicInteger providerCalls = new AtomicInteger();
        AiProvider provider = provider(request -> {
            providerCalls.incrementAndGet();
            int traceAttempt = callsByTrace
                    .computeIfAbsent(request.traceId(), ignored -> new AtomicInteger())
                    .incrementAndGet();
            if ("fixed".equals(request.modelProfile()) && traceAttempt == 1) {
                return CompletableFuture.failedFuture(
                        new ProviderFailure("MODEL_RATE_LIMITED", true, null));
            }
            return CompletableFuture.completedFuture(result("Fast", 1L, 1L));
        });
        WorkloadRunner runner = runner(provider, 7);

        WorkloadRunner.ComparisonStartResult start = runner.startComparison(request(2));
        assertTrue(start.accepted());
        await(Duration.ofSeconds(4), () -> {
            JsonObject comparison = runner.comparison(start.comparisonId());
            return comparison != null && "completed".equals(comparison.getString("status"));
        });

        JsonObject comparison = runner.comparison(start.comparisonId());
        JsonObject fixed = comparison.getJsonArray("phases").getJsonObject(0);
        assertEquals(7, providerCalls.get());
        assertEquals(7, comparison.getInteger("providerInvocations"));
        assertEquals(2, fixed.getInteger("retries"));
        assertEquals(1, fixed.getInteger("completed"));
        assertEquals(1, fixed.getInteger("failed"));
        assertEquals("completed", comparison.getJsonArray("phases")
                .getJsonObject(2).getString("status"));
    }

    @Test
    void stopIgnoresLateProviderResultsAndReleasesTheSharedExecutionGate() throws Exception {
        ControlledProvider provider = new ControlledProvider();
        RecordingTransport transport = new RecordingTransport();
        WorkloadRunner runner = new WorkloadRunner(
                vertx, provider, transport, () -> "session-stop", 4);
        WorkloadRunner.ComparisonStartResult start = runner.startComparison(request(1));
        Invocation late = provider.invocations.getFirst();

        assertTrue(runner.stopComparison(start.comparisonId()));
        late.completion().complete(result("Fixed", 1L, 1L));
        await(() -> "stopped".equals(
                runner.comparison(start.comparisonId()).getString("status")));

        JsonObject comparison = runner.comparison(start.comparisonId());
        assertEquals(1, provider.invocations.size());
        assertEquals(0, comparison.getJsonArray("phases").getJsonObject(0)
                .getInteger("completed"));
        assertEquals(0L, transport.events.stream()
                .filter(event -> event.eventType() == EventType.RESPONSE_COMPLETED)
                .count());
        assertFalse(runner.stopComparison(start.comparisonId()));

        WorkloadRunner.StartResult normal = runner.start(new RunRequest(
                Workload.CHAT, 1, Scenario.HEALTHY, "safe next run", "fixed"));
        assertTrue(normal.accepted());
        assertTrue(runner.stop(normal.runId()));
    }

    @Test
    void stopLinearizesWithAPhaseTerminalBeforeTheNextPhaseCanStart() throws Exception {
        ControlledProvider provider = new ControlledProvider();
        BlockingTerminalTransport transport = new BlockingTerminalTransport();
        WorkloadRunner runner = new WorkloadRunner(
                vertx, provider, transport, () -> "session-transition-stop", 4);
        WorkloadRunner.ComparisonStartResult start = runner.startComparison(request(1));

        Thread completion = Thread.ofPlatform().start(() -> provider.invocations.getFirst()
                .completion().complete(result("Fixed", 1L, 1L)));
        assertTrue(transport.phaseTerminalEntered.await(2, TimeUnit.SECONDS));

        assertTrue(runner.stopComparison(start.comparisonId()));
        transport.releasePhaseTerminal.countDown();
        completion.join(2_000L);

        assertFalse(completion.isAlive());
        assertEquals(1, provider.invocations.size());
        assertEquals("stopped", runner.comparison(start.comparisonId()).getString("status"));
        assertEquals(0L, transport.events.stream()
                .filter(event -> event.eventType() == EventType.RUN_STARTED)
                .filter(event -> "router-default".equals(event.details().modelProfile()))
                .count());
    }

    @Test
    void synchronousProviderThrowBecomesASafeTerminalFailure() throws Exception {
        AiProvider provider = provider(ignored -> {
            throw new IllegalStateException("secret provider detail");
        });
        WorkloadRunner runner = runner(provider, 3);

        WorkloadRunner.ComparisonStartResult start = runner.startComparison(request(1));
        await(() -> "completed".equals(
                runner.comparison(start.comparisonId()).getString("status")));

        JsonObject comparison = runner.comparison(start.comparisonId());
        assertEquals(3, comparison.getInteger("providerInvocations"));
        for (Object value : comparison.getJsonArray("phases")) {
            JsonObject phase = (JsonObject) value;
            assertEquals(0, phase.getInteger("completed"));
            assertEquals(1, phase.getInteger("failed"));
            assertEquals(0.0, phase.getDouble("successRate"));
            assertNull(phase.getValue("p50LatencyMs"));
            assertNull(phase.getValue("p95LatencyMs"));
            assertNull(phase.getValue("p95DeltaMs"));
            assertNull(phase.getValue("totalTokensDelta"));
        }
        assertFalse(comparison.encode().contains("secret provider detail"));
    }

    private WorkloadRunner runner(AiProvider provider, int limit) {
        return new WorkloadRunner(
                vertx, provider, new RecordingTransport(), () -> "comparison-session", limit);
    }

    private static ComparisonRequest request(int traffic) {
        return new ComparisonRequest(
                Workload.CHAT,
                traffic,
                Scenario.HEALTHY,
                "same private prompt");
    }

    private static ProviderResult result(String family, Long inputTokens, Long outputTokens) {
        return new ProviderResult(
                "private provider output",
                "fixed",
                "safe-route",
                family,
                inputTokens,
                outputTokens);
    }

    private static AiProvider provider(
            Function<ProviderRequest, CompletableFuture<ProviderResult>> invocation) {
        return new AiProvider() {
            @Override
            public String alias() {
                return "safe-provider";
            }

            @Override
            public boolean synthetic() {
                return true;
            }

            @Override
            public List<ModelProfile> modelProfiles() {
                return PROFILES;
            }

            @Override
            public CompletableFuture<ProviderResult> invoke(ProviderRequest request) {
                return invocation.apply(request);
            }
        };
    }

    private static void await(BooleanSupplier condition) throws Exception {
        await(Duration.ofSeconds(2), condition);
    }

    private static void await(Duration timeout, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition was not met before timeout");
            }
            Thread.sleep(10L);
        }
    }

    private static final class ControlledProvider implements AiProvider {
        private final List<Invocation> invocations = new CopyOnWriteArrayList<>();

        @Override
        public String alias() {
            return "controlled-provider";
        }

        @Override
        public boolean synthetic() {
            return true;
        }

        @Override
        public List<ModelProfile> modelProfiles() {
            return PROFILES;
        }

        @Override
        public CompletableFuture<ProviderResult> invoke(ProviderRequest request) {
            CompletableFuture<ProviderResult> completion = new CompletableFuture<>();
            invocations.add(new Invocation(request, completion));
            return completion;
        }
    }

    private record Invocation(
            ProviderRequest request,
            CompletableFuture<ProviderResult> completion) {
    }

    private static final class RecordingTransport implements EventTransport {
        private final List<TelemetryEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public void start(Consumer<EventDelivery> consumer) {
        }

        @Override
        public CompletableFuture<Void> publish(TelemetryEvent event) {
            events.add(event);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean healthy() {
            return true;
        }

        @Override
        public void close() {
        }
    }

    private static final class BlockingTerminalTransport implements EventTransport {
        private final List<TelemetryEvent> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch phaseTerminalEntered = new CountDownLatch(1);
        private final CountDownLatch releasePhaseTerminal = new CountDownLatch(1);

        @Override
        public String name() {
            return "blocking-terminal";
        }

        @Override
        public void start(Consumer<EventDelivery> consumer) {
        }

        @Override
        public CompletableFuture<Void> publish(TelemetryEvent event) {
            events.add(event);
            if (event.eventType() == EventType.RUN_COMPLETED
                    && "fixed".equals(event.details().modelProfile())) {
                phaseTerminalEntered.countDown();
                try {
                    if (!releasePhaseTerminal.await(2, TimeUnit.SECONDS)) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("phase terminal was not released"));
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(exception);
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean healthy() {
            return true;
        }

        @Override
        public void close() {
        }
    }
}
