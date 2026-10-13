package dev.mcattle.task;

import dev.mcattle.control.ItemQuery;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;

import java.util.List;

/** Drops items on the ground (Q). */
public final class DropTask extends Task {
    private final ItemQuery query;
    private final int count;

    public DropTask(ItemQuery query, int count) {
        this.query = query;
        this.count = count;
    }

    @Override
    protected Status run(NpcBrain npc) {
        int amount = count <= 0 ? Integer.MAX_VALUE : count;
        List<ItemStack> stacks = npc.inventory().take(query, amount);
        if (stacks.isEmpty()) return fail("i don't have any " + query.describe());
        for (ItemStack s : stacks) {
            ItemEntity e = npc.npc().dropItem(s, false, true);
            if (e != null) npc.manager().markGift(e.getUuid());
        }
        npc.inventory().swingMainHand();
        return Status.SUCCESS;
    }

    @Override
    public String describe() {
        return "dropping " + query.describe();
    }
}
