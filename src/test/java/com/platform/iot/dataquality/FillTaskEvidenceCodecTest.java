package com.platform.iot.dataquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.dataquality.model.FillSourceType;
import com.platform.iot.dataquality.model.FillTaskEvidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class FillTaskEvidenceCodecTest {

    private FillTaskEvidenceCodec codec;

    @BeforeEach
    void setUp() {
        codec = new FillTaskEvidenceCodec(new ObjectMapper());
    }

    @Test
    void typicalEvidenceRoundTripKeepsEveryField() {
        FillTaskEvidence.Typical evidence = new FillTaskEvidence.Typical(
                "CONFIG001",
                2,
                new BigDecimal("18.75"),
                "℃",
                1_800_000L,
                7_200_000L,
                3_600_000L,
                "typical-v1",
                List.of(new FillTaskEvidence.MinuteSegment(
                        3_600_000L, 3_720_000L)));

        String json = codec.encode(FillSourceType.TYPICAL_VALUE, evidence);

        assertThat(codec.decode(FillSourceType.TYPICAL_VALUE, json))
                .isEqualTo(evidence);
    }

    @Test
    void interpolationEvidenceRoundTripKeepsEveryField() {
        FillTaskEvidence.Interpolation evidence =
                new FillTaskEvidence.Interpolation(
                        1_800_000L,
                        10.5D,
                        1_980_000L,
                        13.5D,
                        "linear-v1");

        String json = codec.encode(FillSourceType.INTERPOLATION, evidence);

        assertThat(codec.decode(FillSourceType.INTERPOLATION, json))
                .isEqualTo(evidence);
    }

    @Test
    void rejectsMalformedMissingAndSourceMismatchedEvidence() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                codec.decode(FillSourceType.TYPICAL_VALUE, "{broken"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                codec.decode(
                        FillSourceType.TYPICAL_VALUE,
                        """
                        {"configId":null,"version":1,"value":18.0,"unit":"℃",
                         "validFrom":0,"validTo":null,"hourStart":0,
                         "algorithmVersion":"typical-v1","appliedSegments":[]}
                        """));

        FillTaskEvidence.Interpolation interpolation =
                new FillTaskEvidence.Interpolation(
                        1_800_000L, 1D, 1_860_000L, 2D, "linear-v1");
        assertThatIllegalArgumentException().isThrownBy(() ->
                codec.encode(FillSourceType.TYPICAL_VALUE, interpolation));
    }

    @Test
    void rejectsUnorderedOrOverlappingTypicalSegments() {
        FillTaskEvidence.Typical overlapping = new FillTaskEvidence.Typical(
                "CONFIG001", 1, BigDecimal.TEN, "℃", 0L, null,
                3_600_000L, "typical-v1",
                List.of(
                        new FillTaskEvidence.MinuteSegment(
                                3_600_000L, 3_720_000L),
                        new FillTaskEvidence.MinuteSegment(
                                3_660_000L, 3_780_000L)));

        assertThatIllegalArgumentException().isThrownBy(() ->
                        codec.encode(FillSourceType.TYPICAL_VALUE, overlapping))
                .satisfies(exception -> assertThat(exception.getCause())
                        .hasMessage("典型值应用区间必须按时间排序且不能重叠"));
    }
}
