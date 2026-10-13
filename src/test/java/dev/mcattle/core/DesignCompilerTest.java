package dev.mcattle.core;

import dev.mcattle.core.llm.Design;
import dev.mcattle.core.llm.DesignCompiler;
import dev.mcattle.core.llm.DesignCompiler.Cell;
import dev.mcattle.core.llm.JsonExtract;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DesignCompilerTest {
    private static Design.Part part(String shape, String block, int x1, int y1, int z1, int x2, int y2, int z2, String options, List<String> rows) {
        return new Design.Part(shape, block, x1, y1, z1, x2, y2, z2, options, rows);
    }

    private static Map<String, String> byPos(List<Cell> cells) {
        return cells.stream().collect(Collectors.toMap(c -> c.x() + "," + c.y() + "," + c.z(), Cell::block));
    }

    @Test
    void laterPartsPaintOver() {
        Design d = new Design("hut", "", List.of(
                part("hollow", "oak_planks", 0, 0, 0, 4, 3, 4, "", List.of()),
                part("clear", "", 2, 1, 0, 2, 2, 0, "", List.of()),
                part("block", "minecraft:oak_door[half=lower,facing=north]", 2, 1, 0, 0, 0, 0, "", List.of())));
        Map<String, String> m = byPos(DesignCompiler.compile(d, 32, 5000));
        assertEquals("minecraft:oak_planks", m.get("0,0,0"));
        assertEquals("minecraft:oak_door[half=lower,facing=north]", m.get("2,1,0"));
        assertEquals(DesignCompiler.AIR, m.get("2,2,0"));
        assertNull(m.get("2,1,2"), "hollow interior untouched");
    }

    @Test
    void layersAndPalette() {
        Design d = new Design("board", "", List.of(part("layers", "", 0, 0, 0, 0, 0, 0, "#=white_wool o=stone",
                List.of("#.#", ".o.", "#_#", "---", "o"))));
        Map<String, String> m = byPos(DesignCompiler.compile(d, 32, 5000));
        assertEquals("minecraft:white_wool", m.get("0,0,0"));
        assertEquals("minecraft:stone", m.get("1,0,1"));
        assertEquals(DesignCompiler.AIR, m.get("1,0,2"));
        assertNull(m.get("1,0,0"));
        assertEquals("minecraft:stone", m.get("0,1,0"));
    }

    @Test
    void shapesAndLimits() {
        List<Cell> tower = DesignCompiler.compile(new Design("t", "", List.of(part("cylinder", "stone", 0, 0, 0, 3, 10, 0, "hollow=true", List.of()))), 32, 5000);
        assertTrue(tower.size() > 100);
        List<Cell> capped = DesignCompiler.compile(new Design("big", "", List.of(part("fill", "stone", 0, 0, 0, 100, 100, 100, "", List.of()))), 32, 1000);
        assertEquals(1000, capped.size());
        assertTrue(capped.stream().allMatch(c -> c.x() <= 32 && c.y() <= 32));
        List<Cell> roof = DesignCompiler.compile(new Design("r", "", List.of(part("roof", "spruce_planks", 0, 4, 0, 6, 0, 6, "", List.of()))), 32, 5000);
        assertTrue(roof.stream().anyMatch(c -> c.y() == 7), "ridge at the top");
    }

    @Test
    void parsesModelJson() {
        String json = "{\"name\":\"x\",\"summary\":\"y\",\"parts\":[{\"shape\":\"fill\",\"block\":\"dirt\",\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":1,\"y2\":0,\"z2\":1,\"options\":\"\",\"rows\":[]}]}";
        Design d = JsonExtract.parse(json, Design.class);
        assertNotNull(d);
        assertEquals(4, DesignCompiler.compile(d, 32, 100).size());
    }
}
