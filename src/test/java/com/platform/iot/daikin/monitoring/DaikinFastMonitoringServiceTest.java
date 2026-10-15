package com.platform.iot.daikin.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.control.*;
import com.platform.iot.daikin.model.*;
import com.platform.iot.daikin.monitoring.state.DaikinMonitoringStateService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.awaitility.Awaitility.await;

class DaikinFastMonitoringServiceTest {
    private final AtomicLong now=new AtomicLong(1792027800000L);
    private final ObjectMapper mapper=new ObjectMapper();
    private final DaikinControlAccess access=mock(DaikinControlAccess.class);
    private final DaikinControlGrantReader reader=mock(DaikinControlGrantReader.class);
    private final DaikinMonitoringTargets targets=mock(DaikinMonitoringTargets.class);
    private final DaikinMonitoringStateService state=mock(DaikinMonitoringStateService.class);
    private final DaikinMonitoringProperties properties=new DaikinMonitoringProperties();
    private final DaikinControlAccess.Target target=new DaikinControlAccess.Target("device","building","identity",
            "source","site","controller","unit","vendor","pending",1,0,false,null,null,null);
    private JdbcTemplate jdbc;
    private DaikinFastMonitoringService service;
    private Clock clock;

    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:fast-monitor-"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE biz_daikin_control_operation(operation_id VARCHAR(32),operator_id BIGINT,target_json TEXT,updated_at BIGINT,status VARCHAR(32),sent_at BIGINT,next_check_at BIGINT,deadline_at BIGINT)");
        jdbc.execute("CREATE TABLE biz_daikin_control_slot(physical_key VARCHAR(64),operation_id VARCHAR(32))");
        jdbc.execute("CREATE TABLE biz_daikin_control_controller_slot(controller_key VARCHAR(64),operation_id VARCHAR(32))");
        jdbc.execute("CREATE TABLE biz_daikin_source(source_id VARCHAR(200))");
        jdbc.update("INSERT INTO biz_daikin_source VALUES ('source')");
        properties.setEnabled(true);
        when(access.candidate("device")).thenReturn(target);
        when(reader.supports("source")).thenReturn(true);
        when(reader.read(target)).thenReturn(new DaikinControlGateway.Snapshot(mapper.readTree("{}"),
                mapper.readTree("{\"unitId\":\"unit\",\"onOff\":\"off\",\"unitStatus\":\"stopped\",\"temperature\":28}")));
        when(targets.find("pending")).thenReturn(Optional.of(new DaikinMonitoringTargets.Target(
                new DaikinDeviceKey("source","site","controller",DaikinDeviceKey.Kind.INDOOR,"unit"),
                "pending","identity","identity-value","profile","device","IDU","building",null,null,1)));
        clock=new Clock() {
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return Instant.ofEpochMilli(now.get());}
        };
        service=new DaikinFastMonitoringService(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),
                properties,access,List.of(reader),targets,state,mapper,clock);
    }
    @AfterEach void close(){service.stop();}

    @Test void multipleUsersShareOneDevicePollAndOneUnwatchDoesNotStopOtherViewer() {
        service.watch(1,"device","watch-one");service.watch(2,"device","watch-two");
        assertThat(service.desired()).hasSize(1);
        verify(reader,never()).read(any());
        service.unwatch(1,"device","watch-one");
        service.refresh(service.desired().get("device"));
        verify(reader,times(1)).read(target);
        verify(state).observeFast(any(),argThat(observation -> observation.fields().get("onOff").normalizedValue().equals("off")
                && !observation.fields().containsKey("temperature") && !observation.fields().containsKey("fanSpeed")));
        service.unwatch(2,"device","watch-two");
        assertThat(service.desired()).isEmpty();
    }

    @Test void failedOrRevokedReadDoesNotPublishAndHiddenViewerExpires() {
        service.watch(1,"device","watch-one");
        var owners=service.desired().get("device");
        when(reader.read(target)).thenAnswer(call -> {service.unwatch(1,"device","watch-one");return new DaikinControlGateway.Snapshot(mapper.readTree("{}"),mapper.readTree("{\"onOff\":\"on\"}"));});
        service.refresh(owners);
        verifyNoInteractions(state);
        service.watch(2,"device","watch-two");
        doThrow(new BusinessException(403,"denied")).when(access).requireRead(2,"device");
        service.refresh(service.desired().get("device"));
        verify(reader,times(1)).read(target);
        now.addAndGet(15_001);
        assertThat(service.desired()).isEmpty();
    }

    @Test void followsPersistedCompletionWithoutViewerAndExpiresAtOriginalDeadline() throws Exception {
        jdbc.update("INSERT INTO biz_daikin_control_operation(operator_id,target_json,updated_at,status,sent_at) VALUES (1,'null',?,'CLOUD_VERIFIED',?)",now.get(),now.get()-30000);
        jdbc.update("INSERT INTO biz_daikin_control_operation(operator_id,target_json,updated_at,status,sent_at) VALUES (1,?,?,'CLOUD_VERIFIED',?)",mapper.writeValueAsString(target),now.get(),now.get()-30000);
        assertThat(service.desired()).containsKey("device");
        service.refresh(service.desired().get("device"));
        verify(state).observeFast(any(),any());
        now.addAndGet(180_000);
        assertThat(service.desired()).isEmpty();
        jdbc.update("UPDATE biz_daikin_control_operation SET updated_at=?,status='UNKNOWN'",now.get());
        assertThat(service.desired()).isEmpty();
    }

    @Test void activeControllerReadbackHasPriorityAndDisabledMonitoringNeverReads() {
        jdbc.update("INSERT INTO biz_daikin_control_controller_slot VALUES (?,'active')",target.controllerKey());
        jdbc.update("INSERT INTO biz_daikin_control_operation(operation_id,next_check_at,deadline_at) VALUES ('active',?,?)",now.get(),now.get()+120000);
        service.watch(1,"device","watch-one");
        service.refresh(service.desired().get("device"));
        verify(reader,never()).read(any());
        properties.setEnabled(false);
        assertThat(service.watch(1,"device","watch-one").enabled()).isFalse();
        service.schedule();
        verify(reader,never()).read(any());
    }

    @Test void schedulingDoesNotOverlapSameDeviceAndBacksOffOnFailure() throws Exception {
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        when(reader.read(target)).thenAnswer(call -> {started.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();throw new IllegalStateException("stub failure");});
        service.watch(1,"device","watch-one");
        service.schedule();assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
        service.schedule();verify(reader,times(1)).read(target);
        release.countDown();
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            now.addAndGet(0);service.schedule();verify(reader,times(1)).read(target);
        });
        // 即使再次续期也不能绕过失败退避；恢复时间之前不增加厂家请求。
        now.addAndGet(5000);service.watch(1,"device","watch-one");service.schedule();
        verify(reader,times(1)).read(target);
    }

    @Test void identityChangeDuringNetworkReadRejectsCommitAndNoControlCapabilityIsUsed() {
        service.watch(1,"device","watch-one");
        when(reader.read(target)).thenAnswer(call -> {
            when(targets.find("pending")).thenReturn(Optional.empty());
            return new DaikinControlGateway.Snapshot(mapper.readTree("{}"),mapper.readTree("{\"onOff\":\"off\"}"));
        });
        service.refresh(service.desired().get("device"));
        verifyNoInteractions(state);
        assertThatThrownBy(() -> service.watch(1,"device","bad")).isInstanceOf(BusinessException.class);
        doThrow(new BusinessException(403,"denied")).when(access).requireRead(7,"device");
        assertThatThrownBy(() -> service.watch(7,"device","watch-seven")).isInstanceOf(BusinessException.class);
    }

    @Test void permissionRevokedDuringReadDiscardsResultAndCapacityIsBounded() {
        service.watch(1,"device","watch-one");
        when(reader.read(target)).thenAnswer(call -> {
            doThrow(new BusinessException(403,"denied")).when(access).requireRead(1,"device");
            return new DaikinControlGateway.Snapshot(mapper.readTree("{}"),mapper.readTree("{\"onOff\":\"off\"}"));
        });
        service.refresh(service.desired().get("device"));
        verifyNoInteractions(state);
        for(int i=0;i<32;i++) service.watch(2,"device","watch-user2-"+i);
        assertThatThrownBy(() -> service.watch(2,"device","watch-user2-overflow"))
                .isInstanceOfSatisfying(BusinessException.class,error -> assertThat(error.getCode()).isEqualTo(429));
        now.addAndGet(15001);
        assertThat(service.watch(2,"device","watch-user2-new").enabled()).isTrue();
    }

    @Test void realStateTransactionObservesLocalReversalAfterViewerClosesWithoutRewritingCompletedControl() throws Exception {
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("daikin-monitoring-state-test.sql")).execute(jdbc.getDataSource());
        var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        var realState=new DaikinMonitoringStateService(jdbc,tx);
        var trusted=targets.find("pending").orElseThrow();
        var stateTarget=DaikinObservationInbox.stateTarget(trusted);
        realState.registerTarget(stateTarget,now.get());
        realState.observe(stateTarget,now.get(),new DaikinDeviceObservation(trusted.key(),"vendor",null,null,
                Instant.ofEpochMilli(now.get()),null,Map.of(
                "onOff",new DaikinFieldValue(DaikinFieldValue.Status.PRESENT,"\"off\"","off"),
                "unitStatus",new DaikinFieldValue(DaikinFieldValue.Status.PRESENT,"\"stopped\"","stopped"),
                "mode",new DaikinFieldValue(DaikinFieldValue.Status.PRESENT,"\"cooling\"","cooling"))));
        service.stop();
        service=new DaikinFastMonitoringService(jdbc,tx,properties,access,List.of(reader),targets,realState,mapper,clock);
        service.watch(1,"device","watch-one");
        now.addAndGet(5000);
        when(reader.read(target)).thenReturn(new DaikinControlGateway.Snapshot(mapper.readTree("{}"),
                mapper.readTree("{\"onOff\":\"on\",\"unitStatus\":\"operating\"}")));
        service.refresh(service.desired().get("device"));
        jdbc.update("INSERT INTO biz_daikin_control_operation(operator_id,target_json,updated_at,status,sent_at) VALUES (1,?,?,'CLOUD_VERIFIED',?)",
                mapper.writeValueAsString(target),now.get(),now.get()-1000);
        service.unwatch(1,"device","watch-one");
        now.addAndGet(5000);
        when(reader.read(target)).thenReturn(new DaikinControlGateway.Snapshot(mapper.readTree("{}"),
                mapper.readTree("{\"onOff\":\"off\",\"unitStatus\":\"stopped\"}")));
        service.refresh(service.desired().get("device"));
        assertThat(jdbc.queryForObject("SELECT normalized_value FROM biz_daikin_current_state WHERE field_name='onOff'",String.class)).isEqualTo("off");
        assertThat(jdbc.queryForList("SELECT after_normalized_value FROM biz_daikin_state_event WHERE field_name='onOff' ORDER BY observed_at_ms",String.class)).containsExactly("on","off");
        assertThat(jdbc.queryForObject("SELECT field_status FROM biz_daikin_current_state WHERE field_name='mode'",String.class)).isEqualTo("PRESENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_operation WHERE status='CLOUD_VERIFIED'",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT SUM(on_ms) FROM biz_daikin_observed_runtime_day",Long.class)).isEqualTo(5000);
        now.addAndGet(180000);
        assertThat(service.desired()).isEmpty();
    }
}
