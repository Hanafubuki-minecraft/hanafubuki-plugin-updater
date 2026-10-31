package com.hanafubuki.updater.console;

import java.time.Instant;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hanafubuki.updater.Permissions;
import com.hanafubuki.updater.config.ConfigManager;
import com.hanafubuki.updater.config.UpdaterSettings;
import com.hanafubuki.updater.manager.PluginResult;
import com.hanafubuki.updater.manager.UpdateListener;
import com.hanafubuki.updater.manager.UpdateReport;
import com.hanafubuki.updater.source.GitHubAdvisories;

import net.kyori.adventure.text.Component;

/**
 * Turns run progress into console lines (styled or JSON), writes new versions into config.yml
 * and notifies players with {@code hanafubuki.updater.notify}.
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
        if (r.status() == PluginResult.Status.UPDATED && settings().general().autoUpdateConfig()) {
            config.setPluginVersion(r.plugin(), r.candidateVersion());
        }
        if (settings().consoleJson()) {
            plugin.getLogger().info(gson.toJson(json(r)));
        } else {
            console(line(r));
            for (String note : r.notes()) {
                console(messages.get().format("<warning>    ⚠ <note>", Messages.text("note", note)));
            }
            for (GitHubAdvisories.Alert a : r.alerts()) {
                console(alertLine(r, a));
            }
        }
        if (settings().notifications().inGame()) {
            if (r.status() == PluginResult.Status.UPDATED) {
                notifyPlayers(messages.get().prefixed(
                        "<success>Plugin <accent><plugin></accent> updated, try restarting</success> <muted>(<old> → <new>)",
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
            case UPDATED -> m.format("<success>  ✔ <plugin> <installed> → <candidate> staged, applied on next restart",
                    plugin, installed, candidate);
            case AVAILABLE -> m.format("<accent>  ⬆ <plugin> <installed> → <candidate> available", plugin, installed, candidate);
            case ALREADY_STAGED -> m.format("<muted>  … <plugin> <candidate> is staged and waits for a restart", plugin, candidate);
            case UP_TO_DATE -> m.format("<muted>  • <plugin> <installed> is up to date", plugin, installed);
            case NO_VERSION -> m.format("<muted>  • <plugin>: <message>", plugin, message);
            case SKIPPED -> m.format("<muted>  - <plugin> skipped: <message>", plugin, message);
            case BLOCKED -> m.format("<warning>  ⛔ <plugin> <candidate> not installed: <message>", plugin, candidate, message);
            case FAILED -> m.format("<error>  ✖ <plugin>: <message>", plugin, message);
            case CANCELLED -> m.format("<muted>  - <plugin> cancelled", plugin);
        };
    }

    private Component alertLine(PluginResult r, GitHubAdvisories.Alert a) {
        return messages.get().format("<error>    ⚠ Security (<severity>) in <plugin> <installed>: <summary> "
                        + "<muted>[<id>, patched: <patched>]",
                Messages.text("severity", a.severity()), Messages.text("plugin", r.plugin()),
                Messages.text("installed", r.installedVersion()), Messages.text("summary", a.summary()),
                Messages.text("id", a.id()), Messages.text("patched", a.patched()));
    }

    private void console(Component c) {
        plugin.getComponentLogger().info(c);
    }

    private void notifyPlayers(Component message) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission(Permissions.NOTIFY)) {
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
