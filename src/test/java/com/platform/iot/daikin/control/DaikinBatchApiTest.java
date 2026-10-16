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
import static org.assertj.core.api.Assertions.*;

/** 公共 API 使用真实服务和离线 H2，202 仅表示批次持久化，不能代表厂家受理。 */
class DaikinBatchApiTest {
    @Test void batchContractPersistsReturns202SupportsHistoryAndCancelsQueue() throws Exception {
        try(var t=new DaikinBatchControlTest.Fixture(3,1)) {
            var mvc=MockMvcBuilders.standaloneSetup(new DaikinBatchController(t.service))
                    .setControllerAdvice(new DaikinControlExceptionHandler(),new GlobalExceptionHandler(mock(SecurityAuditService.class))).build();
            var auth=new UsernamePasswordAuthenticationToken(new JwtUserPrincipal(7L,"offline"),null,List.of());
            var p=mvc.perform(post("/v1/hvac-control/batches/preview").principal(auth).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"equipmentIds\":[\"e000\",\"e001\",\"e002\"]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(3))
                    .andExpect(jsonPath("$.data.items[0].independentOnOff").value(true)).andReturn();
            String preview=t.f.json.readTree(p.getResponse().getContentAsString()).path("data").path("previewId").asText();
            String body=t.f.json.writeValueAsString(new DaikinBatchContracts.SubmitBatch(preview,DaikinControlContracts.Action.TEMPERATURE,"26","api-batch-request",false));
            var result=mvc.perform(post("/v1/hvac-control/batches").principal(auth).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.queuedCount").value(3)).andExpect(jsonPath("$.data.completed").value(false)).andReturn();
            String id=t.f.json.readTree(result.getResponse().getContentAsString()).path("data").path("batchId").asText();
            mvc.perform(post("/v1/hvac-control/batches").principal(auth).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.batchId").value(id));
            mvc.perform(get("/v1/hvac-control/batches").principal(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
            mvc.perform(post("/v1/hvac-control/batches/"+id+"/cancel").principal(auth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.completed").value(true)).andExpect(jsonPath("$.data.items[0].status").value("CANCELLED"));
            mvc.perform(get("/v1/hvac-control/batches/"+id).principal(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3));
            assertThat(t.sends).hasValue(0);
        }
    }
    @Test void unknownWireFieldsEmptyOversizedAndOtherUsersAreRejected() throws Exception {
        try(var t=new DaikinBatchControlTest.Fixture(1,1)) {
            var mvc=MockMvcBuilders.standaloneSetup(new DaikinBatchController(t.service))
                    .setControllerAdvice(new DaikinControlExceptionHandler(),new GlobalExceptionHandler(mock(SecurityAuditService.class))).build();
            var auth=new UsernamePasswordAuthenticationToken(new JwtUserPrincipal(7L,"offline"),null,List.of());
            mvc.perform(post("/v1/hvac-control/batches/preview").principal(auth).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"equipmentIds\":[]}")).andExpect(status().isBadRequest());
            mvc.perform(post("/v1/hvac-control/batches").principal(auth).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"vendorId\":\"arbitrary\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("HVAC_CONTROL_INVALID_REQUEST"));
            mvc.perform(post("/v1/hvac-control/batches").principal(auth).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"previewId\":\"arbitrary\",\"action\":\"ON_OFF\",\"value\":\"on\",\"idempotencyKey\":\"forged-scope\",\"confirmLinkedImpact\":false,\"independentOnOff\":true}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("HVAC_CONTROL_INVALID_REQUEST"));
            var batch=t.submit(1,DaikinControlContracts.Action.ON_OFF,"on");
            var other=new UsernamePasswordAuthenticationToken(new JwtUserPrincipal(8L,"other"),null,List.of());
            mvc.perform(get("/v1/hvac-control/batches/"+batch.batchId()).principal(other)).andExpect(status().isNotFound());
            assertThatThrownBy(()->t.service.preview(7,new DaikinBatchContracts.PreviewRequest(java.util.Collections.nCopies(101,"e000")))).isInstanceOf(com.platform.framework.exception.BusinessException.class);
            assertThat(t.sends).hasValue(0);
        }
    }
}
