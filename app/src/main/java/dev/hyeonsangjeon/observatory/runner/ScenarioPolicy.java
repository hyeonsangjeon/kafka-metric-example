package dev.hyeonsangjeon.observatory.runner;

import dev.hyeonsangjeon.observatory.model.Scenario;

public final class ScenarioPolicy {
    private ScenarioPolicy() {
    }

    public static boolean shouldThrottle(Scenario scenario, int ordinal) {
        return scenario == Scenario.MODEL_THROTTLING && ordinal % 3 == 0;
    }

    public static boolean shouldDuplicate(Scenario scenario, int ordinal) {
        return scenario == Scenario.DUPLICATE_DELIVERY && ordinal % 4 == 0;
    }

    public static Integer partitionHint(Scenario scenario) {
        return scenario == Scenario.HOT_PARTITION ? 0 : null;
    }
}
