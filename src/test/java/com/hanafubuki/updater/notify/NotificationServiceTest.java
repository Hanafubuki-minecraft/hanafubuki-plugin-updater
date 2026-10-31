package com.hanafubuki.updater.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanafubuki.updater.config.UpdaterSettings;
import com.hanafubuki.updater.config.UpdaterSettings.Event;
import com.hanafubuki.updater.http.HttpService;
import com.hanafubuki.updater.manager.PluginResult;
import com.hanafubuki.updater.manager.PluginResult.Status;
import com.hanafubuki.updater.manager.UpdateReport;
import com.hanafubuki.updater.source.GitHubAdvisories;
import com.hanafubuki.updater.testing.FakeHttpServer;
import com.hanafubuki.updater.testing.FakeHttpServer.Reply;
import com.hanafubuki.updater.testing.TestHttp;

class NotificationServiceTest {

    private static final Instant START = Instant.parse("2026-10-31T04:00:00Z");
    private static final Instant END = Instant.parse("2026-10-31T04:00:12Z");
    private static final String SERVER = "Hanafubuki";
    /** Discord: at most 6000 characters in all embed titles and descriptions of one message. */
    private static final int DISCORD_MESSAGE_CHARS = 6000;

    // --- builders ------------------------------------------------------------------------------

    private static PluginResult result(String plugin, Status status, String installed, String candidate, String message) {
        return new PluginResult(plugin, "modrinth", status, installed, candidate, message, List.of(), List.of(), null, 10);
    }

    private static PluginResult updated(String plugin, String from, String to) {
        return new PluginResult(plugin, "github", Status.UPDATED, from, to, null, List.of("no checksum published"),
                List.of(), "https://example.com/" + plugin, 50);
    }

    private static PluginResult withAlerts(String plugin, int count) {
        List<GitHubAdvisories.Alert> alerts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            alerts.add(new GitHubAdvisories.Alert("GHSA-" + i, "CVE-2026-" + i, "high", "Bug " + i,
                    "https://github.com/advisories/GHSA-" + i, ">= 2.0." + i));
        }
        return new PluginResult(plugin, "github", Status.UP_TO_DATE, "1.0", null, null, List.of(), alerts, null, 5);
    }

    private static UpdateReport report(PluginResult... results) {
        return new UpdateReport("schedule", false, START, END, false, List.of(results));
    }

    private static UpdaterSettings.Discord discord(Set<Event> events, String mention) {
        return new UpdaterSettings.Discord(true, "https://discord.invalid/api/webhooks/1/x", "Updater",
                "https://example.com/avatar.png", mention, events);
    }

    private static List<JsonObject> embeds(JsonObject message) {
        List<JsonObject> out = new ArrayList<>();
        message.getAsJsonArray("embeds").forEach(e -> out.add(e.getAsJsonObject()));
        return out;
    }

    private static int embedChars(JsonObject message) {
        int total = 0;
        for (JsonObject e : embeds(message)) {
            total += e.get("title").getAsString().length() + e.get("description").getAsString().length();
        }
        return total;
    }

    // --- Discord payloads ----------------------------------------------------------------------

    @Test
    void onlyConfiguredEventsAreIncluded() {
        UpdateReport r = report(
                updated("LuckPerms", "5.4.0", "5.4.1"),
                result("Vault", Status.AVAILABLE, "1.7", "1.8", null),
                result("Essentials", Status.FAILED, "2.20", null, "HTTP 503"),
                result("WorldEdit", Status.UP_TO_DATE, "7.3", null, null));
        List<JsonObject> messages = NotificationService.discordMessages(r, discord(EnumSet.of(Event.UPDATED), null), SERVER);
        assertEquals(1, messages.size());
        List<JsonObject> embeds = embeds(messages.get(0));
        assertEquals(1, embeds.size());
        JsonObject e = embeds.get(0);
        assertEquals("Updates staged on Hanafubuki", e.get("title").getAsString());
        assertEquals(NotificationService.COLOR_UPDATED, e.get("color").getAsInt());
        String d = e.get("description").getAsString();
        assertTrue(d.contains("**LuckPerms** 5.4.0 → 5.4.1 ([page](https://example.com/LuckPerms))"), d);
        assertTrue(d.contains("⚠ no checksum published"), d);
        assertTrue(d.endsWith("Restart the server to apply them."), d);
        assertFalse(d.contains("Vault"), d);
        assertFalse(d.contains("Essentials"), d);
        assertTrue(Instant.parse(e.get("timestamp").getAsString()).isAfter(START));
    }

    @Test
    void nothingToSendGivesNoMessages() {
        UpdateReport r = report(result("WorldEdit", Status.UP_TO_DATE, "7.3", null, null));
        assertEquals(List.of(), NotificationService.discordMessages(r,
                discord(EnumSet.of(Event.UPDATED, Event.AVAILABLE, Event.FAILED, Event.SECURITY), "<@&1>"), SERVER));
        assertEquals(List.of(), NotificationService.discordMessages(report(updated("A", "1", "2")),
                discord(EnumSet.noneOf(Event.class), null), SERVER));
    }

    @Test
    void everyEventTypeHasItsEmbed() {
        UpdateReport r = report(
                updated("LuckPerms", "5.4.0", "5.4.1"),
                result("Vault", Status.AVAILABLE, null, "1.8", null),
                result("Essentials", Status.FAILED, "2.20", null, "HTTP 503"),
                result("Geyser", Status.BLOCKED, "2.4", "2.5", "needs Floodgate 2.3"),
                withAlerts("ViaVersion", 1));
        List<JsonObject> embeds = embeds(NotificationService.discordMessages(r,
                discord(EnumSet.allOf(Event.class), null), SERVER).get(0));
        assertEquals(List.of("Updates staged on Hanafubuki", "Updates available on Hanafubuki", "Update problems on Hanafubuki",
                        "Security advisory for ViaVersion 1.0", "Update check finished on Hanafubuki"),
                embeds.stream().map(e -> e.get("title").getAsString()).toList());
        assertEquals(List.of(NotificationService.COLOR_UPDATED, NotificationService.COLOR_AVAILABLE,
                        NotificationService.COLOR_FAILED, NotificationService.COLOR_SECURITY, NotificationService.COLOR_SUMMARY),
                embeds.stream().map(e -> e.get("color").getAsInt()).toList());

        String available = embeds.get(1).get("description").getAsString();
        assertTrue(available.contains("**Vault** - → 1.8"), available);
        assertTrue(available.endsWith("Run /updateplugins to install them."), available);

        String problems = embeds.get(2).get("description").getAsString();
        assertTrue(problems.contains("**Essentials** (modrinth): HTTP 503"), problems);
        assertTrue(problems.contains("**Geyser** (modrinth): needs Floodgate 2.3"), problems);

        JsonObject security = embeds.get(3);
        assertEquals("https://github.com/advisories/GHSA-0", security.get("url").getAsString());
        assertEquals("**HIGH** Bug 0\nPatched in: >= 2.0.0\nCVE-2026-0", security.get("description").getAsString());

        String summary = embeds.get(4).get("description").getAsString();
        assertTrue(summary.startsWith("Update run by schedule, 5 plugins in 12 s\n"), summary);
        assertTrue(summary.contains("updated: 1\n"), summary);
        assertTrue(summary.contains("failed: 1\n"), summary);
        assertTrue(summary.contains("up to date: 1\n"), summary);
    }

    @Test
    void securityEmbedWithoutOptionalFields() {
        PluginResult r = new PluginResult("X", "github", Status.UP_TO_DATE, "1.0", null, null, List.of(),
                List.of(new GitHubAdvisories.Alert("GHSA-x", null, "critical", "Remote code execution", null, null)), null, 1);
        JsonObject e = embeds(NotificationService.discordMessages(report(r),
                discord(EnumSet.of(Event.SECURITY), null), SERVER).get(0)).get(0);
        assertEquals("**CRITICAL** Remote code execution", e.get("description").getAsString());
        assertFalse(e.has("url"));
    }

    @Test
    void messageHasIdentityMentionAndAllowedMentions() {
        JsonObject msg = NotificationService.discordMessages(report(updated("A", "1", "2")),
                discord(EnumSet.of(Event.UPDATED), "<@&1234>"), SERVER).get(0);
        assertEquals("Updater", msg.get("username").getAsString());
        assertEquals("https://example.com/avatar.png", msg.get("avatar_url").getAsString());
        assertEquals("<@&1234>", msg.get("content").getAsString());
        JsonArray parse = msg.getAsJsonObject("allowed_mentions").getAsJsonArray("parse");
        assertEquals(List.of("roles", "users"), parse.asList().stream().map(JsonElement::getAsString).toList());
    }

    @Test
    void blankMentionAndMissingIdentityAreLeftOut() {
        UpdaterSettings.Discord d = new UpdaterSettings.Discord(true, "https://x", null, null, "  ", EnumSet.of(Event.UPDATED));
        JsonObject msg = NotificationService.discordMessages(report(updated("A", "1", "2")), d, SERVER).get(0);
        assertFalse(msg.has("content"));
        assertFalse(msg.has("username"));
        assertFalse(msg.has("avatar_url"));
        assertTrue(msg.has("allowed_mentions"));
    }

    @Test
    void moreThanTenEmbedsAreSplitAndOnlyFirstMessageMentions() {
        UpdateReport r = report(withAlerts("ViaVersion", 12));
        List<JsonObject> messages = NotificationService.discordMessages(r,
                discord(EnumSet.of(Event.SECURITY, Event.SUMMARY), "<@&42>"), SERVER);
        assertEquals(2, messages.size());
        assertEquals(10, embeds(messages.get(0)).size());
        assertEquals(3, embeds(messages.get(1)).size());
        assertEquals("<@&42>", messages.get(0).get("content").getAsString());
        assertFalse(messages.get(1).has("content"));
        for (JsonObject m : messages) {
            assertTrue(m.has("allowed_mentions"));
            assertEquals("Updater", m.get("username").getAsString());
        }
        // embeds keep their order across messages
        assertEquals("Update check finished on Hanafubuki",
                embeds(messages.get(1)).get(2).get("title").getAsString());
    }

    @Test
    void exactlyTenEmbedsFitInOneMessage() {
        List<JsonObject> messages = NotificationService.discordMessages(report(withAlerts("X", 10)),
                discord(EnumSet.of(Event.SECURITY), null), SERVER);
        assertEquals(1, messages.size());
        assertEquals(10, embeds(messages.get(0)).size());
    }

    @Test
    void longListsAreAbbreviatedBelowDiscordDescriptionLimit() {
        List<PluginResult> many = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            many.add(updated("Plugin" + i, "1.0." + i, "1.1." + i));
        }
        UpdateReport r = new UpdateReport("command:Steve", false, START, END, false, many);
        JsonObject e = embeds(NotificationService.discordMessages(r, discord(EnumSet.of(Event.UPDATED), null), SERVER).get(0)).get(0);
        String d = e.get("description").getAsString();
        assertTrue(d.length() <= 4096, "length " + d.length());
        assertTrue(d.endsWith("…"), d.substring(d.length() - 20));
    }

    @Test
    void longFailureMessagesAndTitlesAreAbbreviated() {
        String serverName = "S".repeat(400);
        UpdateReport r = report(result("Broken", Status.FAILED, "1", null, "e".repeat(1000)));
        JsonObject e = embeds(NotificationService.discordMessages(r, discord(EnumSet.of(Event.FAILED), null), serverName).get(0)).get(0);
        assertTrue(e.get("title").getAsString().length() <= 256);
        String d = e.get("description").getAsString();
        assertTrue(d.contains("e".repeat(299) + "…"), d);
        assertFalse(d.contains("e".repeat(300)), d);
    }

    @Test
    void longUsernameIsAbbreviated() {
        UpdaterSettings.Discord d = new UpdaterSettings.Discord(true, "https://x", "U".repeat(100), null, null,
                EnumSet.of(Event.UPDATED));
        JsonObject msg = NotificationService.discordMessages(report(updated("A", "1", "2")), d, SERVER).get(0);
        assertEquals(80, msg.get("username").getAsString().length());
    }

    @Test
    void messagesStayWithinDiscordTotalEmbedSize() {
        // Two large embeds: each below the description limit, together above Discord's 6000 characters per message.
        List<PluginResult> results = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            results.add(updated("Updated" + i, "1.0." + i, "1.1." + i));
            results.add(result("Failed" + i, Status.FAILED, "1.0", null, "connection reset by peer while downloading"));
        }
        UpdateReport r = new UpdateReport("schedule", false, START, END, false, results);
        List<JsonObject> messages = NotificationService.discordMessages(r,
                discord(EnumSet.of(Event.UPDATED, Event.FAILED, Event.SUMMARY), "<@&7>"), SERVER);
        int embedCount = 0;
        for (JsonObject m : messages) {
            assertTrue(embedChars(m) <= DISCORD_MESSAGE_CHARS, "message has " + embedChars(m) + " characters");
            assertTrue(embeds(m).size() <= 10);
            embedCount += embeds(m).size();
        }
        assertEquals(3, embedCount);
        assertTrue(messages.size() >= 2);
        assertEquals("<@&7>", messages.get(0).get("content").getAsString());
        for (JsonObject m : messages.subList(1, messages.size())) {
            assertFalse(m.has("content"));
        }
    }

    @Test
    void summaryTextForDryRunAndCancelledRun() {
        UpdateReport r = new UpdateReport("command:Steve", true, START, END, true,
                List.of(result("A", Status.AVAILABLE, "1", "2", null), result("B", Status.NO_VERSION, "1", null, null)));
        String text = NotificationService.summaryText(r);
        assertTrue(text.startsWith("Check only by command:Steve, 2 plugins in 12 s (cancelled)\n"), text);
        assertTrue(text.contains("available: 1\n"), text);
        assertTrue(text.contains("no version: 1\n"), text);
    }

    @Test
    void abbreviate() {
        assertEquals("", NotificationService.abbreviate(null, 5));
        assertEquals("short", NotificationService.abbreviate("short", 5));
        assertEquals("long…", NotificationService.abbreviate("longer", 5));
        assertEquals("…", NotificationService.abbreviate("abc", 0));
    }

    // --- webhook payload -----------------------------------------------------------------------

    @Test
    void webhookPayloadFields() {
        UpdateReport r = report(
                updated("LuckPerms", "5.4.0", "5.4.1"),
                result("Essentials", Status.FAILED, "2.20", null, "HTTP 503"),
                result("WorldEdit", Status.UP_TO_DATE, "7.3", null, null));
        JsonObject p = NotificationService.webhookPayload(r, EnumSet.allOf(Event.class), SERVER).orElseThrow();
        assertEquals("run_finished", p.get("event").getAsString());
        assertEquals(List.of("updated", "failed", "summary"),
                p.getAsJsonArray("events").asList().stream().map(JsonElement::getAsString).toList());
        assertEquals(SERVER, p.get("server").getAsString());
        assertEquals("schedule", p.get("trigger").getAsString());
        assertFalse(p.get("dry_run").getAsBoolean());
        assertFalse(p.get("cancelled").getAsBoolean());
        assertEquals("2026-10-31T04:00:00Z", p.get("started").getAsString());
        assertEquals("2026-10-31T04:00:12Z", p.get("finished").getAsString());
        JsonObject counts = p.getAsJsonObject("counts");
        assertEquals(1, counts.get("updated").getAsInt());
        assertEquals(1, counts.get("failed").getAsInt());
        assertEquals(1, counts.get("up_to_date").getAsInt());

        JsonArray results = p.getAsJsonArray("results");
        assertEquals(2, results.size());
        JsonObject first = results.get(0).getAsJsonObject();
        assertEquals("LuckPerms", first.get("plugin").getAsString());
        assertEquals("github", first.get("source").getAsString());
        assertEquals("updated", first.get("status").getAsString());
        assertEquals("5.4.0", first.get("installed_version").getAsString());
        assertEquals("5.4.1", first.get("new_version").getAsString());
        assertEquals("https://example.com/LuckPerms", first.get("page_url").getAsString());
        assertEquals("no checksum published", first.getAsJsonArray("notes").get(0).getAsString());
        assertEquals(0, first.getAsJsonArray("alerts").size());
        JsonObject second = results.get(1).getAsJsonObject();
        assertEquals("failed", second.get("status").getAsString());
        assertEquals("HTTP 503", second.get("message").getAsString());
        assertTrue(second.get("new_version").isJsonNull());
    }

    @Test
    void webhookIncludesOnlyConfiguredEvents() {
        UpdateReport r = report(
                updated("A", "1", "2"),
                result("B", Status.BLOCKED, "1", "2", "needs C"),
                withAlerts("D", 2));
        JsonObject p = NotificationService.webhookPayload(r, EnumSet.of(Event.FAILED), SERVER).orElseThrow();
        assertEquals(List.of("failed"), p.getAsJsonArray("events").asList().stream().map(JsonElement::getAsString).toList());
        assertEquals(1, p.getAsJsonArray("results").size());
        assertEquals("blocked", p.getAsJsonArray("results").get(0).getAsJsonObject().get("status").getAsString());
        // counts always describe the whole run
        assertEquals(3, p.getAsJsonObject("counts").entrySet().size());
    }

    @Test
    void webhookSecurityAlerts() {
        JsonObject p = NotificationService.webhookPayload(report(updated("A", "1", "2"), withAlerts("D", 2)),
                EnumSet.of(Event.SECURITY), SERVER).orElseThrow();
        assertEquals(List.of("security"), p.getAsJsonArray("events").asList().stream().map(JsonElement::getAsString).toList());
        JsonArray results = p.getAsJsonArray("results");
        assertEquals(1, results.size());
        JsonArray alerts = results.get(0).getAsJsonObject().getAsJsonArray("alerts");
        assertEquals(2, alerts.size());
        JsonObject a = alerts.get(1).getAsJsonObject();
        assertEquals("GHSA-1", a.get("id").getAsString());
        assertEquals("CVE-2026-1", a.get("cve").getAsString());
        assertEquals("high", a.get("severity").getAsString());
        assertEquals("Bug 1", a.get("summary").getAsString());
        assertEquals("https://github.com/advisories/GHSA-1", a.get("url").getAsString());
        assertEquals(">= 2.0.1", a.get("patched_versions").getAsString());
    }

    @Test
    void webhookIsEmptyWhenNoConfiguredEventHappened() {
        UpdateReport r = report(result("A", Status.UP_TO_DATE, "1", null, null), withAlerts("B", 1));
        assertEquals(Optional.empty(), NotificationService.webhookPayload(r,
                EnumSet.of(Event.UPDATED, Event.FAILED, Event.AVAILABLE), SERVER));
        assertEquals(Optional.empty(), NotificationService.webhookPayload(report(), EnumSet.noneOf(Event.class), SERVER));
    }

    @Test
    void webhookSummaryIsSentEvenWithoutResults() {
        UpdateReport r = new UpdateReport("startup", true, START, END, true, List.of());
        JsonObject p = NotificationService.webhookPayload(r, EnumSet.of(Event.SUMMARY), SERVER).orElseThrow();
        assertEquals(List.of("summary"), p.getAsJsonArray("events").asList().stream().map(JsonElement::getAsString).toList());
        assertEquals(0, p.getAsJsonArray("results").size());
        assertTrue(p.get("dry_run").getAsBoolean());
        assertTrue(p.get("cancelled").getAsBoolean());
    }

    @Test
    void payloadsDoNotDependOnDefaultLocale() {
        Locale before = Locale.getDefault();
        try {
            // Turkish upper/lower-cases i/I to dotted/dotless variants
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            UpdateReport r = report(
                    result("A", Status.FAILED, "1", null, "boom"),
                    result("B", Status.AVAILABLE, "1", "2", null),
                    result("C", Status.SKIPPED, "1", null, null),
                    withAlerts("D", 1));
            JsonObject p = NotificationService.webhookPayload(r, EnumSet.allOf(Event.class), SERVER).orElseThrow();
            assertEquals(List.of("available", "failed", "security", "summary"),
                    p.getAsJsonArray("events").asList().stream().map(JsonElement::getAsString).toList());
            assertEquals("failed", p.getAsJsonArray("results").get(0).getAsJsonObject().get("status").getAsString());
            assertTrue(p.getAsJsonObject("counts").has("skipped"), p.getAsJsonObject("counts").toString());

            assertTrue(NotificationService.summaryText(r).contains("failed: 1"), NotificationService.summaryText(r));

            PluginResult critical = new PluginResult("X", "github", Status.UP_TO_DATE, "1.0", null, null, List.of(),
                    List.of(new GitHubAdvisories.Alert("GHSA-x", null, "critical", "RCE", null, null)), null, 1);
            JsonObject e = embeds(NotificationService.discordMessages(report(critical),
                    discord(EnumSet.of(Event.SECURITY), null), SERVER).get(0)).get(0);
            assertEquals("**CRITICAL** RCE", e.get("description").getAsString());
        } finally {
            Locale.setDefault(before);
        }
    }

    // --- end to end ----------------------------------------------------------------------------

    private FakeHttpServer server;
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private Logger logger;

    @BeforeEach
    void setUp() throws IOException {
        server = FakeHttpServer.start();
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private UpdaterSettings.Notifications settings(boolean discordOn, boolean webhookOn) {
        return new UpdaterSettings.Notifications(true, true,
                new UpdaterSettings.Discord(discordOn, server.base() + "/discord", "Updater", null, "<@&99>",
                        EnumSet.of(Event.UPDATED, Event.FAILED)),
                new UpdaterSettings.Webhook(webhookOn, server.base() + "/hook",
                        Map.of("Authorization", "Bearer hook-secret", "X-Server", "hanafubuki"), EnumSet.allOf(Event.class)),
                SERVER);
    }

    private static void waitFor(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5 s");
            }
            Thread.onSpinWait();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void runFinishedPostsToDiscordAndWebhook() {
        server.route("/discord", r -> Reply.status(204));
        server.route("/hook", r -> Reply.status(200));
        NotificationService service = new NotificationService(settings(true, true), TestHttp.service(), logger);
        assertTrue(service.anyEnabled());
        try {
            service.onRunFinished(report(updated("LuckPerms", "5.4.0", "5.4.1"),
                    result("Essentials", Status.FAILED, "2.20", null, "HTTP 503")));
            waitFor(() -> server.requests("/discord").size() == 1 && server.requests("/hook").size() == 1);

            FakeHttpServer.Request discordReq = server.requests("/discord").get(0);
            assertEquals("POST", discordReq.method());
            assertEquals("application/json", discordReq.header("Content-Type"));
            assertEquals(TestHttp.USER_AGENT, discordReq.header("User-Agent"));
            assertNull(discordReq.header("Authorization"));
            JsonObject discordBody = JsonParser.parseString(discordReq.body()).getAsJsonObject();
            assertEquals("<@&99>", discordBody.get("content").getAsString());
            assertEquals("Updater", discordBody.get("username").getAsString());
            List<JsonObject> embeds = embeds(discordBody);
            assertEquals(2, embeds.size());
            assertEquals("Updates staged on Hanafubuki", embeds.get(0).get("title").getAsString());
            assertEquals("Update problems on Hanafubuki", embeds.get(1).get("title").getAsString());
            // no HTML escaping of '>' and '&' in mentions and markdown
            assertTrue(discordReq.body().contains("<@&99>"), discordReq.body());

            FakeHttpServer.Request hookReq = server.requests("/hook").get(0);
            assertEquals("Bearer hook-secret", hookReq.header("Authorization"));
            assertEquals("hanafubuki", hookReq.header("X-Server"));
            assertEquals("application/json", hookReq.header("Content-Type"));
            JsonObject hookBody = JsonParser.parseString(hookReq.body()).getAsJsonObject();
            assertEquals("run_finished", hookBody.get("event").getAsString());
            assertEquals(SERVER, hookBody.get("server").getAsString());
            assertEquals(2, hookBody.getAsJsonArray("results").size());
            // null members are not sent
            assertFalse(hookBody.getAsJsonArray("results").get(1).getAsJsonObject().has("new_version"));
            assertEquals(List.of(), warnings);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shutdownWaitsForPendingNotifications() {
        server.route("/hook", r -> Reply.status(200));
        NotificationService service = new NotificationService(settings(false, true), TestHttp.service(), logger);
        service.onRunFinished(report(updated("A", "1", "2")));
        service.shutdown();
        assertEquals(1, server.requests("/hook").size());
        assertTrue(server.requests("/discord").isEmpty());
    }

    @Test
    void disabledTargetsSendNothing() {
        NotificationService service = new NotificationService(settings(false, false), TestHttp.service(), logger);
        assertFalse(service.anyEnabled());
        service.onRunFinished(report(updated("A", "1", "2")));
        service.sendTest("console");
        service.shutdown();
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void nothingMatchingSendsNothing() {
        NotificationService service = new NotificationService(
                new UpdaterSettings.Notifications(true, true,
                        new UpdaterSettings.Discord(true, server.base() + "/discord", null, null, null, EnumSet.of(Event.UPDATED)),
                        new UpdaterSettings.Webhook(true, server.base() + "/hook", Map.of(), EnumSet.of(Event.FAILED)),
                        SERVER),
                TestHttp.service(), logger);
        service.onRunFinished(report(result("A", Status.UP_TO_DATE, "1", null, null)));
        service.shutdown();
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void failedDeliveryIsLoggedNotThrown() {
        server.route("/discord", r -> Reply.text(400, "{\"message\": \"Invalid Form Body\"}"));
        NotificationService service = new NotificationService(
                new UpdaterSettings.Notifications(true, true,
                        new UpdaterSettings.Discord(true, server.base() + "/discord", null, null, null, EnumSet.of(Event.UPDATED)),
                        new UpdaterSettings.Webhook(true, "http://127.0.0.1:1/unreachable", Map.of(), EnumSet.allOf(Event.class)),
                        SERVER),
                TestHttp.service().withRetry(com.hanafubuki.updater.http.RetryPolicy.NONE), logger);
        service.onRunFinished(report(updated("A", "1", "2")));
        service.shutdown();
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("Discord notification failed: HTTP 400"), warnings.get(0));
        assertTrue(warnings.get(0).contains("Invalid Form Body"), warnings.get(0));
        assertTrue(warnings.get(1).startsWith("webhook notification failed: "), warnings.get(1));
    }

    @Test
    void sendTestReachesEveryEnabledTarget() {
        server.route("/discord", r -> Reply.status(204));
        server.route("/hook", r -> Reply.status(200));
        NotificationService service = new NotificationService(settings(true, true), TestHttp.service(), logger);
        try {
            service.sendTest("Steve");
            waitFor(() -> server.requests("/discord").size() == 1 && server.requests("/hook").size() == 1);
            JsonObject discordBody = JsonParser.parseString(server.requests("/discord").get(0).body()).getAsJsonObject();
            String description = embeds(discordBody).get(0).get("description").getAsString();
            assertTrue(description.contains("**Hanafubuki**"), description);
            assertTrue(description.contains("Requested by Steve."), description);
            JsonObject hookBody = JsonParser.parseString(server.requests("/hook").get(0).body()).getAsJsonObject();
            assertEquals("test", hookBody.get("event").getAsString());
            assertEquals("Steve", hookBody.get("requested_by").getAsString());
            assertEquals(SERVER, hookBody.get("server").getAsString());
            assertEquals("Bearer hook-secret", server.requests("/hook").get(0).header("Authorization"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void splitDiscordMessagesAreAllDelivered() {
        server.route("/discord", r -> Reply.status(204));
        NotificationService service = new NotificationService(
                new UpdaterSettings.Notifications(true, true,
                        new UpdaterSettings.Discord(true, server.base() + "/discord", null, null, "<@&5>",
                                EnumSet.of(Event.SECURITY)),
                        new UpdaterSettings.Webhook(false, null, Map.of(), Set.of()), SERVER),
                TestHttp.service(), logger);
        service.onRunFinished(report(withAlerts("ViaVersion", 15)));
        service.shutdown();
        List<FakeHttpServer.Request> requests = server.requests("/discord");
        assertEquals(2, requests.size());
        JsonObject first = JsonParser.parseString(requests.get(0).body()).getAsJsonObject();
        JsonObject second = JsonParser.parseString(requests.get(1).body()).getAsJsonObject();
        assertEquals(10, embeds(first).size());
        assertEquals(5, embeds(second).size());
        assertEquals("<@&5>", first.get("content").getAsString());
        assertFalse(second.has("content"));
    }

    @Test
    void httpServiceUsedForSendingIsTheGivenOne() {
        // The service never builds its own client: all traffic carries the shared User-Agent.
        server.route("/hook", r -> Reply.status(200));
        HttpService http = TestHttp.service();
        NotificationService service = new NotificationService(settings(false, true), http, logger);
        service.sendTest("console");
        service.shutdown();
        assertEquals(http.userAgent(), server.requests("/hook").get(0).header("User-Agent"));
    }
}
