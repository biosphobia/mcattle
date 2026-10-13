package dev.mcattle.control;

import net.minecraft.block.Block;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.item.SwordItem;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns whatever the player or the language model said ("wood blocks", "#minecraft:planks", "diamond pick",
 * "minecraft:torch", "some food") into an {@link ItemQuery}.
 */
public final class ItemResolver {
    private static Map<String, Item> byName;

    private ItemResolver() {
    }

    private static Map<String, Item> names() {
        if (byName == null) {
            Map<String, Item> map = new HashMap<>();
            for (Item item : Registries.ITEM) {
                Identifier id = Registries.ITEM.getId(item);
                map.putIfAbsent(id.getPath().replace('_', ' '), item);
                String english = Text.translatable(item.getTranslationKey()).getString().toLowerCase(Locale.ROOT);
                map.putIfAbsent(english, item);
            }
            byName = map;
        }
        return byName;
    }

    @Nullable
    public static ItemQuery resolve(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (s.equals("*") || s.equals("everything") || s.equals("all") || s.equals("anything")) return ItemQuery.ANY;
        if (s.startsWith("#")) {
            String id = s.substring(1).replace(' ', '_');
            Identifier tagId = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
            if (tagId == null) return null;
            return ItemQuery.tag(Tags.item(tagId.toString()), tagId.getPath().replace('_', ' '), preferredForTag(id));
        }
        Identifier id = Identifier.tryParse(s.contains(":") ? s : "minecraft:" + s.replace(' ', '_'));
        if (id != null && Registries.ITEM.containsId(id)) return ItemQuery.of(Registries.ITEM.get(id));

        s = s.replaceAll("\\b(blocks? of|pieces? of|a|an|the|some|of|please|more)\\b", " ").replaceAll("\\s+", " ").trim();
        ItemQuery alias = alias(s);
        if (alias != null) return alias;

        Map<String, Item> names = names();
        for (String candidate : variants(s)) {
            Item item = names.get(candidate);
            if (item != null) return ItemQuery.of(item);
            alias = alias(candidate);
            if (alias != null) return alias;
        }
        // fuzzy: item whose name contains every word
        String[] words = singular(s).split(" ");
        Item bestItem = null;
        int bestLen = Integer.MAX_VALUE;
        for (Map.Entry<String, Item> e : names.entrySet()) {
            boolean all = true;
            for (String w : words) {
                if (!e.getKey().contains(w)) {
                    all = false;
                    break;
                }
            }
            if (all && e.getKey().length() < bestLen) {
                bestLen = e.getKey().length();
                bestItem = e.getValue();
            }
        }
        return bestItem == null ? null : ItemQuery.of(bestItem);
    }

    private static String[] variants(String s) {
        String noBlock = s.replaceAll("\\bblocks?$", "").trim();
        return new String[]{s, singular(s), noBlock, singular(noBlock), s.replace(" ", "_")};
    }

    static String singular(String s) {
        if (s.endsWith("ies")) return s.substring(0, s.length() - 3) + "y";
        if (s.endsWith("ches") || s.endsWith("shes") || s.endsWith("xes")) return s.substring(0, s.length() - 2);
        if (s.endsWith("ss")) return s;
        if (s.endsWith("s") && s.length() > 3) return s.substring(0, s.length() - 1);
        return s;
    }

    @Nullable
    private static Item preferredForTag(String id) {
        String path = id.replace("minecraft:", "");
        return switch (path) {
            case "logs", "logs_that_burn" -> Items.OAK_LOG;
            case "planks" -> Items.OAK_PLANKS;
            case "wool" -> Items.WHITE_WOOL;
            case "beds" -> Items.WHITE_BED;
            case "coals" -> Items.COAL;
            case "saplings" -> Items.OAK_SAPLING;
            case "stone_tool_materials", "stone_crafting_materials" -> Items.COBBLESTONE;
            case "swords" -> Items.STONE_SWORD;
            case "pickaxes" -> Items.STONE_PICKAXE;
            case "axes" -> Items.STONE_AXE;
            case "shovels" -> Items.STONE_SHOVEL;
            case "boats" -> Items.OAK_BOAT;
            case "fishes" -> Items.COD;
            default -> null;
        };
    }

    @Nullable
    private static ItemQuery alias(String s) {
        switch (s) {
            case "wood", "woods", "wood block", "wood blocks", "wooden block", "wooden blocks", "log", "logs", "tree",
                 "trees", "wood log", "wood logs", "timber", "lumber" -> {
                return ItemQuery.tag(Tags.ITEM_LOGS, "logs", Items.OAK_LOG);
            }
            case "plank", "planks", "wood plank", "wood planks", "wooden planks", "wooden plank", "board", "boards" -> {
                return ItemQuery.tag(Tags.ITEM_PLANKS, "planks", Items.OAK_PLANKS);
            }
            case "stone", "stones", "rock", "rocks", "cobble", "cobbles", "cobble stone" -> {
                return ItemQuery.category("cobblestone", st -> st.isOf(Items.COBBLESTONE) || st.isOf(Items.COBBLED_DEEPSLATE), Items.COBBLESTONE);
            }
            case "iron", "irons", "iron bar", "iron bars", "iron ingot" -> {
                return ItemQuery.of(Items.IRON_INGOT);
            }
            case "gold", "gold bar", "gold bars" -> {
                return ItemQuery.of(Items.GOLD_INGOT);
            }
            case "copper" -> {
                return ItemQuery.of(Items.COPPER_INGOT);
            }
            case "diamond", "diamonds", "dia", "dias", "diamond gem" -> {
                return ItemQuery.of(Items.DIAMOND);
            }
            case "emerald", "emeralds" -> {
                return ItemQuery.of(Items.EMERALD);
            }
            case "coal", "coals", "fuel" -> {
                return ItemQuery.tag(Tags.ITEM_COALS, "coal", Items.COAL);
            }
            case "wool", "wools" -> {
                return ItemQuery.tag(Tags.ITEM_WOOL, "wool", Items.WHITE_WOOL);
            }
            case "bed", "beds" -> {
                return ItemQuery.tag(Tags.ITEM_BEDS, "bed", Items.WHITE_BED);
            }
            case "sapling", "saplings" -> {
                return ItemQuery.tag(Tags.ITEM_SAPLINGS, "sapling", Items.OAK_SAPLING);
            }
            case "food", "foods", "snack", "snacks", "something to eat", "grub", "meal" -> {
                return ItemQuery.category("food", st -> InventoryControl.foodScore(st) >= 1, Items.COOKED_BEEF);
            }
            case "meat", "steak", "steaks" -> {
                return ItemQuery.category("meat", st -> st.isFood() && st.getItem().getFoodComponent() != null
                        && st.getItem().getFoodComponent().isMeat() && InventoryControl.foodScore(st) >= 1, Items.COOKED_BEEF);
            }
            case "sword", "swords", "weapon", "weapons" -> {
                return ItemQuery.category("sword", st -> st.getItem() instanceof SwordItem, Items.STONE_SWORD);
            }
            case "pickaxe", "pickaxes", "pick", "picks" -> {
                return ItemQuery.category("pickaxe", st -> st.getItem() instanceof PickaxeItem, Items.STONE_PICKAXE);
            }
            case "axe", "axes" -> {
                return ItemQuery.category("axe", st -> st.getItem() instanceof AxeItem, Items.STONE_AXE);
            }
            case "shovel", "shovels", "spade" -> {
                return ItemQuery.category("shovel", st -> st.getItem() instanceof ShovelItem, Items.STONE_SHOVEL);
            }
            case "hoe", "hoes" -> {
                return ItemQuery.category("hoe", st -> st.getItem() instanceof HoeItem, Items.STONE_HOE);
            }
            case "bow", "bows" -> {
                return ItemQuery.category("bow", st -> st.getItem() instanceof BowItem, Items.BOW);
            }
            case "armor", "armour" -> {
                return ItemQuery.category("armor", st -> st.getItem() instanceof ArmorItem, Items.IRON_CHESTPLATE);
            }
            case "diamond pick" -> {
                return ItemQuery.of(Items.DIAMOND_PICKAXE);
            }
            case "iron pick" -> {
                return ItemQuery.of(Items.IRON_PICKAXE);
            }
            case "stone pick" -> {
                return ItemQuery.of(Items.STONE_PICKAXE);
            }
            case "wooden pick", "wood pick", "wood pickaxe" -> {
                return ItemQuery.of(Items.WOODEN_PICKAXE);
            }
            case "torch", "torches", "light", "lights" -> {
                return ItemQuery.of(Items.TORCH);
            }
            case "glass", "window", "windows" -> {
                return ItemQuery.of(Items.GLASS);
            }
            case "door", "doors" -> {
                return ItemQuery.of(Items.OAK_DOOR);
            }
            case "table", "crafting table", "workbench" -> {
                return ItemQuery.of(Items.CRAFTING_TABLE);
            }
            case "string", "strings", "web" -> {
                return ItemQuery.of(Items.STRING);
            }
            case "flower", "flowers" -> {
                return ItemQuery.tag(Tags.item("flowers"), "flowers", Items.POPPY);
            }
            case "seed", "seeds" -> {
                return ItemQuery.of(Items.WHEAT_SEEDS);
            }
            case "dirt", "dirt block", "dirt blocks", "mud" -> {
                return ItemQuery.of(Items.DIRT);
            }
            default -> {
                return null;
            }
        }
    }

    /** Block a material word refers to ("oak planks", "stone bricks", "minecraft:cobblestone"); null if not a block. */
    @Nullable
    public static Block resolveBlock(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (s.equals("wood") || s.equals("wooden") || s.equals("planks")) return net.minecraft.block.Blocks.OAK_PLANKS;
        if (s.equals("stone") || s.equals("cobble") || s.equals("rock")) return net.minecraft.block.Blocks.COBBLESTONE;
        if (s.equals("brick")) return net.minecraft.block.Blocks.BRICKS;
        if (s.matches("(oak|spruce|birch|jungle|acacia|dark oak|mangrove|cherry|bamboo)( wood)?")) {
            Identifier id = Identifier.tryParse("minecraft:" + s.replace(" wood", "").replace(' ', '_') + "_planks");
            if (id != null && Registries.BLOCK.containsId(id)) return Registries.BLOCK.get(id);
        }
        ItemQuery q = resolve(raw);
        if (q == null || q.preferred() == null) return null;
        Item item = q.preferred();
        if (item instanceof BlockItem bi) return bi.getBlock();
        return null;
    }

    public static String describe(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).getPath() + (stack.getCount() > 1 ? " x" + stack.getCount() : "");
    }
}
