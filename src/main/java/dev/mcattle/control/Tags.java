package dev.mcattle.control;

import net.minecraft.block.Block;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

/** Vanilla tags looked up by id (so the code doesn't depend on which constants a given mapping exposes). */
public final class Tags {
    private Tags() {
    }

    public static TagKey<Block> block(String id) {
        return TagKey.of(RegistryKeys.BLOCK, new Identifier(id.contains(":") ? id : "minecraft:" + id));
    }

    public static TagKey<Item> item(String id) {
        return TagKey.of(RegistryKeys.ITEM, new Identifier(id.contains(":") ? id : "minecraft:" + id));
    }

    public static TagKey<EntityType<?>> entity(String id) {
        return TagKey.of(RegistryKeys.ENTITY_TYPE, new Identifier(id.contains(":") ? id : "minecraft:" + id));
    }

    public static final TagKey<Block> LOGS = block("logs");
    public static final TagKey<Block> LEAVES = block("leaves");
    public static final TagKey<Block> PLANKS = block("planks");
    public static final TagKey<Block> BEDS = block("beds");
    public static final TagKey<Block> DIRT = block("dirt");
    public static final TagKey<Block> SAND = block("sand");
    public static final TagKey<Block> BASE_STONE_OVERWORLD = block("base_stone_overworld");
    public static final TagKey<Block> BASE_STONE_NETHER = block("base_stone_nether");
    public static final TagKey<Block> CLIMBABLE = block("climbable");
    public static final TagKey<Block> WOODEN_DOORS = block("wooden_doors");
    public static final TagKey<Block> FENCE_GATES = block("fence_gates");
    public static final TagKey<Block> FENCES = block("fences");
    public static final TagKey<Block> WALLS = block("walls");
    public static final TagKey<Block> FIRE = block("fire");
    public static final TagKey<Block> SAPLINGS = block("saplings");
    public static final TagKey<Block> CROPS = block("crops");
    public static final TagKey<Block> FLOWERS = block("flowers");
    public static final TagKey<Block> SNOW = block("snow");
    public static final TagKey<Block> ICE = block("ice");
    public static final TagKey<Block> TERRACOTTA = block("terracotta");
    public static final TagKey<Block> MINEABLE_PICKAXE = block("mineable/pickaxe");
    public static final TagKey<Block> MINEABLE_AXE = block("mineable/axe");
    public static final TagKey<Block> MINEABLE_SHOVEL = block("mineable/shovel");
    public static final TagKey<Block> MINEABLE_HOE = block("mineable/hoe");
    public static final TagKey<Block> SHULKER_BOXES = block("shulker_boxes");

    public static final String[] ORE_TAGS = {"coal_ores", "iron_ores", "copper_ores", "gold_ores", "redstone_ores",
            "lapis_ores", "diamond_ores", "emerald_ores"};

    public static final TagKey<Item> ITEM_LOGS = item("logs");
    public static final TagKey<Item> ITEM_PLANKS = item("planks");
    public static final TagKey<Item> ITEM_WOOL = item("wool");
    public static final TagKey<Item> ITEM_SAPLINGS = item("saplings");
    public static final TagKey<Item> ITEM_COALS = item("coals");
    public static final TagKey<Item> ITEM_BEDS = item("beds");
}
