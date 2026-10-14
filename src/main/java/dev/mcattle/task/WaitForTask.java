package dev.mcattle.task;

import dev.mcattle.npc.NpcBrain;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pauses until the player does something: says something, places or breaks blocks nearby, throws the NPC an
 * item, comes close or walks away. Meanwhile the NPC watches them. What happened is kept so the planner can be
 * asked how to react ("Steve placed white_wool at (3,64,7)").
 */
public final class WaitForTask extends Task {
    public enum Kind {CHAT, BLOCK, ITEM, NEAR, FAR}

    private final Kind kind;
    @Nullable private final Target who;
    private final int timeoutTicks;
    private final double radius;
    private final boolean think;
    @Nullable private BlockPos center;
    private final Map<BlockPos, BlockState> snapshot = new HashMap<>();
    @Nullable private String happened;

    public WaitForTask(Kind kind, @Nullable Target who, int timeoutTicks, double radius, boolean think) {
        this.kind = kind;
        this.who = who;
        this.timeoutTicks = timeoutTicks;
        this.radius = radius;
        this.think = think;
    }

    public static Kind kind(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "chat", "say", "answer", "reply", "talk" -> Kind.CHAT;
            case "block", "blocks", "build", "move", "place", "break" -> Kind.BLOCK;
            case "item", "gift", "give", "throw" -> Kind.ITEM;
            case "far", "leave", "away", "gone" -> Kind.FAR;
            default -> Kind.NEAR;
        };
    }

    /** What happened, or null while still waiting. */
    @Nullable
    public String happened() {
        return happened;
    }

    /** True if the planner should decide the next steps now (asked to, or nothing else is queued). */
    public boolean wantsFollowUp(boolean nothingQueued) {
        return think || nothingQueued;
    }

    @Nullable
    public ServerPlayerEntity player(NpcBrain npc) {
        return who != null && who.entity(npc) instanceof ServerPlayerEntity p ? p : null;
    }

    private boolean matches(NpcBrain npc, Entity e) {
        if (who == null) return true;
        Entity w = who.entity(npc);
        return w == null || w == e;
    }

    /** A chat line arrived; returns true if this wait consumed it. */
    public boolean offerChat(NpcBrain npc, ServerPlayerEntity speaker, String text) {
        if (kind != Kind.CHAT || happened != null || !matches(npc, speaker)) return false;
        if (speaker.getWorld() != npc.world() || speaker.distanceTo(npc.npc()) > 48) return false;
        happened = speaker.getName().getString() + " said: \"" + (text.length() > 120 ? text.substring(0, 120) : text) + "\"";
        return true;
    }

    public boolean offerItem(NpcBrain npc, Entity giver, ItemStack stack) {
        if (kind != Kind.ITEM || happened != null || !matches(npc, giver)) return false;
        happened = giver.getName().getString() + " gave " + stack.getCount() + " " + Registries.ITEM.getId(stack.getItem()).getPath();
        return true;
    }

    @Override
    protected void start(NpcBrain npc) {
        if (kind == Kind.BLOCK) {
            Entity w = who == null ? null : who.entity(npc);
            center = w != null && w.getWorld() == npc.world() ? w.getBlockPos() : npc.npc().getBlockPos();
            int r = (int) Math.min(8, Math.max(2, radius));
            for (BlockPos p : BlockPos.iterate(center.add(-r, -3, -r), center.add(r, 4, r))) {
                snapshot.put(p.toImmutable(), npc.world().getBlockState(p));
            }
        }
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (happened != null) return Status.SUCCESS;
        Entity w = who == null ? null : who.entity(npc);
        if (w != null && w.getWorld() == npc.world()) {
            npc.move().lookAt(w);
            double dist = w.distanceTo(npc.npc());
            if (kind == Kind.NEAR && dist <= radius) happened = w.getName().getString() + " came over";
            if (kind == Kind.FAR && dist > radius) happened = w.getName().getString() + " walked away";
        } else if (kind == Kind.FAR && who != null) {
            happened = who.describe() + " is gone";
        }
        if (kind == Kind.BLOCK && ticks % 10 == 0) checkBlocks(npc);
        if (happened != null) return Status.SUCCESS;
        if (ticks >= timeoutTicks) {
            happened = "nothing happened for " + timeoutTicks / 20 + "s (still waiting for " + (who == null ? "someone" : who.describe()) + ")";
            return Status.SUCCESS;
        }
        return Status.RUNNING;
    }

    private void checkBlocks(NpcBrain npc) {
        List<String> changes = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : snapshot.entrySet()) {
            BlockState now = npc.world().getBlockState(e.getKey());
            if (now == e.getValue()) continue;
            BlockPos p = e.getKey();
            String at = " at (" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
            changes.add(now.isAir() ? "broke " + name(e.getValue()) + at : "placed " + name(now) + at);
            if (changes.size() >= 6) break;
        }
        if (!changes.isEmpty()) happened = (who == null ? "someone" : who.describe()) + " " + String.join(", ", changes);
    }

    private static String name(BlockState s) {
        return Registries.BLOCK.getId(s.getBlock()).getPath();
    }

    @Override
    public String describe() {
        String w = who == null ? "someone" : who.describe();
        return switch (kind) {
            case CHAT -> "waiting for " + w + " to answer";
            case BLOCK -> "waiting for " + w + "'s move";
            case ITEM -> "waiting for " + w + " to toss something";
            case NEAR -> "waiting for " + w;
            case FAR -> "waiting for " + w + " to leave";
        };
    }
}
