package dev.mcattle.task;

import dev.mcattle.npc.NpcBrain;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;

import java.util.Comparator;

/** Bodyguard: stays close to someone and fights whatever threatens them. */
public final class GuardTask extends Task {
    private final Target protectee;
    private final FollowTask follow;

    public GuardTask(Target protectee) {
        this.protectee = protectee;
        this.follow = new FollowTask(protectee, 3.5);
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (hasChild()) {
            tickChild(npc);
            return Status.RUNNING;
        }
        Entity e = protectee.entity(npc);
        if (!(e instanceof LivingEntity ward) || !ward.isAlive()) return fail("lost " + protectee.describe());
        if (ticks % 10 == 0) {
            LivingEntity threat = ward.getAttacker();
            if (threat != null && threat.isAlive() && threat != npc.npc() && threat.distanceTo(ward) < 12) {
                setChild(new AttackTask(Target.of(threat), !(threat instanceof PlayerEntity), 30, false), npc);
                npc.say("back off " + threat.getName().getString() + "!");
                return Status.RUNNING;
            }
            LivingEntity mob = npc.world().getEntitiesByClass(LivingEntity.class, ward.getBoundingBox().expand(12, 6, 12),
                            m -> m instanceof Monster && m.isAlive() && !(m instanceof net.minecraft.entity.mob.EndermanEntity em && !em.isAngry()))
                    .stream().min(Comparator.comparingDouble(m -> m.squaredDistanceTo(ward))).orElse(null);
            if (mob != null) {
                setChild(new AttackTask(Target.of(mob), true, 40, false), npc);
                return Status.RUNNING;
            }
        }
        follow.tick(npc);
        return Status.RUNNING;
    }

    @Override
    public String describe() {
        return "guarding " + protectee.describe();
    }

    @Override
    public boolean isOpenEnded() {
        return true;
    }

    @Override
    public Task helpCopy(NpcBrain helper) {
        return new GuardTask(protectee);
    }
}
