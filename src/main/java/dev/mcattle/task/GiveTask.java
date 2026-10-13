package dev.mcattle.task;

import dev.mcattle.control.ItemQuery;
import dev.mcattle.control.Navigator;
import dev.mcattle.control.PathFinder;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Brings items to someone and tosses them over, getting the items first if needed. */
public final class GiveTask extends Task {
    private final Target target;
    private final ItemQuery query;
    private final int count;
    private final boolean obtain;
    private boolean obtained;
    private BlockPos plannedFor;
    private int navFailures;

    public GiveTask(Target target, ItemQuery query, int count, boolean obtain) {
        this.target = target;
        this.query = query;
        this.count = count;
        this.obtain = obtain;
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (hasChild()) {
            Status s = tickChild(npc);
            if (s == Status.RUNNING) return Status.RUNNING;
            if (s == Status.FAILURE && npc.inventory().count(query) == 0) return Status.FAILURE;
            obtained = true; // got some or all; give what we have
        }
        int have = npc.inventory().count(query);
        if (!obtained && obtain && !query.isAny() && count > 0 && have < count) {
            obtained = true;
            setChild(new ObtainTask(query, count), npc);
            return Status.RUNNING;
        }
        if (have == 0) return fail("i don't have any " + query.describe());

        Entity e = target.entity(npc);
        Vec3d pos = target.position(npc);
        if (pos == null) return fail("where did " + target.describe() + " go?");
        double dist = npc.pos().distanceTo(pos);
        if (dist > 2.8 || (e != null && e.getWorld() != npc.world())) {
            BlockPos goal = BlockPos.ofFloored(pos);
            if (plannedFor == null || plannedFor.getSquaredDistance(goal) > 4 || npc.nav().state() == Navigator.State.FAILED) {
                if (npc.nav().state() == Navigator.State.FAILED && ++navFailures > 4) {
                    return fail("can't get to you, come closer?");
                }
                plannedFor = goal;
                npc.nav().navigate(PathFinder.near(goal, 2.0));
            }
            npc.nav().tick();
            return Status.RUNNING;
        }
        npc.nav().stop();
        if (e != null) npc.move().lookAt(e.getEyePos().add(0, -0.6, 0));
        else npc.move().lookAt(pos);
        if (ticks % 4 != 0) return Status.RUNNING; // give the head a moment to turn
        int amount = count <= 0 ? Integer.MAX_VALUE : count;
        List<ItemStack> stacks = npc.inventory().take(query, amount);
        for (ItemStack s : stacks) {
            ItemEntity item = npc.npc().dropItem(s, false, true);
            if (item != null) npc.manager().markGift(item.getUuid());
        }
        npc.inventory().swingMainHand();
        int given = stacks.stream().mapToInt(ItemStack::getCount).sum();
        if (count > 0 && given < count) npc.say("only had " + given + ", here");
        return Status.SUCCESS;
    }

    @Override
    public String describe() {
        return "giving " + (count > 0 ? count + " " : "") + query.describe() + " to " + target.describe();
    }

    @Override
    public Task helpCopy(NpcBrain helper) {
        return new GiveTask(target, query, Math.max(1, count / 2), obtain);
    }
}
