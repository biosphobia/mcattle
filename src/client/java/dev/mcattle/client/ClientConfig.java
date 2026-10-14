package dev.mcattle.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Client-side voice settings (speech recognition and text-to-speech), stored in {@code config/mcattle-client.json}. */
public final class ClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // ---- push-to-talk speech recognition ----
    /**
     * "local": recognized on this computer with Vosk (free, offline, nothing to install: the engine and a ~40 MB
     * English model are downloaded once into config/mcattle/speech). Works in singleplayer.
     * "http": sent from this computer to an OpenAI-compatible /v1/audio/transcriptions endpoint (OpenAI, Groq...).
     * "server": the audio goes to the Minecraft server, which transcribes it with its own configured endpoint.
     */
    public String speechToText = "local";
    /** Vosk model to download for "local" (see https://alphacephei.com/vosk/models for other languages). */
    public String localModelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    public String sttEndpoint = "https://api.openai.com/v1/audio/transcriptions";
    public String sttApiKey = "";
    public String sttModel = "whisper-1";
    public String sttLanguage = "en";

    // ---- text-to-speech ----
    /** Read NPC chat aloud. */
    public boolean enabled = true;
    /**
     * "system": the voices built into the operating system (Windows speech, macOS say, espeak-ng / espeak on
     * Linux), free and offline.
     * "http": an OpenAI-compatible /v1/audio/speech endpoint (OpenAI, Kokoro-FastAPI, LocalAI, openedai-speech...)
     * for much more natural voices.
     */
    public String engine = "system";
    /** 0..1, multiplied with Minecraft's master and "Players" volume sliders. */
    public double volume = 1.0;
    /** Lines from NPCs further away than this are not spoken. */
    public double maxDistance = 48;

    public String httpEndpoint = "https://api.openai.com/v1/audio/speech";
    /** Leave empty for local servers; otherwise e.g. your OpenAI key. */
    public String httpApiKey = "";
    public String httpModel = "tts-1";
    /** Each NPC gets one of these voices (always the same one for the same NPC). */
    public List<String> httpVoices = List.of("alloy", "echo", "fable", "onyx", "nova", "shimmer");

    public static ClientConfig load(Path file) {
        ClientConfig config = null;
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                config = GSON.fromJson(reader, ClientConfig.class);
            } catch (Exception e) {
                System.err.println("[mcattle] Could not read " + file + ", using defaults: " + e);
            }
        }
        if (config == null) config = new ClientConfig();
        if (config.httpVoices == null || config.httpVoices.isEmpty()) config.httpVoices = List.of("alloy");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
        } catch (Exception e) {
            System.err.println("[mcattle] Could not write " + file + ": " + e);
        }
        return config;
    }
}
