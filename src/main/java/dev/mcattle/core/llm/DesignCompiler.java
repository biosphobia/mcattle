package dev.mcattle.core.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a {@link Design} into individual block cells (Minecraft-independent, so it is unit tested).
 * Later parts overwrite earlier ones. Sizes are clamped so a runaway design can't flood the server.
 */
public final class DesignCompiler {
    public static final String AIR = "minecraft:air";

    public record Cell(int x, int y, int z, String block) {
    }

    private DesignCompiler() {
    }

    /**
     * @param maxSpan  largest allowed extent along any axis
     * @param maxCells stop adding blocks after this many (the design is truncated, not rejected)
     */
    public static List<Cell> compile(Design design, int maxSpan, int maxCells) {
        Map<Long, Cell> cells = new LinkedHashMap<>();
        if (design == null || design.parts() == null) return List.of();
        int half = maxSpan;
        for (Design.Part p : design.parts()) {
            if (p == null || p.shape() == null) continue;
            String shape = p.shape().toLowerCase(Locale.ROOT).trim();
            String block = normalize(p.block());
            Map<String, String> opts = options(p.options());
            boolean hollow = "true".equalsIgnoreCase(opts.get("hollow"));
            int x1 = clamp(p.x1(), half), y1 = clamp(p.y1(), half), z1 = clamp(p.z1(), half);
            int x2 = clamp(p.x2(), half), y2 = clamp(p.y2(), half), z2 = clamp(p.z2(), half);
            Painter paint = (x, y, z, b) -> {
                if (cells.size() >= maxCells && !cells.containsKey(key(x, y, z))) return;
                cells.put(key(x, y, z), new Cell(x, y, z, b));
            };
            switch (shape) {
                case "block" -> paint.set(x1, y1, z1, block);
                case "fill" -> box(paint, x1, y1, z1, x2, y2, z2, block, false, false);
                case "clear" -> box(paint, x1, y1, z1, x2, y2, z2, AIR, false, false);
                case "hollow" -> box(paint, x1, y1, z1, x2, y2, z2, block, true, false);
                case "walls" -> box(paint, x1, y1, z1, x2, y2, z2, block, true, true);
                case "line" -> line(paint, x1, y1, z1, x2, y2, z2, block);
                case "cylinder" -> cylinder(paint, x1, y1, z1, Math.max(1, Math.abs(x2)), Math.max(y1, y2), block, hollow);
                case "sphere" -> sphere(paint, x1, y1, z1, Math.max(1, Math.abs(x2)), block, hollow, false);
                case "dome" -> sphere(paint, x1, y1, z1, Math.max(1, Math.abs(x2)), block, hollow, true);
                case "pyramid" -> pyramid(paint, x1, y1, z1, x2, z2, block, hollow);
                case "roof" -> roof(paint, x1, y1, z1, x2, z2, block, "z".equalsIgnoreCase(opts.get("axis")));
                case "layers" -> layers(paint, x1, y1, z1, p.rows(), palette(opts.get("palette"), p.options()));
                default -> {
                }
            }
        }
        return new ArrayList<>(cells.values());
    }

    interface Painter {
        void set(int x, int y, int z, String block);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }

    private static int clamp(int v, int max) {
        return Math.max(-max, Math.min(max, v));
    }

    static String normalize(String block) {
        if (block == null || block.isBlank()) return "minecraft:stone";
        String b = block.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (b.equals("air") || b.equals("_")) return AIR;
        return b.contains(":") ? b : "minecraft:" + b;
    }

    static Map<String, String> options(String options) {
        Map<String, String> map = new HashMap<>();
        if (options == null) return map;
        for (String part : options.trim().split("\\s+")) {
            int eq = part.indexOf('=');
            if (eq > 0) map.putIfAbsent(part.substring(0, eq).toLowerCase(Locale.ROOT), part.substring(eq + 1));
        }
        return map;
    }

    /** "palette=#=stone,w=white_wool" or bare "#=stone w=white_wool" tokens in the options string. */
    static Map<Character, String> palette(String paletteValue, String options) {
        Map<Character, String> map = new HashMap<>();
        List<String> tokens = new ArrayList<>();
        if (paletteValue != null) for (String t : paletteValue.split(",")) tokens.add(t);
        if (options != null) for (String t : options.trim().split("\\s+")) if (!t.toLowerCase(Locale.ROOT).startsWith("palette=")) tokens.add(t);
        for (String t : tokens) {
            int eq = t.indexOf('=');
            if (eq == 1) map.put(t.charAt(0), normalize(t.substring(2)));
        }
        return map;
    }

    private static void box(Painter p, int x1, int y1, int z1, int x2, int y2, int z2, String block, boolean shell, boolean wallsOnly) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), ay = Math.min(y1, y2), by = Math.max(y1, y2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        for (int x = ax; x <= bx; x++)
            for (int y = ay; y <= by; y++)
                for (int z = az; z <= bz; z++) {
                    boolean edgeXZ = x == ax || x == bx || z == az || z == bz;
                    boolean edgeY = y == ay || y == by;
                    if (!shell || edgeXZ || (!wallsOnly && edgeY)) p.set(x, y, z, block);
                }
    }

    private static void line(Painter p, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        int steps = Math.max(Math.abs(x2 - x1), Math.max(Math.abs(y2 - y1), Math.abs(z2 - z1)));
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0 : (double) i / steps;
            p.set((int) Math.round(x1 + (x2 - x1) * t), (int) Math.round(y1 + (y2 - y1) * t), (int) Math.round(z1 + (z2 - z1) * t), block);
        }
    }

    private static void cylinder(Painter p, int cx, int y1, int cz, int r, int y2, String block, boolean hollow) {
        for (int y = y1; y <= y2; y++)
            for (int x = -r; x <= r; x++)
                for (int z = -r; z <= r; z++) {
                    double d = Math.sqrt(x * x + z * z);
                    if (d <= r + 0.4 && (!hollow || d > r - 0.6 || y == y1)) p.set(cx + x, y, cz + z, block);
                }
    }

    private static void sphere(Painter p, int cx, int cy, int cz, int r, String block, boolean hollow, boolean upperHalf) {
        for (int x = -r; x <= r; x++)
            for (int y = upperHalf ? 0 : -r; y <= r; y++)
                for (int z = -r; z <= r; z++) {
                    double d = Math.sqrt(x * x + y * y + z * z);
                    if (d <= r + 0.4 && (!hollow || d > r - 0.6)) p.set(cx + x, cy + y, cz + z, block);
                }
    }

    /** Stepped solid (or hollow) pyramid on the base rectangle. */
    private static void pyramid(Painter p, int x1, int y, int z1, int x2, int z2, String block, boolean hollow) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        for (int k = 0; ax + k <= bx - k && az + k <= bz - k; k++) {
            for (int x = ax + k; x <= bx - k; x++)
                for (int z = az + k; z <= bz - k; z++) {
                    boolean edge = x == ax + k || x == bx - k || z == az + k || z == bz - k;
                    if (!hollow || edge) p.set(x, y + k, z, block);
                }
        }
    }

    /** Gable roof over the rectangle; ridge runs along x unless axis=z. */
    private static void roof(Painter p, int x1, int y, int z1, int x2, int z2, String block, boolean alongZ) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        if (!alongZ) {
            for (int k = 0; az + k <= bz - k; k++)
                for (int x = ax; x <= bx; x++) {
                    p.set(x, y + k, az + k, block);
                    p.set(x, y + k, bz - k, block);
                }
            for (int k = 1; az + k <= bz - k; k++) // gable ends so every roof row has support
                for (int z = az + k; z <= bz - k; z++) {
                    p.set(ax, y + k - 1, z, block);
                    p.set(bx, y + k - 1, z, block);
                }
        } else {
            for (int k = 0; ax + k <= bx - k; k++)
                for (int z = az; z <= bz; z++) {
                    p.set(ax + k, y + k, z, block);
                    p.set(bx - k, y + k, z, block);
                }
            for (int k = 1; ax + k <= bx - k; k++)
                for (int x = ax + k; x <= bx - k; x++) {
                    p.set(x, y + k - 1, az, block);
                    p.set(x, y + k - 1, bz, block);
                }
        }
    }

    private static void layers(Painter p, int x0, int y0, int z0, List<String> rows, Map<Character, String> palette) {
        if (rows == null) return;
        int y = y0, z = z0;
        for (String row : rows) {
            if (row == null) continue;
            if (row.trim().equals("---")) {
                y++;
                z = z0;
                continue;
            }
            for (int i = 0; i < row.length(); i++) {
                char c = row.charAt(i);
                if (c == ' ' || c == '.') continue;
                String block = c == '_' ? AIR : palette.get(c);
                if (block != null) p.set(x0 + i, y, z, block);
            }
            z++;
        }
    }
}
