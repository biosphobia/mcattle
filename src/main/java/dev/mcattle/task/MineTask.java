package dev.mcattle.task;

import dev.mcattle.control.BlockSearch;
import dev.mcattle.control.ItemQuery;
import dev.mcattle.control.Knowledge;
import dev.mcattle.control.PathFinder;
import dev.mcattle.control.WorldRules;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mines a kind of block until enough drops are collected. Without x-ray it only targets blocks a player could
 * see (touching air or water); if none are around it digs down to the ore's usual depth and branch-mines,
 * mining any matching ore the tunnels expose.
 */
public final class MineTask extends Task {
    private final Knowledge.Source source;
    private final ItemQuery counted;
    private final int targetTotal;
    @Nullable private BlockSearch search;
    @Nullable private BlockPos targetBlock;
    private final Set<BlockPos> failed = new HashSet<>();
    private int branchLegs;
    private int surfaceSearches;
    private boolean digging;
    private boolean obtainingTool;
    private Direction tunnelDir = Direction.NORTH;

    public MineTask(Knowledge.Source source, ItemQuery counted, int targetTotal) {
        this.source = source;
        this.counted = counted;
        this.targetTotal = targetTotal;
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (npc.inventory().count(counted) >= targetTotal) return Status.SUCCESS;
        if (hasChild()) {
            Status s = tickChild(npc);
            if (s == Status.RUNNING) return Status.RUNNING;
            if (s == Status.FAILURE && targetBlock != null) failed.add(targetBlock);
            if (targetBlock != null) {
                npc.manager().release(targetBlock, npc);
                if (s == Status.SUCCESS) {
                    setChild(new CollectItemsTask(5, x -> true, 20 * 4), npc);
                    targetBlock = null;
                    return Status.RUNNING;
                }
                targetBlock = null;
            }
            if (obtainingTool) {
                obtainingTool = false;
                if (s == Status.FAILURE) return fail("need a better pickaxe for " + source.description() + " and couldn't make one");
            }
        }
        ServerWorld world = npc.world();
        boolean xray = npc.config().npc.allowXray;
        if (search == null) {
            search = new BlockSearch(world, npc.npc().getBlockPos(), digging ? 12 : 32, digging ? 8 : 24, source.blocks(),
                    (p, st) -> !failed.contains(p) && (xray || BlockSearch.isExposed(world, p))
                            && !npc.manager().isReservedByOther(p, npc) && !WorldRules.exposesFluid(world, p), 8);
        }
        if (!search.step(16)) return Status.RUNNING;
        List<BlockPos> found = search.results();
        search = null;
        if (!found.isEmpty()) {
            BlockPos pos = found.get(0);
            BlockState state = world.getBlockState(pos);
            if (!npc.inventory().canHarvest(state)) {
                Item tool = Knowledge.requiredTool(state);
                if (tool == null) return fail("can't mine " + source.description());
                obtainingTool = true;
                setChild(new ObtainTask(ItemQuery.of(tool), 1), npc);
                return Status.RUNNING;
            }
            targetBlock = pos;
            npc.manager().reserve(pos, npc);
            setChild(new BreakBlockTask(pos), npc);
            return Status.RUNNING;
        }
        // nothing visible: dig for it
        int level = Knowledge.miningLevel(source);
        BlockPos feet = npc.npc().getBlockPos();
        if (level == Integer.MIN_VALUE) {
            if (surfaceSearches++ > 3) {
                // common blocks like stone are just below the surface
                if (!digging) {
                    digging = true;
                    setChild(new NavigateTask(PathFinder.near(feet.down(6), 1.5), "digging down", true), npc);
                    return Status.RUNNING;
                }
                return fail("couldn't find any " + source.description());
            }
            setChild(new ExploreTask(40, 20 * 20), npc);
            return Status.RUNNING;
        }
        if (branchLegs > 8) return fail("no " + source.description() + " down here, unlucky");
        // make sure we have a pickaxe that can mine the ore before going down
        if (npc.inventory().count(s -> s.getItem() instanceof net.minecraft.item.PickaxeItem) == 0) {
            obtainingTool = true;
            setChild(new ObtainTask(ItemQuery.of(net.minecraft.item.Items.STONE_PICKAXE), 1), npc);
            return Status.RUNNING;
        }
        digging = true;
        if (feet.getY() > level + 3) {
            // staircase down to the right depth (navigator digs through stone)
            int depth = Math.min(24, feet.getY() - level);
            BlockPos goal = feet.offset(tunnelDir, depth).down(depth);
            setChild(new NavigateTask(PathFinder.near(goal, 2.5), "digging down to y=" + level, true), npc);
            branchLegs++;
            if (npc.random().nextInt(3) == 0) tunnelDir = tunnelDir.rotateYClockwise();
            return Status.RUNNING;
        }
        // branch mine: a straight tunnel, then turn
        branchLegs++;
        if (branchLegs % 2 == 0) tunnelDir = npc.random().nextBoolean() ? tunnelDir.rotateYClockwise() : tunnelDir.rotateYCounterclockwise();
        BlockPos goal = feet.offset(tunnelDir, 20);
        setChild(new NavigateTask(PathFinder.near(goal, 1.5), "branch mining", true), npc);
        return Status.RUNNING;
    }

    @Override
    public void stop(NpcBrain npc) {
        super.stop(npc);
        if (targetBlock != null) npc.manager().release(targetBlock, npc);
    }

    @Override
    public String describe() {
        return "mining " + source.description() + (digging ? " (tunneling)" : "");
    }

    @Override
    public Task helpCopy(NpcBrain helper) {
        return new MineTask(source, counted, helper.inventory().count(counted) + Math.max(2, targetTotal / 2));
    }
}
