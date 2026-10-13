package dev.mcattle.npc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import dev.mcattle.MCattle;
import dev.mcattle.config.MCattleConfig;
import dev.mcattle.core.names.NameGenerator;
import dev.mcattle.task.BuildJob;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** All NPCs on the server: spawning, removal, persistence, shared reservations and chat output. */
public final class NpcManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final MinecraftServer server;
    private final MCattle mod;
    private final Map<UUID, NpcBrain> brains = new LinkedHashMap<>();
    private final Map<String, NpcIdentity> registry = new LinkedHashMap<>();
    private final SkinService skins;
    private final Random random = new Random();
    private final NameGenerator names = new NameGenerator(random);
    private final Map<Long, UUID> reservations = new HashMap<>();
    private final Map<UUID, Long> gifts = new HashMap<>();
    private final Deque<String> recentChat = new ArrayDeque<>();
    private final List<BuildJob> jobs = new ArrayList<>();
    private final Path registryFile;

    public NpcManager(MinecraftServer server, MCattle mod) {
        this.server = server;
        this.mod = mod;
        Path root = server.getSavePath(WorldSavePath.ROOT);
        this.registryFile = root.resolve("mcattle_npcs.json");
        this.skins = new SkinService(root.resolve("mcattle_skins.json"));
    }

    public MinecraftServer server() {
        return server;
    }

    public MCattleConfig config() {
        return mod.config();
    }

    public MCattle mod() {
        return mod;
    }

    public Collection<NpcBrain> all() {
        return brains.values();
    }

    @Nullable
    public NpcBrain byUuid(UUID uuid) {
        return brains.get(uuid);
    }

    /** Case-insensitive lookup by full name or the "real name" half (maya for CobbleMaya). */
    @Nullable
    public NpcBrain byName(String name) {
        if (name == null || name.isBlank()) return null;
        String lower = name.trim().toLowerCase(Locale.ROOT);
        for (NpcBrain b : brains.values()) if (b.name().toLowerCase(Locale.ROOT).equals(lower)) return b;
        for (NpcBrain b : brains.values()) if (NameGenerator.nicknames(b.name()).contains(lower)) return b;
        return null;
    }

    public List<String> names() {
        return brains.values().stream().map(NpcBrain::name).toList();
    }

    // ------------------------------------------------------------------ spawning

    public String randomName() {
        List<String> taken = new ArrayList<>(registry.keySet());
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) taken.add(p.getGameProfile().getName());
        return names.generate(taken);
    }

    /** Spawns a brand new NPC. Completes with an error message on failure. */
    public CompletableFuture<NpcBrain> spawn(@Nullable String requestedName, @Nullable String skin, @Nullable ServerPlayerEntity owner,
                                             ServerWorld world, Vec3d pos, float yaw) {
        if (brains.size() >= config().npc.maxNpcs) {
            return CompletableFuture.failedFuture(new IllegalStateException("too many NPCs (max " + config().npc.maxNpcs + ")"));
        }
        String name = requestedName == null || requestedName.isBlank() || requestedName.equalsIgnoreCase("random") ? randomName() : requestedName;
        if (!NameGenerator.isValidName(name)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("names must be 3-16 letters, digits or _"));
        }
        if (byName(name) != null || server.getPlayerManager().getPlayer(name) != null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(name + " is already online"));
        }
        String skinSpec = resolveSkin(skin);
        NpcIdentity id = new NpcIdentity();
        id.name = name;
        id.skin = skin == null ? "random" : skin;
        id.persona = Personas.random(random);
        id.owner = owner == null ? null : owner.getUuidAsString();
        id.homeDimension = world.getRegistryKey().getValue().toString();
        BlockPos home = BlockPos.ofFloored(pos);
        id.homeX = home.getX();
        id.homeY = home.getY();
        id.homeZ = home.getZ();
        id.autonomous = config().npc.autonomousByDefault;
        id.uuid = uuidFor(skinSpec).toString();

        CompletableFuture<NpcBrain> result = new CompletableFuture<>();
        CompletableFuture<java.util.Optional<SkinService.Texture>> texture = skinSpec.startsWith("account:")
                ? skins.fetch(skinSpec.substring("account:".length()))
                : CompletableFuture.completedFuture(java.util.Optional.empty());
        texture.whenComplete((tex, err) -> server.execute(() -> {
            if (tex != null && tex.isPresent()) {
                id.textureValue = tex.get().value();
                id.textureSignature = tex.get().signature();
            }
            try {
                result.complete(join(id, world, pos, yaw, true));
            } catch (Exception e) {
                MCattle.LOGGER.error("Failed to spawn NPC {}", name, e);
                result.completeExceptionally(e);
            }
        }));
        return result;
    }

    /** "steve" / "alex" / "account:Name" for a preset key, an explicit spec, or a random preset. */
    private String resolveSkin(@Nullable String skin) {
        Map<String, String> presets = config().npc.skinPresets;
        if (skin == null || skin.isBlank() || skin.equalsIgnoreCase("random")) {
            List<String> values = new ArrayList<>(presets.values());
            return values.isEmpty() ? "steve" : values.get(random.nextInt(values.size()));
        }
        String s = skin.trim();
        String preset = presets.get(s.toLowerCase(Locale.ROOT));
        if (preset != null) return preset;
        if (s.startsWith("account:") || s.equals("steve") || s.equals("alex")) return s;
        if (NameGenerator.isValidName(s)) return "account:" + s;
        return "steve";
    }

    /**
     * Without textures, 1.20.1 picks one of 18 default skins (9 characters x slim/wide) from
     * floorMod(uuid.hashCode(), 18). Index 0 is slim Alex, 15 is wide Steve.
     */
    private UUID uuidFor(String skinSpec) {
        UUID uuid = UUID.randomUUID();
        if (skinSpec.equals("steve") || skinSpec.equals("alex")) {
            int want = skinSpec.equals("alex") ? 0 : 15;
            while (Math.floorMod(uuid.hashCode(), 18) != want) uuid = UUID.randomUUID();
        }
        return uuid;
    }

    /** Puts an NPC into the world through the normal player join path. */
    private NpcBrain join(NpcIdentity id, ServerWorld world, Vec3d pos, float yaw, boolean fresh) {
        GameProfile profile = new GameProfile(id.uuid(), id.name);
        if (id.textureValue != null && id.textureSignature != null) {
            profile.getProperties().put("textures", new Property("textures", id.textureValue, id.textureSignature));
        }
        NpcPlayer npc = new NpcPlayer(server, world, profile);
        NpcBrain brain = new NpcBrain(npc, id, this);
        npc.setBrain(brain);
        brains.put(id.uuid(), brain);
        registry.put(id.name, id);

        server.getPlayerManager().onPlayerConnect(new FakeConnection(), npc);
        if (fresh || !id.spawnedBefore) {
            npc.teleport(world, pos.x, pos.y, pos.z, yaw, 0);
            npc.changeGameMode(GameMode.SURVIVAL);
            npc.setHealth(npc.getMaxHealth());
        }
        if (npc.getHealth() <= 0) npc.setHealth(npc.getMaxHealth()); // saved while dead (server stopped mid-respawn)
        npc.unsetRemoved();
        npc.setStepHeight(0.6f);
        npc.showAllSkinLayers();
        ServerWorld actual = npc.getServerWorld();
        server.getPlayerManager().sendToDimension(new EntitySetHeadYawS2CPacket(npc, (byte) (npc.headYaw * 256 / 360)), actual.getRegistryKey());
        server.getPlayerManager().sendToDimension(new EntityPositionS2CPacket(npc), actual.getRegistryKey());
        id.spawnedBefore = true;
        saveRegistry();
        return brain;
    }

    public boolean remove(NpcBrain brain, boolean forget) {
        brain.clearOrders();
        brains.remove(brain.npc().getUuid());
        if (forget) {
            registry.remove(brain.name());
            saveRegistry();
        }
        reservations.values().removeIf(u -> u.equals(brain.npc().getUuid()));
        brain.npc().networkHandler.onDisconnected(Text.literal("NPC removed"));
        return true;
    }

    // ------------------------------------------------------------------ persistence

    public void loadAndRespawn() {
        if (!Files.exists(registryFile)) return;
        try (Reader r = Files.newBufferedReader(registryFile, StandardCharsets.UTF_8)) {
            List<NpcIdentity> list = GSON.fromJson(r, new TypeToken<List<NpcIdentity>>() {
            }.getType());
            if (list == null) return;
            for (NpcIdentity id : list) {
                if (id == null || id.name == null || id.uuid == null) continue;
                registry.put(id.name, id);
                if (!config().npc.persist) continue;
                RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, new Identifier(id.homeDimension));
                ServerWorld world = server.getWorld(key);
                if (world == null) world = server.getOverworld();
                try {
                    join(id, world, new Vec3d(id.homeX + 0.5, id.homeY, id.homeZ + 0.5), 0, false);
                } catch (Exception e) {
                    MCattle.LOGGER.error("Could not restore NPC {}", id.name, e);
                }
            }
        } catch (Exception e) {
            MCattle.LOGGER.error("Could not read {}", registryFile, e);
        }
    }

    public void saveRegistry() {
        try {
            Files.createDirectories(registryFile.getParent());
            try (Writer w = Files.newBufferedWriter(registryFile, StandardCharsets.UTF_8)) {
                GSON.toJson(new ArrayList<>(registry.values()), w);
            }
        } catch (Exception e) {
            MCattle.LOGGER.error("Could not save {}", registryFile, e);
        }
    }

    /** Server shutting down: save and disconnect NPCs (they come back on the next start). */
    public void shutdown() {
        saveRegistry();
        for (NpcBrain b : new ArrayList<>(brains.values())) {
            try {
                b.clearOrders();
                b.npc().networkHandler.onDisconnected(Text.literal("Server closed"));
            } catch (Exception e) {
                MCattle.LOGGER.warn("Error disconnecting NPC {}", b.name(), e);
            }
        }
        brains.clear();
    }

    // ------------------------------------------------------------------ coordination between NPCs

    public void reserve(BlockPos pos, NpcBrain npc) {
        reservations.put(pos.asLong(), npc.npc().getUuid());
    }

    public void release(BlockPos pos, NpcBrain npc) {
        reservations.remove(pos.asLong(), npc.npc().getUuid());
    }

    public boolean isReservedByOther(BlockPos pos, NpcBrain npc) {
        UUID owner = reservations.get(pos.asLong());
        return owner != null && !owner.equals(npc.npc().getUuid()) && brains.containsKey(owner);
    }

    public void markGift(UUID itemEntity) {
        gifts.put(itemEntity, server.getTicks() + 20L * 60);
    }

    public boolean isGift(UUID itemEntity) {
        Long until = gifts.get(itemEntity);
        return until != null && until > server.getTicks();
    }

    public void registerJob(BuildJob job) {
        jobs.add(job);
        jobs.removeIf(BuildJob::isDone);
    }

    public void tick() {
        if (server.getTicks() % 200 == 0) {
            long now = server.getTicks();
            gifts.values().removeIf(t -> t < now);
        }
    }

    // ------------------------------------------------------------------ chat

    public void broadcastChat(NpcBrain npc, String message) {
        Text text = Text.literal("<" + npc.name() + "> " + message);
        int range = config().npc.chatRange;
        if (range < 0) {
            server.getPlayerManager().broadcast(text, false);
        } else {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (p instanceof NpcPlayer) continue;
                if (p.getWorld() == npc.npc().getWorld() && p.distanceTo(npc.npc()) <= range) p.sendMessage(text, false);
            }
            MCattle.LOGGER.info("[NPC chat] <{}> {}", npc.name(), message);
        }
        recordChat(npc.name(), message);
    }

    public void recordChat(String who, String message) {
        recentChat.addLast("<" + who + "> " + (message.length() > 160 ? message.substring(0, 160) + "..." : message));
        while (recentChat.size() > 8) recentChat.removeFirst();
    }

    public List<String> recentChat() {
        return new ArrayList<>(recentChat);
    }

    public Iterator<NpcBrain> iterator() {
        return brains.values().iterator();
    }
}
