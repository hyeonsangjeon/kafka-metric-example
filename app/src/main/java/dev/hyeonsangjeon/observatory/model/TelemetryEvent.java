package dev.hyeonsangjeon.observatory.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelemetryEvent(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") EventType eventType,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("run_id") String runId,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("emitted_at") String emittedAt,
        @JsonProperty("provider_alias") String providerAlias,
        String workload,
        String scenario,
        long sequence,
        @JsonProperty("partition_hint") Integer partitionHint,
        EventDetails details) {

    public static final String SCHEMA_VERSION = "1.0";

    public TelemetryEvent {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(emittedAt, "emittedAt");
        Objects.requireNonNull(providerAlias, "providerAlias");
        Objects.requireNonNull(workload, "workload");
        Objects.requireNonNull(scenario, "scenario");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported telemetry schema version: " + schemaVersion);
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
    }

    public static TelemetryEvent create(
            EventType type,
            String sessionId,
            String runId,
            String traceId,
            String providerAlias,
            Workload workload,
            Scenario scenario,
            long sequence,
            Integer partitionHint,
            EventDetails details) {
        return new TelemetryEvent(
                SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                type,
                sessionId,
                runId,
                traceId,
                Instant.now().toString(),
                providerAlias,
                workload.wireName(),
                scenario.wireName(),
                sequence,
                partitionHint,
                details);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventDetails(
            @JsonProperty("prompt_hash") String promptHash,
            @JsonProperty("prompt_chars") Integer promptChars,
            @JsonProperty("response_hash") String responseHash,
            @JsonProperty("response_chars") Integer responseChars,
            @JsonProperty("latency_ms") Long latencyMs,
            Integer attempt,
            @JsonProperty("error_code") String errorCode,
            Boolean synthetic,
            @JsonProperty("observed_lag") Long observedLag,
            @JsonProperty("requested_traffic") Integer requestedTraffic,
            @JsonProperty("signal_source") String signalSource,
            @JsonProperty("model_profile") String modelProfile,
            @JsonProperty("route_strategy") String routeStrategy,
            @JsonProperty("selected_route") String selectedRoute,
            @JsonProperty("model_family") String modelFamily,
            @JsonProperty("input_tokens") Long inputTokens,
            @JsonProperty("output_tokens") Long outputTokens) {

        public EventDetails {
            validateSafeSlug(modelProfile, "model_profile", 64);
            validateSafeSlug(routeStrategy, "route_strategy", 32);
            validateSafeSlug(selectedRoute, "selected_route", 64);
            if (modelFamily != null
                    && (modelFamily.isBlank()
                    || modelFamily.length() > 64
                    || modelFamily.chars().anyMatch(Character::isISOControl))) {
                throw new IllegalArgumentException("model_family must be a short safe display label");
            }
            if (inputTokens != null && inputTokens < 0L) {
                throw new IllegalArgumentException("input_tokens must be non-negative");
            }
            if (outputTokens != null && outputTokens < 0L) {
                throw new IllegalArgumentException("output_tokens must be non-negative");
            }
        }

        public static EventDetails empty(boolean synthetic) {
            return new EventDetails(null, null, null, null, null, null, null,
                    synthetic, null, null, null, null, null, null, null, null, null);
        }

        public static EventDetails empty(boolean synthetic, String modelProfile, String routeStrategy) {
            return new EventDetails(null, null, null, null, null, null, null,
                    synthetic, null, null, null, modelProfile, routeStrategy,
                    null, null, null, null);
        }

        public static EventDetails run(int requestedTraffic, boolean synthetic) {
            return new EventDetails(null, null, null, null, null, null, null,
                    synthetic, null, requestedTraffic, null, null, null, null, null, null, null);
        }

        public static EventDetails run(
                int requestedTraffic,
                boolean synthetic,
                String modelProfile,
                String routeStrategy) {
            return new EventDetails(null, null, null, null, null, null, null,
                    synthetic, null, requestedTraffic, null, modelProfile, routeStrategy,
                    null, null, null, null);
        }

        public static EventDetails request(String promptHash, int promptChars, int attempt, boolean synthetic) {
            return new EventDetails(promptHash, promptChars, null, null, null,
                    attempt, null, synthetic, null, null, null,
                    null, null, null, null, null, null);
        }

        public static EventDetails request(
                String promptHash,
                int promptChars,
                int attempt,
                boolean synthetic,
                String modelProfile,
                String routeStrategy) {
            return new EventDetails(promptHash, promptChars, null, null, null,
                    attempt, null, synthetic, null, null, null,
                    modelProfile, routeStrategy, null, null, null, null);
        }

        public static EventDetails response(
                String promptHash,
                int promptChars,
                String responseHash,
                int responseChars,
                long latencyMs,
                int attempt,
                boolean synthetic) {
            return new EventDetails(promptHash, promptChars, responseHash, responseChars,
                    latencyMs, attempt, null, synthetic, null, null, null,
                    null, null, null, null, null, null);
        }

        public static EventDetails response(
                String promptHash,
                int promptChars,
                String responseHash,
                int responseChars,
                long latencyMs,
                int attempt,
                boolean synthetic,
                String modelProfile,
                String routeStrategy,
                String selectedRoute,
                String modelFamily,
                Long inputTokens,
                Long outputTokens) {
            return new EventDetails(promptHash, promptChars, responseHash, responseChars,
                    latencyMs, attempt, null, synthetic, null, null, null,
                    modelProfile, routeStrategy, selectedRoute, modelFamily, inputTokens, outputTokens);
        }

        public static EventDetails failure(
                String promptHash,
                int promptChars,
                long latencyMs,
                int attempt,
                String errorCode,
                boolean synthetic) {
            return new EventDetails(promptHash, promptChars, null, null,
                    latencyMs, attempt, errorCode, synthetic, null, null, null,
                    null, null, null, null, null, null);
        }

        public static EventDetails failure(
                String promptHash,
                int promptChars,
                long latencyMs,
                int attempt,
                String errorCode,
                boolean synthetic,
                String modelProfile,
                String routeStrategy,
                String selectedRoute,
                String modelFamily) {
            return new EventDetails(promptHash, promptChars, null, null,
                    latencyMs, attempt, errorCode, synthetic, null, null, null,
                    modelProfile, routeStrategy, selectedRoute, modelFamily, null, null);
        }

        public static EventDetails lag(long observedLag, boolean synthetic) {
            return new EventDetails(null, null, null, null, null, null, null,
                    synthetic, observedLag, null, synthetic ? "modeled" : "measured",
                    null, null, null, null, null, null);
        }

        private static void validateSafeSlug(String value, String name, int maxLength) {
            if (value != null && (!value.matches("[a-z0-9-]{1," + maxLength + "}"))) {
                throw new IllegalArgumentException(name + " must be a safe slug");
            }
        }
    }
}
