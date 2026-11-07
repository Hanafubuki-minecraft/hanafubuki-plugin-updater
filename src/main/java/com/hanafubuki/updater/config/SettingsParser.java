package com.hanafubuki.updater.config;

import java.net.URI;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.hanafubuki.updater.http.RetryPolicy;
import com.hanafubuki.updater.manager.Schedule;
import com.hanafubuki.updater.source.GitHubSource;
import com.hanafubuki.updater.source.HangarSource;
import com.hanafubuki.updater.source.ModrinthSource;
import com.hanafubuki.updater.source.SourceSettings;

/**
 * Turns the raw config.yml tree (nested maps and lists) into {@link UpdaterSettings}.
 *
 * <p>Never throws for bad values: every problem is reported with its config path and a safe
 * default is used instead, so one typo does not disable the whole plugin.
 */
public final class SettingsParser {

    /** Known source ids. */
    public static final Set<String> SOURCES = Set.of("modrinth", "github", "hangar", "spigotmc", "local", "custom");

    /** Result of parsing: the settings plus every problem found. */
    public record Result(UpdaterSettings settings, List<String> problems) {
    }

    private final Map<String, Object> root;
    private final List<String> problems = new ArrayList<>();
    private final java.util.function.Function<String, String> env;

    private SettingsParser(Map<String, Object> root, java.util.function.Function<String, String> env) {
        this.root = root;
        this.env = env;
    }

    public static Result parse(Map<String, Object> root) {
        return parse(root, System::getenv);
    }

    /** For tests: custom environment lookup (used for ${VAR} references in secrets). */
    public static Result parse(Map<String, Object> root, java.util.function.Function<String, String> env) {
        SettingsParser p = new SettingsParser(root == null ? Map.of() : root, env);
        UpdaterSettings settings = p.build();
        return new Result(settings, List.copyOf(p.problems));
    }

    /** Keys that were removed, with what to do instead. */
    private static final Map<String, String> REMOVED = Map.of(
            "global.auto_update_config", "versions are no longer written to config.yml; state.json keeps them",
            "global.auto_discover", "use global.auto_detect; found plugins wait in plugins.json for /updateplugins confirm",
            "global.auto_discover_on_startup", "use global.auto_detect; found plugins wait in plugins.json for /updateplugins confirm");

    private UpdaterSettings build() {
        new TreeMap<>(REMOVED).forEach((key, hint) -> {
            if (get(key) != null) {
                problems.add(key + ": removed in 1.6.0, delete this line (" + hint + ")");
            }
        });
        UpdaterSettings.General general = new UpdaterSettings.General(
                string("global.min_version", null),
                string("global.max_version", null),
                bool("global.auto_detect", true),
                integer("global.parallelism", 4, 1, 16),
                integer("global.max_download_mb", 256, 1, 2048) * 1024L * 1024L);

        UpdaterSettings.Modes modes = new UpdaterSettings.Modes(
                bool("modes.include_only", false),
                nameSet("modes.include_list"),
                nameSet("modes.exclude_list"));

        ModrinthSource.Settings modrinth = new ModrinthSource.Settings(source("modrinth"),
                stringList("modrinth.loaders", List.of("paper")),
                stringList("modrinth.game_versions", List.of()),
                versionTypes());

        String token = secret("github.token");
        GitHubSource.SignatureMode signatures;
        try {
            signatures = GitHubSource.SignatureMode.parse(string("github.signatures", "off"));
        } catch (IllegalArgumentException e) {
            problems.add("github.signatures: " + e.getMessage());
            signatures = GitHubSource.SignatureMode.OFF;
        }
        GitHubSource.Settings github = new GitHubSource.Settings(source("github"), token, signatures,
                bool("github.include_prereleases", false));

        HangarSource.Settings hangar = new HangarSource.Settings(source("hangar"), string("hangar.channel", "Release"));

        return new UpdaterSettings(general, modes, schedule(), modrinth, github, bool("github.advisories", true), hangar,
                source("spigotmc"), source("custom"),
                new UpdaterSettings.Backups(bool("backups.enabled", true), integer("backups.keep", 3, 1, 100)),
                dependencyMode(), notifications(), style(), bool("console.json_format", false), health(), plugins(), pluginKeys());
    }

    // --- sections -------------------------------------------------------------------------

    private SourceSettings source(String section) {
        int apiTimeout = integer(section + ".timeout", 10, 1, 300);
        int downloadTimeout = integer(section + ".download_timeout", 120, 5, 3600);
        RetryPolicy retry;
        try {
            retry = new RetryPolicy(integer(section + ".retry.max_retries", 3, 0, 10),
                    integer(section + ".retry.base_delay_ms", 1000, 0, 60_000),
                    integer(section + ".retry.max_delay_ms", 10_000, 0, 300_000));
        } catch (IllegalArgumentException e) {
            problems.add(section + ".retry: " + e.getMessage());
            retry = RetryPolicy.DEFAULT;
        }
        return new SourceSettings(Duration.ofSeconds(apiTimeout), Duration.ofSeconds(downloadTimeout), retry);
    }

    private List<String> versionTypes() {
        List<String> types = stringList("modrinth.version_types", List.of("release"));
        List<String> valid = new ArrayList<>();
        for (String t : types) {
            String lower = t.toLowerCase(Locale.ROOT);
            if (List.of("release", "beta", "alpha").contains(lower)) {
                valid.add(lower);
            } else {
                problems.add("modrinth.version_types: unknown type '" + t + "' (release, beta, alpha)");
            }
        }
        return valid.isEmpty() ? List.of("release") : valid;
    }

    private Schedule schedule() {
        Schedule d = Schedule.DISABLED;
        Schedule.Mode mode;
        String modeText = offAware(string("schedule.mode", "off"));
        try {
            mode = Schedule.Mode.valueOf(modeText.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add("schedule.mode: '" + modeText + "' is not off, interval, daily or weekly");
            mode = Schedule.Mode.OFF;
        }
        Duration interval = Duration.ofMinutes(integer("schedule.interval_minutes", 360, 15, 10_080));
        LocalTime time = d.time();
        String timeText = string("schedule.time", "04:00");
        if (get("schedule.time") instanceof Number n && n.intValue() >= 0 && n.intValue() < 24 * 60) {
            // YAML 1.1 reads an unquoted 4:30 as the base-60 number 270.
            timeText = String.format("%02d:%02d", n.intValue() / 60, n.intValue() % 60);
        }
        try {
            time = LocalTime.parse(timeText.length() == 4 ? "0" + timeText : timeText);
        } catch (DateTimeException e) {
            problems.add("schedule.time: '" + timeText + "' is not HH:MM");
        }
        DayOfWeek day = d.day();
        String dayText = string("schedule.day", "monday");
        try {
            day = DayOfWeek.valueOf(dayText.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add("schedule.day: '" + dayText + "' is not a weekday name");
        }
        ZoneId zone = d.zone();
        String zoneText = string("schedule.timezone", null);
        if (zoneText != null) {
            try {
                zone = ZoneId.of(zoneText);
            } catch (DateTimeException e) {
                problems.add("schedule.timezone: unknown time zone '" + zoneText + "'");
            }
        }
        return new Schedule(mode, interval, time, day, zone, bool("schedule.check_on_startup", false),
                Duration.ofSeconds(integer("schedule.startup_delay_seconds", 60, 0, 3600)),
                bool("schedule.check_only", false));
    }

    private UpdaterSettings.DependencyMode dependencyMode() {
        String m = offAware(string("dependencies.check", "block")).toUpperCase(Locale.ROOT);
        try {
            return UpdaterSettings.DependencyMode.valueOf(m);
        } catch (IllegalArgumentException e) {
            problems.add("dependencies.check: '" + m.toLowerCase(Locale.ROOT) + "' is not off, warn or block");
            return UpdaterSettings.DependencyMode.BLOCK;
        }
    }

    private UpdaterSettings.Notifications notifications() {
        boolean discordEnabled = bool("notifications.discord.enabled", false);
        String discordUrl = secret("notifications.discord.webhook_url");
        if (discordEnabled && !validHttpsUrl(discordUrl)) {
            problems.add("notifications.discord.webhook_url must be an https URL; Discord notifications are off");
            discordEnabled = false;
        }
        UpdaterSettings.Discord discord = new UpdaterSettings.Discord(discordEnabled, discordUrl,
                string("notifications.discord.username", "Hanafubuki Updater"),
                string("notifications.discord.avatar_url", null),
                string("notifications.discord.mention", null),
                events("notifications.discord.events", EnumSet.of(UpdaterSettings.Event.UPDATED,
                        UpdaterSettings.Event.FAILED, UpdaterSettings.Event.SECURITY)));

        boolean hookEnabled = bool("notifications.webhook.enabled", false);
        String hookUrl = secret("notifications.webhook.url");
        if (hookEnabled && !validHttpsUrl(hookUrl)) {
            problems.add("notifications.webhook.url must be an https URL; the webhook is off");
            hookEnabled = false;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (get("notifications.webhook.headers") instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String value = resolveEnv(String.valueOf(e.getValue()), "notifications.webhook.headers." + e.getKey());
                if (value != null) {
                    headers.put(String.valueOf(e.getKey()), value);
                }
            }
        }
        UpdaterSettings.Webhook webhook = new UpdaterSettings.Webhook(hookEnabled, hookUrl, headers,
                events("notifications.webhook.events", EnumSet.allOf(UpdaterSettings.Event.class)));

        return new UpdaterSettings.Notifications(bool("notifications.in_game", true),
                bool("notifications.on_join", true), discord, webhook,
                string("notifications.server_name", "Minecraft server"));
    }

    private UpdaterSettings.Style style() {
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("primary", "<#f9a8d4>");
        colors.put("accent", "<#c4b5fd>");
        colors.put("success", "<green>");
        colors.put("warning", "<yellow>");
        colors.put("error", "<red>");
        colors.put("muted", "<gray>");
        // Old configs had console.colors with info/warning/error/success.
        if (get("console.colors") instanceof Map<?, ?> old) {
            copyColor(old, "info", colors, "primary");
            copyColor(old, "success", colors, "success");
            copyColor(old, "warning", colors, "warning");
            copyColor(old, "error", colors, "error");
        }
        if (get("style") instanceof Map<?, ?> style) {
            for (String key : List.copyOf(colors.keySet())) {
                copyColor(style, key, colors, key);
            }
        }
        // Not trimmed: the prefix usually ends with a space before the message.
        Object rawPrefix = get("style.prefix");
        String prefix = rawPrefix == null || rawPrefix instanceof Map<?, ?> || rawPrefix instanceof Collection<?>
                ? "<gradient:#f9a8d4:#c4b5fd>Hanafubuki</gradient> <dark_gray>»</dark_gray> "
                : String.valueOf(rawPrefix);
        return new UpdaterSettings.Style(prefix, colors);
    }

    private static void copyColor(Map<?, ?> from, String fromKey, Map<String, String> to, String toKey) {
        Object v = from.get(fromKey);
        if (v != null && !String.valueOf(v).isBlank()) {
            to.put(toKey, String.valueOf(v).trim());
        }
    }

    private UpdaterSettings.Health health() {
        boolean enabled = bool("health_check.enabled", false);
        return new UpdaterSettings.Health(enabled, string("health_check.bind_address", "127.0.0.1"),
                integer("health_check.port", 8080, 1, 65535));
    }

    /** Every name under plugins:, including entries with errors; they still override plugins.json. */
    private Set<String> pluginKeys() {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (get("plugins") instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                String name = String.valueOf(key).trim();
                if (!name.isEmpty()) {
                    out.add(name);
                }
            }
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    private Map<String, PluginEntry> plugins() {
        Map<String, PluginEntry> out = new LinkedHashMap<>();
        Object section = get("plugins");
        if (section == null) {
            return out;
        }
        if (!(section instanceof Map<?, ?> map)) {
            problems.add("plugins: must be a section of plugin entries");
            return out;
        }
        Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String name = String.valueOf(e.getKey()).trim();
            if (!(e.getValue() instanceof Map<?, ?> raw)) {
                problems.add("plugins." + name + ": must be a section with at least 'source'");
                continue;
            }
            if (!seen.add(name)) {
                problems.add("plugins." + name + ": listed twice (names are case-insensitive); the second entry is ignored");
                continue;
            }
            Map<String, Object> options = new LinkedHashMap<>();
            for (Map.Entry<?, ?> o : raw.entrySet()) {
                options.put(String.valueOf(o.getKey()), o.getValue());
            }
            Object source = options.remove("source");
            if (source == null || String.valueOf(source).isBlank()) {
                problems.add("plugins." + name + ": missing 'source' (" + String.join(", ", new TreeSet<>(SOURCES)) + ")");
                continue;
            }
            String sourceId = String.valueOf(source).trim().toLowerCase(Locale.ROOT);
            if (!SOURCES.contains(sourceId)) {
                problems.add("plugins." + name + ": unknown source '" + source + "'");
                continue;
            }
            out.put(name, new PluginEntry(name, sourceId, options));
        }
        return out;
    }

    private Set<UpdaterSettings.Event> events(String path, Set<UpdaterSettings.Event> def) {
        Object raw = get(path);
        if (raw == null) {
            return def;
        }
        Set<UpdaterSettings.Event> out = EnumSet.noneOf(UpdaterSettings.Event.class);
        for (String s : stringList(path, List.of())) {
            try {
                out.add(UpdaterSettings.Event.valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                problems.add(path + ": unknown event '" + s + "' (updated, available, failed, security, summary)");
            }
        }
        return out;
    }

    // --- primitives -----------------------------------------------------------------------

    /** YAML 1.1 reads an unquoted "off" as false; treat false/no like "off". */
    private static String offAware(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.equals("false") || v.equals("no") ? "off" : v;
    }

    /** Reads a dotted path through nested maps. */
    private Object get(String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> m)) {
                return null;
            }
            current = m.get(part);
        }
        return current;
    }

    private String string(String path, String def) {
        Object v = get(path);
        if (v == null) {
            return def;
        }
        if (v instanceof Map<?, ?> || v instanceof Collection<?>) {
            problems.add(path + ": expected a single value");
            return def;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }


    /** A secret: plain value or ${ENV_VAR}. */
    private String secret(String path) {
        String raw = string(path, null);
        return raw == null ? null : resolveEnv(raw, path);
    }

    private static final java.util.regex.Pattern ENV_REF = java.util.regex.Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** Replaces every ${NAME} with the environment variable NAME, e.g. "Bearer ${TOKEN}". */
    private String resolveEnv(String raw, String path) {
        java.util.regex.Matcher m = ENV_REF.matcher(raw.trim());
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = env.apply(m.group(1));
            if (value == null || value.isBlank()) {
                problems.add(path + ": environment variable " + m.group(1) + " is not set");
                return null;
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private boolean bool(String path, boolean def) {
        Object v = get(path);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("yes") || s.equals("on")) {
            return true;
        }
        if (s.equals("false") || s.equals("no") || s.equals("off")) {
            return false;
        }
        problems.add(path + ": '" + v + "' is not true or false");
        return def;
    }

    private int integer(String path, int def, int min, int max) {
        Object v = get(path);
        if (v == null) {
            return def;
        }
        int value;
        if (v instanceof Number n) {
            value = n.intValue();
        } else {
            try {
                value = Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                problems.add(path + ": '" + v + "' is not a number");
                return def;
            }
        }
        if (value < min || value > max) {
            problems.add(path + ": " + value + " is outside " + min + ".." + max);
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }

    private List<String> stringList(String path, List<String> def) {
        Object v = get(path);
        if (v == null) {
            return def;
        }
        List<String> out = new ArrayList<>();
        if (v instanceof Collection<?> c) {
            for (Object o : c) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).trim());
                }
            }
        } else if (!String.valueOf(v).isBlank()) {
            out.add(String.valueOf(v).trim());
        }
        return out;
    }

    private Set<String> nameSet(String path) {
        return new LinkedHashSet<>(stringList(path, List.of()));
    }

    private static boolean validHttpsUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI u = URI.create(url);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
