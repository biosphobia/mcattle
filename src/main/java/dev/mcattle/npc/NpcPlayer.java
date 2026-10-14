package dev.mcattle.npc;

import com.mojang.authlib.GameProfile;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;
import org.jetbrains.annotations.Nullable;

/**
 * The NPC itself: a real server-side player with no client behind it. Vanilla clients see a normal player
 * (skin, name tag, tab list entry, armor, held items, animations). All decisions come from its {@link NpcBrain}.
 */
public class NpcPlayer extends ServerPlayerEntity {
    @Nullable
    private NpcBrain brain;
    private int respawnTimer = -1;
    @Nullable private java.util.UUID pendingGiftFrom;

    public NpcPlayer(MinecraftServer server, ServerWorld world, GameProfile profile) {
        super(server, world, profile);
    }

    public void setBrain(NpcBrain brain) {
        this.brain = brain;
    }

    @Nullable
    public NpcBrain brain() {
        return brain;
    }

    @Override
    public void tick() {
        if (getServer().getTicks() % 10 == 0) {
            networkHandler.syncWithPlayerPosition();
            getServerWorld().getChunkManager().updatePosition(this);
        }
        try {
            if (brain != null) {
                if (isAlive()) {
                    brain.tick();
                } else {
                    brain.onDeadTick();
                }
            }
            super.tick();
            playerTick();
        } catch (NullPointerException e) {
            // Mirrors what other fake-player implementations do: never take the server down over a
            // half-initialized NPC (e.g. during the first tick after joining).
            if (brain != null) brain.logError("tick", e);
        }
    }

    /** Server-side players skip fall damage (the client reports it); NPCs have no client, so compute it here. */
    @Override
    protected void fall(double heightDifference, boolean onGround, BlockState state, BlockPos landedPosition) {
        handleFall(0.0, heightDifference, 0.0, onGround);
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        boolean hurt = super.damage(source, amount);
        if (hurt && brain != null) brain.onHurt(source, amount);
        return hurt;
    }

    @Override
    public void onDeath(DamageSource damageSource) {
        super.onDeath(damageSource);
        respawnTimer = Math.max(1, brain != null ? brain.config().npc.respawnDelayTicks : 60);
        if (brain != null) brain.onDeath(damageSource);
    }

    /** Instead of being removed after the death animation, the NPC waits and then respawns in place of a client respawn. */
    @Override
    protected void updatePostDeath() {
        if (deathTime < 20) deathTime++;
        if (respawnTimer < 0) respawnTimer = 60; // died without onDeath (e.g. restored dead from disk)
        if (respawnTimer > 0 && --respawnTimer == 0) {
            respawn();
        }
    }

    private void respawn() {
        respawnTimer = -1;
        boolean keepInventory = getServerWorld().getGameRules().getBoolean(GameRules.KEEP_INVENTORY);
        dead = false;
        deathTime = 0;
        hurtTime = 0;
        fallDistance = 0;
        setHealth(getMaxHealth());
        hungerManager = new HungerManager();
        clearStatusEffects();
        extinguish();
        setFireTicks(0);
        setPose(EntityPose.STANDING);
        if (!keepInventory) {
            experienceLevel = 0;
            totalExperience = 0;
            experienceProgress = 0;
        }
        if (brain != null) {
            brain.onRespawn();
        }
        // Clients saw this entity die; make them forget it and see a fresh one.
        ServerWorld world = getServerWorld();
        world.getChunkManager().unloadEntity(this);
        world.getChunkManager().loadEntity(this);
    }

    /** Picking up an item someone threw at us: remember the thrower until the pickup stat names the item. */
    @Override
    public void sendPickup(net.minecraft.entity.Entity item, int count) {
        super.sendPickup(item, count);
        if (item instanceof net.minecraft.entity.ItemEntity ie) {
            pendingGiftFrom = ((dev.mcattle.mixin.ItemEntityAccessor) ie).mcattle$getThrower();
        }
    }

    @Override
    public void increaseStat(net.minecraft.stat.Stat<?> stat, int amount) {
        super.increaseStat(stat, amount);
        if (pendingGiftFrom != null && stat.getType() == net.minecraft.stat.Stats.PICKED_UP
                && stat.getValue() instanceof net.minecraft.item.Item item) {
            java.util.UUID from = pendingGiftFrom;
            pendingGiftFrom = null;
            ServerPlayerEntity giver = getServer().getPlayerManager().getPlayer(from);
            if (giver != null && giver != this && brain != null) {
                brain.social().received(giver, new net.minecraft.item.ItemStack(item, amount), amount);
            }
        }
    }

    /** Clears the "removed" flag left over from the join path (same as other fake-player mods do). */
    public void markPresent() {
        unsetRemoved();
    }

    /** Shows hat, jacket and sleeve layers of the skin, like a client with default skin settings. */
    public void showAllSkinLayers() {
        getDataTracker().set(PLAYER_MODEL_PARTS, (byte) 0x7f);
    }

    public boolean isWaitingToRespawn() {
        return respawnTimer > 0;
    }

    @Override
    public String getIp() {
        return "127.0.0.1";
    }
}
