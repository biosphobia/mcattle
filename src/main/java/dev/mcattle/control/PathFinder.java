package dev.mcattle.control;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Player-style A* over block positions (a node is where the feet are). Besides walking it knows jumping up one
 * block, dropping down, swimming, climbing ladders, digging through natural blocks, pillaring and bridging,
 * each with a cost that reflects how long a player would take. The search is incremental: call
 * {@link #step(int)} each tick with a node budget so a long search never lags the server.
 */
public final class PathFinder {
    public enum Move {START, WALK, DIAGONAL, STEP_UP, DROP, SWIM, CLIMB_UP, CLIMB_DOWN, PILLAR, BRIDGE, DIG_DOWN, BREAK_WALK, BREAK_STEP_UP}

    public enum Status {SEARCHING, FOUND, PARTIAL, FAILED}

    /** One step of a finished path: the node to reach, how, which blocks must be broken first and where to place one. */
    public record Step(BlockPos pos, Move move, List<BlockPos> toBreak, @Nullable BlockPos placeAt) {
    }

    public interface Goal {
        boolean reached(BlockPos feet);

        /** Optimistic remaining cost estimate. */
        double heuristic(BlockPos feet);
    }

    /** What the searching NPC is able/allowed to do. */
    public static final class Abilities {
        public Predicate<BlockPos> mayBreak = p -> false;
        /** Estimated ticks to break a block (infinite if impossible). */
        public java.util.function.ToDoubleBiFunction<BlockPos, BlockState> breakTicks = (p, s) -> Double.POSITIVE_INFINITY;
        public int scaffoldBlocks;
        public int maxDrop = 3;
        public boolean allowSwim = true;
        public Set<Long> avoid = Collections.emptySet();
    }

    private static final class Node implements Comparable<Node> {
        final BlockPos pos;
        double g, f;
        Node parent;
        Move move = Move.START;
        List<BlockPos> toBreak = List.of();
        BlockPos placeAt;
        int scaffoldUsed;
        boolean closed;

        Node(BlockPos pos) {
            this.pos = pos;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    private final ServerWorld world;
    private final Goal goal;
    private final Abilities abilities;
    private final int maxNodes;
    private final double heuristicWeight;
    private final Map<Long, Node> nodes = new HashMap<>();
    private final Map<Long, BlockState> stateCache = new HashMap<>();
    private final PriorityQueue<Node> open = new PriorityQueue<>();
    private Node best;
    private double bestH;
    private Node found;
    private int expanded;
    private Status status = Status.SEARCHING;

    public PathFinder(ServerWorld world, BlockPos start, Goal goal, Abilities abilities, int maxNodes) {
        this.world = world;
        this.goal = goal;
        this.abilities = abilities;
        this.maxNodes = maxNodes;
        this.heuristicWeight = 1.4;
        Node s = new Node(start.toImmutable());
        s.g = 0;
        s.f = goal.heuristic(start);
        nodes.put(start.asLong(), s);
        open.add(s);
        best = s;
        bestH = goal.heuristic(start);
        if (goal.reached(start)) {
            found = s;
            status = Status.FOUND;
        }
    }

    public Status status() {
        return status;
    }

    /** Runs up to {@code budget} node expansions. */
    public Status step(int budget) {
        if (status != Status.SEARCHING) return status;
        for (int i = 0; i < budget; i++) {
            Node cur = open.poll();
            if (cur == null || expanded >= maxNodes) {
                status = (best != null && best.parent != null) ? Status.PARTIAL : Status.FAILED;
                return status;
            }
            if (cur.closed) continue;
            cur.closed = true;
            expanded++;
            if (goal.reached(cur.pos)) {
                found = cur;
                status = Status.FOUND;
                return status;
            }
            expand(cur);
        }
        return status;
    }

    /** The finished path (goal or best partial), without the start node. */
    public List<Step> path() {
        Node end = found != null ? found : best;
        List<Step> steps = new ArrayList<>();
        for (Node n = end; n != null && n.parent != null; n = n.parent) {
            steps.add(new Step(n.pos, n.move, n.toBreak, n.placeAt));
        }
        Collections.reverse(steps);
        return steps;
    }

    // ---------------------------------------------------------------- world queries (cached per search)

    private BlockState state(BlockPos pos) {
        return stateCache.computeIfAbsent(pos.asLong(), k -> WorldRules.isLoaded(world, pos)
                ? world.getBlockState(pos) : net.minecraft.block.Blocks.BEDROCK.getDefaultState());
    }

    private boolean passable(BlockPos p) {
        return WorldRules.isPassable(world, p, state(p));
    }

    private boolean standable(BlockPos p) {
        return WorldRules.isStandable(world, p, state(p));
    }

    private boolean water(BlockPos p) {
        return WorldRules.isWater(state(p));
    }

    private boolean climbable(BlockPos p) {
        return WorldRules.isClimbable(state(p));
    }

    private boolean danger(BlockPos p) {
        return WorldRules.isDangerous(state(p));
    }

    /** A node where an NPC can stay: body fits and there is something holding it up (or it floats/climbs). */
    private boolean canStand(BlockPos feet) {
        if (!passable(feet) || !passable(feet.up())) return false;
        if (danger(feet.down())) return false;
        return standable(feet.down()) || water(feet) || climbable(feet);
    }

    /** Cost to break a block on the way, or infinity if not allowed. */
    private double breakCost(BlockPos p) {
        BlockState s = state(p);
        if (WorldRules.isPassable(world, p, s)) return 0;
        if (!abilities.mayBreak.test(p)) return Double.POSITIVE_INFINITY;
        if (WorldRules.exposesFluid(world, p) || WorldRules.hasFallingAbove(world, p)) return Double.POSITIVE_INFINITY;
        double ticks = abilities.breakTicks.applyAsDouble(p, s);
        if (Double.isInfinite(ticks) || ticks > 20 * 15) return Double.POSITIVE_INFINITY;
        return 1.5 + ticks / 6.0;
    }

    private double hazardCost(BlockPos feet) {
        double c = 0;
        for (Direction d : Direction.Type.HORIZONTAL) {
            if (WorldRules.isLava(state(feet.offset(d))) || WorldRules.isLava(state(feet.down().offset(d)))) c += 8;
        }
        if (water(feet.up())) c += 1.5; // head under water: breath
        if (abilities.avoid.contains(feet.asLong())) c += 25;
        return c;
    }

    // ---------------------------------------------------------------- expansion

    private void expand(Node cur) {
        BlockPos p = cur.pos;
        boolean inWater = water(p);
        boolean onClimbable = climbable(p);
        boolean grounded = standable(p.down());

        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos q = p.offset(d);
            boolean feetFree = passable(q), headFree = passable(q.up());
            if (feetFree && headFree) {
                if (danger(q.down()) && !water(q)) {
                    // never walk onto magma/campfire etc.
                } else if (standable(q.down()) || water(q) || climbable(q)) {
                    add(cur, q, water(q) ? Move.SWIM : Move.WALK, (water(q) ? 2.0 : 1.0), List.of(), null, 0);
                } else {
                    // nothing below: fall, or bridge across
                    boolean dropped = false;
                    for (int k = 1; k <= 24; k++) {
                        BlockPos r = q.down(k);
                        if (!passable(r)) break;
                        if (water(r)) {
                            add(cur, r, Move.DROP, 1.0 + k * 0.4, List.of(), null, 0);
                            dropped = true;
                            break;
                        }
                        if (standable(r.down()) && !danger(r.down())) {
                            if (k <= abilities.maxDrop) {
                                add(cur, r, Move.DROP, 1.0 + k * 0.5, List.of(), null, 0);
                                dropped = true;
                            }
                            break;
                        }
                        if (WorldRules.isLava(state(r))) break;
                    }
                    if (grounded && !inWater && cur.scaffoldUsed < abilities.scaffoldBlocks
                            && state(q.down()).isReplaceable() && !WorldRules.isLava(state(q.down()))) {
                        add(cur, q, Move.BRIDGE, dropped ? 6.0 : 3.5, List.of(), q.down(), 1);
                    }
                }
            } else {
                // something in the way at body height
                BlockPos up = q.up();
                boolean qSolidTop = standable(q);
                boolean canJump = grounded || inWater || onClimbable;
                if (canJump && qSolidTop && passable(up) && passable(up.up()) && passable(p.up(2))) {
                    add(cur, up, Move.STEP_UP, 2.0, List.of(), null, 0);
                } else if (grounded && qSolidTop) {
                    // dig a step up: q stays as the floor, clear the space above it and our own headroom
                    double c = breakCost(p.up(2)) + breakCost(up.up()) + breakCost(up);
                    if (!Double.isInfinite(c)) {
                        add(cur, up, Move.BREAK_STEP_UP, 2.0 + c, breakList(p.up(2), up.up(), up), null, 0);
                    }
                }
                if (standable(q.down()) || water(q)) {
                    // dig straight through at the same level
                    double c = breakCost(q.up()) + breakCost(q);
                    if (!Double.isInfinite(c)) {
                        add(cur, q, Move.BREAK_WALK, 1.0 + c, breakList(q.up(), q), null, 0);
                    }
                }
            }
        }

        // diagonals: only plain walking with both corners open (no corner cutting through walls)
        if (grounded && !inWater) {
            for (int[] dd : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                BlockPos q = p.add(dd[0], 0, dd[1]);
                BlockPos a = p.add(dd[0], 0, 0), b = p.add(0, 0, dd[1]);
                if (passable(a) && passable(a.up()) && passable(b) && passable(b.up())
                        && passable(q) && passable(q.up()) && standable(q.down()) && !danger(q.down())) {
                    add(cur, q, Move.DIAGONAL, 1.414, List.of(), null, 0);
                }
            }
        }

        // vertical moves
        if (inWater || onClimbable) {
            if (passable(p.up()) && passable(p.up(2))) {
                add(cur, p.up(), onClimbable ? Move.CLIMB_UP : Move.SWIM, 2.0, List.of(), null, 0);
            }
            if (passable(p.down()) && (water(p.down()) || climbable(p.down()))) {
                add(cur, p.down(), onClimbable ? Move.CLIMB_DOWN : Move.SWIM, 2.0, List.of(), null, 0);
            }
        }
        if (grounded && !inWater) {
            // dig straight down (only onto something solid, never into a cave or lava)
            BlockPos below = p.down();
            if (standable(below.down()) && !danger(below.down()) && !WorldRules.isLava(state(below.down(2)))) {
                double c = breakCost(below);
                if (!Double.isInfinite(c) && c > 0) {
                    add(cur, below, Move.DIG_DOWN, 1.0 + c, List.of(below), null, 0);
                }
            }
            // pillar up: jump and place a block under ourselves
            if (cur.scaffoldUsed < abilities.scaffoldBlocks) {
                double c = breakCost(p.up(2));
                if (!Double.isInfinite(c)) {
                    add(cur, p.up(), Move.PILLAR, 4.0 + c, c > 0 ? List.of(p.up(2)) : List.of(), p, 1);
                }
            }
        }
    }

    private static List<BlockPos> breakList(BlockPos... positions) {
        List<BlockPos> list = new ArrayList<>(positions.length);
        for (BlockPos p : positions) list.add(p);
        return list;
    }

    private void add(Node from, BlockPos to, Move move, double cost, List<BlockPos> toBreak, @Nullable BlockPos placeAt, int scaffold) {
        // only keep blocks that actually need breaking
        List<BlockPos> breaks = new ArrayList<>();
        for (BlockPos b : toBreak) if (!passable(b)) breaks.add(b);
        if (move != Move.PILLAR && move != Move.BRIDGE && move != Move.DIG_DOWN && !canStand(to) && breaks.isEmpty()) return;
        double g = from.g + cost + hazardCost(to);
        long key = to.asLong();
        Node n = nodes.get(key);
        if (n == null) {
            n = new Node(to.toImmutable());
            nodes.put(key, n);
        } else if (n.closed || g >= n.g) {
            return;
        }
        n.g = g;
        double h = goal.heuristic(to);
        n.f = g + h * heuristicWeight;
        n.parent = from;
        n.move = move;
        n.toBreak = breaks;
        n.placeAt = placeAt == null ? null : placeAt.toImmutable();
        n.scaffoldUsed = from.scaffoldUsed + scaffold;
        open.add(n);
        if (h < bestH) {
            bestH = h;
            best = n;
        }
    }

    // ---------------------------------------------------------------- goals

    /** Stand within {@code range} blocks of a position. */
    public static Goal near(BlockPos target, double range) {
        return new Goal() {
            @Override
            public boolean reached(BlockPos feet) {
                return feet.getSquaredDistance(target) <= range * range;
            }

            @Override
            public double heuristic(BlockPos feet) {
                return Math.max(0, Math.sqrt(feet.getSquaredDistance(target)) - range);
            }
        };
    }

    /** Stand somewhere the block is within arm's reach, without standing inside it. */
    public static Goal reach(BlockPos target, double reach) {
        return new Goal() {
            @Override
            public boolean reached(BlockPos feet) {
                if (feet.equals(target) || feet.up().equals(target)) return false;
                double dx = feet.getX() + 0.5 - (target.getX() + 0.5);
                double dy = feet.getY() + 1.62 - (target.getY() + 0.5);
                double dz = feet.getZ() + 0.5 - (target.getZ() + 0.5);
                return dx * dx + dy * dy + dz * dz <= reach * reach;
            }

            @Override
            public double heuristic(BlockPos feet) {
                return Math.max(0, Math.sqrt(feet.getSquaredDistance(target)) - reach + 1);
            }
        };
    }

    /** Get at least {@code distance} away from a position (fleeing). */
    public static Goal away(BlockPos from, double distance) {
        return new Goal() {
            @Override
            public boolean reached(BlockPos feet) {
                return feet.getSquaredDistance(from) >= distance * distance;
            }

            @Override
            public double heuristic(BlockPos feet) {
                return Math.max(0, distance - Math.sqrt(feet.getSquaredDistance(from)));
            }
        };
    }
}
