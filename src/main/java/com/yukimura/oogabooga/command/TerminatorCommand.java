package com.yukimura.oogabooga.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.yukimura.oogabooga.bot.TerminatorBot;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.phys.Vec3;

public class TerminatorCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("terminator")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("connect")
                    .executes(context -> executeConnect(context.getSource())))
                .then(Commands.literal("disconnect")
                    .executes(context -> executeDisconnect(context.getSource())))
                .then(Commands.literal("commence")
                    .executes(context -> executeCommence(context.getSource())))
                .then(Commands.literal("stop")
                    .executes(context -> executeStop(context.getSource())))
        );
    }

    private static int executeConnect(CommandSourceStack source) throws CommandSyntaxException {
        if (TerminatorBot.isActive()) {
            source.sendFailure(Component.literal(
                "A Terminator is already connected. Use /terminator disconnect first."));
            return 0;
        }
        ServerPlayer executor = source.getPlayerOrException();
        Vec3 position = executor.position();
        TerminatorBot.create(source.getServer(), position, executor.getYRot(), executor.getXRot());
        source.sendSuccess(
            () -> Component.literal("\"" + TerminatorBot.BOT_NAME + "\" has entered the server."),
            true
        );
        return 1;
    }

    private static int executeDisconnect(CommandSourceStack source) {
        if (!TerminatorBot.isActive()) {
            source.sendFailure(Component.literal("No Terminator is currently connected."));
            return 0;
        }
        TerminatorBot.remove(source.getServer());
        source.sendSuccess(
            () -> Component.literal("The Terminator has disconnected."),
            true
        );
        return 1;
    }

    private static int executeCommence(CommandSourceStack source) {
        TerminatorBot bot = TerminatorBot.getActiveBot();
        if (bot == null) {
            source.sendFailure(Component.literal(
                "No Terminator is connected. Use /terminator connect first."));
            return 0;
        }
        bot.commence();
        source.sendSuccess(
            () -> Component.literal("The Terminator has begun its hunt."),
            true
        );
        return 1;
    }

    private static int executeStop(CommandSourceStack source) {
        TerminatorBot bot = TerminatorBot.getActiveBot();
        if (bot == null) {
            source.sendFailure(Component.literal("No Terminator is currently connected."));
            return 0;
        }
        bot.stopHunt();
        source.sendSuccess(
            () -> Component.literal("The Terminator has stopped hunting."),
            true
        );
        return 1;
    }
}
