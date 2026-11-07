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
import com.hanafubuki.updater.discovery.PluginRegistry;
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
 * opens its backups. The bottom row has found plugins, check all, update all, status, stop,
 * reload and paging. The found plugins view lists plugins.json entries: left click confirms,
 * right click rejects.
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
    /** Player the menu was built for; decides which details are shown. */
    private Player viewer;
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
        menu.viewer = player;
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
        int waiting = plugin.registry().pendingNames().size();
        menu.button(46, waiting > 0 ? Material.BELL : Material.COMPASS,
                waiting > 0 ? "<warning>Found plugins <accent>(" + waiting + " to review)" : "<primary>Found plugins",
                List.of("<muted>Plugins found automatically.", "<muted>Confirm or reject them here.",
                        "<muted>Unconfirmed plugins are never updated."), (p, c) -> {
                    if (menu.allowed(p, Permissions.CONFIRM)) {
                        openFound(plugin, p, 0);
                    }
                });
        menu.button(47, Material.SPYGLASS, "<primary>Check all", List.of("<muted>Look for updates, download nothing"),
                (p, c) -> menu.startRun(p, new RunOptions(true, null, "gui:" + p.getName()), Permissions.CHECK));
        menu.button(48, Material.ANVIL, "<success>Update all", List.of("<muted>Download every available update.",
                        "<muted>They are applied on the next restart."),
                (p, c) -> menu.startRun(p, new RunOptions(false, null, "gui:" + p.getName()), Permissions.UPDATE));
        menu.button(49, Material.BOOK, "<primary>Status",
                player.hasPermission(Permissions.STATUS) ? menu.statusLore() : List.of("<muted>Needs " + Permissions.STATUS), (p, c) -> {
                    if (menu.allowed(p, Permissions.STATUS)) {
                        p.closeInventory();
                        plugin.commands().run(p, "status");
                    }
                });
        menu.button(50, Material.BARRIER, "<error>Stop", List.of("<muted>Cancel the running check"), (p, c) -> {
            if (menu.allowed(p, Permissions.STOP)) {
                p.closeInventory();
                plugin.commands().run(p, "stop");
            }
        });
        menu.button(51, Material.REPEATER, "<warning>Reload config", List.of("<muted>Re-read config.yml"), (p, c) -> {
            if (menu.allowed(p, Permissions.RELOAD)) {
                p.closeInventory();
                plugin.commands().run(p, "reload");
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
                    : "last check: " + escape(s.lastResult().toLowerCase().replace('_', ' '))).orElse("not checked yet");
        }

        List<String> lore = new ArrayList<>();
        lore.add(state);
        lore.add("<muted>Source: <accent>" + escape(entry.source()));
        lore.add("<muted>From: <accent>" + (plugin.inConfig(entry.name()) ? "config.yml" : "plugins.json (found, confirmed)"));
        lore.add("<muted>Installed: <accent>" + escape(installed == null ? "-" : installed.version()));
        stats.filter(s -> viewer.hasPermission(Permissions.STATS)).ifPresent(s -> {
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
        plugin.messages().send(player, options.dryRun() ? "<primary>Checking for updates..." : "<primary>Updating in the background...");
    }

    // --- found plugins view ------------------------------------------------------------------

    /** Order in the found plugins view: what needs a decision first. */
    private static int rank(PluginRegistry.Status status) {
        return switch (status) {
            case PENDING -> 0;
            case UNIDENTIFIED -> 1;
            case REJECTED -> 2;
            case CONFIRMED -> 3;
        };
    }

    /** Plugins found automatically, with confirm and reject on click. */
    public static void openFound(PluginUpdater plugin, Player player, int page) {
        if (!player.hasPermission(Permissions.GUI) || !player.hasPermission(Permissions.CONFIRM)) {
            plugin.messages().send(player, "<error>You need <accent><perm></accent> for this.", Messages.text("perm",
                    player.hasPermission(Permissions.GUI) ? Permissions.CONFIRM : Permissions.GUI));
            return;
        }
        List<PluginRegistry.Entry> entries = new ArrayList<>(plugin.registry().entries());
        entries.sort(java.util.Comparator.comparingInt((PluginRegistry.Entry e) -> rank(e.status()))
                .thenComparing(PluginRegistry.Entry::name, String.CASE_INSENSITIVE_ORDER));
        int pages = Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.max(0, Math.min(page, pages - 1));
        UpdaterMenu menu = new UpdaterMenu(plugin);
        menu.viewer = player;
        menu.inventory = Bukkit.createInventory(menu, SIZE, plugin.messages().format(
                "<primary>Found plugins <muted>(<page>/<pages>)", Messages.text("page", current + 1), Messages.text("pages", pages)));

        int slot = 0;
        for (PluginRegistry.Entry e : entries.subList(current * PER_PAGE, Math.min(entries.size(), (current + 1) * PER_PAGE))) {
            menu.foundItem(slot++, e, current);
        }
        if (entries.isEmpty()) {
            menu.button(22, Material.GRAY_STAINED_GLASS_PANE, "<muted>Nothing found yet",
                    List.of("<muted>Installed plugins missing from config.yml", "<muted>show up here."), (p, c) -> {
                    });
        }
        if (current > 0) {
            menu.button(45, Material.ARROW, "<primary>Previous page", List.of(), (p, c) -> openFound(plugin, p, current - 1));
        }
        int waiting = (int) entries.stream().filter(e -> e.status() == PluginRegistry.Status.PENDING).count();
        if (waiting > 0) {
            menu.button(47, Material.EMERALD_BLOCK, "<success>Confirm all waiting (" + waiting + ")",
                    List.of("<muted>Update every waiting plugin", "<muted>from now on."), (p, c) -> {
                        if (menu.allowed(p, Permissions.CONFIRM)) {
                            plugin.commands().run(p, "confirm", "all");
                            openFound(plugin, p, current);
                        }
                    });
        }
        menu.button(49, Material.ARROW, "<primary>Back", List.of(), (p, c) -> openMain(plugin, p, 0));
        menu.button(51, Material.SPYGLASS, "<primary>Search now", List.of("<muted>Look for new plugins again"), (p, c) -> {
            if (menu.allowed(p, Permissions.DISCOVER)) {
                p.closeInventory();
                plugin.commands().run(p, "discover");
            }
        });
        if (current < pages - 1) {
            menu.button(53, Material.ARROW, "<primary>Next page", List.of(), (p, c) -> openFound(plugin, p, current + 1));
        }
        player.openInventory(menu.inventory);
    }

    private void foundItem(int slot, PluginRegistry.Entry e, int page) {
        boolean overridden = plugin.inConfig(e.name());
        Material material = switch (e.status()) {
            case PENDING -> Material.ORANGE_CONCRETE;
            case CONFIRMED -> Material.LIME_CONCRETE;
            case REJECTED -> Material.RED_CONCRETE;
            case UNIDENTIFIED -> Material.GRAY_CONCRETE;
        };
        List<String> lore = new ArrayList<>();
        lore.add(switch (e.status()) {
            case PENDING -> "<warning>Waiting for confirmation, not updated";
            case CONFIRMED -> "<success>Confirmed, updated";
            case REJECTED -> "<error>Rejected, not updated";
            case UNIDENTIFIED -> "<muted>No source found";
        });
        if (overridden) {
            lore.add("<warning>config.yml has an entry; it is used instead");
        }
        lore.add("<muted>Installed: <accent>" + escape(e.version()));
        if (e.isUsable()) {
            lore.add("<muted>Source: <accent>" + escape(e.source()));
            new java.util.TreeMap<>(e.options()).forEach((k, v) -> lore.add("<muted>  " + escape(k) + ": <accent>" + escape(v)));
        }
        lore.add("<muted>How: <accent>" + escape(abbreviate(e.reason(), 60)));
        if (e.discovered() != null) {
            lore.add("<muted>Found: <accent>" + TIME.format(e.discovered()));
        }
        if (e.decided() != null) {
            lore.add("<muted>Decided: <accent>" + TIME.format(e.decided()) + "</accent> by <accent>" + escape(e.decidedBy()));
        }
        lore.add("");
        boolean canConfirm = e.isUsable() && e.status() != PluginRegistry.Status.CONFIRMED;
        boolean canReject = e.status() != PluginRegistry.Status.REJECTED;
        if (canConfirm) {
            lore.add("<primary>Left click: <muted>confirm (update it)");
        }
        if (canReject) {
            lore.add("<primary>Right click: <muted>" + (e.status() == PluginRegistry.Status.UNIDENTIFIED ? "hide" : "reject (don't update)"));
        }
        if (!e.isUsable()) {
            lore.add("<muted>To update it, add it to config.yml");
            lore.add("<muted>with its source, then reload.");
        }
        String name = e.name();
        button(slot, material, "<accent>" + escape(name), lore, (p, click) -> {
            if (!allowed(p, Permissions.CONFIRM)) {
                return;
            }
            if (click == ClickType.LEFT && canConfirm) {
                plugin.commands().confirmOne(p, name);
                openFound(plugin, p, page);
            } else if (click == ClickType.RIGHT && canReject) {
                plugin.commands().rejectOne(p, name);
                openFound(plugin, p, page);
            }
        });
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
        menu.viewer = player;
        menu.inventory = Bukkit.createInventory(menu, SIZE, plugin.messages().format("<primary>Backups of <accent><name>",
                Messages.text("name", pluginName)));
        int slot = 0;
        for (BackupManager.Backup b : backups.subList(0, Math.min(backups.size(), PER_PAGE))) {
            menu.button(slot++, Material.CHEST, "<accent>" + escape(b.version()), List.of(
                    "<muted>Saved: <accent>" + TIME.format(b.created()),
                    "<muted>Size: <accent>" + (b.size() / 1024) + " KB",
                    "",
                    "<primary>Click: <muted>restore this version on next restart"), (p, c) -> {
                if (menu.allowed(p, Permissions.ROLLBACK)) {
                    p.closeInventory();
                    Optional<InstalledPlugin> installed = ServerPlugins.snapshot().values().stream()
                            .filter(x -> x.name().equalsIgnoreCase(pluginName)).findFirst();
                    if (installed.isEmpty()) {
                        plugin.messages().send(p, "<error><name> is not installed.", Messages.text("name", pluginName));
                    } else {
                        // Exactly the clicked file, even if several backups share a version.
                        plugin.commands().restore(p, installed.get(), null, b.file());
                    }
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
                plugin.commands().run(p, "unstage", pluginName);
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
        return s.length() <= max ? s : s.substring(0, max - 1) + "...";
    }
}
