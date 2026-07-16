package dev.hyeonsangjeon.observatory.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {
    @Test
    void safeLocalDefaultsRequireNoCloudConfiguration() {
        AppConfig config = AppConfig.from(Map.of());

        config.validate();
        assertEquals("127.0.0.1", config.host());
        assertEquals(AppConfig.AiMode.SIMULATED, config.aiMode());
        assertEquals(AppConfig.TransportMode.MEMORY, config.requestedTransport());
        assertEquals(10, config.maxCloudRequestsPerRun());
    }

    @Test
    void foundryModeRequiresAnEndpointAndDeployment() {
        AppConfig config = AppConfig.from(Map.of("AI_MODE", "foundry"));

        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void aiProviderTakesPrecedenceButLegacyAiModeStillWorks() {
        AppConfig preferred = AppConfig.from(Map.of(
                "AI_PROVIDER", "simulated",
                "AI_MODE", "foundry"));
        AppConfig legacy = AppConfig.from(Map.of(
                "AI_MODE", "ollama",
                "OLLAMA_MODEL", "local-test-model"));

        preferred.validate();
        legacy.validate();
        assertEquals(AppConfig.AiMode.SIMULATED, preferred.aiMode());
        assertEquals(AppConfig.AiMode.OLLAMA, legacy.aiMode());
    }

    @Test
    void emptyPreferredProviderFallsBackToLegacyMode() {
        AppConfig config = AppConfig.from(Map.of(
                "AI_PROVIDER", " ",
                "AI_MODE", "ollama",
                "OLLAMA_MODEL", "local-test-model"));

        config.validate();
        assertEquals(AppConfig.AiMode.OLLAMA, config.aiMode());
    }

    @Test
    void ollamaDefaultsToLoopbackAndRequiresAModel() {
        AppConfig missingModel = AppConfig.from(Map.of("AI_PROVIDER", "ollama"));
        AppConfig configured = AppConfig.from(Map.of(
                "AI_PROVIDER", "ollama",
                "OLLAMA_MODEL", "local-test-model"));

        assertThrows(IllegalArgumentException.class, missingModel::validate);
        configured.validate();
        assertEquals(AppConfig.DEFAULT_OLLAMA_BASE_URL, configured.ollamaBaseUrl());
        assertTrue(configured.ollamaRequireLoopback());
    }

    @Test
    void ollamaRejectsRemoteEndpointsUnlessExplicitlyAllowed() {
        AppConfig rejected = AppConfig.from(Map.of(
                "AI_PROVIDER", "ollama",
                "OLLAMA_MODEL", "local-test-model",
                "OLLAMA_BASE_URL", "http://192.0.2.1:11434/v1"));
        AppConfig allowed = AppConfig.from(Map.of(
                "AI_PROVIDER", "ollama",
                "OLLAMA_MODEL", "local-test-model",
                "OLLAMA_BASE_URL", "http://192.0.2.1:11434/v1",
                "OLLAMA_REQUIRE_LOOPBACK", "false"));

        assertThrows(IllegalArgumentException.class, rejected::validate);
        allowed.validate();
        assertFalse(allowed.ollamaRequireLoopback());
    }

    @Test
    void foundryRouterIsOptionalAndProfileIsOnlySafeMetadata() {
        AppConfig fixed = AppConfig.from(Map.of(
                "AI_PROVIDER", "foundry",
                "FOUNDRY_PROJECT_ENDPOINT", "https://safe.services.ai.azure.com/api/projects/demo",
                "FOUNDRY_MODEL", "private-fixed-deployment"));
        AppConfig routed = AppConfig.from(Map.of(
                "AI_PROVIDER", "foundry",
                "FOUNDRY_PROJECT_ENDPOINT", "https://safe.services.ai.azure.com/api/projects/demo",
                "FOUNDRY_MODEL", "private-fixed-deployment",
                "FOUNDRY_ROUTER_MODEL", "private-router-deployment",
                "FOUNDRY_ROUTER_PROFILE", "quality"));

        fixed.validate();
        routed.validate();
        assertFalse(fixed.foundryRouterConfigured());
        assertTrue(routed.foundryRouterConfigured());
        assertEquals("quality", routed.foundryRouterProfile());
    }

    @Test
    void invalidRouterProfileAndBooleanAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AppConfig.from(Map.of(
                "FOUNDRY_ROUTER_PROFILE", "secret-custom-mode")).validate());
        assertThrows(IllegalArgumentException.class, () -> AppConfig.from(Map.of(
                "OLLAMA_REQUIRE_LOOPBACK", "yes")));
    }

    @Test
    void bindHostCannotContainWhitespace() {
        AppConfig config = AppConfig.from(Map.of("APP_HOST", "0.0.0.0 public"));

        assertThrows(IllegalArgumentException.class, config::validate);
    }
}
