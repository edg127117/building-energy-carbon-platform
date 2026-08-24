package com.platform.iot.qualityusage.governance;

import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.ChangeSetCreateRequest;
import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.PolicyDraftRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 同键状态动作在数据库行锁下的提交后重放验证。 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QualityUsageGovernanceConcurrencyIntegrationTest {
    private static final long ADMIN = 1L;
    private static final long ENERGY = 101L;
    private static final Set<String> ADMIN_ROLE = Set.of("PLATFORM_ADMIN");
    private static final Set<String> ENERGY_ROLE = Set.of("ENERGY_MANAGER");

    @Autowired private QualityUsageGovernanceService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void seedDirectoryAndScope() {
        jdbc.update("INSERT INTO sys_user_building(user_id,building_id) VALUES (?,?)", ENERGY, "BLD001");
        jdbc.update("""
                INSERT INTO biz_quality_usage_scenario
                  (scenario_id,scenario_code,scenario_name,adapter_type,status,introduced_version)
                VALUES ('QUS_RT','POINT_REALTIME_VIEW','实时展示','POINT_REALTIME_GATE','ENABLED','TEST_V1')
                """);
    }

    @Test
    void concurrentSameApprovalKeyReturnsOnePublishedResultAndOneRevision() throws Exception {
        var changeSet = service.createChangeSet(ENERGY, ENERGY_ROLE,
                new ChangeSetCreateRequest("BLD001", "并发幂等", null,
                        List.of(new PolicyDraftRequest("POINT012", "POINT_REALTIME_VIEW", List.of("Q0"),
                                null, "并发幂等验证"))));
        var submitted = service.submit(ENERGY, ENERGY_ROLE, changeSet.changeSetId(),
                "concurrent-submit", "提交");

        CountDownLatch callersReady = new CountDownLatch(2);
        CountDownLatch startCalls = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection lockConnection = dataSource.getConnection();
             PreparedStatement lockStatement = lockConnection.prepareStatement("""
                     SELECT request_id FROM biz_quality_usage_review_request
                     WHERE request_id=? FOR UPDATE
                     """)) {
            lockConnection.setAutoCommit(false);
            lockStatement.setString(1, submitted.requestId());
            lockStatement.executeQuery().close();

            Future<?> first = executor.submit(() -> approveAfterStart(
                    callersReady, startCalls, submitted.requestId()));
            Future<?> second = executor.submit(() -> approveAfterStart(
                    callersReady, startCalls, submitted.requestId()));
            assertThat(callersReady.await(5, TimeUnit.SECONDS)).isTrue();
            startCalls.countDown();
            // 两个调用都先通过初次 replay 查询，再因审核行锁阻塞；释放后后到者必须二次重放。
            TimeUnit.MILLISECONDS.sleep(150);
            lockConnection.commit();

            assertThat(first.get(10, TimeUnit.SECONDS)).isNotNull();
            assertThat(second.get(10, TimeUnit.SECONDS)).isNotNull();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(service.reviewDetail(ENERGY, ENERGY_ROLE, submitted.requestId()).status()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject(
                "SELECT config_revision FROM biz_quality_usage_config_revision WHERE singleton_id=1", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_quality_usage_audit_log WHERE action_type='APPROVE'", Integer.class))
                .isEqualTo(1);
    }

    private Object approveAfterStart(CountDownLatch callersReady, CountDownLatch startCalls, String requestId) {
        callersReady.countDown();
        try {
            if (!startCalls.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发审批测试未收到开始信号");
            }
            return service.approve(ADMIN, ADMIN_ROLE, requestId, "concurrent-approve", "同键重试");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发审批测试被中断", exception);
        }
    }
}
