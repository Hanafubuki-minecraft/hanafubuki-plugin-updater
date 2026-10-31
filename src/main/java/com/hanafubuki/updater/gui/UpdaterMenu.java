package com.hanafubuki.updater.gui;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.hanafubuki.updater.Permissions;
import com.hanafubuki.updater.PluginUpdater;
import com.hanafubuki.updater.ServerPlugins;
import com.hanafubuki.updater.config.PluginEntry;
import com.hanafubuki.updater.console.Messages;
import com.hanafubuki.updater.manager.BackupManager;
import com.hanafubuki.updater.manager.PluginResult;
import com.hanafubuki.updater.manager.RunOptions;
import com.hanafubuki.updater.manager.StateStore;
import com.hanafubuki.updater.manager.UpdateReport;
import com.hanafubuki.updater.source.InstalledPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Inventory menu for /updateplugins gui.
 *
 * <p>Main view: one item per configured plugin, coloured by state (orange = security advisory,
 * red = failed, light blue = update available, yellow = waiting for restart, lime = up to date,
 * gray = not checked). Left click checks the plugin, shift + left click updates it, right click
 * opens its backups. The bottom row has check all, update all, stop, reload and paging.
 * Every action checks the same permission as the matching command.
 */
public final class UpdaterMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int PER_PAGE = 45;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** What a click on a slot does. */
    @FunctionalInterface
    interface Action {
        void run(Player player, ClickType click);
    }

    private final PluginUpdater plugin;
    private final Map<Integer, Action> actions = new HashMap<>();
    private Inventory inventory;

    private UpdaterMenu(PluginUpdater plugin) {
        this.plugin = plugin;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void click(Player player, int slot, ClickType click) {
        Action action = actions.get(slot);
        if (action != null) {
            action.run(player, click);
        }
    }

    // --- main view ---------------------------------------------------------------------------

    public static void openMain(PluginUpdater plugin, Player player, int page) {
        if (!player.hasPermission(Permissions.GUI)) {
            plugin.messages().send(player, "<error>You need <accent><perm></accent> for this.", Messages.text("perm", Permissions.GUI));
            return;
        }
        UpdaterMenu menu = new UpdaterMenu(plugin);
        List<PluginEntry> entries = new ArrayList<>(plugin.settings().plugins().values());
        int pages = Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.max(0, Math.min(page, pages - 1));
        menu.inventory = Bukkit.createInventory(menu, SIZE, plugin.messages().format(
                "<primary>Plugin updates <muted>(<page>/<pages>)", Messages.text("page", current + 1), Messages.text("pages", pages)));

        Map<String, InstalledPlugin> installed = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        installed.putAll(ServerPlugins.snapshot());
        Map<String, PluginResult> lastResults = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        plugin.lastReport().map(UpdateReport::results).ifPresent(list -> list.forEach(r -> lastResults.put(r.plugin(), r)));
        Map<String, String> staged = plugin.state().allStaged();

        int slot = 0;
        for (PluginEntry entry : entries.subList(current * PER_PAGE, Math.min(entries.size(), (current + 1) * PER_PAGE))) {
            menu.pluginItem(slot++, entry, installed.get(entry.name()), lastResults.get(entry.name()), staged.get(entry.name()));
        }

        if (current > 0) {
            menu.button(45, Material.ARROW, "<primary>Previous page", List.of(), (p, c) -> openMain(plugin, p, current - 1));
        }
        menu.button(47, Material.SPYGLASS, "<primary>Check all", List.of("<muted>Look for updates, download nothing"),
                (p, c) -> menu.startRun(p, new RunOptions(true, null, "gui:" + p.getName()), Permissions.CHECK));
        menu.button(48, Material.ANVIL, "<success>Update all", List.of("<muted>Download every available update.",
                        "<muted>They are applied on the next restart."),
                (p, c) -> menu.startRun(p, new RunOptions(false, null, "gui:" + p.getName()), Permissions.UPDATE));
        menu.button(49, Material.BOOK, "<primary>Status", menu.statusLore(), (p, c) -> {
            p.closeInventory();
            p.performCommand("updateplugins status");
        });
        menu.button(50, Material.BARRIER, "<error>Stop", List.of("<muted>Cancel the running check"), (p, c) -> {
            if (menu.allowed(p, Permissions.STOP)) {
                p.closeInventory();
                p.performCommand("updateplugins stop");
            }
        });
        menu.button(51, Material.REPEATER, "<warning>Reload config", List.of("<muted>Re-read config.yml"), (p, c) -> {
            if (menu.allowed(p, Permissions.RELOAD)) {
                p.closeInventory();
                p.performCommand("updateplugins reload");
            }
        });
        if (current < pages - 1) {
            menu.button(53, Material.ARROW, "<primary>Next page", List.of(), (p, c) -> openMain(plugin, p, current + 1));
        }
        player.openInventory(menu.inventory);
    }

    private List<String> statusLore() {
        List<String> lore = new ArrayList<>();
        lore.add("<muted>Running: <accent>" + (plugin.manager().isRunning() ? "yes" : "no"));
        lore.add("<muted>Schedule: <accent>" + escape(plugin.settings().schedule().describe()));
        plugin.scheduler().nextRun().ifPresent(t -> lore.add("<muted>Next check: <accent>" + TIME.format(t)));
        plugin.state().lastRun().ifPresent(r -> lore.add("<muted>Last run: <accent>"
                + (r.finished() == null ? "-" : TIME.format(r.finished()))));
        lore.add("<muted>Waiting for restart: <accent>" + plugin.state().allStaged().size());
        return lore;
    }

    private void pluginItem(int slot, PluginEntry entry, InstalledPlugin installed, PluginResult last, String stagedVersion) {
        Optional<StateStore.PluginStats> stats = plugin.state().stats(entry.name());
        Optional<String> held = plugin.state().heldVersion(entry.name());
        Material material;
        String state;
        if (installed == null) {
            material = Material.GRAY_CONCRETE;
            state = "<muted>not installed";
        } else if (last != null && !last.alerts().isEmpty()) {
            material = Material.ORANGE_CONCRETE;
            state = "<error>security advisory!";
        } else if (held.isPresent()) {
            material = Material.PURPLE_CONCRETE;
            state = "<accent>rolled back to " + escape(held.get()) + ", updates paused";
        } else if (stagedVersion != null) {
            material = Material.YELLOW_CONCRETE;
            state = "<warning>" + escape(stagedVersion) + " waits for a restart";
        } else if (last != null) {
            material = switch (last.status()) {
                case AVAILABLE -> Material.LIGHT_BLUE_CONCRETE;
                case UPDATED, ALREADY_STAGED -> Material.YELLOW_CONCRETE;
                case UP_TO_DATE -> Material.LIME_CONCRETE;
                case FAILED, BLOCKED -> Material.RED_CONCRETE;
                default -> Material.GRAY_CONCRETE;
            };
            state = switch (last.status()) {
                case AVAILABLE -> "<accent>" + escape(last.candidateVersion()) + " available";
                case UP_TO_DATE -> "<success>up to date";
                case FAILED, BLOCKED -> "<error>" + escape(abbreviate(last.message(), 60));
                default -> "<muted>" + last.status().name().toLowerCase().replace('_', ' ');
            };
        } else {
            material = Material.GRAY_CONCRETE;
            state = "<muted>" + stats.map(s -> s.lastResult() == null ? "not checked yet"
                    : "last check: " + s.lastResult().toLowerCase().replace('_', ' ')).orElse("not checked yet");
        }

        List<String> lore = new ArrayList<>();
        lore.add(state);
        lore.add("<muted>Source: <accent>" + escape(entry.source()));
        lore.add("<muted>Installed: <accent>" + escape(installed == null ? "-" : installed.version()));
        stats.ifPresent(s -> {
            if (s.lastSeenVersion() != null) {
                lore.add("<muted>Latest seen: <accent>" + escape(s.lastSeenVersion()));
            }
            lore.add("<muted>Checks: <accent>" + s.checks() + "</accent>, updates: <accent>" + s.updates()
                    + "</accent>, failures: <accent>" + s.failures());
            if (s.lastCheck() != null) {
                lore.add("<muted>Last check: <accent>" + TIME.format(s.lastCheck()));
            }
        });
        lore.add("");
        lore.add("<primary>Left click: <muted>check");
        lore.add("<primary>Shift + left click: <muted>update");
        lore.add("<primary>Right click: <muted>backups / rollback");

        String name = entry.name();
        button(slot, material, "<accent>" + escape(name), lore, (p, click) -> {
            if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
                if (allowed(p, Permissions.ROLLBACK)) {
                    openBackups(plugin, p, name);
                }
            } else if (click == ClickType.SHIFT_LEFT) {
                startRun(p, new RunOptions(false, Set.of(name), "gui:" + p.getName()), Permissions.UPDATE);
            } else if (click == ClickType.LEFT) {
                startRun(p, new RunOptions(true, Set.of(name), "gui:" + p.getName()), Permissions.CHECK);
            }
        });
    }

    private void startRun(Player player, RunOptions options, String permission) {
        if (!allowed(player, permission)) {
            return;
        }
        player.closeInventory();
        if (!plugin.startRun(options, player)) {
            plugin.messages().send(player, "<warning>A check is already running.");
            return;
        }
        plugin.messages().send(player, options.dryRun() ? "<primary>Checking for updates…" : "<primary>Updating in the background…");
    }

    // --- backups view ------------------------------------------------------------------------

    /** Loads backups in the background, then opens the menu on the main thread. */
    public static void openBackups(PluginUpdater plugin, Player player, String pluginName) {
        plugin.runBackground(() -> {
            List<BackupManager.Backup> backups;
            try {
                backups = plugin.backups().list(pluginName);
            } catch (IOException e) {
                plugin.runSync(() -> plugin.messages().send(player, "<error>Could not read backups: <e>", Messages.text("e", e.getMessage())));
                return;
            }
            plugin.runSync(() -> showBackups(plugin, player, pluginName, backups));
        });
    }

    private static void showBackups(PluginUpdater plugin, Player player, String pluginName, List<BackupManager.Backup> backups) {
        if (!player.isOnline()) {
            return;
        }
        UpdaterMenu menu = new UpdaterMenu(plugin);
        menu.inventory = Bukkit.createInventory(menu, SIZE, plugin.messages().format("<primary>Backups of <accent><name>",
                Messages.text("name", pluginName)));
        int slot = 0;
        for (BackupManager.Backup b : backups.subList(0, Math.min(backups.size(), PER_PAGE))) {
            String version = b.version();
            menu.button(slot++, Material.CHEST, "<accent>" + escape(version), List.of(
                    "<muted>Saved: <accent>" + TIME.format(b.created()),
                    "<muted>Size: <accent>" + (b.size() / 1024) + " KB",
                    "",
                    "<primary>Click: <muted>restore this version on next restart"), (p, c) -> {
                if (menu.allowed(p, Permissions.ROLLBACK)) {
                    p.closeInventory();
                    p.performCommand("updateplugins rollback " + pluginName + " " + version);
                }
            });
        }
        if (backups.isEmpty()) {
            menu.button(22, Material.GRAY_STAINED_GLASS_PANE, "<muted>No backups yet",
                    List.of("<muted>A backup is made before every update", "<muted>when backups.enabled is true."), (p, c) -> {
                    });
        }
        menu.button(48, Material.BARRIER, "<warning>Remove staged update", List.of("<muted>Keep the installed version",
                "<muted>and resume automatic updates"), (p, c) -> {
            if (menu.allowed(p, Permissions.ROLLBACK)) {
                p.closeInventory();
                p.performCommand("updateplugins unstage " + pluginName);
            }
        });
        menu.button(49, Material.ARROW, "<primary>Back", List.of(), (p, c) -> openMain(plugin, p, 0));
        player.openInventory(menu.inventory);
    }

    // --- helpers -----------------------------------------------------------------------------

    private boolean allowed(Player player, String permission) {
        if (player.hasPermission(permission)) {
            return true;
        }
        plugin.messages().send(player, "<error>You need <accent><perm></accent> for this.", Messages.text("perm", permission));
        return false;
    }

    private void button(int slot, Material material, String name, List<String> lore, Action action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(name));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            lines.add(plain(line));
        }
        meta.lore(lines);
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
        actions.put(slot, action);
    }

    private Component plain(String template) {
        return plugin.messages().format(template, TagResolver.empty()).decoration(TextDecoration.ITALIC, false);
    }

    /** Escapes text that goes into a MiniMessage template built in this class. */
    private static String escape(String s) {
        return s == null ? "-" : net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(s);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "-";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
