package dev.mcattle.npc;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Canned chat lines for moments that don't deserve a language-model call. If the NPC has a remembered speaking
 * style, its restyled lines are used instead.
 */
public final class Lines {
    private Lines() {
    }

    private static String pick(Random r, List<String> lines) {
        return lines.get(r.nextInt(lines.size()));
    }

    private static String styled(NpcBrain npc, String key, List<String> fallback, String player) {
        Map<String, List<String>> custom = npc.identity().styleLines;
        List<String> lines = custom != null && custom.get(key) != null && !custom.get(key).isEmpty() ? custom.get(key) : fallback;
        return pick(npc.random(), lines).replace("{player}", player);
    }

    public static String ack(NpcBrain npc) {
        return styled(npc, "ack", List.of("on it", "ok!", "sure thing", "gotcha", "yep", "say less", "roger that", "k",
                "aye aye", "can do", "you got it", "alright alright", "consider it done", "bet"), "");
    }

    public static String done(NpcBrain npc) {
        return styled(npc, "done", List.of("done!", "all done", "ez", "there you go", "finished", "and... done", "ta-da",
                "that's handled", "gg"), "");
    }

    public static String confused(NpcBrain npc) {
        return styled(npc, "confused", List.of("huh?", "what?", "say that again?", "i don't get it", "uhh what", "??",
                "not sure what you mean", "come again?"), "");
    }

    public static String hit(NpcBrain npc, String who) {
        return styled(npc, "hit", List.of("hey!", "ow, {player}!", "rude", "oh you wanna go?", "why would you do that",
                "bro what", "ok that's it", "you're gonna regret that"), who);
    }

    public static String death(NpcBrain npc) {
        return styled(npc, "death", List.of("bruh", "nooo my stuff", "that was lag", "i'll be back", "rip me", "not again",
                "ok that one was on me", "unlucky"), "");
    }

    public static String greeting(NpcBrain npc, String who) {
        return styled(npc, "greeting", List.of("hey {player}", "yo {player}!", "hi {player}", "o/", "sup", "hello!",
                "ayy {player}"), who);
    }

    public static String noLlm(NpcBrain npc) {
        return styled(npc, "simpleOnly", List.of("hmm, too complicated for my brain right now", "my brain's offline, keep it simple?",
                "i can only do simple stuff at the moment", "try simpler words?"), "");
    }
}
