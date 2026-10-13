package dev.mcattle.core.llm;

import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicRequestTest {
    @Test
    void requestCarriesEffortSchemaAndCache() {
        StructuredMessageCreateParams<Orders> params = AnthropicProvider
                .request("claude-opus-5-5", SystemPrompt.PLANNER, "situation", 4096L, "low")
                .outputConfig(Orders.class)
                .build();
        OutputConfig output = params.rawParams().outputConfig().orElseThrow();
        System.out.println("OUTPUT_CONFIG " + output);
        assertEquals(OutputConfig.Effort.LOW, output.effort().orElseThrow(), "effort kept");
        assertTrue(output.format().isPresent(), "json schema format present");
        assertTrue(params.rawParams().system().orElseThrow().isTextBlockParams(), "system prompt as cacheable blocks");
    }
}
