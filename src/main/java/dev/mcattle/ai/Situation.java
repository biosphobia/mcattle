package dev.mcattle.ai;

import dev.mcattle.control.BlockSearch;
import dev.mcattle.control.Tags;
import dev.mcattle.control.WorldRules;
import dev.mcattle.npc.NpcBrain;
import dev.mcattle.npc.NpcManager;
import dev.mcattle.npc.NpcPlayer;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compact plain-text situation reports for the language model. Detail goes only where it matters (the
 * addressed NPCs); everything else is a line or less, to keep each request to a few hundred tokens.
 */
public final class Situation {
    private Situation() {
    }

    public static String forCommand(NpcManager manager, ServerPlayerEntity speaker, String text, boolean voice,
                                    List<NpcBrain> addressed, boolean everyone) {
        ServerWorld world = speaker.getServerWorld();
        StringBuilder sb = new StringBuilder(1200);
        sb.append("Time: ").append(timeOfDay(world)).append(world.isThundering() ? ", thunderstorm" : world.isRaining() ? ", raining" : "")
                .append(". Difficulty ").append(world.getDifficulty().getName())
                .append(", pvp ").append(manager.server().isPvpEnabled() ? "on" : "off").append(".\n");
        sb.append("Speaker ").append(speaker.getName().getString()).append(" at ").append(pos(speaker.getBlockPos()))
                .append(" in ").append(world.getRegistryKey().getValue().getPath())
                .append(", hp ").append((int) Math.ceil(speaker.getHealth())).append("/20")
                .append(", holding ").append(itemName(speaker.getMainHandStack()))
                .append(", facing ").append(speaker.getHorizontalFacing().asString())
                .append(speaker.isCreative() ? ", creative mode" : "").append(".\n");
        String looking = lookingAt(speaker);
        if (!looking.isEmpty()) sb.append("Speaker is looking at ").append(looking).append(".\n");
        sb.append("Message (").append(voice ? "voice, may contain transcription errors" : "chat").append("): \"")
                .append(text.replace('"', '\'')).append("\"\n");
        sb.append("Addressed: ").append(everyone ? "everyone" : addressed.isEmpty() ? "nobody by name (all nearby NPCs heard it)"
                : String.join(", ", addressed.stream().map(NpcBrain::name).toList())).append("\n");
        sb.append("NPCs:\n");
        for (NpcBrain npc : manager.all()) {
            boolean detailed = addressed.contains(npc);
            if (!detailed && npc.npc().getWorld() != world) continue;
            if (!detailed && npc.npc().distanceTo(speaker) > 128) continue;
            sb.append(npcBlock(npc, speaker, detailed));
        }
        List<String> others = world.getPlayers().stream()
                .filter(p -> !(p instanceof NpcPlayer) && p != speaker && p.distanceTo(speaker) < 64)
                .map(p -> p.getName().getString() + " " + (int) p.distanceTo(speaker) + "m").toList();
        if (!others.isEmpty()) sb.append("Other players nearby: ").append(String.join(", ", others)).append("\n");
        List<String> chat = manager.recentChat();
        if (!chat.isEmpty()) sb.append("Recent chat:\n").append(String.join("\n", chat)).append("\n");
        return sb.toString();
    }

    public static String forBanter(NpcManager manager, List<NpcBrain> npcs, List<ServerPlayerEntity> players) {
        StringBuilder sb = new StringBuilder(600);
        NpcBrain first = npcs.get(0);
        sb.append("Time: ").append(timeOfDay(first.world())).append(".\nSpeakers (NPCs):\n");
        for (NpcBrain npc : npcs) {
            var p = npc.npc();
            sb.append("- ").append(npc.name()).append(" [").append(npc.identity().persona).append("] hp ")
                    .append((int) Math.ceil(p.getHealth())).append(", food ").append(p.getHungerManager().getFoodLevel())
                    .append(", doing: ").append(npc.activity()).append("\n");
            String longTerm = dev.mcattle.npc.NpcMemory.describe(npc);
            if (!longTerm.isEmpty()) sb.append("  memory (follow it): ").append(longTerm).append("\n");
        }
        if (!players.isEmpty()) {
            sb.append("Human players nearby: ").append(String.join(", ", players.stream().map(p -> p.getName().getString()).toList())).append("\n");
        }
        List<String> chat = manager.recentChat();
        if (!chat.isEmpty()) sb.append("Recent chat:\n").append(String.join("\n", chat)).append("\n");
        return sb.toString();
    }

    /** Context for one round of an activity. */
    public static String forActivity(ActivitySession session) {
        StringBuilder sb = new StringBuilder(2000);
        long elapsed = (session.participants.isEmpty() ? 0 : session.participants.get(0).server().getTicks() - session.startTick) / 20;
        sb.append("Activity goal: ").append(session.goal).append("\n");
        sb.append("Round ").append(session.round + 1).append(", ").append(elapsed / 60).append("m").append(elapsed % 60).append("s in.\n");
        sb.append("Your notes: ").append(session.notes.isBlank() ? "(none yet - this is the start)" : session.notes).append("\n");
        sb.append("Since last round:\n");
        if (session.events.isEmpty()) sb.append("- nothing new\n");
        for (String e : session.events) sb.append("- ").append(e).append("\n");
        ServerWorld world = session.world();
        if (world != null) sb.append("Time: ").append(timeOfDay(world)).append(".\n");
        sb.append("Participants:\n");
        for (NpcBrain npc : session.participants) {
            var p = npc.npc();
            ActivityTask t = session.tasks.get(npc);
            sb.append("* ").append(npc.name()).append(" [").append(npc.identity().persona).append("] at ").append(pos(p.getBlockPos()))
                    .append(", hp ").append((int) Math.ceil(p.getHealth())).append(", holding ").append(itemName(p.getMainHandStack()))
                    .append(", ").append(t == null || t.idle() ? "idle" : t.describe()).append("\n  inventory: ").append(npc.inventorySummary(12)).append("\n");
            String longTerm = dev.mcattle.npc.NpcMemory.describe(npc);
            if (!longTerm.isEmpty()) sb.append("  memory (follow it): ").append(longTerm).append("\n");
        }
        if (world != null) {
            for (ServerPlayerEntity p : world.getPlayers()) {
                if (p instanceof NpcPlayer || p.isSpectator()) continue;
                boolean near = session.participants.stream().anyMatch(n -> n.npc().distanceTo(p) < 48);
                if (!near) continue;
                String looking = lookingAt(p);
                sb.append("Player ").append(p.getName().getString()).append(p.getUuid().equals(session.host) ? " (host)" : "")
                        .append(" at ").append(pos(p.getBlockPos())).append(", holding ").append(itemName(p.getMainHandStack()))
                        .append(looking.isEmpty() ? "" : ", looking at " + looking).append("\n");
            }
        }
        if (session.watch != null && world != null) {
            var w = session.watch;
            sb.append("Watched area ").append(pos(new BlockPos(w.getMinX(), w.getMinY(), w.getMinZ()))).append(" to ")
                    .append(pos(new BlockPos(w.getMaxX(), w.getMaxY(), w.getMaxZ()))).append(" non-air blocks:\n");
            Map<String, List<String>> byBlock = new TreeMap<>();
            int n = 0;
            for (BlockPos p : BlockPos.iterate(w.getMinX(), w.getMinY(), w.getMinZ(), w.getMaxX(), w.getMaxY(), w.getMaxZ())) {
                BlockState st = world.getBlockState(p);
                if (st.isAir() || n++ > 200) continue;
                byBlock.computeIfAbsent(ActivitySession.name(st), k -> new java.util.ArrayList<>()).add(pos(p));
            }
            if (byBlock.isEmpty()) sb.append("(empty)\n");
            for (Map.Entry<String, List<String>> e : byBlock.entrySet()) {
                sb.append(e.getKey()).append(": ").append(String.join(" ", e.getValue())).append("\n");
            }
        } else if (!session.participants.isEmpty()) {
            sb.append("Around ").append(session.participants.get(0).name()).append(": ").append(surroundings(session.participants.get(0))).append("\n");
        }
        return sb.toString();
    }

    /** Context for designing a build at a particular site. */
    public static String forDesign(NpcBrain npc, String request, String size, BlockPos anchor, net.minecraft.util.math.Direction forward) {
        ServerWorld world = npc.world();
        StringBuilder sb = new StringBuilder(1500);
        sb.append("Build request: \"").append(request.replace('"', '\'')).append("\"\n");
        sb.append("Size: ").append(size == null || size.isBlank() ? "medium (use judgment)" : size).append("\n");
        boolean creative = npc.npc().isCreative();
        sb.append("Builders: ");
        List<String> builders = new java.util.ArrayList<>();
        for (NpcBrain b : npc.manager().all()) {
            if (b == npc || b.npc().getWorld() == world && b.npc().getBlockPos().getSquaredDistance(anchor) < 48 * 48) {
                builders.add(b.name() + (b.npc().isCreative() ? " (creative)" : " (survival)"));
            }
        }
        sb.append(String.join(", ", builders)).append(creative ? ". Creative: any blocks are fine." : ". Survival: materials must be gathered or crafted.").append("\n");
        sb.append("Inventory of ").append(npc.name()).append(": ").append(npc.inventorySummary(14)).append("\n");
        String biome = world.getBiome(anchor).getKey().map(k -> k.getValue().getPath()).orElse("?");
        sb.append("Biome: ").append(biome).append(". Nearby: ").append(surroundings(npc)).append("\n");
        // terrain relative to the ground at the anchor, in the design's local frame
        net.minecraft.util.math.Direction right = forward.rotateYClockwise();
        int ground = world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, anchor.offset(forward, 3)).getY();
        sb.append("Ground height relative to y=0 (rows: z = 0,3,6..24 away from the player; columns: x = -12..12 step 3):\n");
        for (int z = 0; z <= 24; z += 3) {
            StringBuilder row = new StringBuilder("z").append(z).append(':');
            for (int x = -12; x <= 12; x += 3) {
                BlockPos col = anchor.offset(forward, 3 + z).offset(right, x);
                if (!world.isChunkLoaded(col.getX() >> 4, col.getZ() >> 4)) {
                    row.append(" ?");
                    continue;
                }
                BlockPos top = world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, col);
                BlockState surface = world.getBlockState(top.down());
                int dh = top.getY() - ground;
                row.append(' ').append(dh).append(surface.getFluidState().isEmpty() ? "" : "w");
            }
            sb.append(row).append('\n');
        }
        sb.append("(w = water). Design it so it sits well on this terrain.\n");
        return sb.toString();
    }

    static String npcBlock(NpcBrain npc, Entity reference, boolean detailed) {
        NpcPlayer p = npc.npc();
        StringBuilder sb = new StringBuilder();
        sb.append("* ").append(npc.name());
        if (detailed) sb.append(" [persona: ").append(npc.identity().persona).append("]");
        sb.append(" ").append(p.getWorld() == reference.getWorld() ? (int) p.distanceTo(reference) + "m from speaker" : "in another dimension")
                .append(", hp ").append((int) Math.ceil(p.getHealth())).append(", food ").append(p.getHungerManager().getFoodLevel());
        if (p.isCreative()) sb.append(", creative");
        sb.append("\n  doing: ").append(npc.activity()).append("\n");
        if (detailed) {
            sb.append("  inventory: ").append(npc.inventorySummary(18)).append("\n");
            sb.append("  around: ").append(surroundings(npc)).append("\n");
            List<String> memory = npc.memory();
            if (!memory.isEmpty()) sb.append("  recently: ").append(String.join(" / ", memory)).append("\n");
            String longTerm = dev.mcattle.npc.NpcMemory.describe(npc);
            if (!longTerm.isEmpty()) sb.append("  long-term memory (follow it): ").append(longTerm).append("\n");
        }
        return sb.toString();
    }

    /** What the NPC can see around it, summarized: trees, ores, water, useful blocks, mobs. */
    static String surroundings(NpcBrain npc) {
        ServerWorld world = npc.world();
        BlockPos center = npc.npc().getBlockPos();
        boolean xray = npc.config().npc.allowXray;
        Map<String, Integer> nearest = new TreeMap<>();
        Map<String, java.util.Set<String>> kinds = new LinkedHashMap<>();
        List<BlockPos> found = new BlockSearch(world, center, 24, 12, Situation::interesting,
                (pos, s) -> xray || !WorldRules.isOre(s) || BlockSearch.isExposed(world, pos), 400).runNow();
        for (BlockPos pos : found) {
            BlockState s = world.getBlockState(pos);
            String cat = category(s);
            if (cat == null) continue;
            int d = (int) Math.sqrt(pos.getSquaredDistance(center));
            nearest.merge(cat, d, Math::min);
            if (cat.equals("trees") || cat.equals("ores")) {
                String name = Registries.BLOCK.getId(s.getBlock()).getPath().replace("_log", "").replace("deepslate_", "");
                kinds.computeIfAbsent(cat, k -> new java.util.TreeSet<>()).add(name);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : nearest.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey());
            if (kinds.containsKey(e.getKey())) sb.append(" (").append(String.join(" ", kinds.get(e.getKey()))).append(")");
            sb.append(" ").append(e.getValue()).append("m");
        }
        Map<String, Integer> mobs = new TreeMap<>();
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, npc.box().expand(24, 10, 24),
                e -> (e instanceof Monster || e instanceof AnimalEntity) && e.isAlive())) {
            String name = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
            mobs.merge(name, (int) e.distanceTo(npc.npc()), Math::min);
        }
        if (!mobs.isEmpty()) {
            sb.append(sb.length() > 0 ? "; " : "").append("mobs: ");
            sb.append(String.join(", ", mobs.entrySet().stream().limit(8).map(e -> e.getKey() + " " + e.getValue() + "m").toList()));
        }
        String biome = world.getBiome(center).getKey().map(k -> k.getValue().getPath()).orElse("?");
        sb.append(sb.length() > 0 ? "; " : "").append("biome ").append(biome).append(", y=").append(center.getY());
        if (!world.isSkyVisible(center.up())) sb.append(", underground/indoors");
        return sb.toString();
    }

    private static boolean interesting(BlockState s) {
        return category(s) != null;
    }

    private static String category(BlockState s) {
        if (s.isIn(Tags.LOGS)) return "trees";
        if (WorldRules.isOre(s)) return "ores";
        if (s.isOf(Blocks.WATER)) return "water";
        if (s.isOf(Blocks.LAVA)) return "lava";
        if (s.isOf(Blocks.CRAFTING_TABLE)) return "crafting_table";
        if (s.isOf(Blocks.FURNACE)) return "furnace";
        if (s.isOf(Blocks.CHEST) || s.isOf(Blocks.BARREL)) return "chest";
        if (s.isIn(Tags.BEDS)) return "bed";
        if (s.isIn(Tags.CROPS)) return "crops";
        if (s.isOf(Blocks.SAND)) return "sand";
        if (s.isOf(Blocks.GRAVEL)) return "gravel";
        return null;
    }

    /** "white_wool at (10,64,5), 3m away" for the block the player is looking at, or "". */
    public static String lookingAt(ServerPlayerEntity player) {
        net.minecraft.util.hit.HitResult hit = player.raycast(24.0, 0f, false);
        if (hit instanceof net.minecraft.util.hit.BlockHitResult bhr && hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
            BlockPos p = bhr.getBlockPos();
            BlockState s = player.getWorld().getBlockState(p);
            return Registries.BLOCK.getId(s.getBlock()).getPath() + " at " + pos(p) + ", " + (int) Math.sqrt(p.getSquaredDistance(player.getBlockPos())) + "m away";
        }
        return "";
    }

    static String timeOfDay(ServerWorld world) {
        long t = world.getTimeOfDay() % 24000;
        long day = world.getTimeOfDay() / 24000;
        String part = t < 1000 ? "dawn" : t < 6000 ? "morning" : t < 9000 ? "midday" : t < 12000 ? "afternoon"
                : t < 13000 ? "sunset" : t < 23000 ? "night" : "dawn";
        return "day " + day + ", " + part;
    }

    static String pos(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }

    static String itemName(net.minecraft.item.ItemStack s) {
        return s.isEmpty() ? "nothing" : Registries.ITEM.getId(s.getItem()).getPath().toLowerCase(Locale.ROOT);
    }
}
