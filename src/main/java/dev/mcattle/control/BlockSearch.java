package dev.mcattle.control;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Incremental search for blocks around a point, a few chunk sections per tick. Sections whose palette has no
 * matching state are skipped without touching their blocks, so scanning for rare ores is cheap.
 */
public final class BlockSearch {
    private final ServerWorld world;
    private final BlockPos center;
    private final int radius;
    private final int minY, maxY;
    private final Predicate<BlockState> statePredicate;
    private final BiPredicate<BlockPos, BlockState> positionPredicate;
    private final int wanted;
    private final Deque<long[]> sections = new ArrayDeque<>(); // {chunkX, chunkZ, sectionIndex}
    private final List<BlockPos> results = new ArrayList<>();
    private boolean done;

    /**
     * @param statePredicate    cheap test on the block state (also used for palette skipping)
     * @param positionPredicate extra test for candidates (e.g. "exposed to air"), may be null
     * @param wanted            stop early once this many matches were found in the closest rings
     */
    public BlockSearch(ServerWorld world, BlockPos center, int radius, int verticalRadius, Predicate<BlockState> statePredicate,
                       BiPredicate<BlockPos, BlockState> positionPredicate, int wanted) {
        this.world = world;
        this.center = center.toImmutable();
        this.radius = radius;
        this.minY = Math.max(world.getBottomY(), center.getY() - verticalRadius);
        this.maxY = Math.min(world.getTopY() - 1, center.getY() + verticalRadius);
        this.statePredicate = statePredicate;
        this.positionPredicate = positionPredicate == null ? (p, s) -> true : positionPredicate;
        this.wanted = wanted;

        int cx = center.getX() >> 4, cz = center.getZ() >> 4, cr = (radius >> 4) + 1;
        List<long[]> list = new ArrayList<>();
        int bottomSection = world.getBottomY() >> 4;
        for (int x = cx - cr; x <= cx + cr; x++) {
            for (int z = cz - cr; z <= cz + cr; z++) {
                for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
                    list.add(new long[]{x, z, sy - bottomSection, sy});
                }
            }
        }
        list.sort(Comparator.comparingDouble(a -> {
            double dx = (a[0] << 4) + 8 - center.getX();
            double dy = (a[3] << 4) + 8 - center.getY();
            double dz = (a[1] << 4) + 8 - center.getZ();
            return dx * dx + dy * dy * 1.5 + dz * dz;
        }));
        sections.addAll(list);
    }

    public boolean isDone() {
        return done;
    }

    /** Processes up to {@code budget} sections. Returns true when finished. */
    public boolean step(int budget) {
        if (done) return true;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int i = 0; i < budget && !sections.isEmpty(); i++) {
            long[] s = sections.poll();
            int chunkX = (int) s[0], chunkZ = (int) s[1], index = (int) s[2];
            Chunk chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            ChunkSection[] array = chunk.getSectionArray();
            if (index < 0 || index >= array.length) continue;
            ChunkSection section = array[index];
            if (section == null || section.isEmpty() || !section.getBlockStateContainer().hasAny(statePredicate)) continue;
            int baseY = (int) s[3] << 4;
            for (int y = 0; y < 16; y++) {
                int wy = baseY + y;
                if (wy < minY || wy > maxY) continue;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!statePredicate.test(state)) continue;
                        pos.set((chunkX << 4) + x, wy, (chunkZ << 4) + z);
                        if (pos.getSquaredDistance(center) > (double) radius * radius) continue;
                        if (positionPredicate.test(pos, state)) results.add(pos.toImmutable());
                    }
                }
            }
            // sections are processed nearest-first, so once enough matches exist we can stop
            if (results.size() >= wanted && sections.size() > 0) {
                long[] next = sections.peek();
                double nd = Math.sqrt(sq((next[0] << 4) + 8 - center.getX()) + sq((next[1] << 4) + 8 - center.getZ()));
                double farthest = results.stream().mapToDouble(p -> Math.sqrt(p.getSquaredDistance(center))).max().orElse(0);
                if (nd - 12 > farthest) sections.clear();
            }
        }
        if (sections.isEmpty()) {
            done = true;
            results.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(center)));
        }
        return done;
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Matches sorted by distance (only complete after {@link #isDone()}). */
    public List<BlockPos> results() {
        return results;
    }

    /** Runs the whole search immediately; use only for small radii. */
    public List<BlockPos> runNow() {
        while (!step(64)) {
            // keep going
        }
        return results;
    }

    /** Visible to a player: at least one neighbor is air, water or see-through. */
    public static boolean isExposed(ServerWorld world, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            BlockState s = world.getBlockState(n);
            if (!s.isOpaqueFullCube(world, n)) return true;
        }
        return false;
    }
}
