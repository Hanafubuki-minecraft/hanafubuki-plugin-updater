package com.hanafubuki.updater.manager;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.hanafubuki.updater.http.HttpService;
import com.hanafubuki.updater.source.InstalledPlugin;
import com.hanafubuki.updater.source.UpdateCandidate;
import com.hanafubuki.updater.util.PluginDescriptor;

/**
 * Downloads a candidate into the Bukkit update folder and verifies it.
 *
 * <p>Never touches the running jar in plugins/. The file is written as a temporary
 * {@code .part} file inside the update folder, verified (checksum, plugin name, optional extra
 * check), then atomically renamed to the installed jar's file name. Paper replaces the plugin
 * on the next restart. The temporary file is always removed.
 */
public final class Installer {

    /** Extra verification of the downloaded jar, e.g. dependency checks. Throw to reject. */
    @FunctionalInterface
    public interface Verifier {
        void verify(PluginDescriptor descriptor, Path file) throws IOException;
    }

    private final HttpService http;

    public Installer(HttpService http) {
        this.http = http;
    }

    /** Where the staged file for this plugin lives. */
    public static Path stagedPath(InstalledPlugin installed, Path updateFolder) {
        return updateFolder.resolve(installed.jar().getFileName().toString());
    }

    /**
     * Downloads, verifies and stages the candidate.
     *
     * @return the staged file
     */
    public Path stage(InstalledPlugin installed, UpdateCandidate candidate, Path updateFolder, Duration timeout,
                      long maxBytes, Verifier verifier) throws IOException {
        Files.createDirectories(updateFolder);
        Path target = stagedPath(installed, updateFolder);
        Path tmp = updateFolder.resolve("." + installed.jar().getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            fetch(candidate.downloadUrl(), candidate, tmp, timeout, maxBytes);

            if (candidate.checksum() != null && !candidate.checksum().matches(tmp)) {
                throw new IOException("Checksum mismatch: expected " + candidate.checksum() + ". The file was rejected");
            }
            Optional<PluginDescriptor> descriptor = PluginDescriptor.read(tmp);
            if (descriptor.isEmpty()) {
                throw new IOException("The downloaded file is not a plugin jar (no plugin.yml or paper-plugin.yml)");
            }
            if (!descriptor.get().name().equalsIgnoreCase(installed.name())) {
                throw new IOException("The downloaded jar is the plugin '" + descriptor.get().name()
                        + "', not '" + installed.name() + "'. The file was rejected");
            }
            if (verifier != null) {
                verifier.verify(descriptor.get(), tmp);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void fetch(URI url, UpdateCandidate candidate, Path tmp, Duration timeout, long maxBytes) throws IOException {
        if ("file".equalsIgnoreCase(url.getScheme())) {
            Path source = Path.of(url);
            if (Files.size(source) > maxBytes) {
                throw new IOException("File is larger than the limit of " + maxBytes + " bytes");
            }
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        if (!"https".equalsIgnoreCase(url.getScheme()) && !"http".equalsIgnoreCase(url.getScheme())) {
            throw new IOException("Unsupported download URL scheme: " + url.getScheme());
        }
        http.download(url, candidate.downloadHeaders(), tmp, timeout, maxBytes);
    }
}
