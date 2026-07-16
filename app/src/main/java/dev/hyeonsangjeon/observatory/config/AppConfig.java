package dev.hyeonsangjeon.observatory.config;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record AppConfig(
        String host,
        int port,
        TransportMode requestedTransport,
        String kafkaBootstrapServers,
        String kafkaTopic,
        String kafkaDlqTopic,
        AiMode aiMode,
        String foundryProjectEndpoint,
        String foundryModel,
        String foundryRouterModel,
        String foundryRouterProfile,
        String foundryRouterDefaultModel,
        String foundryRouterAdvancedModel,
        String foundryRouterAdvancedProfile,
        String ollamaBaseUrl,
        String ollamaModel,
        boolean ollamaRequireLoopback,
        int maxCloudRequestsPerRun) {

    public static final int DEFAULT_PORT = 8080;
    public static final int DEFAULT_MAX_CLOUD_REQUESTS = 10;
    public static final String DEFAULT_OLLAMA_BASE_URL = "http://127.0.0.1:11434/v1";
    private static final Set<String> ROUTER_PROFILES = Set.of("balanced", "cost", "quality");
    private static final Set<String> ADVANCED_ROUTER_PROFILES = Set.of("cost", "quality");

    public static AppConfig fromEnvironment() {
        return from(System.getenv());
    }

    public static AppConfig from(Map<String, String> env) {
        int port = boundedInt(env.get("APP_PORT"), DEFAULT_PORT, 1, 65_535, "APP_PORT");
        int cloudLimit = boundedInt(env.get("MAX_CLOUD_REQUESTS_PER_RUN"),
                DEFAULT_MAX_CLOUD_REQUESTS, 1, 100, "MAX_CLOUD_REQUESTS_PER_RUN");
        String providerSetting = firstNonBlank(
                env.get("AI_PROVIDER"), env.get("AI_MODE"), "simulated");
        String legacyRouterModel = trimToEmpty(env.get("FOUNDRY_ROUTER_MODEL"));
        String legacyRouterProfile = normalizedRouterProfile(
                env.get("FOUNDRY_ROUTER_PROFILE"), "balanced", ROUTER_PROFILES,
                "FOUNDRY_ROUTER_PROFILE");
        String defaultRouterModel = trimToEmpty(env.get("FOUNDRY_ROUTER_DEFAULT_MODEL"));
        String advancedRouterModel = trimToEmpty(env.get("FOUNDRY_ROUTER_ADVANCED_MODEL"));
        boolean explicitDualRouterConfiguration = !defaultRouterModel.isBlank()
                || !advancedRouterModel.isBlank();
        if (!explicitDualRouterConfiguration && !legacyRouterModel.isBlank()) {
            if ("balanced".equals(legacyRouterProfile)) {
                defaultRouterModel = legacyRouterModel;
            } else {
                advancedRouterModel = legacyRouterModel;
            }
        }
        String advancedRouterProfile = normalizedRouterProfile(
                firstNonBlank(
                        env.get("FOUNDRY_ROUTER_ADVANCED_PROFILE"),
                        ADVANCED_ROUTER_PROFILES.contains(legacyRouterProfile)
                                ? legacyRouterProfile : null,
                        "quality"),
                "quality",
                ADVANCED_ROUTER_PROFILES,
                "FOUNDRY_ROUTER_ADVANCED_PROFILE");

        return new AppConfig(
                valueOrDefault(env.get("APP_HOST"), "127.0.0.1"),
                port,
                TransportMode.parse(env.getOrDefault("EVENT_TRANSPORT", "memory")),
                valueOrDefault(env.get("KAFKA_BOOTSTRAP_SERVERS"), "localhost:9092"),
                valueOrDefault(env.get("KAFKA_TOPIC"), "foundry.telemetry.v1"),
                valueOrDefault(env.get("KAFKA_DLQ_TOPIC"), "foundry.telemetry.dlq.v1"),
                AiMode.parse(providerSetting),
                trimToEmpty(env.get("FOUNDRY_PROJECT_ENDPOINT")),
                trimToEmpty(env.get("FOUNDRY_MODEL")),
                legacyRouterModel,
                legacyRouterProfile,
                defaultRouterModel,
                advancedRouterModel,
                advancedRouterProfile,
                valueOrDefault(env.get("OLLAMA_BASE_URL"), DEFAULT_OLLAMA_BASE_URL),
                trimToEmpty(env.get("OLLAMA_MODEL")),
                strictBoolean(env.get("OLLAMA_REQUIRE_LOOPBACK"), true, "OLLAMA_REQUIRE_LOOPBACK"),
                cloudLimit);
    }

    public boolean foundryConfigured() {
        return !foundryProjectEndpoint.isBlank() && !foundryModel.isBlank();
    }

    public boolean foundryRouterConfigured() {
        return foundryDefaultRouterConfigured() || foundryAdvancedRouterConfigured();
    }

    public boolean foundryDefaultRouterConfigured() {
        return foundryConfigured() && !foundryRouterDefaultModel.isBlank();
    }

    public boolean foundryAdvancedRouterConfigured() {
        return foundryConfigured() && !foundryRouterAdvancedModel.isBlank();
    }

    public void validate() {
        if (host.isBlank() || host.length() > 255 || host.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("APP_HOST must be a non-blank host name or address");
        }
        if (aiMode == AiMode.FOUNDRY && !foundryConfigured()) {
            throw new IllegalArgumentException(
                    "AI_PROVIDER=foundry requires FOUNDRY_PROJECT_ENDPOINT and FOUNDRY_MODEL");
        }
        if (!ROUTER_PROFILES.contains(foundryRouterProfile)) {
            throw new IllegalArgumentException(
                    "FOUNDRY_ROUTER_PROFILE must be one of balanced, cost, quality");
        }
        if (!ADVANCED_ROUTER_PROFILES.contains(foundryRouterAdvancedProfile)) {
            throw new IllegalArgumentException(
                    "FOUNDRY_ROUTER_ADVANCED_PROFILE must be one of cost, quality");
        }
        if (!foundryRouterDefaultModel.isBlank()
                && foundryRouterDefaultModel.equals(foundryRouterAdvancedModel)) {
            throw new IllegalArgumentException(
                    "Foundry default and advanced router deployments must differ");
        }
        if (aiMode == AiMode.OLLAMA) {
            if (ollamaModel.isBlank()) {
                throw new IllegalArgumentException("AI_PROVIDER=ollama requires OLLAMA_MODEL");
            }
            validateOllamaEndpoint();
        }
        validateTopic(kafkaTopic, "KAFKA_TOPIC");
        validateTopic(kafkaDlqTopic, "KAFKA_DLQ_TOPIC");
        if (kafkaTopic.equals(kafkaDlqTopic)) {
            throw new IllegalArgumentException("KAFKA_TOPIC and KAFKA_DLQ_TOPIC must differ");
        }
    }

    private static void validateTopic(String topic, String name) {
        if (topic.isBlank() || topic.length() > 249 || !topic.matches("[a-zA-Z0-9._-]+")) {
            throw new IllegalArgumentException(name + " must be a valid Kafka topic name");
        }
    }

    private static int boundedInt(String raw, int fallback, int min, int max, String name) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static boolean strictBoolean(String raw, boolean fallback, String name) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(name + " must be true or false");
        };
    }

    private void validateOllamaEndpoint() {
        URI endpoint;
        try {
            endpoint = URI.create(ollamaBaseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("OLLAMA_BASE_URL must be an absolute HTTP URL", exception);
        }
        if (!("http".equalsIgnoreCase(endpoint.getScheme())
                || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("OLLAMA_BASE_URL must be an absolute HTTP URL without credentials, query, or fragment");
        }
        if (!ollamaRequireLoopback) {
            return;
        }
        String hostName = endpoint.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(hostName)
                || "127.0.0.1".equals(hostName)
                || "0:0:0:0:0:0:0:1".equalsIgnoreCase(hostName)
                || "::1".equals(hostName)
                || "[::1]".equals(hostName);
        if (!loopback) {
            // Deliberately avoid DNS here: accepting a hostname that resolves to loopback creates
            // a DNS-rebinding escape from the local-only policy.
            throw new IllegalArgumentException(
                    "OLLAMA_BASE_URL must use localhost or a loopback literal when OLLAMA_REQUIRE_LOOPBACK=true");
        }
    }

    private static String valueOrDefault(String value, String fallback) {
        String trimmed = trimToEmpty(value);
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToEmpty(value);
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }

    private static String normalizedRouterProfile(
            String raw,
            String fallback,
            Set<String> allowed,
            String name) {
        String value = valueOrDefault(raw, fallback).toLowerCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(name + " has unsupported value: " + value);
        }
        return value;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public enum TransportMode {
        MEMORY, KAFKA;

        public static TransportMode parse(String value) {
            return parseEnum(TransportMode.class, value, "EVENT_TRANSPORT");
        }

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum AiMode {
        SIMULATED, OLLAMA, FOUNDRY;

        public static AiMode parse(String value) {
            return parseEnum(AiMode.class, value, "AI_PROVIDER");
        }

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String value, String name) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " has unsupported value: " + value, exception);
        }
    }
}
