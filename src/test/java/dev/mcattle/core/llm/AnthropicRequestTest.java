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

    @Test
    void designAndActivitySchemasAreAccepted() {
        var design = AnthropicProvider.request("claude-opus-5-5", SystemPrompt.DESIGNER, "ctx", 16000L, "medium")
                .outputConfig(Design.class).build();
        assertTrue(design.rawParams().outputConfig().orElseThrow().format().isPresent());
        var turn = AnthropicProvider.request("claude-opus-5-5", SystemPrompt.DIRECTOR, "ctx", 4096L, "low")
                .outputConfig(ActivityTurn.class).build();
        assertTrue(turn.rawParams().outputConfig().orElseThrow().format().isPresent());
        assertTrue(SystemPrompt.DIRECTOR.contains("- place:"));
    }
}
