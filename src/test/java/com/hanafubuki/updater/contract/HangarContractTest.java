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
import com.hanafubuki.updater.source.HangarSource;
import com.hanafubuki.updater.source.InstalledPlugin;
import com.hanafubuki.updater.source.UpdateCandidate;
import com.hanafubuki.updater.util.Checksum;
import com.hanafubuki.updater.util.PluginDescriptor;

/**
 * Contract tests against the real Hangar API (https://hangar.papermc.io/api/v1).
 */
@Tag("contract")
class HangarContractTest {

    private final HttpService http = ContractHttp.service();
    private final HangarSource hangar = new HangarSource(http, HangarSource.Settings.DEFAULT);

    @Test
    void latestViaVersionReleaseDownloadsAndVerifies(@TempDir Path dir) throws Exception {
        InstalledPlugin installed = ContractHttp.installed(dir.resolve("plugins"), "ViaVersion", "ViaVersion.jar");

        Optional<UpdateCandidate> found = hangar.findLatest(
                ContractHttp.request("hangar", installed, Map.of("project_slug", "ViaVersion")));

        assertTrue(found.isPresent(), "Hangar lists no Release version of ViaVersion for PAPER 1.21.9");
        UpdateCandidate candidate = found.get();
        assertNotNull(candidate.version());
        assertFalse(candidate.version().isBlank());
        assertEquals("https", candidate.downloadUrl().getScheme());
        assertEquals("hangarcdn.papermc.io", candidate.downloadUrl().getHost());
        assertTrue(candidate.downloadUrl().getPath().endsWith(".jar"), "not a jar: " + candidate.downloadUrl());
        assertNotNull(candidate.checksum(), "Hangar published no sha256Hash");
        assertEquals(Checksum.Algorithm.SHA256, candidate.checksum().algorithm());
        assertTrue(candidate.notes().isEmpty(), "unexpected notes " + candidate.notes());

        Path updateFolder = dir.resolve("plugins").resolve("update");
        Path staged = new Installer(http).stage(installed, candidate, updateFolder,
                ContractHttp.DOWNLOAD_TIMEOUT, ContractHttp.MAX_DOWNLOAD_BYTES, null);

        assertEquals(updateFolder.resolve("ViaVersion.jar"), staged);
        assertTrue(Files.isRegularFile(staged));
        assertTrue(candidate.checksum().matches(staged));
        assertEquals("ViaVersion", PluginDescriptor.read(staged).orElseThrow().name());
    }
}
