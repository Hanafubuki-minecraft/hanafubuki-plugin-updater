package com.hanafubuki.updater.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.hanafubuki.updater.config.UpdaterSettings.Event;
import com.hanafubuki.updater.http.RetryPolicy;
import com.hanafubuki.updater.manager.Schedule;
import com.hanafubuki.updater.source.GitHubSource;
import com.hanafubuki.updater.source.SourceSettings;

class SettingsParserTest {

    private static final Function<String, String> NO_ENV = name -> null;

    private static SettingsParser.Result parse(String yaml) {
        return parse(yaml, NO_ENV);
    }

    private static SettingsParser.Result parse(String yaml, Function<String, String> env) {
        Map<String, Object> root = new Yaml().load(yaml);
        return SettingsParser.parse(root, env);
    }

    private static void assertProblem(SettingsParser.Result r, String expected) {
        assertTrue(r.problems().stream().anyMatch(p -> p.contains(expected)),
                "expected a problem containing '" + expected + "' in " + r.problems());
    }

    // --- defaults ------------------------------------------------------------------------------

    @Test
    void emptyConfigUsesDefaultsWithoutProblems() {
        for (SettingsParser.Result r : List.of(parse(""), parse("{}"),
                SettingsParser.parse(null, NO_ENV), SettingsParser.parse(Map.of()))) {
            assertEquals(List.of(), r.problems());
            UpdaterSettings s = r.settings();

            UpdaterSettings.General g = s.general();
            assertNull(g.minVersion());
            assertNull(g.maxVersion());
            assertTrue(g.autoUpdateConfig());
            assertFalse(g.autoDiscover());
            assertFalse(g.discoverOnStartup());
            assertEquals(4, g.parallelism());
            assertEquals(256L * 1024 * 1024, g.maxDownloadBytes());

            assertFalse(s.modes().includeOnly());
            assertEquals(Set.of(), s.modes().includeList());
            assertEquals(Set.of(), s.modes().excludeList());

            Schedule sch = s.schedule();
            assertEquals(Schedule.Mode.OFF, sch.mode());
            assertEquals(Duration.ofMinutes(360), sch.interval());
            assertEquals(LocalTime.of(4, 0), sch.time());
            assertEquals(DayOfWeek.MONDAY, sch.day());
            assertEquals(ZoneId.systemDefault(), sch.zone());
            assertFalse(sch.checkOnStartup());
            assertEquals(Duration.ofSeconds(60), sch.startupDelay());
            assertFalse(sch.dryRun());

            assertEquals(SourceSettings.DEFAULT, s.modrinth().source());
            assertEquals(List.of("paper"), s.modrinth().loaders());
            assertEquals(List.of(), s.modrinth().gameVersions());
            assertEquals(List.of("release"), s.modrinth().versionTypes());

            assertEquals(SourceSettings.DEFAULT, s.github().source());
            assertNull(s.github().token());
            assertEquals(GitHubSource.SignatureMode.OFF, s.github().signatures());
            assertFalse(s.github().includePrereleases());
            assertTrue(s.githubAdvisories());

            assertEquals("Release", s.hangar().channel());
            assertEquals(SourceSettings.DEFAULT, s.hangar().source());
            assertEquals(SourceSettings.DEFAULT, s.spigotmc());
            assertEquals(SourceSettings.DEFAULT, s.custom());

            assertEquals(new UpdaterSettings.Backups(true, 3), s.backups());
            assertEquals(UpdaterSettings.DependencyMode.BLOCK, s.dependencies());

            UpdaterSettings.Notifications n = s.notifications();
            assertTrue(n.inGame());
            assertTrue(n.onJoin());
            assertEquals("Minecraft server", n.serverName());
            assertFalse(n.discord().enabled());
            assertNull(n.discord().url());
            assertEquals("Hanafubuki Updater", n.discord().username());
            assertNull(n.discord().avatarUrl());
            assertNull(n.discord().mention());
            assertEquals(EnumSet.of(Event.UPDATED, Event.FAILED, Event.SECURITY), n.discord().events());
            assertFalse(n.webhook().enabled());
            assertNull(n.webhook().url());
            assertEquals(Map.of(), n.webhook().headers());
            assertEquals(EnumSet.allOf(Event.class), n.webhook().events());

            assertEquals(Map.of("primary", "<#f9a8d4>", "accent", "<#c4b5fd>", "success", "<green>",
                    "warning", "<yellow>", "error", "<red>", "muted", "<gray>"), s.style().colors());
            assertTrue(s.style().prefix().contains("Hanafubuki"));
            assertFalse(s.consoleJson());

            assertEquals(new UpdaterSettings.Health(false, "127.0.0.1", 8080), s.health());
            assertEquals(Map.of(), s.plugins());
        }
    }

    // --- valid values --------------------------------------------------------------------------

    @Test
    void fullValidConfig() {
        SettingsParser.Result r = parse("""
                global:
                  min_version: "1.0"
                  max_version: "9.9"
                  auto_update_config: false
                  auto_discover: yes
                  auto_discover_on_startup: "on"
                  parallelism: 8
                  max_download_mb: 64
                modes:
                  include_only: true
                  include_list: [LuckPerms, Essentials, LuckPerms]
                  exclude_list: WorldEdit
                modrinth:
                  loaders: [paper, purpur]
                  game_versions: ["1.21.9"]
                  version_types: [Release, BETA]
                  timeout: 20
                  download_timeout: 300
                  retry:
                    max_retries: 5
                    base_delay_ms: 500
                    max_delay_ms: 4000
                github:
                  token: ghp_plain
                  signatures: require
                  include_prereleases: true
                  advisories: false
                hangar:
                  channel: Snapshot
                backups:
                  enabled: false
                  keep: 10
                dependencies:
                  check: warn
                console:
                  json_format: true
                health_check:
                  enabled: true
                  bind_address: 0.0.0.0
                  port: 9100
                """);
        assertEquals(List.of(), r.problems());
        UpdaterSettings s = r.settings();
        assertEquals(new UpdaterSettings.General("1.0", "9.9", false, true, true, 8, 64L * 1024 * 1024), s.general());
        assertTrue(s.modes().includeOnly());
        assertEquals(List.of("LuckPerms", "Essentials"), List.copyOf(s.modes().includeList()));
        assertEquals(Set.of("WorldEdit"), s.modes().excludeList());
        assertEquals(List.of("paper", "purpur"), s.modrinth().loaders());
        assertEquals(List.of("1.21.9"), s.modrinth().gameVersions());
        assertEquals(List.of("release", "beta"), s.modrinth().versionTypes());
        assertEquals(new SourceSettings(Duration.ofSeconds(20), Duration.ofSeconds(300), new RetryPolicy(5, 500, 4000)),
                s.modrinth().source());
        assertEquals("ghp_plain", s.github().token());
        assertEquals(GitHubSource.SignatureMode.REQUIRE, s.github().signatures());
        assertTrue(s.github().includePrereleases());
        assertFalse(s.githubAdvisories());
        assertEquals("Snapshot", s.hangar().channel());
        assertEquals(new UpdaterSettings.Backups(false, 10), s.backups());
        assertEquals(UpdaterSettings.DependencyMode.WARN, s.dependencies());
        assertTrue(s.consoleJson());
        assertEquals(new UpdaterSettings.Health(true, "0.0.0.0", 9100), s.health());
    }

    @Test
    void yamlBooleanOffForSignaturesMeansOff() {
        // YAML 1.1 reads a bare "off" as boolean false
        SettingsParser.Result r = parse("github:\n  signatures: off\n");
        assertEquals(List.of(), r.problems());
        assertEquals(GitHubSource.SignatureMode.OFF, r.settings().github().signatures());
        assertEquals(GitHubSource.SignatureMode.WARN, parse("github:\n  signatures: warn\n").settings().github().signatures());
    }

    // --- invalid values ------------------------------------------------------------------------

    @Test
    void invalidValuesAreReportedWithPathAndDefaultsAreUsed() {
        SettingsParser.Result r = parse("""
                global:
                  auto_update_config: maybe
                  parallelism: lots
                  max_download_mb: 999999
                  min_version: [1, 2]
                modes:
                  include_only: {a: b}
                modrinth:
                  version_types: [release, nightly]
                  timeout: 0
                github:
                  signatures: sometimes
                backups:
                  keep: 0
                dependencies:
                  check: strict
                schedule:
                  mode: hourly
                  interval_minutes: 5
                  time: "25:99"
                  day: someday
                  timezone: Mars/Olympus
                health_check:
                  port: 70000
                """);
        UpdaterSettings s = r.settings();

        assertProblem(r, "global.auto_update_config: 'maybe' is not true or false");
        assertTrue(s.general().autoUpdateConfig());

        assertProblem(r, "global.parallelism: 'lots' is not a number");
        assertEquals(4, s.general().parallelism());

        assertProblem(r, "global.max_download_mb: 999999 is outside 1..2048");
        assertEquals(2048L * 1024 * 1024, s.general().maxDownloadBytes());

        assertProblem(r, "global.min_version: expected a single value");
        assertNull(s.general().minVersion());

        assertProblem(r, "modes.include_only: '{a=b}' is not true or false");
        assertFalse(s.modes().includeOnly());

        assertProblem(r, "modrinth.version_types: unknown type 'nightly'");
        assertEquals(List.of("release"), s.modrinth().versionTypes());

        assertProblem(r, "modrinth.timeout: 0 is outside 1..300");
        assertEquals(Duration.ofSeconds(1), s.modrinth().source().apiTimeout());

        assertProblem(r, "github.signatures: signatures must be off, warn or require");
        assertEquals(GitHubSource.SignatureMode.OFF, s.github().signatures());

        assertProblem(r, "backups.keep: 0 is outside 1..100");
        assertEquals(1, s.backups().keep());

        assertProblem(r, "dependencies.check: 'strict' is not off, warn or block");
        assertEquals(UpdaterSettings.DependencyMode.BLOCK, s.dependencies());

        assertProblem(r, "schedule.mode: 'hourly' is not off, interval, daily or weekly");
        assertProblem(r, "schedule.interval_minutes: 5 is outside 15..10080");
        assertProblem(r, "schedule.time: '25:99' is not HH:MM");
        assertProblem(r, "schedule.day: 'someday' is not a weekday name");
        assertProblem(r, "schedule.timezone: unknown time zone 'Mars/Olympus'");
        assertEquals(Schedule.Mode.OFF, s.schedule().mode());
        assertEquals(Duration.ofMinutes(15), s.schedule().interval());
        assertEquals(LocalTime.of(4, 0), s.schedule().time());
        assertEquals(DayOfWeek.MONDAY, s.schedule().day());
        assertEquals(ZoneId.systemDefault(), s.schedule().zone());

        assertProblem(r, "health_check.port: 70000 is outside 1..65535");
        assertEquals(65535, s.health().port());

        assertEquals(16, r.problems().size(), r.problems().toString());
    }

    @Test
    void onlyUnknownVersionTypesFallBackToRelease() {
        SettingsParser.Result r = parse("modrinth:\n  version_types: [nightly, snapshot]\n");
        assertEquals(2, r.problems().size());
        assertEquals(List.of("release"), r.settings().modrinth().versionTypes());
    }

    @Test
    void retryMaxRetriesOutOfRangeIsClamped() {
        SettingsParser.Result r = parse("github:\n  retry:\n    max_retries: 50\n    base_delay_ms: -5\n");
        assertProblem(r, "github.retry.max_retries: 50 is outside 0..10");
        assertProblem(r, "github.retry.base_delay_ms: -5 is outside 0..60000");
        assertEquals(new RetryPolicy(10, 0, 10_000), r.settings().github().source().retry());
    }

    @Test
    void problemsListIsImmutable() {
        SettingsParser.Result r = parse("global:\n  parallelism: x\n");
        assertEquals(1, r.problems().size());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> r.problems().add("x"));
    }

    // --- style and old keys --------------------------------------------------------------------

    @Test
    void oldConsoleColorsMapToStyle() {
        SettingsParser.Result r = parse("""
                console:
                  colors:
                    info: "<aqua>"
                    success: "<dark_green>"
                    warning: "<gold>"
                    error: "<dark_red>"
                """);
        assertEquals(List.of(), r.problems());
        Map<String, String> colors = r.settings().style().colors();
        assertEquals("<aqua>", colors.get("primary"));
        assertEquals("<dark_green>", colors.get("success"));
        assertEquals("<gold>", colors.get("warning"));
        assertEquals("<dark_red>", colors.get("error"));
        assertEquals("<gray>", colors.get("muted"));
    }

    @Test
    void newStyleSectionWinsOverOldConsoleColors() {
        SettingsParser.Result r = parse("""
                console:
                  colors:
                    info: "<aqua>"
                    error: "<dark_red>"
                style:
                  prefix: "<white>[Updater]</white> "
                  primary: "<#ff00ff>"
                  muted: "  "
                  unknown_key: "<blue>"
                """);
        UpdaterSettings.Style style = r.settings().style();
        // The trailing space separates the prefix from the message and must survive.
        assertEquals("<white>[Updater]</white> ", style.prefix());
        assertEquals("<#ff00ff>", style.colors().get("primary"));
        assertEquals("<dark_red>", style.colors().get("error"));
        // blank values keep the default; unknown keys are not added
        assertEquals("<gray>", style.colors().get("muted"));
        assertFalse(style.colors().containsKey("unknown_key"));
    }

    // --- secrets -------------------------------------------------------------------------------

    @Test
    void secretsResolveEnvironmentVariables() {
        Map<String, String> env = Map.of("GH_TOKEN", "ghp_from_env", "DISCORD_URL", "https://discord.com/api/webhooks/1/abc",
                "HOOK_AUTH", "Bearer xyz");
        SettingsParser.Result r = parse("""
                github:
                  token: ${GH_TOKEN}
                notifications:
                  discord:
                    enabled: true
                    webhook_url: "${DISCORD_URL}"
                  webhook:
                    enabled: true
                    url: https://example.com/hook
                    headers:
                      Authorization: ${HOOK_AUTH}
                      X-Static: plain
                """, env::get);
        assertEquals(List.of(), r.problems());
        assertEquals("ghp_from_env", r.settings().github().token());
        assertTrue(r.settings().notifications().discord().enabled());
        assertEquals("https://discord.com/api/webhooks/1/abc", r.settings().notifications().discord().url());
        assertEquals(Map.of("Authorization", "Bearer xyz", "X-Static", "plain"),
                r.settings().notifications().webhook().headers());
    }

    @Test
    void unsetEnvironmentVariablesAreReported() {
        SettingsParser.Result r = parse("""
                github:
                  token: ${MISSING_TOKEN}
                notifications:
                  discord:
                    enabled: true
                    webhook_url: ${MISSING_URL}
                  webhook:
                    headers:
                      Authorization: ${BLANK}
                      X-Ok: fine
                """, name -> name.equals("BLANK") ? "  " : null);
        assertProblem(r, "github.token: environment variable MISSING_TOKEN is not set");
        assertNull(r.settings().github().token());
        assertProblem(r, "notifications.discord.webhook_url: environment variable MISSING_URL is not set");
        assertProblem(r, "notifications.discord.webhook_url must be an https URL");
        assertFalse(r.settings().notifications().discord().enabled());
        assertProblem(r, "notifications.webhook.headers.Authorization: environment variable BLANK is not set");
        assertEquals(Map.of("X-Ok", "fine"), r.settings().notifications().webhook().headers());
    }

    @Test
    void defaultEnvironmentOverloadReadsRealEnvironment() {
        // PATH exists in every test environment; the single-argument overload uses System.getenv.
        SettingsParser.Result r = SettingsParser.parse(new Yaml().load("github:\n  token: ${PATH}\n"));
        assertEquals(List.of(), r.problems());
        assertEquals(System.getenv("PATH"), r.settings().github().token());
    }

    // --- notifications -------------------------------------------------------------------------

    @Test
    void discordAndWebhookNeedHttpsUrls() {
        SettingsParser.Result r = parse("""
                notifications:
                  discord:
                    enabled: true
                    webhook_url: http://discord.com/api/webhooks/1/abc
                  webhook:
                    enabled: true
                    url: not a url
                """);
        assertProblem(r, "notifications.discord.webhook_url must be an https URL; Discord notifications are off");
        assertProblem(r, "notifications.webhook.url must be an https URL; the webhook is off");
        assertFalse(r.settings().notifications().discord().enabled());
        assertFalse(r.settings().notifications().webhook().enabled());
    }

    @Test
    void enabledWithoutUrlIsDisabled() {
        SettingsParser.Result r = parse("notifications:\n  webhook:\n    enabled: true\n");
        assertProblem(r, "notifications.webhook.url must be an https URL");
        assertFalse(r.settings().notifications().webhook().enabled());
    }

    @Test
    void disabledTargetsDoNotValidateUrls() {
        SettingsParser.Result r = parse("""
                notifications:
                  discord:
                    enabled: false
                    webhook_url: http://insecure
                """);
        assertEquals(List.of(), r.problems());
    }

    @Test
    void notificationOptionsAndEvents() {
        SettingsParser.Result r = parse("""
                notifications:
                  in_game: false
                  on_join: false
                  server_name: Hanafubuki
                  discord:
                    enabled: true
                    webhook_url: https://discord.com/api/webhooks/1/abc
                    username: Bot
                    avatar_url: https://example.com/a.png
                    mention: "<@&123>"
                    events: [updated, Summary, explode]
                  webhook:
                    enabled: true
                    url: https://example.com/hook
                    events: security
                """);
        assertEquals(List.of("notifications.discord.events: unknown event 'explode' (updated, available, failed, security, summary)"),
                r.problems());
        UpdaterSettings.Notifications n = r.settings().notifications();
        assertFalse(n.inGame());
        assertFalse(n.onJoin());
        assertEquals("Hanafubuki", n.serverName());
        assertEquals(new UpdaterSettings.Discord(true, "https://discord.com/api/webhooks/1/abc", "Bot",
                "https://example.com/a.png", "<@&123>", EnumSet.of(Event.UPDATED, Event.SUMMARY)), n.discord());
        assertEquals(EnumSet.of(Event.SECURITY), n.webhook().events());
    }

    @Test
    void emptyEventListMeansNoEvents() {
        SettingsParser.Result r = parse("notifications:\n  webhook:\n    events: []\n");
        assertEquals(Set.of(), r.settings().notifications().webhook().events());
    }

    // --- schedule ------------------------------------------------------------------------------

    @Test
    void scheduleModesAndValues() {
        SettingsParser.Result r = parse("""
                schedule:
                  mode: Weekly
                  interval_minutes: 90
                  time: "4:30"
                  day: friday
                  timezone: Europe/Kyiv
                  check_on_startup: true
                  startup_delay_seconds: 0
                  check_only: true
                """);
        assertEquals(List.of(), r.problems());
        Schedule s = r.settings().schedule();
        assertEquals(new Schedule(Schedule.Mode.WEEKLY, Duration.ofMinutes(90), LocalTime.of(4, 30), DayOfWeek.FRIDAY,
                ZoneId.of("Europe/Kyiv"), true, Duration.ZERO, true), s);
        assertEquals(Schedule.Mode.INTERVAL, parse("schedule:\n  mode: interval\n").settings().schedule().mode());
        assertEquals(Schedule.Mode.DAILY, parse("schedule:\n  mode: DAILY\n").settings().schedule().mode());
    }

    @Test
    void bareOffIsAcceptedForScheduleModeAndDependencyCheck() {
        // YAML 1.1 reads a bare off as boolean false; it must still mean "off"
        SettingsParser.Result r = parse("""
                schedule:
                  mode: off
                dependencies:
                  check: off
                """);
        assertEquals(List.of(), r.problems());
        assertEquals(Schedule.Mode.OFF, r.settings().schedule().mode());
        assertEquals(UpdaterSettings.DependencyMode.OFF, r.settings().dependencies());

        SettingsParser.Result quoted = parse("schedule:\n  mode: \"off\"\ndependencies:\n  check: \"OFF\"\n");
        assertEquals(List.of(), quoted.problems());
        assertEquals(Schedule.Mode.OFF, quoted.settings().schedule().mode());
        assertEquals(UpdaterSettings.DependencyMode.OFF, quoted.settings().dependencies());
    }

    @Test
    void bareOnIsNotAScheduleMode() {
        SettingsParser.Result r = parse("schedule:\n  mode: on\n");
        assertProblem(r, "schedule.mode: 'true' is not off, interval, daily or weekly");
        assertEquals(Schedule.Mode.OFF, r.settings().schedule().mode());
    }

    @Test
    void scheduleTimeAcceptsTwoDigitHours() {
        assertEquals(LocalTime.of(23, 5), parse("schedule:\n  time: \"23:05\"\n").settings().schedule().time());
        SettingsParser.Result bad = parse("schedule:\n  time: \"4.30\"\n");
        assertProblem(bad, "schedule.time: '4.30' is not HH:MM");
    }

    // --- plugins -------------------------------------------------------------------------------

    @Test
    void pluginEntriesAreParsedInOrder() {
        SettingsParser.Result r = parse("""
                plugins:
                  LuckPerms:
                    source: modrinth
                    project_id: Vebnzrzj
                  EssentialsX:
                    source: GitHub
                    repo: EssentialsX/Essentials
                    asset_pattern: "EssentialsX-*.jar"
                  "123":
                    source: spigotmc
                    resource_id: 1234
                """);
        assertEquals(List.of(), r.problems());
        assertEquals(List.of("LuckPerms", "EssentialsX", "123"), r.settings().pluginNames());
        PluginEntry e = r.settings().plugins().get("EssentialsX");
        assertEquals("github", e.source());
        assertEquals("EssentialsX/Essentials", e.string("repo"));
        assertFalse(e.options().containsKey("source"));
        assertEquals(1234, r.settings().plugins().get("123").options().get("resource_id"));
    }

    @Test
    void badPluginEntriesAreSkippedWithProblems() {
        SettingsParser.Result r = parse("""
                plugins:
                  NoSource:
                    project_id: abc
                  BlankSource:
                    source: "  "
                  Unknown:
                    source: curseforge
                  JustText: modrinth
                  Good:
                    source: modrinth
                    project_id: good
                  good:
                    source: hangar
                  GOOD:
                    source: github
                """);
        assertProblem(r, "plugins.NoSource: missing 'source' (custom, github, hangar, local, modrinth, spigotmc)");
        assertProblem(r, "plugins.BlankSource: missing 'source'");
        assertProblem(r, "plugins.Unknown: unknown source 'curseforge'");
        assertProblem(r, "plugins.JustText: must be a section with at least 'source'");
        assertProblem(r, "plugins.good: listed twice (names are case-insensitive); the second entry is ignored");
        assertProblem(r, "plugins.GOOD: listed twice");
        assertEquals(6, r.problems().size(), r.problems().toString());
        assertEquals(List.of("Good"), r.settings().pluginNames());
        assertEquals("modrinth", r.settings().plugins().get("Good").source());
    }

    @Test
    void pluginsMustBeASection() {
        SettingsParser.Result r = parse("plugins: [a, b]\n");
        assertEquals(List.of("plugins: must be a section of plugin entries"), r.problems());
        assertEquals(Map.of(), r.settings().plugins());
        assertEquals(List.of(), parse("plugins:\n").problems());
    }

    @Test
    void everyKnownSourceIsAccepted() {
        StringBuilder yaml = new StringBuilder("plugins:\n");
        for (String source : SettingsParser.SOURCES) {
            yaml.append("  P_").append(source).append(":\n    source: ").append(source).append('\n');
        }
        SettingsParser.Result r = parse(yaml.toString());
        assertEquals(List.of(), r.problems());
        assertEquals(SettingsParser.SOURCES.size(), r.settings().plugins().size());
    }

    // --- modes ---------------------------------------------------------------------------------

    @Test
    void modesMatchCaseInsensitively() {
        UpdaterSettings.Modes modes = parse("""
                modes:
                  include_only: true
                  include_list: [LuckPerms]
                  exclude_list: [worldedit]
                """).settings().modes();
        assertTrue(modes.isIncluded("luckperms"));
        assertTrue(modes.isIncluded("LUCKPERMS"));
        assertFalse(modes.isIncluded("WorldEdit"));
        assertTrue(modes.isExcluded("WorldEdit"));
        assertFalse(modes.isExcluded("LuckPerms"));
    }

    @Test
    void blankListEntriesAreIgnored() {
        UpdaterSettings.Modes modes = parse("modes:\n  exclude_list: [\"\", \" A \", null]\n").settings().modes();
        assertEquals(Set.of("A"), modes.excludeList());
    }
}
