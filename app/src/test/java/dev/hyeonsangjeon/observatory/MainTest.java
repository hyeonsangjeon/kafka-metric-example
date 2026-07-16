package dev.hyeonsangjeon.observatory;

import dev.hyeonsangjeon.observatory.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainTest {
    @Test
    void foundryProviderWiringPreservesBothRouterDeployments() {
        AppConfig config = AppConfig.from(Map.of(
                "AI_PROVIDER", "foundry",
                "FOUNDRY_PROJECT_ENDPOINT", "https://safe.services.ai.azure.com/api/projects/demo",
                "FOUNDRY_MODEL", "private-fixed-deployment",
                "FOUNDRY_ROUTER_DEFAULT_MODEL", "private-default-router",
                "FOUNDRY_ROUTER_ADVANCED_MODEL", "private-advanced-router",
                "FOUNDRY_ROUTER_ADVANCED_PROFILE", "quality"));
        config.validate();
        Main.FoundryProviderSettings settings = Main.foundryProviderSettings(config);

        assertEquals("https://safe.services.ai.azure.com/api/projects/demo",
                settings.projectEndpoint());
        assertEquals("private-fixed-deployment", settings.fixedModel());
        assertEquals("private-default-router", settings.defaultRouterModel());
        assertEquals("private-advanced-router", settings.advancedRouterModel());
        assertEquals("quality", settings.advancedRouterProfile());
    }
}
