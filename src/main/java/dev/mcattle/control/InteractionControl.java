package dev.mcattle.control;

import dev.mcattle.npc.NpcPlayer;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.state.property.Property;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.RaycastContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Hands of the NPC: breaking blocks over time, placing against real faces, using items and attacking. */
public final class InteractionControl {
    public static final double REACH = 4.5;
    public static final double ATTACK_REACH = 3.0;

    public enum Result {IN_PROGRESS, DONE, FAILED}

    private final NpcPlayer npc;
    private final MovementControl move;
    private final InventoryControl inventory;

    @Nullable private BlockPos breakingPos;
    @Nullable private BlockState breakingState;
    private float breakProgress;
    private int breakTicks;

    public InteractionControl(NpcPlayer npc, MovementControl move, InventoryControl inventory) {
        this.npc = npc;
        this.move = move;
        this.inventory = inventory;
    }

    private ServerWorld world() {
        return npc.getServerWorld();
    }

    public boolean inReach(BlockPos pos) {
        return npc.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= REACH * REACH;
    }

    public boolean inReach(Vec3d point, double reach) {
        return npc.getEyePos().squaredDistanceTo(point) <= reach * reach;
    }

    /**
     * The first block in the way when looking at {@code pos}, or null if some part of it can be seen (and so
     * clicked) directly. Tries the centre of each face, like a player aiming at whichever side is exposed.
     */
    @Nullable
    public BlockPos obstruction(BlockPos pos) {
        ServerWorld world = world();
        Vec3d eye = npc.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        for (Direction d : Direction.values()) {
            Vec3d face = center.add(d.getOffsetX() * 0.45, d.getOffsetY() * 0.45, d.getOffsetZ() * 0.45);
            // faces pointing away from us can't be seen
            if (d.getOffsetX() * (eye.x - face.x) + d.getOffsetY() * (eye.y - face.y) + d.getOffsetZ() * (eye.z - face.z) <= 0) continue;
            BlockHitResult hit = world.raycast(new RaycastContext(eye, face, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, npc));
            if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)) return null;
        }
        // hidden: the block in the way is whatever sits on the straight line to its centre
        BlockHitResult hit = world.raycast(new RaycastContext(eye, center, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, npc));
        if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)) return null;
        return hit.getBlockPos().toImmutable();
    }

    /** True if the NPC can see a point on the block (no other block in between). */
    public boolean canSee(BlockPos pos) {
        return obstruction(pos) == null;
    }

    /**
     * Mines a block over several ticks with the best tool, showing the crack animation to players.
     * Call every tick until it returns DONE or FAILED.
     */
    public Result breakBlock(BlockPos pos) {
        BlockState target = world().getBlockState(pos);
        if (target.isAir() || (!target.getFluidState().isEmpty() && target.getCollisionShape(world(), pos).isEmpty())) {
            cancelBreaking();
            return Result.DONE;
        }
        // a player can only mine the block their crosshair is on: dig through whatever is in front of it first
        BlockPos blocker = inReach(pos) ? obstruction(pos) : null;
        if (blocker != null) {
            BlockState b = world().getBlockState(blocker);
            if (WorldRules.isPrecious(world(), blocker, b) || !WorldRules.isBreakable(world(), blocker, b)) {
                cancelBreaking();
                return Result.FAILED;
            }
            return mine(blocker) == Result.FAILED ? Result.FAILED : Result.IN_PROGRESS;
        }
        return mine(pos);
    }

    private Result mine(BlockPos pos) {
        ServerWorld world = world();
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || (!state.getFluidState().isEmpty() && state.getCollisionShape(world, pos).isEmpty())) {
            cancelBreaking();
            return Result.DONE;
        }
        if (!inReach(pos) || !WorldRules.isBreakable(world, pos, state)) {
            cancelBreaking();
            return Result.FAILED;
        }
        move.lookAt(Vec3d.ofCenter(pos));
        if (!pos.equals(breakingPos) || state != breakingState) {
            cancelBreaking();
            breakingPos = pos.toImmutable();
            breakingState = state;
            breakProgress = 0;
            breakTicks = 0;
            inventory.selectBestTool(state);
            state.onBlockBreakStart(world, pos, npc);
        }
        if (npc.isCreative()) {
            npc.interactionManager.tryBreakBlock(pos);
            inventory.swingMainHand();
            breakingPos = null;
            return Result.DONE;
        }
        breakTicks++;
        breakProgress += state.calcBlockBreakingDelta(npc, world, pos);
        if (breakTicks % 4 == 1) inventory.swingMainHand();
        if (breakProgress >= 1.0f) {
            world.setBlockBreakingInfo(npc.getId(), pos, -1);
            boolean broken = npc.interactionManager.tryBreakBlock(pos);
            inventory.swingMainHand();
            breakingPos = null;
            npc.updateLastActionTime();
            return broken ? Result.DONE : Result.FAILED;
        }
        world.setBlockBreakingInfo(npc.getId(), pos, (int) (breakProgress * 10f));
        if (breakTicks > 20 * 60) { // a minute on one block: something is wrong (e.g. no tool for obsidian)
            cancelBreaking();
            return Result.FAILED;
        }
        return Result.IN_PROGRESS;
    }

    public void cancelBreaking() {
        if (breakingPos != null) {
            world().setBlockBreakingInfo(npc.getId(), breakingPos, -1);
            breakingPos = null;
        }
    }

    public boolean isBreaking() {
        return breakingPos != null;
    }

    /** Estimated ticks to break a block with the best tool we carry (for planning). */
    public float estimateBreakTicks(BlockPos pos, BlockState state) {
        float hardness = state.getHardness(world(), pos);
        if (hardness < 0) return Float.POSITIVE_INFINITY;
        if (hardness == 0) return 1;
        boolean harvest = inventory.canHarvest(state);
        float speed = inventory.bestToolSpeed(state);
        return hardness * (harvest ? 30f : 100f) / speed;
    }

    /** A face of a neighbouring block the NPC could click (in reach, in sight) to put a block at {@code target}. */
    private List<BlockHitResult> placementHits(BlockPos target) {
        ServerWorld world = world();
        Vec3d eye = npc.getEyePos();
        List<BlockHitResult> hits = new ArrayList<>();
        for (Direction d : Direction.values()) {
            BlockPos support = target.offset(d);
            BlockState s = world.getBlockState(support);
            if (!WorldRules.isSupport(world, support, s)) continue;
            Direction face = d.getOpposite();
            Vec3d center = Vec3d.ofCenter(support).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            // the clicked face must point toward the eye, be in reach and not be hidden behind something
            Vec3d toEye = eye.subtract(center);
            if (toEye.dotProduct(Vec3d.of(face.getVector())) <= 0) continue;
            if (eye.squaredDistanceTo(center) > REACH * REACH) continue;
            BlockHitResult seen = world.raycast(new RaycastContext(eye, center.subtract(Vec3d.of(face.getVector()).multiply(0.05)),
                    RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, npc));
            if (seen.getType() != HitResult.Type.MISS && !seen.getBlockPos().equals(support) && !seen.getBlockPos().equals(target)) continue;
            // aim at the middle of the face, and on side faces also at the upper and lower part (top/bottom slabs, stairs)
            hits.add(new BlockHitResult(center, face, support, false));
            if (face.getAxis().isHorizontal()) {
                hits.add(new BlockHitResult(center.add(0, 0.3, 0), face, support, false));
                hits.add(new BlockHitResult(center.add(0, -0.3, 0), face, support, false));
            }
        }
        hits.sort(Comparator.comparingDouble(h -> eye.squaredDistanceTo(h.getPos())));
        return hits;
    }

    @Nullable
    public BlockHitResult placementHit(BlockPos target) {
        List<BlockHitResult> hits = placementHits(target);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** True if the block could be placed from where the NPC stands right now (something to click, in sight). */
    public boolean canPlaceFromHere(BlockPos target) {
        return placementHit(target) != null;
    }

    /** True if the NPC's own body is in the way of placing a full block there. */
    public boolean bodyBlocks(BlockPos target) {
        return npc.getBoundingBox().intersects(new Box(target));
    }

    public Result placeBlock(BlockPos target, Predicate<ItemStack> item) {
        return placeBlock(target, item, null);
    }

    /**
     * Places one block of the selected kind at target the way a player does: right-clicking a visible face of
     * a neighbouring block within reach. When a specific orientation is wanted (stairs facing a way, a top slab,
     * a sideways log) the NPC picks the face, the spot on it and the direction to look that produce it, just like
     * a player turning around before placing. Returns FAILED when there is nothing to click from here.
     */
    public Result placeBlock(BlockPos target, Predicate<ItemStack> item, @Nullable BlockState wanted) {
        ServerWorld world = world();
        BlockState current = world.getBlockState(target);
        if (!current.isReplaceable()) return Result.FAILED;
        if (!inventory.select(item)) return Result.FAILED;
        if (!inReach(target)) return Result.FAILED;
        ItemStack stack = npc.getMainHandStack();
        List<BlockHitResult> hits = placementHits(target);
        if (hits.isEmpty()) return Result.FAILED;

        BlockHitResult hit = hits.get(0);
        float yaw = MovementControl.yawTo(npc.getEyePos(), hit.getPos());
        float pitch = MovementControl.pitchTo(npc.getEyePos(), hit.getPos());
        if (wanted != null && stack.getItem() instanceof BlockItem blockItem) {
            float oldYaw = npc.getYaw(), oldPitch = npc.getPitch();
            search:
            for (BlockHitResult h : hits) {
                float aimYaw = MovementControl.yawTo(npc.getEyePos(), h.getPos());
                float aimPitch = MovementControl.pitchTo(npc.getEyePos(), h.getPos());
                // the block's facing comes from the way the player looks; the face clicked must stay in view (< ~60 deg off)
                for (float dyaw : new float[]{0, 90, -90, 180, 45, -45}) {
                    for (float p : new float[]{aimPitch, 60, -60}) {
                        float y = aimYaw + dyaw;
                        if (Math.abs(MathHelper.wrapDegrees(dyaw)) > 60 && Math.abs(p) < 50) continue;
                        npc.setYaw(y);
                        npc.setPitch(p);
                        BlockState s = blockItem.getBlock().getPlacementState(new ItemPlacementContext(npc, Hand.MAIN_HAND, stack, h));
                        if (s != null && sameShape(s, wanted)) {
                            hit = h;
                            yaw = y;
                            pitch = p;
                            break search;
                        }
                    }
                }
            }
            npc.setYaw(oldYaw);
            npc.setPitch(oldPitch);
        }
        move.setRotation(yaw, pitch);
        npc.setYaw(yaw);
        npc.setPitch(pitch);
        npc.setHeadYaw(yaw);
        npc.setSneaking(true); // so clicking a chest or crafting table doesn't open it
        ActionResult result = npc.interactionManager.interactBlock(npc, world, stack, Hand.MAIN_HAND, hit);
        npc.setSneaking(false);
        inventory.swingMainHand();
        npc.updateLastActionTime();
        if (!world.getBlockState(target).isReplaceable()) return Result.DONE;
        return result.isAccepted() ? Result.DONE : Result.FAILED;
    }

    /** Same block with the same orientation-type properties (ignores things like waterlogged or connections). */
    private static boolean sameShape(BlockState a, BlockState b) {
        if (a.getBlock() != b.getBlock()) return false;
        for (Property<?> p : b.getProperties()) {
            String n = p.getName();
            if (n.equals("facing") || n.equals("half") || n.equals("type") || n.equals("axis") || n.equals("rotation")
                    || n.equals("face") || n.equals("hinge") || n.equals("attachment")) {
                if (!java.util.Objects.equals(a.getEntries().get(p), b.getEntries().get(p))) return false;
            }
        }
        return true;
    }

    /** Right-clicks a block (doors, chests, beds, crafting...). */
    public ActionResult useBlock(BlockPos pos, Direction face) {
        move.lookAt(Vec3d.ofCenter(pos));
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos).add(Vec3d.of(face.getVector()).multiply(0.5)), face, pos, false);
        ActionResult r = npc.interactionManager.interactBlock(npc, world(), npc.getMainHandStack(), Hand.MAIN_HAND, hit);
        inventory.swingMainHand();
        return r;
    }

    /** Starts using the held item (eat, drink, draw bow, raise shield...). */
    public ActionResult useItem(Hand hand) {
        return npc.interactionManager.interactItem(npc, world(), npc.getStackInHand(hand), hand);
    }

    public void releaseItem() {
        if (npc.isUsingItem()) npc.stopUsingItem();
    }

    /** Melee attack with vanilla mechanics (cooldown, crits when falling, sweeping, knockback). */
    public boolean attack(Entity target) {
        if (npc.getEyePos().squaredDistanceTo(closestPoint(target)) > ATTACK_REACH * ATTACK_REACH) return false;
        if (!npc.canSee(target)) return false;
        npc.attack(target);
        npc.swingHand(Hand.MAIN_HAND, true);
        npc.updateLastActionTime();
        return true;
    }

    public static Vec3d closestPoint(Entity target) {
        Box box = target.getBoundingBox();
        return new Vec3d(box.getCenter().x, box.minY + (box.maxY - box.minY) * 0.6, box.getCenter().z);
    }

    /** Opens/closes a wooden door or gate in front of us if it's closed. */
    public boolean openIfClosed(BlockPos pos) {
        BlockState state = world().getBlockState(pos);
        if (!WorldRules.isOpenable(state)) return false;
        if (state.contains(net.minecraft.state.property.Properties.OPEN) && !state.get(net.minecraft.state.property.Properties.OPEN)) {
            boolean wasSneaking = npc.isSneaking();
            npc.setSneaking(false);
            useBlock(pos, Direction.UP);
            npc.setSneaking(wasSneaking);
            return true;
        }
        return false;
    }

    public static boolean isAirLike(BlockState state) {
        return state.isAir() || state.isOf(Blocks.CAVE_AIR) || state.isOf(Blocks.VOID_AIR);
    }
}
