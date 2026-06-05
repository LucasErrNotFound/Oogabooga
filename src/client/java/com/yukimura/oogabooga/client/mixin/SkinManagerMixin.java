package com.yukimura.oogabooga.client.mixin;

import com.mojang.authlib.GameProfile;
import com.yukimura.oogabooga.bot.TerminatorBot;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Mixin(SkinManager.class)
public class SkinManagerMixin {
    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void redirectBotSkin(GameProfile profile,
            CallbackInfoReturnable<CompletableFuture<Optional<PlayerSkin>>> callbackInfo) {
        if (!TerminatorBot.BOT_UUID.equals(profile.id())) return;
        Identifier skinIdentifier = Identifier.fromNamespaceAndPath("oogabooga", "entity/bot/herobrine");
        ClientAsset.ResourceTexture bodyTexture = new ClientAsset.ResourceTexture(skinIdentifier);
        PlayerSkin skin = new PlayerSkin(bodyTexture, null, null, PlayerModelType.WIDE, true);
        callbackInfo.setReturnValue(CompletableFuture.completedFuture(Optional.of(skin)));
    }
}
