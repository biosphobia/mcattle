package dev.mcattle.npc;

import dev.mcattle.control.ItemQuery;
import dev.mcattle.control.ItemResolver;
import dev.mcattle.core.social.TraitSet;
import dev.mcattle.task.FleeTask;
import dev.mcattle.task.ObtainTask;
import dev.mcattle.task.PlaceNearTask;
import dev.mcattle.task.SequenceTask;
import dev.mcattle.task.Task;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Minecraft side of an NPC's traits: experiences that build them up (dying to something, being given the
 * same thing again and again, doing a job a lot, being praised or mocked) and the few traits the body acts on
 * directly (keeping away from what it fears, going after what it adores). Everything else reaches its behavior
 * through the language model, which sees the strongest traits in every request.
 */
public final class NpcTraits {
    private static final Pattern FEAR = Pattern.compile("\\b(afraid of|scared of|terrified of|fear of|phobia of|hates?|can't stand)\\s+(.+)");
    private static final Pattern LOVE = Pattern.compile("\\b(loves?|adores?|likes?|obsessed with|fond of|collects?|in love with|addicted to|worships?)\\s+(.+)");

    private final NpcBrain npc;
    private final Map<String, Object> resolved = new HashMap<>();

    public NpcTraits(NpcBrain npc) {
        this.npc = npc;
    }

    public TraitSet set() {
        NpcIdentity id = npc.identity();
        if (id.traits == null) id.traits = new TraitSet();
        return id.traits;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /** Applies the model's "traits" field. */
    public void apply(@Nullable String field) {
        for (TraitSet.Change c : TraitSet.parse(field)) {
            if (c.remove()) set().remove(c.text());
            else set().reinforce(c.text(), c.amount(), now());
        }
    }

    public void tick() {
        if (npc.age() % 1200 == 0) set().tick(now());
    }

    // ------------------------------------------------------------------ experiences

    public void died(DamageSource source) {
        Entity attacker = source.getAttacker();
        String cause;
        if (attacker instanceof NpcPlayer || attacker instanceof net.minecraft.entity.player.PlayerEntity) return; // that's a relationship thing
        if (attacker != null) cause = attacker.getType().getName().getString().toLowerCase(Locale.ROOT) + "s";
        else cause = source.getName().replace('_', ' ').replace("inFire", "fire").replace("onFire", "fire").replace("inWall", "tight spaces")
                .replace("lava", "lava").replace("fall", "heights").replace("drown", "deep water");
        set().experience("death:" + cause, 1, 2, "afraid of " + cause, 0.35, now());
    }

    public void gotGift(ItemStack stack) {
        String name = Registries.ITEM.getId(stack.getItem()).getPath().replace('_', ' ');
        set().experience("gift:" + name, 1, 3, "fond of " + name, 0.2, now());
    }

    public void ate(ItemStack stack) {
        String name = Registries.ITEM.getId(stack.getItem()).getPath().replace('_', ' ');
        set().experience("ate:" + name, 1, 8, "loves eating " + name, 0.15, now());
    }

    public void killed(LivingEntity victim) {
        if (victim instanceof net.minecraft.entity.player.PlayerEntity) return;
        String name = victim.getType().getName().getString().toLowerCase(Locale.ROOT);
        set().experience("kill:" + name, 1, 12, "seasoned " + name + " slayer", 0.2, now());
    }

    /** A finished job: doing the same kind of thing a lot becomes something it enjoys. */
    public void did(Task task) {
        String d = task.describe() == null ? "" : task.describe().toLowerCase(Locale.ROOT);
        String kind = d.contains("mining") || d.contains("mine ") ? "mining"
                : d.contains("chop") ? "chopping trees"
                : d.contains("build") ? "building"
                : d.contains("farm") ? "farming"
                : d.contains("craft") ? "crafting"
                : d.contains("attack") || d.contains("fight") ? "fighting"
                : d.contains("danc") ? "dancing" : null;
        if (kind != null) set().experience("did:" + kind, 1, 6, "enjoys " + kind, 0.15, now());
    }

    public void praised(boolean praise) {
        if (praise) set().experience("praised", 1, 4, "confident", 0.2, now());
        else set().experience("mocked", 1, 4, "insecure about being useless", 0.2, now());
    }

    // ------------------------------------------------------------------ acting on traits

    /** Entity types it is afraid of (from traits like "afraid of creepers"), with strength. */
    private List<Map.Entry<EntityType<?>, Double>> fears() {
        List<Map.Entry<EntityType<?>, Double>> out = new ArrayList<>();
        for (TraitSet.Trait t : set().traits()) {
            if (t.strength < 0.35) continue;
            Matcher m = FEAR.matcher(t.text.toLowerCase(Locale.ROOT));
            if (!m.find()) continue;
            Object r = resolved.computeIfAbsent("fear:" + t.text, k -> entityType(m.group(2)));
            if (r instanceof EntityType<?> type) out.add(Map.entry(type, t.strength));
        }
        return out;
    }

    @Nullable
    private static EntityType<?> entityType(String phrase) {
        String p = phrase.toLowerCase(Locale.ROOT);
        EntityType<?> best = null;
        int bestLen = 0;
        for (EntityType<?> type : Registries.ENTITY_TYPE) {
            String id = Registries.ENTITY_TYPE.getId(type).getPath();
            String spaced = id.replace('_', ' ');
            if ((p.contains(spaced) || p.contains(id)) && spaced.length() > bestLen && type.getSpawnGroup() != net.minecraft.entity.SpawnGroup.MISC) {
                best = type;
                bestLen = spaced.length();
            }
        }
        return best;
    }

    /** Runs away from something it is afraid of, if one is close. */
    @Nullable
    public Task fearReflex() {
        if (npc.age() % 20 != 9) return null;
        for (var fear : fears()) {
            double range = 4 + fear.getValue() * 6;
            for (Entity e : npc.world().getOtherEntities(npc.npc(), npc.box().expand(range), x -> x.getType() == fear.getKey() && x.isAlive())) {
                if (npc.random().nextDouble() < 0.4) npc.say(npc.random().nextBoolean() ? "NOPE" : "get it away from me");
                npc.social().mind().feel("scared", 1);
                return new FleeTask(e, 12, 80, "afraid");
            }
        }
        return null;
    }

    /** Something it adores (an item or block from a trait like "obsessed with diamond blocks"), with strength. */
    @Nullable
    private Map.Entry<ItemQuery, TraitSet.Trait> favorite() {
        for (TraitSet.Trait t : set().strongest(8)) {
            if (t.strength < 0.4) continue;
            Matcher m = LOVE.matcher(t.text.toLowerCase(Locale.ROOT));
            if (!m.find()) continue;
            Object r = resolved.computeIfAbsent("love:" + t.text, k -> {
                String what = m.group(2).replaceAll("\\b(eating|the|some|any|all)\\b", "").trim();
                ItemQuery q = ItemResolver.resolve(what);
                if (q == null && what.endsWith("s")) q = ItemResolver.resolve(what.substring(0, what.length() - 1));
                return q == null ? "none" : q;
            });
            if (r instanceof ItemQuery q) return Map.entry(q, t);
        }
        return null;
    }

    /** True if this item is something it adores (a gift of it makes its day). */
    public boolean adores(ItemStack stack) {
        var fav = favorite();
        return fav != null && fav.getKey().test(stack);
    }

    /** Free time: go get some of the thing it adores and, if it's a block, put one down to admire. */
    @Nullable
    public Task indulge() {
        var fav = favorite();
        if (fav == null || npc.random().nextDouble() > fav.getValue().strength * 0.5) return null;
        ItemQuery q = fav.getKey();
        int have = npc.inventory().count(q);
        List<Task> steps = new ArrayList<>();
        if (have < 4) steps.add(new ObtainTask(q, have + 1 + npc.random().nextInt(4)));
        if (q.preferred() instanceof BlockItem && npc.random().nextBoolean()) steps.add(new PlaceNearTask(q));
        if (steps.isEmpty()) return null;
        return new SequenceTask(steps, "indulging in " + q.describe());
    }

    /** The strongest traits, for situation reports. */
    public String describe(int max) {
        return set().describe(max);
    }
}
