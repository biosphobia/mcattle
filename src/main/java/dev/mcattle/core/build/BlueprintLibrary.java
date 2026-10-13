package dev.mcattle.core.build;

import dev.mcattle.core.build.Blueprint.Role;

import java.util.List;
import java.util.Locale;

/** Procedural structures NPCs know how to build. Sizes are clamped to keep survival builds reasonable. */
public final class BlueprintLibrary {
    public static final List<String> NAMES = List.of("house", "hut", "cabin", "tower", "wall", "bridge", "pyramid",
            "platform", "pillar", "fort", "igloo", "statue");

    private BlueprintLibrary() {
    }

    /**
     * @param size   main horizontal size, or 0 for the default
     * @param height main vertical size, or 0 for the default
     */
    public static Blueprint create(String structure, int size, int height) {
        String s = structure == null ? "house" : structure.toLowerCase(Locale.ROOT).trim();
        return switch (s) {
            case "hut", "shelter", "shack" -> hut(clamp(size, 5, 4, 7), clamp(height, 3, 3, 4));
            case "cabin" -> cabin(clamp(size, 7, 5, 11), clamp(height, 3, 3, 5));
            case "tower", "skyscraper" -> tower(clamp(size, 5, 3, 9), clamp(height, 12, 5, 40));
            case "wall" -> wall(clamp(size, 16, 3, 64), clamp(height, 3, 1, 10));
            case "bridge" -> bridge(clamp(size, 12, 3, 64));
            case "pyramid", "monument" -> pyramid(clamp(size, 9, 3, 21));
            case "platform", "floor" -> platform(clamp(size, 7, 2, 21));
            case "pillar" -> pillar(clamp(height > 0 ? height : size, 10, 2, 64));
            case "fort", "castle", "fortress" -> fort(clamp(size, 11, 7, 21), clamp(height, 4, 3, 8));
            case "igloo" -> igloo(clamp(size, 7, 5, 9));
            case "statue" -> statue(clamp(size, 1, 1, 3));
            default -> house(clamp(size, 7, 5, 13), clamp(height, 4, 3, 6));
        };
    }

    private static int clamp(int requested, int fallback, int min, int max) {
        int v = requested <= 0 ? fallback : requested;
        return Math.max(min, Math.min(max, v));
    }

    /** Box room shell: floor, walls with frame corners, clear interior. Returns the blueprint for further edits. */
    private static Blueprint room(Blueprint b, int w, int d, int h) {
        b.fill(0, 0, 0, w - 1, 0, d - 1, Role.FLOOR);
        for (int y = 1; y <= h; y++) {
            for (int x = 0; x < w; x++) {
                b.set(x, y, 0, Role.WALL);
                b.set(x, y, d - 1, Role.WALL);
            }
            for (int z = 0; z < d; z++) {
                b.set(0, y, z, Role.WALL);
                b.set(w - 1, y, z, Role.WALL);
            }
            b.set(0, y, 0, Role.FRAME);
            b.set(w - 1, y, 0, Role.FRAME);
            b.set(0, y, d - 1, Role.FRAME);
            b.set(w - 1, y, d - 1, Role.FRAME);
        }
        b.fill(1, 1, 1, w - 2, h, d - 2, Role.CLEAR);
        return b;
    }

    private static void door(Blueprint b, int x, int z) {
        b.set(x, 1, z, Role.DOOR);
        b.set(x, 2, z, Role.CLEAR);
    }

    /** Gable roof running along x, resting on walls of height h. Gable ends are filled so every row has support. */
    private static void gableRoof(Blueprint b, int w, int d, int h, Role roof, Role gable) {
        int layers = (d + 1) / 2;
        for (int k = 0; k < layers; k++) {
            int y = h + 1 + k;
            int zFront = k, zBack = d - 1 - k;
            for (int x = 0; x < w; x++) {
                b.set(x, y, zFront, roof);
                b.set(x, y, zBack, roof);
            }
            // gable end triangles (x = 0 and x = w-1) between the two roof rows
            for (int z = zFront + 1; z < zBack; z++) {
                b.set(0, y, z, gable);
                b.set(w - 1, y, z, gable);
                for (int x = 1; x < w - 1; x++) b.set(x, y, z, Role.CLEAR);
            }
        }
    }

    public static Blueprint house(int w, int h) {
        int d = w;
        Blueprint b = room(new Blueprint("house"), w, d, h);
        door(b, w / 2, 0);
        // windows on all four walls at eye height
        int wy = 2;
        for (int x = 2; x < w - 2; x += 2) {
            if (x != w / 2) b.set(x, wy, 0, Role.WINDOW);
            b.set(x, wy, d - 1, Role.WINDOW);
        }
        for (int z = 2; z < d - 2; z += 2) {
            b.set(0, wy, z, Role.WINDOW);
            b.set(w - 1, wy, z, Role.WINDOW);
        }
        gableRoof(b, w, d, h, Role.ROOF, Role.WALL);
        b.set(1, h - 1, d / 2, Role.LIGHT);
        b.set(w - 2, h - 1, d / 2, Role.LIGHT);
        return b;
    }

    public static Blueprint hut(int w, int h) {
        Blueprint b = room(new Blueprint("hut"), w, w, h);
        door(b, w / 2, 0);
        b.fill(0, h + 1, 0, w - 1, h + 1, w - 1, Role.ROOF);
        b.set(w / 2, 2, w - 1, Role.WINDOW);
        b.set(1, h, w / 2, Role.LIGHT);
        return b;
    }

    public static Blueprint cabin(int w, int h) {
        int d = Math.max(5, w - 2);
        Blueprint b = room(new Blueprint("cabin"), w, d, h);
        for (int y = 1; y <= h; y++) { // log walls: whole wall is frame material
            for (int x = 0; x < w; x++) {
                b.set(x, y, 0, Role.FRAME);
                b.set(x, y, d - 1, Role.FRAME);
            }
            for (int z = 0; z < d; z++) {
                b.set(0, y, z, Role.FRAME);
                b.set(w - 1, y, z, Role.FRAME);
            }
        }
        door(b, w / 2, 0);
        b.set(w / 2 - 2, 2, 0, Role.WINDOW);
        b.set(w / 2 + 2, 2, 0, Role.WINDOW);
        b.set(w / 2, 2, d - 1, Role.WINDOW);
        gableRoof(b, w, d, h, Role.ROOF, Role.WALL);
        b.set(1, h, d / 2, Role.LIGHT);
        return b;
    }

    public static Blueprint tower(int w, int h) {
        Blueprint b = room(new Blueprint("tower"), w, w, h);
        door(b, w / 2, 0);
        for (int y = 4; y < h; y += 4) {
            b.set(w / 2, y, w - 1, Role.WINDOW);
            b.set(0, y, w / 2, Role.WINDOW);
            b.set(w - 1, y, w / 2, Role.WINDOW);
            b.set(1, y, 1, Role.LIGHT);
        }
        b.fill(0, h + 1, 0, w - 1, h + 1, w - 1, Role.FLOOR); // lookout floor
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < w; z++) {
                boolean edge = x == 0 || z == 0 || x == w - 1 || z == w - 1;
                if (edge && (x + z) % 2 == 0) b.set(x, h + 2, z, Role.ACCENT);
            }
        }
        return b;
    }

    public static Blueprint wall(int length, int h) {
        Blueprint b = new Blueprint("wall");
        b.fill(0, 0, 0, length - 1, h - 1, 0, Role.WALL);
        for (int x = 0; x < length; x += 2) b.set(x, h, 0, Role.ACCENT);
        for (int x = 0; x < length; x += 8) b.fill(x, 0, 0, x, h - 1, 0, Role.FRAME);
        return b;
    }

    /** Bridge pointing away from the viewer (+z), 3 wide with railings. */
    public static Blueprint bridge(int length) {
        Blueprint b = new Blueprint("bridge");
        b.fill(0, 0, 0, 2, 0, length - 1, Role.FLOOR);
        for (int z = 0; z < length; z++) {
            b.set(0, 1, z, z % 4 == 0 ? Role.FRAME : Role.ACCENT);
            b.set(2, 1, z, z % 4 == 0 ? Role.FRAME : Role.ACCENT);
            b.set(1, 1, z, Role.CLEAR);
            b.set(1, 2, z, Role.CLEAR);
        }
        for (int z = 0; z < length; z += 8) {
            b.set(0, 2, z, Role.LIGHT);
            b.set(2, 2, z, Role.LIGHT);
        }
        return b;
    }

    public static Blueprint pyramid(int base) {
        if (base % 2 == 0) base++;
        Blueprint b = new Blueprint("pyramid");
        for (int layer = 0; layer * 2 < base; layer++) {
            int lo = layer, hi = base - 1 - layer;
            b.fill(lo, layer, lo, hi, layer, hi, Role.WALL);
        }
        b.set(base / 2, base / 2 + 1, base / 2, Role.LIGHT);
        return b;
    }

    public static Blueprint platform(int size) {
        Blueprint b = new Blueprint("platform");
        b.fill(0, 0, 0, size - 1, 0, size - 1, Role.FLOOR);
        b.fill(0, 1, 0, size - 1, 2, size - 1, Role.CLEAR);
        b.set(0, 1, 0, Role.LIGHT);
        b.set(size - 1, 1, size - 1, Role.LIGHT);
        return b;
    }

    public static Blueprint pillar(int h) {
        Blueprint b = new Blueprint("pillar");
        b.fill(0, 0, 0, 0, h - 1, 0, Role.WALL);
        b.set(0, h, 0, Role.LIGHT);
        return b;
    }

    public static Blueprint fort(int w, int h) {
        if (w % 2 == 0) w++;
        Blueprint b = new Blueprint("fort");
        for (int y = 0; y < h; y++) {
            for (int i = 0; i < w; i++) {
                b.set(i, y, 0, Role.WALL);
                b.set(i, y, w - 1, Role.WALL);
                b.set(0, y, i, Role.WALL);
                b.set(w - 1, y, i, Role.WALL);
            }
        }
        // corner towers one block taller
        for (int[] c : new int[][]{{0, 0}, {w - 1, 0}, {0, w - 1}, {w - 1, w - 1}}) {
            b.fill(c[0], 0, c[1], c[0], h + 1, c[1], Role.FRAME);
            b.set(c[0], h + 2, c[1], Role.LIGHT);
        }
        // crenellations
        for (int i = 1; i < w - 1; i += 2) {
            b.set(i, h, 0, Role.ACCENT);
            b.set(i, h, w - 1, Role.ACCENT);
            b.set(0, h, i, Role.ACCENT);
            b.set(w - 1, h, i, Role.ACCENT);
        }
        // gate
        b.fill(w / 2 - 1, 0, 0, w / 2 + 1, 2, 0, Role.CLEAR);
        b.fill(1, 0, 1, w - 2, 2, w - 2, Role.CLEAR);
        return b;
    }

    public static Blueprint igloo(int size) {
        if (size % 2 == 0) size++;
        Blueprint b = new Blueprint("igloo");
        double r = size / 2.0;
        double cx = r - 0.5, cz = r - 0.5;
        int top = (int) Math.ceil(r);
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                for (int y = 0; y <= top; y++) {
                    double dist = Math.sqrt((x - cx) * (x - cx) + (y * 1.2) * (y * 1.2) + (z - cz) * (z - cz));
                    if (dist <= r && dist > r - 1.2) b.set(x, y, z, y == 0 ? Role.FLOOR : Role.WALL);
                    else if (dist <= r - 1.2) b.set(x, y, z, y == 0 ? Role.FLOOR : Role.CLEAR);
                }
            }
        }
        // the door goes into the first wall block on the front center line
        for (int z = 0; z < size / 2; z++) {
            if (b.get(size / 2, 1, z) == Role.WALL) {
                b.set(size / 2, 1, z, Role.DOOR);
                b.set(size / 2, 2, z, Role.CLEAR);
                break;
            }
        }
        b.set(size / 2, 1, size / 2, Role.LIGHT);
        return b;
    }

    /** A chunky blocky player statue, scale 1-3 (8, 16 or 24 blocks tall). Built bottom-up, legs first. */
    public static Blueprint statue(int scale) {
        Blueprint b = new Blueprint("statue");
        int u = scale;
        b.fill(u, 0, 0, 5 * u - 1, 3 * u - 1, u - 1, Role.FRAME);          // legs
        b.fill(u, 3 * u, 0, 5 * u - 1, 6 * u - 1, u - 1, Role.WALL);       // body
        b.fill(0, 3 * u, 0, u - 1, 6 * u - 1, u - 1, Role.ACCENT);         // left arm
        b.fill(5 * u, 3 * u, 0, 6 * u - 1, 6 * u - 1, u - 1, Role.ACCENT); // right arm
        b.fill(u, 6 * u, 0, 5 * u - 1, 8 * u - 1, u - 1, Role.ROOF);       // head
        b.fill(2 * u - 1, 7 * u, 0, 2 * u - 1, 7 * u, 0, Role.ACCENT);     // eyes
        b.fill(4 * u, 7 * u, 0, 4 * u, 7 * u, 0, Role.ACCENT);
        return b;
    }
}
