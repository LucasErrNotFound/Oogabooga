package com.yukimura.oogabooga;

import com.yukimura.oogabooga.bot.TerminatorBot;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

public final class DragonDeathHandler {

    private DragonDeathHandler() {
    }

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(entity instanceof EnderDragon)) {
                return;
            }
            TerminatorBot bot = TerminatorBot.getActiveBot();
            if (bot != null) {
                bot.onPlayerWon();
            }
        });
    }
}
