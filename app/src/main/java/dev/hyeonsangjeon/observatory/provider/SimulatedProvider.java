package dev.hyeonsangjeon.observatory.provider;

import io.vertx.core.Vertx;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class SimulatedProvider implements AiProvider {
    private static final List<ModelProfile> PROFILES = List.of(
            new ModelProfile(
                    "fixed",
                    "Fixed model",
                    "fixed",
                    "Every request uses the same simulated model.",
                    false),
            new ModelProfile(
                    "router-default",
                    "Model Router · Default",
                    "balanced",
                    "Workload shape deterministically selects Fast, General, or Reasoning.",
                    true),
            new ModelProfile(
                    "router-advanced",
                    "Model Router · Advanced",
                    "cost",
                    "A cost-oriented simulated route favors faster model families.",
                    true));

    private final Vertx vertx;

    public SimulatedProvider(Vertx vertx) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
    }

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
        return PROFILES;
    }

    @Override
    public ModelProfile requireModelProfile(String id) {
        if ("router-balanced".equals(id)) {
            return PROFILES.get(1);
        }
        return AiProvider.super.requireModelProfile(id);
    }

    @Override
    public CompletableFuture<ProviderResult> invoke(ProviderRequest request) {
        ModelProfile profile = requireModelProfile(request.modelProfile());
        SafeRoute route = route(request, profile);
        CompletableFuture<ProviderResult> result = new CompletableFuture<>();
        long baseDelayMs = switch (route.id()) {
            case "fast" -> 65L;
            case "reasoning" -> 230L;
            default -> 125L;
        };
        long delayMs = baseDelayMs + Math.floorMod(request.ordinal() * 37L, 85L);
        vertx.setTimer(delayMs, ignored -> {
            String output = switch (request.workload()) {
                case CHAT -> "Simulated assistant result for trace " + request.ordinal();
                case SUMMARIZE -> "Simulated concise summary " + request.ordinal();
                case EXTRACT -> "{\"simulated\":true,\"item\":" + request.ordinal() + "}";
            };
            result.complete(new ProviderResult(
                    output,
                    profile.strategy(),
                    route.id(),
                    route.label(),
                    Math.max(1L, request.prompt().length() / 4L),
                    Math.max(1L, output.length() / 4L)));
        });
        return result;
    }

    private static SafeRoute route(ProviderRequest request, ModelProfile profile) {
        if (!profile.router()) {
            return new SafeRoute("fixed", "Fixed");
        }
        if ("cost".equals(profile.strategy())) {
            return request.ordinal() % 4 == 0
                    ? new SafeRoute("general", "General")
                    : new SafeRoute("fast", "Fast");
        }
        return switch (request.workload()) {
            case CHAT -> request.ordinal() % 5 == 0
                    ? new SafeRoute("reasoning", "Reasoning")
                    : request.ordinal() % 2 == 0
                            ? new SafeRoute("general", "General")
                            : new SafeRoute("fast", "Fast");
            case SUMMARIZE -> request.ordinal() % 4 == 0
                    ? new SafeRoute("reasoning", "Reasoning")
                    : request.ordinal() % 3 == 0
                            ? new SafeRoute("fast", "Fast")
                            : new SafeRoute("general", "General");
            case EXTRACT -> request.ordinal() % 6 == 0
                    ? new SafeRoute("reasoning", "Reasoning")
                    : request.ordinal() % 4 == 0
                            ? new SafeRoute("general", "General")
                            : new SafeRoute("fast", "Fast");
        };
    }

    private record SafeRoute(String id, String label) {
    }
}
