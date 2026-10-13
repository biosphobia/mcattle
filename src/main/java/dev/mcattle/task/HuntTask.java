package dev.mcattle.task;

import dev.mcattle.control.ItemQuery;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;

/** Hunts mobs of a type (for food, leather, string...) and collects the drops. */
public final class HuntTask extends Task {
    private final EntityType<?> type;
    @Nullable private final ItemQuery counted;
    private final int amount;
    private int kills;
    private int searches;
    private boolean collecting;

    /** @param counted stop when the inventory holds {@code amount} of these; null = stop after {@code amount} kills */
    public HuntTask(EntityType<?> type, @Nullable ItemQuery counted, int amount) {
        this.type = type;
        this.counted = counted;
        this.amount = Math.max(1, amount);
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (counted != null ? npc.inventory().count(counted) >= amount : kills >= amount) return Status.SUCCESS;
        if (hasChild()) {
            Status s = tickChild(npc);
            if (s == Status.RUNNING) return Status.RUNNING;
            if (!collecting && s == Status.SUCCESS) {
                kills++;
                collecting = true;
                setChild(new CollectItemsTask(6, x -> true, 20 * 5), npc);
                return Status.RUNNING;
            }
            collecting = false;
        }
        LivingEntity prey = npc.world().getEntitiesByClass(LivingEntity.class, new Box(npc.npc().getBlockPos()).expand(48, 16, 48),
                        e -> e.getType() == type && e.isAlive() && !(e instanceof net.minecraft.entity.passive.PassiveEntity p && p.isBaby()))
                .stream().min(Comparator.comparingDouble(e -> e.squaredDistanceTo(npc.npc()))).orElse(null);
        if (prey == null) {
            if (++searches > 4) return fail("can't find any " + type.getName().getString().toLowerCase(java.util.Locale.ROOT) + "s");
            setChild(new ExploreTask(64, 20 * 30), npc);
            return Status.RUNNING;
        }
        setChild(new AttackTask(Target.of(prey), true, 60, false), npc);
        return Status.RUNNING;
    }

    @Override
    public String describe() {
        return "hunting " + type.getName().getString().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public Task helpCopy(NpcBrain helper) {
        return new HuntTask(type, counted, amount);
    }
}
