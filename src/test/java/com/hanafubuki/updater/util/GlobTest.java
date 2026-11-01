package com.hanafubuki.updater.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GlobTest {

    @ParameterizedTest(name = "{0} matches {1}")
    @CsvSource({
            "*.jar, LuckPerms-Bukkit-5.4.141.jar",
            "LuckPerms-Bukkit-*.jar, LuckPerms-Bukkit-5.4.141.jar",
            "luckperms-bukkit-*.JAR, LuckPerms-Bukkit-5.4.141.jar",
            "Plugin-?.jar, Plugin-1.jar",
            "*, anything at all",
            "*-paper-*.jar, worldedit-paper-7.3.0.jar",
            "exact.jar, exact.jar",
            // empty match for *
            "Plugin*.jar, Plugin.jar",
            // non-ASCII case folding
            "ÄPFEL-*.jar, äpfel-1.jar",
    })
    void matches(String glob, String name) {
        assertTrue(Glob.matches(glob, name));
    }

    @ParameterizedTest(name = "{0} does not match {1}")
    @CsvSource({
            "*.jar, plugin.jar.sha256",
            "Plugin-?.jar, Plugin-10.jar",
            "Plugin-?.jar, Plugin-.jar",
            "*-bukkit-*.jar, worldedit-paper-7.3.0.jar",
            // the whole name must match, not a part of it
            "plugin, my-plugin.jar",
            "exact.jar, exact.jar.bak",
    })
    void doesNotMatch(String glob, String name) {
        assertFalse(Glob.matches(glob, name));
    }

    @Test
    void regexCharactersAreLiteral() {
        assertTrue(Glob.matches("a.b+(c)[1]{2}$^|\\.jar", "a.b+(c)[1]{2}$^|\\.jar"));
        // '.' is not a wildcard
        assertFalse(Glob.matches("a.jar", "abjar"));
        // '\Q...\E' quoting in the input does not break the pattern
        assertTrue(Glob.matches("x\\E*.jar", "x\\Eyz.jar"));
    }

    @Test
    void emptyOrNullPatternIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Glob.toPattern(""));
        assertThrows(IllegalArgumentException.class, () -> Glob.toPattern(null));
    }

    @Test
    void toPatternIsCaseInsensitive() {
        assertEquals(true, Glob.toPattern("ABC*").matcher("abcdef").matches());
    }
}
