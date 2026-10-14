package dev.mcattle.task;

import dev.mcattle.control.PathFinder;
import dev.mcattle.npc.NpcBrain;
import dev.mcattle.npc.NpcPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Fights like a decent PvP player: closes distance sprinting, circle-strafes, times hits to the attack cooldown,
 * jump-crits, W-taps for knockback, blocks with a shield when the opponent winds up, uses a bow at range,
 * and backs off to eat when low (not in ordered fights to the death). Spars stop when the opponent is nearly dead.
 */
public final class AttackTask extends Task {
    private final Target target;
    private final boolean lethal;
    private final int maxTicks;
    private final boolean reflex;
    private int strafeDir = 1;
    private int strafeTicks;
    private int wtapTicks;
    private int shieldTicks;
    private int bowTicks;
    private boolean retreating;
    private BlockPos plannedFor;
    private boolean announced;

    /**
     * @param durationSeconds give up after this long (0 = until someone wins)
     * @param reflex          started by the NPC itself (self defense / retaliation) rather than ordered
     */
    public AttackTask(Target target, boolean lethal, int durationSeconds, boolean reflex) {
        this.target = target;
        this.lethal = lethal;
        this.maxTicks = durationSeconds <= 0 ? Integer.MAX_VALUE : durationSeconds * 20;
        this.reflex = reflex;
    }

    public boolean isTargeting(Entity e) {
        return target.matches(e);
    }

    @Override
    protected void start(NpcBrain npc) {
        npc.inventory().selectBestWeapon();
        npc.inventory().equipShield();
    }

    @Override
    protected Status run(NpcBrain npc) {
        NpcPlayer self = npc.npc();
        Entity e = target.entity(npc);
        if (!(e instanceof LivingEntity enemy) || !enemy.isAlive() || enemy.isRemoved()) {
            if (!reflex && e instanceof PlayerEntity) npc.say("gg");
            return Status.SUCCESS;
        }
        if (enemy.getWorld() != self.getWorld()) return fail("they ran off to another dimension");
        if (ticks > maxTicks) return Status.SUCCESS;
        if (enemy instanceof PlayerEntity p && (p.isCreative() || p.isSpectator())) {
            if (!reflex) npc.say("can't hurt you in creative, coward");
            return Status.SUCCESS;
        }
        if (!lethal && enemy.getHealth() <= 6 && enemy instanceof PlayerEntity) {
            npc.say(npc.random().nextBoolean() ? "gg, i'll let you live" : "ok ok i win, gg");
            return Status.SUCCESS;
        }
        if (!announced && !reflex && enemy instanceof PlayerEntity && !npc.server().isPvpEnabled()) {
            announced = true;
            npc.say("pvp is off on this server so this is gonna be a pillow fight");
        }
        double dist = self.distanceTo(enemy);

        // creeper about to blow: get out of there
        if (enemy instanceof CreeperEntity creeper && creeper.getFuseSpeed() > 0 && dist < 5) {
            npc.move().lookAt(creeper);
            Vec3d away = self.getPos().add(self.getPos().subtract(creeper.getPos()).normalize().multiply(4));
            npc.move().moveTo(away, true);
            return Status.RUNNING;
        }

        if (hasChild()) {
            Status s = tickChild(npc);
            if (s == Status.RUNNING) return Status.RUNNING;
            retreating = false;
        }

        // low health: back off and eat - except in a fight to the death it was ordered into
        if ((reflex || !lethal) && self.getHealth() < 7 && npc.inventory().foodCount() + npc.inventory().count(s -> s.isOf(Items.GOLDEN_APPLE)) > 0 && !retreating) {
            retreating = true;
        }
        if (retreating) {
            npc.hands().releaseItem();
            if (dist < 7) {
                Vec3d away = self.getPos().add(self.getPos().subtract(enemy.getPos()).normalize().multiply(5));
                npc.move().moveTo(away, true);
                if (self.horizontalCollision && self.isOnGround()) npc.move().jump();
                return Status.RUNNING;
            }
            setChild(new EatTask(true), npc);
            return Status.RUNNING;
        }

        if (ticks % 20 == 0 && !self.isUsingItem()) npc.inventory().selectBestWeapon();

        // ranged: bow when far and we have arrows
        boolean canBow = npc.inventory().has(s -> s.getItem() instanceof BowItem) && npc.inventory().count(Items.ARROW) > 0;
        if (canBow && dist > 9 && dist < 40 && self.canSee(enemy)) {
            return shootBow(npc, enemy, dist);
        } else if (bowTicks > 0) {
            npc.hands().releaseItem();
            bowTicks = 0;
            npc.inventory().selectBestWeapon();
        }

        if (dist > 3.2) {
            approach(npc, enemy, dist);
            return Status.RUNNING;
        }
        melee(npc, enemy, dist);
        return Status.RUNNING;
    }

    private void approach(NpcBrain npc, LivingEntity enemy, double dist) {
        NpcPlayer self = npc.npc();
        lowerShield(npc);
        if (dist < 8 && self.canSee(enemy) && Math.abs(enemy.getY() - self.getY()) < 2.5) {
            npc.nav().stop();
            plannedFor = null;
            npc.move().lookAt(enemy);
            npc.move().moveTo(enemy.getPos(), true);
            // zig-zag against archers
            if (enemy.isUsingItem() && enemy.getActiveItem().getItem() instanceof BowItem) {
                npc.move().input(1f, (ticks / 10) % 2 == 0 ? 0.7f : -0.7f);
                npc.move().lookAt(enemy);
            }
            if (self.horizontalCollision && self.isOnGround()) npc.move().jump();
            if (self.isTouchingWater()) npc.move().jump();
            return;
        }
        BlockPos goal = enemy.getBlockPos();
        if (plannedFor == null || plannedFor.getSquaredDistance(goal) > 4 || ticks % 40 == 0) {
            plannedFor = goal;
            npc.nav().navigate(PathFinder.near(goal, 2.0));
        }
        npc.nav().setSprint(true);
        npc.nav().tick();
    }

    private void melee(NpcBrain npc, LivingEntity enemy, double dist) {
        NpcPlayer self = npc.npc();
        npc.nav().stop();
        plannedFor = null;
        npc.move().fastTurn();
        npc.move().lookAt(enemy.getEyePos().add(0, -0.4, 0));

        // footwork: keep ~2.6 blocks, circle around them, switch direction unpredictably
        if (--strafeTicks <= 0) {
            strafeDir = npc.random().nextInt(5) == 0 ? 0 : (npc.random().nextBoolean() ? 1 : -1);
            strafeTicks = 12 + npc.random().nextInt(25);
        }
        float forward = dist > 2.7 ? 1f : dist < 1.9 ? -0.7f : 0.25f;
        float cooldown = self.getAttackCooldownProgress(0.5f);

        // read the opponent: shield up if they're winding up a full-strength swing
        if (npc.inventory().hasShield()) {
            boolean threat = enemy instanceof PlayerEntity p ? p.getAttackCooldownProgress(0.5f) > 0.9f && enemy.handSwinging
                    || p.getAttackCooldownProgress(0.5f) > 0.95f && dist < 3.0 && npc.random().nextInt(8) == 0
                    : enemy.isUsingItem() && enemy.getActiveItem().getItem() instanceof BowItem;
            if (shieldTicks > 0) {
                shieldTicks--;
                if (shieldTicks == 0 || cooldown >= 1.0f && npc.random().nextInt(3) == 0) lowerShield(npc);
            } else if (threat && cooldown < 0.8f) {
                npc.hands().useItem(Hand.OFF_HAND);
                shieldTicks = 8 + npc.random().nextInt(8);
            }
        }
        boolean blocking = self.isUsingItem() && self.getActiveItem().isOf(Items.SHIELD);
        npc.move().input(forward, strafeDir * (blocking ? 0.5f : 0.9f));

        // W-tap: release sprint briefly after a hit so the next one knocks back again
        if (wtapTicks > 0) {
            wtapTicks--;
            npc.move().sprint(false);
        } else {
            npc.move().sprint(forward > 0.5f);
        }

        boolean inWater = self.isTouchingWater();
        if (inWater) npc.move().jump();
        boolean wantCrit = !inWater && enemy.hurtTime < 4 && npc.random().nextInt(10) < 6;
        if (wantCrit && self.isOnGround() && cooldown > 0.75f && dist < 3.0) {
            npc.move().jump();
        }
        boolean falling = !self.isOnGround() && self.getVelocity().y < -0.08 && self.fallDistance > 0;
        boolean ready = cooldown >= 0.92f;
        if (ready && dist <= 3.0 && (falling || self.isOnGround() || inWater)) {
            if (blocking) lowerShield(npc);
            if (falling) self.setSprinting(false); // crits need a non-sprinting falling hit
            if (npc.hands().attack(enemy)) {
                wtapTicks = 2;
                if (falling) self.setSprinting(false);
            }
        }
        // jump down/up to reach a target on a different level
        if (Math.abs(enemy.getY() - self.getY()) > 1.2 && self.horizontalCollision && self.isOnGround()) npc.move().jump();
    }

    private Status shootBow(NpcBrain npc, LivingEntity enemy, double dist) {
        NpcPlayer self = npc.npc();
        npc.nav().stop();
        lowerShieldIfNotBow(npc);
        if (!npc.inventory().select(s -> s.getItem() instanceof BowItem)) return Status.RUNNING;
        // aim with arrow drop: arrows fly ~3 blocks/tick at full draw, gravity 0.05
        double flightTicks = dist / 3.0;
        Vec3d aim = enemy.getPos().add(enemy.getVelocity().multiply(flightTicks)).add(0, enemy.getHeight() * 0.6, 0);
        double drop = 0.5 * 0.05 * flightTicks * flightTicks * 1.6;
        npc.move().lookAt(aim.add(0, drop, 0));
        npc.move().input(0, (ticks / 30) % 2 == 0 ? 0.6f : -0.6f);
        if (!self.isUsingItem()) {
            npc.hands().useItem(Hand.MAIN_HAND);
            bowTicks = 0;
        } else {
            bowTicks++;
            if (bowTicks >= 22) {
                npc.hands().releaseItem(); // fires the arrow
                bowTicks = 0;
            }
        }
        return Status.RUNNING;
    }

    private void lowerShield(NpcBrain npc) {
        NpcPlayer self = npc.npc();
        if (self.isUsingItem() && self.getActiveItem().isOf(Items.SHIELD)) self.stopUsingItem();
        shieldTicks = 0;
    }

    private void lowerShieldIfNotBow(NpcBrain npc) {
        ItemStack active = npc.npc().getActiveItem();
        if (npc.npc().isUsingItem() && active.isOf(Items.SHIELD)) npc.npc().stopUsingItem();
    }

    @Override
    public void stop(NpcBrain npc) {
        super.stop(npc);
        npc.npc().stopUsingItem();
    }

    @Override
    public String describe() {
        return (lethal ? "fighting " : "sparring with ") + target.describe();
    }

    @Override
    public Task helpCopy(NpcBrain helper) {
        return new AttackTask(target, lethal, 0, false);
    }
}
