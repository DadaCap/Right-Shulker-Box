package me.dadacap.rightshulkerbox;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fabric entrypoint for the mod; wires up the server tick hook that drives shulker sessions. */
public class RightShulkerBoxMod implements ModInitializer {
	public static final String MOD_ID = "rightshulkerbox";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Registers the server tick listener that keeps {@link ShulkerSessionManager} sessions valid. */
	@Override
	public void onInitialize() {
		ServerTickEvents.START_SERVER_TICK.register(ShulkerSessionManager::onServerTick);
		LOGGER.info("Right-Shulker-Box initialized");
	}
}
