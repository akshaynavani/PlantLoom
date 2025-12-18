package com.plantloom.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void backoffGrowsExponentiallyAndIsCapped() {
        RetryPolicy policy = new RetryPolicy(5, 100, 2.0, 500);

        assertThat(policy.delayBeforeAttempt(1)).isZero();
        assertThat(policy.delayBeforeAttempt(2)).isEqualTo(100);
        assertThat(policy.delayBeforeAttempt(3)).isEqualTo(200);
        assertThat(policy.delayBeforeAttempt(4)).isEqualTo(400);
        assertThat(policy.delayBeforeAttempt(5)).isEqualTo(500);
    }

    @Test
    void normalisesInvalidValues() {
        RetryPolicy policy = new RetryPolicy(0, -5, 0.5, 0);

        assertThat(policy.maxAttempts()).isEqualTo(1);
        assertThat(policy.backoffMs()).isZero();
        assertThat(policy.multiplier()).isEqualTo(1.0);
        assertThat(policy.hasAttemptsAfter(1)).isFalse();
    }

    @Test
    void countsRemainingAttempts() {
        RetryPolicy policy = new RetryPolicy(3, 10, 1.0, 0);

        assertThat(policy.hasAttemptsAfter(1)).isTrue();
        assertThat(policy.hasAttemptsAfter(2)).isTrue();
        assertThat(policy.hasAttemptsAfter(3)).isFalse();
    }
}
