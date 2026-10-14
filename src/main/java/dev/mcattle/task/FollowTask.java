package dev.mcattle.task;

import dev.mcattle.control.Navigator;
import dev.mcattle.control.PathFinder;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

/** Stays near a player or NPC until told otherwise. Walks (no teleporting), retrying when the way is blocked. */
public final class FollowTask extends Task {
    private final Target target;
    private final double distance;
    private BlockPos plannedFor;
    private int failures;

    public FollowTask(Target target, double distance) {
        this.target = target;
        this.distance = distance;
    }

    @Override
    protected Status run(NpcBrain npc) {
        Entity e = target.entity(npc);
        if (e == null || e.isRemoved()) return fail("lost " + target.describe());
        if (e.getWorld() != npc.world()) return fail("can't follow " + target.describe() + " there");
        double dist = npc.npc().distanceTo(e);
        if (failures > 4) {
            // no way through right now: wait a bit, look around, then try again (no teleporting, like a player)
            npc.move().lookAt(e);
            if (ticks % 60 == 0) failures = 0;
            return Status.RUNNING;
        }
        if (dist <= distance) {
            npc.nav().stop();
            plannedFor = null;
            npc.move().lookAt(e);
            if (e.isSneaking() && npc.random().nextInt(30) == 0) npc.move().sneak(); // copycat
            return Status.RUNNING;
        }
        BlockPos goal = e.getBlockPos();
        if (plannedFor == null || plannedFor.getSquaredDistance(goal) > 9 || npc.nav().state() == Navigator.State.ARRIVED) {
            plannedFor = goal;
            npc.nav().navigate(PathFinder.near(goal, distance * 0.7));
        }
        Navigator.State s = npc.nav().tick();
        if (s == Navigator.State.FAILED) {
            failures++;
            plannedFor = null;
        }
        if (dist < distance + 3) npc.move().lookAt(e);
        return Status.RUNNING;
    }

    @Override
    public String describe() {
        return "following " + target.describe();
    }

    @Override
    public boolean isOpenEnded() {
        return true;
    }

    @Override
    public Task helpCopy(NpcBrain helper) {
        return new FollowTask(target, distance + 1.5);
    }
}
