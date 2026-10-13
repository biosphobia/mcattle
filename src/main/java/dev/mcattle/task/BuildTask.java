package dev.mcattle.task;

import dev.mcattle.control.InteractionControl;
import dev.mcattle.control.ItemQuery;
import dev.mcattle.control.Navigator;
import dev.mcattle.control.PathFinder;
import dev.mcattle.control.WorldRules;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.block.BlockState;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Works on a {@link BuildJob} block by block like a survival player: clears obstacles, fetches materials in
 * batches when it runs out (chopping, mining, crafting as needed), climbs up with scaffolding to reach high
 * blocks and removes that scaffolding at the end.
 */
public final class BuildTask extends Task {
    private final BuildJob job;
    @Nullable private BuildJob.Cell cell;
    private int cellTicks;
    private boolean navigating;
    private boolean steppingAway;
    private final Set<String> unobtainable = new HashSet<>();
    private boolean cleaningUp;
    private int waitTicks;

    public BuildTask(BuildJob job) {
        this.job = job;
    }

    public BuildJob job() {
        return job;
    }

    @Override
    protected Status run(NpcBrain npc) {
        ServerWorld world = npc.world();
        boolean assisted = npc.config().npc.assistedPlacement;
        if (hasChild()) {
            Status s = tickChild(npc);
            if (s == Status.RUNNING) return Status.RUNNING;
            if (s == Status.FAILURE && cell != null && cell.kind == BuildJob.Kind.PLACE) {
                // couldn't get the material
                unobtainable.add(cell.block.toString());
                if (!cell.optional) npc.say("can't get " + TaskUtil.pretty(cell.block.asItem()) + ", skipping those");
                job.skip(cell);
                cell = null;
            }
        }
        if (job.isDone()) return cleanup(npc);

        if (cell == null || cell.done || cell.skipped) {
            cell = job.claim(world, npc.npc().getUuid(), npc.npc().getBlockPos(), assisted);
            cellTicks = 0;
            navigating = false;
            if (cell == null) {
                // others are busy with the remaining blocks: hang around
                if (++waitTicks > 20 * 60) return job.isDone() ? Status.SUCCESS : fail("can't reach the rest of the " + job.name());
                return Status.RUNNING;
            }
        }
        waitTicks = 0;
        cellTicks++;
        if (cellTicks > 20 * 45) {
            job.finish(cell, false);
            cell = null;
            return Status.RUNNING;
        }
        if (BuildJob.satisfied(world, cell)) {
            job.finish(cell, true);
            cell = null;
            return Status.RUNNING;
        }
        BlockState current = world.getBlockState(cell.pos);

        // 1. make room: clear cells, or something in the way of a block
        boolean obstructed = cell.kind == BuildJob.Kind.PLACE && !current.isReplaceable();
        if (cell.kind == BuildJob.Kind.CLEAR || obstructed) {
            if (WorldRules.isPrecious(world, cell.pos, current) || !WorldRules.isBreakable(world, cell.pos, current)) {
                job.skip(cell);
                cell = null;
                return Status.RUNNING;
            }
            if (!moveIntoReach(npc, cell.pos)) return Status.RUNNING;
            InteractionControl.Result r = npc.hands().breakBlock(cell.pos);
            if (r == InteractionControl.Result.FAILED) {
                job.finish(cell, false);
                cell = null;
            } else if (r == InteractionControl.Result.DONE && cell.kind == BuildJob.Kind.CLEAR) {
                job.finish(cell, true);
                cell = null;
            }
            return Status.RUNNING;
        }

        // 2. materials
        ItemQuery item = cell.item();
        if (!npc.npc().isCreative() && npc.inventory().count(item) == 0) {
            boolean noCoal = cell.block.asItem() == Items.TORCH
                    && npc.inventory().count(s -> s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL)) == 0;
            if (cell.optional && (cell.block.asItem() == Items.GLASS || noCoal || unobtainable.contains(cell.block.toString()))) {
                // windows stay open and torches are skipped rather than a long detour for glass or coal
                job.skip(cell);
                cell = null;
                return Status.RUNNING;
            }
            int batch = Math.min(64, job.remainingNeed(cell.block));
            npc.say(npc.random().nextInt(3) == 0 ? "brb, need more " + TaskUtil.pretty(cell.block.asItem()) : "");
            setChild(new ObtainTask(item, Math.max(1, batch)), npc);
            return Status.RUNNING;
        }

        // 3. get there and place it
        if (steppingAway) {
            Navigator.State st = npc.nav().tick();
            if (st == Navigator.State.MOVING || st == Navigator.State.SEARCHING) return Status.RUNNING;
            steppingAway = false;
            if (npc.hands().bodyBlocks(cell.pos)) {
                job.finish(cell, false);
                cell = null;
                return Status.RUNNING;
            }
        }
        if (!moveIntoReach(npc, cell.pos)) return Status.RUNNING;
        if (npc.hands().bodyBlocks(cell.pos)) {
            // standing where the block goes: step out of the way first
            npc.nav().navigate(PathFinder.away(cell.pos, 2));
            steppingAway = true;
            return Status.RUNNING;
        }
        if (npc.npc().isCreative() && npc.inventory().count(item) == 0) {
            npc.inventory().giveCreative(new net.minecraft.item.ItemStack(cell.block.asItem(), 64));
        }
        InteractionControl.Result r = npc.hands().placeBlock(cell.pos, item, assisted && cellTicks > 60);
        if (r == InteractionControl.Result.DONE) {
            job.finish(cell, true);
            cell = null;
        } else if (cellTicks % 40 == 39) {
            job.finish(cell, false);
            cell = null;
        }
        return Status.RUNNING;
    }

    /** Returns true when the block is within reach; otherwise keeps navigating. */
    private boolean moveIntoReach(NpcBrain npc, BlockPos pos) {
        if (npc.hands().inReach(pos) && npc.npc().isOnGround()) {
            if (navigating) {
                npc.nav().stop();
                navigating = false;
            }
            return true;
        }
        if (!navigating) {
            npc.nav().navigate(PathFinder.reach(pos, InteractionControl.REACH - 0.6));
            navigating = true;
        }
        Navigator.State s = npc.nav().tick();
        if (s == Navigator.State.FAILED || s == Navigator.State.ARRIVED && !npc.hands().inReach(pos)) {
            navigating = false;
            if (cell != null) {
                job.finish(cell, false);
                cell = null;
            }
        }
        return false;
    }

    /** Removes the dirt pillars / bridges used to reach high blocks. */
    private Status cleanup(NpcBrain npc) {
        if (!cleaningUp) {
            cleaningUp = true;
            List<BlockPos> scaffold = npc.nav().placedScaffold();
            Set<BlockPos> structure = new HashSet<>(job.footprint());
            List<BlockPos> toRemove = scaffold.stream().filter(p -> !structure.contains(p)).sorted((a, b) -> b.getY() - a.getY()).toList();
            scaffold.clear();
            if (!toRemove.isEmpty()) {
                setChild(new SequenceTask(toRemove.stream().map(p -> (Task) new BreakBlockTask(p)).toList(), "cleaning up scaffolding"), npc);
                return Status.RUNNING;
            }
        }
        return Status.SUCCESS;
    }

    @Override
    public void stop(NpcBrain npc) {
        super.stop(npc);
        job.release(npc.npc().getUuid());
    }

    @Override
    public String describe() {
        return "building a " + job.name() + " (" + (job.total() - job.remaining()) + "/" + job.total() + ")";
    }

    @Override
    public Task helpCopy(NpcBrain helper) {
        return new BuildTask(job);
    }
}
