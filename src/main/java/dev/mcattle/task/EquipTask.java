package dev.mcattle.task;

import dev.mcattle.control.ItemQuery;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShieldItem;

/** Holds or wears an item (obtaining it first if needed and allowed). */
public final class EquipTask extends Task {
    private final ItemQuery query;
    private final boolean obtain;

    public EquipTask(ItemQuery query, boolean obtain) {
        this.query = query;
        this.obtain = obtain;
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (hasChild()) {
            Status s = tickChild(npc);
            if (s == Status.FAILURE) return Status.FAILURE;
            if (s == Status.RUNNING) return Status.RUNNING;
        }
        int slot = npc.inventory().findSlot(query);
        if (slot < 0) {
            if (obtain && ticks < 5) {
                setChild(new ObtainTask(query, 1), npc);
                return Status.RUNNING;
            }
            // nothing to equip: not a failure worth complaining about for "equip a sword" before a fight
            return Status.SUCCESS;
        }
        ItemStack stack = npc.npc().getInventory().main.get(slot);
        if (stack.getItem() instanceof ArmorItem armor) {
            EquipmentSlot target = armor.getSlotType();
            ItemStack old = npc.npc().getEquippedStack(target);
            npc.npc().getInventory().main.set(slot, old.copy());
            npc.npc().equipStack(target, stack.copy());
        } else if (stack.getItem() instanceof ShieldItem) {
            npc.inventory().equipShield();
        } else {
            npc.inventory().selectSlot(slot);
        }
        return Status.SUCCESS;
    }

    @Override
    public String describe() {
        return "equipping " + query.describe();
    }
}
