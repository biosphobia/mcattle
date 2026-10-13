package dev.mcattle.control;

import dev.mcattle.npc.NpcPlayer;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.event.GameEvent;
import org.jetbrains.annotations.Nullable;

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
     * Mines a block over several ticks with the best tool, showing the crack animation to players.
     * Call every tick until it returns DONE or FAILED.
     */
    public Result breakBlock(BlockPos pos) {
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

    /** Finds a neighbor face to click so a block can be placed at {@code target}. */
    @Nullable
    public BlockHitResult placementHit(BlockPos target) {
        ServerWorld world = world();
        BlockHitResult best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos support = target.offset(d);
            BlockState s = world.getBlockState(support);
            if (!WorldRules.isSupport(world, support, s)) continue;
            Direction face = d.getOpposite();
            Vec3d hit = Vec3d.ofCenter(support).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            double dist = npc.getEyePos().squaredDistanceTo(hit);
            if (dist < bestDist && dist <= REACH * REACH) {
                bestDist = dist;
                best = new BlockHitResult(hit, face, support, false);
            }
        }
        return best;
    }

    /** True if the NPC's own body is in the way of placing a full block there. */
    public boolean bodyBlocks(BlockPos target) {
        return npc.getBoundingBox().intersects(new Box(target));
    }

    /**
     * Places one block of the selected kind at target. Returns DONE when the block is there,
     * FAILED when impossible right now (no item, out of reach, nothing to click and no assisted placement).
     */
    public Result placeBlock(BlockPos target, Predicate<ItemStack> item, boolean assisted) {
        ServerWorld world = world();
        BlockState current = world.getBlockState(target);
        if (!current.isReplaceable()) return Result.FAILED;
        if (!inventory.select(item)) return Result.FAILED;
        if (!inReach(target)) return Result.FAILED;
        ItemStack stack = npc.getMainHandStack();
        BlockHitResult hit = placementHit(target);
        if (hit != null) {
            move.lookAt(hit.getPos());
            move.sneak(); // so clicking a chest or crafting table doesn't open it
            npc.setSneaking(true);
            ActionResult result = npc.interactionManager.interactBlock(npc, world, stack, Hand.MAIN_HAND, hit);
            npc.setSneaking(false);
            inventory.swingMainHand();
            npc.updateLastActionTime();
            if (result.isAccepted() && !world.getBlockState(target).isReplaceable()) return Result.DONE;
            if (!world.getBlockState(target).isReplaceable()) return Result.DONE;
        }
        if (assisted && stack.getItem() instanceof BlockItem blockItem) {
            // Nothing to click against: place it like scaffolding would have allowed a player to.
            BlockHitResult fake = new BlockHitResult(Vec3d.ofCenter(target), Direction.UP, target, false);
            ItemPlacementContext ctx = new ItemPlacementContext(npc, Hand.MAIN_HAND, stack, fake);
            BlockState placed = blockItem.getBlock().getPlacementState(ctx);
            if (placed == null) placed = blockItem.getBlock().getDefaultState();
            if (!placed.canPlaceAt(world, target)) return Result.FAILED;
            world.setBlockState(target, placed);
            BlockSoundGroup sound = placed.getSoundGroup();
            world.playSound(null, target, sound.getPlaceSound(), SoundCategory.BLOCKS, (sound.getVolume() + 1f) / 2f, sound.getPitch() * 0.8f);
            world.emitGameEvent(npc, GameEvent.BLOCK_PLACE, target);
            if (!npc.isCreative()) stack.decrement(1);
            inventory.swingMainHand();
            return Result.DONE;
        }
        return Result.FAILED;
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
