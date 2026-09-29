package com.platform.weather;

import com.platform.weather.source.*;
import com.platform.weather.source.WeatherSourceModels.*;
import com.platform.weather.energy.DailyElectricityAdapter;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.platform.weather.WeatherModels.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class WeatherWorkerTest {
    WeatherProperties config=new WeatherProperties();
    WeatherRepository repo=mock(WeatherRepository.class);
    WeatherService access=mock(WeatherService.class);
    WeatherSourceClient source=mock(WeatherSourceClient.class);
    WeatherTimeseries ts=mock(WeatherTimeseries.class);
    DailyElectricityAdapter energy=mock(DailyElectricityAdapter.class);
    WeatherWorker worker=new WeatherWorker(config,repo,access,source,ts,energy);
    Job job(String dataset) { return new Job("job","WEATHER","building","b",Source.OPEN_METEO,Product.HISTORY_HOURLY,LocalDate.of(2026,9,20),LocalDate.of(2026,9,20),null,null,1,"RUNNING",1,1,System.currentTimeMillis()+180000,0,dataset,null,0); }
    @Test void disabledWorkerDoesNotTouchExternalResources() {
        try { worker.tick();verifyNoInteractions(repo,source,ts,energy); } finally { worker.close(); }
    }
    @Test void interruptionLeavesLeaseForRecoveryInsteadOfRecordingSourceFailure(org.springframework.boot.test.system.CapturedOutput output) {
        Job j=job("dataset");
        when(repo.dataset("dataset")).thenThrow(new WeatherSourceException(WeatherSourceException.Code.SOURCE_INTERRUPTED));
        try {
            worker.execute(j);
            verify(repo,never()).finish(any(),anyString(),anyString(),anyLong());
            verify(repo,never()).publish(any(),any());
            org.assertj.core.api.Assertions.assertThat(output.getAll()).contains("id=job", "attempt=1", "code=SOURCE_INTERRUPTED");
        } finally { worker.close(); }
    }
    @Test void preparedDatasetReplaysWithoutFetchingAgain() {
        Job j=job("dataset");var data=new Dataset("dataset","b",Source.OPEN_METEO,Product.HISTORY_HOURLY,Instant.now(),"PREPARED","table",null);
        when(repo.dataset("dataset")).thenReturn(data);
        try { worker.execute(j);verifyNoInteractions(source);verify(ts).write(data);verify(repo).publish(j,data);verify(access,times(2)).authorizeJob(j); } finally { worker.close(); }
    }
    @Test void failedTimeseriesWriteCannotPublishHead() {
        Job j=job("dataset");var data=new Dataset("dataset","b",Source.OPEN_METEO,Product.HISTORY_HOURLY,Instant.now(),"PREPARED","table",null);
        when(repo.dataset("dataset")).thenReturn(data);doThrow(new IllegalStateException("simulated")).when(ts).write(data);
        try { worker.execute(j);verify(repo,never()).publish(any(),any());verify(repo).finish(j,"RETRY_WAIT","STORAGE_OR_EXECUTION_FAILED",0); } finally { worker.close(); }
    }
    @Test void storageFailureLogsStageAndSqlCodeWithoutCredentials(org.springframework.boot.test.system.CapturedOutput output) {
        Job j=job("dataset");
        var data=new Dataset("dataset","b",Source.OPEN_METEO,Product.HISTORY_HOURLY,Instant.now(),"PREPARED","table",null);
        when(repo.dataset("dataset")).thenReturn(data);
        doThrow(new IllegalStateException("password=SECRET_PAYLOAD",new java.sql.SQLException("token=SECRET_TOKEN","HY000",1234))).when(ts).write(data);
        try {
            worker.execute(j);
            org.assertj.core.api.Assertions.assertThat(output.getAll()).contains("stage=TIMESERIES_WRITE_VERIFY","sqlState=HY000","vendorCode=1234","rootType=SQLException")
                    .doesNotContain("SECRET_PAYLOAD","SECRET_TOKEN");
            verify(repo,never()).publish(any(),any());
            verify(repo).finish(j,"RETRY_WAIT","STORAGE_OR_EXECUTION_FAILED",0);
        } finally { worker.close(); }
    }
    @Test void replayDecodeFailureIsDistinguishedFromTimeseriesFailure(org.springframework.boot.test.system.CapturedOutput output) {
        Job j=job("dataset");
        when(repo.dataset("dataset")).thenThrow(new IllegalArgumentException("WEATHER_PAYLOAD_INVALID"));
        try {
            worker.execute(j);
            org.assertj.core.api.Assertions.assertThat(output.getAll()).contains("stage=DATASET_LOAD","code=WEATHER_PAYLOAD_INVALID");
            verifyNoInteractions(source,ts);
        } finally { worker.close(); }
    }
}
