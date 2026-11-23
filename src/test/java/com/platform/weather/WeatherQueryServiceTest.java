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
    private final LocalDate day=LocalDate.of(2026,9,28);
    private final Instant now=day.atTime(12,0).atZone(ZONE).toInstant();
    private final WeatherQueryService query=new WeatherQueryService(repo,access,Clock.fixed(now,ZONE));
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
    @Test void currentDayUsesLatestValidObservationInEachBeijingHour() {
        Instant start=day.atStartOfDay(ZONE).toInstant();
        when(repo.effective("building",now)).thenReturn(binding);
        when(repo.publishedCurrent("b",start,now)).thenReturn(List.of(
                current("previous-day",start.minusSeconds(1),20d,start),
                current("early",start.plusSeconds(900),5d,start.plusSeconds(910)),
                current("latest-in-hour",start.plusSeconds(2700),0d,start.plusSeconds(2710)),
                current("delayed-old",start.plusSeconds(1800),9d,start.plusSeconds(2720)),
                current("missing",start.plusSeconds(3000),null,start.plusSeconds(3010)),
                current("just-observed",now,-1d,now),
                current("future",now.plusSeconds(900),9d,now)));

        var dayWeather=query.currentDay(1,List.of("BUILDING_OWNER"),"building");
        assertThat(dayWeather.date()).isEqualTo(day);
        assertThat(dayWeather.hours()).containsExactly(
                new CurrentTemperatureHour(start,start.plusSeconds(2700),0d),
                new CurrentTemperatureHour(now,now,-1d));
        verify(access).reader(1,List.of("BUILDING_OWNER"),"building");
        verify(repo,never()).head(any(),any(),any(),any());
        verify(repo,never()).latest(any(),any(),any());
    }
    @Test void currentDayKeepsPreviousReadingForCardAcrossMidnightButLeavesNewDayEmpty() {
        var previous=current("previous",day.minusDays(1).atTime(23,45).atZone(ZONE).toInstant(),7d,
                day.minusDays(1).atTime(23,46).atZone(ZONE).toInstant());
        when(repo.effective("building",now)).thenReturn(binding);
        when(repo.latest("b",Source.OPEN_METEO,Product.CURRENT)).thenReturn(List.of(previous));

        var dayWeather=query.currentDay(1,List.of(),"building");
        assertThat(dayWeather.current().datasetId()).isEqualTo("previous");
        assertThat(dayWeather.current().updateStatus()).isEqualTo("OVERDUE");
        assertThat(dayWeather.hours()).isEmpty();
    }
    @Test void currentDayRejectsUnauthorizedBuildingBeforeReadingDatasets() {
        doThrow(new com.platform.framework.exception.BusinessException(403,"denied")).when(access).reader(1,List.of(),"b");
        assertThatThrownBy(()->query.currentDay(1,List.of(),"b")).isInstanceOf(com.platform.framework.exception.BusinessException.class);
        verifyNoInteractions(repo);
    }
    private Dataset current(String id, Instant observedAt, Double celsius, Instant fetchedAt) {
        var sample=new Sample(observedAt,celsius==null?Map.of():Map.of("temperature_2m",celsius),Map.of());
        var payload=new FetchResult(Source.OPEN_METEO,Product.CURRENT,fetchedAt,null,null,null,List.of(sample),"test");
        return new Dataset(id,"b",Source.OPEN_METEO,Product.CURRENT,fetchedAt,"PUBLISHED","unused",payload);
    }
}
