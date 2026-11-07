package com.hanafubuki.updater.source;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hanafubuki.updater.config.PluginEntry;
import com.hanafubuki.updater.http.HttpService;
import com.hanafubuki.updater.util.Checksum;
import com.hanafubuki.updater.util.Json;
import com.hanafubuki.updater.util.VersionUtils;

/**
 * Modrinth (https://api.modrinth.com/v2).
 *
 * <p>The installed jar is identified by its SHA-512 hash ({@code GET /version_file/{hash}}),
 * so there is never a search by name. {@code project_id} (id or slug) can be set to pin the
 * project explicitly. Versions are listed with {@code GET /project/{id}/version} filtered by
 * loaders and game versions, then filtered by version type (release by default).
 *
 * <p>Whether the candidate is newer is decided from Modrinth data, not from version strings:
 * if the installed file is a known Modrinth version, the newest version is newer only if it
 * was published later. Version numbers like "v5.5.71-bukkit" therefore need no parsing.
 */
public final class ModrinthSource implements UpdateSource {

    public static final String DEFAULT_BASE_URL = "https://api.modrinth.com/v2";
    private static final Pattern PROJECT_ID = Pattern.compile("^[A-Za-z0-9_.-]{2,64}$");
    private static final List<String> VERSION_TYPES = List.of("release", "beta", "alpha");

    /**
     * Global Modrinth filters from config.yml.
     *
     * @param loaders      loader filter, default ["paper"]
     * @param gameVersions game version filter; empty means "the server's version"
     * @param versionTypes allowed version types, default ["release"]
     */
    public record Settings(SourceSettings source, List<String> loaders, List<String> gameVersions, List<String> versionTypes) {
        public static final Settings DEFAULT = new Settings(SourceSettings.DEFAULT, List.of("paper"), List.of(), List.of("release"));
    }

    private final HttpService http;
    private final Settings settings;
    private final String baseUrl;

    public ModrinthSource(HttpService http, Settings settings) {
        this(http, settings, DEFAULT_BASE_URL);
    }

    /** For tests: point at a fake API. */
    public ModrinthSource(HttpService http, Settings settings, String baseUrl) {
        this.http = http.withRetry(settings.source().retry());
        this.settings = settings;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public String id() {
        return "modrinth";
    }

    @Override
    public List<String> validate(PluginEntry entry) {
        List<String> problems = new ArrayList<>();
        String projectId = entry.string("project_id");
        if (projectId != null && !PROJECT_ID.matcher(projectId).matches()) {
            problems.add("project_id '" + projectId + "' is not a valid Modrinth id or slug");
        }
        List<String> types = entry.list("version_types");
        if (types != null) {
            for (String t : types) {
                if (!VERSION_TYPES.contains(t.toLowerCase(Locale.ROOT))) {
                    problems.add("version_types contains '" + t + "' (allowed: release, beta, alpha)");
                }
            }
        }
        return problems;
    }

    /** The Modrinth version the installed file belongs to. */
    public record KnownVersion(String id, String projectId, Instant published) {
    }

    @Override
    public Optional<UpdateCandidate> findLatest(UpdateRequest request) throws IOException {
        String installedSha512 = Checksum.hash(request.installed().jar(), Checksum.Algorithm.SHA512);
        Optional<KnownVersion> known = identify(installedSha512);

        String projectId = request.entry().string("project_id");
        if (projectId == null) {
            projectId = known.map(KnownVersion::projectId).orElseThrow(() -> new IOException(
                    "The installed jar is not a file published on Modrinth (downloaded from another site?). "
                            + "Set project_id to the slug from the plugin's modrinth.com/plugin/<slug> page"));
        }

        List<String> loaders = orDefault(request.entry().list("loaders"), settings.loaders());
        List<String> gameVersions = orDefault(request.entry().list("game_versions"),
                settings.gameVersions().isEmpty() ? List.of(request.minecraftVersion()) : settings.gameVersions());
        List<String> versionTypes = orDefault(request.entry().list("version_types"), settings.versionTypes());

        URI uri = URI.create(baseUrl + "/project/" + encode(projectId) + "/version?loaders=" + encode(jsonArray(loaders))
                + "&game_versions=" + encode(jsonArray(gameVersions)));
        JsonArray versions = Json.parseArray(http.getOk(uri, Map.of("Accept", "application/json"), settings.source().apiTimeout()),
                "Modrinth versions of " + projectId);

        List<JsonObject> usable = new ArrayList<>();
        java.util.Set<String> skippedTypes = new java.util.TreeSet<>();
        for (JsonElement e : versions) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject v = e.getAsJsonObject();
            String type = Json.str(v, "version_type");
            if (type == null || versionTypes.stream().noneMatch(type::equalsIgnoreCase)) {
                if (type != null) {
                    skippedTypes.add(type.toLowerCase(Locale.ROOT));
                }
                continue;
            }
            if (pickJar(v) == null) {
                continue;
            }
            usable.add(v);
        }
        if (usable.isEmpty()) {
            if (!skippedTypes.isEmpty()) {
                // Geyser, for example, publishes only beta versions on Modrinth.
                throw new NoVersionException("Modrinth has only " + String.join("/", skippedTypes) + " versions of " + projectId
                        + " for " + String.join(", ", loaders) + " " + String.join(", ", gameVersions)
                        + "; add version_types: [release, " + String.join(", ", skippedTypes) + "] to this plugin to use them");
            }
            return Optional.empty();
        }
        usable.sort(Comparator.comparing((JsonObject v) -> published(v)).reversed());
        JsonObject latest = preferLoader(usable, loaders);
        JsonObject file = pickJar(latest);

        String sha512 = Json.str(Json.obj(file, "hashes"), "sha512");
        Checksum checksum = sha512 == null ? null : Checksum.parse("sha512:" + sha512).orElse(null);
        if (checksum == null) {
            throw new IOException("Modrinth did not publish a SHA-512 hash for " + Json.str(file, "filename"));
        }

        UpdateCandidate.Freshness freshness = UpdateCandidate.Freshness.UNKNOWN;
        if (checksum.hex().equalsIgnoreCase(installedSha512)) {
            freshness = UpdateCandidate.Freshness.SAME;
        } else if (known.isPresent() && sameProject(known.get(), latest, projectId)) {
            freshness = published(latest).isAfter(known.get().published())
                    ? UpdateCandidate.Freshness.NEWER : UpdateCandidate.Freshness.SAME;
        }

        String versionNumber = Json.str(latest, "version_number");
        String pageUrl = "https://modrinth.com/project/" + Json.str(latest, "project_id") + "/version/" + Json.str(latest, "id");
        return Optional.of(new UpdateCandidate(versionNumber, URI.create(Json.str(file, "url")), checksum, Map.of(),
                freshness, pageUrl, List.of()));
    }

    /**
     * Looks up which Modrinth version a file with this SHA-512 belongs to.
     *
     * @return empty if the file is not on Modrinth
     */
    public Optional<KnownVersion> identify(String sha512) throws IOException {
        URI uri = URI.create(baseUrl + "/version_file/" + sha512 + "?algorithm=sha512");
        HttpService.Response r = http.get(uri, Map.of("Accept", "application/json"), settings.source().apiTimeout());
        if (r.status() == 404) {
            return Optional.empty();
        }
        if (r.status() != 200) {
            throw new IOException("Modrinth hash lookup failed: HTTP " + r.status());
        }
        JsonObject v = Json.parseObject(r.body(), "Modrinth version_file");
        String id = Json.str(v, "id");
        String projectId = Json.str(v, "project_id");
        if (id == null || projectId == null) {
            throw new IOException("Modrinth version_file answer has no id/project_id");
        }
        return Optional.of(new KnownVersion(id, projectId, published(v)));
    }

    /** Public view for auto-discovery: the Modrinth project id of a jar, if it is on Modrinth. */
    public Optional<String> projectIdOf(java.nio.file.Path jar) throws IOException {
        return identify(Checksum.hash(jar, Checksum.Algorithm.SHA512)).map(KnownVersion::projectId);
    }

    private static boolean sameProject(KnownVersion known, JsonObject latest, String configuredProject) {
        String latestProject = Json.str(latest, "project_id");
        return known.projectId().equals(latestProject) || known.projectId().equals(configuredProject);
    }

    /**
     * Picks the newest version, but when the same version is published as separate builds per loader
     * (BlueMap: "5.16-paper" and "5.16-spigot", seconds apart), takes the build for the loader that
     * comes first in the configured list, so a Paper server gets the Paper build.
     *
     * @param newestFirst usable versions, newest first
     */
    static JsonObject preferLoader(List<JsonObject> newestFirst, List<String> loaders) {
        JsonObject newest = newestFirst.get(0);
        String newestNumber = Json.str(newest, "version_number");
        for (String loader : loaders) {
            for (JsonObject v : newestFirst) {
                if (Json.containsIgnoreCase(Json.arr(v, "loaders"), loader)
                        && VersionUtils.compare(Json.str(v, "version_number"), newestNumber) == 0) {
                    return v;
                }
            }
        }
        return newest;
    }

    /** The primary .jar file of a version, or the only .jar file. Null if there is none. */
    static JsonObject pickJar(JsonObject version) {
        JsonObject onlyJar = null;
        int jars = 0;
        for (JsonElement fe : Json.arr(version, "files")) {
            if (!fe.isJsonObject()) {
                continue;
            }
            JsonObject f = fe.getAsJsonObject();
            String name = Json.str(f, "filename");
            if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".jar") || Json.str(f, "url") == null) {
                continue;
            }
            if (Json.bool(f, "primary", false)) {
                return f;
            }
            onlyJar = f;
            jars++;
        }
        return jars == 1 ? onlyJar : null;
    }

    private static Instant published(JsonObject v) {
        String s = Json.str(v, "date_published");
        if (s == null) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    private static List<String> orDefault(List<String> value, List<String> def) {
        return value == null || value.isEmpty() ? def : value;
    }

    private static String jsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
