package dev.hyeonsangjeon.observatory.provider;

import com.sun.net.httpserver.HttpServer;
import dev.hyeonsangjeon.observatory.model.Workload;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaProviderTest {
    private HttpServer server;
    private OllamaProvider provider;
    private AtomicReference<String> receivedBody;

    @BeforeEach
    void setUp() throws IOException {
        receivedBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("""
                    {"output":[{"type":"message","content":[{"type":"output_text","text":"local answer"}]}],
                     "usage":{"input_tokens":12,"output_tokens":3},
                     "model":"private-local-model-name"}
                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        provider = new OllamaProvider(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "private-local-model-name");
    }

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void callsResponsesEndpointAndReturnsOnlySafeLocalMetadata() throws Exception {
        ProviderResult result = provider.invoke(new ProviderRequest(
                        "trace-1", 1, Workload.CHAT, "private prompt", "ollama-fixed"))
                .get(3, TimeUnit.SECONDS);

        assertEquals("local answer", result.output());
        assertEquals("fixed", result.routeStrategy());
        assertEquals("local", result.selectedRoute());
        assertEquals("Local model", result.modelFamily());
        assertEquals(12L, result.inputTokens());
        assertEquals(3L, result.outputTokens());
        assertFalse(result.toString().contains("private-local-model-name"));

        JsonObject request = new JsonObject(receivedBody.get());
        assertEquals("private-local-model-name", request.getString("model"));
        assertEquals("private prompt", request.getString("input"));
        assertFalse(request.getBoolean("store"));
        assertTrue(provider.modelProfiles().stream()
                .anyMatch(profile -> profile.id().equals("ollama-fixed")));
    }
}
