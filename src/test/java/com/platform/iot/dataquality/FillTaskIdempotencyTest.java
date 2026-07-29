package com.platform.iot.dataquality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class FillTaskIdempotencyTest {

    private static final long MINUTE = 1_800_000L;
    private static final long HOUR = 3_600_000L;

    @Test
    void q1ReusesOneKeyForTheSameContinuousGap() {
        String first = FillTaskIdempotency.q1(
                "POINT001", MINUTE, MINUTE + 180_000L, "linear-v1");
        String second = FillTaskIdempotency.q1(
                "POINT001", MINUTE, MINUTE + 180_000L, "linear-v1");

        assertThat(first).isEqualTo(second)
                .isEqualTo("Q1:POINT001:1800000:1980000:linear-v1");
    }

    @Test
    void q1ChangesWhenRightEndpointOrAlgorithmVersionChanges() {
        String baseline = FillTaskIdempotency.q1(
                "POINT001", MINUTE, MINUTE + 180_000L, "linear-v1");

        assertThat(FillTaskIdempotency.q1(
                "POINT001", MINUTE, MINUTE + 240_000L, "linear-v1"))
                .isNotEqualTo(baseline);
        assertThat(FillTaskIdempotency.q1(
                "POINT001", MINUTE, MINUTE + 180_000L, "linear-v2"))
                .isNotEqualTo(baseline);
    }

    @Test
    void q2ReusesOneKeyWithinTheSameConfigVersionAndNaturalHour() {
        String first = FillTaskIdempotency.q2(
                "POINT001", "CONFIG001", 3, HOUR);
        String second = FillTaskIdempotency.q2(
                "POINT001", "CONFIG001", 3, HOUR);

        assertThat(first).isEqualTo(second)
                .isEqualTo("Q2:POINT001:CONFIG001:3:3600000");
        assertThat(FillTaskIdempotency.q2(
                "POINT001", "CONFIG001", 3, HOUR + 3_600_000L))
                .isNotEqualTo(first);
        assertThat(FillTaskIdempotency.q2(
                "POINT001", "CONFIG001", 4, HOUR))
                .isNotEqualTo(first);
    }

    @Test
    void regenerationReusesOnlyTheSameOldTaskPointAndRange() {
        String first = FillTaskIdempotency.regeneration(
                "TASK001", "POINT001", MINUTE, MINUTE + 120_000L);
        String second = FillTaskIdempotency.regeneration(
                "TASK001", "POINT001", MINUTE, MINUTE + 120_000L);

        assertThat(first).isEqualTo(second)
                .isEqualTo("REGEN:TASK001:POINT001:1800000:1920000");
        assertThat(FillTaskIdempotency.regeneration(
                "TASK002", "POINT001", MINUTE, MINUTE + 120_000L))
                .isNotEqualTo(first);
        assertThat(FillTaskIdempotency.regeneration(
                "TASK001", "POINT001", MINUTE, MINUTE + 180_000L))
                .isNotEqualTo(first);
    }

    @Test
    void recalculationQ1UsesAJobScopedNamespaceWithoutChangingStandardQ1() {
        String manual = FillTaskIdempotency.recalculationQ1(
                " JOB001 ", " POINT001 ",
                MINUTE, MINUTE + 180_000L, " linear-v1 ");

        assertThat(manual)
                .isEqualTo(
                        "RECALC_Q1:JOB001:POINT001:1800000:1980000:linear-v1")
                .isNotEqualTo(FillTaskIdempotency.q1(
                        "POINT001", MINUTE, MINUTE + 180_000L, "linear-v1"));
        assertThat(FillTaskIdempotency.recalculationQ1(
                "JOB002", "POINT001",
                MINUTE, MINUTE + 180_000L, "linear-v1"))
                .isNotEqualTo(manual);
    }

    @Test
    void recalculationQ2UsesAJobScopedNamespaceWithoutChangingStandardQ2() {
        String manual = FillTaskIdempotency.recalculationQ2(
                " JOB001 ", " POINT001 ", " CONFIG001 ", 3, HOUR);

        assertThat(manual)
                .isEqualTo(
                        "RECALC_Q2:JOB001:POINT001:CONFIG001:3:3600000")
                .isNotEqualTo(FillTaskIdempotency.q2(
                        "POINT001", "CONFIG001", 3, HOUR));
        assertThat(FillTaskIdempotency.recalculationQ2(
                "JOB002", "POINT001", "CONFIG001", 3, HOUR))
                .isNotEqualTo(manual);
    }

    @Test
    void recalculationKeysRejectInvalidIdentifiersVersionsTimesAndLengths() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                FillTaskIdempotency.recalculationQ1(
                        " ", "POINT001",
                        MINUTE, MINUTE + 60_000L, "linear-v1"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                FillTaskIdempotency.recalculationQ1(
                        "JOB001", " ", MINUTE, MINUTE + 60_000L, "linear-v1"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                FillTaskIdempotency.recalculationQ1(
                        "JOB001", "POINT001", MINUTE, MINUTE + 60_000L, " "));
        assertThatIllegalArgumentException().isThrownBy(() ->
                FillTaskIdempotency.recalculationQ1(
                        "JOB001", "POINT001",
                        MINUTE + 1L, MINUTE + 60_000L, "linear-v1"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                FillTaskIdempotency.recalculationQ1(
                        "JOB001", "POINT001",
                        MINUTE, MINUTE + 60_001L, "linear-v1"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                FillTaskIdempotency.recalculationQ1(
                        "JOB001", "POINT001",
                        MINUTE + 60_000L, MINUTE, "linear-v1"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                FillTaskIdempotency.recalculationQ2(
                        "JOB001", "POINT001", " ", 1, HOUR));
        assertThatIllegalArgumentException().isThrownBy(() ->
                FillTaskIdempotency.recalculationQ2(
                        "JOB001", "POINT001", "CONFIG001", 0, HOUR));
        assertThatIllegalArgumentException().isThrownBy(() ->
                FillTaskIdempotency.recalculationQ2(
                        "JOB001", "POINT001", "CONFIG001", 1,
                        HOUR + 60_000L));
        assertThatIllegalArgumentException().isThrownBy(() ->
                FillTaskIdempotency.recalculationQ1(
                        "J".repeat(100), "P".repeat(32),
                        MINUTE, MINUTE + 60_000L, "A".repeat(32)));
    }

    @Test
    void rejectsBlankIdentifiersMisalignedTimesAndOversizedKeys() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                FillTaskIdempotency.q1(" ", MINUTE, MINUTE + 60_000L, "v1"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                FillTaskIdempotency.q1("POINT001", MINUTE + 1L, MINUTE + 60_000L, "v1"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                FillTaskIdempotency.q2("POINT001", "CONFIG001", 1, HOUR + 60_000L));
        assertThatIllegalArgumentException().isThrownBy(() ->
                FillTaskIdempotency.regeneration(
                        "TASK001", "POINT001", MINUTE + 60_000L, MINUTE));
        assertThatIllegalArgumentException().isThrownBy(() ->
                FillTaskIdempotency.q1(
                        "POINT001", MINUTE, MINUTE + 60_000L, "v".repeat(160)));
    }
}
