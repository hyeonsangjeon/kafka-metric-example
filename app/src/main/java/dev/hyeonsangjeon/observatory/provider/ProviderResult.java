package dev.hyeonsangjeon.observatory.provider;

import java.util.Objects;

/**
 * Provider output plus privacy-safe routing metadata. Raw endpoint and deployment names never cross
 * the provider boundary.
 */
public record ProviderResult(
        String output,
        String routeStrategy,
        String selectedRoute,
        String modelFamily,
        Long inputTokens,
        Long outputTokens) {

    public ProviderResult {
        output = output == null ? "" : output;
        Objects.requireNonNull(routeStrategy, "routeStrategy");
        Objects.requireNonNull(selectedRoute, "selectedRoute");
        Objects.requireNonNull(modelFamily, "modelFamily");
        if (!routeStrategy.matches("[a-z0-9-]{1,32}")) {
            throw new IllegalArgumentException("route strategy must be a safe slug");
        }
        if (!selectedRoute.matches("[a-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("selected route must be a safe slug");
        }
        if (modelFamily.length() > 64 || modelFamily.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("model family must be a short display label");
        }
        if (inputTokens != null && inputTokens < 0L) {
            throw new IllegalArgumentException("input tokens must be non-negative");
        }
        if (outputTokens != null && outputTokens < 0L) {
            throw new IllegalArgumentException("output tokens must be non-negative");
        }
    }

    public ProviderResult(String output) {
        this(output, "fixed", "fixed", "Fixed", null, null);
    }
}
