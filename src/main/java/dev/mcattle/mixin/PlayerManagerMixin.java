package dev.mcattle.mixin;

import dev.mcattle.npc.FakeNetworkHandler;
import dev.mcattle.npc.NpcPlayer;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** NPCs get a network handler that swallows packets instead of the vanilla one. */
@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {
    @Redirect(method = "onPlayerConnect", at = @At(value = "NEW", target = "net/minecraft/server/network/ServerPlayNetworkHandler"))
    private ServerPlayNetworkHandler mcattle$replaceNetworkHandler(MinecraftServer server, ClientConnection connection, ServerPlayerEntity player) {
        if (player instanceof NpcPlayer npc) {
            return new FakeNetworkHandler(server, connection, npc);
        }
        return new ServerPlayNetworkHandler(server, connection, player);
    }
}
