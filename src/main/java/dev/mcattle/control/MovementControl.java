package dev.mcattle.control;

import dev.mcattle.npc.NpcPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Translates "go there / look there" intents into the same inputs a player's keyboard and mouse produce
 * (forward/strafe, jump, sneak, sprint, yaw/pitch). Intents are cleared every tick; tasks re-issue them.
 */
public final class MovementControl {
    private final NpcPlayer npc;

    @Nullable private Vec3d moveTarget;
    @Nullable private Vec3d lookTarget;
    private float forward, strafe;
    private boolean jump, sneak, sprint;
    private float maxTurn = 35f;
    private boolean manualInput;
    private Float forcedYaw, forcedPitch;

    public MovementControl(NpcPlayer npc) {
        this.npc = npc;
    }

    /** Called at the start of each tick: forget last tick's intents. */
    public void reset() {
        moveTarget = null;
        lookTarget = null;
        forward = strafe = 0;
        jump = sneak = sprint = false;
        manualInput = false;
        forcedYaw = null;
        forcedPitch = null;
        maxTurn = 35f;
    }

    public void moveTo(Vec3d target, boolean sprint) {
        this.moveTarget = target;
        this.sprint = sprint;
    }

    /** Direct key-style input relative to where the NPC is facing. */
    public void input(float forward, float strafe) {
        this.forward = forward;
        this.strafe = strafe;
        this.manualInput = true;
    }

    public void lookAt(Vec3d target) {
        this.lookTarget = target;
    }

    public void lookAt(Entity entity) {
        this.lookTarget = entity.getEyePos();
    }

    public void setRotation(float yaw, float pitch) {
        this.forcedYaw = yaw;
        this.forcedPitch = pitch;
    }

    public void jump() {
        this.jump = true;
    }

    public void sneak() {
        this.sneak = true;
    }

    public void sprint(boolean sprint) {
        this.sprint = sprint;
    }

    public void fastTurn() {
        this.maxTurn = 90f;
    }

    public boolean isMoving() {
        return moveTarget != null || manualInput;
    }

    public static float yawTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x, dz = to.z - from.z;
        return (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
    }

    public static float pitchTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        return (float) -(MathHelper.atan2(dy, h) * (180.0 / Math.PI));
    }

    /** Applies the intents to the entity. Must run before the entity's physics tick. */
    public void apply() {
        Vec3d eye = npc.getEyePos();
        float yaw = npc.getYaw();
        float pitch = npc.getPitch();

        Vec3d look = lookTarget;
        if (look == null && moveTarget != null) {
            look = new Vec3d(moveTarget.x, eye.y - 0.1, moveTarget.z);
        }
        if (forcedYaw != null) {
            yaw = forcedYaw;
            pitch = forcedPitch;
        } else if (look != null && look.squaredDistanceTo(eye) > 1.0E-4) {
            float wantYaw = yawTo(eye, look);
            float wantPitch = pitchTo(eye, look);
            yaw = yaw + MathHelper.clamp(MathHelper.wrapDegrees(wantYaw - yaw), -maxTurn, maxTurn);
            pitch = pitch + MathHelper.clamp(wantPitch - pitch, -maxTurn, maxTurn);
        }
        pitch = MathHelper.clamp(pitch, -90f, 90f);
        npc.setYaw(yaw);
        npc.setPitch(pitch);
        npc.setHeadYaw(yaw);
        npc.setBodyYaw(yaw);

        float fwd = forward, side = strafe;
        if (moveTarget != null && !manualInput) {
            double dx = moveTarget.x - npc.getX(), dz = moveTarget.z - npc.getZ();
            if (dx * dx + dz * dz > 0.0025) {
                float moveYaw = yawTo(npc.getPos(), moveTarget);
                float delta = (moveYaw - yaw) * MathHelper.RADIANS_PER_DEGREE;
                fwd = MathHelper.cos(delta);
                side = -MathHelper.sin(delta);
                // slow down when nearly there so we don't overshoot narrow ledges
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist < 0.6) {
                    fwd *= (float) Math.max(0.3, dist / 0.6);
                    side *= (float) Math.max(0.3, dist / 0.6);
                }
            }
        }
        boolean usingItem = npc.isUsingItem();
        float scale = sneak ? 0.3f : 1f;
        if (usingItem) scale *= 0.2f; // same slowdown a player gets while eating or drawing a bow
        npc.forwardSpeed = fwd * scale;
        npc.sidewaysSpeed = side * scale;
        npc.setJumping(jump);
        npc.setSneaking(sneak);
        boolean canSprint = sprint && fwd > 0.8f && !sneak && !usingItem && npc.getHungerManager().getFoodLevel() > 6;
        npc.setSprinting(canSprint);
    }
}
