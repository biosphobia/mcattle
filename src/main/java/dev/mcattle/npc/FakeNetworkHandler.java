package dev.mcattle.npc;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/** Network handler for NPCs: drops outgoing packets and applies teleports immediately (there is no client to confirm). */
public class FakeNetworkHandler extends ServerPlayNetworkHandler {
    public FakeNetworkHandler(MinecraftServer server, ClientConnection connection, NpcPlayer player) {
        super(server, connection, player);
    }

    @Override
    public void sendPacket(Packet<?> packet) {
    }

    @Override
    public void sendPacket(Packet<?> packet, @Nullable PacketCallbacks callbacks) {
    }

    @Override
    public void disconnect(Text reason) {
        // /kick and similar: the NPC leaves (it comes back on the next server start)
        if (player instanceof NpcPlayer npc && npc.brain() != null) {
            NpcBrain brain = npc.brain();
            npc.getServer().execute(() -> {
                if (brain.manager().byUuid(npc.getUuid()) == brain) brain.manager().remove(brain, false);
            });
        }
    }

    @Override
    public void requestTeleport(double x, double y, double z, float yaw, float pitch, Set<PositionFlag> flags) {
        super.requestTeleport(x, y, z, yaw, pitch, flags);
        if (player.getServerWorld().getPlayerByUuid(player.getUuid()) != null) {
            syncWithPlayerPosition();
            player.getServerWorld().getChunkManager().updatePosition(player);
        }
    }
}
