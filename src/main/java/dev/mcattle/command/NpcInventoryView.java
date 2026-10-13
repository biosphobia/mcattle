package dev.mcattle.command;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

/** Exposes an NPC's inventory (36 main + 4 armor + offhand) as a 5-row chest so players can look and trade. */
public final class NpcInventoryView implements Inventory {
    private final PlayerInventory inv;

    public NpcInventoryView(PlayerInventory inv) {
        this.inv = inv;
    }

    @Override
    public int size() {
        return 45;
    }

    @Override
    public boolean isEmpty() {
        return inv.isEmpty();
    }

    @Override
    public ItemStack getStack(int slot) {
        return slot < inv.size() ? inv.getStack(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        return slot < inv.size() ? inv.removeStack(slot, amount) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeStack(int slot) {
        return slot < inv.size() ? inv.removeStack(slot) : ItemStack.EMPTY;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        if (slot < inv.size()) inv.setStack(slot, stack);
    }

    @Override
    public boolean isValid(int slot, ItemStack stack) {
        return slot < inv.size();
    }

    @Override
    public void markDirty() {
        inv.markDirty();
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return true;
    }

    @Override
    public void clear() {
        // never wipe an NPC from the viewer
    }
}
