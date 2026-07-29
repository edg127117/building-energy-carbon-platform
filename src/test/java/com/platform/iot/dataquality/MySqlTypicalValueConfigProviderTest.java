package com.platform.iot.dataquality;

import com.platform.iot.dataquality.mapper.BizPointTypicalValueConfigMapper;
import com.platform.iot.dataquality.model.TypicalValueStatus;
import com.platform.iot.dataquality.model.entity.BizPointTypicalValueConfig;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MySqlTypicalValueConfigProviderTest {

    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void shouldUseApprovedSnapshotWithHalfOpenValidityWindow() {
        BizPointTypicalValueConfigMapper mapper = mock(BizPointTypicalValueConfigMapper.class);
        BizPointTypicalValueConfig approved = config(
                "C1", TypicalValueStatus.APPROVED,
                LocalDateTime.of(2026, 7, 29, 10, 0),
                LocalDateTime.of(2026, 7, 29, 11, 0),
                1);
        BizPointTypicalValueConfig draft = config(
                "C2", TypicalValueStatus.DRAFT,
                approved.getValidFrom(), approved.getValidTo(), 2);
        when(mapper.selectApprovedSnapshot()).thenReturn(List.of(approved, draft));

        MySqlTypicalValueConfigProvider provider = new MySqlTypicalValueConfigProvider(mapper);
        provider.refresh();

        assertThat(provider.findApproved("P1", epoch(approved.getValidFrom())))
                .containsSame(approved);
        assertThat(provider.findApproved("P1", epoch(approved.getValidTo()))).isEmpty();
        assertThat(provider.snapshot()).containsExactly(approved);
    }

    @Test
    void shouldKeepLastCompleteSnapshotWhenRefreshFails() {
        BizPointTypicalValueConfigMapper mapper = mock(BizPointTypicalValueConfigMapper.class);
        BizPointTypicalValueConfig approved = config(
                "C1", TypicalValueStatus.APPROVED,
                LocalDateTime.of(2026, 7, 29, 10, 0), null, 1);
        when(mapper.selectApprovedSnapshot())
                .thenReturn(List.of(approved))
                .thenThrow(new IllegalStateException("mysql unavailable"));
        MySqlTypicalValueConfigProvider provider = new MySqlTypicalValueConfigProvider(mapper);

        provider.refresh();
        provider.refresh();

        assertThat(provider.findApproved("P1", epoch(approved.getValidFrom())))
                .containsSame(approved);
    }

    @Test
    void shouldReturnEmptyBeforeFirstSuccessfulLoad() {
        BizPointTypicalValueConfigMapper mapper = mock(BizPointTypicalValueConfigMapper.class);
        when(mapper.selectApprovedSnapshot()).thenThrow(new IllegalStateException("mysql unavailable"));
        MySqlTypicalValueConfigProvider provider = new MySqlTypicalValueConfigProvider(mapper);

        provider.refresh();

        assertThat(provider.snapshot()).isEmpty();
        assertThat(provider.findApproved("P1", epoch(LocalDateTime.of(2026, 7, 29, 10, 0))))
                .isEmpty();
    }

    @Test
    void shouldPreferHighestVersionIfDirtySnapshotContainsTwoMatches() {
        BizPointTypicalValueConfigMapper mapper = mock(BizPointTypicalValueConfigMapper.class);
        LocalDateTime start = LocalDateTime.of(2026, 7, 29, 10, 0);
        BizPointTypicalValueConfig v1 = config("C1", TypicalValueStatus.APPROVED, start, null, 1);
        BizPointTypicalValueConfig v2 = config("C2", TypicalValueStatus.APPROVED, start, null, 2);
        when(mapper.selectApprovedSnapshot()).thenReturn(List.of(v1, v2));
        MySqlTypicalValueConfigProvider provider = new MySqlTypicalValueConfigProvider(mapper);

        provider.refresh();

        assertThat(provider.findApproved("P1", epoch(start))).containsSame(v2);
    }

    private static BizPointTypicalValueConfig config(
            String id,
            TypicalValueStatus status,
            LocalDateTime validFrom,
            LocalDateTime validTo,
            int version) {
        BizPointTypicalValueConfig config = new BizPointTypicalValueConfig();
        config.setConfigId(id);
        config.setPointId("P1");
        config.setStatus(status);
        config.setValidFrom(validFrom);
        config.setValidTo(validTo);
        config.setVersion(version);
        return config;
    }

    private static long epoch(LocalDateTime time) {
        return time.atZone(PROJECT_ZONE).toInstant().toEpochMilli();
    }
}
