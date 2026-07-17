package dev.hyeonsangjeon.observatory.runner;

import dev.hyeonsangjeon.observatory.model.EventType;
import dev.hyeonsangjeon.observatory.model.RunRequest;
import dev.hyeonsangjeon.observatory.model.Scenario;
import dev.hyeonsangjeon.observatory.model.TelemetryEvent;
import dev.hyeonsangjeon.observatory.model.Workload;
import dev.hyeonsangjeon.observatory.provider.AiProvider;
import dev.hyeonsangjeon.observatory.provider.ProviderFailure;
import dev.hyeonsangjeon.observatory.provider.ProviderRequest;
import dev.hyeonsangjeon.observatory.provider.ProviderResult;
import dev.hyeonsangjeon.observatory.transport.EventDelivery;
import dev.hyeonsangjeon.observatory.transport.EventTransport;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkloadRunnerTest {
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
    void providerInvocationDoesNotWaitForPendingOrFailedTelemetryPublication() {
        assertProviderInvoked(new CompletableFuture<>());
        assertProviderInvoked(CompletableFuture.failedFuture(
                new IllegalStateException("telemetry unavailable")));
    }

    @Test
    void throttledProviderFailureEmitsSignalAndRetriesExactlyOnceToSuccess() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AiProvider provider = new FakeProvider(request -> {
            if (providerCalls.incrementAndGet() == 1) {
                return CompletableFuture.failedFuture(
                        new ProviderFailure("FOUNDRY_RATE_LIMITED", true, null));
            }
            return CompletableFuture.completedFuture(new ProviderResult("recovered"));
        });
        RecordingTransport transport = new RecordingTransport(
                ignored -> CompletableFuture.completedFuture(null));
        WorkloadRunner runner = new WorkloadRunner(
                vertx, provider, transport, () -> "session-retry");

        WorkloadRunner.StartResult start = runner.start(request());

        assertTrue(start.accepted());
        transport.runCompleted().get(2, TimeUnit.SECONDS);
        assertEquals(2, providerCalls.get());
        assertEquals(
                List.of(
                        EventType.RUN_STARTED,
                        EventType.REQUEST_STARTED,
                        EventType.RESPONSE_THROTTLED,
                        EventType.RESPONSE_COMPLETED,
                        EventType.RUN_COMPLETED),
                transport.events().stream().map(TelemetryEvent::eventType).toList());

        TelemetryEvent throttled = transport.only(EventType.RESPONSE_THROTTLED);
        assertEquals("FOUNDRY_RATE_LIMITED", throttled.details().errorCode());
        assertEquals(1, throttled.details().attempt());
        TelemetryEvent completed = transport.only(EventType.RESPONSE_COMPLETED);
        assertEquals(2, completed.details().attempt());
        assertEquals("fixed", completed.details().modelProfile());
        assertEquals("fixed", completed.details().routeStrategy());
        assertEquals("fixed", completed.details().selectedRoute());
    }

    @Test
    void unsupportedExecutionProfileIsRejectedBeforeRunStarts() {
        RecordingTransport transport = new RecordingTransport(
                ignored -> CompletableFuture.completedFuture(null));
        WorkloadRunner runner = new WorkloadRunner(
                vertx,
                new FakeProvider(request -> CompletableFuture.completedFuture(new ProviderResult("unused"))),
                transport,
                () -> "session-profile-validation");
        RunRequest invalid = new RunRequest(
                Workload.CHAT, 1, Scenario.HEALTHY, "Explain stream recovery.", "router-balanced");

        assertThrows(IllegalArgumentException.class, () -> runner.start(invalid));
        assertTrue(transport.events().isEmpty());
    }

    @Test
    void foundryRunRetryCannotExceedTheConfiguredProviderInvocationLimit() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AiProvider provider = new AiProvider() {
            @Override
            public String alias() {
                return "foundry";
            }

            @Override
            public boolean synthetic() {
                return false;
            }

            @Override
            public CompletableFuture<ProviderResult> invoke(ProviderRequest request) {
                providerCalls.incrementAndGet();
                return CompletableFuture.failedFuture(
                        new ProviderFailure("MODEL_RATE_LIMITED", true, null));
            }
        };
        RecordingTransport transport = new RecordingTransport(
                ignored -> CompletableFuture.completedFuture(null));
        WorkloadRunner runner = new WorkloadRunner(
                vertx, provider, transport, () -> "session-cloud-budget", 1);

        WorkloadRunner.StartResult start = runner.start(request());

        assertTrue(start.accepted());
        transport.runCompleted().get(2, TimeUnit.SECONDS);
        assertEquals(1, providerCalls.get());
        assertEquals("MODEL_REQUEST_BUDGET_EXHAUSTED",
                transport.only(EventType.RESPONSE_FAILED).details().errorCode());
    }

    private void assertProviderInvoked(CompletableFuture<Void> publishResult) {
        AtomicInteger providerCalls = new AtomicInteger();
        AiProvider provider = new FakeProvider(request -> {
            providerCalls.incrementAndGet();
            return new CompletableFuture<>();
        });
        RecordingTransport transport = new RecordingTransport(ignored -> publishResult);
        WorkloadRunner runner = new WorkloadRunner(
                vertx, provider, transport, () -> "session-publication-boundary");

        WorkloadRunner.StartResult start = runner.start(request());

        assertTrue(start.accepted());
        assertEquals(1, providerCalls.get());
        assertEquals(
                List.of(EventType.RUN_STARTED, EventType.REQUEST_STARTED),
                transport.events().stream().map(TelemetryEvent::eventType).toList());
    }

    private static RunRequest request() {
        return new RunRequest(Workload.CHAT, 1, Scenario.HEALTHY, "Explain stream recovery.");
    }

    private static final class FakeProvider implements AiProvider {
        private final Function<ProviderRequest, CompletableFuture<ProviderResult>> invocation;

        private FakeProvider(Function<ProviderRequest, CompletableFuture<ProviderResult>> invocation) {
            this.invocation = invocation;
        }

        @Override
        public String alias() {
            return "fake-provider";
        }

        @Override
        public boolean synthetic() {
            return true;
        }

        @Override
        public CompletableFuture<ProviderResult> invoke(ProviderRequest request) {
            return invocation.apply(request);
        }
    }

    private static final class RecordingTransport implements EventTransport {
        private final List<TelemetryEvent> events = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Void> runCompleted = new CompletableFuture<>();
        private final Function<TelemetryEvent, CompletableFuture<Void>> publication;

        private RecordingTransport(Function<TelemetryEvent, CompletableFuture<Void>> publication) {
            this.publication = publication;
        }

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
            if (event.eventType() == EventType.RUN_COMPLETED) {
                runCompleted.complete(null);
            }
            return publication.apply(event);
        }

        @Override
        public boolean healthy() {
            return true;
        }

        @Override
        public void close() {
        }

        private List<TelemetryEvent> events() {
            return List.copyOf(events);
        }

        private CompletableFuture<Void> runCompleted() {
            return runCompleted;
        }

        private TelemetryEvent only(EventType type) {
            return events.stream().filter(event -> event.eventType() == type).findFirst().orElseThrow();
        }
    }
}
