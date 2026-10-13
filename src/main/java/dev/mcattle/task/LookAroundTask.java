package dev.mcattle.task;

import dev.mcattle.npc.NpcBrain;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

/** Idles like an AFK player: glances around, sometimes at nearby players. */
public final class LookAroundTask extends Task {
    @Nullable private Entity focus;
    private float yaw, pitch;
    private int until;

    @Override
    protected Status run(NpcBrain npc) {
        if (ticks >= until) {
            until = ticks + 40 + npc.random().nextInt(60);
            var players = npc.nearbyPlayers(10, true);
            if (!players.isEmpty() && npc.random().nextBoolean()) {
                focus = players.get(npc.random().nextInt(players.size()));
            } else {
                focus = null;
                yaw = npc.npc().getYaw() + npc.random().nextFloat() * 140 - 70;
                pitch = npc.random().nextFloat() * 40 - 20;
            }
        }
        if (focus != null && focus.isAlive()) {
            npc.move().lookAt(focus);
        } else {
            float cur = npc.npc().getYaw();
            float step = MathHelper.clamp(MathHelper.wrapDegrees(yaw - cur), -6f, 6f);
            float curPitch = npc.npc().getPitch();
            npc.move().setRotation(cur + step, curPitch + MathHelper.clamp(pitch - curPitch, -4f, 4f));
        }
        return Status.RUNNING;
    }

    @Override
    public String describe() {
        return "looking around";
    }
}
