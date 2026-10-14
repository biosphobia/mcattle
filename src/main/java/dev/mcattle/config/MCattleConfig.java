package dev.mcattle.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-side configuration, stored as JSON in {@code config/mcattle.json}.
 * Every field has a sensible default so the mod works (without AI) out of the box.
 */
public final class MCattleConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public Llm llm = new Llm();
    public Speech speech = new Speech();
    public Npc npc = new Npc();

    public static final class Llm {
        /** "anthropic", "openai" (any OpenAI-compatible server, e.g. Ollama / LM Studio / llama.cpp) or "none". */
        public String provider = "anthropic";
        /** Leave empty to read the ANTHROPIC_API_KEY environment variable. */
        public String anthropicApiKey = "";
        /** Model used to interpret player commands. claude-haiku-5-5 is ~40x cheaper if cost matters more than smarts. */
        public String anthropicModel = "claude-opus-5-5";
        /** Model used for idle banter between NPCs (cheap, short calls). */
        public String anthropicBanterModel = "claude-opus-5-5";
        /** Reasoning effort for command interpretation: low / medium / high. */
        public String effort = "low";
        /** Reasoning effort for free-form build designs: low keeps NPCs from standing around planning. */
        public String designEffort = "low";
        /** Largest design accepted, in blocks. */
        public int maxDesignBlocks = 2500;
        /** An activity (game, party...) ends after this many director rounds or minutes. */
        public int activityMaxRounds = 40;
        public int activityMaxMinutes = 30;

        public String openaiBaseUrl = "http://127.0.0.1:11434/v1";
        public String openaiApiKey = "";
        public String openaiModel = "qwen2.5:7b-instruct";

        /** Hard cap on LLM requests per minute across the whole server. */
        public int maxRequestsPerMinute = 12;
        /** Hard cap on tokens (input + output) per real-time day; 0 = unlimited. */
        public long maxTokensPerDay = 400_000;
        /** Try the free built-in command parser before calling the LLM. */
        public boolean localParserFirst = true;
        /** Let NPCs occasionally chat with each other / nearby players using the LLM. */
        public boolean banter = true;
        /** Minimum seconds between banter calls (server-wide). */
        public int banterIntervalSeconds = 420;
        public int requestTimeoutSeconds = 120;
    }

    public static final class Speech {
        /** Accept push-to-talk audio from clients that have the mod installed. */
        public boolean enabled = true;
        /**
         * OpenAI-compatible transcription endpoint (multipart "file" + "model").
         * Works with whisper.cpp's server (run with --inference-path /v1/audio/transcriptions),
         * faster-whisper-server/speaches, LocalAI, Groq or OpenAI.
         */
        public String endpoint = "http://127.0.0.1:8080/v1/audio/transcriptions";
        public String apiKey = "";
        public String model = "whisper-1";
        public String language = "en";
        /** Max seconds of audio accepted per utterance. */
        public int maxSeconds = 20;
        /**
         * Send what NPCs say to nearby players who have MCattle on their client, so it is read aloud there
         * (voice and engine are chosen in each player's config/mcattle-client.json).
         */
        public boolean textToSpeech = true;
        /** Players further away than this don't hear NPCs speak (text chat is unaffected). */
        public int textToSpeechRange = 48;
    }

    public static final class Npc {
        public int maxNpcs = 8;
        /** Only operators may spawn NPCs when true. */
        public boolean requireOpToSpawn = false;
        /** Players within this range of an NPC are "heard" by it in text chat when no name is used. */
        public int hearingRange = 32;
        /** Push-to-talk reaches NPCs within this range when no name is used. */
        public int voiceRange = 64;
        /** NPCs start playing on their own as soon as they spawn. */
        public boolean autonomousByDefault = true;
        /** Respawn NPCs that existed when the server stopped. */
        public boolean persist = true;
        /** NPCs may see ores through walls. Off = they must find or dig to them like a player. */
        public boolean allowXray = false;
        /** Small floating line above each NPC showing what it's doing, or that it's thinking / listening. */
        public boolean statusIndicator = true;
        /** NPCs fight back when a player hits them without having asked for a fight. */
        public boolean retaliate = true;
        /** Autonomous NPCs only break natural blocks (logs, stone, dirt, ores...) never player builds. */
        public boolean protectBuilds = true;
        /** Ticks an NPC stays dead before respawning. */
        public int respawnDelayTicks = 60;
        /** Chat radius for NPC messages; -1 = whole server like a real player. */
        public int chatRange = -1;
        /** Social gestures (sneak greeting, looking at players) chance multiplier. 0 disables. */
        public double socialness = 1.0;
        /**
         * Skin presets usable with /npc spawn. "steve" / "alex" are the built-in default skins; "account:Name"
         * copies the skin of that Minecraft account (fetched once from Mojang and cached). Any other
         * "account:" entries can be added freely, and "/npc spawn name account:SomeName" works without a preset.
         */
        public java.util.Map<String, String> skinPresets = defaultSkins();

        private static java.util.Map<String, String> defaultSkins() {
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
            m.put("steve", "steve");
            m.put("alex", "alex");
            m.put("herobrine", "account:MHF_Herobrine");
            m.put("villager", "account:MHF_Villager");
            m.put("zombie", "account:MHF_Zombie");
            m.put("skeleton", "account:MHF_Skeleton");
            m.put("creeper", "account:MHF_Creeper");
            m.put("enderman", "account:MHF_Enderman");
            m.put("pigman", "account:MHF_PigZombie");
            m.put("golem", "account:MHF_Golem");
            m.put("blaze", "account:MHF_Blaze");
            m.put("witherskeleton", "account:MHF_WSkeleton");
            return m;
        }
    }

    public static MCattleConfig load(Path file) {
        MCattleConfig config = null;
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                config = GSON.fromJson(reader, MCattleConfig.class);
            } catch (Exception e) {
                System.err.println("[mcattle] Could not read " + file + ", using defaults: " + e);
            }
        }
        if (config == null) config = new MCattleConfig();
        if (config.llm == null) config.llm = new Llm();
        if (config.speech == null) config.speech = new Speech();
        if (config.npc == null) config.npc = new Npc();
        config.save(file); // writes any newly added fields back out
        return config;
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            System.err.println("[mcattle] Could not write " + file + ": " + e);
        }
    }

    public String resolvedAnthropicKey() {
        if (llm.anthropicApiKey != null && !llm.anthropicApiKey.isBlank()) return llm.anthropicApiKey.trim();
        String env = System.getenv("ANTHROPIC_API_KEY");
        return env == null ? "" : env.trim();
    }
}
