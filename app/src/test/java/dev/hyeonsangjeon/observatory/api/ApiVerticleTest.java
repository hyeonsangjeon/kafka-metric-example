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

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiVerticleTest {
    private Vertx vertx;
    private int port;
    private LocalOnlyFakeProvider provider;

    @BeforeEach
    void startApi() throws Exception {
        port = freePort();
        AppConfig config = AppConfig.from(Map.of(
                "APP_PORT", Integer.toString(port),
                "AI_PROVIDER", "ollama",
                "OLLAMA_MODEL", "private-local-model"));
        config.validate();

        vertx = Vertx.vertx();
        ProjectionStore store = new ProjectionStore();
        provider = new LocalOnlyFakeProvider();
        InMemoryEventTransport transport = new InMemoryEventTransport(vertx);
        TransportSelection selection = new TransportSelection(transport, "memory", null);
        WorkloadRunner runner = new WorkloadRunner(
                vertx, provider, transport, store::sessionId);
        SseHub hub = new SseHub(vertx);
        ApiVerticle api = new ApiVerticle(
                config, selection, provider, store, runner, hub, store::snapshot);

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
    void ollamaHealthAndConfigurationAreNeverReportedAsSimulated() throws Exception {
        HttpResponse<String> healthResponse = get("/api/v1/health");
        HttpResponse<String> configResponse = get("/api/v1/config");
        JsonObject health = new JsonObject(healthResponse.body());
        JsonObject config = new JsonObject(configResponse.body());

        assertEquals(200, healthResponse.statusCode());
        assertEquals("ollama", health.getString("provider"));
        assertEquals("ollama", config.getString("mode"));
        assertEquals("ollama-local", config.getString("modelAlias"));
        assertEquals("ollama-fixed", config.getJsonObject("defaults").getString("modelId"));
        assertEquals("ollama-fixed", config.getJsonArray("modelProfiles")
                .getJsonObject(0).getString("id"));
        assertFalse(configResponse.body().contains("simulated"));
        assertFalse(configResponse.body().contains("private-local-model"));
    }

    @Test
    void mismatchedAndUnknownProfilesReturnValidationErrors() throws Exception {
        HttpResponse<String> mismatch = post(new JsonObject()
                .put("modelId", "ollama-fixed")
                .put("modelProfile", "unknown-profile"));
        HttpResponse<String> unknown = post(new JsonObject()
                .put("modelProfile", "unknown-profile"));

        assertEquals(422, mismatch.statusCode());
        assertEquals("INVALID_RUN_REQUEST", new JsonObject(mismatch.body())
                .getJsonObject("error").getString("code"));
        assertTrue(new JsonObject(mismatch.body()).getJsonObject("error")
                .getString("message").contains("must match"));
        assertEquals(422, unknown.statusCode());
        assertTrue(new JsonObject(unknown.body()).getJsonObject("error")
                .getString("message").contains("unsupported model profile"));
    }

    @Test
    void acceptedRunCanonicalizesLegacyProfileAlias() throws Exception {
        HttpResponse<String> response = post(new JsonObject()
                .put("traffic", 1)
                .put("modelProfile", "legacy-local"));

        assertEquals(202, response.statusCode());
        assertEquals("ollama-fixed", new JsonObject(response.body()).getString("modelProfile"));
        assertEquals("ollama-fixed", provider.invoked.get(3, TimeUnit.SECONDS).modelProfile());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(JsonObject body) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/runs"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.encode()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class LocalOnlyFakeProvider implements AiProvider {
        private final CompletableFuture<ProviderRequest> invoked = new CompletableFuture<>();

        @Override
        public String alias() {
            return "ollama-local";
        }

        @Override
        public boolean synthetic() {
            return false;
        }

        @Override
        public List<ModelProfile> modelProfiles() {
            return List.of(new ModelProfile(
                    "ollama-fixed",
                    "Ollama · Local model",
                    "fixed",
                    "Every request stays local.",
                    false));
        }

        @Override
        public ModelProfile requireModelProfile(String id) {
            if ("legacy-local".equals(id)) {
                return modelProfiles().getFirst();
            }
            return AiProvider.super.requireModelProfile(id);
        }

        @Override
        public CompletableFuture<ProviderResult> invoke(ProviderRequest request) {
            invoked.complete(request);
            return CompletableFuture.completedFuture(new ProviderResult(
                    "unused", "fixed", "local", "Local model", null, null));
        }
    }
}
