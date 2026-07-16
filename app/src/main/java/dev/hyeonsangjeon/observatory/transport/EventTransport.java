package dev.hyeonsangjeon.observatory.transport;

import dev.hyeonsangjeon.observatory.model.TelemetryEvent;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface EventTransport extends AutoCloseable {
    String name();

    void start(Consumer<EventDelivery> consumer);

    CompletableFuture<Void> publish(TelemetryEvent event);

    boolean healthy();

    @Override
    void close();
}
