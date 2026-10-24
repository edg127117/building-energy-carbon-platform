package com.platform.hvac.asset.service;

import com.platform.audit.AuditEnvironmentMode;
import com.platform.audit.AuditGovernanceProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class D01TestBuildingRetirementServiceTest {
    @ParameterizedTest
    @EnumSource(value = AuditEnvironmentMode.class, names = {"DEVELOPMENT", "PRODUCTION"})
    void nonTestEnvironmentRejectsBothOperationsBeforeAnyDatabaseAccess(AuditEnvironmentMode mode) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var properties = new AuditGovernanceProperties();
        properties.setEnvironmentMode(mode);
        var service = new D01TestBuildingRetirementService(jdbc, properties);

        assertThatThrownBy(() -> service.exclude("B", "D01专用验收（合成数据）", "a".repeat(64), "exclude"))
                .hasMessageContaining("仅测试环境");
        assertThatThrownBy(() -> service.archive("B", "D01专用验收（合成数据）", "a".repeat(64), "archive"))
                .hasMessageContaining("仅测试环境");
        verifyNoInteractions(jdbc);
    }
}
