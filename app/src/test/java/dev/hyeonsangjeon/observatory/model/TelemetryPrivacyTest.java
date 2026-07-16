package dev.hyeonsangjeon.observatory.model;

import dev.hyeonsangjeon.observatory.util.Hashing;
import io.vertx.core.json.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryPrivacyTest {
    @Test
    void serializationContainsOnlyHashesAndCharacterCounts() {
        String rawPrompt = "private customer prompt: account 839201";
        String rawResponse = "private model response: approved";
        TelemetryEvent event = TelemetryEvent.create(
                EventType.RESPONSE_COMPLETED,
                "session-1",
                "run-1",
                "trace-1",
                "foundry-deployment",
                Workload.CHAT,
                Scenario.HEALTHY,
                2,
                null,
                TelemetryEvent.EventDetails.response(
                        Hashing.sha256(rawPrompt),
                        rawPrompt.length(),
                        Hashing.sha256(rawResponse),
                        rawResponse.length(),
                        185,
                        1,
                        false));

        String json = Json.encode(event);

        assertFalse(json.contains(rawPrompt));
        assertFalse(json.contains(rawResponse));
        assertFalse(json.contains("account 839201"));
        assertTrue(json.contains(Hashing.sha256(rawPrompt)));
        assertTrue(json.contains("\"prompt_chars\":" + rawPrompt.length()));
        assertTrue(json.contains(Hashing.sha256(rawResponse)));
        assertTrue(json.contains("\"response_chars\":" + rawResponse.length()));
    }

    @Test
    void routingMetadataRejectsRawOrUnsafeIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> TelemetryEvent.EventDetails.response(
                "sha256:abc",
                10,
                "sha256:def",
                20,
                100,
                1,
                false,
                "router-balanced",
                "balanced",
                "private deployment/name",
                "Private model",
                3L,
                4L));
    }
}
