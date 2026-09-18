package com.platform.iot.onboarding.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.BackendDuty;
import com.platform.iot.onboarding.DaikinSyncAccessService;
import com.platform.framework.web.PageResponse;
import com.platform.security.JwtUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 默认配置下验证鉴权、关闭边界及文档契约；不配置任何厂家客户端。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DaikinDirectorySyncApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void anonymousCannotSubmitOrReadJobs() throws Exception {
        mvc.perform(post("/v1/daikin/sources/source-a/sync-jobs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ONBOARDING_UNAUTHORIZED"));
        mvc.perform(get("/v1/daikin/sources/source-a/sync-jobs/job-a"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/daikin/sync-jobs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsAcceptedTaskWithoutWaitingForManufacturer() throws Exception {
        var access = mock(DaikinSyncAccessService.class);
        var job = new DaikinSyncAccessService.SyncJobView(
                "job-a", "source-a", "QUEUED", 0, null, 1L, 1L, null);
        when(access.request(7L, Set.of("BUILDING_OWNER"), "source-a")).thenReturn(job);
        var auth = new UsernamePasswordAuthenticationToken(new JwtUserPrincipal(7L, "ops"), null,
                List.of(new SimpleGrantedAuthority("ROLE_BUILDING_OWNER")));
        MockMvcBuilders.standaloneSetup(new DaikinDirectorySyncController(access)).build()
                .perform(post("/v1/daikin/sources/source-a/sync-jobs").principal(auth))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").value("job-a"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
        verify(access).request(7L, Set.of("BUILDING_OWNER"), "source-a");
    }

    @Test
    void listsAuthorizedHistoryWithStablePageContract() throws Exception {
        var access = mock(DaikinSyncAccessService.class);
        var job = new DaikinSyncAccessService.SyncJobView(
                "job-a", "source-a", "SUCCEEDED", 1, null, 10L, 20L, 20L);
        when(access.list(7L, Set.of("BUILDING_OWNER"), 1, 10))
                .thenReturn(new PageResponse<>(1, 10, 1, List.of(job)));
        var auth = new UsernamePasswordAuthenticationToken(new JwtUserPrincipal(7L, "ops"), null,
                List.of(new SimpleGrantedAuthority("ROLE_BUILDING_OWNER")));
        MockMvcBuilders.standaloneSetup(new DaikinDirectorySyncController(access)).build()
                .perform(get("/v1/daikin/sync-jobs").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].createdAt").value(10));
    }

    @Test
    void documentsAsyncEndpointsAndRejectsDisabledRealSync() throws Exception {
        String login = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(login).path("data").path("token").asText();
        jdbc.update("""
                INSERT INTO sys_user_backend_duty
                  (assignment_id,user_id,duty_key,status,effective_at,created_by,created_at)
                VALUES ('SYNC-API-TEST',1,?,'ACTIVE',?,1,?)
                """, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER.name(),
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now());
        mvc.perform(post("/v1/daikin/sources/source-a/sync-jobs").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DAIKIN_SYNC_DISABLED"));
        String spec = mvc.perform(get("/v3/api-docs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var paths = mapper.readTree(spec).path("paths");
        assertThat(paths.has("/v1/daikin/sources/{sourceId}/sync-jobs")).isTrue();
        assertThat(paths.has("/v1/daikin/sources/{sourceId}/sync-jobs/{jobId}")).isTrue();
        assertThat(paths.has("/v1/daikin/sync-jobs")).isTrue();
        assertThat(paths.path("/v1/daikin/sources/{sourceId}/sync-jobs").path("post")
                .path("responses").has("202")).isTrue();
    }
}
