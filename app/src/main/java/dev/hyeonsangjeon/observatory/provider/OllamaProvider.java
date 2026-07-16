package dev.hyeonsangjeon.observatory.provider;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Local Ollama adapter using its OpenAI-compatible Responses endpoint. No cloud fallback is
 * attempted, and prompt/response bodies remain inside this provider boundary.
 */
public final class OllamaProvider implements AiProvider {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final List<ModelProfile> PROFILES = List.of(new ModelProfile(
            "ollama-fixed",
            "Ollama · Local model",
            "fixed",
            "Every request stays on the configured local Ollama instance.",
            false));

    private final URI responsesEndpoint;
    private final String model;
    private final ExecutorService requestExecutor;
    private final ExecutorService httpExecutor;
    private final HttpClient client;

    public OllamaProvider(String baseUrl, String model) {
        String normalizedBase = Objects.requireNonNull(baseUrl, "baseUrl").strip();
        this.responsesEndpoint = URI.create(
                normalizedBase.replaceFirst("/+$", "") + "/responses");
        this.model = Objects.requireNonNull(model, "model").strip();
        if (this.model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        ThreadFactory requestFactory = Thread.ofPlatform()
                .name("ollama-responses-", 0)
                .daemon(true)
                .factory();
        ThreadFactory httpFactory = Thread.ofPlatform()
                .name("ollama-http-", 0)
                .daemon(true)
                .factory();
        this.requestExecutor = Executors.newFixedThreadPool(4, requestFactory);
        this.httpExecutor = Executors.newFixedThreadPool(4, httpFactory);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(httpExecutor)
                .build();
    }

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
        return PROFILES;
    }

    @Override
    public CompletableFuture<ProviderResult> invoke(ProviderRequest request) {
        ModelProfile profile = requireModelProfile(request.modelProfile());
        return CompletableFuture.supplyAsync(() -> invokeBlocking(request, profile), requestExecutor);
    }

    private ProviderResult invokeBlocking(ProviderRequest request, ModelProfile profile) {
        JsonObject payload = new JsonObject()
                .put("model", model)
                .put("instructions", instructions(request))
                .put("input", request.prompt())
                .put("max_output_tokens", 384)
                .put("store", false);
        HttpRequest httpRequest = HttpRequest.newBuilder(responsesEndpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.encode()))
                .build();
        try {
            HttpResponse<InputStream> response = client.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream input = response.body()) {
                body = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (body.length > MAX_RESPONSE_BYTES) {
                throw new ProviderFailure("OLLAMA_RESPONSE_TOO_LARGE", false, null);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                boolean throttled = response.statusCode() == 429;
                throw new ProviderFailure(
                        "OLLAMA_HTTP_" + response.statusCode(), throttled, null);
            }
            JsonObject json = new JsonObject(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            JsonObject usage = json.getJsonObject("usage", new JsonObject());
            return new ProviderResult(
                    extractOutputText(json),
                    profile.strategy(),
                    "local",
                    "Local model",
                    longValue(usage, "input_tokens"),
                    longValue(usage, "output_tokens"));
        } catch (ProviderFailure failure) {
            throw failure;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProviderFailure("OLLAMA_REQUEST_INTERRUPTED", false, exception);
        } catch (IOException exception) {
            throw new ProviderFailure("OLLAMA_UNAVAILABLE", false, exception);
        } catch (DecodeException | ClassCastException exception) {
            throw new ProviderFailure("OLLAMA_INVALID_RESPONSE", false, exception);
        } catch (RuntimeException exception) {
            throw new ProviderFailure("OLLAMA_REQUEST_FAILED", false, exception);
        }
    }

    private static String instructions(ProviderRequest request) {
        return switch (request.workload()) {
            case CHAT -> "Answer briefly and clearly.";
            case SUMMARIZE -> "Summarize the input in at most three bullet points.";
            case EXTRACT -> "Extract the main entities as compact JSON.";
        };
    }

    private static String extractOutputText(JsonObject response) {
        String direct = response.getString("output_text");
        if (direct != null) {
            return direct;
        }
        StringBuilder output = new StringBuilder();
        JsonArray items = response.getJsonArray("output", new JsonArray());
        for (Object itemValue : items) {
            if (!(itemValue instanceof JsonObject item)) {
                continue;
            }
            JsonArray content = item.getJsonArray("content", new JsonArray());
            for (Object contentValue : content) {
                if (contentValue instanceof JsonObject part) {
                    String text = part.getString("text");
                    if (text != null) {
                        output.append(text);
                    }
                }
            }
        }
        return output.toString();
    }

    private static Long longValue(JsonObject object, String key) {
        Number value = object.getNumber(key);
        return value == null ? null : value.longValue();
    }

    @Override
    public void close() {
        requestExecutor.shutdownNow();
        httpExecutor.shutdownNow();
    }
}
