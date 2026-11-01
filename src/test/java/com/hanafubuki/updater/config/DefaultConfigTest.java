package com.hanafubuki.updater.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.hanafubuki.updater.manager.Schedule;
import com.hanafubuki.updater.source.CustomHttpSource;
import com.hanafubuki.updater.source.GitHubSource;
import com.hanafubuki.updater.source.HangarSource;
import com.hanafubuki.updater.source.LocalSource;
import com.hanafubuki.updater.source.ModrinthSource;
import com.hanafubuki.updater.source.SpigotSource;
import com.hanafubuki.updater.source.UpdateSource;
import com.hanafubuki.updater.testing.TestHttp;

/**
 * The shipped config.yml and docs/config.example.yml must parse without any problem, so the
 * documentation never drifts from what the plugin accepts.
 */
class DefaultConfigTest {

    private static Map<String, Object> load(Path path) throws IOException {
        return new Yaml().load(Files.readString(path));
    }

    @Test
    void shippedConfigHasNoProblems() throws IOException {
        SettingsParser.Result result = SettingsParser.parse(load(Path.of("src/main/resources/config.yml")), name -> null);
        assertEquals(List.of(), result.problems());
        UpdaterSettings s = result.settings();
        assertTrue(s.plugins().isEmpty(), "the default config must not update anything until the admin adds plugins");
        assertEquals(Schedule.Mode.OFF, s.schedule().mode());
        assertFalse(s.health().enabled());
        assertEquals("127.0.0.1", s.health().bindAddress());
        assertFalse(s.notifications().discord().enabled());
        assertTrue(s.backups().enabled());
        assertEquals(UpdaterSettings.DependencyMode.BLOCK, s.dependencies());
        assertEquals(List.of("paper"), s.modrinth().loaders());
        assertEquals(List.of("release"), s.modrinth().versionTypes());
    }

    @Test
    void exampleConfigHasNoProblems() throws IOException {
        Map<String, String> env = Map.of("DISCORD_WEBHOOK_URL", "https://discord.com/api/webhooks/1/abc",
                "UPDATER_WEBHOOK_TOKEN", "t", "GITHUB_TOKEN", "ghp_test", "CI_TOKEN", "ci");
        SettingsParser.Result result = SettingsParser.parse(load(Path.of("docs/config.example.yml")), env::get);
        assertEquals(List.of(), result.problems());
        UpdaterSettings s = result.settings();
        assertEquals(Schedule.Mode.DAILY, s.schedule().mode());
        assertTrue(s.notifications().discord().enabled());
        assertEquals("ghp_test", s.github().token());

        Map<String, UpdateSource> sources = Map.of(
                "modrinth", new ModrinthSource(TestHttp.service(), ModrinthSource.Settings.DEFAULT),
                "github", new GitHubSource(TestHttp.service(), GitHubSource.Settings.DEFAULT),
                "hangar", new HangarSource(TestHttp.service(), HangarSource.Settings.DEFAULT),
                "spigotmc", new SpigotSource(TestHttp.service(), TestHttp.SETTINGS),
                "local", new LocalSource(),
                "custom", new CustomHttpSource(TestHttp.service(), TestHttp.SETTINGS));
        assertEquals(sources.keySet(), Map.copyOf(s.plugins()).values().stream().map(PluginEntry::source)
                .collect(java.util.stream.Collectors.toSet()), "the example should show every source");
        for (PluginEntry entry : s.plugins().values()) {
            assertEquals(List.of(), sources.get(entry.source()).validate(entry), "entry " + entry.name());
        }
    }
}
