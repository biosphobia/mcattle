package dev.mcattle.ai;

import dev.mcattle.control.ItemQuery;
import dev.mcattle.control.ItemResolver;
import dev.mcattle.control.Knowledge;
import dev.mcattle.control.Tags;
import dev.mcattle.core.llm.Orders.Step;
import dev.mcattle.core.llm.Skills;
import dev.mcattle.npc.NpcBrain;
import dev.mcattle.task.*;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Turns skill calls (from the built-in parser or the language model) into executable tasks. */
public final class TaskFactory {
    /** Special results that aren't tasks. */
    public enum Control {NONE, STOP, AUTONOMOUS}

    public record Built(List<Task> tasks, Control control, List<String> problems) {
    }

    private TaskFactory() {
    }

    public static Built build(NpcBrain npc, List<Step> steps, @Nullable ServerPlayerEntity speaker) {
        List<Task> tasks = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Control control = Control.NONE;
        for (Step step : steps) {
            if (step == null) continue;
            String action = step.action().toLowerCase(Locale.ROOT).trim();
            if (action.equals(Skills.STOP)) {
                control = Control.STOP;
                continue;
            }
            if (action.equals(Skills.AUTONOMOUS)) {
                control = Control.AUTONOMOUS;
                continue;
            }
            try {
                Task t = one(npc, action, step, speaker);
                if (t != null) tasks.add(t);
            } catch (Problem p) {
                problems.add(p.getMessage());
            } catch (RuntimeException e) {
                dev.mcattle.MCattle.LOGGER.warn("Bad step {} for {}", step, npc.name(), e);
                problems.add("got confused, sorry");
            }
        }
        return new Built(tasks, control, problems);
    }

    static final class Problem extends Exception {
        Problem(String message) {
            super(message);
        }
    }

    private static Target target(NpcBrain npc, String spec, @Nullable ServerPlayerEntity speaker) throws Problem {
        Target t = Target.resolve(npc, spec, speaker);
        if (t == null) throw new Problem("who's " + (spec.isBlank() ? "that" : spec) + "?");
        return t;
    }

    private static ItemQuery item(String spec) throws Problem {
        ItemQuery q = ItemResolver.resolve(spec);
        if (q == null) throw new Problem("what's " + (spec.isBlank() ? "that" : "\"" + spec + "\"") + "?");
        return q;
    }

    @Nullable
    private static Task one(NpcBrain npc, String action, Step step, @Nullable ServerPlayerEntity speaker) throws Problem {
        int count = Math.max(0, step.count());
        switch (action) {
            case Skills.GOTO -> {
                return new GotoTask(target(npc, step.target(), speaker), step.intOption("range", 3));
            }
            case Skills.FOLLOW -> {
                return new FollowTask(target(npc, step.target(), speaker), step.intOption("distance", 3));
            }
            case Skills.OBTAIN -> {
                ItemQuery q = item(step.item());
                return new ObtainTask(q, npc.inventory().count(q) + Math.max(1, count));
            }
            case Skills.MINE -> {
                return mine(npc, step.item(), Math.max(1, count));
            }
            case Skills.CHOP -> {
                ItemQuery logs = step.item().isBlank() ? ItemQuery.tag(Tags.ITEM_LOGS, "logs", Items.OAK_LOG) : item(step.item());
                if (!logs.test(new ItemStack(Items.OAK_LOG)) && logs.preferred() != null && !Knowledge.isLog(logs.preferred())) {
                    logs = ItemQuery.tag(Tags.ITEM_LOGS, "logs", Items.OAK_LOG);
                }
                return new ChopTask(logs, npc.inventory().count(logs) + Math.max(1, count == 0 ? 16 : count), logs);
            }
            case Skills.CRAFT -> {
                ItemQuery q = item(step.item());
                if (q.preferred() == null) return new ObtainTask(q, npc.inventory().count(q) + Math.max(1, count));
                Item item = q.preferred();
                if (Knowledge.craftingRecipes(npc.server(), item).isEmpty()) {
                    return new ObtainTask(q, npc.inventory().count(q) + Math.max(1, count));
                }
                return new CraftTask(item, npc.inventory().count(item) + Math.max(1, count));
            }
            case Skills.SMELT -> {
                ItemQuery q = item(step.item());
                Item output = q.preferred();
                if (output == null || Knowledge.smeltingRecipes(npc.server(), output).isEmpty()) {
                    output = smeltResultOf(npc, output);
                    if (output == null) throw new Problem("can't smelt " + step.item());
                }
                int n = count > 0 ? count : Math.max(1, npc.inventory().count(q));
                return new SmeltTask(output, npc.inventory().count(output) + n, ItemQuery.of(output));
            }
            case Skills.GIVE -> {
                ItemQuery q = step.item().isBlank() ? ItemQuery.ANY : item(step.item());
                return new GiveTask(target(npc, step.target(), speaker), q, count, step.boolOption("obtain", true));
            }
            case Skills.DROP -> {
                return new DropTask(step.item().isBlank() ? ItemQuery.ANY : item(step.item()), count);
            }
            case Skills.EQUIP -> {
                return new EquipTask(item(step.item()), step.boolOption("obtain", false));
            }
            case Skills.ATTACK -> {
                String spec = step.target().isBlank() ? step.item() : step.target();
                Target t = target(npc, spec, speaker);
                Entity e = t.entity(npc);
                boolean defaultLethal = !(e instanceof PlayerEntity);
                if (e == npc.npc()) throw new Problem("i'm not fighting myself lol");
                return new AttackTask(t, step.boolOption("lethal", defaultLethal), step.intOption("duration", 0), false);
            }
            case Skills.GUARD -> {
                return new GuardTask(target(npc, step.target(), speaker));
            }
            case Skills.HUNT -> {
                String name = step.item().isBlank() ? step.target() : step.item();
                EntityType<?> type = Target.entityType(name);
                if (type == null) throw new Problem("what's a " + name + "?");
                return new HuntTask(type, null, Math.max(1, count));
            }
            case Skills.BUILD -> {
                return build(npc, step, speaker);
            }
            case Skills.PLACE -> {
                ItemQuery q = item(step.item());
                BlockPos pos;
                if (step.target().isBlank() || step.target().equalsIgnoreCase("speaker")) {
                    pos = inFrontOf(npc, speaker, 2);
                } else {
                    Vec3d p = target(npc, step.target(), speaker).position(npc);
                    if (p == null) throw new Problem("where's that?");
                    pos = BlockPos.ofFloored(p);
                }
                return new PlaceTask(pos, q);
            }
            case Skills.DIG -> {
                return dig(npc, step, speaker);
            }
            case Skills.EXPLORE -> {
                return new ExploreTask(step.intOption("distance", 96), 20 * 120);
            }
            case Skills.COLLECT -> {
                return new CollectItemsTask(16, s -> true, 20 * 30);
            }
            case Skills.EAT -> {
                return new EatTask(false);
            }
            case Skills.SLEEP -> {
                return new SleepTask();
            }
            case Skills.EMOTE -> {
                Target t = Target.resolve(npc, step.target(), speaker);
                return new EmoteTask(step.option("type", step.item().isBlank() ? "greet" : step.item()), t);
            }
            case Skills.SAY -> {
                String text = !step.text().isBlank() ? step.text() : !step.target().isBlank() ? step.target() : step.item();
                return text.isBlank() ? null : new SayTask(text);
            }
            case Skills.WAIT -> {
                return new WaitTask(20 * Math.max(1, Math.min(count == 0 ? 5 : count, 300)));
            }
            case Skills.DEPOSIT -> {
                return new ChestTask(true, step.item().isBlank() ? ItemQuery.ANY : item(step.item()), count);
            }
            case Skills.WITHDRAW -> {
                return new ChestTask(false, item(step.item()), count == 0 ? 64 : count);
            }
            case Skills.FARM -> {
                return new FarmTask(count == 0 ? 32 : count);
            }
            case Skills.HELP -> {
                NpcBrain other = npc.manager().byName(step.target());
                if (other == null) throw new Problem("help who?");
                if (other == npc) return null;
                Task theirs = other.currentTask();
                Task copy = theirs == null ? null : theirs.helpCopy(npc);
                for (Task queued : other.queuedTasks()) {
                    if (copy != null) break;
                    copy = queued.helpCopy(npc);
                }
                return copy != null ? copy : new FollowTask(Target.of(other.npc()), 3);
            }
            case Skills.REPORT -> {
                return new ReportTask();
            }
            case Skills.BREAK -> {
                Vec3d p = target(npc, step.target(), speaker).position(npc);
                if (p == null) throw new Problem("break what?");
                return new BreakBlockTask(BlockPos.ofFloored(p));
            }
            case Skills.USE -> {
                BlockPos at = null;
                if (!step.target().isBlank()) {
                    Vec3d p = target(npc, step.target(), speaker).position(npc);
                    if (p != null) at = BlockPos.ofFloored(p);
                }
                ItemQuery q = step.item().isBlank() ? null : item(step.item());
                return new UseTask(q, at);
            }
            case Skills.ACTIVITY -> {
                if (speaker == null) throw new Problem("who am i playing with?");
                String goal = !step.text().isBlank() ? step.text() : step.options();
                if (goal.isBlank()) throw new Problem("what are we doing?");
                List<NpcBrain> participants = new ArrayList<>();
                participants.add(npc);
                String who = step.target().trim();
                if (who.equalsIgnoreCase("all") || who.equalsIgnoreCase("everyone")) {
                    for (NpcBrain b : npc.manager().all()) {
                        if (b != npc && b.npc().getWorld() == speaker.getWorld() && b.npc().distanceTo(speaker) < 64) participants.add(b);
                    }
                } else {
                    for (String name : who.split("[,\\s]+")) {
                        NpcBrain b = npc.manager().byName(name);
                        if (b != null && !participants.contains(b)) participants.add(b);
                    }
                }
                return npc.manager().mod().activities().start(npc, goal, participants, speaker);
            }
            case Skills.LOOK -> {
                return new LookTask(target(npc, step.target(), speaker), 60);
            }
            default -> throw new Problem("don't know how to " + action);
        }
    }

    @Nullable
    private static Item smeltResultOf(NpcBrain npc, @Nullable Item input) {
        if (input == null) return null;
        ItemStack in = new ItemStack(input);
        for (SmeltingRecipe r : npc.server().getRecipeManager().listAllOfType(net.minecraft.recipe.RecipeType.SMELTING)) {
            for (Ingredient ing : r.getIngredients()) {
                if (ing.test(in)) return r.getOutput(npc.server().getRegistryManager()).getItem();
            }
        }
        return null;
    }

    private static Task mine(NpcBrain npc, String spec, int count) throws Problem {
        ItemQuery q = item(spec);
        List<Item> candidates = q.preferred() != null ? List.of(q.preferred()) : q.candidates();
        for (Item candidate : candidates.subList(0, Math.min(8, candidates.size()))) {
            if (candidate instanceof BlockItem bi) {
                BlockState state = bi.getBlock().getDefaultState();
                // an ore or stone block: count what it drops
                for (Item drop : new Item[]{Items.COAL, Items.RAW_IRON, Items.RAW_GOLD, Items.RAW_COPPER, Items.DIAMOND,
                        Items.EMERALD, Items.REDSTONE, Items.LAPIS_LAZULI, Items.QUARTZ, Items.COBBLESTONE,
                        Items.COBBLED_DEEPSLATE, Items.DIRT, Items.CLAY_BALL, Items.GLOWSTONE_DUST, Items.FLINT}) {
                    Knowledge.Source src = Knowledge.minedSource(drop);
                    if (src != null && src.blocks().test(state)) {
                        ItemQuery counted = ItemQuery.of(drop);
                        return new MineTask(src, counted, npc.inventory().count(counted) + count);
                    }
                }
            }
            Knowledge.Source src = Knowledge.minedSource(candidate);
            if (src != null) {
                ItemQuery counted = ItemQuery.of(candidate);
                return new MineTask(src, counted, npc.inventory().count(counted) + count);
            }
        }
        // not minable: maybe it's obtainable some other way
        return new ObtainTask(q, npc.inventory().count(q) + count);
    }

    private static Direction facing(NpcBrain npc, @Nullable ServerPlayerEntity speaker) {
        if (speaker != null) return speaker.getHorizontalFacing();
        return npc.npc().getHorizontalFacing();
    }

    private static BlockPos inFrontOf(NpcBrain npc, @Nullable ServerPlayerEntity speaker, int distance) {
        Entity who = speaker != null ? speaker : npc.npc();
        return who.getBlockPos().offset(facing(npc, speaker), distance);
    }

    private static Task build(NpcBrain npc, Step step, @Nullable ServerPlayerEntity speaker) throws Problem {
        String description = !step.text().isBlank() ? step.text()
                : !step.item().isBlank() ? step.item()
                : step.option("structure", "house").replace('_', ' ');
        String material = step.option("material", "");
        if (!material.isBlank() && !description.contains(material.replace('_', ' '))) description += " made of " + material.replace('_', ' ');
        String size = step.option("size", "");
        BlockPos anchor;
        Direction forward;
        boolean exact = false;
        String where = step.target();
        if (where.isBlank() || where.equalsIgnoreCase("speaker") || where.equalsIgnoreCase("me") || where.equalsIgnoreCase("here")) {
            Entity who = speaker != null ? speaker : npc.npc();
            anchor = who.getBlockPos();
            forward = facing(npc, speaker);
        } else {
            Target t = target(npc, where, speaker);
            Vec3d p = t.position(npc);
            if (p == null) throw new Problem("where's that?");
            anchor = BlockPos.ofFloored(p);
            exact = !t.isEntity();
            forward = t.entity(npc) instanceof PlayerEntity player ? player.getHorizontalFacing() : facing(npc, speaker);
        }
        BuildJob job = BuildFactory.start(npc, description, size, anchor, forward, exact);
        return new BuildTask(job);
    }

    private static Task dig(NpcBrain npc, Step step, @Nullable ServerPlayerEntity speaker) {
        String shape = step.option("shape", "hole").toLowerCase(Locale.ROOT);
        int depth = Math.max(1, Math.min(step.intOption("depth", shape.equals("tunnel") ? 12 : shape.equals("stairs") ? 10 : 3), 64));
        int width = Math.max(1, Math.min(step.intOption("width", shape.equals("room") ? 5 : shape.equals("hole") ? 3 : 1), 9));
        Direction forward = facing(npc, speaker);
        Direction right = forward.rotateYClockwise();
        BlockPos start = inFrontOf(npc, speaker, 2);
        List<BlockPos> cells = new ArrayList<>();
        switch (shape) {
            case "tunnel" -> {
                for (int i = 0; i < depth; i++) for (int y = 0; y < 2; y++) for (int w = 0; w < width; w++)
                    cells.add(start.offset(forward, i).offset(right, w - width / 2).up(y));
            }
            case "stairs" -> {
                for (int i = 0; i < depth; i++) for (int y = 0; y < 3; y++)
                    cells.add(start.offset(forward, i).up(y - i - 1));
            }
            case "room" -> {
                for (int x = 0; x < width; x++) for (int z = 0; z < width; z++) for (int y = 0; y < 3; y++)
                    cells.add(start.offset(right, x - width / 2).offset(forward, z).up(y));
            }
            default -> {
                for (int y = 1; y <= depth; y++) for (int x = 0; x < width; x++) for (int z = 0; z < width; z++)
                    cells.add(start.offset(right, x - width / 2).offset(forward, z).down(y));
            }
        }
        BuildJob job = BuildJob.excavation(npc.world(), shape, cells);
        npc.manager().registerJob(job);
        return new BuildTask(job);
    }
}
