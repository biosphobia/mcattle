package dev.mcattle.mixin;

import dev.mcattle.npc.NpcPlayer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.SleepManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** NPCs are not counted for "players sleeping" so they never stop real players from skipping the night. */
@Mixin(SleepManager.class)
public abstract class SleepManagerMixin {
    @Redirect(method = "update", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerEntity;isSpectator()Z"))
    private boolean mcattle$ignoreNpcs(ServerPlayerEntity player) {
        return player instanceof NpcPlayer || player.isSpectator();
    }
}
