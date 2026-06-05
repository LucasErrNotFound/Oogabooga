package com.yukimura.oogabooga;

import com.yukimura.oogabooga.command.TerminatorCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Oogabooga implements ModInitializer {

	public static final String MOD_ID = "oogabooga";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register(
			(dispatcher, registryAccess, environment) -> TerminatorCommand.register(dispatcher));
	}
}