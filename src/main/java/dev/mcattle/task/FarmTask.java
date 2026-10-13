package dev.mcattle.task;

import dev.mcattle.control.BlockSearch;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/** Harvests grown crops nearby and replants them. */
public final class FarmTask extends Task {
    private final int max;
    private final Deque<BlockPos> todo = new ArrayDeque<>();
    @Nullable private BlockPos current;
    @Nullable private Block currentCrop;
    private int harvested;
    private boolean searched;

    public FarmTask(int max) {
        this.max = Math.max(1, max);
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (!searched) {
            searched = true;
            todo.addAll(new BlockSearch(npc.world(), npc.npc().getBlockPos(), 24, 6,
                    s -> s.getBlock() instanceof CropBlock crop && crop.isMature(s), null, max).runNow());
            if (todo.isEmpty()) return fail("nothing's ready to harvest");
        }
        if (hasChild()) {
            Status s = tickChild(npc);
            if (s == Status.RUNNING) return Status.RUNNING;
            if (s == Status.SUCCESS && current != null) {
                harvested++;
                replant(npc, current, currentCrop);
            }
            current = null;
        }
        if (harvested >= max || todo.isEmpty()) {
            setChildOnce(npc);
            return hasChild() ? Status.RUNNING : Status.SUCCESS;
        }
        current = todo.poll();
        BlockState s = npc.world().getBlockState(current);
        if (!(s.getBlock() instanceof CropBlock crop) || !crop.isMature(s)) {
            current = null;
            return Status.RUNNING;
        }
        currentCrop = s.getBlock();
        setChild(new BreakBlockTask(current), npc);
        return Status.RUNNING;
    }

    private boolean collected;

    private void setChildOnce(NpcBrain npc) {
        if (!collected) {
            collected = true;
            setChild(new CollectItemsTask(10, x -> true, 20 * 8), npc);
        }
    }

    private void replant(NpcBrain npc, BlockPos pos, @Nullable Block crop) {
        Item seed = crop == Blocks.WHEAT ? Items.WHEAT_SEEDS : crop == Blocks.CARROTS ? Items.CARROT
                : crop == Blocks.POTATOES ? Items.POTATO : crop == Blocks.BEETROOTS ? Items.BEETROOT_SEEDS : null;
        if (seed == null || !npc.world().getBlockState(pos.down()).isOf(Blocks.FARMLAND)) return;
        if (!npc.inventory().select(s -> s.isOf(seed))) return;
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos.down()).add(0, 0.5, 0), Direction.UP, pos.down(), false);
        npc.move().lookAt(hit.getPos());
        npc.npc().interactionManager.interactBlock(npc.npc(), npc.world(), npc.npc().getMainHandStack(), Hand.MAIN_HAND, hit);
        npc.inventory().swingMainHand();
    }

    @Override
    public String describe() {
        return "farming (" + harvested + " harvested)";
    }

    @Override
    public Task helpCopy(NpcBrain helper) {
        return new FarmTask(max);
    }
}
