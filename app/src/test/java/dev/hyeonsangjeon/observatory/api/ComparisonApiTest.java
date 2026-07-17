package dev.hyeonsangjeon.observatory.api;

import dev.hyeonsangjeon.observatory.config.AppConfig;
import dev.hyeonsangjeon.observatory.projection.ProjectionStore;
import dev.hyeonsangjeon.observatory.provider.AiProvider;
import dev.hyeonsangjeon.observatory.provider.ModelProfile;
import dev.hyeonsangjeon.observatory.provider.ProviderRequest;
import dev.hyeonsangjeon.observatory.provider.ProviderResult;
import dev.hyeonsangjeon.observatory.runner.WorkloadRunner;
import dev.hyeonsangjeon.observatory.stream.SseHub;
import dev.hyeonsangjeon.observatory.transport.InMemoryEventTransport;
import dev.hyeonsangjeon.observatory.transport.TransportSelection;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComparisonApiTest {
    private Vertx vertx;
    private int port;
    private ControlledProvider provider;

    @BeforeEach
    void startApi() throws Exception {
        port = freePort();
        AppConfig config = AppConfig.from(Map.of(
                "APP_PORT", Integer.toString(port),
                "AI_PROVIDER", "simulated",
                "MAX_CLOUD_REQUESTS_PER_RUN", "10"));
        config.validate();

        vertx = Vertx.vertx();
        ProjectionStore store = new ProjectionStore();
        provider = new ControlledProvider();
        InMemoryEventTransport transport = new InMemoryEventTransport(vertx);
        TransportSelection selection = new TransportSelection(transport, "memory", null);
        WorkloadRunner runner = new WorkloadRunner(
                vertx, provider, transport, store::sessionId, 10);
        SseHub hub = new SseHub(vertx);
        ApiVerticle api = new ApiVerticle(
                config,
                selection,
                provider,
                store,
                runner,
                hub,
                () -> store.snapshot().put("comparison", runner.comparisonSnapshot()));

        vertx.deployVerticle(api).toCompletionStage().toCompletableFuture()
                .get(3, TimeUnit.SECONDS);
    }

    @AfterEach
    void stopApi() throws Exception {
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void comparisonReceiptGetSnapshotConfigSseAndStopShareOneSafeContract() throws Exception {
        JsonObject config = json(get("/api/v1/config"));
        JsonObject capability = config.getJsonObject("comparison");
        assertTrue(capability.getBoolean("available"));
        assertNull(capability.getValue("unavailableReason"));
        assertEquals("router-advanced", capability.getJsonArray("profiles")
                .getJsonObject(2).getString("id"));
        assertEquals("cost", capability.getJsonArray("profiles")
                .getJsonObject(2).getString("routeStrategy"));

        HttpResponse<String> started = post("/api/v1/comparisons", new JsonObject()
                .put("workload", "chat")
                .put("scenario", "healthy")
                .put("traffic", 1)
                .put("prompt", "private api prompt"));
        assertEquals(202, started.statusCode());
        JsonObject receipt = json(started);
        assertEquals(Set.of(
                        "comparisonId", "status", "profiles", "trafficPerProfile",
                        "plannedRequests", "providerInvocationLimit"),
                receipt.fieldNames());
        String comparisonId = receipt.getString("comparisonId");

        HttpResponse<String> conflict = post("/api/v1/runs", new JsonObject()
                .put("traffic", 1));
        assertEquals(409, conflict.statusCode());
        assertEquals("RUN_ALREADY_ACTIVE", json(conflict)
                .getJsonObject("error").getString("code"));

        JsonObject direct = json(get("/api/v1/comparisons/" + comparisonId));
        JsonObject snapshot = json(get("/api/v1/snapshot"));
        assertEquals(comparisonId, direct.getString("comparisonId"));
        assertEquals(comparisonId, snapshot.getJsonObject("comparison")
                .getString("comparisonId"));
        assertFalse(direct.encode().contains("private api prompt"));

        String initialSse = initialSseFrame();
        assertTrue(initialSse.contains("event: snapshot"));
        assertTrue(initialSse.contains(comparisonId));

        HttpResponse<String> stopped = post(
                "/api/v1/comparisons/" + comparisonId + "/stop", new JsonObject());
        assertEquals(202, stopped.statusCode());
        assertEquals("stopped", json(stopped).getString("status"));
        assertEquals("stopped", json(get("/api/v1/comparisons/" + comparisonId))
                .getString("status"));
    }

    @Test
    void comparisonRejectsProfileSelectionAndOverBudgetTraffic() throws Exception {
        HttpResponse<String> profile = post("/api/v1/comparisons", new JsonObject()
                .put("modelProfile", "fixed"));
        HttpResponse<String> budget = post("/api/v1/comparisons", new JsonObject()
                .put("traffic", 4));

        assertEquals(422, profile.statusCode());
        assertEquals("INVALID_COMPARISON_REQUEST", json(profile)
                .getJsonObject("error").getString("code"));
        assertEquals(422, budget.statusCode());
        assertEquals("COMPARISON_BUDGET_EXCEEDED", json(budget)
                .getJsonObject("error").getString("code"));
        assertTrue(provider.invocations.isEmpty());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, JsonObject body) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.encode()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String initialSseFrame() throws Exception {
        HttpResponse<InputStream> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri("/api/v1/stream")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        StringBuilder frame = new StringBuilder();
        try (InputStream input = response.body();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                frame.append(line).append('\n');
            }
        }
        return frame.toString();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static JsonObject json(HttpResponse<String> response) {
        return new JsonObject(response.body());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class ControlledProvider implements AiProvider {
        private final List<ProviderRequest> invocations = new CopyOnWriteArrayList<>();

        @Override
        public String alias() {
            return "simulated";
        }

        @Override
        public boolean synthetic() {
            return true;
        }

        @Override
        public List<ModelProfile> modelProfiles() {
            return List.of(
                    new ModelProfile("fixed", "Fixed model", "fixed", "Fixed.", false),
                    new ModelProfile(
                            "router-default", "Router default", "balanced", "Default.", true),
                    new ModelProfile(
                            "router-advanced", "Router advanced", "cost", "Advanced.", true));
        }

        @Override
        public CompletableFuture<ProviderResult> invoke(ProviderRequest request) {
            invocations.add(request);
            return new CompletableFuture<>();
        }
    }
}
