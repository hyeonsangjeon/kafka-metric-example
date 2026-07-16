package dev.hyeonsangjeon.observatory.provider;

import com.azure.ai.agents.AgentsClientBuilder;
import com.azure.ai.agents.ResponsesClient;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.RateLimitException;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Microsoft Foundry provider backed by the Azure AI Agents Responses client.
 * Prompt and response bodies are deliberately kept inside this boundary.
 */
public final class FoundryProvider implements AiProvider {
    static final String SAFE_ALIAS = "foundry";
    private final String fixedModel;
    private final String routerModel;
    private final List<ModelProfile> profiles;
    private final ResponsesClient responsesClient;
    private final ExecutorService executor;

    public FoundryProvider(String projectEndpoint, String model) {
        this(projectEndpoint, model, "", "balanced");
    }

    public FoundryProvider(
            String projectEndpoint,
            String fixedModel,
            String routerModel,
            String routerProfile) {
        this.fixedModel = requireDeployment(fixedModel, "fixedModel");
        this.routerModel = routerModel == null ? "" : routerModel.strip();
        String strategy = Objects.requireNonNull(routerProfile, "routerProfile")
                .strip().toLowerCase(Locale.ROOT);
        this.profiles = configuredProfiles(this.fixedModel, this.routerModel, strategy);
        this.responsesClient = new AgentsClientBuilder()
                .endpoint(Objects.requireNonNull(projectEndpoint, "projectEndpoint"))
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildResponsesClient();
        ThreadFactory factory = Thread.ofPlatform()
                .name("foundry-responses-", 0)
                .daemon(true)
                .factory();
        this.executor = Executors.newFixedThreadPool(4, factory);
    }

    @Override
    public String alias() {
        // Never expose the deployment name or endpoint in telemetry.
        return SAFE_ALIAS;
    }

    @Override
    public boolean synthetic() {
        return false;
    }

    @Override
    public List<ModelProfile> modelProfiles() {
        return profiles;
    }

    @Override
    public CompletableFuture<ProviderResult> invoke(ProviderRequest request) {
        ModelProfile profile = requireModelProfile(request.modelProfile());
        String deployment = profile.router() ? routerModel : fixedModel;
        return CompletableFuture.supplyAsync(() -> {
            try {
                ResponseCreateParams params = ResponseCreateParams.builder()
                        .model(deployment)
                        .instructions(instructions(request))
                        .input(request.prompt())
                        .maxOutputTokens(384)
                        .store(false)
                        .build();
                Response response = responsesClient.getResponseService().create(params);
                SafeModelFamily family = sanitizeModel(rawResponseModel(response.model()));
                ResponseUsage usage = response.usage().orElse(null);
                return new ProviderResult(
                        extractOutputText(response),
                        profile.strategy(),
                        family.id(),
                        family.label(),
                        usage == null ? null : usage.inputTokens(),
                        usage == null ? null : usage.outputTokens());
            } catch (RateLimitException exception) {
                throw new ProviderFailure("MODEL_RATE_LIMITED", true, exception);
            } catch (OpenAIServiceException exception) {
                throw new ProviderFailure("MODEL_HTTP_" + exception.statusCode(),
                        exception.statusCode() == 429, exception);
            } catch (RuntimeException exception) {
                throw new ProviderFailure("MODEL_REQUEST_FAILED", false, exception);
            }
        }, executor);
    }

    static SafeModelFamily sanitizeModel(String model) {
        String normalized = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (normalized.contains("gpt-5-mini")) {
            return new SafeModelFamily("gpt-5-mini", "GPT-5 mini");
        }
        if (normalized.contains("gpt-5-nano")) {
            return new SafeModelFamily("gpt-5-nano", "GPT-5 nano");
        }
        if (normalized.contains("gpt-5")) {
            return new SafeModelFamily("gpt-5", "GPT-5");
        }
        if (normalized.contains("gpt-4.1-mini") || normalized.contains("gpt-4-1-mini")) {
            return new SafeModelFamily("gpt-4-1-mini", "GPT-4.1 mini");
        }
        if (normalized.contains("gpt-4.1-nano") || normalized.contains("gpt-4-1-nano")) {
            return new SafeModelFamily("gpt-4-1-nano", "GPT-4.1 nano");
        }
        if (normalized.contains("gpt-4.1") || normalized.contains("gpt-4-1")) {
            return new SafeModelFamily("gpt-4-1", "GPT-4.1");
        }
        if (normalized.contains("gpt-4o-mini")) {
            return new SafeModelFamily("gpt-4o-mini", "GPT-4o mini");
        }
        if (normalized.contains("gpt-4o")) {
            return new SafeModelFamily("gpt-4o", "GPT-4o");
        }
        if (normalized.contains("o4-mini")) {
            return new SafeModelFamily("o4-mini", "o4-mini");
        }
        if (normalized.contains("o3")) {
            return new SafeModelFamily("o3", "o3");
        }
        if (normalized.contains("phi-4")) {
            return new SafeModelFamily("phi-4", "Phi-4");
        }
        if (normalized.contains("llama")) {
            return new SafeModelFamily("llama", "Llama");
        }
        if (normalized.contains("mistral")) {
            return new SafeModelFamily("mistral", "Mistral");
        }
        return new SafeModelFamily("other", "Other routed model");
    }

    private static String rawResponseModel(ResponsesModel model) {
        return model.string().orElseGet(() -> model.chat()
                .map(chatModel -> chatModel.asString())
                .orElseGet(() -> model.only()
                        .map(onlyModel -> onlyModel.asString())
                        .orElse("")));
    }

    private static String requireDeployment(String value, String name) {
        String deployment = Objects.requireNonNull(value, name).strip();
        if (deployment.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return deployment;
    }

    static List<ModelProfile> configuredProfiles(
            String fixedModel,
            String routerModel,
            String strategy) {
        requireDeployment(fixedModel, "fixedModel");
        List<ModelProfile> configuredProfiles = new ArrayList<>();
        configuredProfiles.add(new ModelProfile(
                "fixed",
                "Fixed deployment",
                "fixed",
                "Every request uses one configured Foundry deployment.",
                false));
        if (routerModel != null && !routerModel.isBlank()) {
            configuredProfiles.add(new ModelProfile(
                    "router-balanced",
                    "Model Router · " + title(strategy),
                    strategy,
                    "The configured Model Router deployment chooses a model; the strategy is deployment metadata.",
                    true));
        }
        return List.copyOf(configuredProfiles);
    }

    private static String title(String value) {
        if (value.isBlank()) {
            return "Balanced";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    record SafeModelFamily(String id, String label) {
    }

    private static String instructions(ProviderRequest request) {
        return switch (request.workload()) {
            case CHAT -> "Answer briefly and clearly.";
            case SUMMARIZE -> "Summarize the input in at most three bullet points.";
            case EXTRACT -> "Extract the main entities as compact JSON.";
        };
    }

    private static String extractOutputText(Response response) {
        StringBuilder text = new StringBuilder();
        for (ResponseOutputItem item : response.output()) {
            item.message().ifPresent(message -> appendText(message, text));
        }
        return text.toString();
    }

    private static void appendText(ResponseOutputMessage message, StringBuilder target) {
        for (ResponseOutputMessage.Content content : message.content()) {
            content.outputText().ifPresent(output -> target.append(output.text()));
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
