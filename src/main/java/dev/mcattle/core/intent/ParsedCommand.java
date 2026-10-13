package dev.mcattle.core.intent;

import dev.mcattle.core.llm.Orders;

import java.util.List;

/**
 * Result of the built-in (free, instant) command parser.
 *
 * @param addressed   NPC names explicitly addressed (empty = nobody named)
 * @param everyone    the player addressed all NPCs ("everyone", "guys"...)
 * @param steps       recognized plan, or null when the parser did not understand the request
 * @param complex     the sentence looks like it has several parts or nuance a language model handles better
 * @param question    the message is conversational (question / small talk) rather than an order
 * @param cleaned     the message with names and filler words removed
 */
public record ParsedCommand(List<String> addressed, boolean everyone, List<Orders.Step> steps, boolean complex,
                            boolean question, String cleaned) {
    public boolean understood() {
        return steps != null && !steps.isEmpty();
    }

    public boolean looksLikeCommand() {
        return understood() || complex;
    }
}
