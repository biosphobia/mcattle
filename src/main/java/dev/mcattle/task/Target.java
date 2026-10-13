package dev.mcattle.task;

import dev.mcattle.npc.NpcBrain;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Something a task points at: a live entity (followed as it moves) or a fixed position. */
public final class Target {
    @Nullable private final UUID entityId;
    @Nullable private final Vec3d position;
    private final String description;

    private Target(@Nullable UUID entityId, @Nullable Vec3d position, String description) {
        this.entityId = entityId;
        this.position = position;
        this.description = description;
    }

    public static Target of(Entity entity) {
        return new Target(entity.getUuid(), null, entity.getName().getString());
    }

    public static Target of(BlockPos pos) {
        return new Target(null, Vec3d.ofBottomCenter(pos), pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }

    /**
     * Resolves "speaker", "me", "home", "here", "x y z", a player or NPC name, or a mob type
     * (nearest one of that type). Returns null if nothing matches.
     */
    @Nullable
    public static Target resolve(NpcBrain npc, String spec, @Nullable ServerPlayerEntity speaker) {
        String s = spec == null ? "" : spec.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || lower.equals("speaker") || lower.equals("me") || lower.equals("player")) {
            return speaker != null ? of(speaker) : null;
        }
        if (lower.equals("home") || lower.equals("base")) return of(npc.home());
        if (lower.equals("here")) return speaker != null ? of(speaker.getBlockPos()) : of(npc.npc().getBlockPos());
        if (lower.equals("self") || lower.equals("you") || lower.equals(npc.name().toLowerCase(Locale.ROOT))) return of(npc.npc());
        String[] parts = lower.replace(",", " ").trim().split("\\s+");
        if (parts.length == 3) {
            try {
                return of(new BlockPos((int) Math.floor(Double.parseDouble(parts[0])), (int) Math.floor(Double.parseDouble(parts[1])),
                        (int) Math.floor(Double.parseDouble(parts[2]))));
            } catch (NumberFormatException ignored) {
            }
        }
        ServerPlayerEntity player = npc.server().getPlayerManager().getPlayer(s);
        if (player == null) {
            for (ServerPlayerEntity p : npc.server().getPlayerManager().getPlayerList()) {
                if (p.getGameProfile().getName().equalsIgnoreCase(s)) {
                    player = p;
                    break;
                }
            }
        }
        if (player == null) {
            // nickname of an NPC ("maya" for CobbleMaya)
            for (ServerPlayerEntity p : npc.server().getPlayerManager().getPlayerList()) {
                String n = p.getGameProfile().getName().toLowerCase(Locale.ROOT);
                if (lower.length() >= 3 && n.endsWith(lower)) {
                    player = p;
                    break;
                }
            }
        }
        if (player != null) return of(player);
        LivingEntity mob = nearestOfType(npc, lower, 48);
        return mob == null ? null : of(mob);
    }

    @Nullable
    public static EntityType<?> entityType(String name) {
        String n = name.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        if (n.endsWith("ies")) n = n.substring(0, n.length() - 3) + "y";
        else if (n.endsWith("s") && !n.endsWith("ss")) n = n.substring(0, n.length() - 1);
        if (n.equals("cow") || n.equals("cattle")) n = "cow";
        if (n.equals("zombie_pigman") || n.equals("pigman")) n = "zombified_piglin";
        if (n.equals("chick")) n = "chicken";
        Identifier id = Identifier.tryParse(n.contains(":") ? n : "minecraft:" + n);
        if (id == null || !Registries.ENTITY_TYPE.containsId(id)) return null;
        return Registries.ENTITY_TYPE.get(id);
    }

    @Nullable
    public static LivingEntity nearestOfType(NpcBrain npc, String typeName, double radius) {
        EntityType<?> type = entityType(typeName);
        if (type == null) return null;
        Vec3d p = npc.npc().getPos();
        List<LivingEntity> list = npc.world().getEntitiesByClass(LivingEntity.class, new Box(p, p).expand(radius),
                e -> e.getType() == type && e.isAlive() && e != npc.npc());
        return list.stream().min(Comparator.comparingDouble(e -> e.squaredDistanceTo(p))).orElse(null);
    }

    @Nullable
    public Entity entity(NpcBrain npc) {
        if (entityId == null) return null;
        Entity e = npc.world().getEntity(entityId);
        if (e == null) e = npc.server().getPlayerManager().getPlayer(entityId);
        return e;
    }

    public boolean matches(Entity e) {
        return entityId != null && entityId.equals(e.getUuid());
    }

    public boolean isEntity() {
        return entityId != null;
    }

    /** Current position, or null if the entity is gone. */
    @Nullable
    public Vec3d position(NpcBrain npc) {
        if (position != null) return position;
        Entity e = entity(npc);
        return e == null || e.isRemoved() ? null : e.getPos();
    }

    public String describe() {
        return description;
    }
}
