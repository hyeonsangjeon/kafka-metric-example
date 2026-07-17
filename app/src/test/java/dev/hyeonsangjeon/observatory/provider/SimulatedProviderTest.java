package dev.hyeonsangjeon.observatory.provider;

import dev.hyeonsangjeon.observatory.model.Workload;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatedProviderTest {
    private Vertx vertx;
    private SimulatedProvider provider;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        provider = new SimulatedProvider(vertx);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        CountDownLatch closed = new CountDownLatch(1);
        vertx.close().onComplete(ignored -> closed.countDown());
        assertTrue(closed.await(2, TimeUnit.SECONDS));
    }

    @Test
    void exposesAllThreeComparisonProfilesAndKeepsTheLegacyAlias() {
        assertEquals(
                java.util.List.of("fixed", "router-default", "router-advanced"),
                provider.modelProfiles().stream().map(ModelProfile::id).toList());
        assertEquals("fixed", provider.defaultModelProfile());
        assertEquals("router-default", provider.requireModelProfile("router-balanced").id());
    }

    @Test
    void routerSelectionIsDeterministicByWorkloadAndOrdinal() throws Exception {
        ProviderResult fast = invoke(Workload.CHAT, 1, "router-balanced");
        ProviderResult general = invoke(Workload.CHAT, 2, "router-balanced");
        ProviderResult reasoning = invoke(Workload.CHAT, 5, "router-balanced");
        ProviderResult advanced = invoke(Workload.CHAT, 1, "router-advanced");
        ProviderResult fixed = invoke(Workload.CHAT, 5, "fixed");

        assertEquals("fast", fast.selectedRoute());
        assertEquals("general", general.selectedRoute());
        assertEquals("reasoning", reasoning.selectedRoute());
        assertEquals("balanced", reasoning.routeStrategy());
        assertEquals("fast", advanced.selectedRoute());
        assertEquals("cost", advanced.routeStrategy());
        assertEquals("fixed", fixed.selectedRoute());
        assertEquals("fixed", fixed.routeStrategy());
    }

    @Test
    void unknownProfileIsRejectedBeforeInvocation() {
        ProviderRequest request = new ProviderRequest(
                "trace", 1, Workload.CHAT, "prompt", "secret-profile");

        assertThrows(IllegalArgumentException.class, () -> provider.invoke(request));
    }

    private ProviderResult invoke(Workload workload, int ordinal, String profile) throws Exception {
        return provider.invoke(new ProviderRequest(
                        "trace-" + ordinal, ordinal, workload, "prompt", profile))
                .get(2, TimeUnit.SECONDS);
    }
}
