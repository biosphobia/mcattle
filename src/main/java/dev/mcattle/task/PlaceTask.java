package dev.mcattle.task;

import dev.mcattle.control.InteractionControl;
import dev.mcattle.control.ItemQuery;
import dev.mcattle.control.Navigator;
import dev.mcattle.control.PathFinder;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.util.math.BlockPos;

/** Places one block at a specific position (getting the item first if needed). */
public final class PlaceTask extends Task {
    private final BlockPos pos;
    private final ItemQuery item;
    private boolean obtained;
    private boolean navigating;
    private int attempts;

    public PlaceTask(BlockPos pos, ItemQuery item) {
        this.pos = pos.toImmutable();
        this.item = item;
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (hasChild()) {
            Status s = tickChild(npc);
            if (s == Status.RUNNING) return Status.RUNNING;
            if (s == Status.FAILURE) return Status.FAILURE;
        }
        if (npc.inventory().count(item) == 0 && !npc.npc().isCreative()) {
            if (obtained) return fail("no " + item.describe() + " to place");
            obtained = true;
            setChild(new ObtainTask(item, 1), npc);
            return Status.RUNNING;
        }
        if (!npc.world().getBlockState(pos).isReplaceable()) return Status.SUCCESS;
        if (!npc.hands().inReach(pos) || npc.hands().bodyBlocks(pos)) {
            if (!navigating) {
                npc.nav().navigate(PathFinder.reach(pos, InteractionControl.REACH - 1));
                navigating = true;
            }
            Navigator.State s = npc.nav().tick();
            if (s == Navigator.State.FAILED || s == Navigator.State.ARRIVED) {
                navigating = false;
                if (++attempts > 3) return fail("can't get there to place it");
            }
            return Status.RUNNING;
        }
        npc.nav().stop();
        if (npc.npc().isCreative() && npc.inventory().count(item) == 0 && item.preferred() != null) {
            npc.inventory().giveCreative(new net.minecraft.item.ItemStack(item.preferred()));
        }
        InteractionControl.Result r = npc.hands().placeBlock(pos, item);
        if (r == InteractionControl.Result.DONE) return Status.SUCCESS;
        return ++attempts > 20 ? fail("can't place it there") : Status.RUNNING;
    }

    @Override
    public String describe() {
        return "placing " + item.describe();
    }
}
