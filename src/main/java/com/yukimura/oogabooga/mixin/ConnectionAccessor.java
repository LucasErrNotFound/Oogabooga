package com.yukimura.oogabooga.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.net.SocketAddress;

@Mixin(Connection.class)
public interface ConnectionAccessor {

    @Accessor("channel")
    void setChannel(Channel channel);

    @Accessor("address")
    void setAddress(SocketAddress address);
}
