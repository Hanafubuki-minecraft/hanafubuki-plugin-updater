package com.hanafubuki.updater.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An expected file hash: algorithm plus lowercase hex digest.
 */
public record Checksum(Algorithm algorithm, String hex) {

    private static final Pattern HEX = Pattern.compile("^[0-9a-f]+$");

    /** Supported hash algorithms. */
    public enum Algorithm {
        SHA1("SHA-1", 40),
        SHA256("SHA-256", 64),
        SHA512("SHA-512", 128);

        private final String jcaName;
        private final int hexLength;

        Algorithm(String jcaName, int hexLength) {
            this.jcaName = jcaName;
            this.hexLength = hexLength;
        }

        public String jcaName() {
            return jcaName;
        }

        public int hexLength() {
            return hexLength;
        }

        /** Finds the algorithm whose digest has the given hex length. */
        public static Optional<Algorithm> byHexLength(int length) {
            for (Algorithm a : values()) {
                if (a.hexLength == length) {
                    return Optional.of(a);
                }
            }
            return Optional.empty();
        }

        /** Parses names like "sha256", "SHA-256", "sha512". */
        public static Optional<Algorithm> byName(String name) {
            if (name == null) {
                return Optional.empty();
            }
            String n = name.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
            return switch (n) {
                case "sha1" -> Optional.of(SHA1);
                case "sha256" -> Optional.of(SHA256);
                case "sha512" -> Optional.of(SHA512);
                default -> Optional.empty();
            };
        }
    }

    public Checksum {
        if (algorithm == null || hex == null) {
            throw new IllegalArgumentException("Checksum needs an algorithm and a value");
        }
        hex = hex.trim().toLowerCase(Locale.ROOT);
        if (hex.length() != algorithm.hexLength() || !HEX.matcher(hex).matches()) {
            throw new IllegalArgumentException("Not a valid " + algorithm.jcaName() + " hex digest: '" + hex + "'");
        }
    }

    /**
     * Parses a checksum string. Accepts "sha256:abcd...", "SHA-512=abcd..." or a bare hex digest
     * (the algorithm is then picked by length: 40 = SHA-1, 64 = SHA-256, 128 = SHA-512).
     * Also accepts the first word of a sha256sum-style line ("abcd...  file.jar").
     *
     * @return the checksum, or empty if the text is not a recognisable digest
     */
    public static Optional<Checksum> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return Optional.empty();
        }
        String firstWord = t.split("\\s+")[0];
        int sep = indexOfSeparator(firstWord);
        try {
            if (sep > 0) {
                Optional<Algorithm> algorithm = Algorithm.byName(firstWord.substring(0, sep));
                if (algorithm.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(new Checksum(algorithm.get(), firstWord.substring(sep + 1)));
            }
            Optional<Algorithm> algorithm = Algorithm.byHexLength(firstWord.length());
            if (algorithm.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Checksum(algorithm.get(), firstWord));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static int indexOfSeparator(String s) {
        int colon = s.indexOf(':');
        int equals = s.indexOf('=');
        if (colon < 0) {
            return equals;
        }
        if (equals < 0) {
            return colon;
        }
        return Math.min(colon, equals);
    }

    /** Computes the hash of a file with the given algorithm, as lowercase hex. */
    public static String hash(Path file, Algorithm algorithm) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm.jcaName());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(algorithm.jcaName() + " is not available in this JVM", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** File identity for {@link #hashCached}: a changed size or modification time means a new file. */
    private record FileKey(Path path, Algorithm algorithm, long size, long modified) {
    }

    private static final int HASH_CACHE_SIZE = 256;
    private static final java.util.Map<FileKey, String> HASH_CACHE = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<FileKey, String> eldest) {
            return size() > HASH_CACHE_SIZE;
        }
    };

    /**
     * Like {@link #hash}, but remembers the result while the file's size and modification time
     * stay the same. Meant for installed plugin jars, which only change on a restart; hashing a
     * large jar on every check would waste CPU. Never use it to verify downloads.
     */
    public static String hashCached(Path file, Algorithm algorithm) throws IOException {
        java.nio.file.attribute.BasicFileAttributes attrs =
                Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
        FileKey key = new FileKey(file.toAbsolutePath().normalize(), algorithm, attrs.size(),
                attrs.lastModifiedTime().toMillis());
        synchronized (HASH_CACHE) {
            String cached = HASH_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        String hex = hash(file, algorithm);
        synchronized (HASH_CACHE) {
            HASH_CACHE.put(key, hex);
        }
        return hex;
    }

    /** True if the file's hash equals this checksum. */
    public boolean matches(Path file) throws IOException {
        return hex.equals(hash(file, algorithm));
    }

    @Override
    public String toString() {
        return algorithm.name().toLowerCase(Locale.ROOT) + ":" + hex;
    }
}
