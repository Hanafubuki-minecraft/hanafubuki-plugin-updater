package com.hanafubuki.updater.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Makes updater menus read-only and forwards clicks to {@link UpdaterMenu}.
 */
public final class MenuListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof UpdaterMenu menu)) {
            return;
        }
        // Nothing may be taken from or put into the menu, including shift-clicks from the player inventory.
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (event.getWhoClicked() instanceof Player player) {
            menu.click(player, event.getRawSlot(), event.getClick());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof UpdaterMenu) {
            event.setCancelled(true);
        }
    }
}
