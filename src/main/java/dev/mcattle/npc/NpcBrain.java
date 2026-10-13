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

    public NpcBrain(NpcPlayer npc, NpcIdentity identity, NpcManager manager) {
        this.npc = npc;
        this.identity = identity;
        this.manager = manager;
        this.move = new MovementControl(npc);
        this.inventory = new InventoryControl(npc);
        this.hands = new InteractionControl(npc, move, inventory);
        this.nav = new Navigator(npc, move, hands, inventory);
        this.social = new SocialBehavior(this);
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

    public List<Task> queuedTasks() {
        return new ArrayList<>(orders);
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
        planner.onFinished(finished, status);
        if (status == Task.Status.FAILURE && fromPlayer) {
            String reason = finished.failureReason();
            say(reason != null ? reason : "couldn't " + finished.describe());
            orders.clear(); // the rest of the plan probably depended on this step
        } else if (status == Task.Status.SUCCESS && fromPlayer && orders.isEmpty() && finished.describe() != null
                && random.nextInt(3) == 0 && !(finished instanceof dev.mcattle.task.SayTask)) {
            say(Lines.done(random));
        }
    }

    private void checkReflexes() {
        if (reflex != null) return;
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
        if (current instanceof AttackTask at && at.isTargeting(attacker)) return;
        if (reflex instanceof AttackTask at && at.isTargeting(attacker)) return;
        if (attacker instanceof Monster) {
            if (npc.getHealth() < 6 && !inventory.has(s -> InventoryControl.weaponScore(s) > 0)) {
                startReflex(new FleeTask(attacker, 16, 100, "monster"));
            } else {
                startReflex(new AttackTask(Target.of(attacker), true, 30, true));
            }
        } else if (attacker instanceof PlayerEntity player && config().npc.retaliate) {
            if (manager.byUuid(player.getUuid()) != null && current instanceof AttackTask) return; // NPC brawl in progress
            say(Lines.hit(random, player.getName().getString()));
            startReflex(new AttackTask(Target.of(attacker), false, 12, true));
        }
    }

    public void onDeath(DamageSource source) {
        clearOrders();
        reflex = null;
        listeningTo = null;
        if (random.nextBoolean()) pendingChat.add(Lines.death(random));
    }

    public void onDeadTick() {
        move.reset();
        flushChat();
    }

    public void onRespawn() {
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, new Identifier(identity.homeDimension));
        ServerWorld homeWorld = server().getWorld(key);
        if (homeWorld == null) homeWorld = server().getOverworld();
        BlockPos h = home();
        npc.teleport(homeWorld, h.getX() + 0.5, h.getY(), h.getZ() + 0.5, npc.getYaw(), 0);
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
