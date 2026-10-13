package dev.mcattle.task;

import dev.mcattle.control.Navigator;
import dev.mcattle.control.PathFinder;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Walk (swim, climb, dig, pillar...) to a target and stop within a given range. */
public final class GotoTask extends Task {
    private final Target target;
    private final double range;
    private BlockPos plannedFor;
    private int attempts;

    public GotoTask(Target target, double range) {
        this.target = target;
        this.range = range;
    }

    @Override
    protected Status run(NpcBrain npc) {
        Entity entity = target.entity(npc);
        if (entity != null && entity.getWorld() != npc.world()) return fail("can't, you're in another dimension");
        Vec3d pos = target.position(npc);
        if (pos == null) return fail("lost track of " + target.describe());
        if (npc.pos().distanceTo(pos) <= range + 0.5) {
            npc.nav().stop();
            return Status.SUCCESS;
        }
        BlockPos goal = BlockPos.ofFloored(pos);
        if (plannedFor == null || (entity != null && plannedFor.getSquaredDistance(goal) > 9 && ticks % 10 == 0)) {
            plannedFor = goal;
            npc.nav().navigate(PathFinder.near(goal, Math.max(1.0, range)));
        }
        Navigator.State s = npc.nav().tick();
        if (s == Navigator.State.ARRIVED) {
            return npc.pos().distanceTo(pos) <= range + 2.5 ? Status.SUCCESS : retry(npc, goal);
        }
        if (s == Navigator.State.FAILED) return retry(npc, goal);
        return Status.RUNNING;
    }

    private Status retry(NpcBrain npc, BlockPos goal) {
        if (++attempts > 3) return fail("can't find a way to " + target.describe());
        plannedFor = goal;
        npc.nav().navigate(PathFinder.near(goal, Math.max(1.0, range) + attempts));
        return Status.RUNNING;
    }

    @Override
    public String describe() {
        return "going to " + target.describe();
    }

    @Override
    public Task helpCopy(NpcBrain helper) {
        return new GotoTask(target, range + 1);
    }
}
