package com.platform.iot.dataquality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecalculationJobIdempotencyTest {

    private static final long FROM = 1_800_000_000_000L;
    private static final long TO = 1_800_003_600_000L;

    @Test
    void voidJobTrimsTaskIdAndBuildsReadableKey() {
        assertThat(RecalculationJobIdempotency.voidJob(" TASK001 "))
                .isEqualTo("VOID_RECALC:TASK001");
    }

    @Test
    void rangeJobUsesCanonicalSortedDistinctRequestJson() {
        String key = RecalculationJobIdempotency.rangeJob(
                7L,
                " BLD001 ",
                List.of(" POINT002 ", "POINT001", "POINT002"),
                FROM,
                TO,
                " 修正测点绑定 ");

        assertThat(key).isEqualTo(
                "RANGE_RECALC:"
                        + "e4471f52de6e7bda4a428228d822924580a9ab60489d23fbf0273dc1e7232994");
    }

    @Test
    void rangeJobChangesWhenAnyAuditedInputChanges() {
        String baseline = RecalculationJobIdempotency.rangeJob(
                7L, "BLD001", List.of("POINT001"), FROM, TO, "原因");

        assertThat(RecalculationJobIdempotency.rangeJob(
                8L, "BLD001", List.of("POINT001"), FROM, TO, "原因"))
                .isNotEqualTo(baseline);
        assertThat(RecalculationJobIdempotency.rangeJob(
                7L, "BLD002", List.of("POINT001"), FROM, TO, "原因"))
                .isNotEqualTo(baseline);
        assertThat(RecalculationJobIdempotency.rangeJob(
                7L, "BLD001", List.of("POINT002"), FROM, TO, "原因"))
                .isNotEqualTo(baseline);
        assertThat(RecalculationJobIdempotency.rangeJob(
                7L, "BLD001", List.of("POINT001"), FROM + 60_000L, TO, "原因"))
                .isNotEqualTo(baseline);
        assertThat(RecalculationJobIdempotency.rangeJob(
                7L, "BLD001", List.of("POINT001"), FROM, TO + 60_000L, "原因"))
                .isNotEqualTo(baseline);
        assertThat(RecalculationJobIdempotency.rangeJob(
                7L, "BLD001", List.of("POINT001"), FROM, TO, "其他原因"))
                .isNotEqualTo(baseline);
    }

    @Test
    void rejectsInputsThatCannotFormAnAuditableCanonicalRequest() {
        assertThatThrownBy(() -> RecalculationJobIdempotency.voidJob(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecalculationJobIdempotency.rangeJob(
                0L, "BLD001", List.of("POINT001"), FROM, TO, "原因"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecalculationJobIdempotency.rangeJob(
                7L, " ", List.of("POINT001"), FROM, TO, "原因"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecalculationJobIdempotency.rangeJob(
                7L, "BLD001", List.of(" "), FROM, TO, "原因"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecalculationJobIdempotency.rangeJob(
                7L, "BLD001", List.of("POINT001"), FROM + 1L, TO, "原因"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecalculationJobIdempotency.rangeJob(
                7L, "BLD001", List.of("POINT001"), TO, FROM, "原因"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecalculationJobIdempotency.rangeJob(
                7L, "BLD001", List.of("POINT001"), FROM, TO, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
