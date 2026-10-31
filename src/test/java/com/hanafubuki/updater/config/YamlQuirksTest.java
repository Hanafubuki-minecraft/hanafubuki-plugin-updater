package com.hanafubuki.updater.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.hanafubuki.updater.manager.Schedule;

/**
 * YAML 1.1 (used by Bukkit) turns some unquoted values into other types. Admins write these
 * values without quotes, so the parser has to accept them.
 */
class YamlQuirksTest {

    private static SettingsParser.Result parse(String yaml) {
        Map<String, Object> root = new Yaml().load(yaml);
        return SettingsParser.parse(root, name -> null);
    }

    @Test
    void unquotedTimeIsReadAsBase60Number() {
        Object raw = ((Map<?, ?>) ((Map<?, ?>) new Yaml().load("schedule:\n  time: 4:30\n")).get("schedule")).get("time");
        assertEquals(270, raw, "precondition: SnakeYAML reads 4:30 as 270");

        SettingsParser.Result r = parse("schedule:\n  mode: daily\n  time: 4:30\n");
        assertEquals(List.of(), r.problems());
        assertEquals(LocalTime.of(4, 30), r.settings().schedule().time());

        SettingsParser.Result late = parse("schedule:\n  mode: daily\n  time: 23:05\n");
        assertEquals(LocalTime.of(23, 5), late.settings().schedule().time());
    }

    @Test
    void unquotedOffIsAccepted() {
        SettingsParser.Result r = parse("schedule:\n  mode: off\ndependencies:\n  check: off\ngithub:\n  signatures: off\n");
        assertEquals(List.of(), r.problems());
        assertEquals(Schedule.Mode.OFF, r.settings().schedule().mode());
        assertEquals(UpdaterSettings.DependencyMode.OFF, r.settings().dependencies());
    }

    @Test
    void prefixKeepsTrailingSpace() {
        SettingsParser.Result r = parse("style:\n  prefix: \"<red>X</red> \"\n");
        assertEquals("<red>X</red> ", r.settings().style().prefix());
    }
}
