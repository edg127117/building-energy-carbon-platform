package com.platform.alarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.SecurityAuditService;
import com.platform.framework.exception.GlobalExceptionHandler;
import com.platform.iot.temporal.model.LatestRawReading;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import com.platform.security.JwtUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import static com.platform.alarm.AlarmChainTest.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立H2及受控采集事实的HTTP验收，不连接B版、服务器或真实设备。 */
class AlarmEightFeatureContractTest {
    AlarmChainTest f;
    MockMvc mvc;
    UsernamePasswordAuthenticationToken user;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String base="/v1/alarms/buildings/A";
    @BeforeEach void setup() throws Exception {
        f=new AlarmChainTest(); f.setup();
        ReflectionTestUtils.setField((Object)AopTestUtils.getTargetObject(f.service),"rawEvents",f.raw);
        mvc=MockMvcBuilders.standaloneSetup(new AlarmController(f.service))
                .setControllerAdvice(new GlobalExceptionHandler(mock(SecurityAuditService.class))).build();
        user=new UsernamePasswordAuthenticationToken(new JwtUserPrincipal(1L,"admin"),null,
                List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")));
    }

    String create(String body) throws Exception {
        return json.readTree(mvc.perform(post(base+"/rules").principal(user).contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").asText();
    }
    @Test void nestedCrossDeviceConfigurationKeepsOldRulesAndRejectsOtherBuilding() throws Exception {
        f.jdbc.update("INSERT INTO biz_equipment VALUES ('M3','机组状态','A','S3','METER','HVAC',0)");
        f.jdbc.update("INSERT INTO biz_data_point VALUES ('P3','M3','A','ONLINE','运行模式','','DIGITAL',0)");
        String body="""
                {"type":"CONDITION","name":"关机仍耗电","severity":"IMPORTANT","qualityLevel":"Q0","enabled":true,
                 "parameter":1,"revision":0,"recoveryCycles":2,"triggerMinutes":0,"recoveryMinutes":0,"spaceId":"S1",
                 "advanced":{"primaryEquipmentId":"M1","timing":{"expectedIntervalSeconds":300,"alignmentSeconds":10,"staleAfterSeconds":600},
                  "trigger":{"logic":"ALL","children":[{"condition":{"pointId":"P1","operator":"GT","value":5}},
                    {"logic":"ANY","children":[{"condition":{"pointId":"P3","operator":"EQ","value":0}}]}]},
                  "recovery":{"logic":"ALL","children":[{"condition":{"pointId":"P1","operator":"LT","value":3}}]}}}
                """;
        String id=create(body);
        mvc.perform(get(base+"/rules/"+id+"/history").principal(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].advanced.trigger.children[1].logic").value("ANY"))
                .andExpect(jsonPath("$.data[0].advanced.recovery.children[0].condition.value").value(3));
        mvc.perform(post(base+"/rules").principal(user).contentType("application/json")
                .content(body.replace("\"primaryEquipmentId\":\"M1\"","\"primaryEquipmentId\":\"M2\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(put(base+"/rules/OFFLINE").principal(user).contentType("application/json")
                .content("{\"enabled\":false,\"parameter\":3,\"revision\":1}"))
                .andExpect(status().isOk());
        assertThat(f.service.rules(1L,ADMIN,"A")).anyMatch(rule->rule.id().equals(id)).anyMatch(rule->rule.type().equals("OFFLINE"));
    }

    @Test void dictionaryUpgradeFreezesSnapshotsAndRollsBackWhenCodesAreRemoved() throws Exception {
        String dict=json.readTree(mvc.perform(post(base+"/points/P2/state-dictionaries").principal(user).contentType("application/json")
                .content("{\"name\":\"启停\",\"states\":[{\"value\":0,\"label\":\"关机\"},{\"value\":1,\"label\":\"开机\"}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").asText();
        String rule=create("""
                {"type":"CONDITION","name":"停机状态","severity":"IMPORTANT","qualityLevel":"Q0","enabled":false,"parameter":1,"revision":0,
                 "conditions":[{"pointId":"P2","operator":"EQ","value":0,"dictionaryId":"%s","dictionaryRevision":1}]}
                """.formatted(dict));
        mvc.perform(put(base+"/state-dictionaries/"+dict).principal(user).contentType("application/json")
                .content("{\"name\":\"启停\",\"revision\":1,\"states\":[{\"value\":0,\"label\":\"停止\"},{\"value\":1,\"label\":\"运行\"}]}"))
                .andExpect(status().isOk());
        assertThat(f.service.rules(1L,ADMIN,"A").stream().filter(r->r.id().equals(rule)).findFirst().orElseThrow().conditions().getFirst().states().getFirst().label()).isEqualTo("关机");
        mvc.perform(post(base+"/state-dictionaries/"+dict+"/apply").principal(user).contentType("application/json")
                .content("{\"dictionaryRevision\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(1));
        mvc.perform(get(base+"/rules/"+rule+"/history").principal(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].conditions[0].states[0].label").value("停止"))
                .andExpect(jsonPath("$.data[1].conditions[0].states[0].label").value("关机"));
        mvc.perform(put(base+"/state-dictionaries/"+dict).principal(user).contentType("application/json")
                .content("{\"name\":\"启停\",\"revision\":2,\"states\":[{\"value\":7,\"label\":\"未知新编码\"}]}"))
                .andExpect(status().isOk());
        mvc.perform(post(base+"/state-dictionaries/"+dict+"/apply").principal(user).contentType("application/json")
                .content("{\"dictionaryRevision\":3}"))
                .andExpect(status().isBadRequest());
        assertThat(f.service.rules(1L,ADMIN,"A").stream().filter(r->r.id().equals(rule)).findFirst().orElseThrow().revision()).isEqualTo(2);
        mvc.perform(get(base+"/rules/options").principal(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points[?(@.value=='P2')].dictionaryRevision").value(2));
        mvc.perform(post(base+"/points/P1/state-dictionaries").principal(user).contentType("application/json")
                .content("{\"name\":\"不应写入\",\"states\":[{\"value\":1,\"label\":\"开\"}]}"))
                .andExpect(status().isBadRequest());
        assertThat(f.service.dictionaries(1L,ADMIN,"A")).hasSize(1);
    }

    @Test void crossDeviceTriggerDeadbandAndTwoFreshRecoveryCyclesProduceScreenReplay() throws Exception {
        f.service.rules(1L,ADMIN,"A");
        f.jdbc.update("UPDATE biz_alarm_rule SET enabled=0 WHERE building_id='A'");
        f.jdbc.update("INSERT INTO biz_equipment VALUES ('M3','关联空调','A','S3','METER','HVAC',0)");
        f.jdbc.update("INSERT INTO biz_data_point VALUES ('P3','M3','A','ONLINE','运行状态','','DIGITAL',0)");
        create("""
                {"type":"CONDITION","name":"关机仍耗电","severity":"IMPORTANT","qualityLevel":"Q0","enabled":true,
                 "parameter":1,"revision":0,"recoveryCycles":2,"triggerMinutes":0,"recoveryMinutes":0,"classification":"FAULT",
                 "advanced":{"primaryEquipmentId":"M1","timing":{"expectedIntervalSeconds":300,"allowedDelaySeconds":10,"alignmentSeconds":10,"staleAfterSeconds":600},
                  "trigger":{"logic":"ALL","children":[{"condition":{"pointId":"P1","operator":"GT","value":5}},
                   {"logic":"ANY","children":[{"condition":{"pointId":"P3","operator":"EQ","value":0,"states":[{"value":0,"label":"关机"},{"value":1,"label":"开机"}]}}]}]},
                  "recovery":{"logic":"ALL","children":[{"condition":{"pointId":"P1","operator":"LT","value":3}}]}}}
                """);
        var stages=new LinkedHashMap<String,Object>();
        setCrossValues(8); f.service.evaluate("A");
        assertThat(f.service.summary(1L,ADMIN,"A").deviceAnomalies()).isEqualTo(1);
        stages.put("trigger",snapshot());
        f.clock.advance(300_000); setCrossValues(4); f.service.evaluate("A");
        assertThat(f.service.events(1L,ADMIN,"A",false,"DEVICE",1,20).total()).isEqualTo(1);
        stages.put("deadband",snapshot());
        f.clock.advance(300_000); setCrossValues(2); f.service.evaluate("A"); f.service.evaluate("A");
        assertThat(f.service.events(1L,ADMIN,"A",false,"DEVICE",1,20).total()).isEqualTo(1);
        stages.put("recoveryOne",snapshot());
        f.clock.advance(300_000); setCrossValues(2); f.service.evaluate("A");
        assertThat(f.service.events(1L,ADMIN,"A",false,"DEVICE",1,20).total()).isZero();
        stages.put("recovered",snapshot());
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/alarm-eight-screen-replay.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(stages));
    }
    void setCrossValues(double power) {
        when(f.raw.findLatestByEquipmentPoints(eq("A"),eq("M1"),anyCollection())).thenReturn(List.of(new LatestRawReading("P1",power,f.clock.millis(),f.clock.millis(),0)));
        when(f.raw.findLatestByEquipmentPoints(eq("A"),eq("M3"),anyCollection())).thenReturn(List.of(new LatestRawReading("P3",0,f.clock.millis(),f.clock.millis(),0)));
    }
    @Test void slowQualitySamplingUsesReportingCyclesRatherThanAlignmentBuckets() throws Exception {
        f.service.rules(1L,ADMIN,"A");
        f.jdbc.update("UPDATE biz_alarm_rule SET enabled=0 WHERE building_id='A'");
        create("""
                {"type":"QUALITY","name":"慢采样质量","severity":"URGENT","qualityLevel":"Q0","enabled":true,"parameter":1,"revision":0,
                 "recoveryCycles":2,"triggerMinutes":5,"recoveryMinutes":0,"conditions":[{"pointId":"P1"}],
                 "advanced":{"timing":{"expectedIntervalSeconds":300,"alignmentSeconds":10}}}
                """);
        setQuality(2); f.service.evaluate("A");
        assertThat(f.service.events(1L,ADMIN,"A",false,"DEVICE",1,20).total()).isZero();
        f.clock.advance(300_000);setQuality(2);f.service.evaluate("A");
        assertThat(f.service.summary(1L,ADMIN,"A").deviceGroups()).contains(new AlarmModels.Group("QUALITY",1));
        f.clock.advance(300_000);setQuality(0);f.service.evaluate("A");f.service.evaluate("A");
        assertThat(f.service.events(1L,ADMIN,"A",false,"DEVICE",1,20).total()).isEqualTo(1);
        f.clock.advance(300_000);setQuality(0);f.service.evaluate("A");
        assertThat(f.service.events(1L,ADMIN,"A",false,"DEVICE",1,20).total()).isZero();
    }
    void setQuality(int quality) {
        when(f.minutes.findLatestByPointIds(anyList())).thenReturn(List.of(new com.platform.iot.temporal.model.HvacMinuteQueryRow("P1",f.clock.millis(),12,12,12,1,quality)));
    }
    Object snapshot() throws Exception {
        var result=new LinkedHashMap<String,Object>();
        result.put("summary",f.service.summary(1L,ADMIN,"A"));
        result.put("rules",f.service.rules(1L,ADMIN,"A"));
        result.put("options",f.service.ruleOptions(1L,ADMIN,"A"));
        result.put("templates",f.service.templates(1L,ADMIN,"A"));
        result.put("events",f.service.events(1L,ADMIN,"A",false,"DEVICE",1,20));
        result.put("history",f.service.events(1L,ADMIN,"A",true,"DEVICE",1,20));
        return result;
    }

    @Test void trendMaintenanceRecoveryAndManualCloseReplayActualHttpResults() throws Exception {
        f.service.evaluate("A");
        String id=f.service.events(1L,ADMIN,"A",false,"DEVICE",1,20).items().getFirst().id();
        mvc.perform(post(base+"/events/"+id+"/close").principal(user).contentType("application/json").content("{\"note\":\"过早关单\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post(base+"/events/"+id+"/silence").principal(user).contentType("application/json").content("{\"minutes\":120,\"note\":\"检修两小时\"}"))
                .andExpect(status().isOk());
        assertThat(f.service.detail(1L,ADMIN,"A",id).silencedUntil()).isEqualTo(f.clock.millis()+7_200_000);
        when(f.raw.findPointHistory(eq("A"),eq("M1"),eq("P1"),anyLong(),anyLong(),isNull(),eq(2001)))
                .thenReturn(List.of(raw("P1","A",4,f.clock.millis()-10_000),raw("P1","B",999,f.clock.millis()-9_000)));
        mvc.perform(get(base+"/events/"+id+"/trend").param("from",String.valueOf(f.clock.millis()-60_000)).param("to",String.valueOf(f.clock.millis())).principal(user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].samples.length()").value(1))
                .andExpect(jsonPath("$.data[0].samples[0].value").value(4));
        mvc.perform(get(base+"/events/"+id+"/trend").param("from","0").param("to",String.valueOf(f.clock.millis())).principal(user)).andExpect(status().isBadRequest());
        when(f.raw.findLatestByEquipmentPoints(eq("A"),eq("M1"),anyCollection())).thenReturn(List.of(new LatestRawReading("P1",4,f.clock.millis()-1000,f.clock.millis(),0)));
        f.service.evaluate("A");
        mvc.perform(post(base+"/events/"+id+"/close").principal(user).contentType("application/json").content("{\"note\":\"恢复已确认\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(base+"/events").param("history","true").param("state","CLOSED").principal(user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/v1/alarms/buildings/B/events/"+id+"/trend").param("from",String.valueOf(f.clock.millis()-60000)).param("to",String.valueOf(f.clock.millis())).principal(user))
                .andExpect(status().isNotFound());
        var replay=new LinkedHashMap<String,Object>();
        replay.put("detail",json.readTree(mvc.perform(get(base+"/events/"+id).principal(user)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)));
        replay.put("summary",json.readTree(mvc.perform(get(base+"/summary").principal(user)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/alarm-eight-http-replay.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(replay));
    }

    @Test void genericFaultSourceCanProveHealthyCoverageWithoutDaikinTargets() throws Exception {
        f.service.rules(1L,ADMIN,"A");f.jdbc.update("UPDATE biz_alarm_rule SET enabled=0 WHERE building_id='A' AND rule_type<>'FAULT'");
        f.jdbc.update("INSERT INTO biz_device_identity VALUES ('I1','M1','A',1)");
        var active=new java.util.concurrent.atomic.AtomicBoolean(true);
        AlarmFaultSource source=building->List.of(new AlarmFaultSource.FaultReading("I1",building,"M1","documented-test-fault",active.get(),f.clock.millis(),"模拟厂商已解析故障"));
        ReflectionTestUtils.setField((Object)AopTestUtils.getTargetObject(f.service),"devices",new CollectedAlarmDeviceSource(f.jdbc,f.runtime,f.raw,f.minutes,json,List.of(source)));
        f.service.evaluate("A");assertThat(f.service.summary(1L,ADMIN,"A").deviceGroups()).contains(new AlarmModels.Group("FAULT",1));
        active.set(false);f.clock.advance(30_000);f.service.evaluate("A");
        assertThat(f.service.summary(1L,ADMIN,"A").deviceAnomalies()).isZero();
        assertThat(f.service.events(1L,ADMIN,"A",true,"DEVICE",1,20).total()).isEqualTo(1);
    }

    @Test void daikinFaultRecoveryRequiresConsecutiveDistinctHealthyRounds() throws Exception {
        f.service.rules(1L, ADMIN, "A");
        f.jdbc.update("UPDATE biz_alarm_rule SET enabled=0 WHERE building_id='A' AND rule_type<>'FAULT'");
        f.jdbc.update("UPDATE biz_alarm_rule SET recovery_cycles=2 WHERE building_id='A' AND rule_type='FAULT'");
        f.jdbc.update("INSERT INTO biz_device_identity VALUES ('I1','M1','A',1)");
        var readings = new java.util.concurrent.atomic.AtomicReference<List<AlarmFaultSource.FaultReading>>();
        AlarmFaultSource source = building -> readings.get();
        ReflectionTestUtils.setField((Object) AopTestUtils.getTargetObject(f.service), "devices",
                new CollectedAlarmDeviceSource(f.jdbc, f.runtime, f.raw, f.minutes, json, List.of(source)));
        long start = f.clock.millis();
        long cycle = start / 60_000;
        readings.set(List.of(new AlarmFaultSource.FaultReading("I1", "A", "M1", "VENDOR_EQUIPMENT",
                true, start, "厂家设备故障", cycle)));
        f.service.evaluate("A");
        assertThat(f.service.summary(1L, ADMIN, "A").deviceAnomalies()).isOne();

        f.clock.advance(60_000);
        var healthy = new AlarmFaultSource.FaultReading("I1", "A", "M1", "VENDOR_OBSERVATION",
                false, f.clock.millis(), "厂家故障状态观测", cycle + 1);
        readings.set(List.of(healthy, new AlarmFaultSource.FaultReading("I1", "A", "M1", "VENDOR_COMMUNICATION",
                true, f.clock.millis(), "厂家通信故障仍存在", cycle + 1)));
        f.service.evaluate("A");
        assertThat(f.service.summary(1L, ADMIN, "A").deviceAnomalies()).isOne();

        f.clock.advance(60_000);
        var firstHealthy = new AlarmFaultSource.FaultReading("I1", "A", "M1", "VENDOR_OBSERVATION",
                false, f.clock.millis(), "厂家故障状态观测", cycle + 2);
        readings.set(List.of(firstHealthy));
        f.service.evaluate("A");
        f.service.evaluate("A");
        assertThat(f.service.summary(1L, ADMIN, "A").deviceAnomalies()).isOne();

        f.clock.advance(360_000);
        f.service.evaluate("A");
        readings.set(List.of(new AlarmFaultSource.FaultReading("I1", "A", "M1", "VENDOR_OBSERVATION",
                null, 0, "无有效故障状态", cycle + 8)));
        f.service.evaluate("A");
        readings.set(List.of(healthy));
        f.service.evaluate("A");
        assertThat(f.service.summary(1L, ADMIN, "A").deviceAnomalies()).isOne();

        for (long next : List.of(cycle + 9, cycle + 10)) {
            f.clock.advance(60_000);
            readings.set(List.of(new AlarmFaultSource.FaultReading("I1", "A", "M1", "VENDOR_OBSERVATION",
                    false, f.clock.millis(), "厂家故障状态观测", next)));
            f.service.evaluate("A");
            assertThat(f.service.summary(1L, ADMIN, "A").deviceAnomalies()).isEqualTo(next == cycle + 9 ? 1L : 0L);
        }
        assertThat(f.service.events(1L, ADMIN, "A", true, "DEVICE", 1, 20).total()).isOne();
    }

    @Test void jumpAndStuckUseMixedSamplingHistoryAndActuallyRecoverInValueGroup() throws Exception {
        f.service.rules(1L,ADMIN,"A");f.jdbc.update("UPDATE biz_alarm_rule SET enabled=0 WHERE building_id='A'");
        for(String type:List.of("JUMP","STUCK")) {
            String rule=create("""
                    {"type":"%s","name":"窗口判定","severity":"IMPORTANT","qualityLevel":"Q0","enabled":true,"parameter":1,"revision":0,
                     "recoveryCycles":1,"triggerMinutes":0,"recoveryMinutes":0,"conditions":[{"pointId":"P1","operator":"GT","value":0}],
                     "advanced":{"primaryEquipmentId":"M1","timing":{"expectedIntervalSeconds":60,"alignmentSeconds":10},
                      "window":{"minutes":10,"tolerance":5,"changeMode":"ABS","runningPointId":"P2","runningValue":1}}}
                    """.formatted(type));
            setWindow(type.equals("JUMP"));f.service.evaluate("A");
            assertThat(f.service.summary(1L,ADMIN,"A").deviceGroups()).contains(new AlarmModels.Group("VALUE",1));
            assertThat(f.service.events(1L,ADMIN,"A",false,"DEVICE",1,20).items()).singleElement().satisfies(event->assertThat(event.ruleId()).isEqualTo(rule));
            f.clock.advance(300_000);setWindow(!type.equals("JUMP"));f.service.evaluate("A");
            assertThat(f.service.events(1L,ADMIN,"A",false,"DEVICE",1,20).total()).isZero();
            assertThat(f.service.events(1L,ADMIN,"A",true,"DEVICE",1,20).items()).anyMatch(event->event.ruleId().equals(rule));
            f.jdbc.update("UPDATE biz_alarm_rule SET enabled=0 WHERE rule_id=?",rule);
        }
    }
    @Test void aSlowReferenceCannotFreshenFastInputAndDigitalStateNeverUsesMinuteAverage() throws Exception {
        f.service.rules(1L,ADMIN,"A");f.jdbc.update("UPDATE biz_alarm_rule SET enabled=0 WHERE building_id='A'");
        f.runtime.snapshot().activeVersions().values().forEach(version->version.setExpectedIntervalSeconds(60));
        f.jdbc.update("INSERT INTO biz_equipment VALUES ('M3','关联空调','A','S3','METER','HVAC',0)");
        f.jdbc.update("INSERT INTO biz_data_point VALUES ('P3','M3','A','ONLINE','运行状态','','DIGITAL',0)");
        create("""
                {"type":"CONDITION","name":"异频率联动监测","severity":"IMPORTANT","qualityLevel":"Q1","enabled":true,"parameter":1,"revision":0,
                 "advanced":{"primaryEquipmentId":"M1","timing":{"expectedIntervalSeconds":300,"alignmentSeconds":120},
                  "trigger":{"logic":"ALL","children":[{"condition":{"pointId":"P1","operator":"GT","value":5}},
                   {"condition":{"pointId":"P3","operator":"EQ","value":0}}]}}}
                """);
        long at=f.clock.millis();
        when(f.raw.findLatestByEquipmentPoints(eq("A"),eq("M1"),anyCollection())).thenReturn(List.of(new LatestRawReading("P1",8,at-90_000,at-90_000,0)));
        when(f.raw.findLatestByEquipmentPoints(eq("A"),eq("M3"),anyCollection())).thenReturn(List.of(new LatestRawReading("P3",0,at,at,0)));
        f.service.evaluate("A");
        assertThat(f.service.summary(1L,ADMIN,"A").deviceAnomalies()).isNull();
        assertThat(f.service.events(1L,ADMIN,"A",false,"DEVICE",1,20).total()).isZero();
        when(f.raw.findLatestByEquipmentPoints(eq("A"),eq("M1"),anyCollection())).thenReturn(List.of(new LatestRawReading("P1",8,at,at,0)));
        when(f.minutes.findLatestByPointIds(anyList())).thenReturn(List.of(new com.platform.iot.temporal.model.HvacMinuteQueryRow("P3",at,1,0,1,2,0)));
        f.service.evaluate("A");
        assertThat(f.service.summary(1L,ADMIN,"A").deviceAnomalies()).isEqualTo(1);
    }
    void setWindow(boolean moving) {
        long at=f.clock.millis();
        when(f.raw.findLatestByEquipmentPoints(eq("A"),eq("M1"),anyCollection())).thenReturn(List.of(new LatestRawReading("P1",40,at,at,0),new LatestRawReading("P2",1,at,at,0)));
        when(f.raw.findPointHistory(eq("A"),eq("M1"),eq("P1"),anyLong(),anyLong(),isNull(),eq(5001)))
                .thenReturn(List.of(raw("P1","A",moving?10:40,at-600_000),raw("P1","A",moving?20:40,at-300_000),raw("P1","A",40,at)));
        when(f.raw.findPointHistory(eq("A"),eq("M1"),eq("P2"),anyLong(),anyLong(),isNull(),eq(5001)))
                .thenReturn(java.util.stream.LongStream.rangeClosed(0,10).mapToObj(index->raw("P2","A",1,at-600_000+index*60_000)).toList());
    }

    @Test void sevenTemplatesAreDiscoverableButMissingFormalDataIsUnknown() throws Exception {
        mvc.perform(get(base+"/rule-templates").principal(user)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(7));
        for(String template:List.of("QUOTA_PROGRESS","INTENSITY","MONTH_ON_MONTH","YEAR_ON_YEAR","BASELINE","COMPLETENESS","YEAR_END_FORECAST")) {
            create("""
                    {"type":"EMISSION","name":"%s","qualityLevel":"Q0","enabled":true,"parameter":1,"revision":0,
                     "advanced":{"emission":{"template":"%s","period":"YEAR","threshold":%s,"recoveryThreshold":%s,
                     "changeMode":"PERCENT","baselineModelId":%s,"forecastMethod":%s}}}
                    """.formatted(template,template,template.equals("COMPLETENESS")?"90":"85",template.equals("COMPLETENESS")?"95":"80",template.equals("BASELINE")?"\"confirmed-model\"":"null",template.equals("YEAR_END_FORECAST")?"\"LINEAR_DAY\"":"null"));
        }
        f.service.evaluate("A");
        var summary=f.service.summary(1L,ADMIN,"A");
        assertThat(summary.emissionWarnings()).isNull();
        assertThat(summary.emissionReason()).isNotBlank();
        assertThat(f.service.events(1L,ADMIN,"A",false,"EMISSION",1,20).total()).isZero();
    }
    static RawTelemetryEvent raw(String point,String building,double value,long at) {
        return new RawTelemetryEvent(point,"code","simulated","point","device",building,"group","M1","meter","family","component","suffix",value,at,at,0,1,false);
    }
}
