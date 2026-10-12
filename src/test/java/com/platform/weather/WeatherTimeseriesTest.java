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
    @Test void readBackIgnoresJsonFieldOrderButStillRejectsChangedValuesAndExtraRows() {
        var base=dataset(Product.FORECAST_HOURLY);
        var values=new LinkedHashMap<String,Double>();
        values.put("temperature_2m",20d);values.put("relative_humidity_2m",60d);
        var reordered=new LinkedHashMap<String,Double>();
        reordered.put("relative_humidity_2m",60d);reordered.put("temperature_2m",20d);
        var original=new Sample(base.fetchedAt(),values,Map.of("weather","晴"));
        var roundTrip=new Sample(base.fetchedAt(),reordered,Map.of("weather","晴"));
        var d=new Dataset(base.id(),base.bindingId(),base.source(),base.product(),base.fetchedAt(),base.state(),base.tableName(),
                new FetchResult(base.source(),base.product(),base.fetchedAt(),null,null,null,List.of(original),"v1"));
        assertThat(codec.hash(List.of(original))).isNotEqualTo(codec.hash(List.of(roundTrip)));
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Sample>>any())).thenReturn(List.of(roundTrip));
        assertThatCode(()->store.write(d)).doesNotThrowAnyException();
        reordered.put("temperature_2m",21d);
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Sample>>any())).thenReturn(List.of(new Sample(base.fetchedAt(),reordered,original.text())));
        assertThatThrownBy(()->store.write(d)).hasMessage("WEATHER_WRITE_VERIFICATION_FAILED");
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Sample>>any())).thenReturn(List.of(original,original));
        assertThatThrownBy(()->store.write(d)).hasMessage("WEATHER_WRITE_VERIFICATION_FAILED");
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Sample>>any())).thenReturn(List.of(new Sample(base.fetchedAt().plusSeconds(1),values,original.text())));
        assertThatThrownBy(()->store.write(d)).hasMessage("WEATHER_WRITE_VERIFICATION_FAILED");
    }
    @Test void generatedIdsAllowLeadingDigitsButSqlIdentifiersRejectInjection() {
        assertThat(WeatherTimeseries.datasetId("0123456789abcdef0123456789abcdef")).hasSize(32);
        assertThatThrownBy(()->WeatherTimeseries.identifier("x;DROP TABLE y")).isInstanceOf(IllegalArgumentException.class);
    }
}
