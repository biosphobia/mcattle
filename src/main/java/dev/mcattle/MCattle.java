package dev.mcattle;

import dev.mcattle.ai.BanterService;
import dev.mcattle.ai.CommandRouter;
import dev.mcattle.command.NpcCommand;
import dev.mcattle.config.MCattleConfig;
import dev.mcattle.core.llm.AnthropicProvider;
import dev.mcattle.core.llm.LlmProvider;
import dev.mcattle.core.llm.LlmService;
import dev.mcattle.core.llm.OpenAiCompatibleProvider;
import dev.mcattle.net.VoiceService;
import dev.mcattle.npc.NpcManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;

/**
 * MCattle: AI companions that join a Minecraft server as real players, play survival on their own and follow
 * spoken or typed orders.
 */
public final class MCattle implements ModInitializer {
    public static final String MOD_ID = "mcattle";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final Path configFile = FabricLoader.getInstance().getConfigDir().resolve("mcattle.json");
    private MCattleConfig config;
    @Nullable private LlmService llm;
    @Nullable private NpcManager npcs;
    @Nullable private MinecraftServer server;
    private final CommandRouter router = new CommandRouter(this);
    private final BanterService banter = new BanterService(this);
    private final VoiceService voice = new VoiceService(this);
    private final dev.mcattle.ai.ActivityManager activities = new dev.mcattle.ai.ActivityManager(this);

    @Override
    public void onInitialize() {
        config = MCattleConfig.load(configFile);
        voice.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> NpcCommand.register(dispatcher, this));

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            banter.reset();
            llm = createLlm();
            npcs = new NpcManager(s, this);
            npcs.loadAndRespawn();
            LOGGER.info("MCattle ready: {}", llm == null ? "no language model (built-in commands only)" : llm.usageReport());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            activities.clear();
            if (npcs != null) npcs.shutdown();
            if (llm != null) llm.close();
            npcs = null;
            llm = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (npcs == null) return;
            npcs.tick();
            activities.tick();
            banter.tick();
        });
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            try {
                router.onChat(sender, message.getSignedContent());
            } catch (Exception e) {
                LOGGER.error("Error handling chat for NPCs", e);
            }
        });
    }

    @Nullable
    private LlmService createLlm() {
        MCattleConfig.Llm c = config.llm;
        LlmProvider provider = null;
        String kind = c.provider == null ? "none" : c.provider.toLowerCase(Locale.ROOT);
        try {
            if (kind.equals("anthropic") || kind.equals("claude")) {
                String key = config.resolvedAnthropicKey();
                if (key.isEmpty()) {
                    LOGGER.warn("MCattle: no Anthropic API key (set llm.anthropicApiKey in config/mcattle.json or ANTHROPIC_API_KEY). NPCs will only understand simple commands.");
                } else {
                    provider = new AnthropicProvider(key, c.anthropicModel, c.anthropicBanterModel, c.effort, c.designEffort, c.requestTimeoutSeconds);
                }
            } else if (kind.equals("openai") || kind.equals("local") || kind.equals("ollama")) {
                provider = new OpenAiCompatibleProvider(c.openaiBaseUrl, c.openaiApiKey, c.openaiModel, c.requestTimeoutSeconds);
            }
        } catch (Exception e) {
            LOGGER.error("MCattle: could not set up the language model", e);
        }
        if (provider == null) return null;
        return new LlmService(provider, Math.max(1, c.maxRequestsPerMinute), c.maxTokensPerDay, LOGGER::warn);
    }

    public void reload() {
        config = MCattleConfig.load(configFile);
        if (llm != null) llm.close();
        llm = createLlm();
    }

    public MCattleConfig config() {
        return config;
    }

    @Nullable
    public LlmService llm() {
        return llm;
    }

    public NpcManager npcs() {
        return npcs;
    }

    public MinecraftServer server() {
        return server;
    }

    public dev.mcattle.ai.ActivityManager activities() {
        return activities;
    }

    public CommandRouter router() {
        return router;
    }
}
