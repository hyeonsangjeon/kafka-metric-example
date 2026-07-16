package dev.hyeonsangjeon.observatory.transport;

import dev.hyeonsangjeon.observatory.config.AppConfig;
import io.vertx.core.Vertx;

import java.time.Duration;

public record TransportSelection(EventTransport transport, String requested, String fallbackReason) {
    public static TransportSelection create(AppConfig config, Vertx vertx) {
        if (config.requestedTransport() == AppConfig.TransportMode.MEMORY) {
            return new TransportSelection(new InMemoryEventTransport(vertx), "memory", null);
        }

        try {
            KafkaEventTransport.verifyBroker(config, Duration.ofSeconds(3));
            return new TransportSelection(new KafkaEventTransport(config), "kafka", null);
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new TransportSelection(
                    new InMemoryEventTransport(vertx),
                    "kafka",
                    "Kafka readiness check failed; memory fallback is active");
        }
    }
}
