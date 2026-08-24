package com.platform.iot.collection;

import com.platform.iot.collection.api.CollectionPolicyContracts.SourceCreateRequest;
import com.platform.iot.collection.mapper.BizCollectionConfigAuditLogMapper;
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
class CollectionPolicyAuditRollbackTest {
    @Autowired private CollectionPolicyService service;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockBean private BizCollectionConfigAuditLogMapper auditMapper;

    @Test
    void auditWriteFailureRollsBackBusinessMutation() {
        when(auditMapper.insert(any())).thenThrow(new DataAccessResourceFailureException("audit unavailable"));
        SourceCreateRequest request = new SourceCreateRequest(
                "MQTT_BLD001_AUDIT_FAIL", "审计回滚来源", "BLD001", "MQTT", null, "验证审计原子性");

        assertThatThrownBy(() -> service.createSource(1L, Set.of("PLATFORM_ADMIN"), request))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_data_source WHERE source_code='MQTT_BLD001_AUDIT_FAIL'",
                Integer.class)).isZero();
    }
}
