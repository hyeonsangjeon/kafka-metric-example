package dev.hyeonsangjeon.observatory.api;

import dev.hyeonsangjeon.observatory.config.AppConfig;
import dev.hyeonsangjeon.observatory.model.ComparisonRequest;
import dev.hyeonsangjeon.observatory.model.RunRequest;
import dev.hyeonsangjeon.observatory.model.Scenario;
import dev.hyeonsangjeon.observatory.model.Workload;
import dev.hyeonsangjeon.observatory.projection.ProjectionStore;
import dev.hyeonsangjeon.observatory.provider.AiProvider;
import dev.hyeonsangjeon.observatory.provider.ModelProfile;
import dev.hyeonsangjeon.observatory.runner.WorkloadRunner;
import dev.hyeonsangjeon.observatory.stream.SseHub;
import dev.hyeonsangjeon.observatory.transport.TransportSelection;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;

import java.time.Instant;
import java.util.function.Supplier;

public final class ApiVerticle extends AbstractVerticle {
    private static final String API_VERSION = "v1";
    private static final long MAX_BODY_BYTES = 16_384L;

    private final AppConfig config;
    private final TransportSelection transportSelection;
    private final AiProvider provider;
    private final ProjectionStore store;
    private final WorkloadRunner runner;
    private final SseHub sseHub;
    private final Supplier<JsonObject> snapshotSupplier;
    private HttpServer server;

    public ApiVerticle(
            AppConfig config,
            TransportSelection transportSelection,
            AiProvider provider,
            ProjectionStore store,
            WorkloadRunner runner,
            SseHub sseHub,
            Supplier<JsonObject> snapshotSupplier) {
        this.config = config;
        this.transportSelection = transportSelection;
        this.provider = provider;
        this.store = store;
        this.runner = runner;
        this.sseHub = sseHub;
        this.snapshotSupplier = snapshotSupplier;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        Router router = createRouter();
        vertx.createHttpServer()
                .requestHandler(router)
                .listen(config.port(), config.host())
                .onSuccess(httpServer -> {
                    server = httpServer;
                    startPromise.complete();
                })
                .onFailure(startPromise::fail);
    }

    private Router createRouter() {
        Router router = Router.router(vertx);
        router.route().handler(this::securityHeaders);
        router.route(HttpMethod.GET, "/api/v1/health").handler(this::health);
        router.route(HttpMethod.GET, "/api/v1/config").handler(this::configuration);
        router.route(HttpMethod.GET, "/api/v1/snapshot").handler(this::snapshot);
        router.route(HttpMethod.GET, "/api/v1/stream").handler(this::stream);
        router.route(HttpMethod.GET, "/api/v1/comparisons/:id").handler(this::comparison);

        BodyHandler bodyHandler = BodyHandler.create().setBodyLimit(MAX_BODY_BYTES);
        router.route(HttpMethod.POST, "/api/v1/runs").handler(bodyHandler).handler(this::startRun);
        router.route(HttpMethod.POST, "/api/v1/runs/:id/stop").handler(this::stopRun);
        router.route(HttpMethod.POST, "/api/v1/comparisons")
                .handler(bodyHandler)
                .handler(this::startComparison);
        router.route(HttpMethod.POST, "/api/v1/comparisons/:id/stop")
                .handler(this::stopComparison);
        router.route(HttpMethod.DELETE, "/api/v1/session").handler(this::resetSession);

        router.route("/api/*").handler(context -> error(context, 404, "NOT_FOUND", "API route not found"));
        router.route().handler(StaticHandler.create("webroot")
                .setCachingEnabled(true)
                .setIndexPage("index.html"));
        router.route().last().handler(this::spaFallback);
        router.route().failureHandler(this::failure);
        return router;
    }

    private void securityHeaders(RoutingContext context) {
        context.response()
                .putHeader("Content-Security-Policy", "default-src 'self'; connect-src 'self'; "
                        + "img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; "
                        + "object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
                .putHeader("X-Content-Type-Options", "nosniff")
                .putHeader("Referrer-Policy", "no-referrer")
                .putHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
                .putHeader("Cross-Origin-Resource-Policy", "same-origin");
        context.next();
    }

    private void health(RoutingContext context) {
        boolean transportHealthy = transportSelection.transport().healthy();
        boolean fellBack = transportSelection.fallbackReason() != null;
        String status = transportHealthy && !fellBack ? "ok" : "degraded";
        JsonObject response = new JsonObject()
                .put("status", status)
                .put("ready", transportHealthy)
                .put("service", "Foundry Stream Lab")
                .put("apiVersion", API_VERSION)
                .put("provider", config.aiMode().wireName())
                .put("transport", transportSelection.transport().name())
                .put("transportRequested", transportSelection.requested())
                .put("timestamp", Instant.now().toString());
        if (fellBack) {
            response.put("message", transportSelection.fallbackReason());
        }
        json(context, fellBack ? 200 : 200, response);
    }

    private void configuration(RoutingContext context) {
        JsonArray modelProfiles = new JsonArray();
        provider.modelProfiles().stream()
                .map(profile -> profile.toJson())
                .forEach(modelProfiles::add);
        JsonObject defaults = new JsonObject()
                .put("workload", Workload.CHAT.wireName())
                .put("traffic", Math.min(12, maxTraffic()))
                .put("scenario", Scenario.HEALTHY.wireName())
                .put("modelId", provider.defaultModelProfile())
                .put("modelProfile", provider.defaultModelProfile());
        JsonObject response = new JsonObject()
                .put("apiVersion", API_VERSION)
                .put("mode", config.aiMode().wireName())
                .put("transport", transportSelection.transport().name())
                .put("transportRequested", transportSelection.requested())
                .put("modelAlias", provider.alias())
                .put("models", modelProfiles.copy())
                .put("modelProfiles", modelProfiles)
                .put("cloudReady", config.aiMode() == AppConfig.AiMode.FOUNDRY && config.foundryConfigured())
                .put("maxCloudRequestsPerRun", config.maxCloudRequestsPerRun())
                .put("maxTrafficPerRun", maxTraffic())
                .put("workloads", Workload.wireNames())
                .put("scenarios", Scenario.wireNames())
                .put("comparison", comparisonCapability())
                .put("defaults", defaults);
        if (transportSelection.fallbackReason() != null) {
            response.put("transportMessage", transportSelection.fallbackReason());
        }
        json(context, 200, response);
    }

    private void snapshot(RoutingContext context) {
        json(context, 200, snapshotPayload());
    }

    private void stream(RoutingContext context) {
        sseHub.register(context.response(), snapshotPayload());
    }

    private void comparison(RoutingContext context) {
        String comparisonId = context.pathParam("id");
        JsonObject comparison = comparisonId == null ? null : runner.comparison(comparisonId);
        if (comparison == null) {
            error(context, 404, "COMPARISON_NOT_FOUND", "No comparison matches that ID");
            return;
        }
        json(context, 200, comparison);
    }

    private void startRun(RoutingContext context) {
        try {
            JsonObject body = context.body().asJsonObject();
            if (body == null) {
                throw new IllegalArgumentException("JSON request body is required");
            }
            Workload workload = Workload.parse(body.getString("workload", Workload.CHAT.wireName()));
            Scenario scenario = Scenario.parse(body.getString("scenario", Scenario.HEALTHY.wireName()));
            int traffic = body.getInteger("traffic", Math.min(12, maxTraffic()));
            String prompt = body.getString("prompt", defaultPrompt(workload));
            String modelId = body.getString("modelId");
            String modelProfile = body.getString("modelProfile");
            if (modelId != null && modelProfile != null && !modelId.equals(modelProfile)) {
                throw new IllegalArgumentException("modelId and modelProfile must match when both are provided");
            }
            String requestedProfile = modelProfile != null
                    ? modelProfile
                    : modelId != null ? modelId : provider.defaultModelProfile();
            ModelProfile selectedProfile = provider.requireModelProfile(requestedProfile);
            if (traffic > maxTraffic()) {
                throw new IllegalArgumentException("traffic exceeds the configured per-run limit of " + maxTraffic());
            }
            RunRequest runRequest = new RunRequest(
                    workload, traffic, scenario, prompt, selectedProfile.id());
            WorkloadRunner.StartResult result = runner.start(runRequest);
            if (!result.accepted()) {
                error(context, 409, "RUN_ALREADY_ACTIVE", "Only one workload run can be active");
                return;
            }
            json(context, 202, new JsonObject()
                    .put("runId", result.runId())
                    .put("status", result.status())
                    .put("modelProfile", selectedProfile.id()));
        } catch (DecodeException exception) {
            error(context, 422, "INVALID_RUN_REQUEST", "Request body must be valid JSON");
        } catch (IllegalArgumentException | ClassCastException exception) {
            error(context, 422, "INVALID_RUN_REQUEST", exception.getMessage());
        }
    }

    private void stopRun(RoutingContext context) {
        String runId = context.pathParam("id");
        if (runId == null || runId.isBlank() || !runner.stop(runId)) {
            error(context, 404, "RUN_NOT_FOUND", "No active run matches that ID");
            return;
        }
        json(context, 202, new JsonObject().put("runId", runId).put("status", "stopping"));
    }

    private void startComparison(RoutingContext context) {
        try {
            JsonObject body = context.body().asJsonObject();
            if (body == null) {
                throw new IllegalArgumentException("JSON request body is required");
            }
            if (body.containsKey("modelProfile") || body.containsKey("modelId")) {
                throw new IllegalArgumentException(
                        "comparison runs select every available profile and do not accept modelProfile or modelId");
            }
            Workload workload = Workload.parse(
                    body.getString("workload", Workload.CHAT.wireName()));
            Scenario scenario = Scenario.parse(
                    body.getString("scenario", Scenario.HEALTHY.wireName()));
            int traffic = body.getInteger("traffic", defaultComparisonTraffic());
            String prompt = body.getString("prompt", defaultPrompt(workload));
            ComparisonRequest request = new ComparisonRequest(
                    workload, traffic, scenario, prompt);
            WorkloadRunner.ComparisonStartResult result = runner.startComparison(request);
            if (!result.accepted()) {
                if ("profiles_unavailable".equals(result.reason())) {
                    error(context, 422, "COMPARISON_UNAVAILABLE",
                            "Comparison requires at least two provider profiles");
                } else if ("provider_invocation_limit".equals(result.reason())) {
                    error(context, 422, "COMPARISON_BUDGET_EXCEEDED",
                            "traffic multiplied by profile count exceeds the provider invocation limit of "
                                    + runner.providerInvocationLimit());
                } else {
                    error(context, 409, "EXECUTION_ALREADY_ACTIVE",
                            "Only one workload run or comparison can be active");
                }
                return;
            }
            json(context, 202, result.receipt());
        } catch (DecodeException exception) {
            error(context, 422, "INVALID_COMPARISON_REQUEST",
                    "Request body must be valid JSON");
        } catch (IllegalArgumentException | ClassCastException exception) {
            error(context, 422, "INVALID_COMPARISON_REQUEST", exception.getMessage());
        }
    }

    private void stopComparison(RoutingContext context) {
        String comparisonId = context.pathParam("id");
        if (comparisonId == null || comparisonId.isBlank()
                || !runner.stopComparison(comparisonId)) {
            error(context, 404, "COMPARISON_NOT_FOUND",
                    "No active comparison matches that ID");
            return;
        }
        json(context, 202, new JsonObject()
                .put("comparisonId", comparisonId)
                .put("status", "stopped"));
    }

    private void resetSession(RoutingContext context) {
        runner.cancelForReset();
        store.reset();
        sseHub.publish("snapshot", snapshotPayload());
        context.response().setStatusCode(204).end();
    }

    private void spaFallback(RoutingContext context) {
        if (context.request().method() != HttpMethod.GET) {
            error(context, 404, "NOT_FOUND", "Route not found");
            return;
        }
        context.response()
                .putHeader(HttpHeaders.CONTENT_TYPE, "text/html; charset=utf-8")
                .sendFile("webroot/index.html")
                .onFailure(ignored -> error(context, 404, "NOT_FOUND", "Dashboard asset not found"));
    }

    private void failure(RoutingContext context) {
        int status = context.statusCode() > 0 ? context.statusCode() : 500;
        if (status == 413) {
            error(context, 413, "BODY_TOO_LARGE", "Request body exceeds the 16 KiB limit");
        } else {
            error(context, status, "REQUEST_FAILED", "Request could not be processed");
        }
    }

    private int maxTraffic() {
        return config.aiMode() == AppConfig.AiMode.FOUNDRY
                ? config.maxCloudRequestsPerRun()
                : RunRequest.MAX_TRAFFIC;
    }

    private int defaultComparisonTraffic() {
        int profileCount = provider.modelProfiles().size();
        if (profileCount < 1) {
            return 1;
        }
        return Math.max(1, Math.min(4, runner.providerInvocationLimit() / profileCount));
    }

    private JsonObject comparisonCapability() {
        JsonArray comparisonProfiles = new JsonArray();
        provider.modelProfiles().stream()
                .map(profile -> new JsonObject()
                        .put("id", profile.id())
                        .put("label", profile.label())
                        .put("kind", profile.router() ? "router" : "fixed")
                        .put("routeStrategy", profile.strategy()))
                .forEach(comparisonProfiles::add);
        int profileCount = comparisonProfiles.size();
        int maxTrafficPerProfile = profileCount == 0
                ? 0
                : runner.providerInvocationLimit() / profileCount;
        boolean available = profileCount >= 2 && maxTrafficPerProfile >= 1;
        String unavailableReason = profileCount < 2
                ? "provider_exposes_fewer_than_two_profiles"
                : maxTrafficPerProfile < 1
                        ? "provider_invocation_limit_too_low"
                        : null;
        return new JsonObject()
                .put("available", available)
                .put("unavailableReason", unavailableReason)
                .put("profiles", comparisonProfiles)
                .put("profileCount", profileCount)
                .put("trafficSemantics", "per-profile")
                .put("defaultTrafficPerProfile", defaultComparisonTraffic())
                .put("maxTrafficPerProfile", maxTrafficPerProfile)
                .put("providerInvocationLimit", runner.providerInvocationLimit());
    }

    private JsonObject snapshotPayload() {
        return snapshotSupplier.get();
    }

    private static String defaultPrompt(Workload workload) {
        return switch (workload) {
            case CHAT -> "Explain one practical Kafka reliability signal.";
            case SUMMARIZE -> "Summarize why consumer lag and retries should be observed together.";
            case EXTRACT -> "Extract the reliability signals: latency, throttling, lag, duplicates.";
        };
    }

    private static void json(RoutingContext context, int status, JsonObject payload) {
        context.response()
                .setStatusCode(status)
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
                .end(payload.encode());
    }

    private static void error(RoutingContext context, int status, String code, String message) {
        JsonObject payload = new JsonObject().put("error", new JsonObject()
                .put("code", code)
                .put("message", message == null ? "Request validation failed" : message));
        json(context, status, payload);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        if (server == null) {
            stopPromise.complete();
            return;
        }
        Future<Void> close = server.close();
        close.onComplete(stopPromise);
    }
}
