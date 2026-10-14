package dev.mcattle.voice;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import dev.mcattle.MCattle;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple Voice Chat plugin (the "voicechat" entrypoint in fabric.mod.json). Simple Voice Chat only loads this
 * class when it is installed, so MCattle runs fine without it. Microphone packets from players near NPCs are
 * decoded and handed to {@link VoiceInput}; nothing is changed or blocked, everyone still hears each other.
 */
public final class VoicechatIntegration implements VoicechatPlugin {
    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();

    @Override
    public String getPluginId() {
        return MCattle.MOD_ID;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, e -> {
            VoiceInput input = input();
            if (input != null) input.setVoiceChatPresent();
            MCattle.LOGGER.info("MCattle: listening to Simple Voice Chat for NPC commands");
        });
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    private static VoiceInput input() {
        MCattle mod = MCattle.instance();
        return mod == null ? null : mod.voice();
    }

    private void onMicrophone(MicrophonePacketEvent event) {
        VoiceInput input = input();
        VoicechatConnection sender = event.getSenderConnection();
        if (input == null || sender == null || sender.getPlayer() == null) return;
        UUID id = sender.getPlayer().getUuid();
        if (!input.wants(id)) {
            OpusDecoder stale = decoders.remove(id);
            if (stale != null) stale.close();
            return;
        }
        byte[] opus = event.getPacket().getOpusEncodedData();
        OpusDecoder decoder = decoders.computeIfAbsent(id, k -> event.getVoicechat().createDecoder());
        if (opus == null || opus.length == 0) {
            decoder.resetState();
            input.audio(id, new short[0]); // end of transmission
            return;
        }
        try {
            input.audio(id, decoder.decode(opus));
        } catch (RuntimeException e) {
            decoder.resetState();
        }
    }
}
