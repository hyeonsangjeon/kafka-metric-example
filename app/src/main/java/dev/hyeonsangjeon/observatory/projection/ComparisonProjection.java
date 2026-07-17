package dev.hyeonsangjeon.observatory.projection;

import dev.hyeonsangjeon.observatory.model.ComparisonRequest;
import dev.hyeonsangjeon.observatory.provider.ModelProfile;
import dev.hyeonsangjeon.observatory.provider.ProviderResult;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Privacy-safe, in-memory view of one comparison. Prompt, output, hashes, trace IDs, endpoints,
 * and deployment identifiers never enter this projection.
 */
public final class ComparisonProjection {
    private final String comparisonId;
    private final String workload;
    private final String scenario;
    private final int trafficPerProfile;
    private final List<String> profiles;
    private final List<PhaseProjection> phases;
    private final int plannedRequests;
    private final int providerInvocationLimit;
    private final String startedAt;

    private String status = "running";
    private String currentProfile;
    private String completedAt;
    private int providerInvocations;

    public ComparisonProjection(
            String comparisonId,
            ComparisonRequest request,
            List<ModelProfile> modelProfiles,
            int providerInvocationLimit) {
        this.comparisonId = Objects.requireNonNull(comparisonId, "comparisonId");
        ComparisonRequest source = Objects.requireNonNull(request, "request");
        this.workload = source.workload().wireName();
        this.scenario = source.scenario().wireName();
        this.trafficPerProfile = source.trafficPerProfile();
        this.providerInvocationLimit = providerInvocationLimit;
        this.profiles = modelProfiles.stream().map(ModelProfile::id).toList();
        this.phases = modelProfiles.stream()
                .map(profile -> new PhaseProjection(profile, trafficPerProfile))
                .toList();
        this.plannedRequests = Math.multiplyExact(trafficPerProfile, modelProfiles.size());
        this.startedAt = Instant.now().toString();
    }

    public String comparisonId() {
        return comparisonId;
    }

    public synchronized boolean terminal() {
        return !"running".equals(status);
    }

    public synchronized void startPhase(int index, String runId) {
        if (terminal()) {
            return;
        }
        PhaseProjection phase = phases.get(index);
        phase.status = "running";
        phase.runId = runId;
        currentProfile = phase.profile;
    }

    public synchronized void providerInvoked() {
        if (!terminal()) {
            providerInvocations++;
        }
    }

    public synchronized void retried(int index) {
        if (!terminal() && "running".equals(phases.get(index).status)) {
            phases.get(index).retries++;
        }
    }

    public synchronized void succeeded(int index, long latencyMs, ProviderResult result) {
        PhaseProjection phase = phases.get(index);
        if (terminal() || !"running".equals(phase.status)) {
            return;
        }
        phase.completed++;
        phase.latencies.add(Math.max(0L, latencyMs));
        if (result.inputTokens() != null) {
            phase.inputTokens += result.inputTokens();
        }
        if (result.outputTokens() != null) {
            phase.outputTokens += result.outputTokens();
        }
        if (result.inputTokens() != null && result.outputTokens() != null) {
            phase.tokenSamples++;
        }
        phase.models.merge(result.modelFamily(), 1L, Long::sum);
    }

    public synchronized void failed(int index, long latencyMs) {
        PhaseProjection phase = phases.get(index);
        if (terminal() || !"running".equals(phase.status)) {
            return;
        }
        phase.failed++;
    }

    public synchronized void completePhase(int index) {
        PhaseProjection phase = phases.get(index);
        if (terminal() || !"running".equals(phase.status)) {
            return;
        }
        phase.status = "completed";
        currentProfile = null;
    }

    public synchronized void complete() {
        if (terminal()) {
            return;
        }
        status = "completed";
        currentProfile = null;
        completedAt = Instant.now().toString();
    }

    public synchronized void stop() {
        if (terminal()) {
            return;
        }
        status = "stopped";
        currentProfile = null;
        completedAt = Instant.now().toString();
        for (PhaseProjection phase : phases) {
            if ("running".equals(phase.status)) {
                phase.status = "stopped";
            } else if ("queued".equals(phase.status)) {
                phase.status = "stopped";
            }
        }
    }

    public synchronized JsonObject receiptJson() {
        return new JsonObject()
                .put("comparisonId", comparisonId)
                .put("status", status)
                .put("profiles", new JsonArray(profiles))
                .put("trafficPerProfile", trafficPerProfile)
                .put("plannedRequests", plannedRequests)
                .put("providerInvocationLimit", providerInvocationLimit);
    }

    public synchronized JsonObject toJson() {
        PhaseProjection baseline = phases.getFirst();
        JsonArray phaseArray = new JsonArray();
        for (PhaseProjection phase : phases) {
            phaseArray.add(phase.toJson(baseline));
        }
        return new JsonObject()
                .put("comparisonId", comparisonId)
                .put("status", status)
                .put("workload", workload)
                .put("scenario", scenario)
                .put("trafficPerProfile", trafficPerProfile)
                .put("profiles", new JsonArray(profiles))
                .put("currentProfile", currentProfile)
                .put("plannedRequests", plannedRequests)
                .put("providerInvocations", providerInvocations)
                .put("providerInvocationLimit", providerInvocationLimit)
                .put("startedAt", startedAt)
                .put("completedAt", completedAt)
                .put("phases", phaseArray);
    }

    private static Long percentile(List<Long> values, double quantile) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(quantile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static Long delta(Long value, Long baseline) {
        if (value == null || baseline == null) {
            return null;
        }
        return value - baseline;
    }

    private static Double percentDelta(Long value, Long baseline) {
        if (value == null || baseline == null || baseline == 0L) {
            return null;
        }
        return rounded(((value - baseline) * 100.0) / baseline);
    }

    private static double rounded(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static final class PhaseProjection {
        private final String profile;
        private final String label;
        private final String kind;
        private final String routeStrategy;
        private final int requested;
        private final List<Long> latencies = new ArrayList<>();
        private final Map<String, Long> models = new LinkedHashMap<>();

        private String status = "queued";
        private String runId;
        private int completed;
        private int failed;
        private int retries;
        private long inputTokens;
        private long outputTokens;
        private int tokenSamples;

        private PhaseProjection(ModelProfile profile, int requested) {
            this.profile = profile.id();
            this.label = profile.label();
            this.kind = profile.router() ? "router" : "fixed";
            this.routeStrategy = profile.strategy();
            this.requested = requested;
        }

        private JsonObject toJson(PhaseProjection baseline) {
            Long p50 = percentile(latencies, 0.50);
            Long p95 = percentile(latencies, 0.95);
            Long baselineP95 = percentile(baseline.latencies, 0.95);
            long totalTokens = inputTokens + outputTokens;
            long baselineTokens = baseline.inputTokens + baseline.outputTokens;
            int terminalRequests = completed + failed;

            JsonArray modelArray = new JsonArray();
            models.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(Map.Entry.comparingByKey()))
                    .map(entry -> new JsonObject()
                            .put("modelFamily", entry.getKey())
                            .put("count", entry.getValue())
                            .put("percentage", completed == 0
                                    ? 0.0
                                    : rounded(entry.getValue() * 100.0 / completed)))
                    .forEach(modelArray::add);

            return new JsonObject()
                    .put("profile", profile)
                    .put("label", label)
                    .put("kind", kind)
                    .put("routeStrategy", routeStrategy)
                    .put("status", status)
                    .put("runId", runId)
                    .put("requested", requested)
                    .put("completed", completed)
                    .put("failed", failed)
                    .put("retries", retries)
                    .put("successRate", terminalRequests == 0
                            ? null
                            : rounded(completed * 100.0 / terminalRequests))
                    .put("p50LatencyMs", p50)
                    .put("p95LatencyMs", p95)
                    .put("inputTokens", inputTokens)
                    .put("outputTokens", outputTokens)
                    .put("tokenSamples", tokenSamples)
                    .put("models", modelArray)
                    .put("p95DeltaMs", delta(p95, baselineP95))
                    .put("p95DeltaPercent", percentDelta(p95, baselineP95))
                    .put("totalTokensDelta", tokenSamples == 0 || baseline.tokenSamples == 0
                            ? null
                            : totalTokens - baselineTokens)
                    .put("totalTokensDeltaPercent", tokenSamples == 0 || baseline.tokenSamples == 0
                            ? null
                            : percentDelta(totalTokens, baselineTokens));
        }
    }
}
