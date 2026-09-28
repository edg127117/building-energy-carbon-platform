package com.platform.weather;

import com.platform.weather.source.WeatherSourceModels.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.platform.weather.WeatherModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WeatherQueryServiceTest {
    private final WeatherRepository repo=mock(WeatherRepository.class);
    private final WeatherService access=mock(WeatherService.class);
    private final WeatherQueryService query=new WeatherQueryService(repo,access);
    private final LocalDate day=LocalDate.of(2026,9,28);
    private final Instant now=day.atTime(12,0).atZone(ZONE).toInstant();
    private final Binding binding=new Binding("b","building",1,"test",32,118,"101190101","CITY",Instant.EPOCH,null,true);
    private Dataset forecast(Source source,boolean complete,Instant fetched) {
        List<Sample> samples=new ArrayList<>();
        for(int i=0;i<7;i++)samples.add(new Sample(day.plusDays(i).atStartOfDay(ZONE).toInstant(),complete||i!=0?Map.of("temperature_2m_max",30d,"temperature_2m_min",20d,"weather_code",1d):Map.of("temperature_2m_min",20d,"weather_code",1d),Map.of()));
        return new Dataset(source.name(),"b",source,Product.FORECAST_DAILY,fetched,"PUBLISHED","unused",new FetchResult(source,Product.FORECAST_DAILY,fetched,null,null,null,samples,"test"));
    }
    @Test void forecastFallsBackAsWholeBatchWhenPreferredIsPartial() {
        when(repo.latest("b",Source.CHINA_WEATHER,Product.FORECAST_DAILY)).thenReturn(List.of(forecast(Source.CHINA_WEATHER,false,now)));
        when(repo.latest("b",Source.OPEN_METEO,Product.FORECAST_DAILY)).thenReturn(List.of(forecast(Source.OPEN_METEO,true,now)));
        Forecast result=query.forecast(binding,now);
        assertThat(result.selectedSource()).isEqualTo(Source.OPEN_METEO);
        assertThat(result.datasetId()).isEqualTo("OPEN_METEO");
        assertThat(result.days()).hasSize(7).allSatisfy(d->assertThat(d.sample()).isNotNull());
    }
    @Test void oldDatesNeverBecomeTodaysForecast() {
        Dataset old=forecast(Source.CHINA_WEATHER,true,now.minus(Duration.ofDays(10)));
        when(repo.latest("b",Source.CHINA_WEATHER,Product.FORECAST_DAILY)).thenReturn(List.of(old));
        Forecast result=query.forecast(binding,now.plus(Duration.ofDays(10)));
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.days()).allSatisfy(d->assertThat(d.sample()).isNull());
    }
    @Test void fieldMeansRequire24DistinctCompleteHoursIndependently() {
        List<Sample> samples=new ArrayList<>();
        for(int i=0;i<24;i++)samples.add(new Sample(day.atTime(i,0).atZone(ZONE).toInstant(),i==3?Map.of("temperature_2m",10d):Map.of("temperature_2m",10d,"relative_humidity_2m",70d),Map.of()));
        assertThat(WeatherQueryService.mean(samples,day,"temperature_2m").value()).isEqualTo(10d);
        assertThat(WeatherQueryService.mean(samples,day,"relative_humidity_2m").value()).isNull();
        assertThat(WeatherQueryService.mean(samples,day,"relative_humidity_2m").hours()).isEqualTo(23);
        samples.add(samples.getFirst());
        assertThat(WeatherQueryService.mean(samples,day,"temperature_2m").value()).isNull();
    }
    @Test void forbiddenReadNeverTouchesWeatherStore() {
        doThrow(new com.platform.framework.exception.BusinessException(403,"denied")).when(access).reader(1,List.of(),"b");
        assertThatThrownBy(()->query.overview(1,List.of(),"b")).isInstanceOf(com.platform.framework.exception.BusinessException.class);
        verifyNoInteractions(repo);
    }
    @Test void unconfiguredWeatherDoesNotNeedElectricityOrSource() {
        var result=query.overview(1,List.of("PLATFORM_ADMIN"),"none");
        assertThat(result.current().status()).isEqualTo("NOT_CONFIGURED");
        verify(repo).effective(eq("none"),any());
        verifyNoMoreInteractions(repo);
    }
}
