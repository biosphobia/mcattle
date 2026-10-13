package dev.mcattle.task;

import dev.mcattle.control.Navigator;
import dev.mcattle.control.PathFinder;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Picks up dropped items nearby by walking over them. */
public final class CollectItemsTask extends Task {
    private final double radius;
    private final Predicate<ItemStack> filter;
    private final int maxTicks;
    @Nullable private ItemEntity targetItem;
    private final Set<UUID> unreachable = new HashSet<>();
    private int targetTicks;

    public CollectItemsTask(double radius, Predicate<ItemStack> filter, int maxTicks) {
        this.radius = radius;
        this.filter = filter;
        this.maxTicks = maxTicks;
    }

    public static CollectItemsTask nearby() {
        return new CollectItemsTask(8, s -> true, 20 * 15);
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (ticks > maxTicks) return Status.SUCCESS;
        if (targetItem == null || !targetItem.isAlive() || targetTicks > 200) {
            if (targetItem != null && targetItem.isAlive()) unreachable.add(targetItem.getUuid());
            targetItem = findItem(npc);
            targetTicks = 0;
            if (targetItem == null) return Status.SUCCESS;
            npc.nav().navigate(PathFinder.near(targetItem.getBlockPos(), 0.8));
        }
        targetTicks++;
        if (npc.npc().getBoundingBox().expand(1.0, 0.5, 1.0).intersects(targetItem.getBoundingBox())) {
            npc.nav().stop();
            npc.move().moveTo(targetItem.getPos(), false); // stand on it until picked up
            return Status.RUNNING;
        }
        Navigator.State s = npc.nav().tick();
        if (s == Navigator.State.FAILED || s == Navigator.State.ARRIVED) {
            if (npc.pos().distanceTo(targetItem.getPos()) < 1.6) {
                npc.move().moveTo(targetItem.getPos(), false);
            } else {
                unreachable.add(targetItem.getUuid());
                targetItem = null;
            }
        }
        return Status.RUNNING;
    }

    @Nullable
    private ItemEntity findItem(NpcBrain npc) {
        Vec3d p = npc.pos();
        List<ItemEntity> items = npc.world().getEntitiesByClass(ItemEntity.class, npc.box().expand(radius, 4, radius),
                e -> e.isAlive() && !unreachable.contains(e.getUuid()) && filter.test(e.getStack()) && !e.isInLava()
                        && !npc.manager().isGift(e.getUuid()));
        return items.stream().min(Comparator.comparingDouble(e -> e.squaredDistanceTo(p))).orElse(null);
    }

    @Override
    public String describe() {
        return "picking up items";
    }
}
