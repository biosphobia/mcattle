package dev.mcattle.core.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * What the language model (or the built-in parser) decided the NPCs should do.
 * Records are used directly as the structured-output schema for the Claude API.
 */
public record Orders(
        @JsonPropertyDescription("One entry per NPC that should react. NPCs not listed keep doing what they were doing.")
        List<NpcOrder> orders) {

    public record NpcOrder(
            @JsonPropertyDescription("Exact NPC name")
            String npc,
            @JsonPropertyDescription("What the NPC says out loud in chat (short, in character). Empty string for nothing.")
            String say,
            @JsonPropertyDescription("'replace' drops the current task list, 'append' queues after it, 'none' only talks")
            String mode,
            @JsonPropertyDescription("Ordered skill calls")
            List<Step> steps,
            @JsonPropertyDescription("New long-term memories for this NPC: lasting instructions (speaking style, habits, rules), names, facts worth keeping. Short, include who said it. Empty list if none.")
            List<String> remember,
            @JsonPropertyDescription("Existing memories to delete, quoted exactly or by a distinctive phrase. Empty list if none.")
            List<String> forget,
            @JsonPropertyDescription("Behavior settings to change, space separated key=value: socialness=0|1|2 retaliate=true|false autonomous=true|false follow=<player>|none guard=<player>|none home=here|none nickname=<word>|none chatty=0|1|2. Empty if none.")
            String settings) {

        public NpcOrder(String npc, String say, String mode, List<Step> steps) {
            this(npc, say, mode, steps, List.of(), List.of(), "");
        }
    }

    public record Step(
            @JsonPropertyDescription("Skill name from the skill list")
            String action,
            @JsonPropertyDescription("Player / NPC / mob name, 'speaker', 'x y z', 'home', or empty")
            String target,
            @JsonPropertyDescription("Item or block id like 'minecraft:oak_log', a tag like '#minecraft:logs', or plain words; empty if unused")
            String item,
            @JsonPropertyDescription("Amount, 0 if unused")
            int count,
            @JsonPropertyDescription("Extra key=value options separated by spaces, e.g. 'lethal=false' or 'type=dance'; empty if unused")
            String options,
            @JsonPropertyDescription("Free text: what to build (build), the goal of an activity (activity), or the words to say (say); empty if unused")
            String text) {

        public static Step of(String action) {
            return new Step(action, "", "", 0, "", "");
        }

        public static Step of(String action, String target, String item, int count, String options) {
            return new Step(action, nz(target), nz(item), count, nz(options), "");
        }

        public static Step of(String action, String target, String item, int count, String options, String text) {
            return new Step(action, nz(target), nz(item), count, nz(options), nz(text));
        }

        public String text() { return nz(text); }

        public String target() { return nz(target); }
        public String item() { return nz(item); }
        public String options() { return nz(options); }
        public String action() { return nz(action); }

        /** Reads one key from {@link #options()} ("key=value key2=value2"). */
        public String option(String key, String fallback) {
            for (String part : options().trim().split("\\s+")) {
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).equalsIgnoreCase(key)) return part.substring(eq + 1);
            }
            return fallback;
        }

        public int intOption(String key, int fallback) {
            try {
                return Integer.parseInt(option(key, Integer.toString(fallback)));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        public boolean boolOption(String key, boolean fallback) {
            String v = option(key, null);
            if (v == null) return fallback;
            return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equals("1");
        }

        private static String nz(String s) {
            return s == null ? "" : s;
        }
    }
}
