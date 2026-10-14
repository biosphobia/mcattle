// Compile-time stub of the Simple Voice Chat plugin API (de.maxhenkel.voicechat:voicechat-api) by henkelmax.
// Only the members MCattle uses; never packaged - the real classes come from the Simple Voice Chat mod at runtime.
package de.maxhenkel.voicechat.api.opus;

public interface OpusDecoder {
    short[] decode(byte[] data);

    void resetState();

    boolean isClosed();

    void close();
}
