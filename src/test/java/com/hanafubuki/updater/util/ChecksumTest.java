package com.hanafubuki.updater.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ChecksumTest {

    // Digests of the ASCII text "hello".
    private static final String SHA1_HELLO = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d";
    private static final String SHA256_HELLO = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    private static final String SHA512_HELLO = "9b71d224bd62f3785d96d46ad3ea3d73319bfbc2890caadae2dff72519673ca7"
            + "2323c3d99ba5c11d7c7acc6e14b8c5da0c4663475c2e5c3adef46f73bcdec043";

    @TempDir
    Path dir;

    private Path helloFile() throws IOException {
        return Files.writeString(dir.resolve("hello.txt"), "hello", StandardCharsets.US_ASCII);
    }

    @Test
    void parsesPrefixWithColon() {
        Checksum c = Checksum.parse("sha256:" + SHA256_HELLO).orElseThrow();
        assertEquals(Checksum.Algorithm.SHA256, c.algorithm());
        assertEquals(SHA256_HELLO, c.hex());
    }

    @Test
    void parsesPrefixWithEqualsAndDashedUpperCaseName() {
        Checksum c = Checksum.parse("SHA-512=" + SHA512_HELLO).orElseThrow();
        assertEquals(Checksum.Algorithm.SHA512, c.algorithm());
        assertEquals(SHA512_HELLO, c.hex());
    }

    @Test
    void parsesUnderscoreName() {
        assertEquals(Checksum.Algorithm.SHA1, Checksum.parse("sha_1:" + SHA1_HELLO).orElseThrow().algorithm());
    }

    @Test
    void bareHexPicksAlgorithmByLength() {
        assertEquals(Checksum.Algorithm.SHA1, Checksum.parse(SHA1_HELLO).orElseThrow().algorithm());
        assertEquals(Checksum.Algorithm.SHA256, Checksum.parse(SHA256_HELLO).orElseThrow().algorithm());
        assertEquals(Checksum.Algorithm.SHA512, Checksum.parse(SHA512_HELLO).orElseThrow().algorithm());
    }

    @Test
    void upperCaseHexIsNormalisedToLowerCase() {
        Checksum c = Checksum.parse("  " + SHA256_HELLO.toUpperCase() + "  ").orElseThrow();
        assertEquals(SHA256_HELLO, c.hex());
    }

    @Test
    void sha256sumLineUsesFirstWord() {
        Checksum c = Checksum.parse(SHA256_HELLO + "  MyPlugin-1.2.3.jar\n").orElseThrow();
        assertEquals(Checksum.Algorithm.SHA256, c.algorithm());
        assertEquals(SHA256_HELLO, c.hex());
        // Binary-mode marker of sha256sum ("*file") is part of the second word, not the digest.
        assertEquals(SHA256_HELLO, Checksum.parse(SHA256_HELLO + " *MyPlugin.jar").orElseThrow().hex());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "   ",
            "not a checksum",
            "abc123",
            // unknown algorithm name
            "md5:5d41402abc4b2a76b9719d911017c592",
            // prefix and length do not agree
            "sha256:" + SHA1_HELLO,
            "sha1:" + SHA256_HELLO,
            // right length, not hex
            "zzf4c61ddcc5e8a2dabede0f3b482cd9aea9434d",
            // bare digest of an unsupported length (MD5)
            "5d41402abc4b2a76b9719d911017c592",
            // separator but nothing before it
            ":" + SHA256_HELLO,
            // empty digest after the separator
            "sha256:",
    })
    void invalidInputIsEmpty(String text) {
        assertEquals(Optional.empty(), Checksum.parse(text));
    }

    @Test
    void constructorValidates() {
        assertThrows(IllegalArgumentException.class, () -> new Checksum(null, SHA1_HELLO));
        assertThrows(IllegalArgumentException.class, () -> new Checksum(Checksum.Algorithm.SHA1, null));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Checksum(Checksum.Algorithm.SHA256, SHA1_HELLO));
        assertTrue(e.getMessage().contains("SHA-256"), e.getMessage());
    }

    @Test
    void algorithmLookups() {
        assertEquals(Optional.of(Checksum.Algorithm.SHA256), Checksum.Algorithm.byName(" Sha-256 "));
        assertEquals(Optional.empty(), Checksum.Algorithm.byName(null));
        assertEquals(Optional.empty(), Checksum.Algorithm.byName("crc32"));
        assertEquals(Optional.of(Checksum.Algorithm.SHA512), Checksum.Algorithm.byHexLength(128));
        assertEquals(Optional.empty(), Checksum.Algorithm.byHexLength(32));
        assertEquals("SHA-1", Checksum.Algorithm.SHA1.jcaName());
        assertEquals(40, Checksum.Algorithm.SHA1.hexLength());
    }

    @Test
    void hashComputesKnownDigests() throws IOException {
        Path file = helloFile();
        assertEquals(SHA1_HELLO, Checksum.hash(file, Checksum.Algorithm.SHA1));
        assertEquals(SHA256_HELLO, Checksum.hash(file, Checksum.Algorithm.SHA256));
        assertEquals(SHA512_HELLO, Checksum.hash(file, Checksum.Algorithm.SHA512));
    }

    @Test
    void hashOfLargeFileSpanningSeveralBuffers() throws IOException {
        byte[] data = new byte[200 * 1024 + 7];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31);
        }
        Path file = Files.write(dir.resolve("big.bin"), data);
        String expected;
        try {
            expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        assertEquals(expected, Checksum.hash(file, Checksum.Algorithm.SHA256));
    }

    @Test
    void matches() throws IOException {
        Path file = helloFile();
        assertTrue(Checksum.parse("sha256:" + SHA256_HELLO).orElseThrow().matches(file));
        assertTrue(Checksum.parse(SHA1_HELLO.toUpperCase()).orElseThrow().matches(file));
        Files.writeString(file, "hello!");
        assertFalse(Checksum.parse("sha256:" + SHA256_HELLO).orElseThrow().matches(file));
    }

    @Test
    void matchesMissingFileThrows() {
        Checksum c = Checksum.parse(SHA256_HELLO).orElseThrow();
        assertThrows(NoSuchFileException.class, () -> c.matches(dir.resolve("missing.jar")));
    }

    @Test
    void toStringRoundTrips() {
        Checksum c = Checksum.parse("SHA-256=" + SHA256_HELLO.toUpperCase()).orElseThrow();
        assertEquals("sha256:" + SHA256_HELLO, c.toString());
        assertEquals(c, Checksum.parse(c.toString()).orElseThrow());
    }

    @Test
    void hashCachedFollowsFileChanges(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path f = dir.resolve("a.jar");
        java.nio.file.Files.writeString(f, "one");
        java.nio.file.attribute.FileTime t = java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L);
        java.nio.file.Files.setLastModifiedTime(f, t);
        String first = Checksum.hashCached(f, Checksum.Algorithm.SHA512);
        assertEquals(Checksum.hash(f, Checksum.Algorithm.SHA512), first);
        assertEquals(first, Checksum.hashCached(f, Checksum.Algorithm.SHA512));
        assertEquals(Checksum.hash(f, Checksum.Algorithm.SHA256), Checksum.hashCached(f, Checksum.Algorithm.SHA256),
                "algorithm is part of the key");

        // Same size, new modification time: hashed again.
        java.nio.file.Files.writeString(f, "two");
        java.nio.file.Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(1_700_000_001_000L));
        assertEquals(Checksum.hash(f, Checksum.Algorithm.SHA512), Checksum.hashCached(f, Checksum.Algorithm.SHA512));

        // New size, same modification time: hashed again.
        java.nio.file.Files.writeString(f, "three");
        java.nio.file.Files.setLastModifiedTime(f, t);
        assertEquals(Checksum.hash(f, Checksum.Algorithm.SHA512), Checksum.hashCached(f, Checksum.Algorithm.SHA512));
    }
}
