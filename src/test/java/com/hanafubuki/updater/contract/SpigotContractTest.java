package com.hanafubuki.updater.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.hanafubuki.updater.http.HttpService;
import com.hanafubuki.updater.source.InstalledPlugin;
import com.hanafubuki.updater.source.SourceSettings;
import com.hanafubuki.updater.source.SpigotSource;
import com.hanafubuki.updater.source.UpdateCandidate;
import com.hanafubuki.updater.util.Json;

/**
 * Contract tests against the real Spiget API (https://api.spiget.org/v2).
 */
@Tag("contract")
class SpigotContractTest {

    private static final Pattern RESOURCE_PAGE = Pattern.compile("^https://www\\.spigotmc\\.org/resources/(\\d+)/$");

    private final HttpService http = ContractHttp.service();
    private final SpigotSource spigot = new SpigotSource(http, SourceSettings.DEFAULT);

    @Test
    void externalLinkToReleasePageIsRefused(@TempDir Path dir) throws Exception {
        // EssentialsX links to a GitHub release page, not to a jar file.
        InstalledPlugin installed = ContractHttp.installed(dir, "Essentials", "EssentialsX.jar");

        IOException e = assertThrows(IOException.class, () -> spigot.findLatest(
                ContractHttp.request("spigotmc", installed, Map.of("resource_id", "9089"))));

        assertTrue(e.getMessage().contains("external"), e.getMessage());
    }

    @Test
    void externalLinkToJarIsFollowed(@TempDir Path dir) throws Exception {
        // ProtocolLib links directly to a .jar asset of a GitHub release.
        InstalledPlugin installed = ContractHttp.installed(dir, "ProtocolLib", "ProtocolLib.jar");

        Optional<UpdateCandidate> found = spigot.findLatest(
                ContractHttp.request("spigotmc", installed, Map.of("resource_id", "1997")));

        assertTrue(found.isPresent());
        UpdateCandidate candidate = found.get();
        assertFalse(candidate.version().isBlank());
        assertEquals("https", candidate.downloadUrl().getScheme());
        assertTrue(candidate.downloadUrl().getPath().toLowerCase().endsWith(".jar"), candidate.downloadUrl().toString());
        assertNull(candidate.checksum(), "Spiget publishes no checksums");
        assertEquals("https://www.spigotmc.org/resources/1997/", candidate.pageUrl());
    }

    @Test
    void nameSearchResolvesOnlyTheExactName(@TempDir Path dir) throws Exception {
        // The search for "Vault" also returns "Vaults", "Vault [Storage]", "PlayerVaults", ...
        InstalledPlugin installed = ContractHttp.installed(dir, "Vault", "Vault.jar");

        Optional<UpdateCandidate> found = spigot.findLatest(
                ContractHttp.request("spigotmc", installed, Map.of("resource_name", "Vault")));

        assertTrue(found.isPresent());
        Matcher m = RESOURCE_PAGE.matcher(found.get().pageUrl());
        assertTrue(m.matches(), "unexpected page URL " + found.get().pageUrl());
        String id = m.group(1);
        assertEquals("34315", id, "'Vault' resolved to another resource");

        JsonObject resource = Json.parseObject(http.getOk(URI.create(SpigotSource.DEFAULT_BASE_URL + "/resources/" + id),
                Map.of("Accept", "application/json"), Duration.ofSeconds(10)), "Spiget resource " + id);
        assertEquals("Vault", Json.str(resource, "name"));
    }

    @Test
    void ambiguousNameIsRefusedEvenWhenMatchesAreBeyondTheFirstResultPage(@TempDir Path dir) throws Exception {
        // Spiget pages search results (X-Page-Count). Many resources are named exactly "Essentials",
        // none of them on the first page of 25 results.
        InstalledPlugin installed = ContractHttp.installed(dir, "Essentials", "Essentials.jar");

        IOException e = assertThrows(IOException.class, () -> spigot.findLatest(
                ContractHttp.request("spigotmc", installed, Map.of("resource_name", "Essentials"))));

        assertTrue(e.getMessage().contains("Several SpigotMC resources are named 'Essentials'"), e.getMessage());
    }
}
