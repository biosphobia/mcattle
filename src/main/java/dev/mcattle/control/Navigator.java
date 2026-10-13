package dev.mcattle.control;

import dev.mcattle.npc.NpcPlayer;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Follows paths from {@link PathFinder}: steers, jumps, swims, opens doors, digs, pillars and bridges,
 * re-plans when knocked off course or stuck. Tasks call {@link #tick()} every tick while they want to move.
 */
public final class Navigator {
    public enum State {IDLE, SEARCHING, MOVING, ARRIVED, FAILED}

    private static final int SEARCH_BUDGET_PER_TICK = 700;

    private final NpcPlayer npc;
    private final MovementControl move;
    private final InteractionControl hands;
    private final InventoryControl inventory;

    @Nullable private PathFinder.Goal goal;
    @Nullable private PathFinder search;
    private List<PathFinder.Step> path = List.of();
    private int index;
    private State state = State.IDLE;
    private boolean partial;
    private int replans;
    private int stuckTicks;
    private double lastProgressDist = Double.MAX_VALUE;
    private int stepTicks;
    private boolean allowBreaking = true;
    private boolean naturalOnly = true;
    private boolean sprint = true;
    private int maxNodes = 8000;
    private final Set<Long> avoid = new HashSet<>();
    /** Blocks this navigator placed to get somewhere (pillars, bridges) so builders can clean them up. */
    private final List<BlockPos> placedScaffold = new ArrayList<>();
    @Nullable private Predicate<BlockPos> extraBreakable;

    public Navigator(NpcPlayer npc, MovementControl move, InteractionControl hands, InventoryControl inventory) {
        this.npc = npc;
        this.move = move;
        this.hands = hands;
        this.inventory = inventory;
    }

    public void setAllowBreaking(boolean allow) {
        this.allowBreaking = allow;
    }

    /** When false, any breakable non-precious block may be dug through (only for explicit player orders). */
    public void setNaturalOnly(boolean naturalOnly) {
        this.naturalOnly = naturalOnly;
    }

    public void setExtraBreakable(@Nullable Predicate<BlockPos> extra) {
        this.extraBreakable = extra;
    }

    public void setSprint(boolean sprint) {
        this.sprint = sprint;
    }

    public List<BlockPos> placedScaffold() {
        return placedScaffold;
    }

    public State state() {
        return state;
    }

    public boolean isPartial() {
        return partial;
    }

    /** Starts (or restarts) navigation toward a goal. */
    public void navigate(PathFinder.Goal goal) {
        this.goal = goal;
        this.replans = 0;
        this.avoid.clear();
        startSearch();
    }

    public void stop() {
        goal = null;
        search = null;
        path = List.of();
        state = State.IDLE;
        hands.cancelBreaking();
    }

    private void startSearch() {
        if (goal == null) return;
        PathFinder.Abilities abilities = new PathFinder.Abilities();
        ServerWorld world = npc.getServerWorld();
        abilities.mayBreak = pos -> {
            if (!allowBreaking) return false;
            BlockState s = world.getBlockState(pos);
            if (WorldRules.isPrecious(world, pos, s)) return false;
            if (extraBreakable != null && extraBreakable.test(pos)) return true;
            return !naturalOnly || WorldRules.isNatural(s);
        };
        abilities.breakTicks = (pos, s) -> hands.estimateBreakTicks(pos, s);
        abilities.scaffoldBlocks = Math.min(64, inventory.scaffoldCount());
        abilities.maxDrop = npc.getHealth() > 10 ? 3 : 2;
        abilities.avoid = new HashSet<>(avoid);
        search = new PathFinder(world, currentNode(), goal, abilities, maxNodes);
        state = State.SEARCHING;
        path = List.of();
        index = 0;
    }

    /** The block the NPC's feet occupy, accounting for slabs, soul sand and being mid-jump. */
    public BlockPos currentNode() {
        return BlockPos.ofFloored(npc.getX(), npc.getY() + 0.2, npc.getZ());
    }

    /** Advances searching or path following by one tick. */
    public State tick() {
        if (goal == null) return state;
        if (state == State.SEARCHING) {
            PathFinder.Status status = search.step(SEARCH_BUDGET_PER_TICK);
            switch (status) {
                case FOUND, PARTIAL -> {
                    path = search.path();
                    partial = status == PathFinder.Status.PARTIAL;
                    index = 0;
                    search = null;
                    stuckTicks = 0;
                    stepTicks = 0;
                    lastProgressDist = Double.MAX_VALUE;
                    if (path.isEmpty()) {
                        state = goal.reached(currentNode()) ? State.ARRIVED : State.FAILED;
                    } else {
                        state = State.MOVING;
                    }
                }
                case FAILED -> {
                    search = null;
                    state = State.FAILED;
                }
                default -> {
                }
            }
            return state;
        }
        if (state != State.MOVING) return state;

        if (index >= path.size()) {
            if (goal.reached(currentNode())) {
                state = State.ARRIVED;
            } else if (partial && replans++ < 8) {
                startSearch(); // walked to the frontier of a partial path: plan the next leg
            } else {
                state = goal.reached(currentNode()) ? State.ARRIVED : State.FAILED;
            }
            return state;
        }
        followStep(path.get(index));
        return state;
    }

    private void followStep(PathFinder.Step step) {
        ServerWorld world = npc.getServerWorld();
        BlockPos target = step.pos();
        BlockPos feet = currentNode();
        stepTicks++;

        // knocked far off the path -> replan
        if (feet.getSquaredDistance(target) > 3.5 * 3.5 && step.move() != PathFinder.Move.DROP) {
            replan(null);
            return;
        }

        // 1. clear the way
        for (BlockPos b : step.toBreak()) {
            BlockState s = world.getBlockState(b);
            if (!WorldRules.isPassable(world, b, s)) {
                move.lookAt(Vec3d.ofCenter(b));
                InteractionControl.Result r = hands.breakBlock(b);
                if (r == InteractionControl.Result.FAILED) {
                    if (!hands.inReach(b)) {
                        move.moveTo(Vec3d.ofBottomCenter(target), false);
                    } else {
                        replan(target);
                    }
                }
                return;
            }
        }

        Vec3d center = Vec3d.ofBottomCenter(target);
        double dx = center.x - npc.getX(), dz = center.z - npc.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = target.getY() - npc.getY();

        switch (step.move()) {
            case PILLAR -> {
                BlockPos placeAt = step.placeAt();
                move.setRotation(npc.getYaw(), 90f);
                if (placeAt != null && world.getBlockState(placeAt).isReplaceable()) {
                    if (npc.isOnGround()) move.jump();
                    if (npc.getY() > placeAt.getY() + 1.05) {
                        if (!inventory.selectScaffold()) {
                            fail();
                            return;
                        }
                        if (hands.placeBlock(placeAt, InventoryControl::isScaffold, false) == InteractionControl.Result.DONE) {
                            placedScaffold.add(placeAt.toImmutable());
                        }
                    }
                } else if (npc.isOnGround() && feet.getY() >= target.getY()) {
                    advance();
                }
                checkStuck(Math.abs(dy) + horizontal);
                return;
            }
            case BRIDGE -> {
                BlockPos placeAt = step.placeAt();
                if (placeAt != null && world.getBlockState(placeAt).isReplaceable()) {
                    // stand at the edge, sneak so we don't fall, place against the block we stand on
                    move.sneak();
                    BlockPos standing = feet.down();
                    Direction toward = Direction.getFacing(target.getX() - feet.getX(), 0, target.getZ() - feet.getZ());
                    Vec3d edge = Vec3d.ofBottomCenter(feet).add(toward.getOffsetX() * 0.35, 0, toward.getOffsetZ() * 0.35);
                    double ex = edge.x - npc.getX(), ez = edge.z - npc.getZ();
                    if (ex * ex + ez * ez > 0.04) move.moveTo(edge, false);
                    if (!inventory.selectScaffold()) {
                        fail();
                        return;
                    }
                    move.lookAt(Vec3d.ofCenter(placeAt));
                    if (hands.placeBlock(placeAt, InventoryControl::isScaffold, false) == InteractionControl.Result.DONE) {
                        placedScaffold.add(placeAt.toImmutable());
                    } else if (stepTicks > 60) {
                        replan(target);
                    }
                    return;
                }
                move.sneak();
            }
            case DIG_DOWN -> {
                // the block below was in toBreak; once it's gone we just fall into the hole
                if (feet.getY() <= target.getY() && npc.isOnGround()) {
                    advance();
                } else {
                    move.moveTo(center, false);
                }
                checkStuck(Math.abs(dy) + horizontal);
                return;
            }
            default -> {
            }
        }

        // open doors in the way
        hands.openIfClosed(target);
        hands.openIfClosed(target.up());

        boolean inWater = npc.isTouchingWater();
        boolean wantUp = dy > 0.4;
        move.moveTo(center, sprint && shouldSprint() && !inWater);
        if (wantUp) {
            if (inWater || WorldRules.isClimbable(world.getBlockState(feet))) {
                move.jump();
            } else if (npc.isOnGround() && horizontal < 1.6) {
                move.jump();
            }
        } else if (inWater && npc.isSubmergedInWater() && step.move() != PathFinder.Move.SWIM) {
            move.jump(); // keep head above water while swimming across
        } else if (inWater && dy > -0.5 && step.move() == PathFinder.Move.SWIM && target.getY() >= feet.getY()) {
            move.jump();
        }
        if (step.move() == PathFinder.Move.CLIMB_DOWN) {
            move.input(0.2f, 0f);
        }

        boolean closeEnough = horizontal < (step.move() == PathFinder.Move.DIAGONAL ? 0.45 : 0.35);
        boolean levelOk = Math.abs(dy) < 0.75 || (step.move() == PathFinder.Move.DROP && dy <= 0.5 && npc.isOnGround())
                || inWater && Math.abs(dy) < 1.2;
        if (closeEnough && levelOk) {
            advance();
            return;
        }
        // passing through without stopping: if the next node is already closer, move on
        if (index + 1 < path.size() && horizontal < 1.0 && levelOk) {
            BlockPos next = path.get(index + 1).pos();
            if (path.get(index + 1).toBreak().isEmpty() && path.get(index + 1).move() == PathFinder.Move.WALK
                    && npc.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(next)) < 1.0) {
                advance();
                return;
            }
        }
        if (npc.horizontalCollision && npc.isOnGround() && stepTicks % 10 == 5) move.jump();
        checkStuck(horizontal + Math.abs(dy));
    }

    private boolean shouldSprint() {
        int straight = 0;
        for (int i = index; i < Math.min(path.size(), index + 4); i++) {
            PathFinder.Move m = path.get(i).move();
            if (m == PathFinder.Move.WALK || m == PathFinder.Move.DIAGONAL) straight++;
        }
        return straight >= 4;
    }

    private void advance() {
        index++;
        stepTicks = 0;
        stuckTicks = 0;
        lastProgressDist = Double.MAX_VALUE;
    }

    private void checkStuck(double dist) {
        if (dist < lastProgressDist - 0.05) {
            lastProgressDist = dist;
            stuckTicks = 0;
            return;
        }
        stuckTicks++;
        if (stuckTicks == 25 && npc.isOnGround()) move.jump();
        if (stuckTicks > 60) {
            replan(index < path.size() ? path.get(index).pos() : null);
        }
    }

    private void replan(@Nullable BlockPos badNode) {
        if (badNode != null) avoid.add(badNode.asLong());
        if (replans++ > 10) {
            fail();
            return;
        }
        startSearch();
    }

    private void fail() {
        state = State.FAILED;
        hands.cancelBreaking();
    }

    /** Remaining path length estimate, for status displays. */
    public int remaining() {
        return Math.max(0, path.size() - index);
    }

    public static double horizontalDistance(Vec3d a, Vec3d b) {
        return MathHelper.sqrt((float) ((a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z)));
    }
}
