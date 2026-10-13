package dev.mcattle.task;

import dev.mcattle.npc.NpcBrain;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/** Player body language: crouch-greeting, waving (arm swings), dancing, spinning, nodding, panicking... */
public final class EmoteTask extends Task {
    private final String type;
    @Nullable private final Target target;
    private final int duration;

    public EmoteTask(String type, @Nullable Target target) {
        this.type = type == null || type.isBlank() ? "greet" : type.toLowerCase(java.util.Locale.ROOT);
        this.target = target;
        this.duration = switch (this.type) {
            case "dance", "panic" -> 80;
            case "spin" -> 30;
            case "greet", "sneak" -> 24;
            default -> 40;
        };
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (ticks > duration) return Status.SUCCESS;
        var move = npc.move();
        Entity e = target == null ? null : target.entity(npc);
        if (e != null && !type.equals("spin") && !type.equals("panic")) move.lookAt(e);
        float yaw = npc.npc().getYaw();
        switch (type) {
            case "greet", "sneak" -> { // the classic crouch spam "hello"
                if ((ticks / 4) % 2 == 0) move.sneak();
            }
            case "wave", "flex" -> {
                if (ticks % 6 == 0) npc.npc().swingHand(ticks % 12 == 0 ? Hand.MAIN_HAND : Hand.OFF_HAND, true);
            }
            case "jump" -> {
                if (npc.npc().isOnGround()) move.jump();
            }
            case "spin" -> move.setRotation(yaw + 24f, 0f);
            case "dance" -> {
                if ((ticks / 5) % 2 == 0) move.sneak();
                if (ticks % 20 < 10) move.setRotation(yaw + 18f, 0f);
                if (ticks % 16 == 0 && npc.npc().isOnGround()) move.jump();
                if (ticks % 7 == 0) npc.npc().swingHand(Hand.MAIN_HAND, true);
            }
            case "nod" -> move.setRotation(yaw, (ticks / 5) % 2 == 0 ? 35f : -15f);
            case "shake" -> move.setRotation(yaw + ((ticks / 4) % 2 == 0 ? 25f : -25f), 0f);
            case "bow" -> {
                move.setRotation(yaw, 60f);
                move.sneak();
            }
            case "panic" -> {
                if (ticks % 12 == 0) move.setRotation(yaw + npc.random().nextInt(180) - 90, 0f);
                else move.setRotation(yaw, 0f);
                move.input(1f, 0f);
                move.sprint(true);
                if (npc.npc().horizontalCollision && npc.npc().isOnGround()) move.jump();
                if (ticks % 20 == 0 && npc.random().nextBoolean()) npc.say(npc.random().nextBoolean() ? "AAAAA" : "aaaaah!!");
            }
            default -> {
                if (e == null && target != null) {
                    Vec3d p = target.position(npc);
                    if (p != null) move.lookAt(p.add(0, 1.5, 0));
                }
            }
        }
        return Status.RUNNING;
    }

    @Override
    public String describe() {
        return "emote " + type;
    }
}
