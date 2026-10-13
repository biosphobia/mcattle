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
        }
        if (!players.isEmpty()) {
            sb.append("Human players nearby: ").append(String.join(", ", players.stream().map(p -> p.getName().getString()).toList())).append("\n");
        }
        List<String> chat = manager.recentChat();
        if (!chat.isEmpty()) sb.append("Recent chat:\n").append(String.join("\n", chat)).append("\n");
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
