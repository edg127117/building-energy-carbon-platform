package com.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import com.platform.framework.exception.BusinessException;
import com.platform.support.TestUserFixture;
import com.platform.system.service.SysUserAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** H2 与隔离缓存替身验证安全失败路径，不连接真实 Redis 或现场设备。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthStateFailureFlowTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private TestUserFixture users;
    @Autowired private SysUserAdminService admin;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private TokenCacheService tokens;
    @org.springframework.boot.test.mock.mockito.SpyBean private com.platform.system.mapper.SysUserMapper states;

    @BeforeEach
    void setUp() {
        when(tokens.validateActiveToken(anyLong(), anyString())).thenReturn(TokenValidationResult.ACTIVE);
    }

    @Test
    void rejectsProtectedRequestDuringOutageAndRevokedTokenAfterRecovery() throws Exception {
        String token = login();
        when(tokens.validateActiveToken(anyLong(), eq(token))).thenReturn(TokenValidationResult.CACHE_UNAVAILABLE);
        mvc.perform(get("/s01-protected-probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("AUTH_STATE_UNAVAILABLE"));
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("AUTH_STATE_UNAVAILABLE"));
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("AUTH_STATE_UNAVAILABLE"));
        when(tokens.validateActiveToken(anyLong(), eq(token))).thenReturn(TokenValidationResult.REJECTED);
        mvc.perform(get("/s01-protected-probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        when(tokens.validateActiveToken(anyLong(), eq(token))).thenReturn(TokenValidationResult.ACTIVE);
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("admin"));
    }

    @Test
    void loginWriteFailureRejectsButLogoutCleanupFailureStillSucceeds() throws Exception {
        doThrow(unavailable()).when(tokens).addToWhitelist(anyLong(), anyString());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data.token").doesNotExist());
        doNothing().when(tokens).addToWhitelist(anyLong(), anyString());
        String token = login();
        doThrow(unavailable()).when(tokens).getFromWhitelist(1L);
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disableCommitsIfRedisCleanupFails() {
        long userId = users.createActiveUser("s01-disable", "test-pass", "BUILDING_OWNER", null);
        doThrow(unavailable()).when(tokens).getFromWhitelist(userId);
        admin.updateStatus(-1L, userId, 0);
        assertThat(jdbc.queryForObject("SELECT status FROM sys_user WHERE id=?", Integer.class, userId)).isEqualTo(0);
        doReturn(null).when(tokens).getFromWhitelist(userId);
        jdbc.update("DELETE FROM sys_auth_cleanup WHERE user_id=?", userId);
        users.remove("s01-disable");
    }

    private String login() throws Exception {
        String result = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).path("data").path("token").asText();
    }

    @Test
    void roleChangeCommitsOnCleanupFailureAndRetriesAfterRecovery() {
        long userId = users.createActiveUser("s01-role", "test-pass", "BUILDING_OWNER", null);
        try {
            doThrow(unavailable()).when(tokens).getFromWhitelist(userId);
            admin.replaceRoles(-1L, userId, java.util.List.of("ENERGY_MANAGER"));
            assertThat(admin.detail(userId).roles()).containsExactly("ENERGY_MANAGER");
            doReturn(null).when(tokens).getFromWhitelist(userId);
            admin.replaceRoles(-1L, userId, java.util.List.of("ENERGY_MANAGER"));
            assertThat(admin.detail(userId).roles()).containsExactly("ENERGY_MANAGER");
        } finally {
            doReturn(null).when(tokens).getFromWhitelist(userId);
            jdbc.update("DELETE FROM sys_auth_cleanup WHERE user_id=?", userId);
            users.remove("s01-role");
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(503, "AUTH_STATE_UNAVAILABLE", "登录状态服务暂不可用，请稍后重试");
    }

    @Test
    void mysqlAuthorityFailureReturns503ForHttpAndSessionQuery() throws Exception {
        String token = login();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("private-db-detail"))
                .when(states).selectSecurityState(anyLong());
        for (String path : java.util.List.of("/s01-protected-probe", "/auth/me")) {
            mvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.errorCode").value("AUTH_STATE_UNAVAILABLE"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-db-detail"))));
        }
    }
}
