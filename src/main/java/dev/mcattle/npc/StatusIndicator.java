package dev.mcattle.npc;

import dev.mcattle.mixin.DisplayEntityInvoker;
import dev.mcattle.mixin.TextDisplayEntityInvoker;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.AffineTransformation;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A small floating line above the NPC's name tag saying what it's up to ("♪ listening", "✎ thinking...",
 * "⛏ mining iron ore"). It is a vanilla text display riding the NPC, so every player sees it without any client
 * mod. Hidden while the NPC is idle.
 */
public final class StatusIndicator {
    public static final String TAG = "mcattle_status";
    /** Displays this server run created; any other tagged display found in the world is a leftover from a crash. */
    private static final Set<UUID> LIVE = Collections.synchronizedSet(new HashSet<>());

    private final NpcBrain npc;
    @Nullable private DisplayEntity.TextDisplayEntity display;
    private String shown = "";

    public StatusIndicator(NpcBrain npc) {
        this.npc = npc;
    }

    public static boolean isOrphan(net.minecraft.entity.Entity e) {
        return e instanceof DisplayEntity.TextDisplayEntity && e.getCommandTags().contains(TAG) && !LIVE.contains(e.getUuid());
    }

    public void tick() {
        if (npc.age() % 5 != 0) return;
        if (!npc.config().npc.statusIndicator) {
            remove();
            return;
        }
        String status = npc.npc().isAlive() ? npc.statusLine() : "";
        if (status.isEmpty()) {
            remove(); // idle: nothing floating over its head
            return;
        }
        ensureDisplay();
        if (display != null && !status.equals(shown)) {
            shown = status;
            ((TextDisplayEntityInvoker) display).mcattle$setText(format(status));
        }
    }

    private void ensureDisplay() {
        NpcPlayer body = npc.npc();
        if (display != null && (display.isRemoved() || display.getWorld() != body.getWorld() || display.getVehicle() != body)) {
            remove();
        }
        if (display != null) return;
        ServerWorld world = body.getServerWorld();
        DisplayEntity.TextDisplayEntity d = new DisplayEntity.TextDisplayEntity(EntityType.TEXT_DISPLAY, world);
        d.addCommandTag(TAG);
        d.refreshPositionAndAngles(body.getX(), body.getY() + 1.4, body.getZ(), 0, 0);
        DisplayEntityInvoker di = (DisplayEntityInvoker) d;
        di.mcattle$setBillboardMode(DisplayEntity.BillboardMode.CENTER);
        // ride at ~1.35 blocks; lift it above the name tag and shrink it so it stays compact
        di.mcattle$setTransformation(new AffineTransformation(new Vector3f(0, 1.2f, 0), new Quaternionf(),
                new Vector3f(0.6f, 0.6f, 0.6f), new Quaternionf()));
        di.mcattle$setViewRange(0.5f); // ~32 blocks
        TextDisplayEntityInvoker ti = (TextDisplayEntityInvoker) d;
        ti.mcattle$setBackground(0x55000000);
        ti.mcattle$setLineWidth(200);
        LIVE.add(d.getUuid());
        if (!world.spawnEntity(d)) {
            LIVE.remove(d.getUuid());
            return;
        }
        d.startRiding(body, true);
        display = d;
        shown = "";
    }

    public void remove() {
        if (display != null) {
            LIVE.remove(display.getUuid());
            display.stopRiding();
            display.discard();
            display = null;
        }
        shown = "";
    }

    private static Text format(String status) {
        // "§" codes are applied as styles: first char is the icon, the rest the description
        int split = status.indexOf(' ');
        String icon = split > 0 ? status.substring(0, split) : "";
        String rest = split > 0 ? status.substring(split + 1) : status;
        Formatting color = switch (icon) {
            case "♪" -> Formatting.AQUA;
            case "✎" -> Formatting.YELLOW;
            case "⚔", "!" -> Formatting.RED;
            default -> Formatting.WHITE;
        };
        return Text.literal(icon + " ").formatted(color).append(Text.literal(rest).formatted(Formatting.GRAY));
    }

    /** Icon + very short text for a task description ("mining 5 iron ore" -> "⛏ mining 5 iron ore"). */
    public static String compact(String description) {
        if (description == null || description.isBlank()) return "";
        String d = description.replaceAll("\\s+", " ").trim();
        int cut = d.indexOf(" > ");
        if (cut > 0) d = d.substring(cut + 3); // the innermost step is what it's doing right now
        int deeper;
        while ((deeper = d.indexOf(" > ")) > 0) d = d.substring(deeper + 3);
        String l = d.toLowerCase(Locale.ROOT);
        String icon;
        if (l.contains("attack") || l.contains("fight") || l.contains("guard") || l.contains("hunt")) icon = "⚔";
        else if (l.contains("flee") || l.contains("running") || l.contains("escap")) icon = "!";
        else if (l.contains("mine") || l.contains("mining") || l.contains("dig") || l.contains("chop") || l.contains("break")) icon = "⛏";
        else if (l.contains("build") || l.contains("place")) icon = "⌂";
        else if (l.contains("craft") || l.contains("smelt")) icon = "⚒";
        else if (l.contains("follow") || l.contains("going") || l.contains("goto") || l.contains("walk") || l.contains("hanging out")) icon = "➜";
        else if (l.contains("eat") || l.contains("food") || l.contains("farm")) icon = "✿";
        else if (l.contains("sleep")) icon = "☾";
        else if (l.contains("give") || l.contains("giving") || l.contains("chest")) icon = "✉";
        else if (l.contains("waiting")) icon = "⌛";
        else if (l.contains("danc") || l.contains("party") || l.contains("activity") || l.contains("play")) icon = "♫";
        else icon = "•";
        if (d.length() > 26) d = d.substring(0, 25) + "…";
        return icon + " " + d;
    }
}
