package com.hanafubuki.updater.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import com.hanafubuki.updater.config.PluginEntry;
import com.hanafubuki.updater.util.Checksum;
import com.hanafubuki.updater.util.PluginDescriptor;
import com.hanafubuki.updater.util.VersionUtils;

/**
 * Jars from a local directory or a single file (also network mounts such as SMB or NFS).
 *
 * <p>Only jars whose plugin.yml/paper-plugin.yml name equals the plugin name are considered.
 * The version comes from the descriptor, or from the file name when {@code pattern}
 * (a regex whose first group is the version) is set. A sidecar file
 * {@code <jar>.sha256}, {@code .sha512} or {@code .sha1} is used as checksum when present;
 * {@code checksum} can be set for a single-file path.
 */
public final class LocalSource implements UpdateSource {

    @Override
    public String id() {
        return "local";
    }

    @Override
    public List<String> validate(PluginEntry entry) {
        List<String> problems = new ArrayList<>();
        String path = entry.string("path");
        if (path == null) {
            problems.add("missing 'path' (directory or jar file)");
        }
        String pattern = entry.string("pattern");
        if (pattern != null) {
            try {
                Pattern p = Pattern.compile(pattern);
                if (p.matcher("").groupCount() < 1) {
                    problems.add("pattern must have a capturing group for the version, e.g. MyPlugin-(.+)\\.jar");
                }
            } catch (PatternSyntaxException e) {
                problems.add("pattern is not a valid regex: " + e.getDescription());
            }
        }
        String checksum = entry.string("checksum");
        if (checksum != null && Checksum.parse(checksum).isEmpty()) {
            problems.add("checksum is not a SHA-1, SHA-256 or SHA-512 hex digest");
        }
        return problems;
    }

    /** A jar found in the source folder. */
    record Found(String version, Path file) {
    }

    @Override
    public Optional<UpdateCandidate> findLatest(UpdateRequest request) throws IOException {
        PluginEntry entry = request.entry();
        Path source = Path.of(entry.string("path"));
        if (!Files.exists(source)) {
            throw new IOException("Local path does not exist: " + source);
        }
        String patternText = entry.string("pattern");
        Pattern pattern = patternText == null ? null : Pattern.compile(patternText);

        List<Path> jars = new ArrayList<>();
        if (Files.isRegularFile(source)) {
            jars.add(source);
        } else {
            try (Stream<Path> files = Files.list(source)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .forEach(jars::add);
            }
        }

        List<Found> found = new ArrayList<>();
        for (Path jar : jars) {
            Optional<PluginDescriptor> descriptor = PluginDescriptor.read(jar);
            if (descriptor.isEmpty() || !descriptor.get().name().equalsIgnoreCase(request.pluginName())) {
                continue;
            }
            String version = descriptor.get().version();
            if (pattern != null) {
                Matcher m = pattern.matcher(jar.getFileName().toString());
                if (!m.find()) {
                    continue;
                }
                version = m.groupCount() >= 1 ? m.group(1) : m.group();
            }
            if (version != null && !version.isBlank()) {
                found.add(new Found(version, jar));
            }
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }
        found.sort(Comparator.comparing(Found::version, VersionUtils.COMPARATOR).reversed());
        Found newest = found.get(0);

        Checksum checksum = sidecarChecksum(newest.file());
        if (checksum == null && Files.isRegularFile(source) && entry.string("checksum") != null) {
            checksum = Checksum.parse(entry.string("checksum")).orElse(null);
        }
        List<String> notes = checksum == null ? List.of("No checksum file next to " + newest.file().getFileName()) : List.of();

        UpdateCandidate.Freshness freshness = UpdateCandidate.Freshness.UNKNOWN;
        String installedHash = Checksum.hashCached(request.installed().jar(), Checksum.Algorithm.SHA256);
        if (installedHash.equals(Checksum.hash(newest.file(), Checksum.Algorithm.SHA256))) {
            freshness = UpdateCandidate.Freshness.SAME;
        }
        return Optional.of(new UpdateCandidate(newest.version(), newest.file().toUri(), checksum, Map.of(), freshness,
                null, notes));
    }

    private static Checksum sidecarChecksum(Path jar) throws IOException {
        for (String ext : List.of(".sha512", ".sha256", ".sha1")) {
            Path sidecar = jar.resolveSibling(jar.getFileName() + ext);
            if (Files.isRegularFile(sidecar) && Files.size(sidecar) < 64 * 1024) {
                Optional<Checksum> c = Checksum.parse(Files.readString(sidecar));
                if (c.isPresent()) {
                    return c.get();
                }
                throw new IOException("Checksum file " + sidecar.getFileName() + " does not contain a valid digest");
            }
        }
        return null;
    }
}
