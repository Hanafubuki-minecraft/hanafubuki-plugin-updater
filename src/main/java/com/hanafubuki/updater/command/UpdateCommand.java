package com.hanafubuki.updater.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import com.hanafubuki.updater.Permissions;
import com.hanafubuki.updater.PluginUpdater;
import com.hanafubuki.updater.ServerPlugins;
import com.hanafubuki.updater.console.Messages;
import com.hanafubuki.updater.gui.UpdaterMenu;
import com.hanafubuki.updater.manager.BackupManager;
import com.hanafubuki.updater.manager.Installer;
import com.hanafubuki.updater.manager.RunOptions;
import com.hanafubuki.updater.manager.StateStore;
import com.hanafubuki.updater.source.InstalledPlugin;

/**
 * {@code /updateplugins} and its sub-commands. Every sub-command has its own permission
 * (see {@link Permissions}); all default to op.
 */
public final class UpdateCommand implements TabExecutor {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** Sub-command name, permission and help text. */
    private record Sub(String name, String permission, String usage, String help) {
    }

    private static final List<Sub> SUBS = List.of(
            new Sub("check", Permissions.CHECK, "check [plugins...]", "check for updates without downloading"),
            new Sub("update", Permissions.UPDATE, "update [all|plugins...] [--dry-run]", "download updates (applied on restart)"),
            new Sub("stop", Permissions.STOP, "stop", "stop the running check"),
            new Sub("status", Permissions.STATUS, "status", "running check, staged updates, schedule"),
            new Sub("stats", Permissions.STATS, "stats [plugin]", "how often plugins are checked and updated"),
            new Sub("backups", Permissions.ROLLBACK, "backups <plugin>", "list stored previous versions"),
            new Sub("rollback", Permissions.ROLLBACK, "rollback <plugin> [version]", "restore a previous version on restart"),
            new Sub("unstage", Permissions.ROLLBACK, "unstage <plugin>", "remove a downloaded update before restart"),
            new Sub("discover", Permissions.DISCOVER, "discover [add] [plugin]", "find installed plugins missing from config"),
            new Sub("metrics", Permissions.METRICS, "metrics [--prometheus]", "show counters"),
            new Sub("notify", Permissions.NOTIFY_TEST, "notify test", "send a test Discord/webhook message"),
            new Sub("gui", Permissions.GUI, "gui", "open the menu"),
            new Sub("reload", Permissions.RELOAD, "reload", "reload config.yml"));

    private final PluginUpdater plugin;

    public UpdateCommand(PluginUpdater plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(Permissions.USE)) {
            m().send(sender, "<error>You don't have permission to use this command.");
            return true;
        }
        if (args.length == 0) {
            // Backwards compatible: plain /updateplugins updates everything.
            return update(sender, new String[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        if (sub.equals("--dry-run") || sub.equals("-d") || sub.equals("dry-run")) {
            return check(sender, rest);
        }
        Optional<Sub> found = SUBS.stream().filter(s -> s.name().equals(sub)).findFirst();
        if (sub.equals("help") || found.isEmpty()) {
            help(sender, label);
            return true;
        }
        if (!sender.hasPermission(found.get().permission())) {
            m().send(sender, "<error>You need <accent><perm></accent> for this.", Messages.text("perm", found.get().permission()));
            return true;
        }
        return switch (sub) {
            case "check" -> check(sender, rest);
            case "update" -> update(sender, rest);
            case "stop" -> stop(sender);
            case "status" -> status(sender);
            case "stats" -> stats(sender, rest);
            case "backups" -> backups(sender, rest);
            case "rollback" -> rollback(sender, rest);
            case "unstage" -> unstage(sender, rest);
            case "discover" -> discover(sender, rest);
            case "metrics" -> metrics(sender, rest);
            case "notify" -> notifyTest(sender, rest);
            case "gui" -> gui(sender);
            case "reload" -> reload(sender);
            default -> {
                help(sender, label);
                yield true;
            }
        };
    }

    private void help(CommandSender sender, String label) {
        m().send(sender, "<primary>Plugin updater commands:");
        for (Sub s : SUBS) {
            if (sender.hasPermission(s.permission())) {
                m().sendLine(sender, "<accent>  /<label> <usage></accent> <muted>- <help>",
                        Messages.text("label", label), Messages.text("usage", s.usage()), Messages.text("help", s.help()));
            }
        }
    }

    private boolean check(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Permissions.CHECK)) {
            m().send(sender, "<error>You need <accent><perm></accent> for this.", Messages.text("perm", Permissions.CHECK));
            return true;
        }
        Set<String> names = pluginArgs(args);
        start(sender, new RunOptions(true, names.isEmpty() ? null : names, trigger(sender)));
        return true;
    }

    private boolean update(CommandSender sender, String[] args) {
        boolean dryRun = Arrays.stream(args).anyMatch(a -> a.equalsIgnoreCase("--dry-run") || a.equalsIgnoreCase("-d"));
        String needed = dryRun ? Permissions.CHECK : Permissions.UPDATE;
        if (!sender.hasPermission(needed)) {
            m().send(sender, "<error>You need <accent><perm></accent> for this.", Messages.text("perm", needed));
            return true;
        }
        Set<String> names = pluginArgs(args);
        names.removeIf(n -> n.equalsIgnoreCase("all"));
        start(sender, new RunOptions(dryRun, names.isEmpty() ? null : names, trigger(sender)));
        return true;
    }

    private void start(CommandSender sender, RunOptions options) {
        if (!plugin.startRun(options, sender)) {
            m().send(sender, "<warning>A check is already running. Use <accent>/updateplugins stop</accent> to cancel it.");
            return;
        }
        String what = options.only() == null ? "all configured plugins" : String.join(", ", options.only());
        m().send(sender, options.dryRun()
                        ? "<primary>Checking <accent><what></accent> for updates (nothing is downloaded)..."
                        : "<primary>Updating <accent><what></accent> in the background...",
                Messages.text("what", what));
    }

    private static Set<String> pluginArgs(String[] args) {
        Set<String> names = new LinkedHashSet<>();
        for (String a : args) {
            if (!a.startsWith("-")) {
                names.add(a);
            }
        }
        return names;
    }

    private static String trigger(CommandSender sender) {
        return sender instanceof Player p ? "command:" + p.getName() : "command:console";
    }

    private boolean stop(CommandSender sender) {
        if (plugin.manager().cancel()) {
            m().send(sender, "<warning>Stopping the running check...");
        } else {
            m().send(sender, "<muted>No check is running.");
        }
        return true;
    }

    private boolean status(CommandSender sender) {
        Messages m = m();
        m.send(sender, "<primary>Plugin updater <accent><version></accent>", Messages.text("version", plugin.getPluginMeta().getVersion()));
        m.sendLine(sender, "<muted>  Running: <accent><running>", Messages.text("running", plugin.manager().isRunning() ? "yes" : "no"));
        m.sendLine(sender, "<muted>  Tracked plugins: <accent><count>", Messages.text("count", plugin.settings().plugins().size()));
        m.sendLine(sender, "<muted>  Schedule: <accent><schedule></accent><next>",
                Messages.text("schedule", plugin.settings().schedule().describe()),
                Messages.text("next", plugin.scheduler().nextRun().map(t -> ", next " + TIME.format(t)).orElse("")));
        plugin.state().lastRun().ifPresent(r -> m.sendLine(sender, "<muted>  Last run: <accent><when></accent> by <trigger> <counts>",
                Messages.text("when", r.finished() == null ? "-" : TIME.format(r.finished())),
                Messages.text("trigger", r.trigger()), Messages.text("counts", r.counts())));
        Map<String, String> staged = plugin.state().allStaged();
        if (staged.isEmpty()) {
            m.sendLine(sender, "<muted>  No updates are waiting for a restart.");
        } else {
            m.sendLine(sender, "<warning>  Waiting for restart:");
            staged.forEach((name, version) -> m.sendLine(sender, "<accent>    <name> -> <version>",
                    Messages.text("name", name), Messages.text("version", version)));
        }
        boolean discord = plugin.settings().notifications().discord().enabled();
        boolean webhook = plugin.settings().notifications().webhook().enabled();
        m.sendLine(sender, "<muted>  Notifications: in-game <a>, Discord <b>, webhook <c>",
                Messages.text("a", plugin.settings().notifications().inGame() ? "on" : "off"),
                Messages.text("b", discord ? "on" : "off"), Messages.text("c", webhook ? "on" : "off"));
        List<String> problems = plugin.configProblems();
        if (!problems.isEmpty()) {
            m.sendLine(sender, "<warning>  config.yml has <count> problem(s), see the console or /updateplugins reload",
                    Messages.text("count", problems.size()));
        }
        return true;
    }

    private boolean stats(CommandSender sender, String[] args) {
        Messages m = m();
        List<StateStore.PluginStats> stats;
        if (args.length > 0) {
            Optional<StateStore.PluginStats> one = plugin.state().stats(args[0]);
            if (one.isEmpty()) {
                m.send(sender, "<warning>No statistics for <name> yet.", Messages.text("name", args[0]));
                return true;
            }
            stats = List.of(one.get());
        } else {
            stats = plugin.state().allStats();
        }
        if (stats.isEmpty()) {
            m.send(sender, "<muted>No checks have run yet.");
            return true;
        }
        m.send(sender, "<primary>Update statistics:");
        for (StateStore.PluginStats s : stats) {
            m.sendLine(sender, "<accent>  <name></accent> <muted>checks <checks>, updates <updates>, failures <failures>, "
                            + "last update <last>, every <avg>, last result <result>",
                    Messages.text("name", s.plugin()), Messages.text("checks", s.checks()), Messages.text("updates", s.updates()),
                    Messages.text("failures", s.failures()),
                    Messages.text("last", s.lastUpdate() == null ? "never" : TIME.format(s.lastUpdate())),
                    Messages.text("avg", s.averageUpdateInterval() == null ? "-" : humanDuration(s.averageUpdateInterval())),
                    Messages.text("result", s.lastResult() == null ? "-" : s.lastResult().toLowerCase(Locale.ROOT)));
        }
        return true;
    }

    private boolean backups(CommandSender sender, String[] args) {
        if (args.length < 1) {
            m().send(sender, "<warning>Usage: /updateplugins backups <plugin>");
            return true;
        }
        String name = args[0];
        plugin.runBackground(() -> {
            List<BackupManager.Backup> list;
            try {
                list = plugin.backups().list(name);
            } catch (IOException e) {
                plugin.runSync(() -> m().send(sender, "<error>Could not read backups: <e>", Messages.text("e", e.getMessage())));
                return;
            }
            plugin.runSync(() -> {
                if (list.isEmpty()) {
                    m().send(sender, "<muted>No backups of <name>.", Messages.text("name", name));
                    return;
                }
                m().send(sender, "<primary>Backups of <accent><name></accent> (newest first):", Messages.text("name", name));
                for (BackupManager.Backup b : list) {
                    m().sendLine(sender, "<accent>  <version></accent> <muted>from <when>, <size> KB",
                            Messages.text("version", b.version()), Messages.text("when", TIME.format(b.created())),
                            Messages.text("size", b.size() / 1024));
                }
            });
        });
        return true;
    }

    private boolean rollback(CommandSender sender, String[] args) {
        if (args.length < 1) {
            m().send(sender, "<warning>Usage: /updateplugins rollback <plugin> [version]");
            return true;
        }
        Optional<InstalledPlugin> installed = installed(args[0]);
        if (installed.isEmpty()) {
            m().send(sender, "<error><name> is not installed.", Messages.text("name", args[0]));
            return true;
        }
        if (plugin.manager().isRunning()) {
            m().send(sender, "<warning>Wait until the running check has finished (or /updateplugins stop).");
            return true;
        }
        String version = args.length > 1 ? args[1] : null;
        Path updateFolder = plugin.updateFolder();
        plugin.runBackground(() -> {
            try {
                BackupManager.Backup b = plugin.backups().restore(version, installed.get(), updateFolder);
                plugin.state().hold(installed.get().name(), b.version(), Instant.now());
                plugin.runSync(() -> m().send(sender, "<success><name> <version> is staged and replaces <current> on the next restart. "
                                + "Automatic updates of this plugin are paused; resume with <accent>/updateplugins unstage <name></accent> "
                                + "after the restart.",
                        Messages.text("name", installed.get().name()), Messages.text("version", b.version()),
                        Messages.text("current", installed.get().version())));
            } catch (IOException e) {
                plugin.runSync(() -> m().send(sender, "<error>Rollback failed: <e>", Messages.text("e", e.getMessage())));
            }
        });
        return true;
    }

    private boolean unstage(CommandSender sender, String[] args) {
        if (args.length < 1) {
            m().send(sender, "<warning>Usage: /updateplugins unstage <plugin>");
            return true;
        }
        Optional<InstalledPlugin> installed = installed(args[0]);
        if (installed.isEmpty()) {
            m().send(sender, "<error><name> is not installed.", Messages.text("name", args[0]));
            return true;
        }
        if (plugin.manager().isRunning()) {
            m().send(sender, "<warning>Wait until the running check has finished (or /updateplugins stop).");
            return true;
        }
        Path staged = Installer.stagedPath(installed.get(), plugin.updateFolder());
        plugin.runBackground(() -> {
            try {
                boolean deleted = Files.deleteIfExists(staged);
                plugin.state().clearStaged(installed.get().name());
                boolean wasHeld = plugin.state().release(installed.get().name());
                plugin.runSync(() -> {
                    if (deleted) {
                        m().send(sender, "<success>Removed the staged file of <name>; it stays at <version>.",
                                Messages.text("name", installed.get().name()), Messages.text("version", installed.get().version()));
                    } else {
                        m().send(sender, "<muted>Nothing is staged for <name>.", Messages.text("name", installed.get().name()));
                    }
                    if (wasHeld) {
                        m().send(sender, "<success>Automatic updates of <name> are active again.",
                                Messages.text("name", installed.get().name()));
                    }
                });
            } catch (IOException e) {
                plugin.runSync(() -> m().send(sender, "<error>Could not remove <file>: <e>",
                        Messages.text("file", staged.getFileName()), Messages.text("e", e.getMessage())));
            }
        });
        return true;
    }

    private boolean discover(CommandSender sender, String[] args) {
        boolean add = args.length > 0 && args[0].equalsIgnoreCase("add");
        String only = add ? (args.length > 1 && !args[1].equalsIgnoreCase("all") ? args[1] : null)
                : (args.length > 0 ? args[0] : null);
        if (!plugin.settings().general().autoDiscover() && add) {
            m().send(sender, "<warning>Adding plugins needs <accent>global.auto_discover: true</accent> in config.yml. "
                    + "Listing still works.");
            return true;
        }
        plugin.discover(sender, add, only);
        return true;
    }

    private boolean metrics(CommandSender sender, String[] args) {
        int tracked = plugin.settings().plugins().size();
        if (args.length > 0 && (args[0].equalsIgnoreCase("--prometheus") || args[0].equalsIgnoreCase("-p"))) {
            for (String line : plugin.manager().metrics().prometheus(tracked).split("\n")) {
                if (!line.startsWith("#")) {
                    m().sendLine(sender, "<muted><line>", Messages.text("line", line));
                }
            }
            return true;
        }
        m().send(sender, "<primary>Counters since the server started:");
        plugin.manager().metrics().snapshot().forEach((k, v) -> m().sendLine(sender, "<muted>  <key>: <accent><value>",
                Messages.text("key", k.replace('_', ' ')), Messages.text("value",
                        k.endsWith("timestamp_ms") ? (v == 0 ? "never" : TIME.format(Instant.ofEpochMilli(v))) : v)));
        m().sendLine(sender, "<muted>  plugins tracked: <accent><n>", Messages.text("n", tracked));
        return true;
    }

    private boolean notifyTest(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("test")) {
            m().send(sender, "<warning>Usage: /updateplugins notify test");
            return true;
        }
        if (!plugin.notifications().anyEnabled()) {
            m().send(sender, "<warning>Discord and webhook notifications are both disabled in config.yml.");
            return true;
        }
        plugin.notifications().sendTest(sender.getName());
        m().send(sender, "<success>Test notification sent. Delivery errors are written to the console.");
        return true;
    }

    private boolean gui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            m().send(sender, "<error>The menu can only be opened by a player.");
            return true;
        }
        UpdaterMenu.openMain(plugin, player, 0);
        return true;
    }

    private boolean reload(CommandSender sender) {
        Optional<List<String>> result = plugin.reload();
        if (result.isEmpty()) {
            m().send(sender, "<warning>A check is running; reload when it has finished (or /updateplugins stop).");
            return true;
        }
        if (result.get().isEmpty()) {
            m().send(sender, "<success>config.yml reloaded, <count> plugins tracked.",
                    Messages.text("count", plugin.settings().plugins().size()));
        } else {
            m().send(sender, "<warning>config.yml reloaded with <count> problem(s):", Messages.text("count", result.get().size()));
            for (String p : result.get()) {
                m().sendLine(sender, "<warning>  - <p>", Messages.text("p", p));
            }
        }
        return true;
    }

    private static Optional<InstalledPlugin> installed(String name) {
        return ServerPlugins.snapshot().values().stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
    }

    static String humanDuration(Duration d) {
        long hours = d.toHours();
        if (hours >= 48) {
            return (hours / 24) + " days";
        }
        if (hours >= 1) {
            return hours + " h";
        }
        return Math.max(1, d.toMinutes()) + " min";
    }

    // --- tab completion --------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(Permissions.USE)) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(SUBS.stream().filter(s -> sender.hasPermission(s.permission())).map(Sub::name).toList());
            subs.add("help");
            return filter(subs.stream(), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String last = args[args.length - 1];
        Stream<String> configured = plugin.settings().plugins().keySet().stream();
        return switch (sub) {
            case "check" -> filter(configured, last);
            case "update" -> filter(Stream.concat(Stream.of("all", "--dry-run"), configured), last);
            case "stats" -> args.length == 2 ? filter(configured, last) : List.of();
            case "backups", "rollback", "unstage" -> args.length == 2 ? filter(configured, last) : List.of();
            case "discover" -> args.length == 2 ? filter(Stream.of("add"), last) : List.of();
            case "metrics" -> args.length == 2 ? filter(Stream.of("--prometheus"), last) : List.of();
            case "notify" -> args.length == 2 ? filter(Stream.of("test"), last) : List.of();
            default -> List.of();
        };
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
