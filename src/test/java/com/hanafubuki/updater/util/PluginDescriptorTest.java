package com.hanafubuki.updater.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hanafubuki.updater.testing.TestJars;

class PluginDescriptorTest {

    @TempDir
    Path dir;

    /** Jar with several text entries, in the given order. */
    private Path jar(String fileName, Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return TestJars.write(dir, fileName, bytes.toByteArray());
    }

    @Test
    void readsPluginYml() throws IOException {
        Path jar = TestJars.write(dir, "Essentials.jar",
                TestJars.plugin("Essentials", "2.21.0", List.of("Vault", "LuckPerms"), "1.21"));
        PluginDescriptor d = PluginDescriptor.read(jar).orElseThrow();
        assertEquals("Essentials", d.name());
        assertEquals("2.21.0", d.version());
        assertEquals("1.21", d.apiVersion());
        assertEquals(List.of("Vault", "LuckPerms"), d.depend());
        assertNull(d.website());
        assertFalse(d.paperPlugin());
    }

    @Test
    void readsPaperPluginYml() throws IOException {
        Path jar = TestJars.write(dir, "Paperish.jar", TestJars.paperPlugin("Paperish", "3.0.0"));
        PluginDescriptor d = PluginDescriptor.read(jar).orElseThrow();
        assertEquals("Paperish", d.name());
        assertEquals("3.0.0", d.version());
        assertEquals("1.21", d.apiVersion());
        assertTrue(d.paperPlugin());
        assertEquals(List.of(), d.depend());
    }

    @Test
    void paperPluginYmlWinsOverPluginYml() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        // plugin.yml first in the archive, so order in the jar does not decide
        entries.put("plugin.yml", "name: Legacy\nversion: '1.0'\nmain: a.Main\n");
        entries.put("paper-plugin.yml", "name: Modern\nversion: '2.0'\nmain: a.Main\n");
        PluginDescriptor d = PluginDescriptor.read(jar("both.jar", entries)).orElseThrow();
        assertEquals("Modern", d.name());
        assertEquals("2.0", d.version());
        assertTrue(d.paperPlugin());
    }

    @Test
    void fallsBackToPluginYmlWhenPaperPluginYmlHasNoName() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("paper-plugin.yml", "version: '2.0'\nmain: a.Main\n");
        entries.put("plugin.yml", "name: Legacy\nversion: '1.0'\nmain: a.Main\ndepend: [Vault]\n");
        PluginDescriptor d = PluginDescriptor.read(jar("fallback.jar", entries)).orElseThrow();
        assertEquals("Legacy", d.name());
        assertFalse(d.paperPlugin());
        assertEquals(List.of("Vault"), d.depend());
    }

    @Test
    void paperPluginServerDependenciesHonourRequiredFlag() throws IOException {
        String yml = """
                name: Deps
                version: '1.0'
                main: a.Main
                api-version: '1.21'
                dependencies:
                  bootstrap:
                    BootOnly:
                      required: true
                  server:
                    Vault:
                      load: BEFORE
                    LuckPerms:
                      required: true
                    PlaceholderAPI:
                      required: false
                    ProtocolLib: {}
                """;
        PluginDescriptor d = PluginDescriptor.read(jar("deps.jar", Map.of("paper-plugin.yml", yml))).orElseThrow();
        // required defaults to true; bootstrap dependencies are not server dependencies
        assertEquals(List.of("Vault", "LuckPerms", "ProtocolLib"), d.depend());
    }

    @Test
    void paperPluginIgnoresBukkitStyleDependList() {
        PluginDescriptor d = PluginDescriptor.parse("name: X\ndepend: [Vault]\n", true).orElseThrow();
        assertEquals(List.of(), d.depend());
    }

    @Test
    void pluginYmlIgnoresSoftDependAndLoadBefore() {
        PluginDescriptor d = PluginDescriptor.parse("""
                name: X
                version: 1.0.0
                depend:
                  - Vault
                softdepend: [PlaceholderAPI]
                loadbefore: [Other]
                """, false).orElseThrow();
        assertEquals(List.of("Vault"), d.depend());
    }

    @Test
    void readsWebsiteAndTrimsValues() {
        PluginDescriptor d = PluginDescriptor.parse("""
                name: '  Spaced  '
                version: ' 1.0 '
                website: https://modrinth.com/plugin/spaced
                api-version: 1.21
                """, false).orElseThrow();
        assertEquals("Spaced", d.name());
        assertEquals("1.0", d.version());
        assertEquals("https://modrinth.com/plugin/spaced", d.website());
        assertEquals("1.21", d.apiVersion());
    }

    @Test
    void numericVersionBecomesText() {
        assertEquals("2", PluginDescriptor.parse("name: X\nversion: 2\n", false).orElseThrow().version());
    }

    @Test
    void missingVersionIsNull() {
        assertNull(PluginDescriptor.parse("name: X\n", false).orElseThrow().version());
    }

    @Test
    void missingOrBlankNameIsEmpty() {
        assertEquals(Optional.empty(), PluginDescriptor.parse("version: 1.0\n", false));
        assertEquals(Optional.empty(), PluginDescriptor.parse("name: '  '\n", false));
        assertEquals(Optional.empty(), PluginDescriptor.parse("- just\n- a list\n", false));
        assertEquals(Optional.empty(), PluginDescriptor.parse("", false));
    }

    @Test
    void jarWithoutDescriptorIsEmpty() throws IOException {
        Path jar = TestJars.write(dir, "lib.jar", TestJars.jar("META-INF/something.txt", "x"));
        assertEquals(Optional.empty(), PluginDescriptor.read(jar));
    }

    @Test
    void nonJarFileIsEmpty() throws IOException {
        Path txt = Files.writeString(dir.resolve("notes.jar"), "this is not a zip file");
        assertEquals(Optional.empty(), PluginDescriptor.read(txt));
    }

    @Test
    void missingFileDirectoryAndNullAreEmpty() {
        assertEquals(Optional.empty(), PluginDescriptor.read(dir.resolve("missing.jar")));
        assertEquals(Optional.empty(), PluginDescriptor.read(dir));
        assertEquals(Optional.empty(), PluginDescriptor.read(null));
    }

    @Test
    void invalidYamlIsEmpty() throws IOException {
        Path jar = jar("broken.jar", Map.of("plugin.yml", "name: [unclosed\nversion: : :"));
        assertEquals(Optional.empty(), PluginDescriptor.read(jar));
    }

    @Test
    void unsafeYamlTagsAreRejected() throws IOException {
        Path jar = jar("tagged.jar", Map.of("plugin.yml",
                "name: !!java.io.File [/tmp]\nversion: '1.0'\n"));
        assertEquals(Optional.empty(), PluginDescriptor.read(jar));
    }

    @Test
    void descriptorLargerThanOneMegabyteIsRejected() throws IOException {
        StringBuilder yml = new StringBuilder("name: Huge\nversion: '1.0'\nmain: a.Main\n");
        String comment = "# padding padding padding padding padding padding padding padding\n";
        while (yml.length() <= 1024 * 1024) {
            yml.append(comment);
        }
        Path jar = jar("huge.jar", Map.of("plugin.yml", yml.toString()));
        // Compressed, the jar itself stays small: the limit applies to the uncompressed entry.
        assertTrue(Files.size(jar) < 100 * 1024);
        assertEquals(Optional.empty(), PluginDescriptor.read(jar));
    }

    @Test
    void descriptorJustBelowTheLimitIsAccepted() throws IOException {
        StringBuilder yml = new StringBuilder("name: Big\nversion: '1.0'\n");
        while (yml.length() < 1024 * 1024 - 100) {
            yml.append("# padding\n");
        }
        Path jar = jar("big.jar", Map.of("plugin.yml", yml.toString()));
        assertEquals("Big", PluginDescriptor.read(jar).orElseThrow().name());
    }

    @Test
    void dependListIsImmutableCopy() {
        List<String> deps = new ArrayList<>(List.of("A"));
        PluginDescriptor d = new PluginDescriptor("X", "1", null, null, deps, false);
        deps.add("B");
        assertEquals(List.of("A"), d.depend());
        assertThrows(UnsupportedOperationException.class, () -> d.depend().add("C"));
        assertEquals(List.of(), new PluginDescriptor("X", "1", null, null, null, false).depend());
    }
}
