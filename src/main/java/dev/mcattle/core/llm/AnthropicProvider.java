package dev.mcattle.core.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Claude API backend using the official Anthropic Java SDK.
 *
 * <p>Token economy: the large planner instructions are a cached system block, the per-request situation report is
 * compact text, reasoning effort defaults to low and responses are schema-constrained JSON (no wasted prose).</p>
 */
public final class AnthropicProvider implements LlmProvider {
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final String model;
    private final String banterModel;
    private final String effort;
    private final String designEffort;

    public AnthropicProvider(String apiKey, String model, String banterModel, String effort, String designEffort, int timeoutSeconds) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(Math.max(10, timeoutSeconds)))
                .maxRetries(2)
                .build();
        this.model = model;
        this.banterModel = banterModel;
        this.effort = effort == null || effort.isBlank() ? "low" : effort.trim().toLowerCase(java.util.Locale.ROOT);
        this.designEffort = designEffort == null || designEffort.isBlank() ? "medium" : designEffort.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public LlmResult<Design> design(String context) {
        // designs are long JSON: give them room (non-streaming stays well under the SDK timeout at this size)
        return run(request(model, SystemPrompt.DESIGNER, context, 16000L, designEffort).outputConfig(Design.class).build());
    }

    @Override
    public LlmResult<StyleLines> restyle(String context) {
        return run(request(banterModel, SystemPrompt.STYLE, context, 2048L, "low").outputConfig(StyleLines.class).build());
    }

    @Override
    public LlmResult<ActivityTurn> direct(String context) {
        return run(request(model, SystemPrompt.DIRECTOR, context, 4096L, effort).outputConfig(ActivityTurn.class).build());
    }

    @Override
    public LlmResult<Orders> plan(String situation) {
        StructuredMessageCreateParams<Orders> params = request(model, SystemPrompt.PLANNER, situation, 4096L, effort)
                .outputConfig(Orders.class)
                .build();
        return run(params);
    }

    @Override
    public LlmResult<Banter> banter(String situation) {
        StructuredMessageCreateParams<Banter> params = request(banterModel, SystemPrompt.BANTER, situation, 1024L, "low")
                .outputConfig(Banter.class)
                .build();
        return run(params);
    }

    static MessageCreateParams.Builder request(String model, String system, String user, long maxTokens, String effort) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(system)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort)).build())
                .addUserMessage(user);
        if (supportsServerSideFallback(model)) {
            // If a safety classifier declines, let the API retry on a suitable model instead of returning nothing.
            builder.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return builder;
    }

    static boolean supportsServerSideFallback(String model) {
        return model.startsWith("claude-opus-5") || model.equals("claude-sonnet-5-5") || model.equals("claude-fable-5-1");
    }

    private <T> LlmResult<T> run(StructuredMessageCreateParams<T> params) {
        StructuredMessage<T> response;
        try {
            response = client.messages().create(params);
        } catch (AnthropicServiceException e) {
            return new LlmResult<>(null, 0, 0, 0, "API error " + e.statusCode() + ": " + e.getMessage());
        }
        Usage usage = response.usage();
        long in = usage.inputTokens() + usage.cacheCreationInputTokens().orElse(0L);
        long cached = usage.cacheReadInputTokens().orElse(0L);
        long out = usage.outputTokens();

        Optional<StopReason> stop = response.stopReason();
        if (stop.isPresent() && stop.get().equals(StopReason.REFUSAL)) {
            String why = response.stopDetails().map(d -> String.valueOf(d.category())).orElse("unspecified");
            return new LlmResult<>(null, in, cached, out, "declined (" + why + ")");
        }
        if (stop.isPresent() && stop.get().equals(StopReason.MAX_TOKENS)) {
            return new LlmResult<>(null, in, cached, out, "response was cut off");
        }
        T value = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(typed -> typed.text())
                .findFirst()
                .orElse(null);
        return new LlmResult<>(value, in, cached, out, value == null ? "empty response" : "");
    }

    @Override
    public String describe() {
        return "Claude API (" + model + ", effort " + effort + ")";
    }
}
