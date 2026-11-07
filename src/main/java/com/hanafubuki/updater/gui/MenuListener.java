package com.hanafubuki.updater.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.Plugin;

/**
 * Makes updater menus read-only and forwards clicks to {@link UpdaterMenu}.
 */
public final class MenuListener implements Listener {

    private final Plugin plugin;

    public MenuListener(Plugin plugin) {
        this.plugin = plugin;
    }

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
            int slot = event.getRawSlot();
            org.bukkit.event.inventory.ClickType click = event.getClick();
            // Bukkit forbids opening or closing inventories inside the click event; act on the next tick.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder(false) == menu) {
                    menu.click(player, slot, click);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof UpdaterMenu) {
            event.setCancelled(true);
        }
    }
}
