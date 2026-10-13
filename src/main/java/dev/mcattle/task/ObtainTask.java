package dev.mcattle.task;

import dev.mcattle.control.ItemQuery;
import dev.mcattle.control.Knowledge;
import dev.mcattle.control.Tags;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Get N of X by any means": the planner that picks between picking up, chopping, mining, crafting (with the
 * whole ingredient tree), smelting and hunting, based on what the NPC already has and what each route costs.
 */
public final class ObtainTask extends Task {
    private final ItemQuery query;
    private final int count;
    private final Set<String> failedRoutes = new HashSet<>();
    private String currentRoute;
    private boolean triedPickup;
    private int routesTried;
    private int lastHave;

    public ObtainTask(ItemQuery query, int count) {
        this.query = query;
        this.count = Math.max(1, count);
    }

    private record Route(String key, double cost, Task task) {
    }

    @Override
    protected Status run(NpcBrain npc) {
        lastHave = npc.inventory().count(query);
        if (lastHave >= count) return Status.SUCCESS;
        if (hasChild()) {
            Status s = tickChild(npc);
            if (s == Status.RUNNING) return Status.RUNNING;
            if (s == Status.FAILURE) failedRoutes.add(currentRoute);
            return npc.inventory().count(query) >= count ? Status.SUCCESS : Status.RUNNING;
        }
        if (npc.npc().isCreative()) {
            Item item = query.preferred() != null ? query.preferred() : query.candidates().stream().findFirst().orElse(null);
            if (item == null) return fail("no such item");
            npc.inventory().giveCreative(new ItemStack(item, count - npc.inventory().count(query)));
            return Status.SUCCESS;
        }
        if (!triedPickup) {
            triedPickup = true;
            currentRoute = "pickup";
            setChild(new CollectItemsTask(12, query, 20 * 10), npc);
            return Status.RUNNING;
        }
        if (++routesTried > 12) return fail("couldn't get " + query.describe());
        Route route = bestRoute(npc);
        if (route == null) {
            String why = failureReason != null ? failureReason : "no idea how to get " + query.describe();
            return fail(why);
        }
        currentRoute = route.key();
        setChild(route.task(), npc);
        return Status.RUNNING;
    }

    private Route bestRoute(NpcBrain npc) {
        MinecraftServer server = npc.server();
        List<Route> routes = new ArrayList<>();

        boolean anyLog = query.test(new ItemStack(Items.OAK_LOG)) && query.test(new ItemStack(Items.BIRCH_LOG));
        if (anyLog) {
            routes.add(new Route("chop:any", 3, new ChopTask(ItemQuery.tag(Tags.ITEM_LOGS, "logs", Items.OAK_LOG), count, query)));
        }

        List<Item> candidates = query.preferred() != null && query.tag() == null ? List.of(query.preferred()) : query.candidates();
        if (candidates.size() > 24) candidates = candidates.subList(0, 24);
        for (Item item : candidates) {
            ItemQuery exact = ItemQuery.of(item);
            if (Knowledge.isLog(item) && !anyLog) {
                routes.add(new Route("chop:" + item, 3, new ChopTask(exact, count, query)));
                continue;
            }
            Knowledge.Source mined = Knowledge.minedSource(item);
            if (mined != null) {
                int level = Knowledge.miningLevel(mined);
                double cost = level == Integer.MIN_VALUE ? 5 : level < 0 ? 60 : 30;
                routes.add(new Route("mine:" + item, cost, new MineTask(mined, query, count)));
            }
            List<CraftingRecipe> recipes = Knowledge.craftingRecipes(server, item);
            if (!recipes.isEmpty()) {
                double cost = Knowledge.estimateCost(server, item, npc.inventory());
                routes.add(new Route("craft:" + item, cost, new CraftTask(item, count, query)));
            }
            List<SmeltingRecipe> smelts = Knowledge.smeltingRecipes(server, item);
            if (!smelts.isEmpty()) {
                double cost = 1e6;
                for (SmeltingRecipe r : smelts) {
                    for (Ingredient ing : r.getIngredients()) {
                        for (ItemStack opt : ing.getMatchingStacks()) {
                            cost = Math.min(cost, 10 + Knowledge.estimateCost(server, opt.getItem(), npc.inventory()));
                        }
                    }
                }
                routes.add(new Route("smelt:" + item, cost, new SmeltTask(item, count, query)));
            }
            for (EntityType<?> type : Knowledge.mobSources(item)) {
                routes.add(new Route("hunt:" + item + ":" + type.getUntranslatedName(), 25, new HuntTask(type, query, count)));
            }
        }
        return routes.stream()
                .filter(r -> !failedRoutes.contains(r.key()) && r.cost() < 1e5)
                .min(Comparator.comparingDouble(Route::cost))
                .orElse(null);
    }

    @Override
    public String describe() {
        return "getting " + count + " " + query.describe() + " (have " + lastHave + ")";
    }

    @Override
    public Task helpCopy(NpcBrain helper) {
        return new ObtainTask(query, count);
    }
}
