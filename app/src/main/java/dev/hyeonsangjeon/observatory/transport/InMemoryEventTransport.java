package dev.hyeonsangjeon.observatory.transport;

import dev.hyeonsangjeon.observatory.model.Scenario;
import dev.hyeonsangjeon.observatory.model.TelemetryEvent;
import io.vertx.core.Vertx;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;

public final class InMemoryEventTransport implements EventTransport {
    static final int PARTITION_COUNT = 4;

    private final Vertx vertx;
    private final AtomicLongArray producedOffsets = new AtomicLongArray(PARTITION_COUNT);
    private final AtomicLongArray consumedOffsets = new AtomicLongArray(PARTITION_COUNT);
    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile Consumer<EventDelivery> consumer;

    public InMemoryEventTransport(Vertx vertx) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public void start(Consumer<EventDelivery> consumer) {
        if (this.consumer != null) {
            throw new IllegalStateException("transport already started");
        }
        this.consumer = Objects.requireNonNull(consumer, "consumer");
    }

    @Override
    public CompletableFuture<Void> publish(TelemetryEvent event) {
        Consumer<EventDelivery> target = consumer;
        if (!open.get() || target == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("transport is not running"));
        }

        int partition = event.partitionHint() == null
                ? Math.floorMod(routingKey(event).hashCode(), PARTITION_COUNT)
                : Math.floorMod(event.partitionHint(), PARTITION_COUNT);
        long offset = producedOffsets.getAndIncrement(partition);
        long delayMs = Scenario.CONSUMER_SLOWDOWN.wireName().equals(event.scenario()) ? 650L : 0L;
        Runnable delivery = () -> {
            if (!open.get()) {
                return;
            }
            consumedOffsets.incrementAndGet(partition);
            long lag = delayMs == 0L ? 0L
                    : Math.max(0, producedOffsets.get(partition) - consumedOffsets.get(partition));
            target.accept(new EventDelivery(event, partition, offset, lag));
        };

        if (delayMs == 0L) {
            vertx.runOnContext(ignored -> delivery.run());
        } else {
            vertx.setTimer(delayMs, ignored -> delivery.run());
        }
        return CompletableFuture.completedFuture(null);
    }

    private static String routingKey(TelemetryEvent event) {
        return event.traceId() == null ? event.runId() : event.traceId();
    }

    @Override
    public boolean healthy() {
        return open.get();
    }

    @Override
    public void close() {
        open.set(false);
        consumer = null;
    }
}
