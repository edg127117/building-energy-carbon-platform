package com.platform.weather;

import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import com.platform.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.time.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.weather.source.WeatherSourceModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WeatherTemperatureControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @MockBean TokenCacheService tokens;
    @MockBean WeatherRepository repository;
    private static final String PATH="/v1/weather/buildings/BLD001/temperature-trend";
    @BeforeEach void session(){when(tokens.validateActiveToken(anyLong(),anyString())).thenReturn(TokenValidationResult.ACTIVE);}
    private String bearer(String role){return "Bearer "+jwt.generateToken(1L,"temperature-reader",List.of(role),1L);}
    @Test void rejectsAnonymousRoleAndMalformedPeriodsBeforeWeatherRead() throws Exception {
        mvc.perform(get(PATH).param("periodType","YEAR").param("period","2025")).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization",bearer("THIRD_PARTY")).param("periodType","YEAR").param("period","2025")).andExpect(status().isForbidden());
        mvc.perform(get(PATH).header("Authorization",bearer("PLATFORM_ADMIN")).param("periodType","YEAR").param("period","2025-01")).andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }
    @Test void noConfigurationReturnsCalendarSlotsAndNoInventedWeatherSource() throws Exception {
        mvc.perform(get(PATH).header("Authorization",bearer("PLATFORM_ADMIN")).param("periodType","MONTH").param("period","2024-02"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.buildingId").value("BLD001"))
                .andExpect(jsonPath("$.data.status").value("NOT_CONFIGURED")).andExpect(jsonPath("$.data.points.length()").value(29))
                .andExpect(jsonPath("$.data.points[0].value").isEmpty()).andExpect(jsonPath("$.data.points[0].validHours").value(0))
                .andExpect(jsonPath("$.data.points[0].startInclusive").value("2024-01-31T16:00:00Z"))
                .andExpect(jsonPath("$.data.sources.length()").value(0));
        verify(repository,never()).headId(anyString(),any(),any(),any());
    }
    @Test void serializesCompleteTemperatureAsDecimalStringsWithHistoricalEvidence() throws Exception {
        var location=new WeatherModels.Binding("LOCATION","BLD001",7,"隔离夹具",30,120,null,"CITY",Instant.EPOCH,null,true);
        var samples=new ArrayList<Sample>();var month=YearMonth.of(2024,2);
        for(int day=1;day<=month.lengthOfMonth();day++)for(int hour=0;hour<24;hour++)
            samples.add(new Sample(month.atDay(day).atTime(hour,0).atZone(WeatherModels.ZONE).toInstant(),Map.of("temperature_2m",day==1?-5d:day==2?0d:12.5d),Map.of()));
        var result=new FetchResult(Source.OPEN_METEO,Product.HISTORY_HOURLY,Instant.parse("2024-03-01T00:00:00Z"),null,30d,120d,samples,"fixture-v1");
        when(repository.effective(eq("BLD001"),any())).thenReturn(location);
        when(repository.headId(eq("LOCATION"),eq(Source.OPEN_METEO),eq(Product.HISTORY_HOURLY),any())).thenReturn("DATA");
        when(repository.dataset("DATA")).thenReturn(new WeatherModels.Dataset("DATA","LOCATION",Source.OPEN_METEO,Product.HISTORY_HOURLY,result.fetchedAt(),"PUBLISHED","unused",result));
        var response=mvc.perform(get(PATH).header("Authorization",bearer("PLATFORM_ADMIN")).param("periodType","MONTH").param("period","2024-02"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.points[0].value").isString()).andExpect(jsonPath("$.data.points[1].value").isString())
                .andExpect(jsonPath("$.data.sources[0].provenance.dataKind").value("MODEL_HISTORY_BEST_MATCH"))
                .andExpect(jsonPath("$.data.unit").value("°C")).andReturn();
        var points=mapper.readTree(response.getResponse().getContentAsString()).path("data").path("points");
        assertThat(new java.math.BigDecimal(points.get(0).path("value").asText())).isEqualByComparingTo("-5");
        assertThat(new java.math.BigDecimal(points.get(1).path("value").asText())).isZero();
    }
}
