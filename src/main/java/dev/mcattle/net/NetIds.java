package dev.mcattle.net;

import net.minecraft.util.Identifier;

/** Custom packet channels shared by the server and the optional client mod. */
public final class NetIds {
    private NetIds() {
    }

    /** S2C: an NPC said something; clients with text-to-speech read it aloud: varint entity id, int voice seed, string name, string text. */
    public static final Identifier SPEAK = new Identifier("mcattle", "speak");

}
