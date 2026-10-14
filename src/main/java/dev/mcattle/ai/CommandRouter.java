package dev.mcattle.ai;

import dev.mcattle.MCattle;
import dev.mcattle.core.intent.LocalIntentParser;
import dev.mcattle.core.intent.ParsedCommand;
import dev.mcattle.core.llm.LlmService;
import dev.mcattle.core.llm.Orders;
import dev.mcattle.npc.Lines;
import dev.mcattle.npc.NpcBrain;
import dev.mcattle.npc.NpcManager;
import dev.mcattle.npc.NpcPlayer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


/**
 * Decides who a chat line / voice transcript is for and what they should do:
 * the free built-in parser handles simple orders instantly, everything else goes to the language model with a
 * compact situation report, and one model call plans for every addressed NPC at once (so they coordinate).
 */
public final class CommandRouter {
    private final MCattle mod;

    public CommandRouter(MCattle mod) {
        this.mod = mod;
    }

    private NpcManager manager() {
        return mod.npcs();
    }

    /** Ordinary chat message. Ignored unless it names an NPC, or sounds like an order and NPCs are close. */
    public void onChat(ServerPlayerEntity player, String message) {
        if (player instanceof NpcPlayer || manager() == null || manager().all().isEmpty()) return;
        manager().recordChat(player.getName().getString(), message);
        handle(player, message, false, null);
    }

    public void onVoice(ServerPlayerEntity player, String transcript) {
        if (manager() == null || manager().all().isEmpty()) return;
        manager().recordChat(player.getName().getString(), "(voice) " + transcript);
        handle(player, transcript, true, null);
    }

    /** /npc tell: explicit target list (names or "all"). */
    public void onTell(ServerPlayerEntity player, String targets, String message) {
        List<NpcBrain> list = new ArrayList<>();
        if (targets.equalsIgnoreCase("all") || targets.equalsIgnoreCase("everyone")) {
            list.addAll(manager().all());
        } else {
            for (String n : targets.split(",")) {
                NpcBrain b = manager().byName(n.trim());
                if (b != null) list.add(b);
            }
        }
        if (list.isEmpty()) {
            player.sendMessage(net.minecraft.text.Text.literal("No NPC called " + targets), false);
            return;
        }
        handle(player, message, false, list);
    }

    private void handle(ServerPlayerEntity speaker, String text, boolean voice, @Nullable List<NpcBrain> forced) {
        var cfg = mod.config();
        java.util.Map<String, List<String>> aliases = new java.util.HashMap<>();
        for (NpcBrain b : manager().all()) aliases.put(b.name(), b.identity().aliases());
        ParsedCommand parsed = LocalIntentParser.parse(text, manager().names(), aliases, onlinePlayerNames());

        List<NpcBrain> addressed = new ArrayList<>();
        for (String n : parsed.addressed()) {
            NpcBrain b = manager().byName(n);
            if (b != null) addressed.add(b);
        }
        boolean everyone = parsed.everyone();
        List<NpcBrain> targets;
        if (forced != null) {
            targets = forced;
            addressed = forced;
        } else if (!addressed.isEmpty()) {
            targets = addressed;
        } else {
            double range = voice ? cfg.npc.voiceRange : cfg.npc.hearingRange;
            if (everyone) range = Math.max(range, 256);
            targets = new ArrayList<>();
            for (NpcBrain b : manager().all()) {
                if (b.npc().getWorld() == speaker.getWorld() && b.npc().distanceTo(speaker) <= range) targets.add(b);
            }
            // plain chat without a name only counts if it sounds like it's meant for the NPCs
            if (!voice && !everyone && !parsed.looksLikeCommand() && !(parsed.question() && targets.size() == 1 && closeTo(targets.get(0), speaker, 8))) {
                return;
            }
        }
        // an activity in progress (a game, a party...) hears everything said to its participants,
        // except plain "stop" style orders which end it the normal way
        boolean stopOrder = parsed.understood() && !parsed.complex()
                && parsed.steps().stream().anyMatch(s -> s.action().equals(dev.mcattle.core.llm.Skills.STOP) || s.action().equals(dev.mcattle.core.llm.Skills.AUTONOMOUS));
        ActivitySession activity = mod.activities().sessionFor(speaker, addressed);
        if (activity != null && !stopOrder && forced == null) {
            mod.activities().chat(activity, speaker, text);
            return;
        }
        if (targets.isEmpty()) return;
        for (NpcBrain b : targets) b.remember(speaker.getName().getString() + ": " + text);

        LlmService llm = mod.llm();
        String blocked = llm == null ? "no language model" : llm.blockedReason();
        boolean simple = parsed.understood() && !parsed.complex();
        if (simple && (cfg.llm.localParserFirst || blocked != null)) {
            applyLocal(targets, parsed, speaker, text);
            return;
        }
        if (blocked == null) {
            final List<NpcBrain> finalTargets = targets;
            final List<NpcBrain> finalAddressed = addressed;
            String situation = Situation.forCommand(manager(), speaker, text, voice, finalAddressed.isEmpty() ? finalTargets : finalAddressed, everyone);
            for (NpcBrain b : finalTargets) b.setThinking(20 * 20);
            llm.plan(situation).thenAccept(result -> mod.server().execute(() -> {
                for (NpcBrain b : finalTargets) b.setThinking(0);
                if (result.isPresent() && result.get().orders() != null && !result.get().orders().isEmpty()) {
                    applyOrders(result.get(), finalTargets, speaker, text);
                } else if (parsed.understood()) {
                    applyLocal(finalTargets, parsed, speaker, text);
                } else {
                    finalTargets.get(0).say(Lines.confused(finalTargets.get(0)));
                }
            }));
            return;
        }
        // no language model available
        if (parsed.understood()) {
            applyLocal(targets, parsed, speaker, text);
        } else {
            NpcBrain first = targets.get(0);
            first.say(parsed.question() ? Lines.confused(first) : Lines.noLlm(first));
            MCattle.LOGGER.debug("Not sending to LLM: {}", blocked);
        }
    }

    private static boolean closeTo(NpcBrain b, ServerPlayerEntity p, double d) {
        return b.npc().getWorld() == p.getWorld() && b.npc().distanceTo(p) <= d;
    }

    private List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (ServerPlayerEntity p : mod.server().getPlayerManager().getPlayerList()) {
            if (!(p instanceof NpcPlayer)) names.add(p.getGameProfile().getName());
        }
        return names;
    }

    private void applyLocal(List<NpcBrain> targets, ParsedCommand parsed, ServerPlayerEntity speaker, String text) {
        boolean first = true;
        for (NpcBrain npc : targets) {
            String say = first || npc.random().nextInt(3) == 0 ? Lines.ack(npc) : "";
            if (apply(npc, say, "replace", parsed.steps(), speaker)) npc.setGoal(text, speaker);
            first = false;
        }
    }

    private void applyOrders(Orders orders, List<NpcBrain> targets, ServerPlayerEntity speaker, @Nullable String text) {
        for (Orders.NpcOrder order : orders.orders()) {
            if (order == null || order.npc() == null) continue;
            NpcBrain npc = manager().byName(order.npc());
            if (npc == null) continue;
            if (!targets.contains(npc) && npc.npc().getWorld() != speaker.getWorld()) continue;
            List<Orders.Step> steps = order.steps() == null ? List.of() : order.steps();
            String mode = order.mode() == null ? "replace" : order.mode().toLowerCase(Locale.ROOT);
            if (apply(npc, order.say() == null ? "" : order.say(), mode, steps, speaker) && text != null) npc.setGoal(text, speaker);
            dev.mcattle.npc.NpcMemory.apply(npc, order.remember(), order.forget(), order.settings(), speaker);
        }
    }

    /**
     * Quietly asks the planner for another way to finish what the NPC was asked to do (after a step failed or it
     * died). Returns false when no model call can be made right now.
     */
    public boolean retry(NpcBrain npc, ServerPlayerEntity speaker, String problem) {
        LlmService llm = mod.llm();
        if (llm == null || llm.blockedReason() != null || npc.goal() == null) return false;
        String situation = Situation.forRetry(manager(), speaker, npc, problem, npc.goalRetries());
        npc.setGoalRetry();
        npc.setThinking(20 * 20);
        llm.plan(situation).thenAccept(result -> mod.server().execute(() -> {
            npc.setThinking(0);
            if (manager() == null || manager().byUuid(npc.npc().getUuid()) != npc) return;
            Orders.NpcOrder mine = null;
            if (result.isPresent() && result.get().orders() != null) {
                for (Orders.NpcOrder o : result.get().orders()) {
                    if (o != null && npc.name().equalsIgnoreCase(o.npc())) mine = o;
                }
            }
            if (mine == null || mine.steps() == null || mine.steps().isEmpty()) {
                npc.say(problem); // out of ideas
                npc.setGoal(null, null);
                return;
            }
            apply(npc, mine.say() == null ? "" : mine.say(), "replace", mine.steps(), speaker); // goal and try count stay
            dev.mcattle.npc.NpcMemory.apply(npc, mine.remember(), mine.forget(), mine.settings(), speaker);
        }));
        return true;
    }

    /** Executes one NPC's plan. Returns true if it was given tasks to work on. */
    public boolean apply(NpcBrain npc, String say, String mode, List<Orders.Step> steps, ServerPlayerEntity speaker) {
        TaskFactory.Built built = TaskFactory.build(npc, steps, speaker);
        if (say != null && !say.isBlank()) npc.say(say);
        switch (built.control()) {
            case STOP -> {
                npc.clearOrders();
                npc.setAutonomous(false);
                npc.setGoal(null, null);
            }
            case AUTONOMOUS -> {
                npc.setAutonomous(true);
                if (built.tasks().isEmpty()) npc.clearOrders();
            }
            default -> {
            }
        }
        boolean working = !built.tasks().isEmpty() && !"none".equals(mode);
        if (working) {
            npc.giveOrders(built.tasks(), !"append".equals(mode), speaker);
        }
        if (!built.problems().isEmpty() && (say == null || say.isBlank())) npc.say(built.problems().get(0));
        return working && !"append".equals(mode);
    }

}
