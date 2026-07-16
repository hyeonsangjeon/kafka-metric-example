package dev.hyeonsangjeon.observatory.transport;

import dev.hyeonsangjeon.observatory.model.TelemetryEvent;

public record EventDelivery(TelemetryEvent event, int partition, long offset, long observedLag) {
}
