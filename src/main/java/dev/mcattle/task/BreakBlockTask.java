package dev.mcattle.task;

import dev.mcattle.control.InteractionControl;
import dev.mcattle.control.Navigator;
import dev.mcattle.control.PathFinder;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.util.math.BlockPos;

/** Walks within reach of a block and mines it. */
public final class BreakBlockTask extends Task {
    private final BlockPos pos;
    private boolean navigating;
    private int navFailures;

    public BreakBlockTask(BlockPos pos) {
        this.pos = pos.toImmutable();
    }

    public BlockPos pos() {
        return pos;
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (npc.world().getBlockState(pos).isAir()) return Status.SUCCESS;
        if (npc.hands().inReach(pos) && !npc.hands().bodyBlocks(pos.up(2))) {
            if (navigating) {
                npc.nav().stop();
                navigating = false;
            }
            InteractionControl.Result r = npc.hands().breakBlock(pos);
            if (r == InteractionControl.Result.DONE) return Status.SUCCESS;
            if (r == InteractionControl.Result.FAILED) return fail("can't break that");
            return Status.RUNNING;
        }
        if (!navigating) {
            npc.nav().navigate(PathFinder.reach(pos, InteractionControl.REACH - 0.5));
            navigating = true;
        }
        Navigator.State s = npc.nav().tick();
        if (s == Navigator.State.FAILED || (s == Navigator.State.ARRIVED && !npc.hands().inReach(pos))) {
            navigating = false;
            if (++navFailures > 2) return fail("can't reach it");
        }
        return Status.RUNNING;
    }

    @Override
    public String describe() {
        return "mining a block";
    }
}
