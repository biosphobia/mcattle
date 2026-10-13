package dev.mcattle.control;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

/** How NPCs judge blocks: walkable, standable, dangerous, natural, worth breaking. */
public final class WorldRules {
    private WorldRules() {
    }

    public static boolean isLoaded(World world, BlockPos pos) {
        return world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
                && pos.getY() >= world.getBottomY() && pos.getY() < world.getTopY();
    }

    /** Blocks an NPC must never stand in or next to its feet. */
    public static boolean isDangerous(BlockState state) {
        Block b = state.getBlock();
        return state.getFluidState().isIn(FluidTags.LAVA) || state.isIn(Tags.FIRE) || b == Blocks.MAGMA_BLOCK
                || b == Blocks.CACTUS || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.POWDER_SNOW
                || b == Blocks.WITHER_ROSE || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE
                || b == Blocks.POINTED_DRIPSTONE;
    }

    public static boolean isWater(BlockState state) {
        return state.getFluidState().isIn(FluidTags.WATER);
    }

    public static boolean isLava(BlockState state) {
        return state.getFluidState().isIn(FluidTags.LAVA);
    }

    /** Wooden doors and fence gates can be opened, so paths may go through them. */
    public static boolean isOpenable(BlockState state) {
        return (state.getBlock() instanceof DoorBlock && state.isIn(Tags.WOODEN_DOORS))
                || state.getBlock() instanceof FenceGateBlock;
    }

    /** The body can occupy this cell (feet or head). */
    public static boolean isPassable(World world, BlockPos pos, BlockState state) {
        if (isDangerous(state)) return false;
        if (state.isAir()) return true;
        if (state.getBlock() == Blocks.COBWEB) return false;
        if (isOpenable(state)) return true;
        return state.getCollisionShape(world, pos).isEmpty();
    }

    /** Something solid enough to stand on (top surface at least half a block high, not taller than a full block). */
    public static boolean isStandable(World world, BlockPos pos, BlockState state) {
        if (state.isAir() || isDangerous(state)) return false;
        if (state.isIn(Tags.FENCES) || state.isIn(Tags.WALLS) || state.getBlock() instanceof FenceGateBlock) return false;
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) return false;
        double top = shape.getMax(Direction.Axis.Y);
        return top >= 0.5 && top <= 1.0;
    }

    public static boolean isClimbable(BlockState state) {
        return state.isIn(Tags.CLIMBABLE);
    }

    /** Has a face another block can be placed against. */
    public static boolean isSupport(World world, BlockPos pos, BlockState state) {
        if (state.isAir() || state.isReplaceable()) return false;
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty() && state.getCollisionShape(world, pos).isEmpty()) return false;
        return !state.getOutlineShape(world, pos).isEmpty();
    }

    /** Terrain a player wouldn't mind being dug: stone, dirt, ores, leaves, sand... never builds. */
    public static boolean isNatural(BlockState state) {
        if (state.isAir()) return true;
        if (state.isIn(Tags.BASE_STONE_OVERWORLD) || state.isIn(Tags.BASE_STONE_NETHER) || state.isIn(Tags.DIRT)
                || state.isIn(Tags.SAND) || state.isIn(Tags.LEAVES) || state.isIn(Tags.SNOW) || state.isIn(Tags.ICE)
                || state.isIn(Tags.TERRACOTTA) || isOre(state)) {
            return true;
        }
        Block b = state.getBlock();
        return b == Blocks.GRAVEL || b == Blocks.CLAY || b == Blocks.SANDSTONE || b == Blocks.RED_SANDSTONE
                || b == Blocks.NETHERRACK || b == Blocks.SOUL_SAND || b == Blocks.SOUL_SOIL || b == Blocks.END_STONE
                || b == Blocks.CALCITE || b == Blocks.DRIPSTONE_BLOCK || b == Blocks.AMETHYST_BLOCK
                || b == Blocks.SMOOTH_BASALT
                || b == Blocks.MOSS_BLOCK || b == Blocks.MUD
                || b == Blocks.GRASS || b == Blocks.TALL_GRASS || b == Blocks.FERN || b == Blocks.LARGE_FERN
                || b == Blocks.DEAD_BUSH || b == Blocks.VINE || b == Blocks.GLOW_LICHEN || b == Blocks.SNOW
                || b == Blocks.BROWN_MUSHROOM_BLOCK || b == Blocks.RED_MUSHROOM_BLOCK || b == Blocks.MUSHROOM_STEM
                || b == Blocks.COBWEB || state.isIn(Tags.FLOWERS);
    }

    public static boolean isOre(BlockState state) {
        for (String tag : Tags.ORE_TAGS) if (state.isIn(Tags.block(tag))) return true;
        return state.getBlock() == Blocks.NETHER_QUARTZ_ORE || state.getBlock() == Blocks.NETHER_GOLD_ORE
                || state.getBlock() == Blocks.ANCIENT_DEBRIS;
    }

    /** Can be broken at all by a survival player (not bedrock, not a portal...). */
    public static boolean isBreakable(World world, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(world, pos).isEmpty()) return false;
        float hardness = state.getHardness(world, pos);
        return hardness >= 0 && hardness < 50;
    }

    /** Things NPCs never break unless explicitly asked: containers, beds, anything with a block entity. */
    public static boolean isPrecious(World world, BlockPos pos, BlockState state) {
        return state.hasBlockEntity() || state.isIn(Tags.BEDS) || state.isIn(Tags.SHULKER_BOXES)
                || state.getBlock() == Blocks.SPAWNER || state.getBlock() == Blocks.END_PORTAL_FRAME;
    }

    /** True if breaking this block would let lava or water flow onto the NPC. */
    public static boolean exposesFluid(World world, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue;
            BlockState n = world.getBlockState(pos.offset(d));
            if (isLava(n)) return true;
            if (d == Direction.UP && isWater(n)) return true;
        }
        return false;
    }

    /** Falling blocks (sand, gravel) directly above would collapse into the hole. */
    public static boolean hasFallingAbove(World world, BlockPos pos) {
        BlockState above = world.getBlockState(pos.up());
        return above.getBlock() instanceof net.minecraft.block.FallingBlock;
    }
}
