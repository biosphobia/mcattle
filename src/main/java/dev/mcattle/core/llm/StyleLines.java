package dev.mcattle.core.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** Canned chat lines rewritten in an NPC's personal speaking style, so free (non-LLM) replies stay in character. */
public record StyleLines(
        @JsonPropertyDescription("5 ways to acknowledge an order (like 'on it')")
        List<String> ack,
        @JsonPropertyDescription("5 ways to say a job is finished")
        List<String> done,
        @JsonPropertyDescription("5 ways to say 'huh? didn't understand'")
        List<String> confused,
        @JsonPropertyDescription("5 reactions to being hit by a player; may contain {player}")
        List<String> hit,
        @JsonPropertyDescription("5 lines after dying")
        List<String> death,
        @JsonPropertyDescription("5 greetings for a player; may contain {player}")
        List<String> greeting,
        @JsonPropertyDescription("5 ways to say 'I can only do simple things right now'")
        List<String> simpleOnly) {
}
