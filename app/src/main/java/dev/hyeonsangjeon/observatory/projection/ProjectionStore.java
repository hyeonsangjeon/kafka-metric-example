package dev.hyeonsangjeon.observatory.projection;

import dev.hyeonsangjeon.observatory.model.EventType;
import dev.hyeonsangjeon.observatory.model.TelemetryEvent;
import dev.hyeonsangjeon.observatory.transport.EventDelivery;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Thread-safe, event-driven dashboard projection. Event IDs are the idempotency key.
 */
public final class ProjectionStore {
    private static final int MAX_DEDUPE_IDS = 50_000;
    private static final int MAX_TRACES = 250;
    private static final int MAX_LATENCIES = 2_000;
    private static final long RATE_WINDOW_MS = 10_000L;

    private String sessionId = UUID.randomUUID().toString();
    private final LinkedHashMap<String, Boolean> seenEventIds = new LinkedHashMap<>();
    private final LinkedHashMap<String, TraceProjection> traces = new LinkedHashMap<>();
    private final Map<Integer, PartitionProjection> partitions = new LinkedHashMap<>();
    private final Map<String, Long> nextSequenceByRun = new LinkedHashMap<>();
    private final Map<String, TreeMap<Long, PendingEvent>> pendingByRun = new LinkedHashMap<>();
    private final List<Long> latencies = new ArrayList<>();
    private final Deque<Long> acceptedEventTimes = new ArrayDeque<>();

    private long requests;
    private long completed;
    private long failed;
    private long throttled;
    private long cancelled;
    private long inputChars;
    private long outputChars;
    private long inputTokens;
    private long outputTokens;
    private long duplicatesDropped;
    private long peakConsumerLag;
    private String lastEventAt;
    private long lastEventEmittedMs;
    private RunProjection activeRun;
    private RunProjection lastRun;

    public synchronized String sessionId() {
        return sessionId;
    }

    /**
     * @return true when the event changed the logical projection; false for a duplicate event ID.
     */
    public synchronized boolean accept(EventDelivery delivery) {
        TelemetryEvent event = delivery.event();
        if (!sessionId.equals(event.sessionId())) {
            return false;
        }
        PartitionProjection partition = partitions.computeIfAbsent(
                delivery.partition(), PartitionProjection::new);
        partition.produced = Math.max(
                partition.produced, delivery.offset() + delivery.observedLag() + 1L);
        partition.consumed = Math.max(partition.consumed, delivery.offset() + 1L);
        partition.lastOffset = delivery.offset();
        partition.lag = delivery.observedLag();
        partition.peakLag = Math.max(partition.peakLag, delivery.observedLag());
        peakConsumerLag = Math.max(peakConsumerLag, totalLag());

        if (seenEventIds.containsKey(event.eventId())) {
            duplicatesDropped++;
            partition.duplicatesDropped++;
            return false;
        }
        rememberEventId(event.eventId());
        rememberEventTime();
        long emittedAtMs = emittedAtMillis(event.emittedAt());
        if (lastEventEmittedMs == 0L || emittedAtMs >= lastEventEmittedMs) {
            lastEventAt = event.emittedAt();
            lastEventEmittedMs = emittedAtMs;
        }
        bufferAndApply(event, partition);
        return true;
    }

    private void bufferAndApply(TelemetryEvent event, PartitionProjection partition) {
        long expected = nextSequenceByRun.getOrDefault(event.runId(), 0L);
        if (event.sequence() < expected) {
            return;
        }
        TreeMap<Long, PendingEvent> pending = pendingByRun.computeIfAbsent(
                event.runId(), ignored -> new TreeMap<>());
        pending.putIfAbsent(event.sequence(), new PendingEvent(event, partition));

        boolean terminalApplied = false;
        PendingEvent next;
        while ((next = pending.remove(expected)) != null) {
            apply(next.event(), next.partition());
            EventType type = next.event().eventType();
            terminalApplied = terminalApplied
                    || type == EventType.RUN_COMPLETED
                    || type == EventType.RUN_STOPPED;
            expected++;
        }
        if (terminalApplied && pending.isEmpty()) {
            pendingByRun.remove(event.runId());
            nextSequenceByRun.remove(event.runId());
        } else {
            nextSequenceByRun.put(event.runId(), expected);
        }
    }

    private static long emittedAtMillis(String emittedAt) {
        try {
            return Instant.parse(emittedAt).toEpochMilli();
        } catch (RuntimeException ignored) {
            return System.currentTimeMillis();
        }
    }

    private void apply(TelemetryEvent event, PartitionProjection partition) {
        TelemetryEvent.EventDetails details = event.details();
        switch (event.eventType()) {
            case RUN_STARTED -> activeRun = new RunProjection(
                    event.runId(), event.workload(), event.scenario(),
                    details == null || details.requestedTraffic() == null ? 0 : details.requestedTraffic(),
                    "running", event.emittedAt(), null, 0,
                    details == null ? null : details.modelProfile(),
                    details == null ? null : details.routeStrategy());
            case REQUEST_STARTED -> {
                requests++;
                if (details != null && details.promptChars() != null) {
                    inputChars += details.promptChars();
                }
                TraceProjection trace = traceFor(event);
                trace.status = "running";
                trace.startedAt = event.emittedAt();
                trace.promptHash = details == null ? null : details.promptHash();
                trace.inputChars = details == null || details.promptChars() == null ? 0 : details.promptChars();
                trace.attempt = details == null || details.attempt() == null ? 1 : details.attempt();
                trace.modelProfile = details == null ? null : details.modelProfile();
                trace.routeStrategy = details == null ? null : details.routeStrategy();
                trace.partition = partition.partition;
            }
            case RESPONSE_COMPLETED -> {
                completed++;
                if (details != null && details.responseChars() != null) {
                    outputChars += details.responseChars();
                }
                completeTrace(event, details, partition, "completed", null);
            }
            case RESPONSE_FAILED -> {
                failed++;
                completeTrace(event, details, partition, "failed",
                        details == null ? "MODEL_REQUEST_FAILED" : details.errorCode());
            }
            case RESPONSE_THROTTLED -> {
                throttled++;
                TraceProjection trace = traceFor(event);
                trace.status = "retrying";
                trace.completedAt = event.emittedAt();
                trace.partition = partition.partition;
                trace.errorCode = details == null ? "MODEL_RATE_LIMITED" : details.errorCode();
                if (details != null) {
                    trace.latencyMs = details.latencyMs() == null ? 0 : details.latencyMs();
                    trace.attempt = details.attempt() == null ? trace.attempt : details.attempt();
                    copyRoutingDetails(trace, details);
                }
            }
            case CONSUMER_LAGGED -> {
                long observed = details == null || details.observedLag() == null ? 0 : details.observedLag();
                partition.peakLag = Math.max(partition.peakLag, observed);
                peakConsumerLag = Math.max(peakConsumerLag, observed);
            }
            case RUN_COMPLETED -> finishRun(event, "completed");
            case RUN_STOPPED -> {
                for (TraceProjection trace : traces.values()) {
                    if (trace.runId.equals(event.runId())
                            && ("running".equals(trace.status)
                            || "retrying".equals(trace.status)
                            || "queued".equals(trace.status))) {
                        trace.status = "stopped";
                        trace.completedAt = event.emittedAt();
                        cancelled++;
                    }
                }
                finishRun(event, "stopped");
            }
        }
    }

    private void completeTrace(
            TelemetryEvent event,
            TelemetryEvent.EventDetails details,
            PartitionProjection partition,
            String status,
            String errorCode) {
        TraceProjection trace = traceFor(event);
        trace.status = status;
        trace.completedAt = event.emittedAt();
        trace.partition = partition.partition;
        trace.errorCode = errorCode;
        if (details != null) {
            trace.latencyMs = details.latencyMs() == null ? 0 : details.latencyMs();
            trace.outputChars = details.responseChars() == null ? 0 : details.responseChars();
            trace.attempt = details.attempt() == null ? trace.attempt : details.attempt();
            if (trace.promptHash == null) {
                trace.promptHash = details.promptHash();
            }
            if (trace.inputChars == 0 && details.promptChars() != null) {
                trace.inputChars = details.promptChars();
            }
            copyRoutingDetails(trace, details);
            if (details.inputTokens() != null) {
                inputTokens += details.inputTokens();
            }
            if (details.outputTokens() != null) {
                outputTokens += details.outputTokens();
            }
        }
        addLatency(trace.latencyMs);
        if (activeRun != null && activeRun.runId.equals(event.runId())) {
            activeRun.completedRequests++;
        }
    }

    private void finishRun(TelemetryEvent event, String status) {
        RunProjection run = activeRun != null && activeRun.runId.equals(event.runId())
                ? activeRun
                : new RunProjection(event.runId(), event.workload(), event.scenario(),
                        0, status, event.emittedAt(), event.emittedAt(), 0,
                        event.details() == null ? null : event.details().modelProfile(),
                        event.details() == null ? null : event.details().routeStrategy());
        run.status = status;
        run.completedAt = event.emittedAt();
        lastRun = run;
        activeRun = null;
    }

    private static void copyRoutingDetails(
            TraceProjection trace,
            TelemetryEvent.EventDetails details) {
        if (details.modelProfile() != null) {
            trace.modelProfile = details.modelProfile();
        }
        if (details.routeStrategy() != null) {
            trace.routeStrategy = details.routeStrategy();
        }
        if (details.selectedRoute() != null) {
            trace.selectedRoute = details.selectedRoute();
        }
        if (details.modelFamily() != null) {
            trace.modelFamily = details.modelFamily();
        }
        if (details.inputTokens() != null) {
            trace.inputTokens = details.inputTokens();
        }
        if (details.outputTokens() != null) {
            trace.outputTokens = details.outputTokens();
        }
    }

    private TraceProjection traceFor(TelemetryEvent event) {
        if (event.traceId() == null) {
            throw new IllegalArgumentException("request/response event requires trace_id");
        }
        TraceProjection existing = traces.get(event.traceId());
        if (existing != null) {
            return existing;
        }
        if (traces.size() >= MAX_TRACES) {
            String oldest = traces.keySet().iterator().next();
            traces.remove(oldest);
        }
        TraceProjection created = new TraceProjection(
                event.traceId(), event.runId(), event.workload(), event.scenario(), event.providerAlias());
        traces.put(event.traceId(), created);
        return created;
    }

    private void rememberEventId(String eventId) {
        if (seenEventIds.size() >= MAX_DEDUPE_IDS) {
            seenEventIds.remove(seenEventIds.keySet().iterator().next());
        }
        seenEventIds.put(eventId, Boolean.TRUE);
    }

    private void addLatency(long latency) {
        if (latency < 0) {
            return;
        }
        if (latencies.size() >= MAX_LATENCIES) {
            latencies.removeFirst();
        }
        latencies.add(latency);
    }

    private void rememberEventTime() {
        long now = System.currentTimeMillis();
        acceptedEventTimes.addLast(now);
        trimRateWindow(now);
    }

    private void trimRateWindow(long now) {
        while (!acceptedEventTimes.isEmpty() && acceptedEventTimes.peekFirst() < now - RATE_WINDOW_MS) {
            acceptedEventTimes.removeFirst();
        }
    }

    public synchronized JsonObject snapshot() {
        long now = System.currentTimeMillis();
        trimRateWindow(now);
        List<Long> sortedLatencies = latencies.stream().sorted().toList();
        long currentLag = totalLag();

        JsonObject kpis = new JsonObject()
                .put("requests", requests)
                .put("completed", completed)
                .put("failed", failed)
                .put("throttled", throttled)
                .put("cancelled", cancelled)
                .put("inFlight", Math.max(0L, requests - completed - failed - cancelled))
                .put("inputChars", inputChars)
                .put("outputChars", outputChars)
                .put("inputTokens", inputTokens)
                .put("outputTokens", outputTokens)
                .put("p50LatencyMs", percentile(sortedLatencies, 0.50))
                .put("p95LatencyMs", percentile(sortedLatencies, 0.95))
                .put("eventsPerSecond", eventsPerSecond(now))
                .put("duplicatesDropped", duplicatesDropped)
                .put("consumerLag", currentLag)
                .put("peakConsumerLag", peakConsumerLag);

        JsonArray traceArray = new JsonArray();
        List<TraceProjection> newestFirst = new ArrayList<>(traces.values());
        newestFirst.sort(Comparator.comparing(
                (TraceProjection trace) -> trace.startedAt == null ? "" : trace.startedAt).reversed());
        newestFirst.forEach(trace -> traceArray.add(trace.toJson()));

        JsonArray partitionArray = new JsonArray();
        partitions.values().stream()
                .sorted(Comparator.comparingInt(partition -> partition.partition))
                .map(PartitionProjection::toJson)
                .forEach(partitionArray::add);

        JsonObject freshness = new JsonObject()
                .put("lastEventAt", lastEventAt)
                .put("ageMs", lastEventEmittedMs == 0L ? null : Math.max(0L, now - lastEventEmittedMs));

        return new JsonObject()
                .put("sessionId", sessionId)
                .put("generatedAt", Instant.now().toString())
                .put("freshness", freshness)
                .put("activeRun", activeRun == null ? null : activeRun.toJson())
                .put("lastRun", lastRun == null ? null : lastRun.toJson())
                .put("kpis", kpis)
                .put("routing", routingSnapshot())
                .put("traces", traceArray)
                .put("partitions", partitionArray);
    }

    private JsonObject routingSnapshot() {
        RunProjection run = activeRun != null ? activeRun : lastRun;
        if (run == null) {
            return new JsonObject()
                    .put("profileId", (String) null)
                    .put("strategy", (String) null)
                    .put("totalRequests", 0)
                    .put("explanation", "Run a workload to observe routing decisions.")
                    .put("routes", new JsonArray());
        }

        List<TraceProjection> runTraces = traces.values().stream()
                .filter(trace -> trace.runId.equals(run.runId))
                .toList();
        Map<String, RouteAggregate> routes = new LinkedHashMap<>();
        for (TraceProjection trace : runTraces) {
            if (trace.selectedRoute == null) {
                continue;
            }
            RouteAggregate route = routes.computeIfAbsent(
                    trace.selectedRoute,
                    ignored -> new RouteAggregate(
                            trace.selectedRoute,
                            trace.modelFamily == null ? "Unresolved" : trace.modelFamily));
            route.requests++;
            if ("completed".equals(trace.status)) {
                route.successes++;
            }
            route.latencies.add(trace.latencyMs);
        }

        JsonArray routeArray = new JsonArray();
        routes.values().stream()
                .sorted(Comparator.comparingLong((RouteAggregate route) -> route.requests)
                        .reversed()
                        .thenComparing(route -> route.label))
                .map(route -> route.toJson(runTraces.size()))
                .forEach(routeArray::add);

        return new JsonObject()
                .put("profileId", run.modelProfile)
                .put("strategy", run.routeStrategy)
                .put("totalRequests", runTraces.size())
                .put("explanation", routingExplanation(run.routeStrategy))
                .put("routes", routeArray);
    }

    private static String routingExplanation(String strategy) {
        if (strategy == null) {
            return "Routing metadata was not available for this run.";
        }
        return switch (strategy) {
            case "fixed" -> "Every request used the same configured execution path.";
            case "balanced" -> "The balanced router selected a model for each workload request.";
            case "cost" -> "The cost-oriented router selected a model for each workload request.";
            case "quality" -> "The quality-oriented router selected a model for each workload request.";
            default -> "The selected execution profile recorded a route for each request.";
        };
    }

    private double eventsPerSecond(long now) {
        if (acceptedEventTimes.isEmpty()) {
            return 0.0;
        }
        long elapsed = Math.max(1_000L, now - acceptedEventTimes.peekFirst());
        return Math.round((acceptedEventTimes.size() * 1_000.0 / elapsed) * 10.0) / 10.0;
    }

    private long totalLag() {
        return partitions.values().stream().mapToLong(partition -> partition.lag).sum();
    }

    private static long percentile(List<Long> sorted, double quantile) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(quantile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    public synchronized void reset() {
        sessionId = UUID.randomUUID().toString();
        seenEventIds.clear();
        traces.clear();
        partitions.clear();
        nextSequenceByRun.clear();
        pendingByRun.clear();
        latencies.clear();
        acceptedEventTimes.clear();
        requests = 0;
        completed = 0;
        failed = 0;
        throttled = 0;
        cancelled = 0;
        inputChars = 0;
        outputChars = 0;
        inputTokens = 0;
        outputTokens = 0;
        duplicatesDropped = 0;
        peakConsumerLag = 0;
        lastEventAt = null;
        lastEventEmittedMs = 0L;
        activeRun = null;
        lastRun = null;
    }

    private record PendingEvent(TelemetryEvent event, PartitionProjection partition) {
    }

    private static final class TraceProjection {
        private final String traceId;
        private final String runId;
        private final String workload;
        private final String scenario;
        private final String providerAlias;
        private String status = "queued";
        private String startedAt;
        private String completedAt;
        private long latencyMs;
        private int inputChars;
        private int outputChars;
        private String promptHash;
        private int partition = -1;
        private int attempt = 1;
        private String errorCode;
        private String modelProfile;
        private String routeStrategy;
        private String selectedRoute;
        private String modelFamily;
        private Long inputTokens;
        private Long outputTokens;

        private TraceProjection(
                String traceId,
                String runId,
                String workload,
                String scenario,
                String providerAlias) {
            this.traceId = traceId;
            this.runId = runId;
            this.workload = workload;
            this.scenario = scenario;
            this.providerAlias = providerAlias;
        }

        private JsonObject toJson() {
            return new JsonObject()
                    .put("traceId", traceId)
                    .put("runId", runId)
                    .put("workload", workload)
                    .put("scenario", scenario)
                    .put("providerAlias", providerAlias)
                    .put("status", status)
                    .put("startedAt", startedAt)
                    .put("completedAt", completedAt)
                    .put("latencyMs", latencyMs)
                    .put("inputChars", inputChars)
                    .put("outputChars", outputChars)
                    .put("promptHash", promptHash)
                    .put("partition", partition)
                    .put("attempt", attempt)
                    .put("errorCode", errorCode)
                    .put("modelProfile", modelProfile)
                    .put("routeStrategy", routeStrategy)
                    .put("selectedRoute", selectedRoute)
                    .put("modelFamily", modelFamily)
                    .put("inputTokens", inputTokens)
                    .put("outputTokens", outputTokens);
        }
    }

    private static final class PartitionProjection {
        private final int partition;
        private long produced;
        private long consumed;
        private long lag;
        private long peakLag;
        private long duplicatesDropped;
        private long lastOffset = -1;

        private PartitionProjection(int partition) {
            this.partition = partition;
        }

        private JsonObject toJson() {
            return new JsonObject()
                    .put("partition", partition)
                    .put("produced", produced)
                    .put("consumed", consumed)
                    .put("lag", lag)
                    .put("peakLag", peakLag)
                    .put("duplicatesDropped", duplicatesDropped)
                    .put("lastOffset", lastOffset);
        }
    }

    private static final class RunProjection {
        private final String runId;
        private final String workload;
        private final String scenario;
        private final int traffic;
        private String status;
        private final String startedAt;
        private String completedAt;
        private int completedRequests;
        private final String modelProfile;
        private final String routeStrategy;

        private RunProjection(
                String runId,
                String workload,
                String scenario,
                int traffic,
                String status,
                String startedAt,
                String completedAt,
                int completedRequests,
                String modelProfile,
                String routeStrategy) {
            this.runId = runId;
            this.workload = workload;
            this.scenario = scenario;
            this.traffic = traffic;
            this.status = status;
            this.startedAt = startedAt;
            this.completedAt = completedAt;
            this.completedRequests = completedRequests;
            this.modelProfile = modelProfile;
            this.routeStrategy = routeStrategy;
        }

        private JsonObject toJson() {
            return new JsonObject()
                    .put("runId", runId)
                    .put("workload", workload)
                    .put("scenario", scenario)
                    .put("traffic", traffic)
                    .put("status", status)
                    .put("startedAt", startedAt)
                    .put("completedAt", completedAt)
                    .put("completedRequests", completedRequests)
                    .put("modelProfile", modelProfile)
                    .put("routeStrategy", routeStrategy);
        }
    }

    private static final class RouteAggregate {
        private final String id;
        private final String label;
        private final List<Long> latencies = new ArrayList<>();
        private long requests;
        private long successes;

        private RouteAggregate(String id, String label) {
            this.id = id;
            this.label = label;
        }

        private JsonObject toJson(int totalRequests) {
            List<Long> sortedLatencies = latencies.stream().sorted().toList();
            return new JsonObject()
                    .put("id", id)
                    .put("label", label)
                    .put("requests", requests)
                    .put("share", percentage(requests, totalRequests))
                    .put("p95LatencyMs", percentile(sortedLatencies, 0.95))
                    .put("successRate", percentage(successes, requests));
        }

        private static double percentage(long numerator, long denominator) {
            if (denominator == 0L) {
                return 0.0;
            }
            return Math.round((numerator * 100_000.0 / denominator)) / 1_000.0;
        }
    }
}
