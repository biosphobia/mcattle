// Compile-time stub of the Simple Voice Chat plugin API (de.maxhenkel.voicechat:voicechat-api) by henkelmax.
// Only the members MCattle uses; never packaged - the real classes come from the Simple Voice Chat mod at runtime.
package de.maxhenkel.voicechat.api;

import de.maxhenkel.voicechat.api.events.EventRegistration;

public interface VoicechatPlugin {
    String getPluginId();

    default void initialize(VoicechatApi api) {
    }

    default void registerEvents(EventRegistration registration) {
    }
}
