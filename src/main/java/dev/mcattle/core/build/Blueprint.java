package dev.mcattle.core.build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A structure as abstract cells. Coordinates are relative: x to the right, y up, z away from the viewer.
 * The front (door side) is z = 0, so the world placer rotates it to face whoever asked for it.
 * Roles are turned into concrete blocks later, depending on the requested material and what the NPC has.
 */
public final class Blueprint {
    public enum Role {
        /** Ground-level floor. */
        FLOOR,
        /** Main wall material. */
        WALL,
        /** Corner posts / beams (logs for wood builds). */
        FRAME,
        ROOF,
        /** Glass if available, otherwise left open. */
        WINDOW,
        /** Lower half of a door (the door item places the upper half). */
        DOOR,
        /** Torch. */
        LIGHT,
        /** Must be air (interior). */
        CLEAR,
        /** Decorative contrast blocks (crenellations, railings, statue details). */
        ACCENT
    }

    public record Cell(int x, int y, int z, Role role) {
    }

    private final String name;
    private final Map<Long, Cell> cells = new LinkedHashMap<>();
    private int width, height, depth;

    public Blueprint(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int depth() {
        return depth;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF);
    }

    /** Sets a cell (later calls overwrite earlier ones). */
    public Blueprint set(int x, int y, int z, Role role) {
        cells.put(key(x, y, z), new Cell(x, y, z, role));
        width = Math.max(width, x + 1);
        height = Math.max(height, y + 1);
        depth = Math.max(depth, z + 1);
        return this;
    }

    public Blueprint fill(int x0, int y0, int z0, int x1, int y1, int z1, Role role) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                    set(x, y, z, role);
        return this;
    }

    public Role get(int x, int y, int z) {
        Cell c = cells.get(key(x, y, z));
        return c == null ? null : c.role();
    }

    public List<Cell> cells() {
        return Collections.unmodifiableList(new ArrayList<>(cells.values()));
    }

    /** Number of solid blocks (everything except CLEAR). */
    public int solidCount() {
        int n = 0;
        for (Cell c : cells.values()) if (c.role() != Role.CLEAR) n++;
        return n;
    }

    public Map<Role, Integer> roleCounts() {
        Map<Role, Integer> counts = new LinkedHashMap<>();
        for (Cell c : cells.values()) counts.merge(c.role(), 1, Integer::sum);
        return counts;
    }
}
