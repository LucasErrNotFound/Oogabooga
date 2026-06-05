package com.yukimura.oogabooga.bot;

import com.yukimura.oogabooga.mixin.ConnectionAccessor;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetSocketAddress;

public class TerminatorBotConnection extends Connection {

    public TerminatorBotConnection() {
        super(PacketFlow.SERVERBOUND);
        ((ConnectionAccessor) this).setChannel(new EmbeddedChannel());
        ((ConnectionAccessor) this).setAddress(new InetSocketAddress("localhost", 0));
    }

    @Override
    public void send(Packet<?> packet) {}

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener) {}

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {}

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public boolean isConnecting() {
        return false;
    }

    @Override
    public void tick() {}

    @Override
    public void setReadOnly() {}

    @Override
    public void handleDisconnection() {}

    @Override
    public void disconnect(Component message) {}

    @Override
    public void disconnect(DisconnectionDetails details) {}

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T packetListener) {}
}
