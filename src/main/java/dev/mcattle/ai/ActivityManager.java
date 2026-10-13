package dev.mcattle.ai;

import dev.mcattle.MCattle;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Running activities on the server. */
public final class ActivityManager {
    private final MCattle mod;
    private final List<ActivitySession> sessions = new ArrayList<>();

    public ActivityManager(MCattle mod) {
        this.mod = mod;
    }

    /**
     * Starts an activity with the given NPCs (or joins an activity one of them is already in) and returns the
     * task for {@code starter}; the other participants are switched over to the activity immediately.
     */
    public ActivityTask start(NpcBrain starter, String goal, List<NpcBrain> participants, ServerPlayerEntity host) {
        ActivitySession session = new ActivitySession(mod, goal, host);
        sessions.add(session);
        ActivityTask mine = session.join(starter);
        for (NpcBrain p : participants) {
            if (p == starter) continue;
            p.giveOrders(List.of(session.join(p)), true, host);
        }
        return mine;
    }

    public void tick() {
        for (ActivitySession s : new ArrayList<>(sessions)) {
            try {
                s.tick();
            } catch (RuntimeException e) {
                MCattle.LOGGER.warn("Activity '{}' crashed", s.goal(), e);
                s.end("error");
            }
        }
        sessions.removeIf(ActivitySession::isEnded);
    }

    /** The activity a player is talking into: they host it or are near one of its participants. */
    @Nullable
    public ActivitySession sessionFor(ServerPlayerEntity player, List<NpcBrain> addressed) {
        for (ActivitySession s : sessions) {
            if (s.isEnded()) continue;
            boolean named = addressed.stream().anyMatch(s::involves);
            boolean near = s.participants.stream().anyMatch(p -> p.npc().getWorld() == player.getWorld() && p.npc().distanceTo(player) < 32);
            if (named || (addressed.isEmpty() && (player.getUuid().equals(s.host) || near))) return s;
        }
        return null;
    }

    public void chat(ActivitySession session, ServerPlayerEntity player, String text) {
        session.onChat(player, text);
    }

    public void clear() {
        for (ActivitySession s : sessions) s.end("server stopping");
        sessions.clear();
    }

    public List<ActivitySession> sessions() {
        return sessions;
    }
}
