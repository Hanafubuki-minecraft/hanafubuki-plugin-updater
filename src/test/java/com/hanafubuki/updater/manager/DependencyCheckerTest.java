package com.hanafubuki.updater.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hanafubuki.updater.testing.TestJars;
import com.hanafubuki.updater.util.PluginDescriptor;

class DependencyCheckerTest {

    private static final Set<String> INSTALLED = Set.of("Vault", "LuckPerms", "ProtocolLib");

    @TempDir
    Path dir;

    private static PluginDescriptor descriptor(List<String> depend, String apiVersion) {
        return new PluginDescriptor("Shop", "2.0", null, apiVersion, depend, false);
    }

    @Test
    void satisfiedDependenciesGiveNoProblems() {
        assertEquals(List.of(), DependencyChecker.check(descriptor(List.of("Vault", "LuckPerms"), "1.21"), INSTALLED, "1.21.9"));
        assertEquals(List.of(), DependencyChecker.check(descriptor(List.of(), null), INSTALLED, "1.21.9"));
    }

    @Test
    void dependencyNamesIgnoreCase() {
        assertEquals(List.of(), DependencyChecker.check(descriptor(List.of("vault", "PROTOCOLLIB"), null), INSTALLED, "1.21.9"));
    }

    @Test
    void missingDependenciesAreListed() {
        List<String> problems = DependencyChecker.check(descriptor(List.of("Vault", "PlaceholderAPI", "Citizens"), null), INSTALLED, "1.21.9");

        assertEquals(List.of("needs plugins that are not installed: PlaceholderAPI, Citizens"), problems);
    }

    @Test
    void newerApiVersionThanTheServerIsAProblem() {
        List<String> problems = DependencyChecker.check(descriptor(List.of(), "1.21.10"), INSTALLED, "1.21.9");

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("1.21.10") && problems.get(0).contains("1.21.9"), problems.get(0));
    }

    @Test
    void olderOrEqualApiVersionIsFine() {
        assertEquals(List.of(), DependencyChecker.check(descriptor(List.of(), "1.13"), INSTALLED, "1.21.9"));
        assertEquals(List.of(), DependencyChecker.check(descriptor(List.of(), "1.21"), INSTALLED, "1.21.9"));
        assertEquals(List.of(), DependencyChecker.check(descriptor(List.of(), "1.21.9"), INSTALLED, "1.21.9"));
        assertEquals(List.of(), DependencyChecker.check(descriptor(List.of(), " "), INSTALLED, "1.21.9"));
        assertEquals(List.of(), DependencyChecker.check(descriptor(List.of(), "1.22"), INSTALLED, null));
    }

    @Test
    void bothProblemsAreReported() {
        assertEquals(2, DependencyChecker.check(descriptor(List.of("Citizens"), "1.22"), INSTALLED, "1.21.9").size());
    }

    @Test
    void worksWithDescriptorsReadFromJars() throws IOException {
        Path jar = TestJars.write(dir, "Shop.jar", TestJars.plugin("Shop", "2.0", List.of("Vault", "Essentials"), "1.21"));
        PluginDescriptor d = PluginDescriptor.read(jar).orElseThrow();

        assertEquals(List.of("needs plugins that are not installed: Essentials"), DependencyChecker.check(d, INSTALLED, "1.21.9"));
    }
}
