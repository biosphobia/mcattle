package dev.mcattle.client;

import dev.mcattle.net.NetIds;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;

/**
 * Optional client half: hold the push-to-talk key (default B) to talk to NPCs. While held a microphone badge
 * shows on screen and nearby NPCs stop to listen; on release the audio goes to the server for transcription.
 * NPC chat is read aloud with text-to-speech (see config/mcattle-client.json). Only active on servers that run MCattle.
 */
public final class MCattleClient implements ClientModInitializer {
    private static KeyBinding pushToTalk;
    private final MicRecorder mic = new MicRecorder();
    private TextToSpeech tts;
    private ClientConfig config;
    private LocalSpeech localSpeech;
    private final java.util.concurrent.ExecutorService sttWorker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcattle-stt");
        t.setDaemon(true);
        return t;
    });
    private String status = "";
    private long statusUntil;
    private long recordingSince;

    @Override
    public void onInitializeClient() {
        pushToTalk = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mcattle.push_to_talk", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B, "category.mcattle"));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        HudRenderCallback.EVENT.register(this::renderHud);
        ClientPlayNetworking.registerGlobalReceiver(NetIds.VOICE_STATUS, (client, handler, buf, sender) -> {
            String text = buf.readString(256);
            client.execute(() -> setStatus(text, 4000));
        });
        java.nio.file.Path configDir = FabricLoader.getInstance().getConfigDir();
        config = ClientConfig.load(configDir.resolve("mcattle-client.json"));
        tts = new TextToSpeech(config);
        localSpeech = new LocalSpeech(configDir.resolve("mcattle").resolve("speech"), config.localModelUrl);
        ClientPlayNetworking.registerGlobalReceiver(NetIds.SPEAK, (client, handler, buf, sender) -> {
            int entityId = buf.readVarInt();
            int seed = buf.readInt();
            String name = buf.readString(64);
            String text = buf.readString(1024);
            client.execute(() -> tts.speak(entityId, seed, name, text));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(tts::stopAll));
    }

    private boolean serverSupportsVoice() {
        return ClientPlayNetworking.canSend(NetIds.PTT_START);
    }

    private void tick(MinecraftClient client) {
        tts.tick(client);
        if (client.player == null || client.getNetworkHandler() == null) {
            if (mic.isRecording()) mic.stop();
            return;
        }
        boolean held = pushToTalk.isPressed() && client.currentScreen == null;
        if (held && !mic.isRecording()) {
            if (!serverSupportsVoice()) {
                if (pushToTalk.wasPressed()) setStatus("this server doesn't run MCattle", 3000);
                return;
            }
            try {
                mic.start();
                recordingSince = System.currentTimeMillis();
                ClientPlayNetworking.send(NetIds.PTT_START, PacketByteBufs.create());
                if (!"server".equalsIgnoreCase(config.speechToText) && !"http".equalsIgnoreCase(config.speechToText)
                        && !localSpeech.isLoaded()) {
                    // first use: start fetching/loading the recognizer while the player is still talking
                    sttWorker.submit(() -> localSpeech.prepare(msg -> client.execute(() -> setStatus(msg, 60000))));
                }
            } catch (Exception e) {
                setStatus("microphone unavailable: " + e.getMessage(), 5000);
            }
        } else if (!held && mic.isRecording()) {
            byte[] pcm = mic.stop();
            if (System.currentTimeMillis() - recordingSince < 250 || pcm.length == 0) {
                ClientPlayNetworking.send(NetIds.PTT_CANCEL, PacketByteBufs.create());
                return;
            }
            String mode = config.speechToText == null ? "local" : config.speechToText.toLowerCase(java.util.Locale.ROOT);
            if (mode.equals("server")) {
                sendAudio(pcm);
                setStatus("sending...", 3000);
            } else {
                transcribeHere(client, pcm, mode.equals("http"));
            }
        }
        while (pushToTalk.wasPressed()) {
            // consume clicks so they don't queue up
        }
    }

    /** Speech recognition on this computer (Vosk offline, or an HTTP endpoint); only the text goes to the server. */
    private void transcribeHere(MinecraftClient client, byte[] pcm, boolean http) {
        setStatus("listening...", 20000);
        sttWorker.submit(() -> {
            try {
                String text;
                if (http) {
                    var stt = new dev.mcattle.core.stt.SpeechToText(config.sttEndpoint, config.sttApiKey, config.sttModel, config.sttLanguage);
                    text = stt.transcribeBlocking(dev.mcattle.core.stt.Wav.fromPcm16Mono(pcm, dev.mcattle.core.stt.Wav.SAMPLE_RATE), "");
                } else {
                    text = localSpeech.transcribe(pcm, msg -> client.execute(() -> setStatus(msg, 60000)));
                }
                String heard = text == null ? "" : text.trim();
                client.execute(() -> {
                    if (client.getNetworkHandler() == null) return;
                    if (heard.isEmpty()) {
                        ClientPlayNetworking.send(NetIds.PTT_CANCEL, PacketByteBufs.create());
                        setStatus("didn't catch that", 2500);
                        return;
                    }
                    PacketByteBuf buf = PacketByteBufs.create();
                    buf.writeString(heard.length() > 500 ? heard.substring(0, 500) : heard, 512);
                    ClientPlayNetworking.send(NetIds.VOICE_TEXT, buf);
                    setStatus("", 0);
                });
            } catch (Exception e) {
                Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
                org.slf4j.LoggerFactory.getLogger("mcattle-stt").warn("Speech recognition failed", cause);
                client.execute(() -> {
                    if (client.getNetworkHandler() != null) ClientPlayNetworking.send(NetIds.PTT_CANCEL, PacketByteBufs.create());
                    setStatus("speech recognition failed: " + cause.getMessage(), 8000);
                    if (client.player != null) {
                        client.player.sendMessage(Text.literal("[MCattle] Speech recognition failed (" + cause.getMessage()
                                + "). You can switch \"speechToText\" in config/mcattle-client.json to \"http\" or \"server\".")
                                .formatted(net.minecraft.util.Formatting.GRAY), false);
                    }
                });
            }
        });
    }

    private void sendAudio(byte[] pcm) {
        int chunks = (pcm.length + NetIds.MAX_CHUNK - 1) / NetIds.MAX_CHUNK;
        for (int i = 0; i < chunks; i++) {
            byte[] part = Arrays.copyOfRange(pcm, i * NetIds.MAX_CHUNK, Math.min(pcm.length, (i + 1) * NetIds.MAX_CHUNK));
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeVarInt(i);
            buf.writeBoolean(i == chunks - 1);
            buf.writeByteArray(part);
            ClientPlayNetworking.send(NetIds.PTT_AUDIO, buf);
        }
    }

    private void setStatus(String text, long millis) {
        status = text;
        statusUntil = System.currentTimeMillis() + millis;
    }

    private void renderHud(DrawContext ctx, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden) return;
        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();
        int x = w / 2 - 60;
        int y = h - 82;
        if (mic.isRecording()) {
            ctx.fill(x, y, x + 120, y + 22, 0xAA000000);
            drawMic(ctx, x + 6, y + 3);
            ctx.drawTextWithShadow(client.textRenderer, Text.translatable("mcattle.hud.listening"), x + 22, y + 3, 0xFFFFFFFF);
            int bar = (int) Math.min(90, mic.level() * 600);
            ctx.fill(x + 22, y + 14, x + 22 + 90, y + 17, 0xFF333333);
            ctx.fill(x + 22, y + 14, x + 22 + bar, y + 17, 0xFF55FF55);
        } else if (!status.isEmpty() && System.currentTimeMillis() < statusUntil) {
            int tw = client.textRenderer.getWidth(status);
            ctx.fill(w / 2 - tw / 2 - 4, y + 6, w / 2 + tw / 2 + 4, y + 18, 0x88000000);
            ctx.drawCenteredTextWithShadow(client.textRenderer, status, w / 2, y + 8, 0xFFDDDDDD);
        }
    }

    /** A little red microphone drawn with rectangles (no texture needed). */
    private static void drawMic(DrawContext ctx, int x, int y) {
        int red = 0xFFE03030, dark = 0xFF7A1010, grey = 0xFFBBBBBB;
        ctx.fill(x + 3, y, x + 9, y + 9, red);          // capsule
        ctx.fill(x + 4, y + 1, x + 5, y + 8, dark);     // highlight stripe
        ctx.fill(x + 1, y + 6, x + 2, y + 11, grey);    // cradle left
        ctx.fill(x + 10, y + 6, x + 11, y + 11, grey);  // cradle right
        ctx.fill(x + 1, y + 11, x + 11, y + 12, grey);  // cradle bottom
        ctx.fill(x + 5, y + 12, x + 7, y + 15, grey);   // stand
        ctx.fill(x + 2, y + 15, x + 10, y + 16, grey);  // base
    }
}
