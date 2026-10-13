package dev.mcattle.task;

import dev.mcattle.control.InventoryControl;
import dev.mcattle.control.ItemQuery;
import dev.mcattle.control.Tags;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Free play without any language model: follows the classic survival progression (wood, tools, food, iron,
 * armor, a bed, diamonds), sleeps at night, builds itself a little home and otherwise explores, gathers and
 * hangs out near players. Goals that fail are put on cooldown so the NPC doesn't get stuck retrying.
 */
public final class AutonomousPlanner {
    private static final long FAIL_COOLDOWN = 20L * 60 * 5;

    private final NpcBrain npc;
    private final Set<Task> owned = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Task, String> keys = new WeakHashMap<>();
    private final Map<String, Long> cooldownUntil = new HashMap<>();
    private boolean builtHome;

    public AutonomousPlanner(NpcBrain npc) {
        this.npc = npc;
    }

    public boolean owns(Task task) {
        return owned.contains(task);
    }

    public void onFinished(Task task, Task.Status status) {
        String key = keys.get(task);
        if (key == null) return;
        if (status == Task.Status.FAILURE) cooldownUntil.put(key, npc.age() + FAIL_COOLDOWN);
        if (key.equals("home") && status == Task.Status.SUCCESS) builtHome = true;
    }

    private boolean ready(String key) {
        return cooldownUntil.getOrDefault(key, 0L) <= npc.age();
    }

    private Task own(String key, Task task) {
        owned.add(task);
        keys.put(task, key);
        return task;
    }

    @Nullable
    public Task next() {
        InventoryControl inv = npc.inventory();
        var world = npc.world();
        boolean night = world.isNight();

        if (npc.npc().getBlockPos().getSquaredDistance(npc.home()) > 160 * 160 && ready("home-walk")) {
            return own("home-walk", new GotoTask(Target.of(npc.home()), 4));
        }
        if (night && ready("sleep") && inv.has(s -> s.isIn(Tags.ITEM_BEDS))) {
            return own("sleep", new SleepTask());
        }
        int food = npc.npc().getHungerManager().getFoodLevel();
        if (food <= 12 && inv.foodCount() == 0 && ready("food")) {
            return own("food", new ObtainTask(ItemQuery.category("cooked meat",
                    s -> s.isOf(Items.COOKED_BEEF) || s.isOf(Items.COOKED_PORKCHOP) || s.isOf(Items.COOKED_MUTTON)
                            || s.isOf(Items.COOKED_CHICKEN) || s.isOf(Items.BREAD), Items.COOKED_BEEF), 4));
        }

        // remembered standing orders: "always follow me" / "always protect me"
        var id = npc.identity();
        if (id.guard != null) {
            var p = npc.server().getPlayerManager().getPlayer(id.guard);
            if (p != null && p.getWorld() == world) return own("guard", new TimedTask(new GuardTask(Target.of(p)), 20 * 60 * 5, "guarding " + p.getName().getString()));
        }
        if (id.follow != null) {
            var p = npc.server().getPlayerManager().getPlayer(id.follow);
            if (p != null) return own("follow", new TimedTask(new FollowTask(Target.of(p), 3), 20 * 60 * 5, "following " + p.getName().getString()));
        }

        int pickTier = bestTier(inv, PickaxeItem.class);
        if (pickTier < 0 && inv.count(s -> s.isIn(Tags.ITEM_LOGS)) < 3 && inv.count(s -> s.isIn(Tags.ITEM_PLANKS)) < 8 && ready("wood")) {
            return own("wood", new ChopTask(ItemQuery.tag(Tags.ITEM_LOGS, "logs", Items.OAK_LOG), 8,
                    ItemQuery.tag(Tags.ITEM_LOGS, "logs", Items.OAK_LOG)));
        }
        Task t;
        if (pickTier < 0 && (t = obtain("wooden_pickaxe", Items.WOODEN_PICKAXE)) != null) return t;
        if (pickTier < 1 && (t = obtain("stone_pickaxe", Items.STONE_PICKAXE)) != null) return t;
        if (bestTier(inv, SwordItem.class) < 1 && (t = obtain("stone_sword", Items.STONE_SWORD)) != null) return t;
        if (bestTier(inv, AxeItem.class) < 1 && (t = obtain("stone_axe", Items.STONE_AXE)) != null) return t;
        if (inv.foodCount() < 3 && food <= 18 && ready("food")) {
            return own("food", new ObtainTask(ItemQuery.category("cooked meat",
                    s -> s.isOf(Items.COOKED_BEEF) || s.isOf(Items.COOKED_PORKCHOP) || s.isOf(Items.COOKED_MUTTON)
                            || s.isOf(Items.COOKED_CHICKEN), Items.COOKED_BEEF), 5));
        }
        if (!builtHome && ready("home") && !night) {
            BlockPos home = npc.home();
            BuildJob job = dev.mcattle.ai.BuildFactory.start(npc, "a small cozy survival starter house for one, made of what's easy to get around here",
                    "small", home, Direction.fromRotation(npc.random().nextInt(4) * 90), false);
            builtHome = true; // try once per session
            npc.say("gonna build myself a little house");
            return own("home", new BuildTask(job));
        }
        if (pickTier < 2 && (t = obtain("iron_pickaxe", Items.IRON_PICKAXE)) != null) return t;
        if (bestTier(inv, SwordItem.class) < 2 && (t = obtain("iron_sword", Items.IRON_SWORD)) != null) return t;
        if (!inv.has(s -> s.isOf(Items.SHIELD)) && !npc.npc().getOffHandStack().isOf(Items.SHIELD)
                && (t = obtain("shield", Items.SHIELD)) != null) return t;
        for (Item armor : new Item[]{Items.IRON_CHESTPLATE, Items.IRON_HELMET, Items.IRON_LEGGINGS, Items.IRON_BOOTS}) {
            if (!wearsOrHas(armor) && (t = obtain(armor.toString(), armor)) != null) return t;
        }
        if (!inv.has(s -> s.isIn(Tags.ITEM_BEDS)) && ready("bed")) {
            return own("bed", new ObtainTask(ItemQuery.tag(Tags.ITEM_BEDS, "bed", Items.WHITE_BED), 1));
        }
        if (pickTier < 3 && (t = obtain("diamond_pickaxe", Items.DIAMOND_PICKAXE)) != null) return t;
        if (bestTier(inv, SwordItem.class) < 3 && (t = obtain("diamond_sword", Items.DIAMOND_SWORD)) != null) return t;

        // leisure
        int roll = npc.random().nextInt(10);
        var players = npc.nearbyPlayers(32, false);
        if (roll < 2 && !players.isEmpty() && ready("hangout")) {
            var p = players.get(npc.random().nextInt(players.size()));
            cooldownUntil.put("hangout", npc.age() + 20L * 60 * 3);
            return own("hangout", new TimedTask(new FollowTask(Target.of(p), 4), 20 * 60, "hanging out with " + p.getName().getString()));
        }
        if (roll < 4 && ready("explore")) return own("explore", new ExploreTask(64, 20 * 60));
        if (roll < 6 && ready("chop")) {
            ItemQuery logs = ItemQuery.tag(Tags.ITEM_LOGS, "logs", Items.OAK_LOG);
            return own("chop", new ChopTask(logs, inv.count(logs) + 16, logs));
        }
        if (roll < 8 && ready("coal")) {
            return own("coal", new ObtainTask(ItemQuery.of(Items.COAL), inv.count(Items.COAL) + 8));
        }
        return own("idle", new TimedTask(new LookAroundTask(), 20 * 20, "chilling"));
    }

    @Nullable
    private Task obtain(String key, Item item) {
        if (!ready(key)) return null;
        return own(key, new ObtainTask(ItemQuery.of(item), 1));
    }

    private boolean wearsOrHas(Item armor) {
        for (ItemStack s : npc.npc().getArmorItems()) if (s.isOf(armor)) return true;
        if (npc.inventory().count(armor) > 0) return true;
        // anything at least as good in that slot counts
        if (armor instanceof ArmorItem a) {
            ItemStack worn = npc.npc().getEquippedStack(a.getSlotType());
            return worn.getItem() instanceof ArmorItem w && w.getProtection() >= a.getProtection();
        }
        return false;
    }

    /** -1 none, 0 wood/gold, 1 stone, 2 iron, 3 diamond, 4 netherite. */
    static int bestTier(InventoryControl inv, Class<? extends ToolItem> type) {
        int best = -1;
        for (ItemStack s : inv.inv().main) {
            if (!type.isInstance(s.getItem())) continue;
            int level = ((ToolItem) s.getItem()).getMaterial().getMiningLevel();
            best = Math.max(best, level);
        }
        return best;
    }
}
