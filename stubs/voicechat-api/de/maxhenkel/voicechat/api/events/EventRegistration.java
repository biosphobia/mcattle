// Compile-time stub of the Simple Voice Chat plugin API (de.maxhenkel.voicechat:voicechat-api) by henkelmax.
// Only the members MCattle uses; never packaged - the real classes come from the Simple Voice Chat mod at runtime.
package de.maxhenkel.voicechat.api.events;

import java.util.function.Consumer;

public interface EventRegistration {
    <T extends Event> void registerEvent(Class<T> eventClass, Consumer<T> onPacket, int priority);

    default <T extends Event> void registerEvent(Class<T> eventClass, Consumer<T> onPacket) {
        registerEvent(eventClass, onPacket, 0);
    }
}
