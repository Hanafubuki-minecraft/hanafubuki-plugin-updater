package com.hanafubuki.updater.source;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hanafubuki.updater.http.HttpService;
import com.hanafubuki.updater.util.Json;
import com.hanafubuki.updater.util.VersionUtils;

/**
 * Security alerts from published GitHub repository security advisories
 * ({@code GET /repos/{owner}/{repo}/security-advisories?state=published}).
 *
 * <p>An advisory applies when the installed version matches one of its
 * {@code vulnerable_version_range} expressions. Works without a token for public repositories.
 */
public final class GitHubAdvisories {

    /**
     * One advisory that affects the installed version.
     *
     * @param id       GHSA id
     * @param cve      CVE id, may be null
     * @param severity low, medium, high or critical
     * @param summary  short description
     * @param url      advisory page
     * @param patched  patched versions as written in the advisory, may be null
     */
    public record Alert(String id, String cve, String severity, String summary, String url, String patched) {
    }

    private final HttpService http;
    private final String baseUrl;
    private final Map<String, String> headers;
    private final Duration timeout;

    public GitHubAdvisories(HttpService http, String baseUrl, Map<String, String> headers, Duration timeout) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.headers = Map.copyOf(headers);
        this.timeout = timeout;
    }

    /**
     * Returns advisories of {@code repo} that affect {@code installedVersion}.
     * Advisories without parsable ranges are skipped.
     */
    public List<Alert> check(String repo, String installedVersion) throws IOException {
        URI uri = URI.create(baseUrl + "/repos/" + repo + "/security-advisories?state=published&per_page=100");
        HttpService.Response r = http.get(uri, headers, timeout);
        if (r.status() == 404) {
            return List.of();
        }
        if (r.status() != 200) {
            throw new IOException("GitHub advisories for " + repo + " answered HTTP " + r.status());
        }
        List<Alert> alerts = new ArrayList<>();
        for (JsonElement e : Json.parseArray(r.body(), "GitHub advisories of " + repo)) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject adv = e.getAsJsonObject();
            if (Json.str(adv, "withdrawn_at") != null) {
                continue;
            }
            for (JsonElement ve : Json.arr(adv, "vulnerabilities")) {
                if (!ve.isJsonObject()) {
                    continue;
                }
                String range = Json.str(ve.getAsJsonObject(), "vulnerable_version_range");
                if (range == null || range.isBlank()) {
                    continue;
                }
                boolean affected;
                try {
                    affected = VersionUtils.matchesRange(installedVersion, range);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                if (affected) {
                    String severity = Json.str(adv, "severity");
                    alerts.add(new Alert(Json.str(adv, "ghsa_id"), Json.str(adv, "cve_id"),
                            severity == null ? "unknown" : severity.toLowerCase(Locale.ROOT),
                            Json.str(adv, "summary"), Json.str(adv, "html_url"),
                            Json.str(ve.getAsJsonObject(), "patched_versions")));
                    break;
                }
            }
        }
        return alerts;
    }
}
