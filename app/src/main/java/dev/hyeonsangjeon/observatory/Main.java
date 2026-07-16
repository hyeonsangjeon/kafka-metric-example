package dev.hyeonsangjeon.observatory;

import dev.hyeonsangjeon.observatory.api.ApiVerticle;
import dev.hyeonsangjeon.observatory.config.AppConfig;
import dev.hyeonsangjeon.observatory.projection.ProjectionStore;
import dev.hyeonsangjeon.observatory.provider.AiProvider;
import dev.hyeonsangjeon.observatory.provider.FoundryProvider;
import dev.hyeonsangjeon.observatory.provider.OllamaProvider;
import dev.hyeonsangjeon.observatory.provider.SimulatedProvider;
import dev.hyeonsangjeon.observatory.runner.WorkloadRunner;
import dev.hyeonsangjeon.observatory.stream.SseHub;
import dev.hyeonsangjeon.observatory.transport.TransportSelection;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnvironment();
        config.validate();

        Vertx vertx = Vertx.vertx();
        ProjectionStore store = new ProjectionStore();
        SseHub sseHub = new SseHub(vertx);
        AiProvider provider = createProvider(config, vertx);
        TransportSelection transportSelection = TransportSelection.create(config, vertx);
        WorkloadRunner runner = new WorkloadRunner(
                vertx, provider, transportSelection.transport(), store::sessionId);
        Supplier<JsonObject> snapshotSupplier = () -> snapshot(
                store, config, transportSelection);

        transportSelection.transport().start(delivery -> vertx.runOnContext(ignored -> {
            boolean accepted = store.accept(delivery);
            if (accepted) {
                sseHub.publish("telemetry", delivery.event());
            }
            sseHub.publish("snapshot", snapshotSupplier.get());
        }));

        vertx.setPeriodic(2_000L, ignored -> sseHub.publish("snapshot", snapshotSupplier.get()));

        ApiVerticle api = new ApiVerticle(
                config, transportSelection, provider, store, runner, sseHub, snapshotSupplier);
        AtomicBoolean closed = new AtomicBoolean();
        Runnable shutdown = () -> close(closed, runner, transportSelection, provider, sseHub, vertx);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform()
                .name("foundry-stream-lab-shutdown")
                .unstarted(shutdown));

        vertx.deployVerticle(api)
                .onSuccess(deploymentId -> LOGGER.info(
                        "Foundry Stream Lab listening on port {} (provider={}, transport={})",
                        config.port(), provider.alias(), transportSelection.transport().name()))
                .onFailure(failure -> {
                    LOGGER.error("Application failed to start", failure);
                    shutdown.run();
                });
    }

    private static AiProvider createProvider(AppConfig config, Vertx vertx) {
        return switch (config.aiMode()) {
            case SIMULATED -> new SimulatedProvider(vertx);
            case OLLAMA -> new OllamaProvider(config.ollamaBaseUrl(), config.ollamaModel());
            case FOUNDRY -> new FoundryProvider(
                    config.foundryProjectEndpoint(),
                    config.foundryModel(),
                    config.foundryRouterModel(),
                    config.foundryRouterProfile());
        };
    }

    private static JsonObject snapshot(
            ProjectionStore store,
            AppConfig config,
            TransportSelection selection) {
        return store.snapshot().put("health", new JsonObject()
                .put("kafka", selection.transport().healthy() ? "healthy" : "offline")
                .put("foundry", config.aiMode() != AppConfig.AiMode.FOUNDRY
                        || config.foundryConfigured() ? "healthy" : "degraded"));
    }

    private static void close(
            AtomicBoolean closed,
            WorkloadRunner runner,
            TransportSelection selection,
            AiProvider provider,
            SseHub hub,
            Vertx vertx) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        runner.cancelForReset();
        hub.close();
        selection.transport().close();
        provider.close();
        CountDownLatch latch = new CountDownLatch(1);
        vertx.close().onComplete(ignored -> latch.countDown());
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
