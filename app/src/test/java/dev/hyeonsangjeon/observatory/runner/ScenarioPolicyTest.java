package dev.hyeonsangjeon.observatory.runner;

import dev.hyeonsangjeon.observatory.model.Scenario;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScenarioPolicyTest {
    @Test
    void healthyScenarioInjectsNoFaults() {
        IntStream.rangeClosed(1, 12).forEach(ordinal -> {
            assertFalse(ScenarioPolicy.shouldThrottle(Scenario.HEALTHY, ordinal));
            assertFalse(ScenarioPolicy.shouldDuplicate(Scenario.HEALTHY, ordinal));
        });
        assertNull(ScenarioPolicy.partitionHint(Scenario.HEALTHY));
    }

    @Test
    void throttlingScenarioDeterministicallyThrottlesEveryThirdRequest() {
        long throttled = IntStream.rangeClosed(1, 12)
                .filter(ordinal -> ScenarioPolicy.shouldThrottle(Scenario.MODEL_THROTTLING, ordinal))
                .count();
        assertEquals(4L, throttled);
    }

    @Test
    void duplicateScenarioDuplicatesEveryFourthTerminalEvent() {
        long duplicated = IntStream.rangeClosed(1, 12)
                .filter(ordinal -> ScenarioPolicy.shouldDuplicate(Scenario.DUPLICATE_DELIVERY, ordinal))
                .count();
        assertEquals(3L, duplicated);
    }

    @Test
    void hotPartitionUsesAStablePartitionHint() {
        assertEquals(0, ScenarioPolicy.partitionHint(Scenario.HOT_PARTITION));
    }
}
