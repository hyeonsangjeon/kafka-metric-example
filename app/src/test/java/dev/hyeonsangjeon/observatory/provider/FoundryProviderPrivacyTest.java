package dev.hyeonsangjeon.observatory.provider;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonArray;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FoundryProviderPrivacyTest {
    @Test
    void sanitizerReturnsOnlyHardcodedFamiliesAndNeverDeploymentNames() {
        String privateDeployment = "customer-839201-prod-gpt-4.1-mini-classified";
        FoundryProvider.SafeModelFamily known = FoundryProvider.sanitizeModel(privateDeployment);
        FoundryProvider.SafeModelFamily unknown = FoundryProvider.sanitizeModel(
                "customer-839201-prod-secret-model");

        assertEquals("gpt-4-1-mini", known.id());
        assertEquals("GPT-4.1 mini", known.label());
        assertEquals("other", unknown.id());
        assertEquals("Other routed model", unknown.label());
        assertFalse(known.toString().contains("customer-839201"));
        assertFalse(unknown.toString().contains("secret-model"));
    }

    @Test
    void publicProviderMetadataNeverIncludesEndpointOrDeploymentNames() {
        String endpoint = "https://safe.services.ai.azure.com/api/projects/private-project-839201";
        String fixedDeployment = "customer-fixed-deployment-839201";
        String defaultRouterDeployment = "customer-default-router-deployment-839201";
        String advancedRouterDeployment = "customer-advanced-router-deployment-839201";
        String publicMetadata = new JsonArray(FoundryProvider.configuredProfiles(
                fixedDeployment,
                defaultRouterDeployment,
                advancedRouterDeployment,
                "quality").stream()
                .map(profile -> profile.toJson())
                .toList()).encode() + FoundryProvider.SAFE_ALIAS;

        assertEquals("foundry", FoundryProvider.SAFE_ALIAS);
        assertFalse(publicMetadata.contains(endpoint));
        assertFalse(publicMetadata.contains("private-project-839201"));
        assertFalse(publicMetadata.contains(fixedDeployment));
        assertFalse(publicMetadata.contains(defaultRouterDeployment));
        assertFalse(publicMetadata.contains(advancedRouterDeployment));
    }

    @Test
    void dualRouterProfilesHaveStablePublicIdsAndDeploymentStrategies() {
        List<ModelProfile> profiles = FoundryProvider.configuredProfiles(
                "private-fixed",
                "private-default-router",
                "private-advanced-router",
                "cost");

        assertEquals(
                List.of("fixed", "router-default", "router-advanced"),
                profiles.stream().map(ModelProfile::id).toList());
        assertEquals(
                List.of("fixed", "balanced", "cost"),
                profiles.stream().map(ModelProfile::strategy).toList());
    }

    @Test
    void deploymentsAreSelectedByProfileWithoutEnteringPublicMetadata() {
        Map<String, String> deployments = FoundryProvider.configuredDeployments(
                "private-fixed",
                "private-default-router",
                "private-advanced-router");

        assertEquals("private-fixed", deployments.get("fixed"));
        assertEquals("private-default-router", deployments.get("router-default"));
        assertEquals("private-advanced-router", deployments.get("router-advanced"));
    }

    @Test
    void legacySingleRouterConfigurationMapsToTheMatchingNewProfile() {
        List<ModelProfile> balanced = FoundryProvider.configuredProfiles(
                "private-fixed", "private-router", "balanced");
        List<ModelProfile> quality = FoundryProvider.configuredProfiles(
                "private-fixed", "private-router", "quality");

        assertEquals(List.of("fixed", "router-default"),
                balanced.stream().map(ModelProfile::id).toList());
        assertEquals(List.of("fixed", "router-advanced"),
                quality.stream().map(ModelProfile::id).toList());
        assertEquals("quality", quality.getLast().strategy());
    }

    @Test
    void sanitizerRecognizesCurrentRouterFamiliesUsingBoundedAliases() {
        Map<String, FoundryProvider.SafeModelFamily> expected = Map.of(
                "gpt-5.4-nano-2026-05-01",
                new FoundryProvider.SafeModelFamily("gpt-5-4-nano", "GPT-5.4 nano"),
                "gpt-5.4-mini-2026-05-01",
                new FoundryProvider.SafeModelFamily("gpt-5-4-mini", "GPT-5.4 mini"),
                "gpt-5.4-2026-05-01",
                new FoundryProvider.SafeModelFamily("gpt-5-4", "GPT-5.4"),
                "gpt-5.5-2026-07-01",
                new FoundryProvider.SafeModelFamily("gpt-5-5", "GPT-5.5"),
                "DeepSeek-V3.2",
                new FoundryProvider.SafeModelFamily("deepseek", "DeepSeek"),
                "grok-4-fast-reasoning",
                new FoundryProvider.SafeModelFamily("grok", "Grok"),
                "claude-opus-4-6",
                new FoundryProvider.SafeModelFamily("claude", "Claude"));

        expected.forEach((rawModel, family) ->
                assertEquals(family, FoundryProvider.sanitizeModel(rawModel)));
    }
}
