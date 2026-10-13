package dev.mcattle.control;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What a seasoned player knows: which blocks drop what, which mobs drop what, how to craft and smelt things,
 * what tool a block needs and at which depth ores are found.
 */
public final class Knowledge {
    private Knowledge() {
    }

    public record Source(String description, Predicate<BlockState> blocks) {
    }

    private static final Map<Item, Source> MINED = new HashMap<>();
    private static final Map<Item, List<EntityType<?>>> MOB_DROPS = new HashMap<>();
    private static final Set<Block> NOT_SELF_DROPPING = new HashSet<>();

    static {
        mined(Items.COBBLESTONE, "stone", s -> s.isOf(Blocks.STONE) || s.isOf(Blocks.COBBLESTONE));
        mined(Items.COBBLED_DEEPSLATE, "deepslate", s -> s.isOf(Blocks.DEEPSLATE) || s.isOf(Blocks.COBBLED_DEEPSLATE));
        mined(Items.DIRT, "dirt", s -> s.isOf(Blocks.DIRT) || s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.PODZOL) || s.isOf(Blocks.MYCELIUM));
        mined(Items.COAL, "coal ore", s -> s.isIn(Tags.block("coal_ores")));
        mined(Items.RAW_IRON, "iron ore", s -> s.isIn(Tags.block("iron_ores")));
        mined(Items.RAW_GOLD, "gold ore", s -> s.isIn(Tags.block("gold_ores")));
        mined(Items.RAW_COPPER, "copper ore", s -> s.isIn(Tags.block("copper_ores")));
        mined(Items.DIAMOND, "diamond ore", s -> s.isIn(Tags.block("diamond_ores")));
        mined(Items.EMERALD, "emerald ore", s -> s.isIn(Tags.block("emerald_ores")));
        mined(Items.REDSTONE, "redstone ore", s -> s.isIn(Tags.block("redstone_ores")));
        mined(Items.LAPIS_LAZULI, "lapis ore", s -> s.isIn(Tags.block("lapis_ores")));
        mined(Items.QUARTZ, "nether quartz ore", s -> s.isOf(Blocks.NETHER_QUARTZ_ORE));
        mined(Items.GOLD_NUGGET, "nether gold ore", s -> s.isOf(Blocks.NETHER_GOLD_ORE));
        mined(Items.FLINT, "gravel", s -> s.isOf(Blocks.GRAVEL));
        mined(Items.CLAY_BALL, "clay", s -> s.isOf(Blocks.CLAY));
        mined(Items.SNOWBALL, "snow", s -> s.isOf(Blocks.SNOW_BLOCK) || s.isOf(Blocks.SNOW));
        mined(Items.GLOWSTONE_DUST, "glowstone", s -> s.isOf(Blocks.GLOWSTONE));
        mined(Items.STRING, "cobweb", s -> s.isOf(Blocks.COBWEB));
        mined(Items.MELON_SLICE, "melon", s -> s.isOf(Blocks.MELON));
        mined(Items.AMETHYST_SHARD, "amethyst cluster", s -> s.isOf(Blocks.AMETHYST_CLUSTER));
        mined(Items.STICK, "dead bush", s -> s.isOf(Blocks.DEAD_BUSH));

        for (Block b : new Block[]{Blocks.STONE, Blocks.DEEPSLATE, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM,
                Blocks.GLOWSTONE, Blocks.CLAY, Blocks.SNOW_BLOCK, Blocks.SNOW, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE,
                Blocks.GLASS, Blocks.GLASS_PANE, Blocks.GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN,
                Blocks.COBWEB, Blocks.BOOKSHELF, Blocks.MELON, Blocks.SEA_LANTERN, Blocks.BUDDING_AMETHYST,
                Blocks.AMETHYST_CLUSTER, Blocks.DEAD_BUSH, Blocks.TURTLE_EGG, Blocks.SCULK_SENSOR, Blocks.SCULK_SHRIEKER,
                Blocks.SCULK_CATALYST, Blocks.BEE_NEST, Blocks.SPAWNER, Blocks.INFESTED_STONE, Blocks.REINFORCED_DEEPSLATE}) {
            NOT_SELF_DROPPING.add(b);
        }

        mob(Items.LEATHER, EntityType.COW, EntityType.MOOSHROOM, EntityType.HORSE);
        mob(Items.BEEF, EntityType.COW, EntityType.MOOSHROOM);
        mob(Items.PORKCHOP, EntityType.PIG);
        mob(Items.MUTTON, EntityType.SHEEP);
        mob(Items.WHITE_WOOL, EntityType.SHEEP);
        mob(Items.CHICKEN, EntityType.CHICKEN);
        mob(Items.FEATHER, EntityType.CHICKEN);
        mob(Items.RABBIT, EntityType.RABBIT);
        mob(Items.RABBIT_HIDE, EntityType.RABBIT);
        mob(Items.STRING, EntityType.SPIDER, EntityType.CAVE_SPIDER);
        mob(Items.SPIDER_EYE, EntityType.SPIDER, EntityType.CAVE_SPIDER);
        mob(Items.BONE, EntityType.SKELETON, EntityType.STRAY);
        mob(Items.ARROW, EntityType.SKELETON, EntityType.STRAY);
        mob(Items.ROTTEN_FLESH, EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED);
        mob(Items.GUNPOWDER, EntityType.CREEPER);
        mob(Items.ENDER_PEARL, EntityType.ENDERMAN);
        mob(Items.SLIME_BALL, EntityType.SLIME);
        mob(Items.BLAZE_ROD, EntityType.BLAZE);
        mob(Items.INK_SAC, EntityType.SQUID);
        mob(Items.GLOW_INK_SAC, EntityType.GLOW_SQUID);
        mob(Items.COD, EntityType.COD);
        mob(Items.SALMON, EntityType.SALMON);
        mob(Items.PHANTOM_MEMBRANE, EntityType.PHANTOM);
    }

    private static void mined(Item drop, String description, Predicate<BlockState> blocks) {
        MINED.put(drop, new Source(description, blocks));
    }

    private static void mob(Item drop, EntityType<?>... types) {
        MOB_DROPS.put(drop, List.of(types));
    }

    /** Blocks that drop this item when mined, or null. Natural blocks that drop themselves are included. */
    @Nullable
    public static Source minedSource(Item item) {
        Source s = MINED.get(item);
        if (s != null) return s;
        if (item instanceof BlockItem bi) {
            Block block = bi.getBlock();
            BlockState state = block.getDefaultState();
            if (!NOT_SELF_DROPPING.contains(block) && (WorldRules.isNatural(state) || state.isIn(Tags.LOGS)
                    || state.isIn(Tags.FLOWERS) || block == Blocks.SUGAR_CANE || block == Blocks.PUMPKIN
                    || block == Blocks.CACTUS || block == Blocks.BAMBOO || block == Blocks.KELP || block == Blocks.LILY_PAD
                    || block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN || block == Blocks.MOSS_CARPET)) {
                return new Source(Registries.BLOCK.getId(block).getPath().replace('_', ' '), st -> st.isOf(block));
            }
        }
        return null;
    }

    public static List<EntityType<?>> mobSources(Item item) {
        return MOB_DROPS.getOrDefault(item, List.of());
    }

    public static boolean isLog(Item item) {
        return new ItemStack(item).isIn(Tags.ITEM_LOGS);
    }

    /** Cheapest pickaxe (or other tool) that can harvest the block, or null if a bare hand works. */
    @Nullable
    public static Item requiredTool(BlockState state) {
        if (!state.isToolRequired()) return null;
        Item[] tiers = state.isIn(Tags.MINEABLE_PICKAXE)
                ? new Item[]{Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE}
                : state.isIn(Tags.MINEABLE_AXE) ? new Item[]{Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE}
                : state.isIn(Tags.MINEABLE_SHOVEL) ? new Item[]{Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL}
                : state.isIn(Tags.MINEABLE_HOE) ? new Item[]{Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE, Items.DIAMOND_HOE}
                : new Item[]{Items.SHEARS, Items.WOODEN_SWORD};
        for (Item tool : tiers) if (new ItemStack(tool).isSuitableFor(state)) return tool;
        return tiers[tiers.length - 1];
    }

    /** Good Y level for branch mining a given source. */
    public static int miningLevel(Source source) {
        String d = source.description();
        if (d.contains("diamond") || d.contains("redstone")) return -58;
        if (d.contains("gold")) return -16;
        if (d.contains("lapis")) return 0;
        if (d.contains("iron")) return 16;
        if (d.contains("copper") || d.contains("coal")) return 48;
        if (d.contains("emerald")) return 100;
        if (d.contains("deepslate")) return -8;
        return Integer.MIN_VALUE; // anywhere
    }

    // ------------------------------------------------------------------ recipes

    private static RecipeManager cachedManager;
    private static Map<Item, List<CraftingRecipe>> crafting = new HashMap<>();
    private static Map<Item, List<SmeltingRecipe>> smelting = new HashMap<>();

    private static void ensureIndexed(MinecraftServer server) {
        RecipeManager manager = server.getRecipeManager();
        if (manager == cachedManager) return;
        DynamicRegistryManager registries = server.getRegistryManager();
        Map<Item, List<CraftingRecipe>> c = new HashMap<>();
        for (CraftingRecipe r : manager.listAllOfType(RecipeType.CRAFTING)) {
            if (r.getIngredients().isEmpty()) continue; // special recipes (map cloning, fireworks...)
            ItemStack out = r.getOutput(registries);
            if (out.isEmpty()) continue;
            c.computeIfAbsent(out.getItem(), k -> new ArrayList<>()).add(r);
        }
        Map<Item, List<SmeltingRecipe>> s = new HashMap<>();
        for (SmeltingRecipe r : manager.listAllOfType(RecipeType.SMELTING)) {
            ItemStack out = r.getOutput(registries);
            if (!out.isEmpty()) s.computeIfAbsent(out.getItem(), k -> new ArrayList<>()).add(r);
        }
        crafting = c;
        smelting = s;
        cachedManager = manager;
    }

    public static List<CraftingRecipe> craftingRecipes(MinecraftServer server, Item output) {
        ensureIndexed(server);
        return crafting.getOrDefault(output, List.of());
    }

    public static List<SmeltingRecipe> smeltingRecipes(MinecraftServer server, Item output) {
        ensureIndexed(server);
        return smelting.getOrDefault(output, List.of());
    }

    /** Fits in the 2x2 inventory grid (no crafting table needed). */
    public static boolean fitsInInventoryGrid(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) return shaped.getWidth() <= 2 && shaped.getHeight() <= 2;
        int n = 0;
        for (Ingredient i : recipe.getIngredients()) if (!i.isEmpty()) n++;
        return n <= 4;
    }

    public static int cookTime(AbstractCookingRecipe recipe) {
        return recipe.getCookTime();
    }

    /** Fuel value in items smelted (vanilla: coal 8, planks 1.5, logs 1.5, sticks 0.5...). */
    public static double fuelValue(ItemStack stack) {
        Item item = stack.getItem();
        if (item == Items.COAL || item == Items.CHARCOAL) return 8;
        if (item == Items.COAL_BLOCK) return 80;
        if (item == Items.LAVA_BUCKET) return 100;
        if (item == Items.BLAZE_ROD) return 12;
        if (stack.isIn(Tags.ITEM_PLANKS) || stack.isIn(Tags.ITEM_LOGS)) return 1.5;
        if (item == Items.STICK) return 0.5;
        if (stack.isIn(Tags.item("wooden_slabs"))) return 0.75;
        return 0;
    }

    /** Rough "effort" to get one of an item, used to pick between crafting, mining, smelting and hunting. */
    /** Cost of making the item by crafting only, ignoring recipes that need anything in {@code exclude}. */
    public static double craftCost(MinecraftServer server, Item item, InventoryControl inv, Set<Item> exclude) {
        double best = 1e6;
        Map<Item, Double> memo = new HashMap<>();
        for (CraftingRecipe r : craftingRecipes(server, item)) {
            double sum = 2;
            int outCount = Math.max(1, r.getOutput(server.getRegistryManager()).getCount());
            for (Ingredient ing : r.getIngredients()) {
                if (ing.isEmpty()) continue;
                double cheapest = 1e6;
                for (ItemStack option : ing.getMatchingStacks()) {
                    if (exclude.contains(option.getItem())) continue;
                    Set<Item> visiting = new HashSet<>(exclude);
                    cheapest = Math.min(cheapest, estimateCost(server, option.getItem(), inv, 1, visiting, memo));
                }
                sum += cheapest / outCount;
            }
            best = Math.min(best, sum);
        }
        return best;
    }

    public static double estimateCost(MinecraftServer server, Item item, InventoryControl inv) {
        return estimateCost(server, item, inv, 0, new HashSet<>(), new HashMap<>());
    }

    private static double estimateCost(MinecraftServer server, Item item, InventoryControl inv, int depth, Set<Item> visiting, Map<Item, Double> memo) {
        if (inv.count(item) > 0) return 0;
        Double known = memo.get(item);
        if (known != null) return known;
        if (depth > 6 || !visiting.add(item)) return 1e6;
        try {
            double best = 1e6;
            if (isLog(item)) best = Math.min(best, 4);
            Source mined = minedSource(item);
            if (mined != null) {
                int level = miningLevel(mined);
                best = Math.min(best, level == Integer.MIN_VALUE ? 5 : level < 0 ? 60 : 30);
            }
            for (CraftingRecipe r : craftingRecipes(server, item)) {
                double sum = 2;
                int outCount = Math.max(1, r.getOutput(server.getRegistryManager()).getCount());
                for (Ingredient ing : r.getIngredients()) {
                    if (ing.isEmpty()) continue;
                    double cheapest = 1e6;
                    for (ItemStack option : ing.getMatchingStacks()) {
                        cheapest = Math.min(cheapest, estimateCost(server, option.getItem(), inv, depth + 1, visiting, memo));
                        if (cheapest == 0) break;
                    }
                    sum += cheapest / outCount;
                    if (sum >= best) break;
                }
                best = Math.min(best, sum);
            }
            for (SmeltingRecipe r : smeltingRecipes(server, item)) {
                for (Ingredient ing : r.getIngredients()) {
                    for (ItemStack option : ing.getMatchingStacks()) {
                        best = Math.min(best, 10 + estimateCost(server, option.getItem(), inv, depth + 1, visiting, memo));
                    }
                }
            }
            if (!mobSources(item).isEmpty()) best = Math.min(best, 25);
            memo.put(item, best);
            return best;
        } finally {
            visiting.remove(item);
        }
    }

    public static boolean tagContains(TagKey<Item> tag, Item item) {
        return new ItemStack(item).isIn(tag);
    }
}
