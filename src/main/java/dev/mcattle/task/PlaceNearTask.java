package dev.mcattle.task;

import dev.mcattle.control.InteractionControl;
import dev.mcattle.control.ItemQuery;
import dev.mcattle.control.WorldRules;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.jetbrains.annotations.Nullable;

/** Places a block (crafting table, furnace, chest...) on the ground right next to the NPC. */
public final class PlaceNearTask extends Task {
    private final ItemQuery item;
    @Nullable private BlockPos spot;
    @Nullable private BlockPos placed;

    public PlaceNearTask(ItemQuery item) {
        this.item = item;
    }

    @Nullable
    public BlockPos placed() {
        return placed;
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (ticks > 100) return fail("couldn't find a spot to put down " + item.describe());
        if (npc.inventory().count(item) == 0 && !npc.npc().isCreative()) return fail("no " + item.describe() + " to place");
        if (spot == null || !npc.world().getBlockState(spot).isReplaceable()) spot = findSpot(npc);
        if (spot == null) {
            npc.move().input(0.6f, 0); // wander a little and look again
            return Status.RUNNING;
        }
        InteractionControl.Result r = npc.hands().placeBlock(spot, item);
        if (r == InteractionControl.Result.DONE) {
            placed = spot;
            return Status.SUCCESS;
        }
        spot = null;
        return Status.RUNNING;
    }

    @Nullable
    static BlockPos findSpot(NpcBrain npc) {
        BlockPos feet = npc.npc().getBlockPos();
        Box body = npc.npc().getBoundingBox();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(feet.add(-2, -1, -2), feet.add(2, 1, 2))) {
            BlockState s = npc.world().getBlockState(p);
            if (!s.isReplaceable() || !s.getFluidState().isEmpty()) continue;
            if (body.intersects(new Box(p))) continue;
            BlockState below = npc.world().getBlockState(p.down());
            if (!WorldRules.isStandable(npc.world(), p.down(), below)) continue;
            if (!npc.hands().inReach(p)) continue;
            double score = p.getSquaredDistance(feet) + Math.abs(p.getY() - feet.getY()) * 2;
            if (score < bestScore && score >= 1) {
                bestScore = score;
                best = p.toImmutable();
            }
        }
        return best;
    }

    @Override
    public String describe() {
        return "placing " + item.describe();
    }
}
