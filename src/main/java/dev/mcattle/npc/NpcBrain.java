package dev.mcattle.npc;

import dev.mcattle.MCattle;
import dev.mcattle.config.MCattleConfig;
import dev.mcattle.control.InteractionControl;
import dev.mcattle.control.InventoryControl;
import dev.mcattle.control.ItemResolver;
import dev.mcattle.control.MovementControl;
import dev.mcattle.control.Navigator;
import dev.mcattle.task.AttackTask;
import dev.mcattle.task.AutonomousPlanner;
import dev.mcattle.task.EatTask;
import dev.mcattle.task.FleeTask;
import dev.mcattle.task.SocialBehavior;
import dev.mcattle.task.Target;
import dev.mcattle.task.Task;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Decision making for one NPC: runs the current order queue, falls back to autonomous survival play, handles
 * reflexes (eat, fight back, flee from lava or creepers, swim up for air), social gestures and chat.
 */
public final class NpcBrain {
    private final NpcPlayer npc;
    private final NpcIdentity identity;
    private final NpcManager manager;
    private final MovementControl move;
    private final InventoryControl inventory;
    private final InteractionControl hands;
    private final Navigator nav;
    private final SocialBehavior social;
    private final NpcSocial feelings;
    private final StatusIndicator indicator;
    private final NpcTraits traits;
    private final AutonomousPlanner planner;
    private final Random random = new Random();

    private final Deque<Task> orders = new ArrayDeque<>();
    @Nullable private Task current;
    @Nullable private Task reflex;
    private boolean autonomous;
    @Nullable private UUID listeningTo;
    private int listeningTicks;
    private int thinkingTicks;
    @Nullable private UUID lastSpeaker;
    private long lastSayTick;
    private final Deque<String> pendingChat = new ArrayDeque<>();
    private final Deque<String> memory = new ArrayDeque<>();
    private int idleTicks;
    private long ticksAlive;
    private int errorCount;
    /** The player request the current orders serve; kept until done so failures and deaths lead to new tries. */
    @Nullable private String goal;
    @Nullable private UUID goalSpeaker;
    private int goalRetries;
    private boolean resumeAfterRespawn;
    private static final int MAX_RETRIES = 3;

    public NpcBrain(NpcPlayer npc, NpcIdentity identity, NpcManager manager) {
        this.npc = npc;
        this.identity = identity;
        this.manager = manager;
        this.move = new MovementControl(npc);
        this.inventory = new InventoryControl(npc);
        this.hands = new InteractionControl(npc, move, inventory);
        this.nav = new Navigator(npc, move, hands, inventory);
        this.social = new SocialBehavior(this);
        this.feelings = new NpcSocial(this);
        this.indicator = new StatusIndicator(this);
        this.traits = new NpcTraits(this);
        this.planner = new AutonomousPlanner(this);
        this.autonomous = identity.autonomous;
    }

    // ------------------------------------------------------------------ accessors

    public NpcPlayer npc() { return npc; }
    public NpcIdentity identity() { return identity; }
    public NpcManager manager() { return manager; }
    public MovementControl move() { return move; }
    public InventoryControl inventory() { return inventory; }
    public InteractionControl hands() { return hands; }
    public Navigator nav() { return nav; }
    public Random random() { return random; }
    /** Emotions and relationships. */
    public NpcSocial social() { return feelings; }
    public String name() { return identity.name; }
    public MCattleConfig config() { return manager.config(); }
    public MinecraftServer server() { return npc.getServer(); }
    public ServerWorld world() { return npc.getServerWorld(); }
    public long age() { return ticksAlive; }
    public boolean isAutonomous() { return autonomous; }

    public BlockPos home() {
        return new BlockPos(identity.homeX, identity.homeY, identity.homeZ);
    }

    @Nullable
    public ServerPlayerEntity lastSpeaker() {
        return lastSpeaker == null ? null : server().getPlayerManager().getPlayer(lastSpeaker);
    }

    // ------------------------------------------------------------------ orders

    /** Replaces (or appends to) the order queue. An empty list with replace=true just stops. */
    public void giveOrders(List<Task> tasks, boolean replace, @Nullable ServerPlayerEntity speaker) {
        if (speaker != null) lastSpeaker = speaker.getUuid();
        if (replace) {
            clearOrders();
        } else if (current != null && (current.isOpenEnded() || planner.owns(current))) {
            // free-play activity or "follow me" can't be queued behind: drop it for the new orders
            stopCurrent();
        }
        orders.addAll(tasks);
        idleTicks = 0;
    }

    public void clearOrders() {
        stopCurrent();
        for (Task t : orders) t.stop(this);
        orders.clear();
    }

    private void stopCurrent() {
        if (current != null) {
            current.stop(this);
            current = null;
        }
        nav.stop();
        hands.cancelBreaking();
    }

    public void setAutonomous(boolean value) {
        autonomous = value;
        identity.autonomous = value;
        manager.saveRegistry();
    }

    @Nullable
    public Task currentTask() {
        return reflex != null ? reflex : current;
    }

    /** The current order if it is waiting for a player to do something. */
    @Nullable
    public dev.mcattle.task.WaitForTask waiting() {
        return current instanceof dev.mcattle.task.WaitForTask w ? w : null;
    }

    /** True if the task is the current order or still queued (reflexes like eating don't count as replacing it). */
    public boolean hasTask(Task task) {
        return current == task || orders.contains(task);
    }

    public List<Task> queuedTasks() {
        return new ArrayList<>(orders);
    }

    public StatusIndicator indicator() { return indicator; }
    /** Personality traits from experiences and players. */
    public NpcTraits traits() { return traits; }

    /** What floats above its head: "♪ listening", "✎ thinking..", "⛏ mining iron ore", or "" when idle. */
    public String statusLine() {
        if (listeningTo != null) return "♪ listening";
        if (thinkingTicks > 0) return "✎ thinking" + ".".repeat(1 + (int) (ticksAlive / 10 % 3));
        if (npc.isSleeping()) return "☾ sleeping";
        Task t = currentTask();
        return t == null ? "" : StatusIndicator.compact(t.describeDeep());
    }

    /** One-line status for the language model and /npc list. */
    public String activity() {
        if (!npc.isAlive()) return "dead, respawning";
        if (listeningTo != null) return "listening to a player";
        if (npc.isSleeping()) return "sleeping";
        Task t = currentTask();
        String s = t == null ? (autonomous ? "idle (free play)" : "standing by") : t.describeDeep();
        if (current != null && current == t && planner.owns(current)) s = "free play: " + s;
        if (!orders.isEmpty()) s += " | then: " + orders.size() + " more";
        return s;
    }

    // ------------------------------------------------------------------ listening (push-to-talk)

    public void startListening(ServerPlayerEntity speaker) {
        listeningTo = speaker.getUuid();
        listeningTicks = 20 * 30;
    }

    public void stopListening() {
        listeningTo = null;
    }

    public void stopListening(UUID speaker) {
        if (speaker.equals(listeningTo)) listeningTo = null;
    }

    // ------------------------------------------------------------------ goal (persistence)

    @Nullable
    public String goal() {
        return goal;
    }

    /** Remembers what the player asked for, so the NPC keeps at it after setbacks. */
    public void setGoal(@Nullable String text, @Nullable ServerPlayerEntity speaker) {
        goal = text == null || text.isBlank() ? null : text;
        goalSpeaker = speaker == null ? null : speaker.getUuid();
        goalRetries = 0;
        resumeAfterRespawn = false;
    }

    public void setGoalRetry() {
        goalRetries++;
    }

    public int goalRetries() {
        return goalRetries;
    }

    /** Asks the planner for another way to reach the goal. False if that isn't possible (no goal / model / speaker). */
    private boolean persist(String problem) {
        if (goal == null || goalSpeaker == null || goalRetries >= MAX_RETRIES) return false;
        ServerPlayerEntity speaker = server().getPlayerManager().getPlayer(goalSpeaker);
        if (speaker == null) return false;
        return manager.mod().router().retry(this, speaker, problem);
    }

    public void setThinking(int ticks) {
        thinkingTicks = ticks;
    }

    // ------------------------------------------------------------------ chat

    public void say(String message) {
        if (message == null || message.isBlank()) return;
        String clean = message.replace('\n', ' ').trim();
        if (clean.length() > 220) clean = clean.substring(0, 220) + "...";
        pendingChat.add(clean);
        remember(name() + ": " + clean);
    }

    public void remember(String line) {
        memory.addLast(line);
        while (memory.size() > 6) memory.removeFirst();
    }

    public List<String> memory() {
        return new ArrayList<>(memory);
    }

    private void flushChat() {
        if (pendingChat.isEmpty()) return;
        long now = server().getTicks();
        if (now - lastSayTick < 25) return; // don't spam several lines in the same instant
        lastSayTick = now;
        manager.broadcastChat(this, pendingChat.poll());
    }

    // ------------------------------------------------------------------ main loop

    public void tick() {
        ticksAlive++;
        move.reset();
        flushChat();
        indicator.tick();

        if (ticksAlive % 40 == 0) {
            inventory.equipBestArmor();
            inventory.equipShield();
            if (inventory.isFull()) inventory.dropJunk();
        }

        // swim up when running out of air, wherever we are going
        if (npc.isSubmergedInWater() && npc.getAir() < npc.getMaxAir() / 2) move.jump();

        if (listeningTo != null) {
            ServerPlayerEntity speaker = server().getPlayerManager().getPlayer(listeningTo);
            if (speaker == null || --listeningTicks <= 0) {
                listeningTo = null;
            } else {
                hands.cancelBreaking();
                hands.releaseItem();
                move.lookAt(speaker);
                if (npc.isTouchingWater()) move.jump();
                move.apply();
                return;
            }
        }
        if (thinkingTicks > 0) thinkingTicks--;

        try {
            feelings.tick();
            traits.tick();
            checkReflexes();
            if (reflex != null) {
                Task.Status s = reflex.tick(this);
                if (s != Task.Status.RUNNING) {
                    reflex.stop(this);
                    reflex = null;
                    nav.stop();
                }
            } else {
                runOrders();
            }
            social.tick(current == null && orders.isEmpty() && reflex == null);
        } catch (RuntimeException e) {
            logError("task", e);
            clearOrders();
            reflex = null;
        }
        move.apply();
    }

    private void runOrders() {
        if (current == null) {
            current = orders.poll();
            if (current == null && autonomous && thinkingTicks <= 0 && npc.getHungerManager() != null) {
                if (++idleTicks > 30) {
                    current = planner.next();
                    idleTicks = 0;
                }
            }
        }
        if (current == null) return;
        Task.Status status = current.tick(this);
        if (status == Task.Status.RUNNING) return;
        Task finished = current;
        current = null;
        finished.stop(this);
        nav.stop();
        boolean fromPlayer = !planner.owns(finished);
        if (status == Task.Status.SUCCESS) traits.did(finished);
        if (status == Task.Status.SUCCESS && finished instanceof dev.mcattle.task.WaitForTask w && w.happened() != null
                && w.wantsFollowUp(orders.isEmpty())) {
            // the player did their part: ask what to do about it (no waiting for the rest of a stale plan)
            ServerPlayerEntity who = w.player(this);
            if (who == null) who = lastSpeaker();
            if (who != null && manager.mod().router().followUp(this, who, w.happened())) {
                for (Task t : orders) t.stop(this);
                orders.clear();
                return;
            }
        }
        planner.onFinished(finished, status);
        if (status == Task.Status.FAILURE && fromPlayer) {
            String reason = finished.failureReason();
            String problem = reason != null ? reason : "couldn't " + finished.describe();
            feelings.mind().feel("frustrated", 0.5);
            for (Task t : orders) t.stop(this);
            orders.clear(); // the rest of the plan probably depended on this step
            // don't give up at the first problem: think of another way (quietly) and keep going
            if (!persist(problem)) {
                say(problem);
                goal = null;
            }
        } else if (status == Task.Status.SUCCESS && fromPlayer && orders.isEmpty()) {
            goal = null; // done
            feelings.mind().feel("proud", 0.4);
            ServerPlayerEntity asker = lastSpeaker();
            if (asker != null && asker.distanceTo(npc) < 32) feelings.event(dev.mcattle.core.social.SocialEvent.WORKED_TOGETHER, asker, 1, null);
            if (finished.describe() != null && random.nextInt(3) == 0 && !(finished instanceof dev.mcattle.task.SayTask)) {
                say(Lines.done(this));
            }
        }
    }

    private void checkReflexes() {
        if (reflex != null || current instanceof dev.mcattle.task.DieTask) return; // told to die: no saving itself
        // strong feelings (fear of someone, a grudge) take over unless busy with something a player asked for
        if (current == null || planner.owns(current) || current.isOpenEnded()) {
            Task impulse = feelings.impulse();
            if (impulse == null) impulse = traits.fearReflex();
            if (impulse != null) {
                startReflex(impulse);
                return;
            }
        }
        // lava / fire: get out first, everything else later
        if (npc.isInLava() || (npc.isOnFire() && !npc.isTouchingWater() && npc.getHealth() < 14)) {
            startReflex(new FleeTask(null, 6, 60, "lava"));
            return;
        }
        // creepers about to blow
        if (ticksAlive % 5 == 0) {
            for (CreeperEntity creeper : world().getEntitiesByClass(CreeperEntity.class, npc.getBoundingBox().expand(5),
                    c -> c.getFuseSpeed() > 0)) {
                startReflex(new FleeTask(creeper, 8, 50, "creeper"));
                return;
            }
        }
        // hungry and safe: eat
        if (ticksAlive % 20 == 0 && npc.getHungerManager().getFoodLevel() <= 14 && !(current instanceof AttackTask)
                && inventory.foodCount() > 0 && !npc.isUsingItem()) {
            startReflex(new EatTask(false));
        }
    }

    private void startReflex(Task task) {
        nav.stop();
        hands.cancelBreaking();
        reflex = task;
    }

    public void onHurt(DamageSource source, float amount) {
        Entity attacker = source.getAttacker();
        if (!(attacker instanceof LivingEntity living) || attacker == npc || !npc.isAlive()) return;
        feelings.onHurt(attacker, amount, isRequestedFight(attacker));
        if (current instanceof AttackTask at && at.isTargeting(attacker)) return;
        if (reflex instanceof AttackTask at && at.isTargeting(attacker)) return;
        if (attacker instanceof Monster) {
            if (npc.getHealth() < 6 && !inventory.has(s -> InventoryControl.weaponScore(s) > 0)) {
                startReflex(new FleeTask(attacker, 16, 100, "monster"));
            } else {
                startReflex(new AttackTask(Target.of(attacker), true, 30, true));
            }
        } else if (attacker instanceof PlayerEntity player
                && (identity.retaliate != null ? identity.retaliate : config().npc.retaliate)) {
            if (manager.byUuid(player.getUuid()) != null && current instanceof AttackTask) return; // NPC brawl in progress
            say(Lines.hit(this, player.getName().getString()));
            var rel = feelings.mind().relation(NpcSocial.key(player));
            double fear = rel == null ? 0 : rel.fear;
            if (fear > 60 && npc.getHealth() < 14) {
                startReflex(new FleeTask(attacker, 14, 100, "scared"));          // too afraid of them to fight back
            } else if (rel == null || rel.affinity < 50 || rel.hits >= 3) {
                startReflex(new AttackTask(Target.of(attacker), false, 12, true)); // a friend gets a pass, at first
            }
        }
    }

    /** True while fighting this entity because a player asked for it (a spar or a duel, not an attack out of the blue). */
    private boolean isRequestedFight(Entity other) {
        if (current instanceof AttackTask at && at.isTargeting(other) && !planner.owns(current)) return true;
        return other instanceof NpcPlayer o && o.brain() != null && o.brain().current instanceof AttackTask at2 && at2.isTargeting(npc)
                && !o.brain().planner.owns(o.brain().current);
    }

    public void onDeath(DamageSource source) {
        traits.died(source);
        Entity killer = source.getAttacker();
        if (killer != null) feelings.onDeath(killer, isRequestedFight(killer));
        else feelings.onDeath(null, false);
        resumeAfterRespawn = goal != null && (current != null || !orders.isEmpty()) && !planner.owns(current);
        clearOrders();
        reflex = null;
        listeningTo = null;
        if (random.nextBoolean()) pendingChat.add(Lines.death(this));
    }

    public void onDeadTick() {
        ticksAlive++;
        indicator.tick();
        move.reset();
        flushChat();
    }

    public void onRespawn() {
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, new Identifier(identity.homeDimension));
        ServerWorld homeWorld = server().getWorld(key);
        if (homeWorld == null) homeWorld = server().getOverworld();
        BlockPos h = home();
        npc.teleport(homeWorld, h.getX() + 0.5, h.getY(), h.getZ() + 0.5, npc.getYaw(), 0);
        if (resumeAfterRespawn) {
            resumeAfterRespawn = false;
            // pick the job back up (inventory was probably lost)
            if (!persist("died and respawned at home, inventory may be gone")) goal = null;
        }
    }

    public void logError(String where, Exception e) {
        if (errorCount++ < 20) MCattle.LOGGER.warn("NPC {} error in {}", name(), where, e);
    }

    // ------------------------------------------------------------------ perception helpers

    public List<LivingEntity> nearbyHostiles(double radius) {
        return world().getEntitiesByClass(LivingEntity.class, npc.getBoundingBox().expand(radius),
                e -> e instanceof Monster && e.isAlive());
    }

    public List<ServerPlayerEntity> nearbyPlayers(double radius, boolean includeNpcs) {
        List<ServerPlayerEntity> list = new ArrayList<>();
        for (ServerPlayerEntity p : world().getPlayers()) {
            if (p == npc || p.isSpectator()) continue;
            if (!includeNpcs && p instanceof NpcPlayer) continue;
            if (p.squaredDistanceTo(npc) <= radius * radius) list.add(p);
        }
        return list;
    }

    public String inventorySummary(int maxEntries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack s : npc.getInventory().main) {
            if (s.isEmpty()) continue;
            String key = net.minecraft.registry.Registries.ITEM.getId(s.getItem()).getPath();
            if (s.isDamageable()) key += "(" + (s.getMaxDamage() - s.getDamage()) + "/" + s.getMaxDamage() + ")";
            counts.merge(key, s.getCount(), Integer::sum);
        }
        for (ItemStack s : npc.getInventory().armor) if (!s.isEmpty()) counts.merge("wearing " + ItemResolver.describe(s), 1, Integer::sum);
        if (!npc.getOffHandStack().isEmpty()) counts.merge("offhand " + ItemResolver.describe(npc.getOffHandStack()), 1, Integer::sum);
        if (counts.isEmpty()) return "empty";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (n++ >= maxEntries) {
                sb.append(", ...");
                break;
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey());
            if (e.getValue() > 1) sb.append(" x").append(e.getValue());
        }
        return sb.toString();
    }

    public Vec3d pos() {
        return npc.getPos();
    }

    public Box box() {
        return npc.getBoundingBox();
    }
}
