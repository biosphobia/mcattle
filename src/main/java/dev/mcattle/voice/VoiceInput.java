package dev.mcattle.voice;

import dev.mcattle.MCattle;
import dev.mcattle.core.stt.SpeechToText;
import dev.mcattle.core.stt.Wav;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Turns what players say over Simple Voice Chat into NPC commands. Audio arrives (already decoded to 48 kHz PCM)
 * from {@link VoicechatIntegration} on the voice chat's network thread; only players near an NPC are listened to.
 * A pause in speech ends an utterance, which is recognized (Vosk offline by default, or an HTTP endpoint) and then
 * handled exactly like a chat message.
 */
public final class VoiceInput {
    private static final long SILENCE_MS = 700;

    private final MCattle mod;
    private final Map<UUID, Utterance> speaking = new ConcurrentHashMap<>();
    /** Players currently close enough to an NPC to be worth listening to (refreshed on the server thread). */
    private final Set<UUID> nearNpcs = ConcurrentHashMap.newKeySet();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcattle-speech");
        t.setDaemon(true);
        return t;
    });
    private LocalSpeech local;
    private volatile boolean voiceChatPresent;

    private static final class Utterance {
        final java.io.ByteArrayOutputStream pcm16k = new java.io.ByteArrayOutputStream(64_000);
        volatile long lastPacket;
        volatile boolean ended;
    }

    public VoiceInput(MCattle mod) {
        this.mod = mod;
    }

    public void setVoiceChatPresent() {
        voiceChatPresent = true;
    }

    public boolean voiceChatPresent() {
        return voiceChatPresent;
    }

    private LocalSpeech local() {
        if (local == null) {
            local = new LocalSpeech(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mcattle").resolve("speech"),
                    mod.config().speech.localModelUrl);
        }
        return local;
    }

    // ------------------------------------------------------------------ from the voice chat thread

    /** True if this player's audio should be decoded at all (cheap check, any thread). */
    public boolean wants(UUID player) {
        return mod.config().speech.enabled && nearNpcs.contains(player);
    }

    /** 48 kHz mono PCM from one voice packet (20 ms). An empty array marks the end of a transmission. */
    public void audio(UUID player, short[] pcm48k) {
        long now = System.currentTimeMillis();
        Utterance u = speaking.get(player);
        if (pcm48k.length == 0) {
            if (u != null) u.lastPacket = 0; // released push-to-talk: finish right away
            return;
        }
        if (u == null || u.ended) {
            u = new Utterance();
            speaking.put(player, u);
            UUID id = player;
            mod.server().execute(() -> startedTalking(id));
        }
        // 48 kHz -> 16 kHz: average every 3 samples
        byte[] out = new byte[pcm48k.length / 3 * 2];
        for (int i = 0, o = 0; i + 2 < pcm48k.length; i += 3, o += 2) {
            int s = (pcm48k[i] + pcm48k[i + 1] + pcm48k[i + 2]) / 3;
            out[o] = (byte) s;
            out[o + 1] = (byte) (s >> 8);
        }
        synchronized (u.pcm16k) {
            if (u.pcm16k.size() < Math.max(1, mod.config().speech.maxSeconds) * Wav.SAMPLE_RATE * 2) u.pcm16k.write(out, 0, out.length);
        }
        u.lastPacket = now;
    }

    // ------------------------------------------------------------------ server thread

    /** Every few ticks: who is near an NPC, and which utterances have ended. */
    public void tick() {
        if (mod.npcs() == null || mod.server() == null) return;
        if (mod.server().getTicks() % 20 == 0) {
            nearNpcs.clear();
            double range = mod.config().npc.voiceRange;
            for (ServerPlayerEntity p : mod.server().getPlayerManager().getPlayerList()) {
                if (p instanceof dev.mcattle.npc.NpcPlayer) continue;
                for (NpcBrain b : mod.npcs().all()) {
                    if (b.npc().getWorld() == p.getWorld() && b.npc().distanceTo(p) <= range) {
                        nearNpcs.add(p.getUuid());
                        break;
                    }
                }
            }
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Utterance> e : speaking.entrySet()) {
            Utterance u = e.getValue();
            if (u.ended || now - u.lastPacket < SILENCE_MS) continue;
            u.ended = true;
            speaking.remove(e.getKey(), u);
            byte[] pcm;
            synchronized (u.pcm16k) {
                pcm = u.pcm16k.toByteArray();
            }
            finished(e.getKey(), pcm);
        }
    }

    private void startedTalking(UUID id) {
        ServerPlayerEntity player = mod.server().getPlayerManager().getPlayer(id);
        if (player == null || mod.npcs() == null) return;
        double range = mod.config().speech.listenRange;
        for (NpcBrain b : mod.npcs().all()) {
            // close NPCs stop and turn to listen; the rest keep working (they still hear it)
            if (b.npc().getWorld() == player.getWorld() && b.npc().distanceTo(player) <= range) b.startListening(player);
        }
        if (!"http".equalsIgnoreCase(mod.config().speech.engine) && !local().isLoaded()) {
            worker.submit(() -> local().prepare(msg -> status(id, msg)));
        }
    }

    private void finished(UUID id, byte[] pcm) {
        ServerPlayerEntity player = mod.server().getPlayerManager().getPlayer(id);
        if (player == null || mod.npcs() == null) return;
        if (pcm.length < Wav.SAMPLE_RATE * 2 * 4 / 10) { // under 0.4 s: a cough, a click
            stopListening(id);
            return;
        }
        var cfg = mod.config().speech;
        String names = String.join(", ", mod.npcs().names());
        worker.submit(() -> {
            String text;
            try {
                if ("http".equalsIgnoreCase(cfg.engine)) {
                    text = new SpeechToText(cfg.endpoint, cfg.apiKey, cfg.model, cfg.language)
                            .transcribeBlocking(Wav.fromPcm16Mono(pcm, Wav.SAMPLE_RATE), names);
                } else {
                    text = local().transcribe(pcm, msg -> status(id, msg));
                }
            } catch (Exception e) {
                Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
                MCattle.LOGGER.warn("Speech recognition failed", cause);
                mod.server().execute(() -> {
                    stopListening(id);
                    ServerPlayerEntity p = mod.server().getPlayerManager().getPlayer(id);
                    if (p != null) p.sendMessage(Text.literal("[MCattle] couldn't understand voice: " + cause.getMessage()
                            + " (speech.engine in config/mcattle.json can be \"http\")").formatted(Formatting.GRAY), false);
                });
                return;
            }
            String heard = text == null ? "" : text.trim();
            mod.server().execute(() -> {
                stopListening(id);
                ServerPlayerEntity p = mod.server().getPlayerManager().getPlayer(id);
                if (p != null && !isNoise(heard)) heard(p, heard);
            });
        });
    }

    private void stopListening(UUID id) {
        if (mod.npcs() == null) return;
        for (NpcBrain b : mod.npcs().all()) b.stopListening(id);
    }

    private void heard(ServerPlayerEntity player, String text) {
        Text line = Text.literal("<" + player.getName().getString() + "> ").append(Text.literal(text))
                .append(Text.literal(" [voice]").formatted(Formatting.DARK_GRAY));
        if (mod.config().speech.showTranscripts) {
            for (ServerPlayerEntity p : mod.server().getPlayerManager().getPlayerList()) {
                if (p == player || p.getWorld() == player.getWorld() && p.distanceTo(player) <= mod.config().npc.voiceRange) p.sendMessage(line, false);
            }
        }
        mod.router().onVoice(player, text);
    }

    /** Download / loading progress for the speech model, shown above the player's hotbar. */
    private void status(UUID id, String message) {
        mod.server().execute(() -> {
            ServerPlayerEntity p = mod.server().getPlayerManager().getPlayer(id);
            if (p != null) p.sendMessage(Text.literal("[MCattle] " + message).formatted(Formatting.GRAY), true);
        });
    }

    /** Recognizers make these up from background noise. */
    static boolean isNoise(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").trim();
        return t.isEmpty() || t.equals("you") || t.equals("thank you") || t.equals("thanks for watching") || t.equals("bye")
                || t.equals("the") || t.equals("huh") || t.equals("uh") || t.equals("um") || t.equals("a") || t.equals("hmm");
    }

    public void close() {
        speaking.clear();
        nearNpcs.clear();
    }
}
