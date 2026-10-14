package dev.mcattle.npc;

import dev.mcattle.core.social.Emotion;
import dev.mcattle.core.social.Relationship;
import dev.mcattle.core.social.SocialEvent;
import dev.mcattle.core.social.SocialMind;
import dev.mcattle.core.social.SocialParse;
import dev.mcattle.task.AttackTask;
import dev.mcattle.task.FleeTask;
import dev.mcattle.task.Target;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Minecraft side of an NPC's feelings: turns what happens in the world (hits, deaths, gifts, rescues, chat,
 * time spent together) into {@link SocialMind} events, and lets mood and relationships show in behavior
 * (greetings or glares, keeping away from people it fears, settling grudges, seeking out friends, particles).
 */
public final class NpcSocial {
    private final NpcBrain npc;
    private int lonelyMinutes;
    @Nullable private LivingEntity lastMonsterAttacker;
    private long lastMonsterAttackTick;
    private long lastGrudgeTick = -100_000;

    public NpcSocial(NpcBrain npc) {
        this.npc = npc;
    }

    public SocialMind mind() {
        NpcIdentity id = npc.identity();
        if (id.social == null) id.social = new SocialMind(id.uuid().hashCode());
        return id.social;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    public static String key(Entity e) {
        return e.getUuidAsString();
    }

    // ------------------------------------------------------------------ events

    public void event(SocialEvent ev, Entity other, double strength, @Nullable String detail) {
        if (other == npc.npc()) return;
        mind().event(ev, key(other), other.getName().getString(), other instanceof NpcPlayer, strength, detail, now());
    }

    @Nullable
    private Entity resolve(String name, @Nullable ServerPlayerEntity speaker) {
        if (name.equalsIgnoreCase("speaker") || name.equalsIgnoreCase("me") && speaker != null) return speaker;
        ServerPlayerEntity p = npc.server().getPlayerManager().getPlayer(name);
        if (p != null) return p;
        NpcBrain other = npc.manager().byName(name);
        return other == null ? null : other.npc();
    }

    /** Applies the model's "feel" tokens (how a message or moment made this NPC feel). */
    public void applyFeel(@Nullable String feel, @Nullable ServerPlayerEntity speaker) {
        for (SocialParse.Feel f : SocialParse.feelings(feel)) {
            boolean self = f.who().equalsIgnoreCase("self") || f.who().equalsIgnoreCase(npc.name());
            SocialEvent ev = SocialEvent.parse(f.word());
            Entity other = self ? null : resolve(f.who(), speaker);
            String otherName = other != null ? other.getName().getString() : f.who();
            String otherKey = other != null ? key(other) : mind().keyOf(f.who());
            if (!self && ev != null && otherKey != null) {
                mind().event(ev, otherKey, otherName, other instanceof NpcPlayer, f.strength(), null, now());
            } else if (mind().feel(f.word(), f.strength())) {
                if (!self) mind().setFeeling(f.word() + " about " + otherName, now(), 10 * 60_000);
                else if (Emotion.blend(f.word()) != null) mind().setFeeling(f.word(), now(), 5 * 60_000);
            } else {
                mind().setFeeling(self ? f.word() : f.word() + " about " + otherName, now(), 10 * 60_000);
            }
        }
    }

    /** Applies the model's "bond" rewrites ("Steve=frenemies since the diamond incident"). */
    public void applyBonds(@Nullable String bonds, @Nullable ServerPlayerEntity speaker) {
        for (Map.Entry<String, String> e : SocialParse.bonds(bonds).entrySet()) {
            Entity other = resolve(e.getKey(), speaker);
            String key = other != null ? key(other) : mind().keyOf(e.getKey());
            if (key == null) continue;
            mind().setBond(key, other != null ? other.getName().getString() : e.getKey(), other instanceof NpcPlayer, e.getValue(), now());
        }
    }

    /** A chat line said to this NPC, when the model didn't judge it: guess the tone from the words. */
    public void heard(ServerPlayerEntity speaker, String message) {
        SocialEvent tone = SocialParse.tone(message);
        if (tone == null) return;
        String quote = message.length() > 40 ? message.substring(0, 40) + "..." : message;
        event(tone, speaker, 1, tone.negative() ? "(\"" + quote + "\")" : null);
    }

    public void onHurt(Entity attacker, float amount, boolean requestedFight) {
        if (attacker instanceof Monster && attacker instanceof LivingEntity living) {
            lastMonsterAttacker = living;
            lastMonsterAttackTick = npc.server().getTicks();
            mind().feel(Emotion.FEAR, 0.05 * amount);
            return;
        }
        if (!(attacker instanceof PlayerEntity)) return;
        if (requestedFight) {
            event(SocialEvent.SPARRED, attacker, 0.3, null);
        } else {
            event(SocialEvent.HIT, attacker, Math.max(0.3, Math.min(2, amount / 4)), null);
            witnessed(SocialEvent.HURT_FRIEND, attacker, 16);
        }
    }

    public void onDeath(@Nullable Entity killer, boolean requestedFight) {
        mind().feel(Emotion.SADNESS, 0.3);
        if (!(killer instanceof PlayerEntity) || killer == npc.npc()) return;
        event(requestedFight ? SocialEvent.BEAT_ME : SocialEvent.KILLED_ME, killer, 1, null);
        if (killer instanceof NpcPlayer other && other.brain() != null) {
            other.brain().social().event(SocialEvent.I_BEAT, npc.npc(), 1, null);
        }
        if (!requestedFight) witnessed(SocialEvent.KILLED_FRIEND, killer, 24);
    }

    /** Other NPCs nearby who care about this one take it personally. */
    private void witnessed(SocialEvent ev, Entity culprit, double range) {
        for (NpcBrain other : npc.manager().all()) {
            if (other == npc || other.npc() == culprit || other.npc().getWorld() != npc.world()) continue;
            if (other.npc().distanceTo(npc.npc()) > range) continue;
            double fondness = other.social().mind().affinity(key(npc.npc()));
            if (fondness < 20) continue;
            other.social().event(ev, culprit, fondness / 50, "(" + npc.name() + ")");
        }
    }

    /** Picked up an item someone threw: a gift. */
    public void received(Entity giver, ItemStack stack, int count) {
        if (giver == npc.npc() || count <= 0) return;
        double value = value(stack) * Math.min(3, 0.6 + count / 8.0);
        String what = count + " " + Registries.ITEM.getId(stack.getItem()).getPath().replace('_', ' ');
        event(SocialEvent.GIFT, giver, Math.max(0.3, Math.min(3, value)), what);
    }

    private static double value(ItemStack s) {
        String id = Registries.ITEM.getId(s.getItem()).getPath();
        if (id.contains("netherite") || id.contains("totem") || id.contains("elytra") || id.contains("enchanted_golden")) return 3;
        if (id.contains("diamond") || id.contains("emerald")) return 2;
        if (id.contains("gold") || id.contains("iron") || s.hasEnchantments() || id.contains("cake") || id.contains("music_disc")) return 1.2;
        if (s.isFood() || id.contains("flower") || id.contains("poppy") || id.contains("dandelion") || id.contains("tulip")) return 0.8;
        return 0.35;
    }

    // ------------------------------------------------------------------ over time

    /** Called every tick by the brain; does real work every second or so. */
    public void tick() {
        long age = npc.age();
        if (age % 100 == 0) {
            mind().tick(now());
            situational();
        }
        if (age % 1200 == 600) company();
        if (age % 40 == 13) particles();
        if (age % 20 == 5) checkRescue();
    }

    private void situational() {
        var p = npc.npc();
        if (p.getHealth() < 6) mind().feel(Emotion.FEAR, 0.08);
        if (npc.world().isNight() && !npc.nearbyHostiles(10).isEmpty()) mind().feel(Emotion.FEAR, 0.04);
        if (p.getHungerManager().getFoodLevel() < 6) mind().feel(Emotion.SADNESS, 0.03);
    }

    /** A minute has passed: time together builds familiarity; long stretches alone get lonely. */
    private void company() {
        List<ServerPlayerEntity> around = npc.nearbyPlayers(10, true);
        boolean busy = npc.currentTask() != null;
        for (ServerPlayerEntity p : around) {
            boolean bothWorking = busy && (!(p instanceof NpcPlayer o) || o.brain() != null && o.brain().currentTask() != null);
            event(bothWorking ? SocialEvent.WORKED_TOGETHER : SocialEvent.TIME_TOGETHER, p, bothWorking ? 0.4 : 1, null);
        }
        if (npc.nearbyPlayers(32, true).isEmpty()) {
            if (++lonelyMinutes > 8) mind().feel("lonely", 0.25 * mind().temperament().warmth());
        } else {
            lonelyMinutes = 0;
        }
    }

    /** A player or NPC killed the monster that was attacking us: they saved us. */
    private void checkRescue() {
        LivingEntity m = lastMonsterAttacker;
        if (m == null) return;
        if (npc.server().getTicks() - lastMonsterAttackTick > 200) {
            lastMonsterAttacker = null;
            return;
        }
        if (m.isAlive()) return;
        lastMonsterAttacker = null;
        LivingEntity killer = m.getAttacker();
        if (killer instanceof PlayerEntity && killer != npc.npc()) {
            event(SocialEvent.SAVED, killer, npc.npc().getHealth() < 10 ? 1.5 : 0.8, "from a " + m.getType().getName().getString().toLowerCase(Locale.ROOT));
        }
    }

    private void particles() {
        SocialMind m = mind();
        ParticleEffect effect = null;
        if (m.get(Emotion.ANGER) > 0.6) effect = ParticleTypes.ANGRY_VILLAGER;
        else if (m.get(Emotion.SADNESS) > 0.6) effect = ParticleTypes.FALLING_WATER;
        else if (m.get(Emotion.FEAR) > 0.7) effect = ParticleTypes.SPLASH;
        else if (m.get(Emotion.JOY) > 0.75) effect = ParticleTypes.HAPPY_VILLAGER;
        else if (npc.random().nextInt(6) == 0) {
            for (ServerPlayerEntity p : npc.nearbyPlayers(5, true)) {
                if (m.affinity(key(p)) > 70) {
                    effect = ParticleTypes.HEART;
                    break;
                }
            }
        }
        if (effect == null || npc.random().nextInt(3) != 0) return;
        var p = npc.npc();
        npc.world().spawnParticles(effect, p.getX(), p.getEyeY() + 0.5, p.getZ(), effect == ParticleTypes.FALLING_WATER ? 4 : 2, 0.25, 0.1, 0.25, 0);
    }

    // ------------------------------------------------------------------ behavior

    /**
     * Lets strong feelings take over when the NPC isn't doing something it was asked to: keeping away from
     * someone it's afraid of, or settling a score with someone it's furious at. Returns a reflex task or null.
     */
    @Nullable
    public dev.mcattle.task.Task impulse() {
        if (npc.age() % 40 != 21) return null;
        SocialMind m = mind();
        for (ServerPlayerEntity p : npc.nearbyPlayers(8, true)) {
            Relationship r = m.relation(key(p));
            if (r == null || p.isCreative() || p.isSpectator()) continue;
            double dist = p.distanceTo(npc.npc());
            if (r.fear > 55 && dist < 5 && m.get(Emotion.FEAR) > 0.3) {
                if (npc.random().nextInt(3) == 0) npc.say(Lines.scared(npc, p.getName().getString()));
                return new FleeTask(p, 10, 80, "fear");
            }
            boolean mayFight = npc.identity().retaliate != null ? npc.identity().retaliate : npc.config().npc.retaliate;
            if (mayFight && r.affinity < -55 && m.get(Emotion.ANGER) > 0.55 && dist < 6 && npc.server().isPvpEnabled()
                    && npc.age() - lastGrudgeTick > 20 * 60 * 3 && npc.random().nextInt(4) == 0) {
                lastGrudgeTick = npc.age();
                npc.say(Lines.grudge(npc, p.getName().getString()));
                return new AttackTask(Target.of(p), false, 10, true);
            }
        }
        return null;
    }

    /** Someone it likes enough to seek out, if they're around (for free play). */
    @Nullable
    public ServerPlayerEntity friendNearby(double range) {
        ServerPlayerEntity best = null;
        double bestScore = 35;
        for (ServerPlayerEntity p : npc.nearbyPlayers(range, true)) {
            double a = mind().affinity(key(p));
            if (a > bestScore) {
                bestScore = a;
                best = p;
            }
        }
        return best;
    }

    /** True if the NPC can't stand this player enough to refuse an order (built-in replies only; the model decides itself). */
    public boolean refuses(ServerPlayerEntity speaker) {
        Relationship r = mind().relation(key(speaker));
        if (r == null) return false;
        double resentment = -r.affinity - r.trust * 0.3 + mind().get(Emotion.ANGER) * 30;
        return resentment > 60 && npc.random().nextDouble() < Math.min(0.9, (resentment - 50) / 50);
    }

    public double affinity(Entity other) {
        return mind().affinity(key(other));
    }

    // ------------------------------------------------------------------ reports for the language model

    /** Mood, temperament and the relationships that matter right now, for the situation report. */
    public String report(Collection<? extends Entity> relevant, int maxOthers) {
        long now = now();
        SocialMind m = mind();
        StringBuilder sb = new StringBuilder();
        sb.append("  feels: ").append(m.describeMood(now)).append(" | temperament: ").append(m.temperament().describe()).append("\n");
        Set<String> shown = new LinkedHashSet<>();
        for (Entity e : relevant) {
            if (e == npc.npc()) continue;
            Relationship r = m.relation(key(e));
            if (r == null) {
                sb.append("  toward ").append(e.getName().getString()).append(": stranger\n");
            } else {
                sb.append("  toward ").append(r.name).append(": ").append(r.describe(4)).append("\n");
            }
            shown.add(key(e));
        }
        int extra = 0;
        for (var entry : m.strongest(maxOthers + shown.size())) {
            if (shown.contains(entry.getKey()) || extra >= maxOthers) continue;
            Relationship r = entry.getValue();
            if (Math.abs(r.affinity) < 15 && r.fear < 20) continue;
            sb.append("  toward ").append(r.name).append(" (not here): ").append(r.describe(2)).append("\n");
            extra++;
        }
        return sb.toString();
    }

    /** One short line: "annoyed; sees Steve as a rival". */
    public String brief(@Nullable Entity toward) {
        StringBuilder sb = new StringBuilder(mind().moodWords());
        if (toward != null) {
            Relationship r = mind().relation(key(toward));
            if (r != null) sb.append("; sees ").append(r.name).append(" as ").append(r.label());
        }
        return sb.toString();
    }

    public List<String> describeAll() {
        List<String> out = new ArrayList<>();
        out.add("mood: " + mind().describeMood(now()));
        out.add("temperament: " + mind().temperament().describe());
        for (var e : mind().strongest(10)) out.add(e.getValue().name + ": " + e.getValue().describe(5));
        return out;
    }
}
