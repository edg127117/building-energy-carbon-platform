package com.platform.iot.collection;

import com.platform.audit.BackendDuty;
import com.platform.iot.collection.api.CollectionPolicyContracts.AliasCreateRequest;
import com.platform.iot.collection.api.CollectionPolicyContracts.InitialPolicyRequest;
import com.platform.iot.collection.api.CollectionPolicyContracts.SourceCreateRequest;
import com.platform.iot.collection.api.CollectionPolicyContracts.DataSourceView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CollectionPolicyAtomicityIntegrationTest {
    @Autowired private CollectionPolicyService service;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void firstActivationRollsBackEveryAliasAndVersionWhenOnePointBecomesInvalid() {
        Set<String> admin = Set.of("PLATFORM_ADMIN");
        grant(BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        grant(BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
        DataSourceView source = service.createSource(1L, admin,
                new SourceCreateRequest("MQTT_BLD001_ATOMIC", "原子首启来源", "BLD001",
                        "MQTT", null, "验证首启事务"));
        service.createAlias(1L, admin, source.sourceId(), alias("ATOMIC_A", "POINT001"));
        service.createAlias(1L, admin, source.sourceId(), alias("ATOMIC_B", "POINT002"));
        jdbcTemplate.update("UPDATE biz_data_point SET del_flag=1 WHERE point_id='POINT002'");
        var submitted = service.submitSource(1L, admin, source.sourceId(), "提交原子首启");

        assertThatThrownBy(() -> service.approveReview(1L, admin, submitted.requestId(), "批准首启"))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM biz_data_source WHERE source_id=?", String.class, source.sourceId()))
                .isEqualTo("DRAFT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_point_alias WHERE source_id=? AND status=2",
                Integer.class, source.sourceId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_collection_policy_version v "
                        + "JOIN biz_collection_policy p ON p.policy_id=v.policy_id "
                        + "WHERE p.source_id=? AND v.status='ACTIVE'", Integer.class, source.sourceId()))
                .isZero();

        jdbcTemplate.update("UPDATE biz_data_point SET del_flag=0 WHERE point_id='POINT002'");
        service.withdrawReview(1L, admin, submitted.requestId());
        service.deleteSource(1L, admin, source.sourceId());
    }

    private AliasCreateRequest alias(String sourcePointCode, String pointId) {
        return new AliasCreateRequest(sourcePointCode, pointId,
                new InitialPolicyRequest(25, 7, "FIXED_DAYS", 30,
                        "LONG_TERM", null, true), "原子性测试");
    }

    private void grant(BackendDuty duty) {
        LocalDateTime now = LocalDateTime.now().minusMinutes(1);
        jdbcTemplate.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by,created_at)
                VALUES (?,?,?,'ACTIVE',?,?,?)
                """, UUID.randomUUID().toString().replace("-", ""), 1L, duty.name(),
                Timestamp.valueOf(now), 1L, Timestamp.valueOf(now));
    }
}
