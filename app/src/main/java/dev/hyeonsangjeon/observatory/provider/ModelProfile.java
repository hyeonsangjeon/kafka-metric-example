package dev.hyeonsangjeon.observatory.provider;

import io.vertx.core.json.JsonObject;

import java.util.Objects;

/**
 * A user-selectable execution profile. All fields are safe to expose through the API and telemetry.
 */
public record ModelProfile(
        String id,
        String label,
        String strategy,
        String description,
        boolean router) {

    public ModelProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(description, "description");
        if (!id.matches("[a-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("model profile id must be a safe slug");
        }
        if (!strategy.matches("[a-z0-9-]{1,32}")) {
            throw new IllegalArgumentException("route strategy must be a safe slug");
        }
    }

    public JsonObject toJson() {
        return new JsonObject()
                .put("id", id)
                .put("label", label)
                .put("strategy", strategy)
                .put("description", description)
                .put("router", router);
    }
}
