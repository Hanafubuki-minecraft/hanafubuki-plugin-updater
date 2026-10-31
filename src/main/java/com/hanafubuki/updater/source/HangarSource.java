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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hanafubuki.updater.config.PluginEntry;
import com.hanafubuki.updater.http.HttpService;
import com.hanafubuki.updater.util.Checksum;
import com.hanafubuki.updater.util.Json;
import com.hanafubuki.updater.util.VersionUtils;

/**
 * Hangar, PaperMC's plugin repository (https://hangar.papermc.io/api/v1).
 *
 * <p>Lists {@code GET /projects/{slug}/versions?platform=PAPER&channel=...}. Only the
 * "Release" channel is used unless {@code channel} says otherwise ("*" = any channel).
 * A version must list the server's Minecraft version (or a range containing it) under
 * PAPER platform dependencies. Hangar publishes SHA-256 hashes, which are verified.
 */
public final class HangarSource implements UpdateSource {

    public static final String DEFAULT_BASE_URL = "https://hangar.papermc.io/api/v1";
    private static final Pattern SLUG = Pattern.compile("^([A-Za-z0-9_.-]+/)?[A-Za-z0-9_.-]+$");

    /**
     * @param channel default channel, "Release" unless configured
     */
    public record Settings(SourceSettings source, String channel) {
        public static final Settings DEFAULT = new Settings(SourceSettings.DEFAULT, "Release");
    }

    private final HttpService http;
    private final Settings settings;
    private final String baseUrl;

    public HangarSource(HttpService http, Settings settings) {
        this(http, settings, DEFAULT_BASE_URL);
    }

    public HangarSource(HttpService http, Settings settings, String baseUrl) {
        this.http = http.withRetry(settings.source().retry());
        this.settings = settings;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public String id() {
        return "hangar";
    }

    @Override
    public List<String> validate(PluginEntry entry) {
        List<String> problems = new ArrayList<>();
        String slug = entry.string("project_slug");
        if (slug == null) {
            problems.add("missing 'project_slug' (Hangar project slug, e.g. ViaVersion)");
        } else if (!SLUG.matcher(slug).matches()) {
            problems.add("project_slug '" + slug + "' is not a valid Hangar slug");
        }
        return problems;
    }

    @Override
    public Optional<UpdateCandidate> findLatest(UpdateRequest request) throws IOException {
        String configured = request.entry().string("project_slug");
        if (configured == null) {
            throw new IOException("Hangar project_slug is not set");
        }
        // Hangar accepts the plain slug; "Owner/Slug" from older configs is reduced to the slug.
        String slug = configured.contains("/") ? configured.substring(configured.indexOf('/') + 1) : configured;
        String channel = request.entry().string("channel") != null ? request.entry().string("channel") : settings.channel();
        boolean anyChannel = channel == null || channel.equals("*");

        StringBuilder url = new StringBuilder(baseUrl).append("/projects/").append(encode(slug))
                .append("/versions?limit=25&offset=0&platform=PAPER");
        if (!anyChannel) {
            url.append("&channel=").append(encode(channel));
        }
        HttpService.Response r = http.get(URI.create(url.toString()), Map.of("Accept", "application/json"),
                settings.source().apiTimeout());
        if (r.status() == 404) {
            throw new IOException("Hangar project '" + slug + "' does not exist");
        }
        if (r.status() != 200) {
            throw new IOException("Hangar answered HTTP " + r.status() + " for " + slug);
        }
        JsonObject page = Json.parseObject(r.body(), "Hangar versions of " + slug);

        List<JsonObject> usable = new ArrayList<>();
        for (JsonElement e : Json.arr(page, "result")) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject v = e.getAsJsonObject();
            if (!anyChannel && !channel.equalsIgnoreCase(Json.str(Json.obj(v, "channel"), "name"))) {
                continue;
            }
            if (!supports(v, request.minecraftVersion()) || downloadUrl(v) == null) {
                continue;
            }
            usable.add(v);
        }
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        usable.sort(Comparator.comparing(HangarSource::created).reversed());
        JsonObject latest = usable.get(0);

        JsonObject paper = Json.obj(Json.obj(latest, "downloads"), "PAPER");
        String sha256 = Json.str(Json.obj(paper, "fileInfo"), "sha256Hash");
        Checksum checksum = sha256 == null ? null : Checksum.parse("sha256:" + sha256).orElse(null);
        List<String> notes = new ArrayList<>();
        if (checksum == null) {
            notes.add("Hangar publishes no checksum for this download");
        }

        UpdateCandidate.Freshness freshness = UpdateCandidate.Freshness.UNKNOWN;
        if (checksum != null && checksum.matches(request.installed().jar())) {
            freshness = UpdateCandidate.Freshness.SAME;
        }
        String name = Json.str(latest, "name");
        return Optional.of(new UpdateCandidate(name, URI.create(downloadUrl(latest)), checksum, Map.of(), freshness,
                "https://hangar.papermc.io/" + slug + "/versions/" + name, notes));
    }

    /** Direct download URL, or an external URL that points at a .jar. Null otherwise. */
    private static String downloadUrl(JsonObject version) {
        JsonObject paper = Json.obj(Json.obj(version, "downloads"), "PAPER");
        if (paper == null) {
            return null;
        }
        String direct = Json.str(paper, "downloadUrl");
        if (direct != null) {
            return direct;
        }
        String external = Json.str(paper, "externalUrl");
        if (external == null) {
            return null;
        }
        try {
            URI uri = URI.create(external);
            boolean jar = uri.getPath() != null && uri.getPath().toLowerCase(Locale.ROOT).endsWith(".jar");
            return jar && "https".equalsIgnoreCase(uri.getScheme()) ? external : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean supports(JsonObject version, String mcVersion) {
        JsonObject deps = Json.obj(version, "platformDependencies");
        for (JsonElement e : Json.arr(deps, "PAPER")) {
            if (e.isJsonPrimitive() && versionMatches(e.getAsString(), mcVersion)) {
                return true;
            }
        }
        return false;
    }

    /** Matches "1.21.9", ranges like "1.21-1.21.10" and wildcards like "1.21.x". */
    static boolean versionMatches(String declared, String mcVersion) {
        String d = declared.trim();
        if (d.equals(mcVersion)) {
            return true;
        }
        if (d.endsWith(".x")) {
            String prefix = d.substring(0, d.length() - 1);
            return mcVersion.startsWith(prefix) || mcVersion.equals(d.substring(0, d.length() - 2));
        }
        int dash = d.indexOf('-');
        if (dash > 0) {
            return VersionUtils.inRange(mcVersion, d.substring(0, dash).trim(), d.substring(dash + 1).trim());
        }
        return false;
    }

    private static Instant created(JsonObject v) {
        String s = Json.str(v, "createdAt");
        try {
            return s == null ? Instant.EPOCH : Instant.parse(s);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
