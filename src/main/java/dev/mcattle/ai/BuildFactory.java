package dev.mcattle.ai;

import dev.mcattle.MCattle;
import dev.mcattle.core.build.BlueprintLibrary;
import dev.mcattle.core.llm.Design;
import dev.mcattle.core.llm.DesignCompiler;
import dev.mcattle.core.llm.LlmService;
import dev.mcattle.npc.NpcBrain;
import dev.mcattle.task.BuildJob;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Locale;

/**
 * Starts a build from a free-text description: the language model designs it (shapes, exact block states,
 * pixel layers) for this exact site, then the NPCs build it block by block. Without a model, or if the design
 * fails, the closest built-in blueprint is used instead.
 */
public final class BuildFactory {
    private BuildFactory() {
    }

    public static BuildJob start(NpcBrain npc, String description, String size, BlockPos anchor, Direction forward, boolean exactCorner) {
        String text = description == null || description.isBlank() ? "a nice house" : description.trim();
        BuildJob job = BuildJob.pending(shortName(text), anchor, forward, exactCorner);
        npc.manager().registerJob(job);
        ServerWorld world = npc.world();
        LlmService llm = npc.manager().mod().llm();
        if (llm == null || llm.blockedReason() != null) {
            job.apply(world, presetFor(text, size), "");
            return job;
        }
        String context = Situation.forDesign(npc, text, size, anchor, forward);
        int maxBlocks = Math.max(100, npc.config().llm.maxDesignBlocks);
        llm.design(context).thenAccept(result -> npc.server().execute(() -> {
            if (result.isPresent()) {
                Design design = result.get();
                List<BuildJob.LocalCell> cells = BuildJob.fromDesign(DesignCompiler.compile(design, 40, maxBlocks));
                if (!cells.isEmpty()) {
                    MCattle.LOGGER.info("Design '{}' for '{}': {} blocks", design.name(), text, cells.size());
                    job.apply(world, cells, design.summary());
                    return;
                }
            }
            job.apply(world, presetFor(text, size), "");
        }));
        return job;
    }

    /** Closest built-in blueprint to the description (offline fallback). */
    static List<BuildJob.LocalCell> presetFor(String text, String size) {
        String t = text.toLowerCase(Locale.ROOT);
        String structure = "house";
        for (String name : BlueprintLibrary.NAMES) {
            if (t.contains(name)) {
                structure = name;
                break;
            }
        }
        if (t.contains("castle") || t.contains("fortress")) structure = "fort";
        int scale = switch (size == null ? "" : size.toLowerCase(Locale.ROOT)) {
            case "small" -> 5;
            case "large", "big", "huge" -> 11;
            default -> 0;
        };
        String material = "";
        for (String m : new String[]{"cobblestone", "stone", "spruce", "birch", "dark oak", "acacia", "jungle", "cherry", "brick", "sandstone"}) {
            if (t.contains(m)) {
                material = m.replace(' ', '_');
                break;
            }
        }
        return BuildJob.presetCells(BlueprintLibrary.create(structure, scale, 0), material);
    }

    static String shortName(String text) {
        String t = text.toLowerCase(Locale.ROOT).replaceAll("^(a|an|the|me a|me an|us a)\\s+", "");
        String[] words = t.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, words.length); i++) {
            if (i > 0) sb.append(' ');
            sb.append(words[i]);
        }
        return sb.toString();
    }
}
