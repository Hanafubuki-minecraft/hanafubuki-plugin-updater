package com.hanafubuki.updater.source;

import static com.hanafubuki.updater.testing.TestJson.arr;
import static com.hanafubuki.updater.testing.TestJson.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.hanafubuki.updater.testing.FakeHttpServer;
import com.hanafubuki.updater.testing.TestHttp;

class GitHubAdvisoriesTest {

    private static final String PATH = "/repos/Owner/Repo/security-advisories";

    private FakeHttpServer server;
    private GitHubAdvisories advisories;

    @BeforeEach
    void setUp() throws IOException {
        server = FakeHttpServer.start();
        advisories = new GitHubAdvisories(TestHttp.service(), server.base() + "/",
                Map.of("Accept", "application/vnd.github+json"), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static JsonObject advisory(String ghsa, String severity, String withdrawnAt, JsonObject... vulnerabilities) {
        return obj("ghsa_id", ghsa, "cve_id", "CVE-2026-0001", "html_url", "https://github.com/Owner/Repo/security/advisories/" + ghsa,
                "summary", "Remote code execution", "severity", severity, "withdrawn_at", withdrawnAt,
                "vulnerabilities", arr((Object[]) vulnerabilities));
    }

    private static JsonObject vulnerability(String range, String patched) {
        return obj("package", obj("ecosystem", "maven", "name", "com.example:plugin"),
                "vulnerable_version_range", range, "patched_versions", patched);
    }

    private void answer(JsonObject... advisories) {
        server.json(PATH, arr((Object[]) advisories).toString());
    }

    @Test
    void affectedVersionGivesAnAlert() throws IOException {
        answer(advisory("GHSA-aaaa-bbbb-cccc", "HIGH", null, vulnerability(">= 1.0.0, < 1.0.1", "1.0.1")));

        List<GitHubAdvisories.Alert> alerts = advisories.check("Owner/Repo", "1.0.0");

        assertEquals(List.of(new GitHubAdvisories.Alert("GHSA-aaaa-bbbb-cccc", "CVE-2026-0001", "high", "Remote code execution",
                "https://github.com/Owner/Repo/security/advisories/GHSA-aaaa-bbbb-cccc", "1.0.1")), alerts);
        FakeHttpServer.Request r = server.requests(PATH).get(0);
        assertEquals("state=published&per_page=100", r.query());
        assertEquals("application/vnd.github+json", r.header("Accept"));
    }

    @Test
    void versionOutsideTheRangeIsNotAffected() throws IOException {
        answer(advisory("GHSA-1", "high", null, vulnerability(">= 1.0.0, < 1.0.1", "1.0.1")));

        assertTrue(advisories.check("Owner/Repo", "1.0.1").isEmpty());
        assertTrue(advisories.check("Owner/Repo", "0.9").isEmpty());
        assertEquals(1, advisories.check("Owner/Repo", "v1.0.0").size());
    }

    @Test
    void anyMatchingVulnerabilityEntryCountsOncePerAdvisory() throws IOException {
        answer(advisory("GHSA-1", "critical", null,
                vulnerability("< 0.5", "0.5"), vulnerability(">= 1.0, < 2.0", "2.0"), vulnerability("<= 1.5", "1.6")));

        List<GitHubAdvisories.Alert> alerts = advisories.check("Owner/Repo", "1.2");

        assertEquals(1, alerts.size());
        assertEquals("2.0", alerts.get(0).patched());
    }

    @Test
    void withdrawnAdvisoriesAreSkipped() throws IOException {
        answer(advisory("GHSA-withdrawn", "high", "2026-05-01T00:00:00Z", vulnerability("< 2.0", "2.0")),
                advisory("GHSA-active", "low", null, vulnerability("< 2.0", "2.0")));

        List<GitHubAdvisories.Alert> alerts = advisories.check("Owner/Repo", "1.0");

        assertEquals(1, alerts.size());
        assertEquals("GHSA-active", alerts.get(0).id());
        assertEquals("low", alerts.get(0).severity());
    }

    @Test
    void unparsableAndMissingRangesAreSkipped() throws IOException {
        answer(advisory("GHSA-bad", "high", null, vulnerability(">=", null), vulnerability("", null)),
                advisory("GHSA-norange", "high", null, obj("package", obj("name", "x"))),
                advisory("GHSA-good", "medium", null, vulnerability(">=", null), vulnerability("= 1.0", "1.1")));

        List<GitHubAdvisories.Alert> alerts = advisories.check("Owner/Repo", "1.0");

        assertEquals(1, alerts.size());
        assertEquals("GHSA-good", alerts.get(0).id());
    }

    @Test
    void missingSeverityIsUnknown() throws IOException {
        JsonObject adv = advisory("GHSA-1", null, null, vulnerability("< 2.0", null));
        answer(adv);

        assertEquals("unknown", advisories.check("Owner/Repo", "1.0").get(0).severity());
    }

    @Test
    void repoWithoutAdvisoriesAnswering404GivesEmpty() throws IOException {
        assertTrue(advisories.check("Owner/Repo", "1.0").isEmpty());
    }

    @Test
    void otherErrorStatusIsAnException() {
        server.route(PATH, r -> FakeHttpServer.Reply.json(401, "{\"message\":\"Bad credentials\"}"));

        IOException e = assertThrows(IOException.class, () -> advisories.check("Owner/Repo", "1.0"));
        assertTrue(e.getMessage().contains("401"), e.getMessage());
    }
}
