package com.platform.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import com.platform.weather.source.WeatherSourceModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.platform.weather.WeatherModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WeatherTemperatureTrendServiceTest {
    private final WeatherRepository repository=mock(WeatherRepository.class);
    private final WeatherService access=mock(WeatherService.class);
    private final WeatherCodec codec=new WeatherCodec(new ObjectMapper().findAndRegisterModules());
    private final WeatherTemperatureTrendService service=new WeatherTemperatureTrendService(repository,access,codec,
            Clock.fixed(Instant.parse("2026-10-30T08:00:00Z"),ZoneOffset.UTC));
    private final Binding binding=new Binding("LOCATION_A","GENERIC_BUILDING",7,"任意已授权建筑",30,120,null,"CITY",Instant.EPOCH,null,true);
    private final List<String> roles=List.of("ENERGY_MANAGER");
    @BeforeEach void location(){when(repository.effective(eq(binding.buildingId()),any())).thenReturn(binding);}
    private static List<Sample> samples(LocalDate from,LocalDate to,java.util.function.ToDoubleFunction<LocalDate> temperature) {
        List<Sample> samples=new ArrayList<>();
        for(LocalDate day=from;day.isBefore(to);day=day.plusDays(1))for(int hour=0;hour<24;hour++)
            samples.add(new Sample(day.atTime(hour,0).atZone(ZONE).toInstant(),Map.of("temperature_2m",temperature.applyAsDouble(day)),Map.of()));
        return samples;
    }
    private Dataset data(String id,Binding location,List<Sample> samples) {
        return new Dataset(id,location.id(),Source.OPEN_METEO,Product.HISTORY_HOURLY,Instant.parse("2026-10-29T10:00:00Z"),"PUBLISHED","unused",
                new FetchResult(Source.OPEN_METEO,Product.HISTORY_HOURLY,Instant.parse("2026-10-29T10:00:00Z"),null,30d,120d,samples,"parser-v1"));
    }
    private void publish(List<Sample> samples) {
        var data=data("DATA_A",binding,samples);
        when(repository.headId(eq(binding.id()),eq(Source.OPEN_METEO),eq(Product.HISTORY_HOURLY),any())).thenReturn(data.id());
        when(repository.dataset(data.id())).thenReturn(data);
    }
    @Test void completeLeapYearReturnsTwelveMonthMeansAndLoadsSharedPayloadOnce() {
        var samples=samples(LocalDate.of(2024,1,1),LocalDate.of(2025,1,1),date->date.getMonthValue()-1d);publish(samples);
        var view=service.query(1,roles,binding.buildingId(),"YEAR","2024");
        assertThat(view.status()).isEqualTo("READY");assertThat(view.points()).hasSize(12);
        for(int index=0;index<12;index++)assertThat(view.points().get(index).value()).isEqualByComparingTo(String.valueOf(index));
        assertThat(view.points().get(1).expectedHours()).isEqualTo(29*24);
        assertThat(view.points().getFirst().value()).isZero();
        assertThat(view.sources()).hasSize(1);assertThat(view.sources().getFirst().provenance().dataKind()).isEqualTo("MODEL_HISTORY_BEST_MATCH");
        assertThat(view.resultVersion()).hasSize(64);verify(repository,times(1)).dataset("DATA_A");
        verify(repository,times(366)).headId(eq(binding.id()),eq(Source.OPEN_METEO),eq(Product.HISTORY_HOURLY),any());
        assertThat(samples).hasSize(366*24);
    }
    @Test void monthReturnsDaysAndOneMissingHourNeverBecomesACompleteMean() {
        var samples=samples(LocalDate.of(2024,2,1),LocalDate.of(2024,3,1),day->-5d);samples.remove(24*28+3);publish(samples);
        var view=service.query(1,roles,binding.buildingId(),"MONTH","2024-02");
        assertThat(view.points()).hasSize(29);
        assertThat(view.points().getFirst().value()).isEqualByComparingTo("-5");
        var missing=view.points().getLast();assertThat(missing.value()).isNull();assertThat(missing.validHours()).isEqualTo(23);
        assertThat(missing.status()).isEqualTo("PARTIAL");assertThat(missing.startInclusive()).isEqualTo(Instant.parse("2024-02-28T16:00:00Z"));
        var year=service.query(1,roles,binding.buildingId(),"YEAR","2024");
        assertThat(year.points().get(1).value()).isNull();assertThat(year.points().get(1).validHours()).isEqualTo(29*24-1);
    }
    @Test void duplicatesNonFiniteAndHumidityGapsAreHandledIndependently() {
        var samples=samples(LocalDate.of(2025,1,1),LocalDate.of(2025,2,1),day->20d);samples.add(samples.getFirst());
        var invalid=samples.get(24);samples.set(24,new Sample(invalid.time(),Map.of("temperature_2m",Double.NaN),Map.of()));publish(samples);
        var view=service.query(1,roles,binding.buildingId(),"MONTH","2025-01");
        assertThat(view.points().getFirst().value()).isNull();assertThat(view.points().getFirst().validHours()).isEqualTo(23);
        assertThat(view.points().get(1).value()).isNull();assertThat(view.points().get(1).validHours()).isEqualTo(23);
        assertThat(view.points().get(2).status()).isEqualTo("READY");assertThat(view.points().get(2).value()).isEqualByComparingTo("20");
        assertThat(view.points().getFirst().reasons()).contains("INVALID_OR_DUPLICATE_HOURS");
    }
    @Test void unfinishedPeriodsAndFutureHoursStayEmptyEvenWhenPayloadContainsThem() {
        publish(samples(LocalDate.of(2026,10,1),LocalDate.of(2026,11,1),day->day.getDayOfMonth()>=30?999d:12d));
        var month=service.query(1,roles,binding.buildingId(),"MONTH","2026-10");
        assertThat(month.points().get(28).value()).isEqualByComparingTo("12");
        assertThat(month.points().get(29).value()).isNull();assertThat(month.points().get(29).status()).isEqualTo("IN_PROGRESS");
        assertThat(month.points().get(30).value()).isNull();assertThat(month.points().get(30).status()).isEqualTo("NOT_STARTED");
        var year=service.query(1,roles,binding.buildingId(),"YEAR","2026");
        assertThat(year.points().get(9).value()).isNull();assertThat(year.points().get(9).validHours()).isEqualTo(29*24);
        assertThat(year.points().get(9).status()).isEqualTo("IN_PROGRESS");
        clearInvocations(repository);
        var future=service.query(1,roles,binding.buildingId(),"YEAR","2027");assertThat(future.status()).isEqualTo("NOT_STARTED");verifyNoInteractions(repository);
    }
    @Test void missingAndDisabledLocationsDoNotReadDatasetsOrFallBackToForecasts() {
        when(repository.effective(eq(binding.buildingId()),any())).thenReturn(null);
        var view=service.query(1,roles,binding.buildingId(),"MONTH","2025-01");
        assertThat(view.status()).isEqualTo("NOT_CONFIGURED");assertThat(view.points()).allSatisfy(p->assertThat(p.value()).isNull());
        verify(repository,never()).headId(anyString(),any(),any(),any());assertThat(view.sources()).isEmpty();
        when(repository.effective(eq(binding.buildingId()),any())).thenReturn(new Binding(binding.id(),binding.buildingId(),7,"禁用",30,120,null,"CITY",Instant.EPOCH,null,false));
        assertThat(service.query(1,roles,binding.buildingId(),"MONTH","2025-01").status()).isEqualTo("NOT_CONFIGURED");
    }
    @Test void rejectsCrossScopeWrongProductAndUnboundedOrAmbiguousPeriods() {
        doThrow(new BusinessException(403,"DENIED","建筑拒绝")).when(access).reader(8,roles,binding.buildingId());
        assertThatThrownBy(()->service.query(8,roles,binding.buildingId(),"YEAR","2024")).hasMessageContaining("建筑拒绝");verifyNoInteractions(repository);
        for(String period:List.of("2023","99999","2024-01"))assertThatThrownBy(()->service.query(1,roles,binding.buildingId(),"YEAR",period)).isInstanceOf(BusinessException.class);
        publish(samples(LocalDate.of(2025,1,1),LocalDate.of(2025,2,1),day->10d));
        when(repository.dataset("DATA_A")).thenReturn(new Dataset("DATA_A",binding.id(),Source.OPEN_METEO,Product.FORECAST_HOURLY,Instant.now(),"PUBLISHED","unused",data("DATA_A",binding,List.of()).result()));
        assertThatThrownBy(()->service.query(1,roles,binding.buildingId(),"MONTH","2025-01")).hasMessageContaining("身份不一致");
    }
    @Test void preservesHistoricalLocationVersionsAndDifferentSourceIdentifiersWithoutCodeChanges() {
        var next=new Binding("LOCATION_B",binding.buildingId(),8,"新位置",31,121,null,"CITY",LocalDate.of(2024,1,16).atStartOfDay(ZONE).toInstant(),null,true);
        when(repository.effective(eq(binding.buildingId()),any())).thenAnswer(call->((Instant)call.getArgument(1)).isBefore(next.effectiveFrom())?binding:next);
        var first=data("OLD_PAYLOAD",binding,samples(LocalDate.of(2024,1,1),LocalDate.of(2024,1,16),day->10d));
        var second=data("NEW_PAYLOAD",next,samples(LocalDate.of(2024,1,16),LocalDate.of(2024,2,1),day->20d));
        when(repository.headId(eq(binding.id()),eq(Source.OPEN_METEO),eq(Product.HISTORY_HOURLY),any())).thenReturn(first.id());
        when(repository.headId(eq(next.id()),eq(Source.OPEN_METEO),eq(Product.HISTORY_HOURLY),any())).thenReturn(second.id());
        when(repository.dataset(first.id())).thenReturn(first);when(repository.dataset(second.id())).thenReturn(second);
        var view=service.query(1,roles,binding.buildingId(),"YEAR","2024");
        assertThat(view.points().getFirst().status()).isEqualTo("READY");assertThat(view.points().getFirst().reasons()).contains("MULTIPLE_LOCATION_VERSIONS");
        assertThat(view.points().getFirst().value()).isEqualByComparingTo(new java.math.BigDecimal("470").divide(new java.math.BigDecimal("31"),java.math.MathContext.DECIMAL128));
        assertThat(view.sources()).extracting(WeatherTemperatureContracts.Source::bindingId).containsExactlyInAnyOrder(binding.id(),next.id());
    }
}
