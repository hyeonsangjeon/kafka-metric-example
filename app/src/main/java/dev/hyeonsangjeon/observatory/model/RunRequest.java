package dev.hyeonsangjeon.observatory.model;

public record RunRequest(
        Workload workload,
        int traffic,
        Scenario scenario,
        String prompt,
        String modelProfile) {
    public static final int MAX_TRAFFIC = 100;
    public static final int MAX_PROMPT_CHARS = 8_000;

    public RunRequest {
        if (workload == null) {
            throw new IllegalArgumentException("workload is required");
        }
        if (scenario == null) {
            throw new IllegalArgumentException("scenario is required");
        }
        if (traffic < 1 || traffic > MAX_TRAFFIC) {
            throw new IllegalArgumentException("traffic must be between 1 and " + MAX_TRAFFIC);
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        prompt = prompt.strip();
        if (prompt.length() > MAX_PROMPT_CHARS) {
            throw new IllegalArgumentException("prompt must not exceed " + MAX_PROMPT_CHARS + " characters");
        }
        if (modelProfile == null || !modelProfile.matches("[a-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("modelProfile must be a safe profile ID");
        }
    }

    public RunRequest(Workload workload, int traffic, Scenario scenario, String prompt) {
        this(workload, traffic, scenario, prompt, "fixed");
    }
}
