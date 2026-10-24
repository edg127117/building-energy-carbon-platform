package com.platform.iot.reliability;

import com.platform.iot.reliability.api.TelemetryConflictController;
import com.platform.framework.exception.GlobalExceptionHandler;
import com.platform.audit.SecurityAuditService;
import com.platform.security.JwtUserPrincipal;
import com.platform.framework.web.PageResponse;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP 路径和方法权限使用真实 Spring 拦截器；领域权限与事务由 H2 契约覆盖。 */
class TelemetryConflictControllerTest {
    @Configuration @EnableMethodSecurity(proxyTargetClass=true) static class Config {
        @Bean TelemetryConflictService service(){return mock(TelemetryConflictService.class);}
        @Bean TelemetryConflictController controller(TelemetryConflictService service){return new TelemetryConflictController(service);}
    }
    AnnotationConfigApplicationContext context;MockMvc mvc;TelemetryConflictService service;
    @BeforeEach void setup(){context=new AnnotationConfigApplicationContext(Config.class);service=context.getBean(TelemetryConflictService.class);mvc=MockMvcBuilders.standaloneSetup(context.getBean(TelemetryConflictController.class)).setControllerAdvice(new GlobalExceptionHandler(mock(SecurityAuditService.class))).build();}
    @AfterEach void close(){SecurityContextHolder.clearContext();context.close();}
    UsernamePasswordAuthenticationToken auth(String role){var a=new UsernamePasswordAuthenticationToken(new JwtUserPrincipal(7L,"operator"),"",List.of(new SimpleGrantedAuthority("ROLE_"+role)));SecurityContextHolder.getContext().setAuthentication(a);return a;}
    @Test void readerReceivesStableBoundedPageAndBuildingIsRequired()throws Exception {
        var a=auth("BUILDING_OWNER");when(service.list(7L,Set.of("BUILDING_OWNER"),"B1",null,1,20)).thenReturn(new PageResponse<>(1,20,0,List.of()));
        mvc.perform(get("/v1/telemetry-conflicts").param("buildingId","B1").principal(a)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/v1/telemetry-conflicts").principal(a)).andExpect(status().isBadRequest());
    }
    @Test void thirdPartyCannotReadAndOwnerCannotReadAdministrativeMessageEvidence()throws Exception {
        mvc.perform(get("/v1/telemetry-conflicts").param("buildingId","B1").principal(auth("THIRD_PARTY"))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/telemetry-conflicts/messages/M1").principal(auth("BUILDING_OWNER"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void resolutionAndRetryReachTheirExactPublicContracts()throws Exception {
        var a=auth("BUILDING_OWNER");
        mvc.perform(post("/v1/telemetry-conflicts/P1/1800000000000/resolve").principal(a).contentType("application/json").content("{\"evidenceId\":null,\"expectedRevision\":2,\"reason\":\"核实\",\"evidenceReference\":\"工单\"}")).andExpect(status().isOk());
        verify(service).resolve(eq(7L),eq(Set.of("BUILDING_OWNER")),eq("P1"),eq(1800000000000L),eq(new TelemetryConflictService.ResolveRequest(null,2,"核实","工单")));
        mvc.perform(post("/v1/telemetry-conflicts/recovery/retry").principal(a).contentType("application/json").content("{\"kind\":\"MESSAGE\",\"id\":\"M1\"}")).andExpect(status().isOk());
        verify(service).retry(7L,Set.of("BUILDING_OWNER"),new TelemetryConflictService.RetryRequest("MESSAGE","M1"));
    }
}
