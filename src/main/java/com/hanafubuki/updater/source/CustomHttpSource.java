package com.hanafubuki.updater.source;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hanafubuki.updater.config.PluginEntry;
import com.hanafubuki.updater.http.HttpService;
import com.hanafubuki.updater.util.Checksum;
import com.hanafubuki.updater.util.Json;
import com.hanafubuki.updater.util.JsonPath;

/**
 * Any JSON REST endpoint that describes the latest version.
 *
 * <p>One GET to {@code url}; version, download URL and checksum are read with
 * {@code version_path}, {@code download_url_path} and {@code checksum_path} (see {@link JsonPath}),
 * or from common field names. Security rules:
 * <ul>
 *   <li>https only, unless {@code allow_http: true}.</li>
 *   <li>{@code headers} and {@code auth} are sent to the API, and to the download only when it is
 *       on the same host. Redirects to other hosts drop them as well.</li>
 *   <li>Values written as {@code ${NAME}} are read from the environment variable NAME, so secrets
 *       do not have to be stored in config.yml.</li>
 * </ul>
 */
public final class CustomHttpSource implements UpdateSource {

    private static final Pattern ENV_REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final List<String> VERSION_FIELDS = List.of("version", "latest_version", "version_number", "tag_name", "name");
    private static final List<String> URL_FIELDS = List.of("download_url", "url", "browser_download_url", "file_url", "asset_url");
    private static final List<String> CHECKSUM_FIELDS = List.of("sha512", "sha256", "checksum", "hash", "digest");

    private final HttpService http;
    private final SourceSettings settings;
    private final Function<String, String> env;

    public CustomHttpSource(HttpService http, SourceSettings settings) {
        this(http, settings, System::getenv);
    }

    /** For tests: custom environment lookup. */
    public CustomHttpSource(HttpService http, SourceSettings settings, Function<String, String> env) {
        this.http = http.withRetry(settings.retry());
        this.settings = settings;
        this.env = env;
    }

    @Override
    public String id() {
        return "custom";
    }

    @Override
    public List<String> validate(PluginEntry entry) {
        List<String> problems = new ArrayList<>();
        String url = entry.string("url");
        if (url == null) {
            problems.add("missing 'url' (JSON endpoint describing the latest version)");
        } else {
            try {
                checkScheme(URI.create(url), entry);
            } catch (IllegalArgumentException | IOException e) {
                problems.add(e.getMessage());
            }
        }
        for (String key : List.of("version_path", "download_url_path", "checksum_path")) {
            String path = entry.string(key);
            if (path != null) {
                try {
                    JsonPath.compile(path);
                } catch (IllegalArgumentException e) {
                    problems.add(key + ": " + e.getMessage());
                }
            }
        }
        if (entry.options().get("auth") instanceof Map<?, ?> auth) {
            Object type = auth.get("type");
            if (type == null || !List.of("bearer", "basic", "api_key").contains(String.valueOf(type).toLowerCase(Locale.ROOT))) {
                problems.add("auth.type must be bearer, basic or api_key");
            }
        }
        String checksum = entry.string("checksum");
        if (checksum != null && Checksum.parse(checksum).isEmpty()) {
            problems.add("checksum is not a SHA-1, SHA-256 or SHA-512 hex digest");
        }
        return problems;
    }

    @Override
    public Optional<UpdateCandidate> findLatest(UpdateRequest request) throws IOException {
        PluginEntry entry = request.entry();
        URI api = URI.create(entry.string("url"));
        checkScheme(api, entry);
        Map<String, String> headers = buildHeaders(entry);
        Map<String, String> apiHeaders = new LinkedHashMap<>(headers);
        apiHeaders.putIfAbsent("Accept", "application/json");

        JsonElement root = Json.parse(http.getOk(api, apiHeaders, settings.apiTimeout()), "custom source " + api.getHost());

        String version = read(root, entry.string("version_path"), VERSION_FIELDS);
        if (version == null) {
            return Optional.empty();
        }
        String rawUrl = read(root, entry.string("download_url_path"), URL_FIELDS);
        if (rawUrl == null) {
            throw new IOException("The custom source gave no download URL for version " + version);
        }
        URI download = api.resolve(rawUrl.trim());
        checkScheme(download, entry);

        Checksum checksum = null;
        if (entry.string("checksum") != null) {
            checksum = Checksum.parse(entry.string("checksum"))
                    .orElseThrow(() -> new IOException("checksum is not a SHA-1, SHA-256 or SHA-512 hex digest"));
        } else {
            String raw = read(root, entry.string("checksum_path"), CHECKSUM_FIELDS);
            if (raw != null) {
                checksum = Checksum.parse(raw).orElseThrow(() -> new IOException("Checksum '" + raw + "' is not a valid digest"));
            } else if (root.isJsonObject() && Json.obj(root.getAsJsonObject(), "hashes") != null) {
                JsonObject hashes = Json.obj(root.getAsJsonObject(), "hashes");
                for (String alg : List.of("sha512", "sha256", "sha1")) {
                    if (Json.str(hashes, alg) != null) {
                        checksum = Checksum.parse(alg + ":" + Json.str(hashes, alg)).orElse(null);
                        break;
                    }
                }
            }
        }
        List<String> notes = checksum == null ? List.of("The custom source publishes no checksum") : List.of();

        Map<String, String> downloadHeaders = HttpService.sameOrigin(api, download) ? headers : Map.of();
        return Optional.of(new UpdateCandidate(version, download, checksum, downloadHeaders,
                UpdateCandidate.Freshness.UNKNOWN, null, notes));
    }

    private static String read(JsonElement root, String path, List<String> fallbackFields) throws IOException {
        if (path != null) {
            try {
                return JsonPath.compile(path).readString(root).orElse(null);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid JSON path '" + path + "': " + e.getMessage());
            }
        }
        if (root.isJsonObject()) {
            for (String field : fallbackFields) {
                String v = Json.str(root.getAsJsonObject(), field);
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
        }
        return null;
    }

    private static void checkScheme(URI uri, PluginEntry entry) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https")) {
            return;
        }
        if (scheme.equals("http") && entry.bool("allow_http", false)) {
            return;
        }
        throw new IOException("Custom source URL must use https (set allow_http: true to allow http): " + HttpService.describe(uri));
    }

    Map<String, String> buildHeaders(PluginEntry entry) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> h : entry.map("headers").entrySet()) {
            headers.put(h.getKey(), resolve(h.getValue()));
        }
        if (entry.options().get("auth") instanceof Map<?, ?> auth) {
            String type = String.valueOf(auth.get("type")).toLowerCase(Locale.ROOT);
            switch (type) {
                case "bearer" -> headers.put("Authorization", "Bearer " + resolve(required(auth, "token")));
                case "basic" -> {
                    String credentials = resolve(required(auth, "username")) + ":" + resolve(required(auth, "password"));
                    headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
                }
                case "api_key" -> {
                    Object header = auth.get("header");
                    headers.put(header == null ? "X-API-Key" : String.valueOf(header), resolve(required(auth, "key")));
                }
                default -> throw new IOException("auth.type must be bearer, basic or api_key");
            }
        }
        return headers;
    }

    private static String required(Map<?, ?> auth, String key) throws IOException {
        Object v = auth.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IOException("auth." + key + " is required");
        }
        return String.valueOf(v);
    }

    /** Replaces every "${NAME}" in the value with the environment variable NAME, e.g. "Bearer ${TOKEN}". */
    String resolve(String value) throws IOException {
        Matcher m = ENV_REF.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String resolved = env.apply(m.group(1));
            if (resolved == null || resolved.isEmpty()) {
                throw new IOException("Environment variable " + m.group(1) + " is not set");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(resolved));
        }
        m.appendTail(out);
        return out.toString();
    }
}
