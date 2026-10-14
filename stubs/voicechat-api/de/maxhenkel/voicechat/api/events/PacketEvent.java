// Compile-time stub of the Simple Voice Chat plugin API (de.maxhenkel.voicechat:voicechat-api) by henkelmax.
// Only the members MCattle uses; never packaged - the real classes come from the Simple Voice Chat mod at runtime.
package de.maxhenkel.voicechat.api.events;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.packets.Packet;

public interface PacketEvent<T extends Packet> extends ServerEvent {
    T getPacket();

    VoicechatConnection getSenderConnection();
}
