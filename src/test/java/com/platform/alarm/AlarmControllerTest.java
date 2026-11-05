package com.platform.alarm;

import com.platform.audit.SecurityAuditService;
import com.platform.framework.exception.GlobalExceptionHandler;
import com.platform.security.JwtUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import com.platform.iot.temporal.model.HvacMinuteQueryRow;
import com.platform.iot.temporal.model.LatestRawReading;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实报警事务服务与隔离存储验证 HTTP 契约，JWT 过滤链沿用全仓安全测试。 */
class AlarmControllerTest {
    AlarmChainTest fixture;
    MockMvc mvc;
    UsernamePasswordAuthenticationToken user;
    @BeforeEach
    void setup() throws Exception {
        fixture = new AlarmChainTest(); fixture.setup(); fixture.service.evaluate("A");
        mvc = MockMvcBuilders.standaloneSetup(new AlarmController(fixture.service))
                .setControllerAdvice(new GlobalExceptionHandler(mock(SecurityAuditService.class))).build();
        user = new UsernamePasswordAuthenticationToken(new JwtUserPrincipal(1L,"admin"),null,
                List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")));
    }
    @Test
    void queryAckAndDetailsUseStableEnvelopeAndRuleRevision() throws Exception {
        String base="/v1/alarms/buildings/A";
        mvc.perform(get(base+"/summary").principal(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceAnomalies").value(1))
                .andExpect(jsonPath("$.data.emissionWarnings").value(0));
        mvc.perform(get(base+"/events").principal(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("OFFLINE"));
        String id=fixture.service.events(1L,AlarmChainTest.ADMIN,"A",false,"DEVICE",1,20).items().getFirst().id();
        mvc.perform(post(base+"/events/"+id+"/ack").principal(user)).andExpect(status().isOk());
        mvc.perform(get(base+"/events/"+id).principal(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ackBy").value(1));
        mvc.perform(put(base+"/rules/OFFLINE").principal(user).contentType("application/json")
                .content("{\"enabled\":false,\"parameter\":3,\"revision\":1}")).andExpect(status().isOk());
        mvc.perform(put(base+"/rules/OFFLINE").principal(user).contentType("application/json")
                .content("{\"enabled\":true,\"parameter\":3,\"revision\":1}")).andExpect(status().isConflict());
    }
    @Test
    void invalidQueriesAndForeignEventAreRejected() throws Exception {
        mvc.perform(get("/v1/alarms/buildings/A/events?size=101").principal(user)).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/alarms/buildings/A/rules").principal(user).contentType("application/json")
                .content("{\"type\":\"VALUE\",\"name\":\"幽灵规则\",\"severity\":\"IMPORTANT\",\"qualityLevel\":\"Q0\",\"enabled\":true,\"parameter\":1,\"revision\":0}")
        ).andExpect(status().isBadRequest());
        String id=fixture.service.events(1L,AlarmChainTest.ADMIN,"A",false,"DEVICE",1,20).items().getFirst().id();
        mvc.perform(get("/v1/alarms/buildings/B/events/"+id).principal(user)).andExpect(status().isNotFound());
        mvc.perform(post("/v1/alarms/buildings/A/events/"+id+"/silence").principal(user)
                .contentType("application/json").content("{\"minutes\":0,\"note\":\"检修\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void scopedRuleHttpContractCreatesEditsAndReadsVersionHistory() throws Exception {
        String base="/v1/alarms/buildings/A";
        mvc.perform(get(base+"/rules/options").principal(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.spaces[0].value").value("S1"))
                .andExpect(jsonPath("$.data.deviceCategories[0].label").value("电表"))
                .andExpect(jsonPath("$.data.points[0].value").value("P1"))
                .andExpect(jsonPath("$.data.points[0].label").value("三层电表 · 功率"))
                .andExpect(jsonPath("$.data.points[0].unit").value("kW"))
                .andExpect(jsonPath("$.data.points[0].equipmentId").value("M1"));
        mvc.perform(post(base+"/rules").principal(user).contentType("application/json")
                .content("{\"type\":\"OFFLINE\",\"name\":\"三楼电表缺报\",\"severity\":\"URGENT\",\"qualityLevel\":\"Q2\",\"spaceId\":\"S1\",\"deviceCategory\":\"ELECTRIC_METER\",\"enabled\":true,\"parameter\":3,\"revision\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isNotEmpty());
        String id=fixture.service.rules(1L,AlarmChainTest.ADMIN,"A").stream().filter(rule->rule.name().equals("三楼电表缺报")).findFirst().orElseThrow().id();
        mvc.perform(put(base+"/rules/"+id).principal(user).contentType("application/json")
                .content("{\"type\":\"OFFLINE\",\"name\":\"三楼电表缺报\",\"severity\":\"GENERAL\",\"qualityLevel\":\"Q1\",\"spaceId\":\"S1\",\"deviceCategory\":\"ELECTRIC_METER\",\"enabled\":true,\"parameter\":4,\"revision\":1}"))
                .andExpect(status().isOk());
        mvc.perform(get(base+"/rules/"+id+"/history").principal(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].parameter_value").value(4))
                .andExpect(jsonPath("$.data[0].qualityLevel").value("Q1"));
    }

    @Test
    void digitalStateOptionsRoundTripThroughRuleListAndRevisionHistory() throws Exception {
        String base="/v1/alarms/buildings/A";
        String create="""
                {"type":"CONDITION","name":"设备状态异常","severity":"IMPORTANT","qualityLevel":"Q0","enabled":false,"parameter":1,"revision":0,
                 "conditionLogic":"ALL","classification":"VALUE","conditions":[{"pointId":"P2","operator":"EQ","value":2,
                 "states":[{"value":1,"label":" 运行 "},{"value":2,"label":" 停止 "}]}]}
                """;
        JsonNode created=new ObjectMapper().readTree(mvc.perform(post(base+"/rules").principal(user).contentType("application/json").content(create))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String id=created.path("data").asText();
        mvc.perform(get(base+"/rules").principal(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='"+id+"')].conditions[0].states[0].label").value("运行"))
                .andExpect(jsonPath("$.data[?(@.id=='"+id+"')].conditions[0].states[1].value").value(2));
        mvc.perform(put(base+"/rules/"+id).principal(user).contentType("application/json")
                .content(create.replace("\"revision\":0","\"revision\":1").replace("\"enabled\":false","\"enabled\":true")))
                .andExpect(status().isOk());
        mvc.perform(get(base+"/rules/"+id+"/history").principal(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].conditions[0].states[0].label").value("运行"))
                .andExpect(jsonPath("$.data[1].conditions[0].states[1].value").value(2));
    }

    @Test
    void rejectsMalformedStateOptionsAndForeignOrAnalogPoints() throws Exception {
        String base="/v1/alarms/buildings/A/rules";
        List<String> conditions=List.of(
                "{\"pointId\":\"P2\",\"operator\":\"EQ\",\"value\":1,\"states\":[{\"value\":1,\"label\":\"开\"},{\"value\":1.0,\"label\":\"关\"}]}",
                "{\"pointId\":\"P2\",\"operator\":\"EQ\",\"value\":1,\"states\":[{\"value\":1,\"label\":\" 开 \"},{\"value\":2,\"label\":\"开\"}]}",
                "{\"pointId\":\"P2\",\"operator\":\"EQ\",\"value\":1,\"states\":[{\"value\":1,\"label\":\" \"}]}",
                "{\"pointId\":\"P2\",\"operator\":\"EQ\",\"value\":3,\"states\":[{\"value\":1,\"label\":\"开\"}]}",
                "{\"pointId\":\"P2\",\"operator\":\"EQ\",\"value\":1,\"upperValue\":2,\"states\":[{\"value\":1,\"label\":\"开\"}]}",
                "{\"pointId\":\"P2\",\"operator\":\"GT\",\"value\":1,\"states\":[{\"value\":1,\"label\":\"开\"}]}",
                "{\"pointId\":\"P1\",\"operator\":\"EQ\",\"value\":1,\"states\":[{\"value\":1,\"label\":\"开\"}]}",
                "{\"pointId\":\"UNKNOWN\",\"operator\":\"EQ\",\"value\":1,\"states\":[{\"value\":1,\"label\":\"开\"}]}"
        );
        for(String condition:conditions) {
            String body="{\"type\":\"CONDITION\",\"name\":\"状态规则\",\"severity\":\"IMPORTANT\",\"qualityLevel\":\"Q0\",\"enabled\":false,\"parameter\":1,\"revision\":0,\"conditions\":["+condition+"]}";
            mvc.perform(post(base).principal(user).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        String tooManyStates=java.util.stream.IntStream.range(0,33).mapToObj(i->"{\"value\":"+i+",\"label\":\"状态"+i+"\"}")
                .collect(java.util.stream.Collectors.joining(","));
        String tooMany="{\"type\":\"CONDITION\",\"name\":\"超量状态\",\"severity\":\"IMPORTANT\",\"qualityLevel\":\"Q0\",\"enabled\":false,\"parameter\":1,\"revision\":0,\"conditions\":[{\"pointId\":\"P2\",\"operator\":\"EQ\",\"value\":1,\"states\":["+tooManyStates+"]}]}";
        mvc.perform(post(base).principal(user).contentType("application/json").content(tooMany)).andExpect(status().isBadRequest());
        String longLabel="x".repeat(41);
        String tooLong="{\"type\":\"CONDITION\",\"name\":\"过长标签\",\"severity\":\"IMPORTANT\",\"qualityLevel\":\"Q0\",\"enabled\":false,\"parameter\":1,\"revision\":0,\"conditions\":[{\"pointId\":\"P2\",\"operator\":\"EQ\",\"value\":1,\"states\":[{\"value\":1,\"label\":\""+longLabel+"\"}]}]}";
        mvc.perform(post(base).principal(user).contentType("application/json").content(tooLong)).andExpect(status().isBadRequest());
        fixture.jdbc.update("INSERT INTO biz_data_point VALUES ('P3','M2','B','ONLINE','异楼状态','编码','DIGITAL',0)");
        String foreign="{\"type\":\"CONDITION\",\"name\":\"跨楼状态\",\"severity\":\"IMPORTANT\",\"qualityLevel\":\"Q0\",\"enabled\":false,\"parameter\":1,\"revision\":0,\"conditions\":[{\"pointId\":\"P3\",\"operator\":\"EQ\",\"value\":1,\"states\":[{\"value\":1,\"label\":\"开\"}]}]}";
        mvc.perform(post(base).principal(user).contentType("application/json").content(foreign)).andExpect(status().isBadRequest());
    }

    @Test
    void legacyConditionJsonWithoutStatesReadsAsEmptyList() throws Exception {
        String id=fixture.service.createRule(1L,AlarmChainTest.ADMIN,"A",new AlarmModels.RuleRequest("CONDITION","旧数字规则","IMPORTANT","Q0",null,null,false,1,0L,
                null,null,null,null,null,List.of(new AlarmModels.Condition("P2","EQ",BigDecimal.ONE,null))));
        fixture.jdbc.update("UPDATE biz_alarm_rule SET conditions_json=? WHERE building_id='A' AND rule_id=?",
                "[{\"pointId\":\"P2\",\"operator\":\"EQ\",\"value\":1}]",id);
        fixture.jdbc.update("UPDATE biz_alarm_rule_version SET conditions_json=? WHERE building_id='A' AND rule_id=?",
                "[{\"pointId\":\"P2\",\"operator\":\"EQ\",\"value\":1}]",id);
        mvc.perform(get("/v1/alarms/buildings/A/rules").principal(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='"+id+"')].conditions[0].states.length()").value(0));
        mvc.perform(get("/v1/alarms/buildings/A/rules/"+id+"/history").principal(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].conditions[0].states.length()").value(0));
    }

    @Test
    void httpQualityConditionAndOfflineReplayProducesActualApiSnapshot() throws Exception {
        String base="/v1/alarms/buildings/A";
        ObjectMapper mapper=new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode replay=mapper.createObjectNode();
        mvc.perform(put(base+"/rules/OFFLINE").principal(user).contentType("application/json")
                .content("{\"enabled\":false,\"parameter\":3,\"revision\":1}")).andExpect(status().isOk());

        String qualityBody="""
                {"type":"QUALITY","name":"测点质量恶化","severity":"IMPORTANT","qualityLevel":"Q1","enabled":true,"parameter":1,"revision":0,
                 "recoveryCycles":1,"triggerMinutes":0,"recoveryMinutes":0,"conditionLogic":"ALL","classification":"QUALITY",
                 "conditions":[{"pointId":"P1","operator":"EQ","value":0}]}
                """;
        JsonNode qualityRequest=mapper.readTree(qualityBody);
        JsonNode qualityCreated=mapper.readTree(mvc.perform(post(base+"/rules").principal(user).contentType("application/json").content(qualityBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String qualityId=qualityCreated.path("data").asText();
        replay.set("qualityRuleRequest",qualityRequest); replay.put("qualityRuleId",qualityId);
        replay.set("ruleOptions",apiData(get(base+"/rules/options")));
        org.mockito.Mockito.when(fixture.minutes.findLatestByPointIds(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(new HvacMinuteQueryRow("P1",fixture.clock.millis(),12,11,13,2,2)));
        fixture.service.evaluate("A");
        JsonNode qualityTriggerSummary=apiData(get(base+"/summary"));
        JsonNode qualityTriggerEvents=apiData(get(base+"/events?type=DEVICE&alarmType=QUALITY"));
        replay.set("qualityTriggerSummary",qualityTriggerSummary); replay.set("qualityTriggerEvents",qualityTriggerEvents);
        fixture.clock.advance(60_000);
        org.mockito.Mockito.when(fixture.minutes.findLatestByPointIds(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(new HvacMinuteQueryRow("P1",fixture.clock.millis(),12,11,13,2,0)));
        fixture.service.evaluate("A");
        replay.set("qualityRecoveredSummary",apiData(get(base+"/summary")));
        replay.set("qualityRecoveredEvents",apiData(get(base+"/events?history=true&type=DEVICE&alarmType=QUALITY")));
        mvc.perform(put(base+"/rules/"+qualityId).principal(user).contentType("application/json")
                .content("{\"type\":\"QUALITY\",\"name\":\"测点质量恶化\",\"severity\":\"IMPORTANT\",\"qualityLevel\":\"Q1\",\"enabled\":false,\"parameter\":1,\"revision\":1,\"recoveryCycles\":1,\"triggerMinutes\":0,\"recoveryMinutes\":0,\"conditionLogic\":\"ALL\",\"classification\":\"QUALITY\",\"conditions\":[{\"pointId\":\"P1\",\"operator\":\"EQ\",\"value\":0}]}")).andExpect(status().isOk());

        String conditionBody="""
                {"type":"CONDITION","name":"功率数值超限","severity":"IMPORTANT","qualityLevel":"Q0","enabled":true,"parameter":1,"revision":0,
                 "recoveryCycles":2,"triggerMinutes":1,"recoveryMinutes":0,"conditionLogic":"ALL","classification":"VALUE",
                 "conditions":[{"pointId":"P1","operator":"GT","value":10},{"pointId":"P2","operator":"EQ","value":1}]}
                """;
        JsonNode conditionRequest=mapper.readTree(conditionBody);
        JsonNode conditionCreated=mapper.readTree(mvc.perform(post(base+"/rules").principal(user).contentType("application/json").content(conditionBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String conditionId=conditionCreated.path("data").asText();
        replay.set("conditionRuleRequest",conditionRequest); replay.put("conditionRuleId",conditionId);
        mockRawValue(12);
        fixture.service.evaluate("A");
        fixture.clock.advance(60_000); mockRawValue(12); fixture.service.evaluate("A");
        JsonNode conditionTriggerSummary=apiData(get(base+"/summary"));
        JsonNode conditionTriggerEvents=apiData(get(base+"/events?type=DEVICE&alarmType=VALUE"));
        replay.set("conditionTriggerSummary",conditionTriggerSummary); replay.set("conditionTriggerEvents",conditionTriggerEvents);
        fixture.clock.advance(60_000); mockRawValue(0); fixture.service.evaluate("A"); fixture.service.evaluate("A");
        replay.set("conditionInterruptedRecoverySummary",apiData(get(base+"/summary")));
        replay.set("conditionInterruptedRecoveryEvents",apiData(get(base+"/events?type=DEVICE&alarmType=VALUE")));
        fixture.clock.advance(60_000); mockRawValue(0); fixture.service.evaluate("A");
        replay.set("conditionRecoveredSummary",apiData(get(base+"/summary")));
        replay.set("conditionRecoveredEvents",apiData(get(base+"/events?history=true&type=DEVICE&alarmType=VALUE")));
        mvc.perform(put(base+"/rules/"+conditionId).principal(user).contentType("application/json")
                .content("{\"type\":\"CONDITION\",\"name\":\"功率数值超限\",\"severity\":\"IMPORTANT\",\"qualityLevel\":\"Q0\",\"enabled\":false,\"parameter\":1,\"revision\":1,\"recoveryCycles\":2,\"triggerMinutes\":1,\"recoveryMinutes\":0,\"conditionLogic\":\"ALL\",\"classification\":\"VALUE\",\"conditions\":[{\"pointId\":\"P1\",\"operator\":\"GT\",\"value\":10},{\"pointId\":\"P2\",\"operator\":\"EQ\",\"value\":1}]}")).andExpect(status().isOk());

        fixture.runtime.snapshot().activeVersions().get("v1").setEffectiveFrom(LocalDateTime.ofInstant(fixture.clock.instant(),ZoneId.of("Asia/Shanghai")));
        String offlineBody="""
                {"type":"OFFLINE","name":"两周期缺报","severity":"IMPORTANT","qualityLevel":"Q2","enabled":true,"parameter":2,"revision":0,
                 "recoveryCycles":2,"triggerMinutes":0,"recoveryMinutes":0,"conditionLogic":"ALL","classification":"OFFLINE","conditions":[]}
                """;
        JsonNode offlineRequest=mapper.readTree(offlineBody);
        JsonNode offlineCreated=mapper.readTree(mvc.perform(post(base+"/rules").principal(user).contentType("application/json").content(offlineBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String offlineId=offlineCreated.path("data").asText();
        replay.set("offlineRuleRequest",offlineRequest); replay.put("offlineRuleId",offlineId);
        fixture.clock.advance(10*60_000L); fixture.service.evaluate("A");
        replay.set("offlineTriggerSummary",apiData(get(base+"/summary")));
        replay.set("offlineTriggerEvents",apiData(get(base+"/events?type=DEVICE&alarmType=OFFLINE")));
        mockRawValue(1); fixture.service.evaluate("A"); fixture.service.evaluate("A");
        replay.set("offlineInterruptedRecoverySummary",apiData(get(base+"/summary")));
        replay.set("offlineInterruptedRecoveryEvents",apiData(get(base+"/events?type=DEVICE&alarmType=OFFLINE")));
        fixture.clock.advance(5*60_000L); mockRawValue(1); fixture.service.evaluate("A");
        replay.set("offlineRecoveredSummary",apiData(get(base+"/summary")));
        replay.set("offlineRecoveredEvents",apiData(get(base+"/events?history=true&type=DEVICE&alarmType=OFFLINE")));

        Path output=Path.of("target","alarm-acceptance-replay.json"); Files.createDirectories(output.getParent());
        Files.writeString(output,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(replay));
    }

    private void mockRawValue(double value) {
        long at=fixture.clock.millis();
        org.mockito.Mockito.when(fixture.raw.findLatestByEquipmentPoints(org.mockito.ArgumentMatchers.eq("A"),org.mockito.ArgumentMatchers.eq("M1"),org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(new LatestRawReading("P1",value,at,at,0),new LatestRawReading("P2",1,at,at,0)));
    }

    private JsonNode apiData(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        String body=mvc.perform(request.principal(user)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode envelope=new ObjectMapper().readTree(body);
        org.assertj.core.api.Assertions.assertThat(envelope.path("code").asInt()).isEqualTo(200);
        return envelope.path("data");
    }
}
