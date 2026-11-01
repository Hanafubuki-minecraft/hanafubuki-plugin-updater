package com.hanafubuki.updater.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hanafubuki.updater.config.PluginEntry;
import com.hanafubuki.updater.testing.TestJars;
import com.hanafubuki.updater.util.Checksum;

class LocalSourceTest {

    @TempDir
    Path dir;

    private Path repo;
    private Path installedJar;
    private final LocalSource source = new LocalSource();

    @BeforeEach
    void setUp() throws IOException {
        repo = Files.createDirectories(dir.resolve("repo"));
        installedJar = TestJars.write(dir.resolve("plugins"), "MyPlugin.jar", TestJars.plugin("MyPlugin", "1.0"));
    }

    private UpdateRequest request(Map<String, Object> options) {
        return new UpdateRequest(new PluginEntry("MyPlugin", "local", options), new InstalledPlugin("MyPlugin", "1.0", installedJar), "1.21.9");
    }

    @Test
    void directoryScanPicksTheNewestJarOfThisPlugin() throws IOException {
        TestJars.write(repo, "MyPlugin-1.5.jar", TestJars.plugin("MyPlugin", "1.5"));
        Path newest = TestJars.write(repo, "MyPlugin-1.10.jar", TestJars.plugin("MyPlugin", "1.10"));
        TestJars.write(repo, "MyPlugin-1.9.jar", TestJars.plugin("MyPlugin", "1.9"));
        TestJars.write(repo, "OtherPlugin-9.0.jar", TestJars.plugin("OtherPlugin", "9.0"));
        TestJars.write(repo, "MyPlugin-99.jar.disabled", TestJars.plugin("MyPlugin", "99"));
        Files.writeString(repo.resolve("broken.jar"), "not a zip");
        Files.createDirectories(repo.resolve("MyPlugin-50.jar"));

        UpdateCandidate c = source.findLatest(request(Map.of("path", repo.toString()))).orElseThrow();

        assertEquals("1.10", c.version());
        assertEquals(newest.toUri(), c.downloadUrl());
        assertNull(c.checksum());
        assertEquals(UpdateCandidate.Freshness.UNKNOWN, c.freshness());
        assertEquals(List.of("No checksum file next to MyPlugin-1.10.jar"), c.notes());
    }

    @Test
    void paperPluginDescriptorsAreRecognised() throws IOException {
        TestJars.write(repo, "MyPlugin-2.0.jar", TestJars.paperPlugin("MyPlugin", "2.0"));

        assertEquals("2.0", source.findLatest(request(Map.of("path", repo.toString()))).orElseThrow().version());
    }

    @Test
    void noJarOfThisPluginGivesEmpty() throws IOException {
        TestJars.write(repo, "OtherPlugin-9.0.jar", TestJars.plugin("OtherPlugin", "9.0"));

        assertEquals(Optional.empty(), source.findLatest(request(Map.of("path", repo.toString()))));
    }

    @Test
    void patternGroupGivesTheVersion() throws IOException {
        TestJars.write(repo, "MyPlugin-build7.jar", TestJars.plugin("MyPlugin", "dev"));
        Path b12 = TestJars.write(repo, "MyPlugin-build12.jar", TestJars.plugin("MyPlugin", "dev"));
        TestJars.write(repo, "MyPlugin-latest.jar", TestJars.plugin("MyPlugin", "999"));

        UpdateCandidate c = source.findLatest(request(Map.of("path", repo.toString(), "pattern", "MyPlugin-build(\\d+)\\.jar"))).orElseThrow();

        assertEquals("12", c.version());
        assertEquals(b12.toUri(), c.downloadUrl());
    }

    @Test
    void sha256SidecarIsUsed() throws IOException {
        Path jar = TestJars.write(repo, "MyPlugin-2.0.jar", TestJars.plugin("MyPlugin", "2.0"));
        String hash = Checksum.hash(jar, Checksum.Algorithm.SHA256);
        Files.writeString(repo.resolve("MyPlugin-2.0.jar.sha256"), hash + "  MyPlugin-2.0.jar\n");

        UpdateCandidate c = source.findLatest(request(Map.of("path", repo.toString()))).orElseThrow();

        assertEquals(new Checksum(Checksum.Algorithm.SHA256, hash), c.checksum());
        assertTrue(c.notes().isEmpty());
    }

    @Test
    void sha512SidecarIsPreferred() throws IOException {
        Path jar = TestJars.write(repo, "MyPlugin-2.0.jar", TestJars.plugin("MyPlugin", "2.0"));
        Files.writeString(repo.resolve("MyPlugin-2.0.jar.sha256"), Checksum.hash(jar, Checksum.Algorithm.SHA256));
        String sha512 = Checksum.hash(jar, Checksum.Algorithm.SHA512);
        Files.writeString(repo.resolve("MyPlugin-2.0.jar.sha512"), "sha512:" + sha512);

        assertEquals(new Checksum(Checksum.Algorithm.SHA512, sha512),
                source.findLatest(request(Map.of("path", repo.toString()))).orElseThrow().checksum());
    }

    @Test
    void invalidSidecarIsAnError() throws IOException {
        TestJars.write(repo, "MyPlugin-2.0.jar", TestJars.plugin("MyPlugin", "2.0"));
        Files.writeString(repo.resolve("MyPlugin-2.0.jar.sha256"), "this is not a hash\n");

        IOException e = assertThrows(IOException.class, () -> source.findLatest(request(Map.of("path", repo.toString()))));
        assertTrue(e.getMessage().contains("MyPlugin-2.0.jar.sha256"), e.getMessage());
    }

    @Test
    void singleFilePathWithChecksumOption() throws IOException {
        Path jar = TestJars.write(repo, "MyPlugin.jar", TestJars.plugin("MyPlugin", "2.0"));
        String hash = Checksum.hash(jar, Checksum.Algorithm.SHA256);

        UpdateCandidate c = source.findLatest(request(Map.of("path", jar.toString(), "checksum", "sha256:" + hash))).orElseThrow();

        assertEquals("2.0", c.version());
        assertEquals(jar.toUri(), c.downloadUrl());
        assertEquals(new Checksum(Checksum.Algorithm.SHA256, hash), c.checksum());
        assertTrue(c.notes().isEmpty());
    }

    @Test
    void singleFileOfAnotherPluginGivesEmpty() throws IOException {
        Path jar = TestJars.write(repo, "Other.jar", TestJars.plugin("Other", "2.0"));

        assertEquals(Optional.empty(), source.findLatest(request(Map.of("path", jar.toString()))));
    }

    @Test
    void missingPathIsAnError() {
        IOException e = assertThrows(IOException.class,
                () -> source.findLatest(request(Map.of("path", dir.resolve("nope").toString()))));
        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
    }

    @Test
    void identicalFileIsSame() throws IOException {
        Files.copy(installedJar, repo.resolve("MyPlugin-1.0.jar"));

        UpdateCandidate c = source.findLatest(request(Map.of("path", repo.toString()))).orElseThrow();

        assertEquals(UpdateCandidate.Freshness.SAME, c.freshness());
    }

    @Test
    void validate() {
        assertTrue(source.validate(new PluginEntry("P", "local", Map.of("path", "/srv/jars"))).isEmpty());
        assertTrue(source.validate(new PluginEntry("P", "local", Map.of("path", "/srv/jars", "pattern", "P-(.+)\\.jar"))).isEmpty());
        assertTrue(source.validate(new PluginEntry("P", "local", Map.of())).get(0).contains("path"));
        assertTrue(source.validate(new PluginEntry("P", "local", Map.of("path", "/x", "pattern", "P-.+\\.jar"))).get(0)
                .contains("capturing group"));
        assertTrue(source.validate(new PluginEntry("P", "local", Map.of("path", "/x", "pattern", "P-(.+"))).get(0)
                .contains("not a valid regex"));
        assertTrue(source.validate(new PluginEntry("P", "local", Map.of("path", "/x", "checksum", "abc"))).get(0)
                .contains("checksum"));
        assertEquals("local", source.id());
    }
}
