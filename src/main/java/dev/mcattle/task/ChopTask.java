package dev.mcattle.task;

import dev.mcattle.control.BlockSearch;
import dev.mcattle.control.ItemQuery;
import dev.mcattle.control.Tags;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Lumberjack: finds real trees (logs with leaves), fells them bottom-up, picks up the drops and replants. */
public final class ChopTask extends Task {
    private final ItemQuery logType;
    private final int targetTotal;
    private final ItemQuery counted;
    @Nullable private BlockSearch search;
    private final Deque<BlockPos> treeLogs = new ArrayDeque<>();
    @Nullable private BlockPos treeBase;
    private final Set<BlockPos> badTrees = new HashSet<>();
    private int explores;
    private int phase; // 0 search, 1 chopping, 2 collecting, 3 replant

    /**
     * @param logType  which logs to chop (tag query for "any")
     * @param targetTotal stop when the inventory holds this many {@code counted} items
     */
    public ChopTask(ItemQuery logType, int targetTotal, ItemQuery counted) {
        this.logType = logType;
        this.targetTotal = targetTotal;
        this.counted = counted;
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (npc.inventory().count(counted) >= targetTotal && phase != 1) return Status.SUCCESS;
        if (hasChild()) {
            Status s = tickChild(npc);
            if (s == Status.RUNNING) return Status.RUNNING;
            if (s == Status.FAILURE && phase == 1 && !treeLogs.isEmpty()) {
                treeLogs.poll(); // skip an unreachable log
            }
            if (phase == 0 && s == Status.FAILURE) explores++;
        }
        ServerWorld world = npc.world();
        switch (phase) {
            case 0 -> {
                if (explores > 4) return fail("there are no trees around here");
                if (search == null) {
                    search = new BlockSearch(world, npc.npc().getBlockPos(), 48, 20,
                            s -> s.isIn(Tags.LOGS) && logType.test(new ItemStack(s.getBlock())),
                            (p, s) -> !badTrees.contains(p) && isNaturalTree(world, p), 6);
                }
                if (!search.step(12)) return Status.RUNNING;
                List<BlockPos> logs = search.results();
                search = null;
                BlockPos log = null;
                for (BlockPos p : logs) {
                    BlockPos base = trunkBase(world, p);
                    if (!badTrees.contains(base) && !npc.manager().isReservedByOther(base, npc)) {
                        log = base;
                        break;
                    }
                }
                if (log == null) {
                    explores++;
                    setChild(new ExploreTask(48 + explores * 16, 20 * 25), npc);
                    return Status.RUNNING;
                }
                treeBase = log;
                npc.manager().reserve(treeBase, npc);
                collectTree(world, treeBase);
                phase = 1;
            }
            case 1 -> {
                while (!treeLogs.isEmpty() && !world.getBlockState(treeLogs.peek()).isIn(Tags.LOGS)) treeLogs.poll();
                if (treeLogs.isEmpty()) {
                    phase = 2;
                    setChild(new CollectItemsTask(7, s -> true, 20 * 8), npc);
                    return Status.RUNNING;
                }
                setChild(new BreakBlockTask(treeLogs.peek()), npc);
            }
            case 2 -> {
                phase = 3;
            }
            case 3 -> {
                replant(npc);
                if (treeBase != null) {
                    npc.manager().release(treeBase, npc);
                    badTrees.add(treeBase);
                }
                treeBase = null;
                phase = 0;
            }
            default -> phase = 0;
        }
        return Status.RUNNING;
    }

    /** Logs with leaves near the top: a tree, not someone's log cabin. */
    static boolean isNaturalTree(ServerWorld world, BlockPos log) {
        BlockPos top = log;
        for (int i = 0; i < 30 && world.getBlockState(top.up()).isIn(Tags.LOGS); i++) top = top.up();
        for (BlockPos p : BlockPos.iterate(top.add(-2, -1, -2), top.add(2, 2, 2))) {
            BlockState s = world.getBlockState(p);
            if (s.isIn(Tags.LEAVES)) return true;
        }
        return false;
    }

    static BlockPos trunkBase(ServerWorld world, BlockPos log) {
        BlockPos base = log;
        for (int i = 0; i < 30 && world.getBlockState(base.down()).isIn(Tags.LOGS); i++) base = base.down();
        return base.toImmutable();
    }

    /** Connected logs of this tree (trunk, 2x2 trunks, branches), lowest first. */
    private void collectTree(ServerWorld world, BlockPos base) {
        treeLogs.clear();
        List<BlockPos> found = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(base);
        seen.add(base);
        while (!queue.isEmpty() && found.size() < 64) {
            BlockPos p = queue.poll();
            found.add(p);
            for (BlockPos n : BlockPos.iterate(p.add(-1, 0, -1), p.add(1, 1, 1))) {
                BlockPos im = n.toImmutable();
                if (seen.contains(im)) continue;
                if (Math.abs(im.getX() - base.getX()) > 4 || Math.abs(im.getZ() - base.getZ()) > 4) continue;
                if (world.getBlockState(im).isIn(Tags.LOGS)) {
                    seen.add(im);
                    queue.add(im);
                }
            }
        }
        found.sort(Comparator.comparingInt(BlockPos::getY).thenComparingDouble(p -> p.getSquaredDistance(base)));
        treeLogs.addAll(found);
    }

    private void replant(NpcBrain npc) {
        if (treeBase == null) return;
        ServerWorld world = npc.world();
        BlockState ground = world.getBlockState(treeBase.down());
        if (!world.getBlockState(treeBase).isAir() || !ground.isIn(Tags.DIRT)) return;
        if (!npc.inventory().select(s -> s.isIn(Tags.ITEM_SAPLINGS))) return;
        if (!npc.hands().inReach(treeBase.down())) return;
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(treeBase.down()).add(0, 0.5, 0), Direction.UP, treeBase.down(), false);
        npc.move().lookAt(hit.getPos());
        npc.npc().interactionManager.interactBlock(npc.npc(), world, npc.npc().getMainHandStack(), Hand.MAIN_HAND, hit);
        npc.inventory().swingMainHand();
    }

    @Override
    public void stop(NpcBrain npc) {
        super.stop(npc);
        if (treeBase != null) npc.manager().release(treeBase, npc);
    }

    @Override
    public String describe() {
        return "chopping trees";
    }

    @Override
    public Task helpCopy(NpcBrain helper) {
        return new ChopTask(logType, helper.inventory().count(counted) + Math.max(4, targetTotal / 2), counted);
    }
}
