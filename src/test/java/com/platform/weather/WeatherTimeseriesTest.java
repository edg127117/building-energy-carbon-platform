package com.platform.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.platform.config.TdengineProperties;
import com.platform.weather.source.WeatherSourceModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import java.time.Instant;
import java.util.*;
import static com.platform.weather.WeatherModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WeatherTimeseriesTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    WeatherCodec codec=new WeatherCodec(new ObjectMapper().registerModule(new JavaTimeModule()));
    WeatherTimeseries store=new WeatherTimeseries(jdbc,new TdengineProperties(),codec);
    Dataset dataset(Product product) {
        Instant now=Instant.parse("2026-09-28T00:00:00Z");
        Sample s=new Sample(now,Map.of("temperature_2m",20d),Map.of());
        return new Dataset("0123456789abcdef0123456789abcdef","1123456789abcdef0123456789abcdef",Source.OPEN_METEO,product,now,"PREPARED","w_test",new FetchResult(Source.OPEN_METEO,product,now,null,null,null,List.of(s),"v1"));
    }
    @Test void writeRequiresReadBackEqualityBeforeCallerCanPublish() {
        var d=dataset(Product.HISTORY_HOURLY);
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Sample>>any())).thenReturn(List.of());
        assertThatThrownBy(()->store.write(d)).hasMessage("WEATHER_WRITE_VERIFICATION_FAILED");
    }
    @Test void currentCleanupOnlyDeletesFixedCaptureAndNeverDropsSharedMonth() {
        var d=dataset(Product.CURRENT);store.drop(d);
        verify(jdbc).execute("DELETE FROM iot_telemetry.w_test WHERE ts="+d.fetchedAt().toEpochMilli());
        verifyNoMoreInteractions(jdbc);
    }
    @Test void generatedIdsAllowLeadingDigitsButSqlIdentifiersRejectInjection() {
        assertThat(WeatherTimeseries.datasetId("0123456789abcdef0123456789abcdef")).hasSize(32);
        assertThatThrownBy(()->WeatherTimeseries.identifier("x;DROP TABLE y")).isInstanceOf(IllegalArgumentException.class);
    }
}
