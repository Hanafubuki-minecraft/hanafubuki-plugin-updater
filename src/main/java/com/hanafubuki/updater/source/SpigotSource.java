package com.hanafubuki.updater.source;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hanafubuki.updater.config.PluginEntry;
import com.hanafubuki.updater.http.HttpService;
import com.hanafubuki.updater.util.Json;

/**
 * SpigotMC resources through the Spiget API (https://api.spiget.org/v2).
 *
 * <ul>
 *   <li>{@code resource_id} is the safe way to point at a resource.</li>
 *   <li>{@code resource_name} is resolved by search, but only an exact, unique name match is accepted.
 *       The first search result is never used blindly.</li>
 *   <li>Premium resources cannot be downloaded and are reported as errors.</li>
 *   <li>"External" resources are only followed when the external link is an https .jar file;
 *       links to web pages are refused.</li>
 *   <li>Spiget publishes no checksums; only the plugin name inside the jar is verified.</li>
 * </ul>
 */
public final class SpigotSource implements UpdateSource {

    public static final String DEFAULT_BASE_URL = "https://api.spiget.org/v2";
    private static final int SEARCH_PAGE_SIZE = 100;
    /** Upper bound for name searches (2000 results); broader names must use resource_id. */
    private static final int SEARCH_MAX_PAGES = 20;

    private final HttpService http;
    private final SourceSettings settings;
    private final String baseUrl;

    public SpigotSource(HttpService http, SourceSettings settings) {
        this(http, settings, DEFAULT_BASE_URL);
    }

    public SpigotSource(HttpService http, SourceSettings settings, String baseUrl) {
        this.http = http.withRetry(settings.retry());
        this.settings = settings;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public String id() {
        return "spigotmc";
    }

    @Override
    public List<String> validate(PluginEntry entry) {
        List<String> problems = new ArrayList<>();
        String id = entry.string("resource_id");
        String name = entry.string("resource_name");
        if (id == null && name == null) {
            problems.add("needs 'resource_id' (number from the spigotmc.org URL) or 'resource_name'");
        }
        if (id != null && parseId(id) <= 0) {
            problems.add("resource_id '" + id + "' must be a positive number");
        }
        return problems;
    }

    @Override
    public Optional<UpdateCandidate> findLatest(UpdateRequest request) throws IOException {
        int id = resolveId(request.entry());
        JsonObject resource = Json.parseObject(getOk("/resources/" + id), "Spiget resource " + id);
        if (Json.bool(resource, "premium", false)) {
            throw new IOException("SpigotMC resource " + id + " is premium and cannot be downloaded automatically");
        }

        URI download;
        JsonObject file = Json.obj(resource, "file");
        String type = Json.str(file, "type");
        List<String> notes = new ArrayList<>(List.of("SpigotMC publishes no checksums; only the plugin name is verified"));
        if (".jar".equalsIgnoreCase(type)) {
            download = URI.create(baseUrl + "/resources/" + id + "/download");
        } else if ("external".equalsIgnoreCase(type)) {
            String external = Json.str(file, "externalUrl");
            download = externalJar(external);
            if (download == null) {
                throw new IOException("SpigotMC resource " + id + " links to an external page (" + external
                        + "), not a jar file. Use the github or modrinth source for this plugin");
            }
            notes.add("Downloaded from the external link " + download.getHost());
        } else {
            throw new IOException("SpigotMC resource " + id + " has an unsupported file type: " + type);
        }

        JsonObject latest = Json.parseObject(getOk("/resources/" + id + "/versions/latest"), "Spiget latest version " + id);
        String version = Json.str(latest, "name");
        if (version == null) {
            return Optional.empty();
        }
        return Optional.of(new UpdateCandidate(version, download, null, Map.of(), UpdateCandidate.Freshness.UNKNOWN,
                "https://www.spigotmc.org/resources/" + id + "/", notes));
    }

    private int resolveId(PluginEntry entry) throws IOException {
        String idText = entry.string("resource_id");
        if (idText != null) {
            int id = parseId(idText);
            if (id <= 0) {
                throw new IOException("resource_id must be a positive number");
            }
            return id;
        }
        String name = entry.string("resource_name");
        if (name == null) {
            throw new IOException("resource_id or resource_name is required");
        }
        // Spiget pages search results; exact matches can be on any page, so all pages are read.
        List<JsonObject> exact = new ArrayList<>();
        for (int page = 1; ; page++) {
            if (page > SEARCH_MAX_PAGES) {
                throw new IOException("The SpigotMC search for '" + name + "' has too many results. Set resource_id instead");
            }
            String body = getOk("/search/resources/" + encode(name) + "?field=name&size=" + SEARCH_PAGE_SIZE
                    + "&page=" + page + "&fields=id,name");
            JsonArray results = Json.parseArray(body, "Spiget search");
            for (JsonElement e : results) {
                if (e.isJsonObject() && name.equalsIgnoreCase(Json.str(e.getAsJsonObject(), "name"))) {
                    exact.add(e.getAsJsonObject());
                }
            }
            if (results.size() < SEARCH_PAGE_SIZE) {
                break;
            }
        }
        if (exact.size() == 1) {
            return (int) Json.num(exact.get(0), "id", -1);
        }
        if (exact.isEmpty()) {
            throw new IOException("No SpigotMC resource is named exactly '" + name + "'. Set resource_id instead");
        }
        List<Long> ids = exact.stream().map(o -> Json.num(o, "id", -1)).toList();
        throw new IOException("Several SpigotMC resources are named '" + name + "' (ids " + ids + "). Set resource_id");
    }

    private String getOk(String path) throws IOException {
        HttpService.Response r = http.get(URI.create(baseUrl + path), Map.of("Accept", "application/json"), settings.apiTimeout());
        if (r.status() == 404) {
            throw new IOException("Spiget: not found (" + path + ")");
        }
        if (r.status() != 200) {
            throw new IOException("Spiget answered HTTP " + r.status() + " for " + path);
        }
        return r.body();
    }

    private static URI externalJar(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null
                    && uri.getPath().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // Not a usable URL.
        }
        return null;
    }

    private static int parseId(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
