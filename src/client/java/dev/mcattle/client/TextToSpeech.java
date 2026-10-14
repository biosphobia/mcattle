package dev.mcattle.client;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reads NPC chat aloud. Speech is synthesized off the game thread (operating-system voices or an
 * OpenAI-compatible speech endpoint) and played as a sound that gets quieter with distance and pans toward
 * the NPC. Every NPC keeps the same voice, picked from its UUID.
 */
public final class TextToSpeech {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcattle-tts");
    private static final String[] MAC_VOICES = {"Samantha", "Daniel", "Karen", "Moira", "Tessa", "Rishi", "Fred", "Albert", "Junior", "Ralph", "Kathy"};
    private static final String[] ESPEAK_VARIANTS = {"m1", "m2", "m3", "m4", "m5", "m7", "f1", "f2", "f3", "f4"};

    private final ClientConfig config;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcattle-tts");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger queued = new AtomicInteger();
    private final List<Playing> playing = new CopyOnWriteArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile boolean warned;
    private String linuxCommand;

    private record Playing(int entityId, Clip clip) {
    }

    public TextToSpeech(ClientConfig config) {
        this.config = config;
    }

    /** Called on the client thread when an NPC says something. */
    public void speak(int entityId, int seed, String name, String text) {
        if (!config.enabled || "off".equalsIgnoreCase(config.engine)) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;
        Entity speaker = client.world.getEntityById(entityId);
        if (speaker != null && speaker.distanceTo(client.player) > config.maxDistance) return;
        String clean = clean(text);
        if (clean.isEmpty()) return;
        if (queued.get() >= 4) return; // a crowd is talking at once: skip rather than lag behind
        queued.incrementAndGet();
        worker.submit(() -> {
            try {
                Path wav = synthesize(seed, clean);
                if (wav == null) return;
                long until = System.currentTimeMillis() + 20_000;
                while (isSpeaking(entityId) && System.currentTimeMillis() < until) Thread.sleep(50); // one line at a time per NPC
                play(entityId, wav);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                warnOnce("Text-to-speech failed: " + e);
            } finally {
                queued.decrementAndGet();
            }
        });
    }

    private boolean isSpeaking(int entityId) {
        for (Playing p : playing) if (p.entityId == entityId && p.clip.isOpen()) return true;
        return false;
    }

    /** Keeps volume and left/right balance in step with where the NPC is relative to the listener. */
    public void tick(MinecraftClient client) {
        if (playing.isEmpty() || client.player == null || client.world == null) return;
        for (Playing p : playing) {
            Entity e = client.world.getEntityById(p.entityId);
            if (e == null) continue;
            applyPosition(client, p.clip, e.getEyePos());
        }
    }

    public void stopAll() {
        for (Playing p : playing) {
            p.clip.stop();
            p.clip.close();
        }
        playing.clear();
    }

    // ------------------------------------------------------------------ playback

    private void play(int entityId, Path wav) throws Exception {
        AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile());
        AudioFormat f = in.getFormat();
        if (f.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
            AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, f.getSampleRate(), 16, f.getChannels(),
                    f.getChannels() * 2, f.getSampleRate(), false);
            in = AudioSystem.getAudioInputStream(pcm, in);
        }
        Clip clip = AudioSystem.getClip();
        clip.open(in);
        in.close();
        Files.deleteIfExists(wav);
        Playing p = new Playing(entityId, clip);
        clip.addLineListener(event -> {
            if (event.getType() == LineEvent.Type.STOP) {
                clip.close();
                playing.remove(p);
            }
        });
        MinecraftClient client = MinecraftClient.getInstance();
        Entity e = client.world == null ? null : client.world.getEntityById(entityId);
        if (e != null) applyPosition(client, clip, e.getEyePos());
        else setGain(clip, volume(client));
        playing.add(p);
        clip.start();
    }

    private float volume(MinecraftClient client) {
        return (float) (config.volume * client.options.getSoundVolume(SoundCategory.MASTER)
                * client.options.getSoundVolume(SoundCategory.PLAYERS));
    }

    private void applyPosition(MinecraftClient client, Clip clip, Vec3d source) {
        if (client.player == null) return;
        Vec3d ear = client.player.getEyePos();
        double dist = ear.distanceTo(source);
        float falloff = (float) MathHelper.clamp(1.0 - dist / Math.max(1, config.maxDistance), 0, 1);
        setGain(clip, volume(client) * falloff * falloff);
        // pan: how far to the listener's right the NPC is
        double dx = source.x - ear.x, dz = source.z - ear.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        float pan = 0;
        if (len > 0.5) {
            float yaw = client.player.getYaw() * MathHelper.RADIANS_PER_DEGREE;
            double rightX = -MathHelper.cos(yaw), rightZ = -MathHelper.sin(yaw);
            pan = (float) ((dx * rightX + dz * rightZ) / len) * 0.8f;
        }
        for (FloatControl.Type type : new FloatControl.Type[]{FloatControl.Type.PAN, FloatControl.Type.BALANCE}) {
            if (clip.isControlSupported(type)) {
                ((FloatControl) clip.getControl(type)).setValue(pan);
                break;
            }
        }
    }

    private static void setGain(Clip clip, float linear) {
        if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
        FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
        float db = (float) (20 * Math.log10(Math.max(linear, 1.0E-4)));
        gain.setValue(MathHelper.clamp(db, gain.getMinimum(), gain.getMaximum()));
    }

    // ------------------------------------------------------------------ synthesis

    private Path synthesize(int seed, String text) throws Exception {
        if ("http".equalsIgnoreCase(config.engine)) return synthesizeHttp(seed, text);
        return synthesizeSystem(seed, text);
    }

    private Path synthesizeHttp(int seed, String text) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.httpModel);
        body.addProperty("input", text);
        body.addProperty("voice", config.httpVoices.get(Math.floorMod(seed, config.httpVoices.size())));
        body.addProperty("response_format", "wav");
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(config.httpEndpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (config.httpApiKey != null && !config.httpApiKey.isBlank()) req.header("Authorization", "Bearer " + config.httpApiKey.trim());
        HttpResponse<byte[]> res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() / 100 != 2) {
            warnOnce("Speech endpoint answered HTTP " + res.statusCode() + ": "
                    + new String(res.body(), 0, Math.min(200, res.body().length), StandardCharsets.UTF_8));
            return null;
        }
        Path out = Files.createTempFile("mcattle-tts", ".wav");
        Files.write(out, res.body());
        return out;
    }

    private Path synthesizeSystem(int seed, String text) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path in = Files.createTempFile("mcattle-tts", ".txt");
        Path out = Files.createTempFile("mcattle-tts", ".wav");
        Files.writeString(in, text, StandardCharsets.UTF_8);
        int pick = Math.floorMod(seed, 1 << 16);
        try {
            boolean ok;
            if (os.contains("win")) {
                int rate = Math.floorMod(seed >> 8, 4) - 1; // -1..2
                String script = "$ErrorActionPreference='Stop';"
                        + "Add-Type -AssemblyName System.Speech;"
                        + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;"
                        + "$v=@($s.GetInstalledVoices() | Where-Object { $_.Enabled });"
                        + "if ($v.Count -gt 0) { $s.SelectVoice($v[" + pick + " % $v.Count].VoiceInfo.Name) };"
                        + "$s.Rate=" + rate + ";"
                        + "$s.SetOutputToWaveFile(" + psQuote(out) + ");"
                        + "$s.Speak([IO.File]::ReadAllText(" + psQuote(in) + ",[Text.Encoding]::UTF8));"
                        + "$s.Dispose()";
                ok = run(List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script));
            } else if (os.contains("mac")) {
                String rate = Integer.toString(170 + Math.floorMod(seed >> 8, 50));
                String voice = MAC_VOICES[pick % MAC_VOICES.length];
                List<String> base = List.of("say", "-r", rate, "-o", out.toString(), "--file-format=WAVE", "--data-format=LEI16@22050", "-f", in.toString());
                List<String> withVoice = new ArrayList<>(List.of("say", "-v", voice));
                withVoice.addAll(base.subList(1, base.size()));
                ok = run(withVoice) || run(base); // fall back to the default voice if that one isn't installed
            } else {
                String cmd = linuxCommand();
                if (cmd == null) {
                    warnOnce("No text-to-speech program found. Install espeak-ng (or set engine to \"http\" in config/mcattle-client.json).");
                    return null;
                }
                String variant = ESPEAK_VARIANTS[pick % ESPEAK_VARIANTS.length];
                String pitch = Integer.toString(30 + Math.floorMod(seed >> 8, 40));
                String speed = Integer.toString(155 + Math.floorMod(seed >> 4, 35));
                ok = run(List.of(cmd, "-v", "en+" + variant, "-p", pitch, "-s", speed, "-w", out.toString(), "-f", in.toString()));
            }
            if (!ok || Files.size(out) < 100) {
                Files.deleteIfExists(out);
                return null;
            }
            return out;
        } finally {
            Files.deleteIfExists(in);
        }
    }

    private String linuxCommand() {
        if (linuxCommand == null) {
            linuxCommand = "";
            for (String c : new String[]{"espeak-ng", "espeak"}) {
                try {
                    if (run(List.of(c, "--version"))) {
                        linuxCommand = c;
                        break;
                    }
                } catch (Exception ignored) {
                    // not installed
                }
            }
        }
        return linuxCommand.isEmpty() ? null : linuxCommand;
    }

    private boolean run(List<String> command) throws IOException, InterruptedException {
        Process p;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            warnOnce("Couldn't start " + command.get(0) + ": " + e.getMessage());
            return false;
        }
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return false;
        }
        return p.exitValue() == 0;
    }

    private static String psQuote(Path path) {
        return "'" + path.toAbsolutePath().toString().replace("'", "''") + "'";
    }

    /** Strips formatting codes and things that sound silly when read out. */
    static String clean(String text) {
        String s = text.replaceAll("§.", "")
                .replaceAll("https?://\\S+", "a link")
                .replaceAll("[^\\p{L}\\p{N}\\p{P}\\p{Zs}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    private void warnOnce(String message) {
        if (warned) return;
        warned = true;
        LOGGER.warn(message);
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(net.minecraft.text.Text.literal("[MCattle] " + message).formatted(net.minecraft.util.Formatting.GRAY), false);
            }
        });
    }
}
