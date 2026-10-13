package dev.mcattle.core.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * A free-form structure designed by the language model, as a list of drawing operations in a local grid:
 * x to the right, y up (0 = ground level), z forward (away from the person who asked). Later parts paint over
 * earlier ones, so hollow out interiors and cut doors/windows after drawing walls.
 */
public record Design(
        @JsonPropertyDescription("Short name, e.g. 'mushroom cottage'")
        String name,
        @JsonPropertyDescription("One sentence the builder can say about it")
        String summary,
        @JsonPropertyDescription("Drawing operations in order")
        List<Part> parts) {

    public record Part(
            @JsonPropertyDescription("block | fill | hollow | walls | line | cylinder | sphere | dome | pyramid | roof | clear | layers")
            String shape,
            @JsonPropertyDescription("Block id with optional state, e.g. 'minecraft:spruce_stairs[facing=north,half=bottom]', 'minecraft:air'. For 'layers' leave empty and use the palette option.")
            String block,
            int x1, int y1, int z1,
            @JsonPropertyDescription("Second corner (or end point). For cylinder/sphere/dome: x1,y1,z1 is the center, x2 = radius, y2 = top y for cylinders.")
            int x2, int y2, int z2,
            @JsonPropertyDescription("key=value options: hollow=true, axis=x|z (roof ridge direction), palette for layers like '#=minecraft:stone_bricks w=minecraft:white_wool' ('.' or space = leave as is, '_' = air); empty if unused")
            String options,
            @JsonPropertyDescription("Only for shape 'layers': rows of characters starting at x1,y1,z1. Each string is one row along x; consecutive rows go +z; an entry '---' starts the next layer one block up. Empty list otherwise.")
            List<String> rows) {
    }
}
