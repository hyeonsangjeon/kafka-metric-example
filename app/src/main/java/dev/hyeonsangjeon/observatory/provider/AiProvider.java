package dev.hyeonsangjeon.observatory.provider;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface AiProvider extends AutoCloseable {
    String alias();

    boolean synthetic();

    default List<ModelProfile> modelProfiles() {
        return List.of(new ModelProfile(
                "fixed", "Fixed model", "fixed", "Every request uses one model.", false));
    }

    default String defaultModelProfile() {
        return modelProfiles().getFirst().id();
    }

    default ModelProfile requireModelProfile(String id) {
        return modelProfiles().stream()
                .filter(profile -> profile.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported model profile: " + id));
    }

    CompletableFuture<ProviderResult> invoke(ProviderRequest request);

    @Override
    default void close() {
    }
}
