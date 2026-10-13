package dev.mcattle.task;

import dev.mcattle.control.ItemQuery;
import dev.mcattle.control.ItemResolver;
import dev.mcattle.control.Tags;
import dev.mcattle.control.WorldRules;
import dev.mcattle.core.build.Blueprint;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.TorchBlock;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A structure being built in the world. Shared between NPCs: each claims the next cell it can work on, so
 * several builders split the work naturally ("everyone build a house").
 */
public final class BuildJob {
    public enum Kind {PLACE, CLEAR}

    public static final class Cell {
        public final BlockPos pos;
        public final Blueprint.Role role;
        public final Kind kind;
        @Nullable public final Block block;
        public final boolean optional;
        boolean done;
        boolean skipped;
        int attempts;
        @Nullable UUID claimedBy;

        Cell(BlockPos pos, Blueprint.Role role, Kind kind, @Nullable Block block, boolean optional) {
            this.pos = pos;
            this.role = role;
            this.kind = kind;
            this.block = block;
            this.optional = optional;
        }

        public ItemQuery item() {
            return block == null ? null : ItemQuery.of(block.asItem());
        }
    }

    private final String name;
    private final List<Cell> cells = new ArrayList<>();
    private final BlockPos origin;
    private int total;

    private BuildJob(String name, BlockPos origin) {
        this.name = name;
        this.origin = origin;
    }

    public String name() {
        return name;
    }

    public BlockPos origin() {
        return origin;
    }

    /**
     * Lays a blueprint out in the world in front of {@code anchor}, facing it.
     *
     * @param forward direction pointing away from the person who asked (the door faces them)
     */
    public static BuildJob create(ServerWorld world, Blueprint blueprint, String material, BlockPos anchor, Direction forward, int gap) {
        Direction right = forward.rotateYClockwise();
        int w = blueprint.width(), d = blueprint.depth();
        BlockPos corner = anchor.offset(forward, gap).offset(right, -(w / 2));
        // ground level: median surface height over the footprint
        List<Integer> heights = new ArrayList<>();
        for (int x = 0; x < w; x += Math.max(1, w / 5)) {
            for (int z = 0; z < d; z += Math.max(1, d / 5)) {
                BlockPos col = corner.offset(right, x).offset(forward, z);
                heights.add(world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, col).getY());
            }
        }
        heights.sort(Integer::compare);
        int baseY = heights.isEmpty() ? anchor.getY() : heights.get(heights.size() / 2);
        if (Math.abs(baseY - anchor.getY()) > 6) baseY = anchor.getY();
        BlockPos origin = new BlockPos(corner.getX(), baseY, corner.getZ());

        Map<Blueprint.Role, Block> palette = palette(blueprint.name(), material);
        BuildJob job = new BuildJob(blueprint.name(), origin);
        Map<Long, Cell> byPos = new LinkedHashMap<>();
        for (Blueprint.Cell c : blueprint.cells()) {
            BlockPos pos = origin.offset(right, c.x()).up(c.y()).offset(forward, c.z());
            Cell cell;
            if (c.role() == Blueprint.Role.CLEAR) {
                cell = new Cell(pos, c.role(), Kind.CLEAR, null, false);
            } else {
                boolean optional = c.role() == Blueprint.Role.WINDOW || c.role() == Blueprint.Role.LIGHT || c.role() == Blueprint.Role.DOOR;
                cell = new Cell(pos, c.role(), Kind.PLACE, palette.get(c.role()), optional);
            }
            byPos.put(pos.asLong(), cell);
        }
        // foundation under floor cells that would float
        Block foundation = palette.get(Blueprint.Role.FLOOR);
        for (Blueprint.Cell c : blueprint.cells()) {
            if (c.y() != 0 || c.role() == Blueprint.Role.CLEAR) continue;
            BlockPos pos = origin.offset(right, c.x()).offset(forward, c.z());
            for (int depth = 1; depth <= 4; depth++) {
                BlockPos below = pos.down(depth);
                BlockState s = world.getBlockState(below);
                if (!s.isReplaceable() && s.getFluidState().isEmpty()) break;
                byPos.putIfAbsent(below.asLong(), new Cell(below, Blueprint.Role.FLOOR, Kind.PLACE, foundation, false));
            }
        }
        job.cells.addAll(byPos.values());
        job.total = job.cells.size();
        job.refresh(world);
        return job;
    }

    /** Excavation: every cell is cleared. */
    public static BuildJob excavation(ServerWorld world, String name, List<BlockPos> positions) {
        BuildJob job = new BuildJob(name, positions.isEmpty() ? BlockPos.ORIGIN : positions.get(0));
        for (BlockPos p : positions) job.cells.add(new Cell(p.toImmutable(), Blueprint.Role.CLEAR, Kind.CLEAR, null, false));
        job.total = job.cells.size();
        job.refresh(world);
        return job;
    }

    static Map<Blueprint.Role, Block> palette(String structure, String material) {
        Block main = ItemResolver.resolveBlock(material);
        if (main == null || !main.getDefaultState().isFullCube(net.minecraft.world.EmptyBlockView.INSTANCE, BlockPos.ORIGIN)) {
            main = switch (structure) {
                case "igloo" -> Blocks.SNOW_BLOCK;
                case "pyramid" -> Blocks.SANDSTONE;
                case "fort", "wall", "tower" -> Blocks.COBBLESTONE;
                case "statue" -> Blocks.COBBLESTONE;
                default -> Blocks.OAK_PLANKS;
            };
        }
        String id = Registries.BLOCK.getId(main).getPath();
        Block frame = main, roof = main, floor = main, accent = main;
        Block door = Blocks.OAK_DOOR;
        if (id.endsWith("_planks")) {
            String wood = id.substring(0, id.length() - "_planks".length());
            frame = block(wood + "_log", wood.equals("crimson") || wood.equals("warped") ? wood + "_stem" : null, main);
            accent = frame;
            door = block(wood + "_door", null, Blocks.OAK_DOOR);
        } else if (main.getDefaultState().isIn(Tags.LOGS)) {
            String wood = id.replace("stripped_", "").replace("_log", "").replace("_wood", "");
            roof = block(wood + "_planks", null, Blocks.OAK_PLANKS);
            floor = roof;
            door = block(wood + "_door", null, Blocks.OAK_DOOR);
        } else if (main == Blocks.COBBLESTONE || main == Blocks.STONE || main == Blocks.STONE_BRICKS || main == Blocks.COBBLED_DEEPSLATE) {
            frame = Blocks.COBBLESTONE;
            accent = main == Blocks.COBBLESTONE ? Blocks.OAK_PLANKS : Blocks.COBBLESTONE;
        }
        if (structure.equals("statue")) {
            frame = main;
            roof = main;
            accent = main == Blocks.COBBLESTONE ? Blocks.OAK_PLANKS : Blocks.COBBLESTONE;
        }
        Map<Blueprint.Role, Block> p = new HashMap<>();
        p.put(Blueprint.Role.WALL, main);
        p.put(Blueprint.Role.FRAME, frame);
        p.put(Blueprint.Role.ROOF, roof);
        p.put(Blueprint.Role.FLOOR, floor);
        p.put(Blueprint.Role.ACCENT, accent);
        p.put(Blueprint.Role.WINDOW, Blocks.GLASS);
        p.put(Blueprint.Role.DOOR, door);
        p.put(Blueprint.Role.LIGHT, Blocks.TORCH);
        return p;
    }

    private static Block block(String path, @Nullable String alt, Block fallback) {
        Identifier id = new Identifier("minecraft", path);
        if (Registries.BLOCK.containsId(id)) return Registries.BLOCK.get(id);
        if (alt != null && Registries.BLOCK.containsId(new Identifier("minecraft", alt))) return Registries.BLOCK.get(new Identifier("minecraft", alt));
        return fallback;
    }

    /** Is the cell already in its finished state? */
    public static boolean satisfied(ServerWorld world, Cell cell) {
        BlockState s = world.getBlockState(cell.pos);
        if (cell.kind == Kind.CLEAR) return s.isAir() || (s.isReplaceable() && s.getFluidState().isEmpty()) || s.getBlock() instanceof DoorBlock;
        if (cell.block == null) return true;
        if (cell.role == Blueprint.Role.DOOR) return s.getBlock() instanceof DoorBlock;
        if (cell.role == Blueprint.Role.LIGHT) return s.getBlock() instanceof TorchBlock || s.getBlock() instanceof WallTorchBlock;
        return s.isOf(cell.block);
    }

    public void refresh(ServerWorld world) {
        for (Cell c : cells) if (!c.done && !c.skipped && satisfied(world, c)) c.done = true;
    }

    public boolean isDone() {
        for (Cell c : cells) if (!c.done && !c.skipped) return false;
        return true;
    }

    public int remaining() {
        int n = 0;
        for (Cell c : cells) if (!c.done && !c.skipped) n++;
        return n;
    }

    public int total() {
        return total;
    }

    /** How many more of this block the job still needs (for gathering in batches). */
    public int remainingNeed(Block block) {
        int n = 0;
        for (Cell c : cells) if (!c.done && !c.skipped && c.kind == Kind.PLACE && c.block == block) n++;
        return n;
    }

    /**
     * Picks the next cell for a builder: lowest layer first, obstacles before blocks, nearest first, only cells
     * that can be attached to something (unless assisted placement is on and nothing else is left).
     */
    @Nullable
    public synchronized Cell claim(ServerWorld world, UUID builder, BlockPos near, boolean assisted) {
        refresh(world);
        Cell current = null;
        for (Cell c : cells) if (builder.equals(c.claimedBy) && !c.done && !c.skipped) current = c;
        if (current != null) return current;
        int lowestPlace = Integer.MAX_VALUE;
        for (Cell c : cells) if (!c.done && !c.skipped && c.kind == Kind.PLACE) lowestPlace = Math.min(lowestPlace, c.pos.getY());
        final int lowest = lowestPlace;
        Comparator<Cell> order = Comparator
                .comparingInt((Cell c) -> c.kind == Kind.CLEAR && c.pos.getY() <= lowest + 3 ? -1000 : 0)
                .thenComparingInt(c -> c.pos.getY())
                .thenComparingInt(c -> c.optional ? 1 : 0)
                .thenComparingDouble(c -> c.pos.getSquaredDistance(near));
        List<Cell> open = new ArrayList<>();
        for (Cell c : cells) if (!c.done && !c.skipped && c.claimedBy == null) open.add(c);
        open.sort(order);
        for (Cell c : open) {
            if (c.kind == Kind.CLEAR || hasSupport(world, c.pos)) {
                c.claimedBy = builder;
                return c;
            }
        }
        if (assisted && !open.isEmpty()) {
            Cell c = open.get(0);
            c.claimedBy = builder;
            return c;
        }
        return null;
    }

    public synchronized void release(UUID builder) {
        for (Cell c : cells) if (builder.equals(c.claimedBy)) c.claimedBy = null;
    }

    public synchronized void finish(Cell c, boolean success) {
        c.claimedBy = null;
        if (success) c.done = true;
        else if (++c.attempts >= 3) c.skipped = true;
    }

    public synchronized void skip(Cell c) {
        c.claimedBy = null;
        c.skipped = true;
    }

    private static boolean hasSupport(ServerWorld world, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            if (WorldRules.isSupport(world, n, world.getBlockState(n))) return true;
        }
        return false;
    }

    public List<BlockPos> footprint() {
        List<BlockPos> list = new ArrayList<>();
        for (Cell c : cells) list.add(c.pos);
        return list;
    }

    public static Item itemFor(Block block) {
        Item item = block.asItem();
        return item instanceof BlockItem ? item : Items.AIR;
    }
}
