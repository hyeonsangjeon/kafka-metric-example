package dev.hyeonsangjeon.observatory.provider;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonArray;

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
        String routerDeployment = "customer-router-deployment-839201";
        String publicMetadata = new JsonArray(FoundryProvider.configuredProfiles(
                fixedDeployment, routerDeployment, "balanced").stream()
                .map(profile -> profile.toJson())
                .toList()).encode() + FoundryProvider.SAFE_ALIAS;

        assertEquals("foundry", FoundryProvider.SAFE_ALIAS);
        assertFalse(publicMetadata.contains(endpoint));
        assertFalse(publicMetadata.contains("private-project-839201"));
        assertFalse(publicMetadata.contains(fixedDeployment));
        assertFalse(publicMetadata.contains(routerDeployment));
    }
}
