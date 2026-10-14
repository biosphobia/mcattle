package dev.mcattle.task;

import dev.mcattle.control.BlockSearch;
import dev.mcattle.control.InventoryControl;
import dev.mcattle.control.ItemQuery;
import dev.mcattle.control.Navigator;
import dev.mcattle.control.PathFinder;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dies on purpose because a player said so, the way a real player would have to: walking into lava, sinking in
 * deep water and not coming up, hugging a cactus, or pillaring up high and stepping off. Picks whatever the
 * surroundings allow (or the method it was told), and moves on to another one if that doesn't work out. Its
 * survival reflexes are off meanwhile. It respawns at home like any player.
 */
public final class DieTask extends Task {
    private static final int FALL_HEIGHT = 26; // enough to kill from full health

    private enum Method {LAVA, DROWN, CACTUS, FALL}

    private final List<Method> plan = new ArrayList<>();
    @Nullable private Method method;
    @Nullable private BlockPos spot;
    private int methodTicks;
    private boolean navigating;
    // pillar state
    private int startY;
    @Nullable private BlockPos pillarBase;
    private int stuckTicks;
    private double lastY;
    @Nullable private Direction leap;

    public DieTask(String how) {
        String h = how == null ? "" : how.toLowerCase(Locale.ROOT);
        if (h.contains("lava") || h.contains("burn")) plan.add(Method.LAVA);
        if (h.contains("drown") || h.contains("water") || h.contains("ocean")) plan.add(Method.DROWN);
        if (h.contains("cactus") || h.contains("prick")) plan.add(Method.CACTUS);
        if (h.contains("fall") || h.contains("jump") || h.contains("height") || h.contains("cliff")) plan.add(Method.FALL);
        for (Method m : Method.values()) if (!plan.contains(m)) plan.add(m); // fallbacks, fall last (always possible)
    }

    public static boolean isActive(@Nullable Task t) {
        return t instanceof DieTask;
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (!npc.npc().isAlive()) return Status.SUCCESS;
        if (hasChild()) {
            Status s = tickChild(npc);
            if (s == Status.RUNNING) return Status.RUNNING;
            if (s == Status.FAILURE && method == Method.FALL) return fail("couldn't get blocks to climb up with");
        }
        if (method == null && !nextMethod(npc)) return fail("couldn't find a way to do it");
        methodTicks++;
        boolean keepGoing = switch (method) {
            case LAVA -> walkInto(npc, true);
            case DROWN -> drown(npc);
            case CACTUS -> walkInto(npc, false);
            case FALL -> fall(npc);
        };
        if (!keepGoing || methodTicks > 20 * 90) {
            npc.nav().stop();
            method = null; // try the next idea
        }
        return Status.RUNNING;
    }

    /** Picks the next method that is possible here. */
    private boolean nextMethod(NpcBrain npc) {
        while (!plan.isEmpty()) {
            Method m = plan.remove(0);
            spot = switch (m) {
                case LAVA -> find(npc, s -> s.isOf(Blocks.LAVA));
                case CACTUS -> find(npc, s -> s.isOf(Blocks.CACTUS));
                case DROWN -> find(npc, s -> s.isOf(Blocks.WATER));
                case FALL -> npc.npc().getBlockPos();
            };
            if (m == Method.DROWN && spot != null && !deep(npc, spot)) spot = null;
            if (spot == null) continue;
            method = m;
            methodTicks = 0;
            navigating = false;
            return true;
        }
        return false;
    }

    @Nullable
    private static BlockPos find(NpcBrain npc, java.util.function.Predicate<BlockState> what) {
        List<BlockPos> found = new BlockSearch(npc.world(), npc.npc().getBlockPos(), 48, 20, what, (p, s) -> true, 1).runNow();
        return found.isEmpty() ? null : found.get(0);
    }

    /** Water at least 3 deep with water above it, so it can sink and stay under. */
    private static boolean deep(NpcBrain npc, BlockPos water) {
        BlockPos top = water;
        while (npc.world().getBlockState(top.up()).isOf(Blocks.WATER)) top = top.up();
        BlockPos bottom = top;
        int depth = 1;
        while (npc.world().getBlockState(bottom.down()).isOf(Blocks.WATER) && depth < 10) {
            bottom = bottom.down();
            depth++;
        }
        return depth >= 3;
    }

    /** Walk up to the lava / cactus and keep walking into it. */
    private boolean walkInto(NpcBrain npc, boolean lava) {
        if (spot == null) return false;
        Vec3d center = Vec3d.ofBottomCenter(spot);
        double dist = npc.pos().distanceTo(center);
        if (dist > 2.5) {
            if (!navigating) {
                npc.nav().navigate(PathFinder.near(spot, 1.5));
                navigating = true;
            }
            Navigator.State s = npc.nav().tick();
            return s != Navigator.State.FAILED;
        }
        npc.nav().stop();
        npc.move().lookAt(center);
        npc.move().moveTo(center, false);
        if (npc.npc().isOnGround() && npc.npc().horizontalCollision && lava) npc.move().jump();
        return true;
    }

    /** Swim to deep water, sink to the bottom and stay there. */
    private boolean drown(NpcBrain npc) {
        if (spot == null) return false;
        if (!npc.npc().isTouchingWater()) {
            if (!navigating) {
                npc.nav().navigate(PathFinder.near(spot, 1.0));
                navigating = true;
            }
            Navigator.State s = npc.nav().tick();
            if (s == Navigator.State.ARRIVED || npc.pos().distanceTo(Vec3d.ofCenter(spot)) < 2.5) {
                npc.move().moveTo(Vec3d.ofBottomCenter(spot), false);
            }
            return s != Navigator.State.FAILED;
        }
        npc.nav().stop();
        npc.move().sneak(); // sneaking in water = sinking
        npc.move().setRotation(npc.npc().getYaw(), 80f);
        return true;
    }

    /** Pillar up {@link #FALL_HEIGHT} blocks, then step off. */
    private boolean fall(NpcBrain npc) {
        var self = npc.npc();
        if (methodTicks == 1) {
            startY = self.getBlockY();
            lastY = self.getY();
        }
        int need = FALL_HEIGHT - (self.getBlockY() - startY);
        if (leap == null && need > 0) {
            if (npc.inventory().count(InventoryControl::isScaffold) < Math.min(need, 64)) {
                setChild(new ObtainTask(ItemQuery.of(Items.DIRT), need), npc);
                startY = self.getBlockY(); // the gathering trip moved us; start the pillar where we end up
                methodTicks = 1;
                return true;
            }
            // clear headroom like a player would
            BlockPos head = self.getBlockPos().up(2);
            if (!npc.world().getBlockState(head).getCollisionShape(npc.world(), head).isEmpty()) {
                npc.hands().breakBlock(head);
                return true;
            }
            npc.move().setRotation(self.getYaw(), 90f);
            npc.inventory().selectScaffold();
            if (self.isOnGround()) {
                pillarBase = self.getBlockPos();
                npc.move().jump();
            } else if (pillarBase != null && self.getY() > pillarBase.getY() + 1.05
                    && npc.world().getBlockState(pillarBase).isReplaceable()) {
                npc.hands().placeBlock(pillarBase, InventoryControl::isScaffold);
            }
            if (Math.abs(self.getY() - lastY) < 0.01) {
                if (++stuckTicks > 100) return false;
            } else {
                stuckTicks = 0;
                lastY = self.getY();
            }
            return true;
        }
        // survived the fall (water, a ledge...): climb again from here
        if (leap != null && self.isOnGround() && self.getBlockY() < startY + FALL_HEIGHT - 4) {
            leap = null;
            startY = self.getBlockY();
            methodTicks = 1;
            return true;
        }
        // at the top: step off toward open air
        if (leap == null) {
            for (Direction d : Direction.Type.HORIZONTAL) {
                if (npc.world().getBlockState(self.getBlockPos().offset(d)).isReplaceable()
                        && npc.world().getBlockState(self.getBlockPos().offset(d).down()).isReplaceable()) {
                    leap = d;
                    break;
                }
            }
            if (leap == null) leap = Direction.NORTH;
        }
        Vec3d over = Vec3d.ofBottomCenter(self.getBlockPos().offset(leap, 2));
        npc.move().moveTo(over, false);
        return true;
    }

    @Override
    public String describe() {
        if (method == null) return "looking for a way to die";
        return switch (method) {
            case LAVA -> "walking into lava";
            case DROWN -> "drowning";
            case CACTUS -> "hugging a cactus";
            case FALL -> "climbing up to jump";
        };
    }
}
