package dev.hyeonsangjeon.observatory.provider;

import dev.hyeonsangjeon.observatory.model.Workload;

import java.util.Objects;

public record ProviderRequest(
        String traceId,
        int ordinal,
        Workload workload,
        String prompt,
        String modelProfile) {

    public ProviderRequest {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(workload, "workload");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(modelProfile, "modelProfile");
    }
}
