package com.hanafubuki.updater.console;

import java.time.Instant;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hanafubuki.updater.Permissions;
import com.hanafubuki.updater.config.ConfigManager;
import com.hanafubuki.updater.config.UpdaterSettings;
import com.hanafubuki.updater.discovery.PluginRegistry;
import com.hanafubuki.updater.manager.PluginResult;
import com.hanafubuki.updater.manager.UpdateListener;
import com.hanafubuki.updater.manager.UpdateReport;
import com.hanafubuki.updater.source.GitHubAdvisories;

import net.kyori.adventure.text.Component;

/**
 * Turns run progress into console lines (styled or JSON) and notifies players with
 * {@code hanafubuki.updater.notify}.
 */
public final class ResultReporter implements UpdateListener {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final Supplier<Messages> messages;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public ResultReporter(JavaPlugin plugin, ConfigManager config, Supplier<Messages> messages) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
    }

    private UpdaterSettings settings() {
        return config.settings();
    }

    @Override
    public void onRunStarted(String trigger, boolean dryRun, int plugins) {
        if (settings().consoleJson()) {
            JsonObject o = base("run_started");
            o.addProperty("trigger", trigger);
            o.addProperty("dry_run", dryRun);
            o.addProperty("plugins", plugins);
            plugin.getLogger().info(gson.toJson(o));
            return;
        }
        console(messages.get().format(dryRun
                        ? "<primary>Checking <accent><count></accent> plugins for updates (check only, started by <trigger>)"
                        : "<primary>Updating <accent><count></accent> plugins (started by <trigger>)",
                Messages.text("count", plugins), Messages.text("trigger", trigger)));
    }

    @Override
    public void onPluginResult(PluginResult r) {
        if (settings().consoleJson()) {
            plugin.getLogger().info(gson.toJson(json(r)));
        } else {
            console(line(r));
            for (String note : r.notes()) {
                console(messages.get().format("<warning>    ! <note>", Messages.text("note", note)));
            }
            for (GitHubAdvisories.Alert a : r.alerts()) {
                console(alertLine(r, a));
            }
        }
        if (settings().notifications().inGame()) {
            if (r.status() == PluginResult.Status.UPDATED) {
                notifyPlayers(messages.get().prefixed(
                        "<success>Plugin <accent><plugin></accent> updated, try restarting</success> <muted>(<old> -> <new>)",
                        Messages.text("plugin", r.plugin()), Messages.text("old", r.installedVersion()),
                        Messages.text("new", r.candidateVersion())));
            }
            for (GitHubAdvisories.Alert a : r.alerts()) {
                notifyPlayers(messages.get().prefix().append(alertLine(r, a)));
            }
        }
    }

    @Override
    public void onRunFinished(UpdateReport report) {
        if (settings().consoleJson()) {
            JsonObject o = base("run_finished");
            o.addProperty("trigger", report.trigger());
            o.addProperty("dry_run", report.dryRun());
            o.addProperty("cancelled", report.cancelled());
            o.addProperty("duration_ms", report.duration().toMillis());
            JsonObject counts = new JsonObject();
            report.counts().forEach((k, v) -> counts.addProperty(k.name().toLowerCase(), v));
            o.add("counts", counts);
            plugin.getLogger().info(gson.toJson(o));
            return;
        }
        console(summary(report));
    }

    /**
     * Reports what a plugin detection found. Main thread.
     *
     * @param requester gets a summary when the detection was started by hand; null otherwise
     */
    public void onDetection(PluginRegistry.Changes changes, PluginRegistry registry, CommandSender requester) {
        Messages m = messages.get();
        if (settings().consoleJson()) {
            if (!changes.isEmpty()) {
                JsonObject o = base("plugins_detected");
                o.add("pending", array(changes.pending()));
                o.add("unidentified", array(changes.unidentified()));
                o.add("removed", array(changes.removed()));
                plugin.getLogger().info(gson.toJson(o));
            }
        } else {
            for (String name : changes.pending()) {
                registry.get(name).ifPresent(e -> console(m.format(
                        "<warning>New plugin <accent><name></accent> <version> found: <source> <options> <muted>(<reason>)",
                        Messages.text("name", e.name()), Messages.text("version", e.version()),
                        Messages.text("source", e.source()), Messages.text("options", options(e)),
                        Messages.text("reason", e.reason()))));
            }
            if (!changes.pending().isEmpty()) {
                console(m.format("<warning>New plugins are not updated until an op confirms them: <commands>, or the menu.",
                        Messages.text("commands", "/updateplugins pending, confirm <plugin>|all, reject <plugin>")));
            }
            if (!changes.unidentified().isEmpty()) {
                console(m.format("<muted>No source found for <list>. Add them to config.yml to update them, "
                        + "or hide them with <command>.", Messages.text("list", String.join(", ", changes.unidentified())),
                        Messages.text("command", "/updateplugins reject <plugin>")));
            }
            if (!changes.removed().isEmpty()) {
                console(m.format("<muted>Removed from plugins.json (uninstalled, or now in config.yml or modes.exclude_list): <list>",
                        Messages.text("list", String.join(", ", changes.removed()))));
            }
        }
        if (!changes.pending().isEmpty() && settings().notifications().inGame()) {
            notifyPlayers(m.prefixed("<warning>New plugin(s) found: <accent><list></accent>. They wait for confirmation. "
                            + "<click:run_command:'/updateplugins pending'><primary>[review]</primary></click>",
                    Messages.text("list", String.join(", ", changes.pending()))), Permissions.CONFIRM);
        }
        if (requester != null && !(requester instanceof org.bukkit.command.ConsoleCommandSender)) {
            if (changes.isEmpty()) {
                m.send(requester, "<success>No new plugins found.");
            } else {
                m.send(requester, "<primary>Detection finished: <accent><pending></accent> new to review, "
                                + "<accent><unidentified></accent> without a source, <accent><removed></accent> removed.",
                        Messages.text("pending", changes.pending().size()), Messages.text("unidentified", changes.unidentified().size()),
                        Messages.text("removed", changes.removed().size()));
            }
        } else if (requester != null && changes.isEmpty()) {
            console(m.format("<success>No new plugins found."));
        }
        if (requester != null && !registry.pendingNames().isEmpty() && requester.hasPermission(Permissions.CONFIRM)) {
            m.send(requester, "<muted>Review found plugins with <accent>/updateplugins pending</accent>.");
        }
    }

    /** Source options as "key=value, ..." for messages. */
    public static String options(PluginRegistry.Entry e) {
        if (e.options().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        new java.util.TreeMap<>(e.options()).forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    private static JsonArray array(java.util.List<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }

    /** One-line summary of a run, also used for command feedback. */
    public Component summary(UpdateReport report) {
        Messages m = messages.get();
        return m.format("<primary><what> finished in <accent><seconds> s</accent>: "
                        + "<success><updated> updated</success>, <accent><available> available</accent>, "
                        + "<muted><current> up to date</muted>, <error><failed> failed</error><cancelled>",
                Messages.text("what", report.dryRun() ? "Update check" : "Update run"),
                Messages.text("seconds", String.format("%.1f", report.duration().toMillis() / 1000.0)),
                Messages.text("updated", report.count(PluginResult.Status.UPDATED)),
                Messages.text("available", report.count(PluginResult.Status.AVAILABLE)),
                Messages.text("current", report.count(PluginResult.Status.UP_TO_DATE)
                        + report.count(PluginResult.Status.ALREADY_STAGED)),
                Messages.text("failed", report.count(PluginResult.Status.FAILED) + report.count(PluginResult.Status.BLOCKED)),
                Messages.text("cancelled", report.cancelled() ? " (cancelled)" : ""));
    }

    /** Styled line for one result. */
    public Component line(PluginResult r) {
        Messages m = messages.get();
        var plugin = Messages.text("plugin", r.plugin());
        var installed = Messages.text("installed", r.installedVersion());
        var candidate = Messages.text("candidate", r.candidateVersion());
        var message = Messages.text("message", r.message());
        return switch (r.status()) {
            case UPDATED -> m.format("<success>  + <plugin> <installed> -> <candidate> staged, applied on next restart",
                    plugin, installed, candidate);
            case AVAILABLE -> m.format("<accent>  ^ <plugin> <installed> -> <candidate> available", plugin, installed, candidate);
            case ALREADY_STAGED -> m.format("<muted>  ~ <plugin> <candidate> is staged and waits for a restart", plugin, candidate);
            case UP_TO_DATE -> m.format("<muted>  = <plugin> <installed> is up to date", plugin, installed);
            case NO_VERSION -> m.format("<muted>  = <plugin>: <message>", plugin, message);
            case SKIPPED -> m.format("<muted>  - <plugin> skipped: <message>", plugin, message);
            case BLOCKED -> m.format("<warning>  ! <plugin> <candidate> not installed: <message>", plugin, candidate, message);
            case FAILED -> m.format("<error>  x <plugin>: <message>", plugin, message);
            case CANCELLED -> m.format("<muted>  - <plugin> cancelled", plugin);
        };
    }

    private Component alertLine(PluginResult r, GitHubAdvisories.Alert a) {
        return messages.get().format("<error>    ! Security (<severity>) in <plugin> <installed>: <summary> "
                        + "<muted>[<id>, patched: <patched>]",
                Messages.text("severity", a.severity()), Messages.text("plugin", r.plugin()),
                Messages.text("installed", r.installedVersion()), Messages.text("summary", a.summary()),
                Messages.text("id", a.id()), Messages.text("patched", a.patched()));
    }

    private void console(Component c) {
        plugin.getComponentLogger().info(c);
    }

    private void notifyPlayers(Component message) {
        notifyPlayers(message, Permissions.NOTIFY);
    }

    private void notifyPlayers(Component message, String permission) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission(permission)) {
                    p.sendMessage(message);
                }
            }
        });
    }

    private JsonObject base(String event) {
        JsonObject o = new JsonObject();
        o.addProperty("time", Instant.now().toString());
        o.addProperty("event", event);
        return o;
    }

    private JsonObject json(PluginResult r) {
        JsonObject o = base("plugin_result");
        o.addProperty("level", switch (r.status()) {
            case FAILED -> "error";
            case BLOCKED -> "warning";
            default -> r.alerts().isEmpty() ? "info" : "warning";
        });
        o.addProperty("plugin", r.plugin());
        o.addProperty("source", r.source());
        o.addProperty("status", r.status().name().toLowerCase());
        o.addProperty("installed_version", r.installedVersion());
        o.addProperty("version", r.candidateVersion());
        o.addProperty("message", r.message());
        o.addProperty("duration_ms", r.durationMs());
        JsonArray notes = new JsonArray();
        r.notes().forEach(notes::add);
        o.add("notes", notes);
        JsonArray alerts = new JsonArray();
        for (GitHubAdvisories.Alert a : r.alerts()) {
            JsonObject ao = new JsonObject();
            ao.addProperty("id", a.id());
            ao.addProperty("severity", a.severity());
            ao.addProperty("summary", a.summary());
            ao.addProperty("patched_versions", a.patched());
            alerts.add(ao);
        }
        o.add("alerts", alerts);
        return o;
    }
}
