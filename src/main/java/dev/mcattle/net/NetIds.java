package dev.mcattle.net;

import net.minecraft.util.Identifier;

/** Custom packet channels shared by the server and the optional client mod. */
public final class NetIds {
    private NetIds() {
    }

    /** C2S: push-to-talk pressed (NPCs nearby stop and listen). */
    public static final Identifier PTT_START = new Identifier("mcattle", "ptt_start");
    /** C2S: a chunk of 16 kHz mono 16-bit PCM audio: int sequence, boolean last, byte[] data. */
    public static final Identifier PTT_AUDIO = new Identifier("mcattle", "ptt_audio");
    /** C2S: push-to-talk released without audio (too short / cancelled). */
    public static final Identifier PTT_CANCEL = new Identifier("mcattle", "ptt_cancel");
    /** C2S: a transcript produced on the client (when the client does its own speech recognition). */
    public static final Identifier VOICE_TEXT = new Identifier("mcattle", "voice_text");
    /** S2C: status line for the push-to-talk HUD (e.g. "transcribing...", "heard: ..."). */
    public static final Identifier VOICE_STATUS = new Identifier("mcattle", "voice_status");

    /** S2C: an NPC said something; clients with text-to-speech read it aloud: varint entity id, int voice seed, string name, string text. */
    public static final Identifier SPEAK = new Identifier("mcattle", "speak");

    public static final int MAX_CHUNK = 30_000;
}
