package dev.hyeonsangjeon.observatory.model;

public enum EventType {
    RUN_STARTED,
    REQUEST_STARTED,
    RESPONSE_COMPLETED,
    RESPONSE_FAILED,
    RESPONSE_THROTTLED,
    CONSUMER_LAGGED,
    RUN_COMPLETED,
    RUN_STOPPED
}
