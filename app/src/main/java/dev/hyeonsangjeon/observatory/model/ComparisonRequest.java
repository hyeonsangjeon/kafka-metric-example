package dev.hyeonsangjeon.observatory.model;

/**
 * One privacy-safe input that is replayed across every provider profile. Traffic is per profile.
 */
public record ComparisonRequest(
        Workload workload,
        int trafficPerProfile,
        Scenario scenario,
        String prompt) {

    public ComparisonRequest {
        if (workload == null) {
            throw new IllegalArgumentException("workload is required");
        }
        if (scenario == null) {
            throw new IllegalArgumentException("scenario is required");
        }
        if (trafficPerProfile < 1 || trafficPerProfile > RunRequest.MAX_TRAFFIC) {
            throw new IllegalArgumentException(
                    "traffic must be between 1 and " + RunRequest.MAX_TRAFFIC);
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        prompt = prompt.strip();
        if (prompt.length() > RunRequest.MAX_PROMPT_CHARS) {
            throw new IllegalArgumentException(
                    "prompt must not exceed " + RunRequest.MAX_PROMPT_CHARS + " characters");
        }
    }
}
