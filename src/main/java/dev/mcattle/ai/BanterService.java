package dev.mcattle.ai;

import dev.mcattle.MCattle;
import dev.mcattle.core.llm.Banter;
import dev.mcattle.core.llm.LlmService;
import dev.mcattle.npc.NpcBrain;
import dev.mcattle.npc.NpcPlayer;
import dev.mcattle.task.AttackTask;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Occasional NPC small talk via the language model: rare (configurable interval, server-wide), only when NPCs
 * are near each other or a human, and a single short call writes the whole exchange.
 */
public final class BanterService {
    private final MCattle mod;
    private long nextBanterTick = 20L * 120;

    public BanterService(MCattle mod) {
        this.mod = mod;
    }

    /** Called when a server (or singleplayer world) starts: tick counters restart at 0. */
    public void reset() {
        nextBanterTick = 20L * 120;
    }

    public void tick() {
        var cfg = mod.config().llm;
        long now = mod.server().getTicks();
        if (!cfg.banter || now < nextBanterTick || mod.npcs() == null) return;
        int interval = Math.max(60, cfg.banterIntervalSeconds) * 20;
        nextBanterTick = now + interval / 2 + (long) (Math.random() * interval);
        LlmService llm = mod.llm();
        if (llm == null || llm.blockedReason() != null) return;

        List<NpcBrain> candidates = new ArrayList<>(mod.npcs().all());
        java.util.Collections.shuffle(candidates);
        for (NpcBrain npc : candidates) {
            if (!npc.npc().isAlive() || npc.currentTask() instanceof AttackTask) continue;
            List<NpcBrain> group = new ArrayList<>();
            group.add(npc);
            for (NpcBrain other : mod.npcs().all()) {
                if (other != npc && other.npc().getWorld() == npc.npc().getWorld() && other.npc().distanceTo(npc.npc()) < 16) group.add(other);
            }
            List<ServerPlayerEntity> humans = npc.nearbyPlayers(16, false).stream().filter(p -> !(p instanceof NpcPlayer)).toList();
            if (group.size() < 2 && humans.isEmpty()) continue;
            if (group.size() > 3) group = group.subList(0, 3);
            final List<NpcBrain> speakers = group;
            String situation = dev.mcattle.ai.Situation.forBanter(mod.npcs(), speakers, humans);
            llm.banter(situation).thenAccept(result -> mod.server().execute(() -> result.ifPresent(b -> say(b, speakers))));
            return;
        }
    }

    private void say(Banter banter, List<NpcBrain> speakers) {
        if (banter.lines() == null) return;
        int n = 0;
        for (Banter.Line line : banter.lines()) {
            if (line == null || line.npc() == null || line.text() == null || n++ >= 3) continue;
            for (NpcBrain b : speakers) {
                if (b.name().equalsIgnoreCase(line.npc())) {
                    b.say(line.text());
                    break;
                }
            }
        }
    }
}
