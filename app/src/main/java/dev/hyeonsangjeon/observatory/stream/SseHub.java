package dev.hyeonsangjeon.observatory.stream;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SseHub implements AutoCloseable {
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    private final Vertx vertx;
    private final Set<HttpServerResponse> clients = ConcurrentHashMap.newKeySet();
    private final long heartbeatTimerId;
    private final AtomicBoolean open = new AtomicBoolean(true);

    public SseHub(Vertx vertx) {
        this.vertx = vertx;
        this.heartbeatTimerId = vertx.setPeriodic(HEARTBEAT_INTERVAL_MS, ignored -> heartbeat());
    }

    public void register(HttpServerResponse response, JsonObject initialSnapshot) {
        if (!open.get()) {
            response.setStatusCode(503).end();
            return;
        }
        response.setChunked(true)
                .putHeader("Content-Type", "text/event-stream; charset=utf-8")
                .putHeader("Cache-Control", "no-cache, no-transform")
                .putHeader("Connection", "keep-alive")
                .putHeader("X-Accel-Buffering", "no");
        clients.add(response);
        response.closeHandler(ignored -> clients.remove(response));
        response.exceptionHandler(ignored -> clients.remove(response));
        send(response, "snapshot", initialSnapshot);
    }

    public void publish(String eventName, Object data) {
        if (!open.get()) {
            return;
        }
        for (HttpServerResponse client : clients) {
            send(client, eventName, data);
        }
    }

    private void heartbeat() {
        for (HttpServerResponse client : clients) {
            if (client.closed()) {
                clients.remove(client);
                continue;
            }
            client.write(": heartbeat\n\n").onFailure(ignored -> clients.remove(client));
        }
    }

    private void send(HttpServerResponse response, String eventName, Object data) {
        if (response.closed()) {
            clients.remove(response);
            return;
        }
        String json = data instanceof JsonObject object ? object.encode() : Json.encode(data);
        response.write("event: " + eventName + "\ndata: " + json + "\n\n")
                .onFailure(ignored -> clients.remove(response));
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        vertx.cancelTimer(heartbeatTimerId);
        clients.forEach(response -> {
            if (!response.closed()) {
                response.end();
            }
        });
        clients.clear();
    }
}
