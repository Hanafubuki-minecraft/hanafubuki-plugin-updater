package com.hanafubuki.updater.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hanafubuki.updater.http.HttpService;
import com.hanafubuki.updater.manager.Installer;
import com.hanafubuki.updater.source.InstalledPlugin;
import com.hanafubuki.updater.source.ModrinthSource;
import com.hanafubuki.updater.source.UpdateCandidate;
import com.hanafubuki.updater.util.Checksum;
import com.hanafubuki.updater.util.PluginDescriptor;

/**
 * Contract tests against the real Modrinth API (https://api.modrinth.com/v2).
 *
 * <p>Uses LuckPerms (project id {@code Vebnzrzj}, slug {@code luckperms}), which publishes
 * Paper builds for Minecraft 1.21.9.
 */
@Tag("contract")
class ModrinthContractTest {

    private static final String LUCKPERMS_PROJECT_ID = "Vebnzrzj";

    private final HttpService http = ContractHttp.service();
    private final ModrinthSource modrinth = new ModrinthSource(http, ModrinthSource.Settings.DEFAULT);

    @Test
    void latestLuckPermsVersionDownloadsVerifiesAndIsIdentifiedByHash(@TempDir Path dir) throws Exception {
        InstalledPlugin installed = ContractHttp.installed(dir.resolve("plugins"), "LuckPerms", "LuckPerms.jar");

        Optional<UpdateCandidate> found = modrinth.findLatest(
                ContractHttp.request("modrinth", installed, Map.of("project_id", "luckperms")));

        assertTrue(found.isPresent(), "Modrinth lists no paper/1.21.9 release of luckperms");
        UpdateCandidate candidate = found.get();
        assertNotNull(candidate.version());
        assertFalse(candidate.version().isBlank(), "version_number is blank");
        assertTrue(candidate.version().matches(".*\\d+\\.\\d+.*"), "unexpected version_number: " + candidate.version());
        assertEquals("https", candidate.downloadUrl().getScheme());
        assertEquals("cdn.modrinth.com", candidate.downloadUrl().getHost());
        assertTrue(candidate.downloadUrl().getPath().endsWith(".jar"), "not a jar: " + candidate.downloadUrl());
        assertNotNull(candidate.checksum(), "Modrinth file without SHA-512");
        assertEquals(Checksum.Algorithm.SHA512, candidate.checksum().algorithm());
        // The fake installed jar is not on Modrinth, so freshness cannot come from Modrinth data.
        assertEquals(UpdateCandidate.Freshness.UNKNOWN, candidate.freshness());
        assertTrue(candidate.pageUrl().startsWith("https://modrinth.com/project/" + LUCKPERMS_PROJECT_ID + "/version/"),
                "unexpected page URL " + candidate.pageUrl());

        Path updateFolder = dir.resolve("plugins").resolve("update");
        Path staged = new Installer(http).stage(installed, candidate, updateFolder,
                ContractHttp.DOWNLOAD_TIMEOUT, ContractHttp.MAX_DOWNLOAD_BYTES, null);

        assertEquals(updateFolder.resolve("LuckPerms.jar"), staged);
        assertTrue(Files.isRegularFile(staged));
        assertTrue(candidate.checksum().matches(staged));
        assertEquals("LuckPerms", PluginDescriptor.read(staged).orElseThrow().name());

        String sha512 = Checksum.hash(staged, Checksum.Algorithm.SHA512);
        Optional<ModrinthSource.KnownVersion> known = modrinth.identify(sha512);
        assertTrue(known.isPresent(), "Modrinth does not know the file it served");
        assertEquals(LUCKPERMS_PROJECT_ID, known.get().projectId());
        assertTrue(candidate.pageUrl().endsWith("/version/" + known.get().id()),
                "hash lookup returned another version than the one listed: " + known.get().id());
    }

    @Test
    void unknownHashIsNotFound() throws Exception {
        assertTrue(modrinth.identify("a".repeat(128)).isEmpty());
    }
}
