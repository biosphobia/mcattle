package dev.mcattle.net;

import dev.mcattle.MCattle;
import dev.mcattle.core.stt.SpeechToText;
import dev.mcattle.core.stt.Wav;
import dev.mcattle.npc.NpcBrain;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of push-to-talk: makes nearby NPCs stop and listen while a player holds the key, assembles the
 * audio, transcribes it and hands the text to the command router.
 */
public final class VoiceService {
    private final MCattle mod;
    private final Map<UUID, ByteArrayOutputStream> buffers = new HashMap<>();

    public VoiceService(MCattle mod) {
        this.mod = mod;
    }

    public void register() {
        ServerPlayNetworking.registerGlobalReceiver(NetIds.PTT_START, (server, player, handler, buf, sender) ->
                server.execute(() -> start(player)));
        ServerPlayNetworking.registerGlobalReceiver(NetIds.PTT_CANCEL, (server, player, handler, buf, sender) ->
                server.execute(() -> cancel(player)));
        ServerPlayNetworking.registerGlobalReceiver(NetIds.PTT_AUDIO, (server, player, handler, buf, sender) -> {
            int seq = buf.readVarInt();
            boolean last = buf.readBoolean();
            byte[] data = buf.readByteArray(NetIds.MAX_CHUNK);
            server.execute(() -> audio(player, seq, last, data));
        });
        ServerPlayNetworking.registerGlobalReceiver(NetIds.VOICE_TEXT, (server, player, handler, buf, sender) -> {
            String text = buf.readString(512);
            server.execute(() -> {
                stopListening(player);
                heard(player, text);
            });
        });
    }

    private void start(ServerPlayerEntity player) {
        if (!mod.config().speech.enabled || mod.npcs() == null) return;
        buffers.put(player.getUuid(), new ByteArrayOutputStream(64_000));
        double range = mod.config().npc.voiceRange;
        for (NpcBrain b : mod.npcs().all()) {
            if (b.npc().getWorld() == player.getWorld() && b.npc().distanceTo(player) <= range) b.startListening(player);
        }
    }

    private void cancel(ServerPlayerEntity player) {
        buffers.remove(player.getUuid());
        stopListening(player);
    }

    private void stopListening(ServerPlayerEntity player) {
        if (mod.npcs() == null) return;
        for (NpcBrain b : mod.npcs().all()) b.stopListening(player.getUuid());
    }

    private void audio(ServerPlayerEntity player, int seq, boolean last, byte[] data) {
        ByteArrayOutputStream buffer = buffers.get(player.getUuid());
        if (buffer == null) return;
        int max = Math.max(1, mod.config().speech.maxSeconds) * Wav.SAMPLE_RATE * 2;
        if (buffer.size() + data.length <= max) buffer.write(data, 0, data.length);
        if (!last) return;
        buffers.remove(player.getUuid());
        byte[] pcm = buffer.toByteArray();
        if (pcm.length < Wav.SAMPLE_RATE / 2) { // under 0.25 s: nothing said
            stopListening(player);
            return;
        }
        status(player, "transcribing...");
        var cfg = mod.config().speech;
        SpeechToText stt = new SpeechToText(cfg.endpoint, cfg.apiKey, cfg.model, cfg.language);
        String names = String.join(", ", mod.npcs().names());
        stt.transcribe(Wav.fromPcm16Mono(pcm, Wav.SAMPLE_RATE), names).whenComplete((text, err) -> mod.server().execute(() -> {
            stopListening(player);
            if (err != null) {
                MCattle.LOGGER.warn("Speech transcription failed", err);
                player.sendMessage(Text.literal("[mcattle] voice transcription failed - is a speech-to-text server configured? ("
                        + err.getCause() + ")").formatted(Formatting.RED), false);
                status(player, "");
                return;
            }
            heard(player, text);
        }));
    }

    private void heard(ServerPlayerEntity player, String text) {
        if (text == null || text.isBlank() || isNoise(text)) {
            status(player, "didn't catch that");
            return;
        }
        status(player, "");
        Text line = Text.literal("<" + player.getName().getString() + "> ").append(Text.literal(text.trim()))
                .append(Text.literal(" [voice]").formatted(Formatting.DARK_GRAY));
        mod.server().getPlayerManager().broadcast(line, false);
        mod.router().onVoice(player, text.trim());
    }

    /** Whisper hallucinates these on silence. */
    private static boolean isNoise(String text) {
        String t = text.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z ]", "").trim();
        return t.isEmpty() || t.equals("you") || t.equals("thank you") || t.equals("thanks for watching") || t.equals("bye");
    }

    private void status(ServerPlayerEntity player, String message) {
        if (!ServerPlayNetworking.canSend(player, NetIds.VOICE_STATUS)) return;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(message);
        ServerPlayNetworking.send(player, NetIds.VOICE_STATUS, buf);
    }
}
