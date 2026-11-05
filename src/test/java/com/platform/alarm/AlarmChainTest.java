package com.platform.alarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.collection.runtime.CollectionRuntimeStateService;
import com.platform.iot.collection.model.entity.*;
import com.platform.hvac.model.entity.BizPointAlias;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.LatestRawReading;
import com.platform.iot.temporal.model.HvacMinuteQueryRow;
import com.platform.system.mapper.SysMenuMapper;
import com.platform.system.model.entity.SysMenu;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static com.platform.alarm.AlarmModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** H2 与原始存储替身隔离验证，不连接 Broker、设备、MySQL 或 TDengine。 */
class AlarmChainTest {
    static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");
    JdbcTemplate jdbc;
    AlarmService service;
    MonthlyQuotaSource quota;
    HvacRawEventRepository raw;
    HvacMinuteRepository minutes;
    BuildingScopeService buildings;
    MutableClock clock;
    CollectionRuntimeStateService runtime;

    @BeforeEach
    void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:alarm-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE biz_building(building_id VARCHAR(32),building_name VARCHAR(100),del_flag INT)");
        jdbc.update("INSERT INTO biz_building VALUES ('A','建筑A',0),('B','建筑B',0)");
        String migration = Files.readString(Path.of("src/env/init/V110__mysql_alarm_information.sql"));
        migration = migration.substring(0, migration.indexOf("-- 只补齐"));
        for (String sql : migration.split(";")) if (!sql.isBlank()) jdbc.execute(sql);
        jdbc.execute("CREATE TABLE biz_equipment_type(type_code VARCHAR(32),type_name VARCHAR(100),equip_category VARCHAR(64))");
        jdbc.update("INSERT INTO biz_equipment_type VALUES ('METER','电表','ELECTRIC_METER')");
        jdbc.execute("CREATE TABLE biz_equipment(equip_id VARCHAR(32),equip_name VARCHAR(100),building_id VARCHAR(32),space_id VARCHAR(32),type_code VARCHAR(32),equip_category VARCHAR(64),del_flag INT)");
        jdbc.execute("CREATE TABLE biz_data_point(point_id VARCHAR(32),equip_id VARCHAR(32),building_id VARCHAR(32),status VARCHAR(16),point_name VARCHAR(100),unit VARCHAR(20),data_type VARCHAR(20),del_flag INT)");
        jdbc.execute("CREATE TABLE biz_space(space_id VARCHAR(32),building_id VARCHAR(32),space_name VARCHAR(100),del_flag INT)");
        jdbc.execute("CREATE TABLE biz_daikin_monitoring_target(identity_id VARCHAR(32),equipment_id VARCHAR(32),building_id VARCHAR(32),mapping_version INT,active INT)");
        jdbc.execute("CREATE TABLE biz_device_identity(identity_id VARCHAR(32),equip_id VARCHAR(32),building_id VARCHAR(32),status INT)");
        jdbc.execute("CREATE TABLE biz_daikin_exception_instance(exception_id BIGINT,identity_id VARCHAR(32),equipment_id VARCHAR(32),building_id VARCHAR(32),mapping_version INT,exception_type VARCHAR(40),scope_type VARCHAR(16),active_key VARCHAR(100),field_name VARCHAR(100),last_detected_at_ms BIGINT)");
        jdbc.update("INSERT INTO biz_equipment VALUES ('M1','三层电表','A','S1','METER','ELECTRIC_METER',0),('M2','其他建筑设备','B','S2','METER','ELECTRIC_METER',0)");
        jdbc.update("INSERT INTO biz_data_point VALUES ('P1','M1','A','ONLINE','功率','kW','ANALOG',0),('P2','M1','A','ONLINE','运行状态','编码','DIGITAL',0)");
        jdbc.update("INSERT INTO biz_space VALUES ('S1','A','三楼配电间',0),('S3','A','屋顶机房',0)");
        clock = new MutableClock(Instant.parse("2026-11-04T08:00:00Z"));
        raw = mock(HvacRawEventRepository.class);
        when(raw.findLatestByEquipmentPoints(anyString(), anyString(), anyCollection())).thenReturn(List.of());
        minutes=mock(HvacMinuteRepository.class);
        when(minutes.findLatestByPointIds(anyList())).thenReturn(List.of());
        runtime = mock(CollectionRuntimeStateService.class);
        configureSource();
        buildings = mock(BuildingScopeService.class);
        var menus = mock(SysMenuMapper.class);
        quota = mock(MonthlyQuotaSource.class);
        when(quota.load(anyString(), any())).thenReturn(new Quota("A", YearMonth.of(2026,10), BigDecimal.ZERO, new BigDecimal("100"), "plan1", "batch1", "hash", null));
        var target = new AlarmService(jdbc, new AlarmAccess(buildings, menus),
                new CollectedAlarmDeviceSource(jdbc, runtime, raw, minutes, new ObjectMapper()), List.of(quota), new ObjectMapper().findAndRegisterModules(), clock);
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds), new AnnotationTransactionAttributeSource()));
        service = (AlarmService) factory.getProxy();
    }

    void configureSource() {
        var source = new BizDataSource(); source.setSourceId("source"); source.setBuildingId("A"); source.setStatus("ENABLED"); source.setRuntimeRevision(1L);
        var alias = new BizPointAlias(); alias.setAliasId("alias"); alias.setPointId("P1");
        var policy = new BizCollectionPolicy(); policy.setPolicyId("policy"); policy.setBuildingId("A"); policy.setAliasId("alias"); policy.setSourceId("source"); policy.setActiveVersionId("v1");
        var version = new BizCollectionPolicyVersion(); version.setEnabledFlag(1); version.setExpectedIntervalSeconds(300); version.setAllowedDelaySeconds(0);
        version.setEffectiveFrom(LocalDateTime.ofInstant(clock.instant().minusSeconds(1000), ZoneId.of("Asia/Shanghai")));
        when(runtime.snapshot()).thenReturn(new CollectionRuntimeStateService.RuntimeSnapshot(Map.of("source",source), Map.of("alias",alias), Map.of("policy",policy), Map.of("v1",version), Map.of("source",1L)));
        when(runtime.state(any(), any(), anyLong())).thenReturn(new CollectionRuntimeStateService.ApplyState(1L,
                com.platform.iot.collection.model.CollectionModels.RuntimeApplyStatus.APPLIED, null, null, null));
    }

    @Test
    void missingReportsTriggerThenFreshInputRecoversThroughRealSourceAndSummary() {
        service.evaluate("A");
        var first = service.events(1L, ADMIN, "A", false, "DEVICE", 1, 20).items().getFirst();
        assertThat(first.type()).isEqualTo("OFFLINE");
        assertThat(first.evidence()).contains("\"lastCommunicationAt\":null", "\"monitoringSince\":");
        assertThat(first.spaceName()).isEqualTo("三楼配电间");
        assertThat(service.summary(1L, ADMIN, "A").deviceAnomalies()).isEqualTo(1);
        service.evaluate("A");
        assertThat(service.events(1L, ADMIN, "A", false, "DEVICE", 1, 20).total()).isEqualTo(1);
        when(raw.findLatestByEquipmentPoints(eq("A"), eq("M1"), anyCollection()))
                .thenReturn(List.of(new LatestRawReading("P1", 10, clock.millis()-1000, clock.millis(), 0)));
        service.evaluate("A");
        assertThat(service.summary(1L, ADMIN, "A").deviceAnomalies()).isZero();
        assertThat(service.events(1L, ADMIN, "A", true, "DEVICE", 1, 20).total()).isEqualTo(1);
        assertThat(service.detail(1L, ADMIN, "A", first.id()).logs()).extracting(Log::action).containsExactly("TRIGGERED","RECOVERED");
    }

    @Test
    void lateBackfillAndSyntheticAndFutureInputDoNotPretendDeviceRecovered() {
        for (LatestRawReading reading : List.of(
                new LatestRawReading("P1",10,clock.millis()-3_600_000,clock.millis(),0),
                new LatestRawReading("P1",10,clock.millis()-1,clock.millis(),1),
                new LatestRawReading("P1",10,clock.millis()+1,clock.millis()+1,0))) {
            when(raw.findLatestByEquipmentPoints(anyString(), anyString(), anyCollection())).thenReturn(List.of(reading));
            service.evaluate("A");
            assertThat(service.summary(1L, ADMIN,"A").deviceAnomalies()).isEqualTo(1);
        }
    }

    @Test
    void primaryGroupingDeduplicatesAndMaintenancePreservesAcknowledgement() {
        jdbc.update("INSERT INTO biz_daikin_monitoring_target VALUES ('I1','M1','A',1,1)");
        jdbc.update("INSERT INTO biz_device_identity VALUES ('I1','M1','A',1)");
        jdbc.update("INSERT INTO biz_daikin_exception_instance VALUES (1,'I1','M1','A',1,'VENDOR_EQUIPMENT','DEVICE','fault1','inEquipmentError',0)");
        service.evaluate("A");
        assertThat(service.events(1L, ADMIN,"A",false,"DEVICE",1,20).total()).isEqualTo(2);
        var summary = service.summary(1L,ADMIN,"A");
        assertThat(summary.deviceAnomalies()).isEqualTo(1);
        assertThat(summary.deviceGroups()).containsExactly(new Group("OFFLINE",1),new Group("QUALITY",0),new Group("VALUE",0),new Group("FAULT",0));
        var event = service.events(1L,ADMIN,"A",false,"DEVICE",1,20).items().getFirst();
        service.action(1L,ADMIN,"A",event.id(),"ACK",null,0);
        service.action(1L,ADMIN,"A",event.id(),"ACK",null,0);
        service.action(1L,ADMIN,"A",event.id(),"SILENCE","检修",1);
        assertThat(service.summary(1L,ADMIN,"A").deviceAnomalies()).isZero();
        clock.advance(61_000); service.evaluate("A");
        assertThat(service.summary(1L,ADMIN,"A").deviceAnomalies()).isEqualTo(1);
        assertThat(service.detail(1L,ADMIN,"A",event.id()).ackBy()).isEqualTo(1);
        assertThat(service.detail(1L,ADMIN,"A",event.id()).logs().stream().filter(log -> log.action().equals("ACK"))).hasSize(1);
    }

    @Test
    void quotaUpgradesWithoutDuplicateAndKeepsOriginalEvidenceThenRecovers() {
        setActual("90"); service.evaluate("A");
        var first = service.events(1L,ADMIN,"A",false,"EMISSION",1,20).items().getFirst();
        assertThat(first.severity()).isEqualTo("WARNING");
        setActual("105"); service.evaluate("A");
        var upgraded = service.events(1L,ADMIN,"A",false,"EMISSION",1,20).items().getFirst();
        assertThat(upgraded.id()).isEqualTo(first.id());
        assertThat(upgraded.severity()).isEqualTo("IMPORTANT");
        assertThat(upgraded.evidence()).contains("\"ratioPercent\":\"105.00\"", "\"actual\":\"105\"", "\"quota\":\"100\"");
        assertThat(upgraded.triggerEvidence()).isEqualTo(first.evidence());
        assertThat(service.summary(1L,ADMIN,"A").emissionWarnings()).isEqualTo(1);
        setActual("80"); service.evaluate("A");
        assertThat(service.summary(1L,ADMIN,"A").emissionWarnings()).isZero();
    }

    @Test
    void outageDoesNotRecoverAndMissingOrWrongBuildingQuotaIsNotZero() {
        setActual("90"); service.evaluate("A");
        when(raw.findLatestByEquipmentPoints(anyString(), anyString(), anyCollection())).thenThrow(new IllegalStateException());
        when(quota.load(anyString(), any())).thenReturn(null);
        service.evaluate("A");
        assertThat(service.summary(1L,ADMIN,"A").deviceAnomalies()).isNull();
        assertThat(service.summary(1L,ADMIN,"A").emissionWarnings()).isNull();
        assertThat(service.events(1L,ADMIN,"A",true,"EMISSION",1,20).total()).isZero();
        when(quota.load(anyString(), any())).thenReturn(new Quota("B",YearMonth.of(2026,10),BigDecimal.ZERO,BigDecimal.TEN,"plan","batch","hash",null));
        service.evaluate("A");
        assertThat(service.summary(1L,ADMIN,"A").emissionWarnings()).isNull();
        clock.advance(121_000);
        assertThat(service.summary(1L,ADMIN,"A").deviceReason()).contains("尚未更新");
    }

    @Test
    void ruleCasAndDisableAndRecoveredReadOnlyAndBuildingScopeAreEnforced() {
        service.evaluate("A");
        var first = service.events(1L,ADMIN,"A",false,"DEVICE",1,20).items().getFirst();
        service.saveRule(1L,ADMIN,"A","OFFLINE",new SaveRule(false,3,1));
        assertThatThrownBy(() -> service.saveRule(1L,ADMIN,"A","OFFLINE",new SaveRule(true,3,1)))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(service.rules(1L,ADMIN,"A").stream().filter(r->r.type().equals("OFFLINE")).findFirst().orElseThrow().revision()).isEqualTo(2);
        assertThatThrownBy(() -> service.action(1L,ADMIN,"A",first.id(),"ACK",null,0)).isInstanceOf(BusinessException.class);
        doThrow(new BusinessException(403,"越权")).when(buildings).checkAccess(eq(2L),any(),eq("A"));
        assertThatThrownBy(() -> service.summary(2L,ADMIN,"A")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.detail(1L,ADMIN,"B",first.id())).isInstanceOf(BusinessException.class);
        assertThat(service.events(1L,ADMIN,"B",false,"DEVICE",1,20).total()).isZero();
    }

    @Test
    void multipleScopedRulesHaveIndependentIdentityVersionsSeverityAndDisableRecovery() {
        service.evaluate("A");
        RuleRequest request=new RuleRequest("OFFLINE","三楼电表缺报","URGENT","Q2","S1","ELECTRIC_METER",true,3,0L);
        String customId=service.createRule(1L,ADMIN,"A",request);
        service.evaluate("A");
        var active=service.events(1L,ADMIN,"A",false,"DEVICE",1,20).items();
        assertThat(active).hasSize(2);
        assertThat(active).anySatisfy(event->{assertThat(event.ruleId()).isEqualTo(customId);assertThat(event.severity()).isEqualTo("URGENT");});
        assertThat(service.rules(1L,ADMIN,"A")).anySatisfy(rule->{assertThat(rule.id()).isEqualTo(customId);assertThat(rule.qualityLevel()).isEqualTo("Q2");});
        assertThat(service.ruleHistory(1L,ADMIN,"A",customId)).singleElement().satisfies(version->{assertThat(version.get("parameter_value")).isEqualTo(3);});

        service.updateRule(1L,ADMIN,"A",customId,new RuleRequest("OFFLINE","三楼电表缺报","GENERAL","Q1","S1","ELECTRIC_METER",false,4,1L));
        assertThat(service.events(1L,ADMIN,"A",false,"DEVICE",1,20).items()).singleElement().satisfies(event->{assertThat(event.ruleId()).isNotEqualTo(customId);});
        assertThat(service.events(1L,ADMIN,"A",true,"DEVICE",1,20).items()).anySatisfy(event->{assertThat(event.ruleId()).isEqualTo(customId);});
        assertThat(service.ruleHistory(1L,ADMIN,"A",customId)).hasSize(2);
        assertThatThrownBy(()->service.updateRule(1L,ADMIN,"A",customId,
                new RuleRequest("OFFLINE","三楼电表缺报","GENERAL","Q1","S1","ELECTRIC_METER",true,4,1L)))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(service.ruleOptions(1L,ADMIN,"A").spaces()).contains(new Option("S1","三楼配电间"));
        assertThat(service.ruleOptions(1L,ADMIN,"A").deviceCategories()).contains(new Option("ELECTRIC_METER","电表"));
    }

    @Test
    void scopedRuleRejectsForeignSpaceAndTypeChanges() {
        assertThatThrownBy(()->service.createRule(1L,ADMIN,"A",new RuleRequest("OFFLINE","越界","IMPORTANT","Q2","S2",null,true,3,0L)))
                .isInstanceOf(BusinessException.class);
        String id=service.createRule(1L,ADMIN,"A",new RuleRequest("OFFLINE","本楼","IMPORTANT","Q2",null,null,true,3,0L));
        assertThatThrownBy(()->service.updateRule(1L,ADMIN,"A",id,new RuleRequest("FAULT","本楼","IMPORTANT","Q1",null,null,true,1,1L)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void qualityRuleUsesFormalMinuteQualityAndRecoversOnlyWhenQualityIsBetterThanThreshold() {
        service.saveRule(1L,ADMIN,"A","OFFLINE",new SaveRule(false,3,1));
        String id=service.createRule(1L,ADMIN,"A",new RuleRequest("QUALITY","测点质量","IMPORTANT","Q1",null,null,true,1,0L,
                1,0,0,"ALL","QUALITY",List.of(new Condition("P1","EQ",BigDecimal.ZERO,null))));
        long qualityAt=clock.millis();
        when(minutes.findLatestByPointIds(anyList())).thenReturn(List.of(new HvacMinuteQueryRow("P1",qualityAt,12,11,13,2,2)));
        service.evaluate("A");
        assertThat(service.events(1L,ADMIN,"A",false,"DEVICE",1,20,"QUALITY",null,null,null,null).items()).singleElement()
                .satisfies(event->{assertThat(event.ruleId()).isEqualTo(id);assertThat(event.type()).isEqualTo("QUALITY");});
        assertThat(service.summary(1L,ADMIN,"A").deviceGroups()).contains(new Group("QUALITY",1));
        when(minutes.findLatestByPointIds(anyList())).thenReturn(List.of(new HvacMinuteQueryRow("P1",qualityAt,12,11,13,2,0)));
        service.evaluate("A");
        assertThat(service.events(1L,ADMIN,"A",false,"DEVICE",1,20,"QUALITY",null,null,null,null).total()).isEqualTo(1);
        clock.advance(60_000);
        when(minutes.findLatestByPointIds(anyList())).thenReturn(List.of(new HvacMinuteQueryRow("P1",clock.millis(),12,11,13,2,0)));
        service.evaluate("A");
        assertThat(service.events(1L,ADMIN,"A",true,"DEVICE",1,20,"QUALITY",null,null,null,null).total()).isEqualTo(1);
        assertThat(service.summary(1L,ADMIN,"A").deviceGroups()).contains(new Group("QUALITY",0));
    }

    @Test
    void conditionUsesFreshRawStateAndRequiresDistinctContinuousMinutesForTriggerAndRecovery() {
        service.saveRule(1L,ADMIN,"A","OFFLINE",new SaveRule(false,3,1));
        String id=service.createRule(1L,ADMIN,"A",new RuleRequest("CONDITION","功率超限","IMPORTANT","Q0",null,null,true,1,0L,
                2,2,0,"ALL","VALUE",List.of(new Condition("P1","GT",BigDecimal.TEN,null))));
        when(raw.findLatestByEquipmentPoints(eq("A"),eq("M1"),anyCollection()))
                .thenReturn(List.of(new LatestRawReading("P1",12,clock.millis(),clock.millis(),0)));
        service.evaluate("A");
        assertThat(service.events(1L,ADMIN,"A",false,"DEVICE",1,20,"VALUE",null,null,null,null).total()).isZero();
        clock.advance(60_000);
        when(raw.findLatestByEquipmentPoints(eq("A"),eq("M1"),anyCollection()))
                .thenReturn(List.of(new LatestRawReading("P1",12,clock.millis(),clock.millis(),0)));
        service.evaluate("A");
        clock.advance(60_000);
        when(raw.findLatestByEquipmentPoints(eq("A"),eq("M1"),anyCollection()))
                .thenReturn(List.of(new LatestRawReading("P1",12,clock.millis(),clock.millis(),0)));
        service.evaluate("A");
        var event=service.events(1L,ADMIN,"A",false,"DEVICE",1,20,"VALUE",null,null,null,null).items().getFirst();
        assertThat(event.ruleId()).isEqualTo(id);
        assertThat(event.evidence()).contains("\"observations\"","\"unit\":\"kW\"");

        clock.advance(60_000);
        when(raw.findLatestByEquipmentPoints(eq("A"),eq("M1"),anyCollection()))
                .thenReturn(List.of(new LatestRawReading("P1",0,clock.millis(),clock.millis(),0)));
        service.evaluate("A");
        service.evaluate("A"); // 同一原始快照不能被调度重复计为第二个恢复周期。
        assertThat(service.events(1L,ADMIN,"A",false,"DEVICE",1,20,"VALUE",null,null,null,null).total()).isEqualTo(1);
        clock.advance(60_000);
        when(raw.findLatestByEquipmentPoints(eq("A"),eq("M1"),anyCollection()))
                .thenReturn(List.of(new LatestRawReading("P1",0,clock.millis(),clock.millis(),0)));
        service.evaluate("A");
        assertThat(service.events(1L,ADMIN,"A",true,"DEVICE",1,20,"VALUE",null,null,null,null).total()).isEqualTo(1);
    }

    @Test
    void digitalStateLabelsPersistAsMetadataWhileNumericValueStillTriggersCondition() {
        service.saveRule(1L,ADMIN,"A","OFFLINE",new SaveRule(false,3,1));
        String id=service.createRule(1L,ADMIN,"A",new RuleRequest("CONDITION","状态等于停止","IMPORTANT","Q0",null,null,true,1,0L,
                1,0,0,"ALL","VALUE",List.of(new Condition("P2","EQ",new BigDecimal("2.0"),null,List.of(
                        new StateOption(BigDecimal.ONE,"运行"),new StateOption(new BigDecimal("2.00"),"停止"))))));
        when(raw.findLatestByEquipmentPoints(eq("A"),eq("M1"),anyCollection()))
                .thenReturn(List.of(new LatestRawReading("P2",2,clock.millis(),clock.millis(),0)));
        service.evaluate("A");
        var event=service.events(1L,ADMIN,"A",false,"DEVICE",1,20,"VALUE",null,null,null,null).items().getFirst();
        assertThat(event.ruleId()).isEqualTo(id);
        assertThat(event.evidence()).contains("\"value\":2").doesNotContain("\"states\"");
        var saved=service.rules(1L,ADMIN,"A").stream().filter(rule->rule.id().equals(id)).findFirst().orElseThrow();
        assertThat(saved.conditions().getFirst().states()).extracting(StateOption::label).containsExactly("运行","停止");
    }

    @Test
    void conditionTruthFlipWithinOneMinuteResetsTriggerAndUnalignedFactsStayUnknown() {
        service.saveRule(1L,ADMIN,"A","OFFLINE",new SaveRule(false,3,1));
        service.createRule(1L,ADMIN,"A",new RuleRequest("CONDITION","连续超限","IMPORTANT","Q0",null,null,true,1,0L,
                1,2,0,"ALL","VALUE",List.of(new Condition("P1","GT",BigDecimal.TEN,null))));
        mockRaw(12);
        service.evaluate("A");
        clock.advance(60_000); mockRaw(12); service.evaluate("A");
        clock.advance(10_000); mockRaw(0); service.evaluate("A");
        clock.advance(10_000); mockRaw(12); service.evaluate("A");
        clock.advance(60_000); mockRaw(12); service.evaluate("A");
        assertThat(service.events(1L,ADMIN,"A",false,"DEVICE",1,20,"VALUE",null,null,null,null).total()).isZero();
        clock.advance(60_000); mockRaw(12); service.evaluate("A");
        assertThat(service.events(1L,ADMIN,"A",false,"DEVICE",1,20,"VALUE",null,null,null,null).total()).isEqualTo(1);

        long now=clock.millis();
        when(raw.findLatestByEquipmentPoints(eq("A"),eq("M1"),anyCollection()))
                .thenReturn(List.of(new LatestRawReading("P1",0,now-130_000,now-10_000,0)));
        service.evaluate("A");
        assertThat(service.summary(1L,ADMIN,"A").deviceAnomalies()).isNull();
        assertThat(service.events(1L,ADMIN,"A",false,"DEVICE",1,20,"VALUE",null,null,null,null).total()).isEqualTo(1);
    }

    private void mockRaw(double value) {
        long at=clock.millis();
        when(raw.findLatestByEquipmentPoints(eq("A"),eq("M1"),anyCollection()))
                .thenReturn(List.of(new LatestRawReading("P1",value,at,at,0)));
    }

    @Test
    void offlineRulesUseIndependentMissingThresholdsAndRecoverByNewReportingCyclesBeforeRefiring() {
        runtime.snapshot().activeVersions().get("v1").setEffectiveFrom(LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai")));
        String twoCycle=service.createRule(1L,ADMIN,"A",new RuleRequest("OFFLINE","两周期缺报","IMPORTANT","Q2",null,null,true,2,0L,
                2,0,0,"ALL","OFFLINE",List.of()));
        when(raw.findLatestByEquipmentPoints(eq("A"),eq("M1"),anyCollection())).thenReturn(List.of());
        clock.advance(10*60_000L); service.evaluate("A");
        var first=service.events(1L,ADMIN,"A",false,"DEVICE",1,20,"OFFLINE",null,null,null,null).items();
        assertThat(first).singleElement().satisfies(event->assertThat(event.ruleId()).isEqualTo(twoCycle));
        clock.advance(5*60_000L); service.evaluate("A");
        assertThat(service.events(1L,ADMIN,"A",false,"DEVICE",1,20,"OFFLINE",null,null,null,null).total()).isEqualTo(2);

        long reportAt=clock.millis();
        when(raw.findLatestByEquipmentPoints(eq("A"),eq("M1"),anyCollection()))
                .thenReturn(List.of(new LatestRawReading("P1",1,reportAt,reportAt,0)));
        service.evaluate("A"); service.evaluate("A");
        assertThat(service.events(1L,ADMIN,"A",false,"DEVICE",1,20,"OFFLINE",null,null,null,null).total()).isEqualTo(1);
        clock.advance(5*60_000L);
        reportAt=clock.millis();
        when(raw.findLatestByEquipmentPoints(eq("A"),eq("M1"),anyCollection()))
                .thenReturn(List.of(new LatestRawReading("P1",1,reportAt,reportAt,0)));
        service.evaluate("A");
        assertThat(service.events(1L,ADMIN,"A",false,"DEVICE",1,20,"OFFLINE",null,null,null,null).total()).isZero();
        assertThat(service.events(1L,ADMIN,"A",true,"DEVICE",1,20,"OFFLINE",null,null,null,null).total()).isEqualTo(2);

        clock.advance(10*60_000L); service.evaluate("A");
        assertThat(service.events(1L,ADMIN,"A",false,"DEVICE",1,20,"OFFLINE",null,null,null,null).total()).isEqualTo(1);
        assertThat(service.events(1L,ADMIN,"A",true,"DEVICE",1,20,"OFFLINE",null,null,null,null).total()).isEqualTo(2);
    }

    @Test
    void scopedRuleWithoutMatchingMonitoredAssetsReportsNoCoverageInsteadOfZeroAlarms() {
        service.saveRule(1L,ADMIN,"A","OFFLINE",new SaveRule(false,3,1));
        service.createRule(1L,ADMIN,"A",new RuleRequest("OFFLINE","屋顶专用规则","IMPORTANT","Q2","S3","ELECTRIC_METER",true,3,0L));
        service.evaluate("A");
        assertThat(service.summary(1L,ADMIN,"A").deviceAnomalies()).isNull();
        assertThat(service.summary(1L,ADMIN,"A").deviceReason()).contains("暂无适用");
    }

    @Test
    void monthRolloverArchivesPreviousEvenWhenNewMonthMissing() {
        setActual("90"); service.evaluate("A");
        clock.advance(Duration.between(clock.instant(),Instant.parse("2026-12-01T00:00:00Z")).toMillis());
        when(quota.load(anyString(),any())).thenReturn(null);
        service.evaluate("A");
        assertThat(service.events(1L,ADMIN,"A",false,"EMISSION",1,20).total()).isZero();
        assertThat(service.events(1L,ADMIN,"A",true,"EMISSION",1,20).total()).isEqualTo(1);
        assertThat(service.summary(1L,ADMIN,"A").period()).isEqualTo("2026-11");
    }

    @Test
    void logFailureRollsBackStateAndNormalReadsDoNotCallExternalResources() {
        service.evaluate("A");
        var event = service.events(1L,ADMIN,"A",false,"DEVICE",1,20).items().getFirst();
        jdbc.execute("ALTER TABLE biz_alarm_event_log ADD CONSTRAINT reject_ack CHECK(action <> 'ACK')");
        assertThatThrownBy(() -> service.action(1L,ADMIN,"A",event.id(),"ACK",null,0)).isInstanceOf(RuntimeException.class);
        assertThat(service.detail(1L,ADMIN,"A",event.id()).ackAt()).isNull();
        clearInvocations(raw,quota);
        service.summary(1L,ADMIN,"A"); service.events(1L,ADMIN,"A",false,"DEVICE",1,20);
        verifyNoInteractions(raw,quota);
    }

    @Test
    void simultaneousScansCannotCreateDuplicateActiveEvents() throws Exception {
        service.evaluate("A");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks = List.of(pool.submit(() -> service.evaluate("A")), pool.submit(() -> service.evaluate("A")));
            for (var task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(service.events(1L,ADMIN,"A",false,"DEVICE",1,20).total()).isEqualTo(1);
        String id = service.events(1L,ADMIN,"A",false,"DEVICE",1,20).items().getFirst().id();
        assertThat(service.detail(1L,ADMIN,"A",id).logs()).extracting(Log::action).containsExactly("TRIGGERED");
    }

    @Test
    void leafMenusDoNotGrantOtherAlarmPagesOrRuleWrites() {
        var menus = mock(SysMenuMapper.class);
        var leaf = new SysMenu(); leaf.setMenuType("C"); leaf.setPath("/operations/alarms/liveAlarms");
        when(menus.selectVisibleMenusByUserId(7L)).thenReturn(List.of(leaf));
        var guard = new AlarmAccess(buildings,menus);
        var reader = Set.of("BUILDING_OWNER");
        guard.require(7L,reader,"A","/operations/alarms/liveAlarms",false);
        assertThatThrownBy(() -> guard.require(7L,reader,"A","/operations/alarms/historyAlarms",false)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> guard.require(7L,reader,"A","/configuration/rules/alarmRules",true)).isInstanceOf(BusinessException.class);
        verify(buildings).checkAccess(7L,reader,"A");
    }

    @Test
    void filtersBeforePaginationAndTreatsSearchWildcardsLiterally() {
        service.evaluate("A");
        assertThat(service.events(1L,ADMIN,"A",false,"DEVICE",1,1,"OFFLINE","UNACK","三层",null,null).total()).isEqualTo(1);
        assertThat(service.events(1L,ADMIN,"A",false,"DEVICE",1,1,null,null,"%",null,null).total()).isZero();
        assertThat(service.events(1L,ADMIN,"A",false,"DEVICE",1,1,null,null,null,clock.millis()+1,null).total()).isZero();
        String id=service.events(1L,ADMIN,"A",false,"DEVICE",1,20).items().getFirst().id();
        service.action(1L,ADMIN,"A",id,"ACK",null,0);
        assertThat(service.events(1L,ADMIN,"A",false,"DEVICE",1,1,null,"UNACK",null,null,null).total()).isZero();
        assertThat(service.events(1L,ADMIN,"A",false,"DEVICE",1,1,null,"ACK",null,null,null).total()).isEqualTo(1);
        assertThatThrownBy(() -> service.rules(1L,ADMIN,"UNKNOWN")).isInstanceOf(BusinessException.class);
    }

    @Test
    void schedulerContinuesToOtherBuildingsAfterSingleBuildingFailure() {
        var alarms = mock(AlarmService.class);
        doThrow(new IllegalStateException("failed")).when(alarms).evaluate("A");
        var scheduler = new AlarmScheduler(jdbc, alarms, 30000);
        assertThat(scheduler.isRunning()).isFalse();
        scheduler.scan();
        verify(alarms).evaluate("A");
        verify(alarms).evaluate("B");
    }

    void setActual(String actual) {
        when(quota.load(eq("A"),any())).thenReturn(new Quota("A",YearMonth.of(2026,10),new BigDecimal(actual),new BigDecimal("100"),"plan1","batch1","hash",null));
    }
    static class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant=instant; }
        void advance(long millis) { instant=instant.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant,zone); }
        @Override public Instant instant() { return instant; }
    }
}
