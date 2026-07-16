package dev.hyeonsangjeon.observatory.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public enum Scenario {
    HEALTHY,
    MODEL_THROTTLING,
    CONSUMER_SLOWDOWN,
    DUPLICATE_DELIVERY,
    HOT_PARTITION;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Scenario parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Unsupported scenario: " + value, exception);
        }
    }

    public static List<String> wireNames() {
        return Arrays.stream(values()).map(Scenario::wireName).toList();
    }
}
