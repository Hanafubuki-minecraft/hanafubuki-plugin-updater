package com.hanafubuki.updater.source;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hanafubuki.updater.config.PluginEntry;
import com.hanafubuki.updater.http.HttpService;
import com.hanafubuki.updater.util.Checksum;
import com.hanafubuki.updater.util.Glob;
import com.hanafubuki.updater.util.Json;

/**
 * GitHub releases (https://api.github.com).
 *
 * <p>Picks the latest release (or the latest pre-release when {@code include_prereleases} is set),
 * then one .jar asset: the one matching {@code asset_pattern}, or the only .jar asset that is not
 * a sources/javadoc jar. Several candidates without a pattern is an error, never a guess.
 *
 * <p>Checksums, in order of trust:
 * <ol>
 *   <li>the asset's {@code digest} computed by GitHub,</li>
 *   <li>a sidecar asset ({@code <asset>.sha256}, {@code .sha512}, {@code .sha1}) or a checksum list
 *       ({@code checksums.txt}, {@code SHA256SUMS}, ...),</li>
 *   <li>a hash written in the release notes next to the asset name.</li>
 * </ol>
 *
 * <p>Signature check ({@code signatures: warn|require}): the release tag, or the commit it points
 * to, must have a signature that GitHub reports as verified.
 */
public final class GitHubSource implements UpdateSource {

    public static final String DEFAULT_BASE_URL = "https://api.github.com";
    public static final Pattern REPO = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}$");
    private static final Pattern HEX_DIGEST = Pattern.compile("\\b([0-9a-fA-F]{128}|[0-9a-fA-F]{64})\\b");
    private static final List<String> CHECKSUM_LISTS = List.of("checksums.txt", "sha256sums", "sha256sums.txt",
            "sha512sums", "sha512sums.txt", "checksums.sha256", "checksums.sha512");

    /** How release signatures are treated. */
    public enum SignatureMode {
        /** Not checked. */
        OFF,
        /** Checked; an unsigned release is installed with a warning. */
        WARN,
        /** Checked; an unsigned release is refused. */
        REQUIRE;

        public static SignatureMode parse(String s) {
            if (s == null || s.isBlank()) {
                return OFF;
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "off", "false", "none" -> OFF;
                case "warn", "check" -> WARN;
                case "require", "required", "true" -> REQUIRE;
                default -> throw new IllegalArgumentException("signatures must be off, warn or require, got '" + s + "'");
            };
        }
    }

    /**
     * Global GitHub settings.
     *
     * @param token              personal access token, may be null
     * @param signatures         default signature mode
     * @param includePrereleases default for include_prereleases
     */
    public record Settings(SourceSettings source, String token, SignatureMode signatures, boolean includePrereleases) {
        public static final Settings DEFAULT = new Settings(SourceSettings.DEFAULT, null, SignatureMode.OFF, false);
    }

    private final HttpService http;
    private final Settings settings;
    private final String baseUrl;

    public GitHubSource(HttpService http, Settings settings) {
        this(http, settings, DEFAULT_BASE_URL);
    }

    public GitHubSource(HttpService http, Settings settings, String baseUrl) {
        this.http = http.withRetry(settings.source().retry());
        this.settings = settings;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public String id() {
        return "github";
    }

    @Override
    public List<String> validate(PluginEntry entry) {
        List<String> problems = new ArrayList<>();
        String repo = entry.string("repo");
        if (repo == null) {
            problems.add("missing 'repo' (format: owner/repo)");
        } else if (!REPO.matcher(repo).matches()) {
            problems.add("repo '" + repo + "' is not in owner/repo format");
        }
        String pattern = entry.string("asset_pattern");
        if (pattern != null && !pattern.toLowerCase(Locale.ROOT).endsWith(".jar") && !pattern.endsWith("*")) {
            problems.add("asset_pattern '" + pattern + "' does not select a .jar file");
        }
        try {
            SignatureMode.parse(entry.string("signatures"));
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
        return problems;
    }

    /** Headers for GitHub API calls. */
    Map<String, String> apiHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/vnd.github+json");
        h.put("X-GitHub-Api-Version", "2022-11-28");
        if (settings.token() != null && !settings.token().isBlank()) {
            h.put("Authorization", "Bearer " + settings.token());
        }
        return h;
    }

    @Override
    public Optional<UpdateCandidate> findLatest(UpdateRequest request) throws IOException {
        PluginEntry entry = request.entry();
        String repo = entry.string("repo");
        if (repo == null || !REPO.matcher(repo).matches()) {
            throw new IOException("GitHub repo is missing or invalid: " + repo);
        }
        boolean prereleases = entry.bool("include_prereleases", settings.includePrereleases());

        Optional<JsonObject> release = prereleases ? newestRelease(repo) : latestRelease(repo);
        if (release.isEmpty()) {
            explainMissingRelease(repo);
        }
        JsonObject rel = release.get();
        String tag = Json.str(rel, "tag_name");
        if (tag == null) {
            throw new IOException("GitHub release without tag_name in " + repo);
        }

        JsonObject asset = pickAsset(Json.arr(rel, "assets"), entry.string("asset_pattern"), repo, tag);
        String assetName = Json.str(asset, "name");
        List<String> notes = new ArrayList<>();

        Checksum checksum = findChecksum(rel, asset);
        if (checksum == null) {
            notes.add("GitHub release " + tag + " publishes no checksum for " + assetName);
        }

        SignatureMode mode = entry.string("signatures") != null
                ? SignatureMode.parse(entry.string("signatures")) : settings.signatures();
        if (mode != SignatureMode.OFF) {
            SignatureResult sig = verifySignature(repo, tag);
            if (!sig.verified()) {
                if (mode == SignatureMode.REQUIRE) {
                    throw new IOException("Release " + tag + " of " + repo + " is not signed with a verified key: " + sig.reason());
                }
                notes.add("Release " + tag + " is not signed with a verified key: " + sig.reason());
            }
        }

        URI downloadUrl;
        Map<String, String> downloadHeaders = new LinkedHashMap<>();
        if (settings.token() != null && !settings.token().isBlank() && Json.str(asset, "url") != null) {
            // The API asset URL also works for private repositories. The redirect to GitHub's
            // file host drops the Authorization header (see HttpService).
            downloadUrl = URI.create(Json.str(asset, "url"));
            downloadHeaders.put("Accept", "application/octet-stream");
            downloadHeaders.put("Authorization", "Bearer " + settings.token());
        } else {
            downloadUrl = URI.create(Json.str(asset, "browser_download_url"));
        }
        return Optional.of(new UpdateCandidate(tag, downloadUrl, checksum, downloadHeaders,
                UpdateCandidate.Freshness.UNKNOWN, Json.str(rel, "html_url"), notes));
    }

    private Optional<JsonObject> latestRelease(String repo) throws IOException {
        URI uri = URI.create(baseUrl + "/repos/" + repo + "/releases/latest");
        HttpService.Response r = http.get(uri, apiHeaders(), settings.source().apiTimeout());
        if (r.status() == 404) {
            return Optional.empty();
        }
        checkStatus(r, repo);
        return Optional.of(Json.parseObject(r.body(), "GitHub latest release of " + repo));
    }

    private Optional<JsonObject> newestRelease(String repo) throws IOException {
        URI uri = URI.create(baseUrl + "/repos/" + repo + "/releases?per_page=20");
        HttpService.Response r = http.get(uri, apiHeaders(), settings.source().apiTimeout());
        if (r.status() == 404) {
            return Optional.empty();
        }
        checkStatus(r, repo);
        for (JsonElement e : Json.parseArray(r.body(), "GitHub releases of " + repo)) {
            if (e.isJsonObject() && !Json.bool(e.getAsJsonObject(), "draft", false)) {
                return Optional.of(e.getAsJsonObject());
            }
        }
        return Optional.empty();
    }

    /**
     * GitHub answers 404 both for "no release" and for "repository not found or private".
     * Tells the two apart so the admin knows what to fix.
     */
    private void explainMissingRelease(String repo) throws IOException {
        HttpService.Response r = http.get(URI.create(baseUrl + "/repos/" + repo), apiHeaders(), settings.source().apiTimeout());
        if (r.status() == 404) {
            boolean hasToken = settings.token() != null && !settings.token().isBlank();
            throw new IOException("GitHub repository " + repo + " was not found" + (hasToken
                    ? ". Check the name, and that github.token can read this repository"
                    : ". If it is private, set github.token to a token that can read it"));
        }
        checkStatus(r, repo);
        throw new IOException("GitHub repository " + repo + " has no published release"
                + (settings.includePrereleases() ? "" : " (pre-releases are ignored unless include_prereleases: true)")
                + ". Publish a release with the plugin jar attached");
    }

    private static void checkStatus(HttpService.Response r, String repo) throws IOException {
        if (r.status() == 200) {
            return;
        }
        if (r.status() == 401) {
            throw new IOException("GitHub rejected github.token (HTTP 401 Bad credentials) while checking " + repo
                    + ". The token is mistyped, expired or revoked; create a new one and run /updateplugins reload");
        }
        if ((r.status() == 403 || r.status() == 429) && r.header("X-RateLimit-Remaining").orElse("").equals("0")) {
            throw new IOException("GitHub API rate limit reached while checking " + repo
                    + ". Set github.token in config.yml to get a higher limit");
        }
        throw new IOException("GitHub API answered HTTP " + r.status() + " for " + repo);
    }

    /** Chooses the jar asset. Throws when the choice would be a guess. */
    static JsonObject pickAsset(JsonArray assets, String pattern, String repo, String tag) throws IOException {
        List<JsonObject> candidates = new ArrayList<>();
        Pattern glob = pattern == null ? null : Glob.toPattern(pattern);
        for (JsonElement e : assets) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject a = e.getAsJsonObject();
            String name = Json.str(a, "name");
            if (name == null || Json.str(a, "browser_download_url") == null) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (glob != null) {
                if (glob.matcher(name).matches()) {
                    candidates.add(a);
                }
            } else if (lower.endsWith(".jar") && !lower.endsWith("-sources.jar") && !lower.endsWith("-javadoc.jar")
                    // maven-shade-plugin leaves the unshaded jar as original-<name>.jar next to the real one.
                    && !lower.startsWith("original-")) {
                candidates.add(a);
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty()) {
            throw new IOException("Release " + tag + " of " + repo + " has no "
                    + (pattern == null ? ".jar asset" : "asset matching '" + pattern + "'"));
        }
        List<String> names = candidates.stream().map(a -> Json.str(a, "name")).toList();
        throw new IOException("Release " + tag + " of " + repo + " has several matching assets " + names
                + ". Set asset_pattern to choose one");
    }

    /** Finds a checksum for the asset; null if none is published. */
    Checksum findChecksum(JsonObject release, JsonObject asset) throws IOException {
        String assetName = Json.str(asset, "name");

        Optional<Checksum> digest = Checksum.parse(Json.str(asset, "digest"));
        if (digest.isPresent()) {
            return digest.get();
        }

        JsonArray assets = Json.arr(release, "assets");
        for (String ext : List.of(".sha512", ".sha256", ".sha1")) {
            JsonObject sidecar = findAsset(assets, assetName + ext);
            if (sidecar != null) {
                Optional<Checksum> c = Checksum.parse(fetchSmallAsset(sidecar));
                if (c.isPresent()) {
                    return c.get();
                }
            }
        }
        for (JsonElement e : assets) {
            if (!e.isJsonObject()) {
                continue;
            }
            String name = Json.str(e.getAsJsonObject(), "name");
            if (name != null && CHECKSUM_LISTS.contains(name.toLowerCase(Locale.ROOT))) {
                Checksum c = checksumFromList(fetchSmallAsset(e.getAsJsonObject()), assetName);
                if (c != null) {
                    return c;
                }
            }
        }
        return checksumFromReleaseNotes(Json.str(release, "body"), assetName);
    }

    private static JsonObject findAsset(JsonArray assets, String name) {
        for (JsonElement e : assets) {
            if (e.isJsonObject() && name.equalsIgnoreCase(Json.str(e.getAsJsonObject(), "name"))) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    private String fetchSmallAsset(JsonObject asset) throws IOException {
        if (Json.num(asset, "size", 0) > 64 * 1024) {
            return "";
        }
        String apiUrl = Json.str(asset, "url");
        if (settings.token() != null && !settings.token().isBlank() && apiUrl != null) {
            // Private repositories: browser URLs need a session, the API asset URL works with the token.
            Map<String, String> headers = new LinkedHashMap<>(apiHeaders());
            headers.put("Accept", "application/octet-stream");
            return http.getOk(URI.create(apiUrl), headers, settings.source().apiTimeout());
        }
        return http.getOk(URI.create(Json.str(asset, "browser_download_url")), Map.of(), settings.source().apiTimeout());
    }

    /** Reads "hash  filename" lines (sha256sum format, optional '*' before the name). */
    static Checksum checksumFromList(String text, String assetName) {
        for (String line : text.split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2) {
                String file = parts[parts.length - 1].replaceFirst("^\\*", "");
                if (file.equalsIgnoreCase(assetName)) {
                    Optional<Checksum> c = Checksum.parse(parts[0]);
                    if (c.isPresent()) {
                        return c.get();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Finds a hash in release notes. A line that mentions the asset name and contains a digest wins.
     * Otherwise a single digest in the whole text is used only if the line is clearly labelled
     * (SHA-256 / SHA-512 / checksum).
     */
    static Checksum checksumFromReleaseNotes(String body, String assetName) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String[] lines = body.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].toLowerCase(Locale.ROOT).contains(assetName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            // Same line, or the next line (tables and "file:\n hash" layouts).
            for (int j = i; j <= Math.min(i + 1, lines.length - 1); j++) {
                Matcher m = HEX_DIGEST.matcher(lines[j]);
                if (m.find()) {
                    return Checksum.parse(m.group(1)).orElse(null);
                }
            }
        }
        List<String> labelled = new ArrayList<>();
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("sha256") || lower.contains("sha-256") || lower.contains("sha512")
                    || lower.contains("sha-512") || lower.contains("checksum")) {
                Matcher m = HEX_DIGEST.matcher(line);
                while (m.find()) {
                    labelled.add(m.group(1));
                }
            }
        }
        if (labelled.size() == 1) {
            return Checksum.parse(labelled.get(0)).orElse(null);
        }
        return null;
    }

    /** Result of a signature check. */
    public record SignatureResult(boolean verified, String reason) {
    }

    /**
     * Checks whether the tag (annotated tags) or the commit it points to carries a signature
     * that GitHub verified.
     */
    public SignatureResult verifySignature(String repo, String tag) throws IOException {
        URI refUri = URI.create(baseUrl + "/repos/" + repo + "/git/ref/tags/" + encodePath(tag));
        JsonObject ref = Json.parseObject(http.getOk(refUri, apiHeaders(), settings.source().apiTimeout()), "GitHub tag ref");
        JsonObject object = Json.obj(ref, "object");
        String type = Json.str(object, "type");
        String sha = Json.str(object, "sha");
        if (sha == null) {
            return new SignatureResult(false, "tag ref has no object");
        }
        String tagReason = null;
        if ("tag".equals(type)) {
            JsonObject tagObj = Json.parseObject(http.getOk(URI.create(baseUrl + "/repos/" + repo + "/git/tags/" + sha),
                    apiHeaders(), settings.source().apiTimeout()), "GitHub tag object");
            JsonObject verification = Json.obj(tagObj, "verification");
            if (Json.bool(verification, "verified", false)) {
                return new SignatureResult(true, "tag signature verified");
            }
            tagReason = Json.str(verification, "reason");
            JsonObject target = Json.obj(tagObj, "object");
            sha = Json.str(target, "sha");
            if (sha == null || !"commit".equals(Json.str(target, "type"))) {
                return new SignatureResult(false, "tag: " + tagReason);
            }
        }
        JsonObject commit = Json.parseObject(http.getOk(URI.create(baseUrl + "/repos/" + repo + "/commits/" + sha),
                apiHeaders(), settings.source().apiTimeout()), "GitHub commit");
        JsonObject verification = Json.obj(Json.obj(commit, "commit"), "verification");
        if (Json.bool(verification, "verified", false)) {
            return new SignatureResult(true, "commit signature verified");
        }
        String commitReason = Json.str(verification, "reason");
        return new SignatureResult(false, (tagReason != null ? "tag: " + tagReason + ", " : "") + "commit: " + commitReason);
    }

    private static String encodePath(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
