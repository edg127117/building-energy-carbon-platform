package com.platform.iot.daikin.control;

import com.platform.audit.SecurityAuditService;
import com.platform.framework.exception.GlobalExceptionHandler;
import com.platform.security.JwtUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 默认关闭控制的真实 API/H2 恢复路径；没有厂家写客户端。 */
class DaikinControlRecoveryApiTest {
    @Test void adminRecoveryContractPreservesOldTargetAndRejectsUntrustedFields() throws Exception {
        var s=new DaikinControlRecoveryTest.Scenario(false); var f=s.f;
        var mvc=MockMvcBuilders.standaloneSetup(new DaikinControlRecoveryController(s.recovery))
                .setControllerAdvice(new DaikinControlExceptionHandler(),new GlobalExceptionHandler(mock(SecurityAuditService.class))).build();
        var admin=new UsernamePasswordAuthenticationToken(new JwtUserPrincipal(9L,"offline-admin"),null,List.of());
        var manager=new UsernamePasswordAuthenticationToken(new JwtUserPrincipal(7L,"offline-manager"),null,List.of());
        mvc.perform(get("/v1/hvac-control/devices/equipment/recovery").principal(manager)).andExpect(status().isForbidden());
        mvc.perform(get("/v1/hvac-control/devices/equipment/recovery").principal(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(true)).andExpect(jsonPath("$.data.operation.value").value("off"));
        String path="/v1/hvac-control/operations/"+s.old.operationId()+"/recovery/observe";
        for(String bad:List.of("{}","{\"onOff\":\"on\",\"temperature\":25,\"vendorId\":\"arbitrary\"}",
                "{\"onOff\":\"on\",\"temperature\":25.5}","{\"onOff\":\"on\",\"temperature\":\"25\"}"))
            mvc.perform(post(path).principal(admin).contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("HVAC_CONTROL_INVALID_REQUEST"));
        String proof=null;
        for(int i=0;i<3;i++) {
            if(i>0) f.clock.advance(30_000);
            var result=mvc.perform(post(path).principal(admin).contentType(MediaType.APPLICATION_JSON).content("{\"onOff\":\"on\",\"temperature\":25}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.stableCount").value(i+1))
                    .andExpect(jsonPath("$.data.canRecover").value(i==2)).andReturn();
            if(i==2) proof=f.json.readTree(result.getResponse().getContentAsString()).path("data").path("proof").asText();
        }
        var body=f.json.createObjectNode().put("onOff","on").put("temperature",25).put("proof",proof)
                .put("reason","现场确认当前开机25度，恢复旧隔离，不重发旧指令").put("onsiteConfirmed",true);
        mvc.perform(post(path.replace("/observe","/complete")).principal(admin).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.reasonCode").value("RECOVERY_CURRENT_BASELINE_RELEASED"))
                .andExpect(jsonPath("$.data.value").value("off")).andExpect(jsonPath("$.data.isolated").value(false));
    }
}
