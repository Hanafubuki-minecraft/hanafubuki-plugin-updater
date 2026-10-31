package com.hanafubuki.updater.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hanafubuki.updater.http.HttpService;
import com.hanafubuki.updater.source.GitHubAdvisories;
import com.hanafubuki.updater.source.GitHubSource;
import com.hanafubuki.updater.source.InstalledPlugin;
import com.hanafubuki.updater.source.SourceSettings;
import com.hanafubuki.updater.source.UpdateCandidate;

/**
 * Contract tests against the real GitHub REST API (https://api.github.com).
 *
 * <p>Without a token GitHub allows 60 requests per hour. Set the environment variable
 * {@code GITHUB_TOKEN} for a higher limit. When GitHub refuses requests because of the rate
 * limit (or a proxy blocks api.github.com), the tests are skipped instead of failing.
 */
@Tag("contract")
class GitHubContractTest {

    private static final String REPO = "ViaVersion/ViaVersion";
    private static final String ASSET_PATTERN = "ViaVersion-*.jar";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String TOKEN = blankToNull(System.getenv("GITHUB_TOKEN"));

    /** Shared, so the ETag cache turns repeated release lookups into free 304 answers. */
    private static final HttpService HTTP = ContractHttp.service();

    private final GitHubSource github = new GitHubSource(HTTP,
            new GitHubSource.Settings(SourceSettings.DEFAULT, TOKEN, GitHubSource.SignatureMode.OFF, false));

    @BeforeEach
    void skipWhenGitHubIsUnavailable() throws IOException {
        unavailableReason().ifPresent(reason -> Assumptions.assumeTrue(false, reason));
    }

    @Test
    void latestReleaseHasExactlyOneChosenJar(@TempDir Path dir) throws Exception {
        InstalledPlugin installed = ContractHttp.installed(dir, "ViaVersion", "ViaVersion.jar");

        UpdateCandidate candidate;
        try {
            candidate = github.findLatest(ContractHttp.request("github", installed, Map.of("repo", REPO)))
                    .orElseThrow(() -> new AssertionError(REPO + " has no latest release"));
        } catch (IOException e) {
            skipIfUnavailable(e);
            // Without asset_pattern the source must refuse to guess between several jars.
            assertTrue(e.getMessage().contains("several matching assets"), e.getMessage());
            candidate = null;
        }
        UpdateCandidate withPattern = call(() -> github.findLatest(ContractHttp.request("github", installed,
                Map.of("repo", REPO, "asset_pattern", ASSET_PATTERN))))
                .orElseThrow(() -> new AssertionError(REPO + " has no latest release"));
        if (candidate != null) {
            assertEquals(candidate.downloadUrl(), withPattern.downloadUrl(),
                    "asset_pattern chose another asset than the single-jar rule");
        }

        assertNotNull(withPattern.version());
        assertFalse(withPattern.version().isBlank(), "release without tag_name");
        assertNotNull(withPattern.pageUrl());
        assertTrue(withPattern.pageUrl().startsWith("https://github.com/" + REPO + "/releases/tag/"), withPattern.pageUrl());
        URI url = withPattern.downloadUrl();
        assertEquals("https", url.getScheme());
        if (TOKEN == null) {
            assertEquals("github.com", url.getHost());
            assertTrue(url.getPath().startsWith("/" + REPO + "/releases/download/"), url.toString());
            assertTrue(url.getPath().matches(".*/ViaVersion-[^/]*\\.jar"), url.toString());
        } else {
            assertEquals("api.github.com", url.getHost());
            assertTrue(url.getPath().startsWith("/repos/" + REPO + "/releases/assets/"), url.toString());
            assertEquals("application/octet-stream", withPattern.downloadHeaders().get("Accept"));
        }
    }

    @Test
    void signatureCheckOfLatestTagReturnsAResult(@TempDir Path dir) throws Exception {
        InstalledPlugin installed = ContractHttp.installed(dir, "ViaVersion", "ViaVersion.jar");
        String tag = call(() -> github.findLatest(ContractHttp.request("github", installed,
                Map.of("repo", REPO, "asset_pattern", ASSET_PATTERN))))
                .orElseThrow(() -> new AssertionError(REPO + " has no latest release")).version();

        GitHubSource.SignatureResult result = call(() -> github.verifySignature(REPO, tag));

        assertNotNull(result);
        assertNotNull(result.reason());
        assertFalse(result.reason().isBlank());
    }

    @Test
    void advisoriesListIsReadable() throws Exception {
        GitHubAdvisories advisories = new GitHubAdvisories(HTTP, GitHubSource.DEFAULT_BASE_URL, apiHeaders(), TIMEOUT);

        List<GitHubAdvisories.Alert> alerts = call(() -> advisories.check(REPO, "1.0.0"));

        assertNotNull(alerts);
        for (GitHubAdvisories.Alert alert : alerts) {
            assertNotNull(alert.id(), "advisory without ghsa_id");
            assertNotNull(alert.severity());
        }
    }

    // ------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface GitHubCall<T> {
        T run() throws IOException;
    }

    /** Runs a GitHub call; skips the test if GitHub refused it because of the rate limit or a proxy. */
    private static <T> T call(GitHubCall<T> call) throws IOException {
        try {
            return call.run();
        } catch (IOException e) {
            skipIfUnavailable(e);
            throw e;
        }
    }

    private static void skipIfUnavailable(IOException e) throws IOException {
        String message = String.valueOf(e.getMessage());
        if (isUnavailableText(message)) {
            Assumptions.assumeTrue(false, "GitHub API unavailable: " + message);
        }
        // Some source errors carry only the status; ask GitHub again to see why.
        if (message.matches("(?s).*HTTP (403|429)\\b.*")) {
            Optional<String> reason = unavailableReason();
            if (reason.isPresent()) {
                Assumptions.assumeTrue(false, reason.get());
            }
        }
    }

    /**
     * Requests the latest release (an endpoint {@link GitHubSource} uses) and returns why GitHub
     * refused it, if it did because of the rate limit or because a proxy blocks the repository.
     */
    private static Optional<String> unavailableReason() throws IOException {
        HttpService.Response r = HTTP.get(URI.create(GitHubSource.DEFAULT_BASE_URL + "/repos/" + REPO + "/releases/latest"),
                apiHeaders(), TIMEOUT);
        if (r.status() != 403 && r.status() != 429) {
            return Optional.empty();
        }
        boolean exhausted = r.header("X-RateLimit-Remaining").orElse("").equals("0");
        if (exhausted || isUnavailableText(r.body())) {
            return Optional.of("GitHub API unavailable (HTTP " + r.status() + "): " + r.body().strip());
        }
        return Optional.empty();
    }

    private static boolean isUnavailableText(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return lower.contains("rate limit") || lower.contains("not enabled for this session");
    }

    /** The same headers {@link GitHubSource} sends to the API. */
    private static Map<String, String> apiHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/vnd.github+json");
        h.put("X-GitHub-Api-Version", "2022-11-28");
        if (TOKEN != null) {
            h.put("Authorization", "Bearer " + TOKEN);
        }
        return h;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
