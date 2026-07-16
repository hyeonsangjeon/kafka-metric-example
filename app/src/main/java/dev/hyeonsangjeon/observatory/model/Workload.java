package dev.hyeonsangjeon.observatory.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public enum Workload {
    CHAT,
    SUMMARIZE,
    EXTRACT;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Workload parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Unsupported workload: " + value, exception);
        }
    }

    public static List<String> wireNames() {
        return Arrays.stream(values()).map(Workload::wireName).toList();
    }
}
