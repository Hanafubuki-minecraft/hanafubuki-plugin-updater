package com.hanafubuki.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Every permission must be declared in plugin.yml, default to op only, and be part of the * node. */
class PluginYmlTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pluginYml() throws Exception {
        try (InputStream in = PluginYmlTest.class.getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(in, "plugin.yml on the classpath");
            return new Yaml().load(in);
        }
    }

    private static Set<String> constants() throws IllegalAccessException {
        Set<String> out = new TreeSet<>();
        for (Field f : Permissions.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                out.add((String) f.get(null));
            }
        }
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyPermissionIsDeclaredOpOnlyAndInTheWildcard() throws Exception {
        Map<String, Object> permissions = (Map<String, Object>) pluginYml().get("permissions");
        Set<String> constants = constants();
        assertTrue(constants.contains("hanafubuki.updater.confirm"));

        Set<String> declared = new TreeSet<>(permissions.keySet());
        declared.remove("hanafubuki.updater.*");
        assertEquals(constants, declared, "plugin.yml and Permissions.java must list the same nodes");

        for (Map.Entry<String, Object> e : permissions.entrySet()) {
            Map<String, Object> node = (Map<String, Object>) e.getValue();
            assertEquals("op", node.get("default"), e.getKey() + " must default to op");
        }
        Map<String, Object> children = (Map<String, Object>) ((Map<String, Object>) permissions.get("hanafubuki.updater.*")).get("children");
        assertEquals(constants, new TreeSet<>(children.keySet()));
        children.forEach((k, v) -> assertEquals(Boolean.TRUE, v, k));
    }

    @Test
    @SuppressWarnings("unchecked")
    void commandNeedsTheUseNode() throws Exception {
        Map<String, Object> commands = (Map<String, Object>) pluginYml().get("commands");
        Map<String, Object> command = (Map<String, Object>) commands.get("updateplugins");
        assertEquals(Permissions.USE, command.get("permission"));
    }
}
