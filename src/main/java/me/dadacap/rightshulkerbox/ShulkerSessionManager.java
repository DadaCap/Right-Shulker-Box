package me.dadacap.rightshulkerbox;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.OptionalInt;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Tracks all currently open {@link ShulkerSession}s and manages their lifecycle across server ticks. */
public final class ShulkerSessionManager {
	/** Active sessions keyed by stack identity, so a moved/duplicated stack is treated as a different box. */
	private static final Map<ItemStack, ShulkerSession> SESSIONS = new IdentityHashMap<>();

	private ShulkerSessionManager() {
	}

	/** True if the given stack instance already has an open session (i.e. it's locked from normal handling). */
	public static boolean hasActiveSession(ItemStack stack) {
		return !stack.isEmpty() && SESSIONS.containsKey(stack);
	}

	/** Finds the session whose virtual menu matches the given menu instance, or null if none. */
	public static ShulkerSession sessionForMenu(AbstractContainerMenu menu) {
		for (ShulkerSession session : SESSIONS.values()) {
			for (ShulkerSession.ViewerRecord record : session.viewers().values()) {
				if (record.virtualMenu() == menu) {
					return session;
				}
			}
		}
		return null;
	}

	/**
	 * Opens (or joins an existing) session for the shulker box stack in the given slot, presenting the
	 * player with a 3-row chest menu backed by the session's virtual container instead of moving the box.
	 */
	public static void openFor(ServerPlayer player, AbstractContainerMenu outerMenu, Slot slot, ItemStack stack) {
		ShulkerSession session = SESSIONS.computeIfAbsent(stack,
				s -> new ShulkerSession(slot.container, slot.getContainerSlot(), s));

		if (!session.isHostStillValid()) {
			SESSIONS.remove(stack);
			return;
		}

		session.addViewer(player, outerMenu);

		outerMenu.setCarried(ItemStack.EMPTY);
		outerMenu.broadcastFullState();

		SimpleMenuProvider provider = new SimpleMenuProvider(
				(containerId, playerInventory, p) -> ChestMenu.threeRows(containerId, playerInventory, session.virtualContainer),
				stack.getHoverName()
		);

		OptionalInt result = player.openMenu(provider);
		if (result.isPresent()) {
			session.recordOpenedMenu(player, outerMenu, player.containerMenu);

			player.containerMenu.setCarried(ItemStack.EMPTY);
			player.containerMenu.broadcastFullState();
		} else {
			session.removeViewer(player);
			if (session.hasNoViewers()) {
				SESSIONS.remove(stack);
			}
		}
	}

	/**
	 * Stops tracking a player as a viewer of a session, whether their virtual menu was closed by the
	 * client (e.g. pressing escape) or forced closed by the server, and resyncs their inventory and
	 * carried (cursor) item so the client doesn't drift out of sync with the server's view of them.
	 */
	public static void closeViewer(ServerPlayer player, ShulkerSession session) {
		session.removeViewer(player);
		if (session.hasNoViewers()) {
			SESSIONS.remove(session.trackedStack);
		}

		// Force the client to resync their inventory, including the carried (cursor) item, to avoid
		// any desyncs between the server and client. Not doing this *will* cause the client's inventory
		// to be in a "frozen" state, where it doesn't update until the player clicks a slot / opens a menu.
		player.inventoryMenu.broadcastFullState();
	}

	/**
	 * Runs each server tick: invalidates sessions whose host box no longer exists, drops viewers who
	 * disconnected, closed their menu, or lost access to the outer container, and removes sessions
	 * once nobody is viewing them.
	 */
	public static void onServerTick(MinecraftServer server) {
		if (SESSIONS.isEmpty()) {
			return;
		}

		for (ShulkerSession session : new ArrayList<>(SESSIONS.values())) {
			if (!session.isHostStillValid()) {
				session.invalidate();
			}

			for (Map.Entry<ServerPlayer, ShulkerSession.ViewerRecord> entry : new ArrayList<>(session.viewers().entrySet())) {
				ServerPlayer player = entry.getKey();
				ShulkerSession.ViewerRecord record = entry.getValue();

				if (player.isRemoved()) {
					session.removeViewer(player);
					continue;
				}

				if (record.virtualMenu() == null || player.containerMenu != record.virtualMenu()) {
					session.removeViewer(player);
					continue;
				}

				if (!session.isReadOnly() && !record.outerMenu().stillValid(player)) {
					player.closeContainer();
					session.removeViewer(player);
				}
			}

			if (session.hasNoViewers()) {
				SESSIONS.remove(session.trackedStack);
			}
		}
	}
}
