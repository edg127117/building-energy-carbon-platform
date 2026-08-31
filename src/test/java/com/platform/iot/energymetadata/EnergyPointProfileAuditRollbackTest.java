package com.platform.iot.energymetadata;

import com.platform.iot.collection.mapper.BizCollectionConfigAuditLogMapper;
import com.platform.iot.energymetadata.api.EnergyPointProfileContracts.CreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EnergyPointProfileAuditRollbackTest {
    @Autowired private EnergyPointProfileService service;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private BizCollectionConfigAuditLogMapper auditMapper;

    @Test
    void auditFailureRollsBackProfileCreation() {
        when(auditMapper.insert(any())).thenThrow(new DataAccessResourceFailureException("audit unavailable"));

        assertThatThrownBy(() -> service.create(1L, Set.of("PLATFORM_ADMIN"),
                new CreateRequest("BLD001", "POINT004", "ELECTRICITY", "GRID_PURCHASED",
                        "INSTANTANEOUS", "MONTH", true, "CONFIRMED", "专家确认")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_energy_point_profile WHERE point_id='POINT004'
                """, Integer.class)).isZero();
    }
}
