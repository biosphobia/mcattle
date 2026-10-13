package dev.mcattle.task;

import dev.mcattle.control.Navigator;
import dev.mcattle.control.PathFinder;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

/** Wanders off in a direction to see what's out there. */
public final class ExploreTask extends Task {
    private final int distance;
    private final int maxTicks;
    private int legs;
    private float heading = Float.NaN;

    public ExploreTask(int distance, int maxTicks) {
        this.distance = distance;
        this.maxTicks = maxTicks;
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (ticks > maxTicks) return Status.SUCCESS;
        Navigator.State s = npc.nav().state();
        if (s == Navigator.State.IDLE || s == Navigator.State.ARRIVED || s == Navigator.State.FAILED) {
            if (legs++ > 3) return Status.SUCCESS;
            if (Float.isNaN(heading) || s == Navigator.State.FAILED) heading = npc.random().nextFloat() * 360f;
            else heading += npc.random().nextFloat() * 60f - 30f;
            double rad = Math.toRadians(heading);
            int leg = Math.max(16, distance / 2);
            BlockPos from = npc.npc().getBlockPos();
            int x = from.getX() + (int) (-Math.sin(rad) * leg);
            int z = from.getZ() + (int) (Math.cos(rad) * leg);
            BlockPos top = npc.world().isChunkLoaded(x >> 4, z >> 4)
                    ? npc.world().getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z))
                    : new BlockPos(x, from.getY(), z);
            npc.nav().navigate(PathFinder.near(top, 4));
        }
        npc.nav().tick();
        return Status.RUNNING;
    }

    @Override
    public String describe() {
        return "exploring";
    }
}
