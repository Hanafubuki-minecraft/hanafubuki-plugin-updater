package com.hanafubuki.updater.notify;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hanafubuki.updater.config.UpdaterSettings;
import com.hanafubuki.updater.config.UpdaterSettings.Event;
import com.hanafubuki.updater.http.HttpService;
import com.hanafubuki.updater.manager.PluginResult;
import com.hanafubuki.updater.manager.UpdateListener;
import com.hanafubuki.updater.manager.UpdateReport;
import com.hanafubuki.updater.source.GitHubAdvisories;

/**
 * Sends run results to Discord and to a generic JSON webhook.
 *
 * <p>One message per run, containing only the configured events. Sending happens on its own
 * thread; failures are logged and never affect updates.
 */
public final class NotificationService implements UpdateListener {

    static final int COLOR_UPDATED = 0x57F287;
    static final int COLOR_AVAILABLE = 0x5865F2;
    static final int COLOR_FAILED = 0xED4245;
    static final int COLOR_SECURITY = 0xFFA500;
    static final int COLOR_SUMMARY = 0xC4B5FD;
    private static final int MAX_EMBEDS = 10;
    private static final int MAX_DESCRIPTION = 4000;
    /** Discord limit for all embed titles and descriptions of one message together. */
    private static final int MAX_MESSAGE_CHARS = 6000;

    private final UpdaterSettings.Notifications settings;
    private final HttpService http;
    private final Logger logger;
    private final ExecutorService executor;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public NotificationService(UpdaterSettings.Notifications settings, HttpService http, Logger logger) {
        this.settings = settings;
        this.http = http;
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "HanafubukiUpdater-notify");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean anyEnabled() {
        return settings.discord().enabled() || settings.webhook().enabled();
    }

    @Override
    public void onRunFinished(UpdateReport report) {
        if (settings.discord().enabled()) {
            List<JsonObject> messages = discordMessages(report, settings.discord(), settings.serverName());
            for (JsonObject m : messages) {
                send("Discord", settings.discord().url(), Map.of(), m);
            }
        }
        if (settings.webhook().enabled()) {
            webhookPayload(report, settings.webhook().events(), settings.serverName())
                    .ifPresent(p -> send("webhook", settings.webhook().url(), settings.webhook().headers(), p));
        }
    }

    /** Sends a test message to every enabled target. */
    public void sendTest(String requestedBy) {
        JsonObject test = new JsonObject();
        test.addProperty("event", "test");
        test.addProperty("server", settings.serverName());
        test.addProperty("requested_by", requestedBy);
        test.addProperty("time", Instant.now().toString());
        if (settings.discord().enabled()) {
            JsonObject msg = discordBase(settings.discord());
            JsonArray embeds = new JsonArray();
            embeds.add(embed("Test notification", "Discord notifications from **" + settings.serverName()
                    + "** work. Requested by " + requestedBy + ".", COLOR_SUMMARY));
            msg.add("embeds", embeds);
            send("Discord", settings.discord().url(), Map.of(), msg);
        }
        if (settings.webhook().enabled()) {
            send("webhook", settings.webhook().url(), settings.webhook().headers(), test);
        }
    }

    private void send(String target, String url, Map<String, String> headers, JsonObject payload) {
        String json = gson.toJson(payload);
        if (executor.isShutdown()) {
            logger.warning(target + " notification dropped: the notifier was replaced by a reload");
            return;
        }
        executor.execute(() -> {
            try {
                HttpService.Response r = http.postJson(URI.create(url), headers, json, Duration.ofSeconds(15));
                if (r.status() / 100 != 2) {
                    logger.warning(target + " notification failed: HTTP " + r.status() + " " + abbreviate(r.body(), 200));
                }
            } catch (IOException | RuntimeException e) {
                logger.warning(target + " notification failed: " + e.getMessage());
            }
        });
    }

    /** Stops accepting messages and waits up to 5 s for queued ones (used when the plugin is disabled). */
    public void shutdown() {
        shutdown(5000);
    }

    /**
     * Stops accepting messages. Queued messages are still sent; waits at most {@code waitMs}
     * (0 = return at once, used on reload so the main thread never blocks).
     */
    public void shutdown(long waitMs) {
        executor.shutdown();
        if (waitMs <= 0) {
            return;
        }
        try {
            if (!executor.awaitTermination(waitMs, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // --- payloads (static, tested) ----------------------------------------------------------

    /** Discord messages for a run; empty if nothing matches the configured events. */
    static List<JsonObject> discordMessages(UpdateReport report, UpdaterSettings.Discord discord, String serverName) {
        Set<Event> events = discord.events();
        List<JsonObject> embeds = new ArrayList<>();
        if (events.contains(Event.UPDATED)) {
            addListEmbed(embeds, "Updates staged on " + serverName, COLOR_UPDATED, report, PluginResult.Status.UPDATED,
                    "Restart the server to apply them.");
        }
        if (events.contains(Event.AVAILABLE)) {
            addListEmbed(embeds, "Updates available on " + serverName, COLOR_AVAILABLE, report, PluginResult.Status.AVAILABLE,
                    "Run /updateplugins to install them.");
        }
        if (events.contains(Event.FAILED) && !report.failures().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (PluginResult r : report.failures()) {
                sb.append("**").append(r.plugin()).append("** (").append(r.source()).append("): ")
                        .append(abbreviate(r.message(), 300)).append('\n');
            }
            embeds.add(embed("Update problems on " + serverName, abbreviate(sb.toString(), MAX_DESCRIPTION), COLOR_FAILED));
        }
        if (events.contains(Event.SECURITY)) {
            for (PluginResult r : report.withAlerts()) {
                for (GitHubAdvisories.Alert a : r.alerts()) {
                    JsonObject e = embed("Security advisory for " + r.plugin() + " " + r.installedVersion(),
                            "**" + a.severity().toUpperCase(Locale.ROOT) + "** " + abbreviate(a.summary(), 500)
                                    + (a.patched() == null ? "" : "\nPatched in: " + a.patched())
                                    + (a.cve() == null ? "" : "\n" + a.cve()), COLOR_SECURITY);
                    if (a.url() != null) {
                        e.addProperty("url", a.url());
                    }
                    embeds.add(e);
                }
            }
        }
        if (events.contains(Event.SUMMARY)) {
            embeds.add(embed("Update check finished on " + serverName, summaryText(report), COLOR_SUMMARY));
        }
        List<JsonObject> messages = new ArrayList<>();
        JsonArray current = null;
        int chars = 0;
        for (JsonObject embed : embeds) {
            int size = embed.get("title").getAsString().length() + embed.get("description").getAsString().length();
            if (current == null || current.size() >= MAX_EMBEDS || chars + size > MAX_MESSAGE_CHARS) {
                JsonObject msg = discordBase(discord);
                if (!messages.isEmpty()) {
                    msg.remove("content");
                }
                current = new JsonArray();
                msg.add("embeds", current);
                messages.add(msg);
                chars = 0;
            }
            current.add(embed);
            chars += size;
        }
        return messages;
    }

    private static void addListEmbed(List<JsonObject> embeds, String title, int color, UpdateReport report,
                                     PluginResult.Status status, String footer) {
        List<PluginResult> list = report.results().stream().filter(r -> r.status() == status).toList();
        if (list.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (PluginResult r : list) {
            sb.append("**").append(r.plugin()).append("** ").append(nullToDash(r.installedVersion()))
                    .append(" → ").append(nullToDash(r.candidateVersion()));
            if (r.pageUrl() != null) {
                sb.append(" ([page](").append(r.pageUrl()).append("))");
            }
            sb.append('\n');
            for (String note : r.notes()) {
                sb.append("  ⚠ ").append(abbreviate(note, 200)).append('\n');
            }
        }
        sb.append('\n').append(footer);
        embeds.add(embed(title, abbreviate(sb.toString(), MAX_DESCRIPTION), color));
    }

    private static JsonObject discordBase(UpdaterSettings.Discord discord) {
        JsonObject msg = new JsonObject();
        if (discord.username() != null) {
            msg.addProperty("username", abbreviate(discord.username(), 80));
        }
        if (discord.avatarUrl() != null) {
            msg.addProperty("avatar_url", discord.avatarUrl());
        }
        if (discord.mention() != null && !discord.mention().isBlank()) {
            msg.addProperty("content", discord.mention());
        }
        JsonObject allowed = new JsonObject();
        JsonArray parse = new JsonArray();
        parse.add("roles");
        parse.add("users");
        allowed.add("parse", parse);
        msg.add("allowed_mentions", allowed);
        return msg;
    }

    private static JsonObject embed(String title, String description, int color) {
        JsonObject e = new JsonObject();
        e.addProperty("title", abbreviate(title, 250));
        e.addProperty("description", description);
        e.addProperty("color", color);
        e.addProperty("timestamp", Instant.now().toString());
        return e;
    }

    static String summaryText(UpdateReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append(report.dryRun() ? "Check only" : "Update run").append(" by ").append(report.trigger())
                .append(", ").append(report.results().size()).append(" plugins in ")
                .append(report.duration().toSeconds()).append(" s");
        if (report.cancelled()) {
            sb.append(" (cancelled)");
        }
        sb.append('\n');
        report.counts().forEach((status, count) -> sb.append(status.name().toLowerCase(Locale.ROOT).replace('_', ' '))
                .append(": ").append(count).append('\n'));
        return sb.toString();
    }

    /** Generic webhook body; empty if no configured event occurred. */
    static java.util.Optional<JsonObject> webhookPayload(UpdateReport report, Set<Event> events, String serverName) {
        Set<Event> happened = EnumSet.noneOf(Event.class);
        JsonArray results = new JsonArray();
        for (PluginResult r : report.results()) {
            Event e = switch (r.status()) {
                case UPDATED -> Event.UPDATED;
                case AVAILABLE -> Event.AVAILABLE;
                case FAILED, BLOCKED -> Event.FAILED;
                default -> null;
            };
            boolean security = !r.alerts().isEmpty() && events.contains(Event.SECURITY);
            boolean include = (e != null && events.contains(e)) || security;
            if (!include) {
                continue;
            }
            if (e != null && events.contains(e)) {
                happened.add(e);
            }
            if (security) {
                happened.add(Event.SECURITY);
            }
            JsonObject o = new JsonObject();
            o.addProperty("plugin", r.plugin());
            o.addProperty("source", r.source());
            o.addProperty("status", r.status().name().toLowerCase(Locale.ROOT));
            o.addProperty("installed_version", r.installedVersion());
            o.addProperty("new_version", r.candidateVersion());
            o.addProperty("message", r.message());
            o.addProperty("page_url", r.pageUrl());
            JsonArray notes = new JsonArray();
            r.notes().forEach(notes::add);
            o.add("notes", notes);
            JsonArray alerts = new JsonArray();
            for (GitHubAdvisories.Alert a : r.alerts()) {
                JsonObject ao = new JsonObject();
                ao.addProperty("id", a.id());
                ao.addProperty("cve", a.cve());
                ao.addProperty("severity", a.severity());
                ao.addProperty("summary", a.summary());
                ao.addProperty("url", a.url());
                ao.addProperty("patched_versions", a.patched());
                alerts.add(ao);
            }
            o.add("alerts", alerts);
            results.add(o);
        }
        if (events.contains(Event.SUMMARY)) {
            happened.add(Event.SUMMARY);
        }
        if (happened.isEmpty()) {
            return java.util.Optional.empty();
        }
        JsonObject p = new JsonObject();
        p.addProperty("event", "run_finished");
        JsonArray ev = new JsonArray();
        happened.forEach(e -> ev.add(e.name().toLowerCase(Locale.ROOT)));
        p.add("events", ev);
        p.addProperty("server", serverName);
        p.addProperty("trigger", report.trigger());
        p.addProperty("dry_run", report.dryRun());
        p.addProperty("cancelled", report.cancelled());
        p.addProperty("started", report.started().toString());
        p.addProperty("finished", report.finished().toString());
        JsonObject counts = new JsonObject();
        report.counts().forEach((k, v) -> counts.addProperty(k.name().toLowerCase(Locale.ROOT), v));
        p.add("counts", counts);
        p.add("results", results);
        return java.util.Optional.of(p);
    }

    private static String nullToDash(String s) {
        return s == null ? "-" : s;
    }

    static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
