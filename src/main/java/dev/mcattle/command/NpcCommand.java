package dev.mcattle.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.mcattle.MCattle;
import dev.mcattle.npc.NpcBrain;
import dev.mcattle.npc.NpcManager;
import dev.mcattle.npc.NpcPlayer;
import net.minecraft.command.CommandSource;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** The /npc command. */
public final class NpcCommand {
    private NpcCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, MCattle mod) {
        SuggestionProvider<ServerCommandSource> npcNames = (ctx, builder) -> {
            List<String> names = new ArrayList<>(mod.npcs() == null ? List.of() : mod.npcs().names());
            names.add("all");
            return CommandSource.suggestMatching(names, builder);
        };
        SuggestionProvider<ServerCommandSource> skins = (ctx, builder) -> {
            List<String> list = new ArrayList<>(mod.config().npc.skinPresets.keySet());
            list.add("random");
            return CommandSource.suggestMatching(list, builder);
        };

        dispatcher.register(literal("npc")
                .then(literal("spawn")
                        .executes(ctx -> spawn(ctx, mod, null, null))
                        .then(argument("name", StringArgumentType.word())
                                .suggests((ctx, b) -> CommandSource.suggestMatching(List.of("random"), b))
                                .executes(ctx -> spawn(ctx, mod, StringArgumentType.getString(ctx, "name"), null))
                                .then(argument("skin", StringArgumentType.greedyString()).suggests(skins)
                                        .executes(ctx -> spawn(ctx, mod, StringArgumentType.getString(ctx, "name"),
                                                StringArgumentType.getString(ctx, "skin"))))))
                .then(literal("remove")
                        .then(argument("npc", StringArgumentType.word()).suggests(npcNames)
                                .executes(ctx -> remove(ctx, mod, StringArgumentType.getString(ctx, "npc")))))
                .then(literal("list").executes(ctx -> list(ctx, mod)))
                .then(literal("skins").executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal("Skins: " + String.join(", ", mod.config().npc.skinPresets.keySet())
                            + ", random, or account:<any Minecraft username>"), false);
                    return 1;
                }))
                .then(literal("tell")
                        .then(argument("npc", StringArgumentType.word()).suggests(npcNames)
                                .then(argument("message", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                                            mod.router().onTell(p, StringArgumentType.getString(ctx, "npc"), StringArgumentType.getString(ctx, "message"));
                                            return 1;
                                        }))))
                .then(literal("stop")
                        .then(argument("npc", StringArgumentType.word()).suggests(npcNames)
                                .executes(ctx -> forEach(ctx, mod, b -> {
                                    b.clearOrders();
                                    b.setAutonomous(false);
                                }, "stopped"))))
                .then(literal("free")
                        .then(argument("npc", StringArgumentType.word()).suggests(npcNames)
                                .executes(ctx -> forEach(ctx, mod, b -> {
                                    b.clearOrders();
                                    b.setAutonomous(true);
                                }, "playing freely"))))
                .then(literal("tphere")
                        .then(argument("npc", StringArgumentType.word()).suggests(npcNames)
                                .executes(ctx -> {
                                    ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                                    return forEach(ctx, mod, b -> b.npc().teleport(p.getServerWorld(), p.getX(), p.getY(), p.getZ(), p.getYaw(), 0), "teleported");
                                })))
                .then(literal("inventory")
                        .then(argument("npc", StringArgumentType.word()).suggests(npcNames)
                                .executes(ctx -> inventory(ctx, mod, StringArgumentType.getString(ctx, "npc")))))
                .then(literal("gamemode")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(argument("npc", StringArgumentType.word()).suggests(npcNames)
                                .then(literal("survival").executes(ctx -> forEach(ctx, mod, b -> b.npc().changeGameMode(GameMode.SURVIVAL), "in survival")))
                                .then(literal("creative").executes(ctx -> forEach(ctx, mod, b -> b.npc().changeGameMode(GameMode.CREATIVE), "in creative")))))
                .then(literal("usage").executes(ctx -> {
                    String report = mod.llm() == null ? "LLM disabled" : mod.llm().usageReport();
                    ctx.getSource().sendFeedback(() -> Text.literal(report), false);
                    return 1;
                }))
                .then(literal("reload").requires(src -> src.hasPermissionLevel(2)).executes(ctx -> {
                    mod.reload();
                    ctx.getSource().sendFeedback(() -> Text.literal("MCattle config reloaded. " + (mod.llm() == null ? "LLM disabled" : mod.llm().usageReport())), true);
                    return 1;
                }))
        );
    }

    private static int spawn(CommandContext<ServerCommandSource> ctx, MCattle mod, String name, String skin) {
        ServerCommandSource src = ctx.getSource();
        if (mod.config().npc.requireOpToSpawn && !src.hasPermissionLevel(2)) {
            src.sendError(Text.literal("Only operators can spawn NPCs on this server"));
            return 0;
        }
        NpcManager npcs = mod.npcs();
        ServerPlayerEntity owner = src.getPlayer();
        Vec3d pos = src.getPosition();
        float yaw = src.getRotation().y;
        src.sendFeedback(() -> Text.literal("Spawning " + (name == null ? "a new NPC" : name) + "...").formatted(Formatting.GRAY), false);
        npcs.spawn(name, skin, owner, src.getWorld(), pos, yaw).whenComplete((brain, err) -> mod.server().execute(() -> {
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                src.sendError(Text.literal("Couldn't spawn: " + cause.getMessage()));
            } else {
                src.sendFeedback(() -> Text.literal(brain.name() + " joined (" + brain.identity().persona + ")").formatted(Formatting.GREEN), false);
            }
        }));
        return 1;
    }

    private static boolean mayControl(ServerCommandSource src, NpcBrain b) {
        if (src.hasPermissionLevel(2)) return true;
        ServerPlayerEntity p = src.getPlayer();
        return p != null && (b.identity().owner == null || b.identity().owner.equals(p.getUuidAsString()));
    }

    private static List<NpcBrain> select(CommandContext<ServerCommandSource> ctx, MCattle mod, String spec) {
        List<NpcBrain> list = new ArrayList<>();
        if (spec.equalsIgnoreCase("all")) list.addAll(mod.npcs().all());
        else {
            NpcBrain b = mod.npcs().byName(spec);
            if (b != null) list.add(b);
        }
        if (list.isEmpty()) ctx.getSource().sendError(Text.literal("No NPC called " + spec));
        return list;
    }

    private static int forEach(CommandContext<ServerCommandSource> ctx, MCattle mod, java.util.function.Consumer<NpcBrain> action, String verb) {
        String spec = StringArgumentType.getString(ctx, "npc");
        int n = 0;
        for (NpcBrain b : select(ctx, mod, spec)) {
            if (!mayControl(ctx.getSource(), b)) continue;
            action.accept(b);
            n++;
        }
        int count = n;
        if (count > 0) ctx.getSource().sendFeedback(() -> Text.literal(count + " NPC(s) " + verb), false);
        return count;
    }

    private static int remove(CommandContext<ServerCommandSource> ctx, MCattle mod, String spec) {
        int n = 0;
        for (NpcBrain b : select(ctx, mod, spec)) {
            if (!mayControl(ctx.getSource(), b)) {
                ctx.getSource().sendError(Text.literal("You can only remove your own NPCs"));
                continue;
            }
            mod.npcs().remove(b, true);
            n++;
        }
        int count = n;
        if (count > 0) ctx.getSource().sendFeedback(() -> Text.literal("Removed " + count + " NPC(s)"), true);
        return count;
    }

    private static int list(CommandContext<ServerCommandSource> ctx, MCattle mod) {
        if (mod.npcs().all().isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.literal("No NPCs. Try /npc spawn"), false);
            return 0;
        }
        for (NpcBrain b : mod.npcs().all()) {
            NpcPlayer p = b.npc();
            String line = String.format("%s  hp %d food %d @ %d %d %d - %s", b.name(), (int) Math.ceil(p.getHealth()),
                    p.getHungerManager().getFoodLevel(), p.getBlockX(), p.getBlockY(), p.getBlockZ(), b.activity());
            ctx.getSource().sendFeedback(() -> Text.literal(line), false);
        }
        return mod.npcs().all().size();
    }

    private static int inventory(CommandContext<ServerCommandSource> ctx, MCattle mod, String spec) {
        ServerPlayerEntity viewer = ctx.getSource().getPlayer();
        if (viewer == null) return 0;
        for (NpcBrain b : select(ctx, mod, spec)) {
            if (!mayControl(ctx.getSource(), b)) {
                ctx.getSource().sendError(Text.literal("That's not your NPC"));
                return 0;
            }
            NpcInventoryView view = new NpcInventoryView(b.npc().getInventory());
            viewer.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, playerInv, player) -> new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X5, syncId, playerInv, view, 5),
                    Text.literal(b.name() + "'s inventory")));
            return 1;
        }
        return 0;
    }

}
