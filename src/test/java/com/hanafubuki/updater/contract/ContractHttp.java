package com.hanafubuki.updater.contract;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import com.hanafubuki.updater.config.PluginEntry;
import com.hanafubuki.updater.http.HttpCache;
import com.hanafubuki.updater.http.HttpService;
import com.hanafubuki.updater.http.RetryPolicy;
import com.hanafubuki.updater.source.InstalledPlugin;
import com.hanafubuki.updater.source.UpdateRequest;
import com.hanafubuki.updater.testing.TestJars;

/**
 * Shared setup for contract tests: a real {@link HttpService} that talks to the public APIs.
 */
final class ContractHttp {

    static final String USER_AGENT = "ByteMe6/hanafubuki-plugin-updater/contract-test";
    static final String MINECRAFT_VERSION = "1.21.9";
    static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(120);
    static final long MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024;

    private ContractHttp() {
    }

    static HttpService service() {
        return new HttpService(HttpService.newClient(Duration.ofSeconds(10)), USER_AGENT, RetryPolicy.DEFAULT,
                Thread::sleep, HttpCache.inMemory(64), null);
    }

    /**
     * Writes a small fake plugin jar into {@code pluginsDir} that stands for the installed plugin.
     * Its hash is unknown to every source, as for a self-built jar.
     */
    static InstalledPlugin installed(Path pluginsDir, String pluginName, String fileName) throws IOException {
        Path jar = TestJars.write(pluginsDir, fileName, TestJars.plugin(pluginName, "0.0.1-contract"));
        return new InstalledPlugin(pluginName, "0.0.1-contract", jar);
    }

    static UpdateRequest request(String source, InstalledPlugin installed, Map<String, Object> options) {
        return new UpdateRequest(new PluginEntry(installed.name(), source, options), installed, MINECRAFT_VERSION);
    }
}
