package com.hanafubuki.updater.source;

import java.time.Duration;

import com.hanafubuki.updater.http.RetryPolicy;

/**
 * Timeouts and retry policy shared by all requests of one source.
 */
public record SourceSettings(Duration apiTimeout, Duration downloadTimeout, RetryPolicy retry) {

    public static final SourceSettings DEFAULT =
            new SourceSettings(Duration.ofSeconds(10), Duration.ofSeconds(120), RetryPolicy.DEFAULT);
}
