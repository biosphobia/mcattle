package dev.mcattle.npc;

import dev.mcattle.mixin.ClientConnectionAccessor;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.listener.PacketListener;

/** A connection that goes nowhere. An embedded channel makes {@link #isOpen()} true so vanilla treats it as live. */
public class FakeConnection extends ClientConnection {
    public FakeConnection() {
        super(NetworkSide.SERVERBOUND);
        ((ClientConnectionAccessor) this).mcattle$setChannel(new EmbeddedChannel());
    }

    @Override
    public void disableAutoRead() {
    }

    @Override
    public void handleDisconnection() {
    }

    @Override
    public void setPacketListener(PacketListener listener) {
    }
}
