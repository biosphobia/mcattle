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

    /** Mood-flavored replacement when feelings run high, or null to use the normal line. */
    private static String moody(NpcBrain npc, String key, String player) {
        var m = npc.social().mind();
        double anger = m.get(dev.mcattle.core.social.Emotion.ANGER), sad = m.get(dev.mcattle.core.social.Emotion.SADNESS);
        if (anger < 0.5 && sad < 0.55) return null;
        boolean angry = anger >= sad;
        List<String> lines = switch (key) {
            case "ack" -> angry ? List.of("fine.", "ugh, ok", "whatever", "if i must", "yeah yeah") : List.of("ok...", "sure, i guess", "mhm", "fine...");
            case "done" -> angry ? List.of("there. happy?", "done. whatever") : List.of("done...", "there you go i guess");
            case "greeting" -> angry ? List.of("hm.", "oh. hi") : List.of("hey...", "hi {player}...", "oh, hey");
            default -> null;
        };
        return lines == null ? null : pick(npc.random(), lines).replace("{player}", player);
    }

    public static String ack(NpcBrain npc) {
        String m = moody(npc, "ack", "");
        if (m != null) return m;
        return styled(npc, "ack", List.of("on it", "ok!", "sure thing", "gotcha", "yep", "say less", "roger that", "k",
                "aye aye", "can do", "you got it", "alright alright", "consider it done", "bet"), "");
    }

    public static String done(NpcBrain npc) {
        String m = moody(npc, "done", "");
        if (m != null) return m;
        return styled(npc, "done", List.of("done!", "all done", "ez", "there you go", "finished", "and... done", "ta-da",
                "that's handled", "gg"), "");
    }

    public static String confused(NpcBrain npc) {
        return styled(npc, "confused", List.of("huh?", "what?", "say that again?", "i don't get it", "uhh what", "??",
                "not sure what you mean", "come again?"), "");
    }

    public static String hit(NpcBrain npc, String who) {
        dev.mcattle.core.social.Relationship r = relation(npc, who);
        if (r != null && r.affinity > 40) {
            return pick(npc.random(), List.of("{player}?? i thought we were friends", "ow... why, {player}?", "hey! that hurt, {player}",
                    "what did i do to you {player}")).replace("{player}", who);
        }
        if (r != null && r.hits >= 3) {
            return pick(npc.random(), List.of("AGAIN, {player}?", "every single time with you", "that's it, {player}",
                    "i'm keeping count, {player}")).replace("{player}", who);
        }
        return styled(npc, "hit", List.of("hey!", "ow, {player}!", "rude", "oh you wanna go?", "why would you do that",
                "bro what", "ok that's it", "you're gonna regret that"), who);
    }

    public static String death(NpcBrain npc) {
        return styled(npc, "death", List.of("bruh", "nooo my stuff", "that was lag", "i'll be back", "rip me", "not again",
                "ok that one was on me", "unlucky"), "");
    }

    public static String greeting(NpcBrain npc, String who) {
        dev.mcattle.core.social.Relationship r = relation(npc, who);
        if (r != null && r.affinity < -30) {
            return pick(npc.random(), List.of("oh. it's you", "{player}.", "great, {player}'s here", "don't talk to me {player}",
                    "still mad at you {player}")).replace("{player}", who);
        }
        if (r != null && r.affinity > 60) {
            long away = System.currentTimeMillis() - r.lastSeen;
            List<String> warm = away > 30 * 60_000L
                    ? List.of("{player}!! missed you", "there you are {player}!", "{player}!!! where have you been", "yay {player}'s back")
                    : List.of("heyy {player}!", "{player} :D", "my favorite person!", "ayy {player}!!");
            return pick(npc.random(), warm).replace("{player}", who);
        }
        String m = moody(npc, "greeting", who);
        if (m != null) return m;
        return styled(npc, "greeting", List.of("hey {player}", "yo {player}!", "hi {player}", "o/", "sup", "hello!",
                "ayy {player}"), who);
    }

    public static String scared(NpcBrain npc, String who) {
        return pick(npc.random(), List.of("stay away from me {player}", "nope nope nope", "please don't", "not you again",
                "{player} pls leave me alone")).replace("{player}", who);
    }

    public static String grudge(NpcBrain npc, String who) {
        return pick(npc.random(), List.of("remember what you did, {player}?", "payback time {player}", "you had this coming",
                "i haven't forgotten, {player}", "round two, {player}")).replace("{player}", who);
    }

    public static String refuse(NpcBrain npc, String who) {
        return pick(npc.random(), List.of("nah. not for you {player}", "after what you did? no", "ask someone else",
                "why would i help YOU", "lol no", "maybe if you apologized first")).replace("{player}", who);
    }

    public static String gift(NpcBrain npc, String who) {
        return pick(npc.random(), List.of("here {player}, for you", "got you something", "take this {player}",
                "thought you'd like this", "a little gift :)")).replace("{player}", who);
    }

    private static dev.mcattle.core.social.Relationship relation(NpcBrain npc, String who) {
        return npc.social().mind().byName(who);
    }

    public static String noLlm(NpcBrain npc) {
        return styled(npc, "simpleOnly", List.of("hmm, too complicated for my brain right now", "my brain's offline, keep it simple?",
                "i can only do simple stuff at the moment", "try simpler words?"), "");
    }
}
