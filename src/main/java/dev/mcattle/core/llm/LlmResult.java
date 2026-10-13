package dev.mcattle.core.llm;

/**
 * A parsed model response plus what it cost.
 *
 * @param value      parsed result, or null when the model declined / produced nothing usable
 * @param inputTokens  uncached input tokens
 * @param cachedTokens input tokens served from the prompt cache
 * @param outputTokens output tokens (including any reasoning)
 * @param note       human readable remark (refusal reason, parse error...), may be empty
 */
public record LlmResult<T>(T value, long inputTokens, long cachedTokens, long outputTokens, String note) {
    public long totalTokens() {
        return inputTokens + cachedTokens + outputTokens;
    }
}
