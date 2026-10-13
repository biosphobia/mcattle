package dev.mcattle.npc;

import dev.mcattle.MCattle;
import dev.mcattle.core.llm.LlmService;
import dev.mcattle.core.llm.StyleLines;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Long-term memory: lasting instructions ("talk like a pirate from now on"), facts and behavior settings. Memories
 * are persisted with the NPC and included in every language-model request about it; settings steer the parts of
 * the NPC that run without a model (free play, greetings, self defense); a speaking style also rewrites the canned
 * chat lines once, so even instant replies stay in character.
 */
public final class NpcMemory {
    public static final int MAX_MEMORIES = 25;
    private static final int MAX_LENGTH = 200;

    private NpcMemory() {
    }

    /** Applies the memory part of an order. Returns true if anything changed. */
    public static boolean apply(NpcBrain npc, @Nullable List<String> remember, @Nullable List<String> forget,
                                @Nullable String settings, @Nullable ServerPlayerEntity speaker) {
        NpcIdentity id = npc.identity();
        boolean changed = false;
        if (forget != null) {
            for (String f : forget) {
                if (f == null || f.isBlank()) continue;
                String needle = f.toLowerCase(Locale.ROOT).trim();
                Iterator<String> it = id.memories().iterator();
                while (it.hasNext()) {
                    String m = it.next().toLowerCase(Locale.ROOT);
                    if (m.contains(needle) || needle.contains(m)) {
                        it.remove();
                        changed = true;
                    }
                }
            }
        }
        if (remember != null) {
            for (String r : remember) {
                if (r == null || r.isBlank()) continue;
                String m = r.trim();
                if (m.length() > MAX_LENGTH) m = m.substring(0, MAX_LENGTH);
                if (id.memories().stream().anyMatch(x -> x.equalsIgnoreCase(r.trim()))) continue;
                id.memories().add(m);
                changed = true;
            }
            while (id.memories().size() > MAX_MEMORIES) id.memories().remove(0);
        }
        if (settings != null && !settings.isBlank()) changed |= applySettings(npc, settings, speaker);
        if (changed) {
            npc.manager().saveRegistry();
            restyle(npc);
        }
        return changed;
    }

    private static boolean applySettings(NpcBrain npc, String settings, @Nullable ServerPlayerEntity speaker) {
        NpcIdentity id = npc.identity();
        boolean changed = false;
        for (String part : settings.trim().split("\\s+")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq).toLowerCase(Locale.ROOT);
            String value = part.substring(eq + 1).trim();
            String lower = value.toLowerCase(Locale.ROOT);
            boolean none = lower.isEmpty() || lower.equals("none") || lower.equals("null") || lower.equals("off");
            switch (key) {
                case "socialness" -> id.socialness = none ? null : clamp(parseInt(value, 1), 0, 2);
                case "chatty" -> id.chatty = none ? null : clamp(parseInt(value, 1), 0, 2);
                case "retaliate" -> id.retaliate = none ? null : Boolean.parseBoolean(lower);
                case "autonomous" -> npc.setAutonomous(!none && !lower.equals("false"));
                case "follow" -> id.follow = none ? null : player(value, speaker);
                case "guard" -> id.guard = none ? null : player(value, speaker);
                case "nickname" -> {
                    if (none) id.aliases().clear();
                    else if (value.length() >= 2 && value.length() <= 24 && !id.aliases().contains(lower)) id.aliases().add(lower);
                }
                case "home" -> {
                    if (!none) {
                        var pos = lower.equals("here") && speaker != null ? speaker.getBlockPos() : npc.npc().getBlockPos();
                        id.homeX = pos.getX();
                        id.homeY = pos.getY();
                        id.homeZ = pos.getZ();
                        id.homeDimension = npc.world().getRegistryKey().getValue().toString();
                    }
                }
                default -> {
                    continue;
                }
            }
            changed = true;
        }
        return changed;
    }

    private static String player(String value, @Nullable ServerPlayerEntity speaker) {
        if ((value.equalsIgnoreCase("speaker") || value.equalsIgnoreCase("me")) && speaker != null) return speaker.getGameProfile().getName();
        return value;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** One-line summary for situation reports. */
    public static String describe(NpcBrain npc) {
        NpcIdentity id = npc.identity();
        StringBuilder sb = new StringBuilder();
        if (!id.memories().isEmpty()) sb.append(String.join(" | ", id.memories()));
        StringBuilder settings = new StringBuilder();
        if (!id.aliases().isEmpty()) settings.append(" also called ").append(String.join("/", id.aliases())).append(';');
        if (id.follow != null) settings.append(" follows ").append(id.follow).append(" when free;");
        if (id.guard != null) settings.append(" guards ").append(id.guard).append(" when free;");
        if (id.socialness != null) settings.append(" socialness ").append(id.socialness).append(';');
        if (id.retaliate != null) settings.append(id.retaliate ? " fights back;" : " never fights back;");
        if (id.chatty != null) settings.append(" chattiness ").append(id.chatty).append(';');
        if (!npc.isAutonomous()) settings.append(" free play off;");
        if (settings.length() > 0) sb.append(sb.length() > 0 ? " || settings:" : "settings:").append(settings);
        return sb.toString();
    }

    /** Regenerates the canned chat lines so they follow the NPC's persona and remembered style. */
    public static void restyle(NpcBrain npc) {
        NpcIdentity id = npc.identity();
        LlmService llm = npc.manager().mod().llm();
        if (id.memories().isEmpty()) {
            id.styleLines = null;
            return;
        }
        if (llm == null || llm.blockedReason() != null) return;
        String context = "NPC " + id.name + ". Persona: " + id.persona + ".\nLong-term memories (style and habits to follow):\n- "
                + String.join("\n- ", id.memories());
        llm.restyle(context).thenAccept(result -> npc.server().execute(() -> result.ifPresent(lines -> {
            id.styleLines = toMap(lines);
            npc.manager().saveRegistry();
            MCattle.LOGGER.info("Restyled chat lines for {}", id.name);
        })));
    }

    private static Map<String, List<String>> toMap(StyleLines l) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        put(m, "ack", l.ack());
        put(m, "done", l.done());
        put(m, "confused", l.confused());
        put(m, "hit", l.hit());
        put(m, "death", l.death());
        put(m, "greeting", l.greeting());
        put(m, "simpleOnly", l.simpleOnly());
        return m;
    }

    private static void put(Map<String, List<String>> m, String key, @Nullable List<String> lines) {
        if (lines == null) return;
        List<String> clean = lines.stream().filter(x -> x != null && !x.isBlank()).map(String::trim).limit(8).toList();
        if (!clean.isEmpty()) m.put(key, clean);
    }
}
