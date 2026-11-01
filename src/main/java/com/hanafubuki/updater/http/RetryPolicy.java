package com.hanafubuki.updater.http;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with jitter: delay = min(base * 2^attempt + jitter, max).
 *
 * @param maxRetries  retries after the first attempt (0 = no retries)
 * @param baseDelayMs first delay in milliseconds
 * @param maxDelayMs  upper bound for one delay
 */
public record RetryPolicy(int maxRetries, long baseDelayMs, long maxDelayMs) {

    public static final RetryPolicy DEFAULT = new RetryPolicy(3, 1000, 10_000);
    public static final RetryPolicy NONE = new RetryPolicy(0, 0, 0);

    public RetryPolicy {
        if (maxRetries < 0 || maxRetries > 10) {
            throw new IllegalArgumentException("max_retries must be between 0 and 10, got " + maxRetries);
        }
        if (baseDelayMs < 0 || maxDelayMs < 0) {
            throw new IllegalArgumentException("Retry delays must not be negative");
        }
        if (maxDelayMs < baseDelayMs) {
            maxDelayMs = baseDelayMs;
        }
    }

    /** Delay before retry number {@code attempt} (0-based). */
    public long delayMs(int attempt) {
        if (baseDelayMs == 0) {
            return 0;
        }
        long exponential = baseDelayMs * (1L << Math.min(attempt, 20));
        long jitter = ThreadLocalRandom.current().nextLong(Math.min(baseDelayMs, 1000) + 1);
        return Math.min(exponential + jitter, maxDelayMs);
    }
}
