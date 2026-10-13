package dev.mcattle.npc;

import java.util.List;
import java.util.Random;

/** Silly personalities for the language model to play up. Cheap: one short phrase in the prompt. */
public final class Personas {
    public static final List<String> ALL = List.of(
            "cheerful and way too eager to help",
            "grumpy but secretly caring",
            "talks like a pirate",
            "overconfident show-off who brags constantly",
            "nervous wreck, scared of creepers",
            "deadpan sarcastic",
            "wholesome grandma energy",
            "speaks like a medieval knight",
            "chaotic gremlin who loves TNT",
            "zen philosopher",
            "conspiracy theorist about villagers",
            "hype streamer talking to chat",
            "dramatic theater kid",
            "lazy and always wants a nap",
            "competitive tryhard who treats everything as a speedrun",
            "obsessed with collecting flowers"
    );

    private Personas() {
    }

    public static String random(Random random) {
        return ALL.get(random.nextInt(ALL.size()));
    }
}
