package dev.mcattle.task;

import dev.mcattle.control.InteractionControl;
import dev.mcattle.control.ItemQuery;
import dev.mcattle.control.Navigator;
import dev.mcattle.control.PathFinder;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/** Right-click: an item in the air (fireworks, food, potions, snowballs) or a block (buttons, note blocks, doors, cake, jukeboxes). */
public final class UseTask extends Task {
    @Nullable private final ItemQuery item;
    @Nullable private final BlockPos block;
    private boolean navigating;
    private boolean used;
    private int holdTicks;

    public UseTask(@Nullable ItemQuery item, @Nullable BlockPos block) {
        this.item = item;
        this.block = block == null ? null : block.toImmutable();
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (used) {
            // keep holding things that charge or take time (eating, bows), then let go
            if (npc.npc().isUsingItem() && ++holdTicks < 40) return Status.RUNNING;
            npc.hands().releaseItem();
            return Status.SUCCESS;
        }
        if (item != null && !npc.inventory().select(item)) {
            if (npc.npc().isCreative() && item.preferred() != null) {
                npc.inventory().giveCreative(new net.minecraft.item.ItemStack(item.preferred()));
                return Status.RUNNING;
            }
            return fail("i don't have any " + item.describe());
        }
        if (block != null) {
            if (!npc.hands().inReach(block)) {
                if (!navigating) {
                    npc.nav().navigate(PathFinder.reach(block, InteractionControl.REACH - 1));
                    navigating = true;
                }
                if (npc.nav().tick() == Navigator.State.FAILED) return fail("can't reach it");
                return Status.RUNNING;
            }
            npc.nav().stop();
            npc.move().lookAt(Vec3d.ofCenter(block));
            if (ticks < 3) return Status.RUNNING; // turn the head first
            npc.npc().setSneaking(false);
            npc.hands().useBlock(block, Direction.UP);
        } else {
            if (ticks < 2) return Status.RUNNING;
            npc.hands().useItem(Hand.MAIN_HAND);
            npc.inventory().swingMainHand();
        }
        used = true;
        return Status.RUNNING;
    }

    @Override
    public String describe() {
        return "using " + (item != null ? item.describe() : "") + (block != null ? " on a block" : "");
    }
}
