package com.yukimura.oogabooga.mixin;

import com.yukimura.oogabooga.bot.TerminatorBot;
import com.yukimura.oogabooga.bot.TerminatorBotConnection;
import com.yukimura.oogabooga.bot.TerminatorBotNetHandler;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Redirect(
        method = "placeNewPlayer",
        at = @At(
            value = "NEW",
            target = "net/minecraft/server/network/ServerGamePacketListenerImpl"
        )
    )
    private ServerGamePacketListenerImpl redirectServerGamePacketListenerImplCreation(
            MinecraftServer server,
            Connection connection,
            ServerPlayer player,
            CommonListenerCookie cookie) {
        if (player instanceof TerminatorBot) {
            return new TerminatorBotNetHandler(server, (TerminatorBotConnection) connection, player, cookie);
        }
        return new ServerGamePacketListenerImpl(server, connection, player, cookie);
    }
}
