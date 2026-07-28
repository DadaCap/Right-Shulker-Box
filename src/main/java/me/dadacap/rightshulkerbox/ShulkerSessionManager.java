package me.dadacap.rightshulkerbox;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
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

	// Work queued here is run on the next server tick, so it does not happen in the middle of a click.
	private static final List<Runnable> PENDING_NEXT_TICK = new ArrayList<>();

	private ShulkerSessionManager() {
	}

	/** Queues a task to run at the start of a later server tick, never synchronously with the caller. */
	public static void deferToNextTick(Runnable task) {
		PENDING_NEXT_TICK.add(task);
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
			s -> new ShulkerSession(slot.container, slot.getContainerSlot(), s)
		);

		if (!session.isHostStillValid()) {
			SESSIONS.remove(stack);
			return;
		}

		// openFor() is scheduled a tick late (see the mixin), so if the opening click's packet was
		// ever processed more than once before that deferred call runs, a second call could land here
		// for a player who's already viewing. Re-running player.openMenu() in that case would force an
		// extra close-then-reopen of the just-opened menu within the same tick, so we skippin'
		ShulkerSession.ViewerRecord existing = session.viewers().get(player);
		if (existing != null && existing.virtualMenu() != null) {
			RightShulkerBoxMod.LOGGER.warn("openFor() called again for player {} while already viewing (duplicate deferred open?) - skipping", player.getName().getString());
			return;
		}

		session.addViewer(player, outerMenu);

		SimpleMenuProvider provider = new SimpleMenuProvider(
				(containerId, playerInventory, p) -> ChestMenu.threeRows(containerId, playerInventory, session.virtualContainer),
				stack.getHoverName()
		);

		OptionalInt result = player.openMenu(provider);
		if (result.isPresent()) {
			session.recordOpenedMenu(player, outerMenu, player.containerMenu);
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

		// This runs while AbstractContainerMenu#removed() is still executing, which Player#doCloseContainer()
		// calls before inventoryMenu.transferState(containerMenu).That transfer step can overwrite the
		// inventory menu's slot state with stale data from the closing virtual menu, so resyncing here
		// would be overwritten immediately, which is why we're deferring this to the next tick,
		// after container closing finishes.
		//
		// Also, we use initInventoryMenu() instead of broadcastFullState(), because initInventoryMenu()
		// reattaches the packet synchronizer, replaces the remote slot trackers, and sends a full state update,
		// while broadcastFullState() only resends what looks changed, so it can miss updates if its cached
		// state is wrong and never recover.
		deferToNextTick(() -> {
			player.initInventoryMenu();
			RightShulkerBoxMod.LOGGER.warn("Closed shulker session for player {} ({} viewers remain)", player.getName().getString(), session.viewers().size());
		});
	}

	/**
	 * Runs once each server tick to clean up old sessions and ensure menus are synced.
	 */
	public static void onServerTick(MinecraftServer server) {
		// Run anything queued for the next tick before cleaning up sessions.
		// This helps avoid menu and inventory problems when a player opens a box from their inventory.
		if (!PENDING_NEXT_TICK.isEmpty()) {
			List<Runnable> due = new ArrayList<>(PENDING_NEXT_TICK);
			PENDING_NEXT_TICK.clear();
			for (Runnable task : due) {
				task.run();
			}
		}

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
