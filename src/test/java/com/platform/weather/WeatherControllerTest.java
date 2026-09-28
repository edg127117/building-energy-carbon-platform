package com.platform.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.platform.framework.exception.GlobalExceptionHandler;
import com.platform.security.JwtUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WeatherControllerTest {
    WeatherService service=mock(WeatherService.class);
    WeatherQueryService query=mock(WeatherQueryService.class);
    MockMvc mvc=MockMvcBuilders.standaloneSetup(new WeatherController(service,query)).setControllerAdvice(new GlobalExceptionHandler(mock(com.platform.audit.SecurityAuditService.class))).build();
    UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken(new JwtUserPrincipal(1L,"test"),null,List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")));
    @Test void sourceJobIsAcceptedAndDoesNotCallReadOrSource() throws Exception {
        when(service.submit(eq(1L),any(),eq("key"),any())).thenReturn(List.of());
        mvc.perform(post("/v1/weather/jobs").principal(auth).header("Idempotency-Key","key").contentType(MediaType.APPLICATION_JSON)
                .content("{\"buildingId\":\"b\",\"source\":\"OPEN_METEO\",\"product\":\"HISTORY_DAILY\",\"start\":\"2026-01-01\",\"end\":\"2026-01-02\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.success").value(true));
        verifyNoInteractions(query);
    }
    @Test void invalidSourceRequestIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/v1/weather/jobs").principal(auth).header("Idempotency-Key","key").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service,query);
    }
    @Test void locationCannotSilentlyDefaultMissingCoordinatesToZero() throws Exception {
        mvc.perform(post("/v1/weather/buildings/b/binding-versions").principal(auth).header("Idempotency-Key","key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nanjing\",\"coverage\":\"CITY\",\"effectiveFrom\":\"2024-01-01T00:00:00+08:00\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service,query);
    }
    @Test void queryForwardsUtcInstantsAndRoleScope() throws Exception {
        when(query.hourly(anyLong(),any(),eq("b"),any(),any(),any())).thenReturn(List.of());
        mvc.perform(get("/v1/weather/buildings/b/hourly").principal(auth).param("product","HISTORY_HOURLY")
                .param("from","2026-01-01T00:00:00+08:00").param("to","2026-01-02T00:00:00+08:00"))
                .andExpect(status().isOk());
        verify(query).hourly(eq(1L),eq(java.util.Set.of("PLATFORM_ADMIN")),eq("b"),eq(com.platform.weather.source.WeatherSourceModels.Product.HISTORY_HOURLY),eq(java.time.Instant.parse("2025-12-31T16:00:00Z")),eq(java.time.Instant.parse("2026-01-01T16:00:00Z")));
        verifyNoInteractions(service);
    }
}
