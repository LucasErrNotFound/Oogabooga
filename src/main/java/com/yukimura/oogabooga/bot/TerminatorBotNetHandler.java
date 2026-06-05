package com.yukimura.oogabooga.bot;

import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

public class TerminatorBotNetHandler extends ServerGamePacketListenerImpl {

    public TerminatorBotNetHandler(
            MinecraftServer server,
            TerminatorBotConnection connection,
            ServerPlayer player,
            CommonListenerCookie cookie) {
        super(server, connection, player, cookie);
    }

    @Override
    public void tick() {}

    @Override
    protected void keepConnectionAlive() {}

    @Override
    public boolean isAcceptingMessages() {
        return true;
    }

    @Override
    public void onDisconnect(DisconnectionDetails details) {}
}
