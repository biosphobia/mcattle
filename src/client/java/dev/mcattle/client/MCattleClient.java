package dev.mcattle.client;

import dev.mcattle.net.NetIds;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Optional client half: reads NPC chat aloud with text-to-speech (see config/mcattle-client.json). Talking to
 * NPCs by voice goes through the Simple Voice Chat mod and needs nothing from this side.
 */
public final class MCattleClient implements ClientModInitializer {
    private TextToSpeech tts;

    @Override
    public void onInitializeClient() {
        tts = new TextToSpeech(ClientConfig.load(FabricLoader.getInstance().getConfigDir().resolve("mcattle-client.json")));
        ClientPlayNetworking.registerGlobalReceiver(NetIds.SPEAK, (client, handler, buf, sender) -> {
            int entityId = buf.readVarInt();
            int seed = buf.readInt();
            String name = buf.readString(64);
            String text = buf.readString(1024);
            client.execute(() -> tts.speak(entityId, seed, name, text));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(tts::stopAll));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tts.tick(client));
    }
}
