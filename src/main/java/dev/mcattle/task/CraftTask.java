package dev.mcattle.task;

import dev.mcattle.control.BlockSearch;
import dev.mcattle.control.InteractionControl;
import dev.mcattle.control.ItemQuery;
import dev.mcattle.control.Knowledge;
import dev.mcattle.control.Navigator;
import dev.mcattle.control.PathFinder;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Crafts an item from inventory like a player at a crafting grid: gathers missing ingredients (recursively),
 * uses the 2x2 grid when possible and otherwise finds or places a crafting table and walks to it.
 */
public final class CraftTask extends Task {
    private final Item item;
    private final int targetTotal;
    private final ItemQuery counted;
    private final Set<String> failedIngredients = new HashSet<>();
    @Nullable private BlockPos table;
    private int craftDelay;
    private int obtainRounds;

    /**
     * @param counted what counts toward the goal (usually just the item, but "any planks" for tag requests)
     */
    public CraftTask(Item item, int targetTotal, ItemQuery counted, Set<Item> chain) {
        this.item = item;
        this.targetTotal = Math.max(1, targetTotal);
        this.counted = counted;
        Set<Item> c = new HashSet<>(chain);
        c.add(item);
        this.chain = c;
    }

    public CraftTask(Item item, int targetTotal, ItemQuery counted) {
        this(item, targetTotal, counted, Set.of());
    }

    public CraftTask(Item item, int targetTotal) {
        this(item, targetTotal, ItemQuery.of(item));
    }

    /** This item plus everything being produced above it in the task chain. */
    private final Set<Item> chain;

    /** True if some ingredient can only be satisfied by an item we're already trying to make (a loop). */
    private boolean circular(CraftingRecipe r) {
        for (Ingredient ing : r.getIngredients()) {
            if (ing.isEmpty()) continue;
            boolean escape = false;
            for (ItemStack opt : ing.getMatchingStacks()) {
                if (!chain.contains(opt.getItem())) {
                    escape = true;
                    break;
                }
            }
            if (!escape) return true;
        }
        return false;
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (npc.inventory().count(counted) >= targetTotal) return Status.SUCCESS;
        if (hasChild()) {
            Status s = tickChild(npc);
            if (s == Status.RUNNING) return Status.RUNNING;
            if (s == Status.FAILURE) return Status.FAILURE;
        }
        List<CraftingRecipe> recipes = Knowledge.craftingRecipes(npc.server(), item).stream().filter(r -> !circular(r)).toList();
        if (recipes.isEmpty()) return fail("can't craft " + TaskUtil.pretty(item));

        // prefer a recipe we can do right now; otherwise the one with the cheapest missing ingredients
        CraftingRecipe ready = null;
        for (CraftingRecipe r : recipes) {
            if (assign(npc, r) != null) {
                ready = r;
                break;
            }
        }
        if (ready == null) {
            if (++obtainRounds > 6) return fail("couldn't get the stuff for " + TaskUtil.pretty(item));
            CraftingRecipe best = recipes.stream()
                    .min(Comparator.comparingDouble(r -> missingCost(npc, r)))
                    .orElseThrow();
            Task gather = gatherFor(npc, best);
            if (gather == null) return fail("can't get ingredients for " + TaskUtil.pretty(item));
            setChild(gather, npc);
            return Status.RUNNING;
        }

        if (!Knowledge.fitsInInventoryGrid(ready) && !npc.npc().isCreative()) {
            Status tableStatus = ensureTable(npc);
            if (tableStatus != Status.SUCCESS) return tableStatus;
        }
        if (craftDelay-- > 0) return Status.RUNNING;
        craftOnce(npc, ready);
        craftDelay = 6;
        return npc.inventory().count(counted) >= targetTotal ? Status.SUCCESS : Status.RUNNING;
    }

    /** Groups recipe slots that accept the same items: "3 planks + 2 sticks". */
    private static Map<String, Integer> slotGroups(CraftingRecipe recipe, Map<String, Ingredient> byKey) {
        Map<String, Integer> groups = new LinkedHashMap<>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            String key = Arrays.stream(ing.getMatchingStacks()).map(s -> s.getItem().toString()).sorted().toList().toString();
            groups.merge(key, 1, Integer::sum);
            byKey.putIfAbsent(key, ing);
        }
        return groups;
    }

    private int craftsNeeded(NpcBrain npc, CraftingRecipe recipe) {
        int perCraft = Math.max(1, recipe.getOutput(npc.server().getRegistryManager()).getCount());
        int missing = targetTotal - npc.inventory().count(counted);
        return Math.max(1, (missing + perCraft - 1) / perCraft);
    }

    private double missingCost(NpcBrain npc, CraftingRecipe recipe) {
        Map<String, Ingredient> byKey = new HashMap<>();
        double cost = 0;
        int crafts = craftsNeeded(npc, recipe);
        for (Map.Entry<String, Integer> g : slotGroups(recipe, byKey).entrySet()) {
            if (failedIngredients.contains(g.getKey())) return 1e9;
            Ingredient ing = byKey.get(g.getKey());
            int need = g.getValue() * crafts - npc.inventory().count(ing);
            if (need <= 0) continue;
            double cheapest = 1e6;
            for (ItemStack opt : ing.getMatchingStacks()) {
                cheapest = Math.min(cheapest, Knowledge.estimateCost(npc.server(), opt.getItem(), npc.inventory()));
            }
            cost += cheapest * need;
        }
        return cost;
    }

    /** Obtain tasks for every ingredient we lack, run one after another. */
    @Nullable
    private Task gatherFor(NpcBrain npc, CraftingRecipe recipe) {
        Map<String, Ingredient> byKey = new HashMap<>();
        int crafts = craftsNeeded(npc, recipe);
        List<Task> steps = new ArrayList<>();
        for (Map.Entry<String, Integer> g : slotGroups(recipe, byKey).entrySet()) {
            Ingredient ing = byKey.get(g.getKey());
            int need = g.getValue() * crafts;
            if (npc.inventory().count(ing) >= need) continue;
            ItemStack[] options = ing.getMatchingStacks();
            if (options.length == 0) return null;
            Item preferred = Arrays.stream(options).map(ItemStack::getItem).filter(i -> !chain.contains(i))
                    .min(Comparator.comparingDouble(i -> Knowledge.estimateCost(npc.server(), i, npc.inventory())))
                    .orElse(options[0].getItem());
            ItemQuery q = ItemQuery.category(TaskUtil.pretty(preferred), ing, preferred);
            steps.add(new ObtainTask(q, need, chain));
        }
        if (steps.isEmpty()) return null;
        return new SequenceTask(steps, "gathering ingredients for " + TaskUtil.pretty(item));
    }

    /** Picks inventory slots satisfying every ingredient once; null if impossible. */
    @Nullable
    private static int[] assign(NpcBrain npc, CraftingRecipe recipe) {
        List<ItemStack> main = npc.npc().getInventory().main;
        int[] used = new int[main.size()];
        List<Ingredient> ings = new ArrayList<>();
        for (Ingredient i : recipe.getIngredients()) if (!i.isEmpty()) ings.add(i);
        // most constrained ingredients first
        ings.sort(Comparator.comparingInt(i -> i.getMatchingStacks().length));
        int[] picks = new int[ings.size()];
        for (int k = 0; k < ings.size(); k++) {
            int found = -1;
            for (int slot = 0; slot < main.size(); slot++) {
                ItemStack s = main.get(slot);
                if (!s.isEmpty() && s.getCount() - used[slot] > 0 && ings.get(k).test(s)) {
                    found = slot;
                    break;
                }
            }
            if (found < 0) return null;
            used[found]++;
            picks[k] = found;
        }
        return picks;
    }

    private void craftOnce(NpcBrain npc, CraftingRecipe recipe) {
        ItemStack output = recipe.getOutput(npc.server().getRegistryManager()).copy();
        if (!npc.npc().isCreative()) {
            int[] picks = assign(npc, recipe);
            if (picks == null) return;
            List<ItemStack> main = npc.npc().getInventory().main;
            for (int slot : picks) {
                ItemStack s = main.get(slot);
                Item remainder = s.getItem().getRecipeRemainder();
                s.decrement(1);
                if (remainder != null) npc.inventory().insert(new ItemStack(remainder));
            }
        }
        output.onCraft(npc.world(), npc.npc(), output.getCount());
        npc.inventory().insert(output);
        npc.inventory().swingMainHand();
        if (table != null) npc.move().lookAt(net.minecraft.util.math.Vec3d.ofCenter(table));
    }

    private Status ensureTable(NpcBrain npc) {
        if (table != null && !npc.world().getBlockState(table).isOf(Blocks.CRAFTING_TABLE)) table = null;
        if (table == null) {
            List<BlockPos> found = new BlockSearch(npc.world(), npc.npc().getBlockPos(), 24, 8,
                    s -> s.isOf(Blocks.CRAFTING_TABLE), null, 1).runNow();
            if (!found.isEmpty()) table = found.get(0);
        }
        if (table == null) {
            if (npc.inventory().count(Items.CRAFTING_TABLE) == 0) {
                setChild(new CraftTask(Items.CRAFTING_TABLE, 1), npc);
                return Status.RUNNING;
            }
            setChild(new PlaceNearTask(ItemQuery.of(Items.CRAFTING_TABLE)), npc);
            return Status.RUNNING;
        }
        if (!npc.hands().inReach(table)) {
            if (npc.nav().state() != Navigator.State.MOVING && npc.nav().state() != Navigator.State.SEARCHING) {
                npc.nav().navigate(PathFinder.reach(table, InteractionControl.REACH - 0.6));
            }
            Navigator.State s = npc.nav().tick();
            if (s == Navigator.State.FAILED) {
                table = null; // too awkward to reach: place a new one next to us
                if (npc.inventory().count(Items.CRAFTING_TABLE) == 0) {
                    setChild(new CraftTask(Items.CRAFTING_TABLE, 1), npc);
                } else {
                    setChild(new PlaceNearTask(ItemQuery.of(Items.CRAFTING_TABLE)), npc);
                }
            }
            return Status.RUNNING;
        }
        npc.nav().stop();
        npc.move().lookAt(net.minecraft.util.math.Vec3d.ofCenter(table));
        return Status.SUCCESS;
    }

    @Override
    public String describe() {
        return "crafting " + TaskUtil.pretty(item);
    }

    @Override
    public Task helpCopy(NpcBrain helper) {
        return new CraftTask(item, targetTotal, counted, chain);
    }
}
