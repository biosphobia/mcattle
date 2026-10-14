package dev.mcattle.ai;

import dev.mcattle.MCattle;
import dev.mcattle.core.llm.ActivityTurn;
import dev.mcattle.core.llm.LlmService;
import dev.mcattle.core.llm.Orders;
import dev.mcattle.npc.NpcBrain;
import dev.mcattle.npc.NpcPlayer;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * An open-ended, multi-round activity (a game, a party, a contest, roleplay...). A "director" language-model call
 * decides each round what every participant says and does, keeps its own notes (rules, board, score), watches
 * an area for block changes and listens to chat, and is called again when the step it asked to wait for happens.
 */
public final class ActivitySession {
    private static final int MIN_TICKS_BETWEEN_CALLS = 60;

    private final MCattle mod;
    final String goal;
    final UUID host;
    final List<NpcBrain> participants = new ArrayList<>();
    final Map<NpcBrain, ActivityTask> tasks = new HashMap<>();
    String notes = "";
    int round;
    final long startTick;
    private long lastCallTick = -1000;
    private long wakeAtTick;
    private String waitFor = "steps";
    private boolean pending;
    private boolean ended;
    private boolean finishing;
    @Nullable BlockBox watch;
    private Map<Long, BlockState> snapshot = new HashMap<>();
    final Deque<String> events = new ArrayDeque<>();
    private boolean chatEvent;
    private boolean blockEvent;
    private int failedCalls;

    ActivitySession(MCattle mod, String goal, ServerPlayerEntity host) {
        this.mod = mod;
        this.goal = goal;
        this.host = host.getUuid();
        this.startTick = mod.server().getTicks();
    }

    public String goal() {
        return goal;
    }

    public boolean isEnded() {
        return ended;
    }

    public boolean isFinishing() {
        return finishing || ended;
    }

    @Nullable
    ServerPlayerEntity host() {
        return mod.server().getPlayerManager().getPlayer(host);
    }

    ActivityTask join(NpcBrain npc) {
        ActivityTask existing = tasks.get(npc);
        if (existing != null) return existing;
        ActivityTask task = new ActivityTask(this, npc);
        participants.add(npc);
        tasks.put(npc, task);
        return task;
    }

    void leave(NpcBrain npc) {
        participants.remove(npc);
        tasks.remove(npc);
    }

    boolean involves(NpcBrain npc) {
        return participants.contains(npc);
    }

    void event(String text) {
        events.addLast(text);
        while (events.size() > 25) events.removeFirst();
    }

    void onChat(ServerPlayerEntity player, String text) {
        event("<" + player.getName().getString() + "> " + text);
        chatEvent = true;
    }

    void onStepsDone(NpcBrain npc, @Nullable String failure) {
        if (failure != null) event(npc.name() + " couldn't do it: " + failure);
    }

    void end(String reason) {
        if (ended) return;
        ended = true;
        MCattle.LOGGER.info("Activity '{}' ended: {}", goal, reason);
        // shared fun brings people closer
        if (round > 0) {
            ServerPlayerEntity h = host();
            for (NpcBrain npc : participants) {
                if (h != null) npc.social().event(dev.mcattle.core.social.SocialEvent.PLAYED_WITH, h, 1, null);
                for (NpcBrain other : participants) {
                    if (other != npc) npc.social().event(dev.mcattle.core.social.SocialEvent.PLAYED_WITH, other.npc(), 0.7, null);
                }
            }
        }
    }

    // ------------------------------------------------------------------ main loop

    void tick() {
        if (ended) return;
        // participants that were given other orders have left
        participants.removeIf(p -> p.npc().isRemoved() || !p.npc().isAlive() && !p.npc().isWaitingToRespawn() || !isRunning(p));
        if (participants.isEmpty()) {
            end("no participants left");
            return;
        }
        long now = mod.server().getTicks();
        var cfg = mod.config().llm;
        if (finishing) {
            if (tasks.values().stream().allMatch(ActivityTask::idle)) end("finished");
            return;
        }
        if (round >= Math.max(1, cfg.activityMaxRounds) || now - startTick > 20L * 60 * Math.max(1, cfg.activityMaxMinutes)) {
            participants.get(0).say("ok that's enough for now, that was fun!");
            finishing = true;
            return;
        }
        if (now % 10 == 0) scanWatchedArea();
        if (pending || now - lastCallTick < MIN_TICKS_BETWEEN_CALLS) return;
        boolean allIdle = tasks.values().stream().allMatch(ActivityTask::idle);
        boolean wake = round == 0 || chatEvent || now >= wakeAtTick
                || waitFor.equals("steps") && allIdle
                || waitFor.equals("blocks") && blockEvent;
        if (wake) callDirector();
    }

    private boolean isRunning(NpcBrain npc) {
        ActivityTask t = tasks.get(npc);
        return t != null && (npc.hasTask(t) || !npc.npc().isAlive());
    }

    private void callDirector() {
        LlmService llm = mod.llm();
        if (llm == null) {
            participants.get(0).say("my brain's offline, can't run this one");
            end("no language model");
            return;
        }
        String blocked = llm.blockedReason();
        if (blocked != null) {
            if (blocked.contains("budget")) {
                participants.get(0).say("i'm all out of brain juice for today, let's continue tomorrow");
                end(blocked);
            }
            return; // rate limited: try again shortly
        }
        pending = true;
        lastCallTick = mod.server().getTicks();
        chatEvent = false;
        blockEvent = false;
        String context = Situation.forActivity(this);
        events.clear();
        llm.direct(context).thenAccept(result -> mod.server().execute(() -> {
            pending = false;
            if (ended) return;
            if (result.isEmpty()) {
                if (++failedCalls >= 3) {
                    participants.get(0).say("i lost track, let's call it here");
                    end("director failed");
                }
                wakeAtTick = mod.server().getTicks() + 100;
                return;
            }
            failedCalls = 0;
            apply(result.get());
        }));
    }

    private void apply(ActivityTurn turn) {
        round++;
        if (turn.notes() != null && !turn.notes().isBlank()) notes = turn.notes();
        ServerPlayerEntity speaker = host();
        if (turn.orders() != null) {
            for (Orders.NpcOrder order : turn.orders()) {
                if (order == null || order.npc() == null) continue;
                NpcBrain npc = mod.npcs().byName(order.npc());
                if (npc == null) continue;
                if (!involves(npc)) {
                    // the director pulled another NPC in
                    if (speaker == null || npc.npc().getWorld() != speaker.getWorld()) continue;
                    npc.giveOrders(List.of(join(npc)), true, speaker);
                }
                ActivityTask task = tasks.get(npc);
                if (order.say() != null && !order.say().isBlank()) npc.say(order.say());
                List<Orders.Step> steps = order.steps() == null ? List.of() : order.steps();
                TaskFactory.Built built = TaskFactory.build(npc, steps, speaker);
                if (!built.problems().isEmpty()) event(npc.name() + ": " + String.join("; ", built.problems()));
                task.assign(built.tasks(), !"append".equalsIgnoreCase(order.mode()));
                dev.mcattle.npc.NpcMemory.apply(npc, order.remember(), order.forget(), order.settings(), speaker);
                npc.social().applyFeel(order.feel(), speaker);
                npc.social().applyBonds(order.bond(), speaker);
            }
        }
        String wf = turn.waitFor() == null ? "steps" : turn.waitFor().toLowerCase(Locale.ROOT).trim();
        waitFor = wf.isEmpty() ? "steps" : wf;
        int seconds = Math.max(5, Math.min(300, turn.waitSeconds() <= 0 ? 60 : turn.waitSeconds()));
        wakeAtTick = mod.server().getTicks() + 20L * seconds;
        if (turn.watchArea() != null && !turn.watchArea().isBlank()) setWatch(turn.watchArea());
        if (turn.done()) finishing = true;
    }

    // ------------------------------------------------------------------ watched area

    void setWatch(String spec) {
        String[] p = spec.trim().replace(",", " ").split("\\s+");
        if (p.length != 6) return;
        try {
            int[] v = new int[6];
            for (int i = 0; i < 6; i++) v[i] = (int) Math.floor(Double.parseDouble(p[i]));
            BlockBox box = BlockBox.create(new BlockPos(v[0], v[1], v[2]), new BlockPos(v[3], v[4], v[5]));
            if (box.getBlockCountX() > 24 || box.getBlockCountY() > 24 || box.getBlockCountZ() > 24) return;
            watch = box;
            snapshot = capture();
        } catch (NumberFormatException ignored) {
        }
    }

    @Nullable
    ServerWorld world() {
        ServerPlayerEntity h = host();
        if (h != null) return h.getServerWorld();
        return participants.isEmpty() ? null : participants.get(0).world();
    }

    private Map<Long, BlockState> capture() {
        Map<Long, BlockState> map = new HashMap<>();
        ServerWorld world = world();
        if (watch == null || world == null) return map;
        for (BlockPos pos : BlockPos.iterate(watch.getMinX(), watch.getMinY(), watch.getMinZ(), watch.getMaxX(), watch.getMaxY(), watch.getMaxZ())) {
            map.put(pos.asLong(), world.getBlockState(pos));
        }
        return map;
    }

    private void scanWatchedArea() {
        if (watch == null) return;
        Map<Long, BlockState> now = capture();
        ServerWorld world = world();
        for (Map.Entry<Long, BlockState> e : now.entrySet()) {
            BlockState before = snapshot.get(e.getKey());
            if (before == null || before.getBlock() == e.getValue().getBlock()) continue;
            BlockPos pos = BlockPos.fromLong(e.getKey());
            String who = "";
            if (world != null) {
                var nearest = world.getClosestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 7, false);
                if (nearest != null) who = " (by " + nearest.getName().getString() + (nearest instanceof NpcPlayer ? ", NPC" : "") + ")";
            }
            event("block " + Situation.pos(pos) + ": " + name(before) + " -> " + name(e.getValue()) + who);
            blockEvent = true;
        }
        snapshot = now;
    }

    static String name(BlockState s) {
        return Registries.BLOCK.getId(s.getBlock()).getPath();
    }
}
