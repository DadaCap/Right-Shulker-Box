package me.dadacap.rightshulkerbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;

import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/**
 * Represents one shulker box being viewed "in place" (right-click open) without moving it
 * out of its host container. Mirrors its contents into a virtual 27-slot container that
 * players interact with, and flushes changes back into the tracked item stack's
 * {@link DataComponents#CONTAINER} data.
 */
public final class ShulkerSession {
	/** Number of slots in a shulker box. */
	public static final int GRID_SIZE = 27;

	final ItemStack trackedStack;
	private final Container hostContainer;
	private final int hostSlotIndex;
	final SimpleContainer virtualContainer;

	private final Map<ServerPlayer, ViewerRecord> viewers = new LinkedHashMap<>();
	private boolean initializing = true;
	private boolean readOnly = false;

	/** Tracks, per viewing player, the menu that held the shulker box slot and the virtual menu opened for it. */
	record ViewerRecord(AbstractContainerMenu outerMenu, AbstractContainerMenu virtualMenu) {
	}

	/** Creates a session for a shulker box stack found at a given slot, loading its current contents into the virtual container. */
	ShulkerSession(Container hostContainer, int hostSlotIndex, ItemStack trackedStack) {
		this.hostContainer = hostContainer;
		this.hostSlotIndex = hostSlotIndex;
		this.trackedStack = trackedStack;

		NonNullList<ItemStack> initial = NonNullList.withSize(GRID_SIZE, ItemStack.EMPTY);
		ItemContainerContents contents = trackedStack.get(DataComponents.CONTAINER);
		if (contents != null) {
			contents.copyInto(initial);
		}

		this.virtualContainer = new SimpleContainer(GRID_SIZE) {
			/** Flushes edits to the tracked stack whenever the virtual container changes, after initial setup completes. */
			@Override
			public void setChanged() {
				super.setChanged();
				if (!ShulkerSession.this.initializing) {
					ShulkerSession.this.flushToSource();
				}
			}

			/** Disallows placing another shulker box inside, preventing nested shulker boxes. */
			@Override
			public boolean canPlaceItem(int slot, @NonNull ItemStack stack) {
				return !isShulkerBox(stack);
			}
		};

		for (int i = 0; i < GRID_SIZE; i++) {
			this.virtualContainer.setItem(i, initial.get(i));
		}
		this.initializing = false;
	}

	/** True if the stack contains shulker box(es). */
	public static boolean isShulkerBox(ItemStack stack) {
		return !stack.isEmpty()
				&& stack.getItem() instanceof BlockItem blockItem
				&& blockItem.getBlock() instanceof ShulkerBoxBlock;
	}

	/** True if the stack is a shulker box that can be right-click opened in place. */
	public static boolean isOpenableShulkerBox(ItemStack stack) {
		return isShulkerBox(stack) && stack.getCount() == 1;
	}

	/** Checks that the tracked stack is still present, unmoved, in its original host slot. */
	boolean isHostStillValid() {
		ItemStack live = hostContainer.getItem(hostSlotIndex);
		return live == trackedStack && isOpenableShulkerBox(live);
	}

	/** Writes the virtual container's current contents back into the tracked stack's container data, unless read-only or the host is gone. */
	private void flushToSource() {
		if (readOnly) {
			return;
		}
		if (!isHostStillValid()) {
			return;
		}

		List<ItemStack> snapshot = new ArrayList<>(virtualContainer.getContainerSize());
		for (int i = 0; i < virtualContainer.getContainerSize(); i++) {
			snapshot.add(virtualContainer.getItem(i));
		}

		trackedStack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(snapshot));
		hostContainer.setChanged();
	}

	/** Registers a player as viewing this session, before their virtual menu has actually opened. */
	void addViewer(ServerPlayer player, AbstractContainerMenu outerMenu) {
		viewers.put(player, new ViewerRecord(outerMenu, null));
	}

	/** Records the virtual menu that was opened for a player once {@code openMenu} succeeds. */
	void recordOpenedMenu(ServerPlayer player, AbstractContainerMenu outerMenu, AbstractContainerMenu virtualMenu) {
		viewers.put(player, new ViewerRecord(outerMenu, virtualMenu));
	}

	/** Stops tracking a player as a viewer of this session. */
	void removeViewer(ServerPlayer player) {
		viewers.remove(player);
	}

	/** Returns the live map of viewing players to their menu records. */
	Map<ServerPlayer, ViewerRecord> viewers() {
		return viewers;
	}

	/** True if no players are currently viewing this session. */
	boolean hasNoViewers() {
		return viewers.isEmpty();
	}

	/** True once the session's host stack has become invalid and edits are no longer accepted. */
	public boolean isReadOnly() {
		return readOnly;
	}

	/** Marks the session invalid (host box moved/removed), replacing its contents with a placeholder and refreshing viewers. */
	void invalidate() {
		if (readOnly) {
			return;
		}
		readOnly = true;

		ItemStack placeholder = new ItemStack(Items.BARRIER);
		placeholder.set(DataComponents.CUSTOM_NAME, Component.literal("No longer valid").withStyle(ChatFormatting.RED));
		placeholder.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("This shulker box moved or was removed.").withStyle(ChatFormatting.GRAY))));

		for (int i = 0; i < virtualContainer.getContainerSize(); i++) {
			virtualContainer.setItem(i, placeholder.copy());
		}

		for (ViewerRecord record : viewers.values()) {
			if (record.virtualMenu() != null) {
				record.virtualMenu().broadcastFullState();
			}
		}
	}
}