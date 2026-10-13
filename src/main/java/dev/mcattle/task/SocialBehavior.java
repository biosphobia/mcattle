package dev.mcattle.task;

import dev.mcattle.npc.Lines;
import dev.mcattle.npc.NpcBrain;
import dev.mcattle.npc.NpcPlayer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Small, rare player-like gestures: noticing someone who walks up, a crouch "hello", a wave. Only takes over
 * the body while the NPC is idle; while working it at most glances over.
 */
public final class SocialBehavior {
    private static final long GREET_COOLDOWN = 20L * 60 * 6;

    private final NpcBrain npc;
    private final Map<UUID, Long> lastGreeted = new HashMap<>();
    @Nullable private ServerPlayerEntity focus;
    private int gestureTicks;
    private int glanceTicks;
    private boolean crouchGreeting;

    public SocialBehavior(NpcBrain npc) {
        this.npc = npc;
    }

    public void tick(boolean idle) {
        Integer personal = npc.identity().socialness;
        double socialness = npc.config().npc.socialness * (personal == null ? 1 : personal);
        if (socialness <= 0) return;
        if (gestureTicks > 0 && focus != null && focus.isAlive()) {
            gestureTicks--;
            if (idle) {
                npc.move().lookAt(focus);
                if (crouchGreeting && (gestureTicks / 4) % 2 == 0) npc.move().sneak();
            }
            return;
        }
        if (glanceTicks > 0 && focus != null && focus.isAlive()) {
            glanceTicks--;
            if (idle) npc.move().lookAt(focus);
            return;
        }
        if (npc.age() % 20 != 7) return;
        long now = npc.age();
        for (ServerPlayerEntity p : npc.nearbyPlayers(7, true)) {
            Long last = lastGreeted.get(p.getUuid());
            if (last != null && now - last < GREET_COOLDOWN) continue;
            lastGreeted.put(p.getUuid(), now);
            focus = p;
            double roll = npc.random().nextDouble();
            boolean isNpc = p instanceof NpcPlayer;
            if (roll < 0.25 * socialness) {
                // the classic crouch-spam hello (only when not busy)
                crouchGreeting = idle;
                gestureTicks = idle ? 24 : 0;
                glanceTicks = idle ? 0 : 15;
                if (!isNpc && npc.random().nextInt(3) == 0) npc.say(Lines.greeting(npc, p.getName().getString()));
            } else if (roll < 0.6 * socialness) {
                glanceTicks = 25 + npc.random().nextInt(30); // just look over
            }
            return;
        }
    }
}
