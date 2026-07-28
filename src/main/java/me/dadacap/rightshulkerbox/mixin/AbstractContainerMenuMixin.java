package me.dadacap.rightshulkerbox.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.dadacap.rightshulkerbox.ShulkerSession;
import me.dadacap.rightshulkerbox.ShulkerSessionManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Intercepts inventory clicks on any container menu to implement right-click-to-open shulker
 * boxes in place, lock stacks that already have an open session, and block moving shulker
 * boxes in/out of a session's virtual grid while it's open.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
	/**
	 * Runs before the vanilla click handler. Cancels the click (and opens a session instead) when the
	 * click is a right-click on an openable shulker box, when the clicked stack already has an active
	 * session (locked), when the session is read-only, or when the click would move a shulker box into
	 * or out of a session's virtual grid.
	 */
	@Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
	private void rightshulkerbox$onClicked(int slotId, int button, ContainerInput clickType, Player player, CallbackInfo ci) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (slotId < 0) {
			return;
		}

		AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;

		Slot slot;
		try {
			slot = self.getSlot(slotId);
		} catch (IndexOutOfBoundsException e) {
			return;
		}

		ShulkerSession viewedSession = ShulkerSessionManager.sessionForMenu(self);
		if (viewedSession != null && viewedSession.isReadOnly() && slotId < ShulkerSession.GRID_SIZE) {
			ci.cancel();
			return;
		}
		if (viewedSession != null) {
			ItemStack incoming = ItemStack.EMPTY;
			boolean targetsGrid = false;

			if ((clickType == ContainerInput.PICKUP || clickType == ContainerInput.QUICK_CRAFT) && slotId < ShulkerSession.GRID_SIZE) {
				targetsGrid = true;
				incoming = self.getCarried();
			} else if (clickType == ContainerInput.SWAP && slotId < ShulkerSession.GRID_SIZE && button >= 0 && button < 9) {
				targetsGrid = true;
				incoming = player.getInventory().getItem(button);
			} else if (clickType == ContainerInput.QUICK_MOVE && slotId >= ShulkerSession.GRID_SIZE) {
				targetsGrid = true;
				incoming = slot.getItem();
			}

			if (targetsGrid && ShulkerSession.isShulkerBox(incoming)) {
				ci.cancel();
				return;
			}
		}

		if (!slot.hasItem()) {
			return;
		}

		ItemStack stack = slot.getItem();

		boolean isOpenAttempt = clickType == ContainerInput.PICKUP
				&& button == 1
				&& self.getCarried().isEmpty()
				&& slot.mayPickup(player)
				&& ShulkerSession.isOpenableShulkerBox(stack);

		boolean isLocked = ShulkerSessionManager.hasActiveSession(stack);

		if (isLocked || isOpenAttempt) {
			ci.cancel();
		}
		if (isOpenAttempt) {
			// Deferred to a later tick (via ShulkerSessionManager's own tick-bound queue, NOT
			// MinecraftServer#execute() - that can run its callback synchronously when already on the
			// main thread, which click handling often already is): handleContainerClick still needs to
			// finish its suppressRemoteUpdates()/resumeRemoteUpdates() pair on the OUTER menu (this)
			// after clicked() returns. Swapping player.containerMenu synchronously here would make that
			// resumeRemoteUpdates() land on the new virtual menu instead, permanently leaving the
			// outer menu (often the player's own inventory) stuck suppressed, i.e. "ghost-frozen"
			// until the player manually clicks a slot in it again.
			// 
			// Not doing this will cause the outer menu to be frozen until the player manually interacts with it.
			// This is most commonly not an issue, but will also be triggered while the client is in their own inventory,
			// which will freeze it, thus not updating, even with item pickups, commands, etc., until the player clicks a slot.
			ShulkerSessionManager.deferToNextTick(() -> ShulkerSessionManager.openFor(serverPlayer, self, slot, stack));
		}
	}

	/**
	 * Runs after a menu finishes closing (client-initiated or server-forced). If the menu was a
	 * session's virtual grid, drops the player as a viewer and resyncs their inventory/cursor.
	 */
	@Inject(method = "removed", at = @At("TAIL"))
	private void rightshulkerbox$onRemoved(Player player, CallbackInfo ci) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;
		}

		AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
		ShulkerSession session = ShulkerSessionManager.sessionForMenu(self);
		if (session != null) {
			ShulkerSessionManager.closeViewer(serverPlayer, session);
		}
	}
}
