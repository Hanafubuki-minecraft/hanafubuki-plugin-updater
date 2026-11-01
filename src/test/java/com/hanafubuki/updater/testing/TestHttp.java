package com.hanafubuki.updater.testing;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.hanafubuki.updater.http.HttpCache;
import com.hanafubuki.updater.http.HttpService;
import com.hanafubuki.updater.http.RetryPolicy;
import com.hanafubuki.updater.source.SourceSettings;

/**
 * HttpService instances for tests: no real sleeping, recorded delays.
 */
public final class TestHttp {

    /** Delays requested by retries, in ms. */
    public static final List<Long> SLEEPS = new CopyOnWriteArrayList<>();

    public static final String USER_AGENT = "ByteMe6/hanafubuki-plugin-updater/test";

    /** Fast settings: 3 retries without waiting. */
    public static final SourceSettings SETTINGS =
            new SourceSettings(Duration.ofSeconds(5), Duration.ofSeconds(10), new RetryPolicy(3, 0, 0));

    private TestHttp() {
    }

    public static HttpService service() {
        return service(HttpCache.none());
    }

    public static HttpService service(HttpCache cache) {
        return new HttpService(HttpService.newClient(Duration.ofSeconds(5)), USER_AGENT, new RetryPolicy(3, 0, 0),
                SLEEPS::add, cache, null);
    }
}
