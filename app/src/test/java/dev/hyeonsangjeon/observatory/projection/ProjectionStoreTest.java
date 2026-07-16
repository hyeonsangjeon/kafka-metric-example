package dev.hyeonsangjeon.observatory.projection;

import dev.hyeonsangjeon.observatory.model.EventType;
import dev.hyeonsangjeon.observatory.model.Scenario;
import dev.hyeonsangjeon.observatory.model.TelemetryEvent;
import dev.hyeonsangjeon.observatory.model.Workload;
import dev.hyeonsangjeon.observatory.transport.EventDelivery;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionStoreTest {
    @Test
    void duplicateEventIdIsDroppedIdempotently() {
        ProjectionStore store = new ProjectionStore();
        TelemetryEvent started = TelemetryEvent.create(
                EventType.RUN_STARTED,
                store.sessionId(),
                "run-1",
                null,
                "simulated",
                Workload.CHAT,
                Scenario.DUPLICATE_DELIVERY,
                0,
                null,
                TelemetryEvent.EventDetails.run(1, true));
        TelemetryEvent event = TelemetryEvent.create(
                EventType.REQUEST_STARTED,
                store.sessionId(),
                "run-1",
                "trace-1",
                "simulated",
                Workload.CHAT,
                Scenario.DUPLICATE_DELIVERY,
                1,
                null,
                TelemetryEvent.EventDetails.request("sha256:abc", 42, 1, true));

        assertTrue(store.accept(new EventDelivery(started, 2, 9, 2)));
        assertTrue(store.accept(new EventDelivery(event, 2, 10, 1)));
        assertFalse(store.accept(new EventDelivery(event, 2, 11, 0)));

        JsonObject snapshot = store.snapshot();
        assertEquals(1L, snapshot.getJsonObject("kpis").getLong("requests"));
        assertEquals(1L, snapshot.getJsonObject("kpis").getLong("duplicatesDropped"));
        assertEquals(12L, snapshot.getJsonArray("partitions")
                .getJsonObject(0).getLong("produced"));
        assertEquals(1L, snapshot.getJsonArray("partitions")
                .getJsonObject(0).getLong("duplicatesDropped"));
    }

    @Test
    void runSequenceReordersEventsDeliveredAcrossPartitions() {
        ProjectionStore store = new ProjectionStore();
        String sessionId = store.sessionId();
        TelemetryEvent started = event(store, EventType.RUN_STARTED, null, 0,
                TelemetryEvent.EventDetails.run(1, true));
        TelemetryEvent request = event(store, EventType.REQUEST_STARTED, "trace-1", 1,
                TelemetryEvent.EventDetails.request("sha256:abc", 12, 1, true));
        TelemetryEvent response = event(store, EventType.RESPONSE_COMPLETED, "trace-1", 2,
                TelemetryEvent.EventDetails.response(
                        "sha256:abc", 12, "sha256:def", 18, 125, 1, true));
        TelemetryEvent completed = event(store, EventType.RUN_COMPLETED, null, 3,
                TelemetryEvent.EventDetails.empty(true));

        assertEquals(sessionId, started.sessionId());
        assertTrue(store.accept(new EventDelivery(response, 1, 0, 0)));
        assertTrue(store.accept(new EventDelivery(completed, 0, 0, 0)));
        assertTrue(store.accept(new EventDelivery(request, 1, 1, 0)));
        assertTrue(store.accept(new EventDelivery(started, 0, 1, 0)));

        JsonObject snapshot = store.snapshot();
        assertEquals(1L, snapshot.getJsonObject("kpis").getLong("requests"));
        assertEquals(1L, snapshot.getJsonObject("kpis").getLong("completed"));
        assertEquals("completed", snapshot.getJsonObject("lastRun").getString("status"));
        assertEquals(1, snapshot.getJsonObject("lastRun").getInteger("completedRequests"));
        assertNull(snapshot.getValue("activeRun"));
    }

    @Test
    void measuredLagReturnsToZeroAfterConsumerCatchesUp() {
        ProjectionStore store = new ProjectionStore();
        TelemetryEvent started = event(store, EventType.RUN_STARTED, null, 0,
                TelemetryEvent.EventDetails.run(1, true));
        TelemetryEvent request = event(store, EventType.REQUEST_STARTED, "trace-1", 1,
                TelemetryEvent.EventDetails.request("sha256:abc", 12, 1, true));
        TelemetryEvent response = event(store, EventType.RESPONSE_COMPLETED, "trace-1", 2,
                TelemetryEvent.EventDetails.response(
                        "sha256:abc", 12, "sha256:def", 18, 125, 1, true));
        TelemetryEvent completed = event(store, EventType.RUN_COMPLETED, null, 3,
                TelemetryEvent.EventDetails.empty(true));

        store.accept(new EventDelivery(started, 0, 0, 3));
        store.accept(new EventDelivery(request, 0, 1, 2));
        store.accept(new EventDelivery(response, 0, 2, 1));
        store.accept(new EventDelivery(completed, 0, 3, 0));

        JsonObject kpis = store.snapshot().getJsonObject("kpis");
        assertEquals(0L, kpis.getLong("consumerLag"));
        assertTrue(kpis.getLong("peakConsumerLag") > 0L);
    }

    @Test
    void eventsFromAnOldSessionCannotContaminateResetProjection() {
        ProjectionStore store = new ProjectionStore();
        String oldSession = store.sessionId();
        store.reset();
        TelemetryEvent stale = TelemetryEvent.create(
                EventType.REQUEST_STARTED,
                oldSession,
                "run-old",
                "trace-old",
                "simulated",
                Workload.CHAT,
                Scenario.HEALTHY,
                1,
                null,
                TelemetryEvent.EventDetails.request("sha256:abc", 7, 1, true));

        assertFalse(store.accept(new EventDelivery(stale, 0, 1, 0)));
        assertEquals(0L, store.snapshot().getJsonObject("kpis").getLong("requests"));
    }

    @Test
    void routingAggregateAndTracesUsePrivacySafeModelMetadata() {
        ProjectionStore store = new ProjectionStore();
        TelemetryEvent started = event(store, EventType.RUN_STARTED, null, 0,
                TelemetryEvent.EventDetails.run(2, true, "router-balanced", "balanced"));
        TelemetryEvent firstRequest = event(store, EventType.REQUEST_STARTED, "trace-fast", 1,
                TelemetryEvent.EventDetails.request(
                        "sha256:abc", 12, 1, true, "router-balanced", "balanced"));
        TelemetryEvent firstResponse = event(store, EventType.RESPONSE_COMPLETED, "trace-fast", 2,
                TelemetryEvent.EventDetails.response(
                        "sha256:abc", 12, "sha256:def", 18, 80, 1, true,
                        "router-balanced", "balanced", "fast", "Fast", 4L, 5L));
        TelemetryEvent secondRequest = event(store, EventType.REQUEST_STARTED, "trace-reasoning", 3,
                TelemetryEvent.EventDetails.request(
                        "sha256:ghi", 16, 1, true, "router-balanced", "balanced"));
        TelemetryEvent secondResponse = event(store, EventType.RESPONSE_COMPLETED, "trace-reasoning", 4,
                TelemetryEvent.EventDetails.response(
                        "sha256:ghi", 16, "sha256:jkl", 30, 260, 1, true,
                        "router-balanced", "balanced", "reasoning", "Reasoning", 6L, 8L));
        TelemetryEvent completed = event(store, EventType.RUN_COMPLETED, null, 5,
                TelemetryEvent.EventDetails.empty(true, "router-balanced", "balanced"));

        store.accept(new EventDelivery(started, 0, 0, 0));
        store.accept(new EventDelivery(firstRequest, 0, 1, 0));
        store.accept(new EventDelivery(firstResponse, 0, 2, 0));
        store.accept(new EventDelivery(secondRequest, 0, 3, 0));
        store.accept(new EventDelivery(secondResponse, 0, 4, 0));
        store.accept(new EventDelivery(completed, 0, 5, 0));

        JsonObject snapshot = store.snapshot();
        JsonObject routing = snapshot.getJsonObject("routing");
        assertEquals("router-balanced", routing.getString("profileId"));
        assertEquals("balanced", routing.getString("strategy"));
        assertEquals(2, routing.getInteger("totalRequests"));
        assertEquals(2, routing.getJsonArray("routes").size());
        assertEquals(50.0, routing.getJsonArray("routes").getJsonObject(0).getDouble("share"));
        assertEquals(100.0, routing.getJsonArray("routes").getJsonObject(0).getDouble("successRate"));
        assertEquals(10L, snapshot.getJsonObject("kpis").getLong("inputTokens"));
        assertEquals(13L, snapshot.getJsonObject("kpis").getLong("outputTokens"));

        JsonObject newestTrace = snapshot.getJsonArray("traces").getJsonObject(0);
        assertEquals("router-balanced", newestTrace.getString("modelProfile"));
        assertEquals("reasoning", newestTrace.getString("selectedRoute"));
        assertEquals("Reasoning", newestTrace.getString("modelFamily"));
    }

    @Test
    void emptySnapshotHasAnExplicitIdleRoutingShape() {
        JsonObject routing = new ProjectionStore().snapshot().getJsonObject("routing");

        assertNull(routing.getValue("profileId"));
        assertNull(routing.getValue("strategy"));
        assertEquals(0, routing.getInteger("totalRequests"));
        assertTrue(routing.getJsonArray("routes").isEmpty());
    }

    private static TelemetryEvent event(
            ProjectionStore store,
            EventType type,
            String traceId,
            long sequence,
            TelemetryEvent.EventDetails details) {
        return TelemetryEvent.create(
                type,
                store.sessionId(),
                "run-1",
                traceId,
                "simulated",
                Workload.CHAT,
                Scenario.HEALTHY,
                sequence,
                null,
                details);
    }
}
