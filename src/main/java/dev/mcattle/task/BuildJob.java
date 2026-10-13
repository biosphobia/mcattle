package dev.mcattle.task;

import dev.mcattle.control.ItemQuery;
import dev.mcattle.control.ItemResolver;
import dev.mcattle.control.Tags;
import dev.mcattle.control.WorldRules;
import dev.mcattle.core.build.Blueprint;
import dev.mcattle.core.llm.DesignCompiler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
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
 * Something being built (or dug out) in the world, shared between NPCs: each builder claims the next cell it can
 * work on, so several NPCs split the work naturally. A job can start "pending" while its design is being made
 * by the language model; builders wait and then start as soon as the cells arrive.
 */
public final class BuildJob {
    public enum Kind {PLACE, CLEAR}

    public static final class Cell {
        public final BlockPos pos;
        public final Kind kind;
        /** Target state for PLACE cells (null for CLEAR). */
        @Nullable public final BlockState target;
        boolean done;
        boolean skipped;
        int attempts;
        @Nullable UUID claimedBy;

        Cell(BlockPos pos, Kind kind, @Nullable BlockState target) {
            this.pos = pos;
            this.kind = kind;
            this.target = target;
        }

        @Nullable
        public ItemQuery item() {
            return target == null ? null : ItemQuery.of(target.getBlock().asItem());
        }

        public Item itemType() {
            return target == null ? Items.AIR : target.getBlock().asItem();
        }
    }

    /** A cell in the design's local frame: x right, y up, z away from the viewer. Null state = air. */
    public record LocalCell(int x, int y, int z, @Nullable BlockState state) {
    }

    private final String name;
    private String summary = "";
    private final List<Cell> cells = new ArrayList<>();
    private final BlockPos anchor;
    private final Direction forward;
    private final boolean exactCorner;
    private BlockPos origin;
    private int total;
    private volatile boolean ready;
    private volatile boolean failed;

    private BuildJob(String name, BlockPos anchor, Direction forward, boolean exactCorner) {
        this.name = name;
        this.anchor = anchor.toImmutable();
        this.forward = forward.getAxis().isHorizontal() ? forward : Direction.SOUTH;
        this.exactCorner = exactCorner;
        this.origin = anchor;
    }

    public String name() {
        return name;
    }

    public String summary() {
        return summary;
    }

    public BlockPos origin() {
        return origin;
    }

    public BlockPos anchor() {
        return anchor;
    }

    public Direction forward() {
        return forward;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isFailed() {
        return failed;
    }

    public void markFailed() {
        failed = true;
    }

    /** A job whose cells will be supplied later by {@link #apply}. */
    public static BuildJob pending(String name, BlockPos anchor, Direction forward, boolean exactCorner) {
        return new BuildJob(name, anchor, forward, exactCorner);
    }

    // ------------------------------------------------------------------ layout

    /** Local direction (x right, z away) -> world, as a block-state transform: mirror then rotate. */
    static BlockState toWorld(BlockState local, Direction forward) {
        BlockRotation rotation = switch (forward) {
            case WEST -> BlockRotation.CLOCKWISE_90;
            case NORTH -> BlockRotation.CLOCKWISE_180;
            case EAST -> BlockRotation.COUNTERCLOCKWISE_90;
            default -> BlockRotation.NONE;
        };
        return local.mirror(BlockMirror.FRONT_BACK).rotate(rotation);
    }

    /** Places local cells in the world in front of the anchor (or at the exact corner) and makes the job ready. */
    public void apply(ServerWorld world, List<LocalCell> local, String summary) {
        this.summary = summary == null ? "" : summary;
        if (local.isEmpty()) {
            failed = true;
            return;
        }
        Direction right = forward.rotateYClockwise();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, minY = Integer.MAX_VALUE;
        for (LocalCell c : local) {
            minX = Math.min(minX, c.x());
            maxX = Math.max(maxX, c.x());
            minZ = Math.min(minZ, c.z());
            maxZ = Math.max(maxZ, c.z());
            minY = Math.min(minY, c.y());
        }
        int width = maxX - minX + 1, depth = maxZ - minZ + 1;
        BlockPos corner;
        int baseY;
        if (exactCorner) {
            corner = anchor;
            baseY = anchor.getY();
        } else {
            corner = anchor.offset(forward, 3).offset(right, -(width / 2));
            List<Integer> heights = new ArrayList<>();
            for (int x = 0; x < width; x += Math.max(1, width / 6)) {
                for (int z = 0; z < depth; z += Math.max(1, depth / 6)) {
                    BlockPos col = corner.offset(right, x).offset(forward, z);
                    heights.add(world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, col).getY());
                }
            }
            heights.sort(Integer::compare);
            baseY = heights.isEmpty() ? anchor.getY() : heights.get(heights.size() / 2);
            if (Math.abs(baseY - anchor.getY()) > 6) baseY = anchor.getY();
        }
        origin = new BlockPos(corner.getX(), baseY, corner.getZ());

        Map<Long, Cell> byPos = new LinkedHashMap<>();
        for (LocalCell c : local) {
            BlockPos pos = origin.offset(right, c.x() - minX).up(c.y()).offset(forward, c.z() - minZ);
            if (c.state() == null) {
                byPos.put(pos.asLong(), new Cell(pos, Kind.CLEAR, null));
                continue;
            }
            BlockState state = toWorld(c.state(), forward);
            if (isSecondHalf(state)) continue; // the item places the other half itself
            if (state.getBlock().asItem() == Items.AIR) continue; // fluids, fire, technical blocks
            byPos.put(pos.asLong(), new Cell(pos, Kind.PLACE, state));
        }
        // foundation under the lowest layer where it would float
        int lowest = minY;
        List<Cell> foundation = new ArrayList<>();
        for (Cell c : byPos.values()) {
            if (c.kind != Kind.PLACE || c.pos.getY() != origin.getY() + lowest) continue;
            BlockState fill = c.target.isFullCube(world, c.pos) ? c.target.getBlock().getDefaultState() : Blocks.DIRT.getDefaultState();
            for (int d = 1; d <= 4; d++) {
                BlockPos below = c.pos.down(d);
                if (byPos.containsKey(below.asLong())) break;
                BlockState s = world.getBlockState(below);
                if (!s.isReplaceable() && s.getFluidState().isEmpty()) break;
                foundation.add(new Cell(below, Kind.PLACE, fill));
            }
        }
        for (Cell f : foundation) byPos.putIfAbsent(f.pos.asLong(), f);
        synchronized (this) {
            cells.clear();
            cells.addAll(byPos.values());
            total = cells.size();
        }
        refresh(world);
        ready = true;
    }

    private static boolean isSecondHalf(BlockState state) {
        if (state.contains(Properties.DOUBLE_BLOCK_HALF) && state.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
        return state.contains(Properties.BED_PART) && state.get(Properties.BED_PART) == BedPart.HEAD;
    }

    /** Parses "minecraft:oak_stairs[facing=east]" (falls back to the plain block, or null if unknown). */
    @Nullable
    public static BlockState parseState(String spec) {
        if (spec == null || spec.isBlank()) return null;
        if (spec.equals(DesignCompiler.AIR)) return Blocks.AIR.getDefaultState();
        try {
            return BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), spec, false).blockState();
        } catch (Exception ignored) {
        }
        String plain = spec.contains("[") ? spec.substring(0, spec.indexOf('[')) : spec;
        Identifier id = Identifier.tryParse(plain);
        if (id != null && Registries.BLOCK.containsId(id)) return Registries.BLOCK.get(id).getDefaultState();
        return null;
    }

    /** Converts compiled design cells into local cells with block states. */
    public static List<LocalCell> fromDesign(List<DesignCompiler.Cell> compiled) {
        List<LocalCell> out = new ArrayList<>();
        Map<String, BlockState> cache = new HashMap<>();
        for (DesignCompiler.Cell c : compiled) {
            BlockState s = cache.computeIfAbsent(c.block(), BuildJob::parseState);
            if (s == null) continue;
            out.add(new LocalCell(c.x(), c.y(), c.z(), s.isAir() ? null : s));
        }
        return out;
    }

    // ------------------------------------------------------------------ presets (used when no language model is available)

    /** Cells of a built-in blueprint (used when no language model is available or a design fails). */
    public static List<LocalCell> presetCells(Blueprint blueprint, String material) {
        Map<Blueprint.Role, Block> palette = palette(blueprint.name(), material);
        List<LocalCell> local = new ArrayList<>();
        for (Blueprint.Cell c : blueprint.cells()) {
            if (c.role() == Blueprint.Role.CLEAR) {
                local.add(new LocalCell(c.x(), c.y(), c.z(), null));
                continue;
            }
            BlockState state = palette.get(c.role()).getDefaultState();
            if (c.role() == Blueprint.Role.DOOR && state.contains(Properties.HORIZONTAL_FACING)) {
                state = state.with(Properties.HORIZONTAL_FACING, Direction.NORTH); // faces the viewer
            }
            local.add(new LocalCell(c.x(), c.y(), c.z(), state));
        }
        return local;
    }

    /** Excavation: every cell is cleared (already in world coordinates). */
    public static BuildJob excavation(ServerWorld world, String name, List<BlockPos> positions) {
        BuildJob job = new BuildJob(name, positions.isEmpty() ? BlockPos.ORIGIN : positions.get(0), Direction.SOUTH, true);
        for (BlockPos p : positions) job.cells.add(new Cell(p.toImmutable(), Kind.CLEAR, null));
        job.total = job.cells.size();
        job.refresh(world);
        job.ready = true;
        return job;
    }

    static Map<Blueprint.Role, Block> palette(String structure, String material) {
        Block main = ItemResolver.resolveBlock(material);
        if (main == null || !main.getDefaultState().isFullCube(net.minecraft.world.EmptyBlockView.INSTANCE, BlockPos.ORIGIN)) {
            main = switch (structure) {
                case "igloo" -> Blocks.SNOW_BLOCK;
                case "pyramid" -> Blocks.SANDSTONE;
                case "fort", "wall", "tower", "statue" -> Blocks.COBBLESTONE;
                default -> Blocks.OAK_PLANKS;
            };
        }
        String id = Registries.BLOCK.getId(main).getPath();
        Block frame = main, roof = main, floor = main, accent = main;
        Block door = Blocks.OAK_DOOR;
        if (id.endsWith("_planks")) {
            String wood = id.substring(0, id.length() - "_planks".length());
            frame = block(wood + "_log", main);
            accent = frame;
            door = block(wood + "_door", Blocks.OAK_DOOR);
        } else if (main.getDefaultState().isIn(Tags.LOGS)) {
            String wood = id.replace("stripped_", "").replace("_log", "").replace("_wood", "");
            roof = block(wood + "_planks", Blocks.OAK_PLANKS);
            floor = roof;
            door = block(wood + "_door", Blocks.OAK_DOOR);
        } else if (main == Blocks.COBBLESTONE || main == Blocks.STONE || main == Blocks.STONE_BRICKS) {
            frame = Blocks.COBBLESTONE;
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

    private static Block block(String path, Block fallback) {
        Identifier id = new Identifier("minecraft", path);
        return Registries.BLOCK.containsId(id) ? Registries.BLOCK.get(id) : fallback;
    }

    // ------------------------------------------------------------------ progress

    /** Is the cell already in its finished state? Placed blocks count if the right block is there in any orientation. */
    public static boolean satisfied(ServerWorld world, Cell cell) {
        BlockState s = world.getBlockState(cell.pos);
        if (cell.kind == Kind.CLEAR) return s.isAir() || (s.isReplaceable() && s.getFluidState().isEmpty()) || s.getBlock() instanceof DoorBlock;
        if (cell.target == null) return true;
        return !s.isAir() && s.getBlock().asItem() == cell.target.getBlock().asItem();
    }

    public synchronized void refresh(ServerWorld world) {
        for (Cell c : cells) if (!c.done && !c.skipped && satisfied(world, c)) c.done = true;
    }

    public synchronized boolean isDone() {
        if (!ready) return false;
        for (Cell c : cells) if (!c.done && !c.skipped) return false;
        return true;
    }

    public synchronized int remaining() {
        int n = 0;
        for (Cell c : cells) if (!c.done && !c.skipped) n++;
        return n;
    }

    public int total() {
        return total;
    }

    /** How many more of this item the job still needs (for gathering in batches). */
    public synchronized int remainingNeed(Item item) {
        int n = 0;
        for (Cell c : cells) if (!c.done && !c.skipped && c.kind == Kind.PLACE && c.itemType() == item) n++;
        return n;
    }

    /** Distinct materials still needed, most-needed first: "oak_planks x40, glass x6". */
    public synchronized String materials(int max) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (Cell c : cells) if (!c.done && !c.skipped && c.kind == Kind.PLACE) counts.merge(c.itemType(), 1, Integer::sum);
        StringBuilder sb = new StringBuilder();
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(max).forEach(e -> {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Registries.ITEM.getId(e.getKey()).getPath()).append(" x").append(e.getValue());
        });
        return sb.toString();
    }

    /**
     * Picks the next cell for a builder: obstacles first, then lowest layer, nearest first, only cells that can be
     * attached to something (unless assisted placement is on and nothing else is left).
     */
    @Nullable
    public synchronized Cell claim(ServerWorld world, UUID builder, BlockPos near, boolean assisted) {
        if (!ready) return null;
        refresh(world);
        for (Cell c : cells) if (builder.equals(c.claimedBy) && !c.done && !c.skipped) return c;
        int lowestPlace = Integer.MAX_VALUE;
        for (Cell c : cells) if (!c.done && !c.skipped && c.kind == Kind.PLACE) lowestPlace = Math.min(lowestPlace, c.pos.getY());
        final int lowest = lowestPlace;
        Comparator<Cell> order = Comparator
                .comparingInt((Cell c) -> c.kind == Kind.CLEAR && c.pos.getY() <= lowest + 3 ? -1000 : 0)
                .thenComparingInt(c -> c.pos.getY())
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

    /** Skips every remaining cell that needs this item (it couldn't be obtained). */
    public synchronized void skipAll(Item item) {
        for (Cell c : cells) if (!c.done && c.kind == Kind.PLACE && c.itemType() == item) c.skipped = true;
    }

    private static boolean hasSupport(ServerWorld world, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            if (WorldRules.isSupport(world, n, world.getBlockState(n))) return true;
        }
        return false;
    }

    public synchronized List<BlockPos> footprint() {
        List<BlockPos> list = new ArrayList<>();
        for (Cell c : cells) list.add(c.pos);
        return list;
    }
}
