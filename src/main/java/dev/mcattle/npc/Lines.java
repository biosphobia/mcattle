package dev.mcattle.npc;

import java.util.List;
import java.util.Random;

/** Canned chat lines for moments that don't deserve a language-model call. */
public final class Lines {
    private Lines() {
    }

    private static String pick(Random r, List<String> lines) {
        return lines.get(r.nextInt(lines.size()));
    }

    public static String ack(Random r) {
        return pick(r, List.of("on it", "ok!", "sure thing", "gotcha", "yep", "say less", "roger that", "k",
                "aye aye", "can do", "you got it", "alright alright", "consider it done", "bet"));
    }

    public static String done(Random r) {
        return pick(r, List.of("done!", "all done", "ez", "there you go", "finished", "and... done", "ta-da",
                "that's handled", "gg"));
    }

    public static String confused(Random r) {
        return pick(r, List.of("huh?", "what?", "say that again?", "i don't get it", "uhh what", "??",
                "not sure what you mean", "come again?"));
    }

    public static String hit(Random r, String who) {
        return pick(r, List.of("hey!", "ow, " + who + "!", "rude", "oh you wanna go?", "why would you do that",
                "bro what", "ok that's it", "you're gonna regret that"));
    }

    public static String death(Random r) {
        return pick(r, List.of("bruh", "nooo my stuff", "that was lag", "i'll be back", "rip me", "not again",
                "ok that one was on me", "unlucky"));
    }

    public static String greeting(Random r, String who) {
        return pick(r, List.of("hey " + who, "yo " + who + "!", "hi " + who, "o/", "sup", "hello!", "ayy " + who));
    }

    public static String noLlm(Random r) {
        return pick(r, List.of("hmm, too complicated for my brain right now", "my brain's offline, keep it simple?",
                "i can only do simple stuff at the moment", "try simpler words?"));
    }
}
