package dev.mcattle.task;

import dev.mcattle.control.BlockSearch;
import dev.mcattle.control.InteractionControl;
import dev.mcattle.control.ItemQuery;
import dev.mcattle.control.Knowledge;
import dev.mcattle.control.Navigator;
import dev.mcattle.control.PathFinder;
import dev.mcattle.control.Tags;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Smelts / cooks in a real furnace: gathers input and fuel, finds or places a furnace, loads it and waits. */
public final class SmeltTask extends Task {
    private final Item output;
    private final int targetTotal;
    private final ItemQuery counted;
    @Nullable private Ingredient input;
    @Nullable private Item inputItem;
    @Nullable private BlockPos furnace;
    private int waitTicks;
    private boolean gathered;

    private final java.util.Set<Item> chain;

    public SmeltTask(Item output, int targetTotal, ItemQuery counted) {
        this(output, targetTotal, counted, java.util.Set.of());
    }

    public SmeltTask(Item output, int targetTotal, ItemQuery counted, java.util.Set<Item> chain) {
        this.output = output;
        this.targetTotal = Math.max(1, targetTotal);
        this.counted = counted;
        java.util.Set<Item> c = new java.util.HashSet<>(chain);
        c.add(output);
        this.chain = c;
    }

    private int missing(NpcBrain npc) {
        return targetTotal - npc.inventory().count(counted);
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (missing(npc) <= 0) return Status.SUCCESS;
        if (hasChild()) {
            Status s = tickChild(npc);
            if (s == Status.RUNNING) return Status.RUNNING;
            if (s == Status.FAILURE) return Status.FAILURE;
        }
        if (input == null) {
            List<SmeltingRecipe> recipes = Knowledge.smeltingRecipes(npc.server(), output);
            if (recipes.isEmpty()) return fail("can't smelt " + TaskUtil.pretty(output));
            SmeltingRecipe recipe = recipes.get(0);
            input = recipe.getIngredients().get(0);
            inputItem = Arrays.stream(input.getMatchingStacks()).map(ItemStack::getItem).filter(i -> !chain.contains(i))
                    .min(Comparator.comparingDouble(i -> Knowledge.estimateCost(npc.server(), i, npc.inventory())))
                    .orElse(null);
            if (inputItem == null) return fail("can't smelt " + TaskUtil.pretty(output));
        }
        int missing = missing(npc);
        int inFurnace = furnaceContents(npc);
        // 1. raw materials
        int needInput = missing - inFurnace;
        if (!gathered && needInput > 0 && npc.inventory().count(input) < needInput) {
            gathered = true;
            setChild(new ObtainTask(ItemQuery.category(TaskUtil.pretty(inputItem), input, inputItem), needInput, chain), npc);
            return Status.RUNNING;
        }
        // 2. fuel
        double fuel = fuelValue(npc);
        if (fuel < Math.min(needInput, npc.inventory().count(input)) && !furnaceBurningEnough(npc, missing)) {
            if (ticks > 20 * 60 * 5) return fail("ran out of fuel");
            int coal = npc.inventory().count(s -> s.isIn(Tags.ITEM_COALS));
            if (coal == 0) {
                int planks = (int) Math.ceil(Math.max(1, needInput) / 1.5) + 1;
                ItemQuery plankQuery = ItemQuery.tag(Tags.ITEM_PLANKS, "planks", Items.OAK_PLANKS);
                setChild(new ObtainTask(plankQuery, npc.inventory().count(plankQuery) + planks, chain), npc);
                return Status.RUNNING;
            }
        }
        // 3. furnace
        if (furnace != null && !(npc.world().getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity)) furnace = null;
        if (furnace == null) {
            List<BlockPos> found = new BlockSearch(npc.world(), npc.npc().getBlockPos(), 24, 8,
                    s -> s.isOf(Blocks.FURNACE), (p, s) -> usable(npc, p), 1).runNow();
            if (!found.isEmpty()) {
                furnace = found.get(0);
            } else if (npc.inventory().count(Items.FURNACE) == 0) {
                setChild(new CraftTask(Items.FURNACE, 1), npc);
                return Status.RUNNING;
            } else {
                PlaceNearTask place = new PlaceNearTask(ItemQuery.of(Items.FURNACE));
                setChild(place, npc);
                return Status.RUNNING;
            }
        }
        if (!npc.hands().inReach(furnace)) {
            if (npc.nav().state() != Navigator.State.MOVING && npc.nav().state() != Navigator.State.SEARCHING) {
                npc.nav().navigate(PathFinder.reach(furnace, InteractionControl.REACH - 0.6));
            }
            if (npc.nav().tick() == Navigator.State.FAILED) furnace = null;
            return Status.RUNNING;
        }
        npc.nav().stop();
        npc.move().lookAt(Vec3d.ofCenter(furnace));
        if (!(npc.world().getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity be)) return Status.RUNNING;

        // 4. load, wait, collect
        ItemStack in = be.getStack(0), fuelSlot = be.getStack(1), out = be.getStack(2);
        if (!out.isEmpty()) {
            npc.inventory().insert(be.removeStack(2));
            npc.inventory().swingMainHand();
        }
        if (in.isEmpty() || input.test(in)) {
            int want = Math.min(missing - in.getCount(), 64 - in.getCount());
            if (want > 0) {
                for (ItemStack s : npc.inventory().take(input, want)) {
                    if (in.isEmpty()) {
                        be.setStack(0, s);
                        in = be.getStack(0);
                    } else {
                        in.increment(s.getCount());
                    }
                }
            }
        }
        if (fuelSlot.isEmpty() || fuelSlot.getCount() < 2) {
            List<ItemStack> fuelStacks = takeFuel(npc, fuelSlot);
            for (ItemStack f : fuelStacks) {
                if (fuelSlot.isEmpty()) {
                    be.setStack(1, f);
                    fuelSlot = be.getStack(1);
                } else {
                    fuelSlot.increment(f.getCount());
                }
            }
        }
        be.markDirty();
        waitTicks++;
        if (waitTicks > 20 * (12 * Math.max(1, missing) + 30)) return fail("the furnace is taking forever");
        if (in.isEmpty() && be.getStack(2).isEmpty() && missing(npc) > 0 && npc.inventory().count(input) == 0) {
            gathered = false; // ran out of input: gather more
        }
        return Status.RUNNING;
    }

    private boolean usable(NpcBrain npc, BlockPos pos) {
        BlockEntity be = npc.world().getBlockEntity(pos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) return false;
        ItemStack in = furnace.getStack(0);
        return in.isEmpty() || input == null || input.test(in);
    }

    private int furnaceContents(NpcBrain npc) {
        if (furnace == null || !(npc.world().getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity be)) return 0;
        int n = 0;
        if (input != null && input.test(be.getStack(0))) n += be.getStack(0).getCount();
        if (be.getStack(2).isOf(output)) n += be.getStack(2).getCount();
        return n;
    }

    private boolean furnaceBurningEnough(NpcBrain npc, int items) {
        if (furnace == null || !(npc.world().getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity be)) return false;
        return Knowledge.fuelValue(be.getStack(1)) * be.getStack(1).getCount() >= items
                || npc.world().getBlockState(furnace).get(AbstractFurnaceBlock.LIT) && items <= 1;
    }

    private double fuelValue(NpcBrain npc) {
        double total = 0;
        for (ItemStack s : npc.npc().getInventory().main) {
            if (s.isEmpty() || (input != null && input.test(s))) continue;
            total += Knowledge.fuelValue(s) * s.getCount();
        }
        return total;
    }

    private List<ItemStack> takeFuel(NpcBrain npc, ItemStack current) {
        if (!current.isEmpty()) {
            Item kind = current.getItem();
            return npc.inventory().take(s -> s.isOf(kind), 8);
        }
        for (Item preferred : new Item[]{Items.COAL, Items.CHARCOAL}) {
            if (npc.inventory().count(preferred) > 0) return npc.inventory().take(s -> s.isOf(preferred), 8);
        }
        return npc.inventory().take(s -> (s.isIn(Tags.ITEM_PLANKS) || s.isIn(Tags.ITEM_LOGS)) && (input == null || !input.test(s)), 8);
    }

    @Override
    public String describe() {
        return "smelting " + TaskUtil.pretty(output);
    }
}
