package dev.mcattle.core.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** A few chat lines NPCs say to each other / nearby players when idle. */
public record Banter(List<Line> lines) {
    public record Line(
            @JsonPropertyDescription("Exact NPC name of the speaker")
            String npc,
            @JsonPropertyDescription("The chat line")
            String text,
            @JsonPropertyDescription("How saying/hearing this leaves the speaker feeling: who:feeling[:strength] tokens (who = a name or 'self'), e.g. 'OakJames:teased self:amused'. Empty if nothing notable.")
            String feel) {
    }
}
