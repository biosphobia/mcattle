package dev.mcattle.core.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** One round of an ongoing activity, decided by the director model. */
public record ActivityTurn(
        @JsonPropertyDescription("Your private notes carried to the next round: rules, game state, score, plan. Rewrite them fully each time (max ~120 words).")
        String notes,
        @JsonPropertyDescription("What each participating NPC says and does now (same format as normal orders)")
        List<Orders.NpcOrder> orders,
        @JsonPropertyDescription("When to call you again: 'steps' (all NPCs finished their steps), 'chat' (a player says something), 'blocks' (something changes in the watched area), 'time' (after waitSeconds)")
        String waitFor,
        @JsonPropertyDescription("Maximum seconds to wait before being called again anyway (5-300)")
        int waitSeconds,
        @JsonPropertyDescription("Absolute area to watch for block changes 'x1 y1 z1 x2 y2 z2' (max 24 blocks per side), or '' to keep the current one")
        String watchArea,
        @JsonPropertyDescription("true when the activity is over")
        boolean done) {
}
