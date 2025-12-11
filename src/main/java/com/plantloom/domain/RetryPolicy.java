package com.plantloom.domain;

/**
 * Retry behaviour for a stage. {@code maxAttempts} counts the first attempt, so 1 means "no retry".
 */
public record RetryPolicy(int maxAttempts, long backoffMs, double multiplier, long maxBackoffMs) {

    public static final RetryPolicy NONE = new RetryPolicy(1, 0, 1.0, 0);

    public RetryPolicy {
        if (maxAttempts < 1) {
            maxAttempts = 1;
        }
        if (backoffMs < 0) {
            backoffMs = 0;
        }
        if (multiplier < 1.0) {
            multiplier = 1.0;
        }
        if (maxBackoffMs <= 0) {
            maxBackoffMs = Math.max(backoffMs, 30_000);
        }
    }

    /** Delay before the given (1-based) retry attempt, i.e. attempt 2 waits {@code backoffMs}. */
    public long delayBeforeAttempt(int attempt) {
        if (attempt <= 1) {
            return 0;
        }
        double delay = backoffMs * Math.pow(multiplier, attempt - 2);
        return (long) Math.min(delay, maxBackoffMs);
    }

    public boolean hasAttemptsAfter(int attempt) {
        return attempt < maxAttempts;
    }
}
