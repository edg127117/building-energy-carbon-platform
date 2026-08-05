package com.platform.hvac.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HvacSnapshotFreshnessPolicyTest {

    private static final long MINUTE = 1_800_000_000_000L;
    private final HvacSnapshotFreshnessPolicy policy =
            new HvacSnapshotFreshnessPolicy(30, 1);

    @Test
    void keepsOneDueMinuteOfTolerance() {
        assertThat(policy.status(MINUTE, MINUTE + 150_000L))
                .isEqualTo("NORMAL");
    }

    @Test
    void marksPointStaleWhenItFallsTwoDueMinutesBehind() {
        assertThat(policy.status(MINUTE, MINUTE + 210_000L))
                .isEqualTo("STALE");
    }

    @Test
    void advancesOnlyAfterTheThirtySecondFreezeBoundary() {
        assertThat(policy.status(MINUTE, MINUTE + 209_999L))
                .isEqualTo("NORMAL");
        assertThat(policy.status(MINUTE, MINUTE + 210_000L))
                .isEqualTo("STALE");
    }

    @Test
    void rejectsNegativeTimeConfiguration() {
        assertThatThrownBy(() -> new HvacSnapshotFreshnessPolicy(-1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HvacSnapshotFreshnessPolicy(30, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
