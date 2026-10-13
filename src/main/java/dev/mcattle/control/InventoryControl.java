package dev.mcattle.control;

import dev.mcattle.npc.NpcPlayer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.MiningToolItem;
import net.minecraft.item.ShieldItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Inventory handling the way a player would do it: hotbar selection, tool choice, armor, food, junk. */
public final class InventoryControl {
    /** Hotbar slot used as scratch space when an item has to be pulled up from the main inventory. */
    private static final int SCRATCH_SLOT = 8;
    private static final Set<Block> SCAFFOLD_BLOCKS = Set.of(Blocks.DIRT, Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE,
            Blocks.NETHERRACK, Blocks.STONE, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.TUFF,
            Blocks.BLACKSTONE, Blocks.END_STONE, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.DEEPSLATE);
    private static final Set<Item> JUNK = Set.of(Items.ROTTEN_FLESH, Items.POISONOUS_POTATO, Items.SPIDER_EYE,
            Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.GRAVEL, Items.DEAD_BUSH, Items.CALCITE);

    private final NpcPlayer npc;

    public InventoryControl(NpcPlayer npc) {
        this.npc = npc;
    }

    public PlayerInventory inv() {
        return npc.getInventory();
    }

    public ItemStack selected() {
        return npc.getMainHandStack();
    }

    /** Main inventory slots (0-35). */
    public int mainSize() {
        return inv().main.size();
    }

    public int count(Predicate<ItemStack> pred) {
        int n = 0;
        for (ItemStack s : inv().main) if (!s.isEmpty() && pred.test(s)) n += s.getCount();
        ItemStack off = inv().offHand.get(0);
        if (!off.isEmpty() && pred.test(off)) n += off.getCount();
        return n;
    }

    public int count(Item item) {
        return count(s -> s.isOf(item));
    }

    public boolean has(Predicate<ItemStack> pred) {
        return findSlot(pred) >= 0;
    }

    /** Slot in main inventory holding a matching stack, hotbar first; -1 if none. */
    public int findSlot(Predicate<ItemStack> pred) {
        List<ItemStack> main = inv().main;
        for (int i = 0; i < main.size(); i++) if (!main.get(i).isEmpty() && pred.test(main.get(i))) return i;
        return -1;
    }

    /** Makes a matching stack the held item. Returns false if there is none. */
    public boolean select(Predicate<ItemStack> pred) {
        if (!selected().isEmpty() && pred.test(selected())) return true;
        int slot = findSlot(pred);
        if (slot < 0) return false;
        selectSlot(slot);
        return true;
    }

    public void selectSlot(int slot) {
        PlayerInventory inv = inv();
        if (PlayerInventory.isValidHotbarIndex(slot)) {
            inv.selectedSlot = slot;
            return;
        }
        int hotbar = -1;
        for (int i = 0; i < 9; i++) {
            if (inv.main.get(i).isEmpty()) {
                hotbar = i;
                break;
            }
        }
        if (hotbar < 0) hotbar = SCRATCH_SLOT;
        ItemStack fromMain = inv.main.get(slot);
        inv.main.set(slot, inv.main.get(hotbar));
        inv.main.set(hotbar, fromMain);
        inv.selectedSlot = hotbar;
    }

    /** Holds nothing important (empty hand, or a harmless block) for actions where tools would be wasted. */
    public void selectHarmless() {
        PlayerInventory inv = inv();
        for (int i = 0; i < 9; i++) {
            if (inv.main.get(i).isEmpty()) {
                inv.selectedSlot = i;
                return;
            }
        }
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.main.get(i);
            if (!s.isDamageable()) {
                inv.selectedSlot = i;
                return;
            }
        }
    }

    /** Picks the fastest suitable tool for a block. Returns the mining speed of the chosen item. */
    public float selectBestTool(BlockState state) {
        int best = -1;
        float bestScore = 1.0f;
        List<ItemStack> main = inv().main;
        for (int i = 0; i < main.size(); i++) {
            ItemStack s = main.get(i);
            if (s.isEmpty()) continue;
            if (s.isDamageable() && s.getMaxDamage() - s.getDamage() <= 1) continue; // about to break
            float speed = s.getMiningSpeedMultiplier(state);
            if (state.isToolRequired() && !s.isSuitableFor(state)) speed = Math.min(speed, 1.0f);
            if (speed > bestScore) {
                bestScore = speed;
                best = i;
            }
        }
        if (best >= 0) {
            selectSlot(best);
            return bestScore;
        }
        selectHarmless();
        return 1.0f;
    }

    /** Best possible mining speed for a block with what we carry, and whether it can actually be harvested. */
    public float bestToolSpeed(BlockState state) {
        float best = 1.0f;
        for (ItemStack s : inv().main) {
            if (s.isEmpty()) continue;
            float speed = s.getMiningSpeedMultiplier(state);
            if (state.isToolRequired() && !s.isSuitableFor(state)) continue;
            best = Math.max(best, speed);
        }
        return best;
    }

    public boolean canHarvest(BlockState state) {
        if (!state.isToolRequired() || npc.isCreative()) return true;
        for (ItemStack s : inv().main) if (!s.isEmpty() && s.isSuitableFor(state)) return true;
        return false;
    }

    public static float weaponScore(ItemStack s) {
        Item item = s.getItem();
        if (item instanceof SwordItem sword) return 10 + sword.getAttackDamage();
        if (item instanceof AxeItem axe) return 8 + axe.getAttackDamage();
        if (item instanceof MiningToolItem tool) return 2 + tool.getAttackDamage() * 0.5f;
        if (item == Items.TRIDENT) return 15;
        return 0;
    }

    public boolean selectBestWeapon() {
        int best = -1;
        float bestScore = 0;
        List<ItemStack> main = inv().main;
        for (int i = 0; i < main.size(); i++) {
            float score = weaponScore(main.get(i));
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best >= 0) {
            selectSlot(best);
            return true;
        }
        selectHarmless();
        return false;
    }

    private static float armorScore(ItemStack s) {
        if (!(s.getItem() instanceof ArmorItem armor)) return -1;
        return armor.getProtection() + armor.getToughness() * 0.5f;
    }

    /** Wears the best armor carried, putting replaced pieces back into the inventory. */
    public void equipBestArmor() {
        List<ItemStack> main = inv().main;
        for (int i = 0; i < main.size(); i++) {
            ItemStack s = main.get(i);
            if (!(s.getItem() instanceof ArmorItem armor)) continue;
            EquipmentSlot slot = armor.getSlotType();
            ItemStack worn = npc.getEquippedStack(slot);
            if (armorScore(s) > armorScore(worn)) {
                main.set(i, worn.copy());
                npc.equipStack(slot, s.copy());
            }
        }
    }

    public void equipShield() {
        if (inv().offHand.get(0).getItem() instanceof ShieldItem) return;
        int slot = findSlot(s -> s.getItem() instanceof ShieldItem);
        if (slot < 0) return;
        ItemStack shield = inv().main.get(slot);
        inv().main.set(slot, inv().offHand.get(0));
        npc.equipStack(EquipmentSlot.OFFHAND, shield);
    }

    public boolean hasShield() {
        return npc.getOffHandStack().getItem() instanceof ShieldItem;
    }

    public static boolean isScaffold(ItemStack s) {
        return s.getItem() instanceof BlockItem bi && SCAFFOLD_BLOCKS.contains(bi.getBlock());
    }

    public int scaffoldCount() {
        if (npc.isCreative()) return 999;
        return count(InventoryControl::isScaffold);
    }

    public boolean selectScaffold() {
        if (select(InventoryControl::isScaffold)) return true;
        if (npc.isCreative()) {
            giveCreative(new ItemStack(Items.DIRT, 64));
            return select(InventoryControl::isScaffold);
        }
        return false;
    }

    /** Creative NPCs conjure what they need, like a creative player using the item picker. */
    public void giveCreative(ItemStack stack) {
        inv().insertStack(stack);
    }

    public static float foodScore(ItemStack s) {
        if (!s.isFood()) return -1;
        Item item = s.getItem();
        if (JUNK.contains(item) || item == Items.PUFFERFISH || item == Items.CHICKEN || item == Items.SUSPICIOUS_STEW) return 0.1f;
        if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) return 0.5f; // saved for fights
        FoodComponent food = item.getFoodComponent();
        if (food == null) return 0;
        return food.getHunger() + food.getHunger() * food.getSaturationModifier() * 2;
    }

    /** Selects the best everyday food; returns false if there is nothing edible worth eating. */
    public boolean selectFood(boolean desperate) {
        int best = -1;
        float bestScore = desperate ? 0.05f : 0.2f;
        List<ItemStack> main = inv().main;
        for (int i = 0; i < main.size(); i++) {
            float score = foodScore(main.get(i));
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0) return false;
        selectSlot(best);
        return true;
    }

    public boolean selectHealingFood() {
        return select(s -> s.isOf(Items.ENCHANTED_GOLDEN_APPLE)) || select(s -> s.isOf(Items.GOLDEN_APPLE)) || selectFood(true);
    }

    public int foodCount() {
        return count(s -> foodScore(s) >= 1);
    }

    public boolean isFull() {
        return inv().getEmptySlot() < 0;
    }

    /** Throws away junk when the inventory is full. Returns true if anything was dropped. */
    public boolean dropJunk() {
        boolean dropped = false;
        List<ItemStack> main = inv().main;
        for (int i = 9; i < main.size(); i++) {
            ItemStack s = main.get(i);
            if (!s.isEmpty() && JUNK.contains(s.getItem())) {
                npc.dropItem(s.copy(), false, true);
                main.set(i, ItemStack.EMPTY);
                dropped = true;
            }
        }
        if (!dropped) {
            // too much cobble/dirt: keep two stacks of each scaffold block
            int kept = 0;
            for (int i = main.size() - 1; i >= 9; i--) {
                ItemStack s = main.get(i);
                if (isScaffold(s) && s.getCount() == s.getMaxCount() && ++kept > 2) {
                    npc.dropItem(s.copy(), false, true);
                    main.set(i, ItemStack.EMPTY);
                    dropped = true;
                }
            }
        }
        return dropped;
    }

    /** Removes up to {@code count} matching items and returns them as stacks (main inventory, then offhand). */
    public List<ItemStack> take(Predicate<ItemStack> pred, int count) {
        List<ItemStack> out = new ArrayList<>();
        int remaining = count;
        PlayerInventory inv = inv();
        for (int i = inv.main.size() - 1; i >= 0 && remaining > 0; i--) {
            ItemStack s = inv.main.get(i);
            if (s.isEmpty() || !pred.test(s)) continue;
            int n = Math.min(remaining, s.getCount());
            out.add(s.split(n));
            remaining -= n;
        }
        ItemStack off = inv.offHand.get(0);
        if (remaining > 0 && !off.isEmpty() && pred.test(off)) {
            int n = Math.min(remaining, off.getCount());
            out.add(off.split(n));
        }
        inv.markDirty();
        return out;
    }

    /** Removes items for crafting/building; creative NPCs don't consume. */
    public boolean consume(Predicate<ItemStack> pred, int count) {
        if (npc.isCreative()) return true;
        if (count(pred) < count) return false;
        take(pred, count);
        return true;
    }

    public void insert(ItemStack stack) {
        inv().offerOrDrop(stack);
    }

    public boolean isTool(ItemStack s) {
        return s.getItem() instanceof ToolItem || s.isDamageable();
    }

    public void swingMainHand() {
        npc.swingHand(Hand.MAIN_HAND, true);
    }
}
