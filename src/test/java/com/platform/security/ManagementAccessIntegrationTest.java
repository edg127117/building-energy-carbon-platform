package com.platform.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.audit.BackendDuty;
import com.platform.audit.duty.BackendDutyAssignmentService;
import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import com.platform.support.TestUserFixture;
import com.platform.system.service.SysUserAdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 真 HTTP 双端口，H2 与进程内 Redis 隔离替身；不连接部署服务或厂家设备。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.health.db.enabled=false", "management.health.redis.enabled=false"})
@ActiveProfiles("test")
class ManagementAccessIntegrationTest {
    @LocalServerPort int businessPort;
    @LocalManagementPort int managementPort;
    @Autowired TestRestTemplate http;
    @Autowired TestUserFixture users;
    @Autowired JwtService jwt;
    @SpyBean TokenCacheService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired BackendDutyAssignmentService assignments;
    @Autowired SysUserAdminService admin;
    @SpyBean com.platform.audit.JdbcBackendDutyService duties;
    private long userId;

    @BeforeEach
    void prepare() {
        userId = users.createActiveUser("s02-observer", "s02-test-only-password", "BUILDING_OWNER", null);
    }

    @AfterEach
    void cleanup() {
        reset(duties);
        reset(tokens);
        jdbc.update("DELETE FROM sys_auth_cleanup WHERE user_id=?", userId);
        users.remove("s02-observer");
    }

    @Test
    void expiredDutyAndRedisOutageFailClosedWithoutLoggingOutOnTransientFailure() throws Exception {
        String token = token("PLATFORM_ADMIN");
        var databaseNow = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",
                (result, row) -> result.getObject(1, java.time.LocalDateTime.class));
        assignments.grant(userId, BackendDuty.OPERATIONS_OBSERVER, null, databaseNow.plusSeconds(3), "s02-expire", 1);
        assertThat(get(managementPort, "/actuator/metrics", token).getStatusCode()).isEqualTo(HttpStatus.OK);
        doReturn(TokenValidationResult.CACHE_UNAVAILABLE).when(tokens).validateActiveToken(userId, token);
        assertThat(get(managementPort, "/actuator/metrics", token).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        reset(tokens);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(7);
        ResponseEntity<JsonNode> response;
        do {
            response = get(managementPort, "/actuator/metrics", token);
            if (response.getStatusCode() == HttpStatus.FORBIDDEN) break;
            Thread.sleep(30);
        } while (System.nanoTime() < deadline);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(tokens.getFromWhitelist(userId)).isEqualTo(token);
    }

    @Test
    void forwardedPortCannotTurnBusinessListenerIntoManagementListener() {
        String token = token("PLATFORM_ADMIN");
        assignments.grant(userId, BackendDuty.OPERATIONS_OBSERVER, null, null, "s02-forwarded", 1);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Forwarded-Port", Integer.toString(managementPort));
        headers.set("Forwarded", "for=127.0.0.1;host=127.0.0.1:" + managementPort);
        assertThat(http.exchange("http://127.0.0.1:" + businessPort + "/api/actuator/metrics", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void healthIsMinimalAndBusinessListenerNeverExposesManagement() {
        assertThat(managementPort).isNotEqualTo(businessPort);
        var health = get(managementPort, "/actuator/health", null);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody().size()).isEqualTo(1);
        assertThat(health.getBody().path("status").asText()).isEqualTo("UP");
        assertThat(get(managementPort, "/actuator/metrics", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String token = token("BUILDING_OWNER");
        assignments.grant(userId, BackendDuty.OPERATIONS_OBSERVER, null, null, "s02-test", 1);
        for (String endpoint : List.of("health", "metrics", "threaddump")) {
            assertThat(get(businessPort, "/api/actuator/" + endpoint, token).getStatusCode())
                    .as("business endpoint %s", endpoint).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @ParameterizedTest
    @EnumSource(FormalRole.class)
    void noBusinessRoleAutomaticallyGrantsOperationsAccess(FormalRole role) {
        assertThat(get(managementPort, "/actuator/metrics", token(role.name())).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void currentDutyAndAccountStateAreRecheckedForEveryMetricsRequest() {
        String token = token("BUILDING_OWNER");
        assignments.grant(userId, BackendDuty.OPERATIONS_OBSERVER, null, null, "s02-grant", 1);
        assertThat(get(managementPort, "/actuator/metrics/jvm.memory.used", token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assignments.revoke(userId, BackendDuty.OPERATIONS_OBSERVER, "s02-revoke", 1);
        assertThat(get(managementPort, "/actuator/metrics", token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assignments.grant(userId, BackendDuty.OPERATIONS_OBSERVER, null, null, "s02-regrant", 1);
        admin.updateStatus(-1L, userId, 0);
        // 故意恢复 Redis 旧白名单：管理端口仍必须执行 S01 的 MySQL 状态/版本校验。
        tokens.addToWhitelist(userId, token);
        assertThat(get(managementPort, "/actuator/metrics", token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void dutyStoreFailureCannotFallBackToBusinessAdministrator() {
        String token = token("PLATFORM_ADMIN");
        doThrow(new DataAccessResourceFailureException("private-duty-store-detail"))
                .when(duties).hasDuty(eq(userId), eq(BackendDuty.OPERATIONS_OBSERVER));
        var response = get(managementPort, "/actuator/metrics", token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().path("errorCode").asText()).isEqualTo("AUTH_STATE_UNAVAILABLE");
        assertThat(response.getBody().toString()).doesNotContain("private-duty-store-detail");
    }

    @Test
    void evenAuthorizedOperationsCannotReadOtherEndpoints() {
        String token = token("BUILDING_OWNER");
        assignments.grant(userId, BackendDuty.OPERATIONS_OBSERVER, null, null, "s02-test", 1);
        for (String endpoint : List.of("threaddump", "heapdump", "env", "configprops", "loggers", "")) {
            var response = get(managementPort, "/actuator/" + endpoint, token);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody().has("threads")).isFalse();
        }
    }

    private String token(String role) {
        Long version = jdbc.queryForObject("SELECT security_version FROM sys_user WHERE id=?", Long.class, userId);
        String value = jwt.generateToken(userId, "s02-observer", List.of(role), version);
        tokens.addToWhitelist(userId, value);
        return value;
    }

    private ResponseEntity<JsonNode> get(int port, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return http.exchange("http://127.0.0.1:" + port + path, HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
    }
}
