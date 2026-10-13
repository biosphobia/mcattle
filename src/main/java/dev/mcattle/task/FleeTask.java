package dev.mcattle.task;

import dev.mcattle.control.PathFinder;
import dev.mcattle.control.WorldRules;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/** Gets away from a threat (or out of lava/fire when {@code threat} is null). */
public final class FleeTask extends Task {
    @Nullable private final Entity threat;
    private final double distance;
    private final int maxTicks;
    private final String reason;
    private boolean planned;

    public FleeTask(@Nullable Entity threat, double distance, int maxTicks, String reason) {
        this.threat = threat;
        this.distance = distance;
        this.maxTicks = maxTicks;
        this.reason = reason;
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (ticks > maxTicks) return Status.SUCCESS;
        if (threat == null) {
            // lava / fire: find the nearest safe dry spot and run for it
            if (!npc.npc().isInLava() && !npc.npc().isOnFire()) return Status.SUCCESS;
            npc.move().jump();
            BlockPos safe = nearestSafe(npc);
            if (safe != null) npc.move().moveTo(Vec3d.ofBottomCenter(safe), true);
            return Status.RUNNING;
        }
        if (!threat.isAlive() || npc.npc().distanceTo(threat) >= distance) return Status.SUCCESS;
        if (!planned || ticks % 20 == 0) {
            npc.nav().navigate(PathFinder.away(threat.getBlockPos(), distance));
            planned = true;
        }
        npc.nav().setSprint(true);
        npc.nav().tick();
        return Status.RUNNING;
    }

    @Nullable
    private static BlockPos nearestSafe(NpcBrain npc) {
        BlockPos center = npc.npc().getBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(center.add(-5, -2, -5), center.add(5, 3, 5))) {
            var feet = npc.world().getBlockState(p);
            if (WorldRules.isLava(feet) || !WorldRules.isPassable(npc.world(), p, feet)) continue;
            var below = npc.world().getBlockState(p.down());
            if (!WorldRules.isStandable(npc.world(), p.down(), below)) continue;
            double d = p.getSquaredDistance(center);
            if (d < bestD) {
                bestD = d;
                best = p.toImmutable();
            }
        }
        return best;
    }

    @Override
    public String describe() {
        return "running from " + reason;
    }
}
