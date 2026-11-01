package com.hanafubuki.updater.manager;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hanafubuki.updater.source.InstalledPlugin;
import com.hanafubuki.updater.source.UpdateCandidate;
import com.hanafubuki.updater.testing.FakeHttpServer;
import com.hanafubuki.updater.testing.TestHttp;
import com.hanafubuki.updater.testing.TestJars;
import com.hanafubuki.updater.testing.TestJson;
import com.hanafubuki.updater.util.Checksum;
import com.hanafubuki.updater.util.PluginDescriptor;

class InstallerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long MAX = 10 * 1024 * 1024;

    @TempDir
    Path dir;

    private FakeHttpServer server;
    private Path plugins;
    private Path updateFolder;
    private InstalledPlugin installed;
    private final Installer installer = new Installer(TestHttp.service());

    @BeforeEach
    void setUp() throws IOException {
        server = FakeHttpServer.start();
        plugins = dir.resolve("plugins");
        updateFolder = plugins.resolve("update");
        Path jar = TestJars.write(plugins, "EssentialsX-2.20.1.jar", TestJars.plugin("Essentials", "2.20.1"));
        installed = new InstalledPlugin("Essentials", "2.20.1", jar);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static UpdateCandidate candidate(URI url, Checksum checksum) {
        return candidate(url, checksum, Map.of());
    }

    private static UpdateCandidate candidate(URI url, Checksum checksum, Map<String, String> headers) {
        return new UpdateCandidate("2.21.0", url, checksum, headers, UpdateCandidate.Freshness.UNKNOWN, null, List.of());
    }

    private static Checksum sha256(byte[] bytes) {
        return new Checksum(Checksum.Algorithm.SHA256, TestJson.digest("SHA-256", bytes));
    }

    private List<String> updateFolderFiles() throws IOException {
        if (!Files.isDirectory(updateFolder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(updateFolder)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private void serve(byte[] bytes) {
        server.route("/EssentialsX-2.21.0.jar", r -> FakeHttpServer.Reply.bytes(200, bytes));
    }

    @Test
    void stagesUnderTheInstalledJarName() throws IOException {
        byte[] bytes = TestJars.plugin("Essentials", "2.21.0");
        serve(bytes);
        byte[] before = Files.readAllBytes(installed.jar());

        Path staged = installer.stage(installed, candidate(server.uri("/EssentialsX-2.21.0.jar"), sha256(bytes)), updateFolder,
                TIMEOUT, MAX, null);

        assertEquals(updateFolder.resolve("EssentialsX-2.20.1.jar"), staged);
        assertEquals(staged, Installer.stagedPath(installed, updateFolder));
        assertArrayEquals(bytes, Files.readAllBytes(staged));
        assertEquals(List.of("EssentialsX-2.20.1.jar"), updateFolderFiles());
        assertArrayEquals(before, Files.readAllBytes(installed.jar()), "the running jar must never be touched");
    }

    @Test
    void existingStagedFileIsReplaced() throws IOException {
        TestJars.write(updateFolder, "EssentialsX-2.20.1.jar", TestJars.plugin("Essentials", "2.21.0-old"));
        byte[] bytes = TestJars.plugin("Essentials", "2.21.0");
        serve(bytes);

        Path staged = installer.stage(installed, candidate(server.uri("/EssentialsX-2.21.0.jar"), null), updateFolder, TIMEOUT, MAX, null);

        assertArrayEquals(bytes, Files.readAllBytes(staged));
        assertEquals(List.of("EssentialsX-2.20.1.jar"), updateFolderFiles());
    }

    @Test
    void downloadHeadersAreSent() throws IOException {
        serve(TestJars.plugin("Essentials", "2.21.0"));

        installer.stage(installed, candidate(server.uri("/EssentialsX-2.21.0.jar"), null, Map.of("Authorization", "Bearer t")),
                updateFolder, TIMEOUT, MAX, null);

        assertEquals("Bearer t", server.requests("/EssentialsX-2.21.0.jar").get(0).header("Authorization"));
    }

    @Test
    void checksumMismatchRejectsAndLeavesNoFile() throws IOException {
        byte[] bytes = TestJars.plugin("Essentials", "2.21.0");
        serve(bytes);
        Checksum wrong = sha256("something else".getBytes(StandardCharsets.UTF_8));

        IOException e = assertThrows(IOException.class, () -> installer.stage(installed,
                candidate(server.uri("/EssentialsX-2.21.0.jar"), wrong), updateFolder, TIMEOUT, MAX, null));

        assertTrue(e.getMessage().contains("Checksum mismatch"), e.getMessage());
        assertEquals(List.of(), updateFolderFiles());
    }

    @Test
    void checksumMismatchKeepsAPreviouslyStagedFile() throws IOException {
        byte[] previous = TestJars.plugin("Essentials", "2.20.9");
        TestJars.write(updateFolder, "EssentialsX-2.20.1.jar", previous);
        serve(TestJars.plugin("Essentials", "2.21.0"));

        assertThrows(IOException.class, () -> installer.stage(installed,
                candidate(server.uri("/EssentialsX-2.21.0.jar"), sha256(new byte[] {1})), updateFolder, TIMEOUT, MAX, null));

        assertArrayEquals(previous, Files.readAllBytes(updateFolder.resolve("EssentialsX-2.20.1.jar")));
        assertEquals(List.of("EssentialsX-2.20.1.jar"), updateFolderFiles());
    }

    @Test
    void wrongPluginNameIsRejected() throws IOException {
        serve(TestJars.plugin("EssentialsChat", "2.21.0"));

        IOException e = assertThrows(IOException.class, () -> installer.stage(installed,
                candidate(server.uri("/EssentialsX-2.21.0.jar"), null), updateFolder, TIMEOUT, MAX, null));

        assertTrue(e.getMessage().contains("EssentialsChat"), e.getMessage());
        assertEquals(List.of(), updateFolderFiles());
    }

    @Test
    void pluginNameMatchIgnoresCase() throws IOException {
        serve(TestJars.plugin("essentials", "2.21.0"));

        installer.stage(installed, candidate(server.uri("/EssentialsX-2.21.0.jar"), null), updateFolder, TIMEOUT, MAX, null);

        assertEquals(List.of("EssentialsX-2.20.1.jar"), updateFolderFiles());
    }

    @Test
    void nonJarIsRejected() throws IOException {
        serve("<html>Download page</html>".getBytes(StandardCharsets.UTF_8));

        IOException e = assertThrows(IOException.class, () -> installer.stage(installed,
                candidate(server.uri("/EssentialsX-2.21.0.jar"), null), updateFolder, TIMEOUT, MAX, null));

        assertTrue(e.getMessage().contains("not a plugin jar"), e.getMessage());
        assertEquals(List.of(), updateFolderFiles());
    }

    @Test
    void jarWithoutDescriptorIsRejected() throws IOException {
        serve(TestJars.jar("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n"));

        assertThrows(IOException.class, () -> installer.stage(installed,
                candidate(server.uri("/EssentialsX-2.21.0.jar"), null), updateFolder, TIMEOUT, MAX, null));
        assertEquals(List.of(), updateFolderFiles());
    }

    @Test
    void verifierGetsTheDescriptorAndCanReject() throws IOException {
        serve(TestJars.plugin("Essentials", "2.21.0", List.of("Vault"), "1.21"));
        AtomicReference<PluginDescriptor> seen = new AtomicReference<>();
        IOException rejection = new IOException("dependency missing");

        IOException e = assertThrows(IOException.class, () -> installer.stage(installed,
                candidate(server.uri("/EssentialsX-2.21.0.jar"), null), updateFolder, TIMEOUT, MAX, (d, file) -> {
                    seen.set(d);
                    assertTrue(Files.isRegularFile(file));
                    throw rejection;
                }));

        assertSame(rejection, e);
        assertEquals("2.21.0", seen.get().version());
        assertEquals(List.of("Vault"), seen.get().depend());
        assertEquals(List.of(), updateFolderFiles());
    }

    @Test
    void fileUrlIsCopied() throws IOException {
        byte[] bytes = TestJars.plugin("Essentials", "2.21.0");
        Path source = TestJars.write(dir.resolve("repo"), "EssentialsX-2.21.0.jar", bytes);

        Path staged = installer.stage(installed, candidate(source.toUri(), sha256(bytes)), updateFolder, TIMEOUT, MAX, null);

        assertArrayEquals(bytes, Files.readAllBytes(staged));
        assertTrue(Files.exists(source), "the source file stays where it is");
    }

    @Test
    void maxBytesIsEnforcedForHttpAndFiles() throws IOException {
        byte[] bytes = TestJars.plugin("Essentials", "2.21.0");
        serve(bytes);
        Path source = TestJars.write(dir.resolve("repo"), "EssentialsX-2.21.0.jar", bytes);
        long limit = bytes.length - 1;

        assertThrows(IOException.class, () -> installer.stage(installed, candidate(server.uri("/EssentialsX-2.21.0.jar"), null),
                updateFolder, TIMEOUT, limit, null));
        assertEquals(List.of(), updateFolderFiles());

        assertThrows(IOException.class, () -> installer.stage(installed, candidate(source.toUri(), null), updateFolder, TIMEOUT, limit, null));
        assertEquals(List.of(), updateFolderFiles());

        // Exactly at the limit is fine.
        installer.stage(installed, candidate(source.toUri(), null), updateFolder, TIMEOUT, bytes.length, null);
        assertEquals(List.of("EssentialsX-2.20.1.jar"), updateFolderFiles());
    }

    @Test
    void httpErrorLeavesNoFile() throws IOException {
        IOException e = assertThrows(IOException.class, () -> installer.stage(installed,
                candidate(server.uri("/missing.jar"), null), updateFolder, TIMEOUT, MAX, null));

        assertTrue(e.getMessage().contains("404"), e.getMessage());
        assertEquals(List.of(), updateFolderFiles());
    }

    @Test
    void unsupportedSchemeIsRejected() throws IOException {
        IOException e = assertThrows(IOException.class, () -> installer.stage(installed,
                candidate(URI.create("ftp://example.org/a.jar"), null), updateFolder, TIMEOUT, MAX, null));

        assertTrue(e.getMessage().contains("ftp"), e.getMessage());
        assertEquals(List.of(), updateFolderFiles());
    }
}
