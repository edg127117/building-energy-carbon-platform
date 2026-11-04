package com.platform.carbon;

import com.fasterxml.jackson.databind.*;
import com.platform.energy.aggregation.EnergyAggregationCore;
import com.platform.energy.catalog.*;
import com.platform.energy.catalog.api.EnergyCatalogContracts.BindingVersionView;
import com.platform.energy.classification.*;
import com.platform.iot.identity.*;
import com.platform.system.mapper.*;
import com.platform.system.model.entity.SysUser;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.platform.carbon.DynamicElectricityContracts.*;

/** 隔离H2贯通实际周期求和、计量树、项目排除、动态算法和持久任务，外部身份/目录用测试替身。 */
class DynamicElectricityLiveServiceTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private DynamicElectricityLiveService live;
    private DynamicElectricityLiveRepository tasks;
    private DynamicElectricityRepository results;
    private DynamicElectricityService queries;
    private DynamicElectricityLiveInput input;
    private CarbonAuthorization auth;
    private DeviceIdentityProvider devices;
    private SysUserMapper users;
    private SysRoleMapper roles;
    private ClassificationService classifications;
    private EnergyCatalogService catalog;
    private EnergyCatalogLookup lookup;
    private Instant from=LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
    private final List<String> role=List.of("PLATFORM_ADMIN");
    private DataSourceTransactionManager manager;

    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:live"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false","sa","");
        jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        jdbc.execute("CREATE TABLE biz_telemetry_raw_intent(building_id VARCHAR(32),event_ms BIGINT)");
        try(var c=ds.getConnection()) {
            ScriptUtils.executeSqlScript(c,new FileSystemResource("src/env/init/V104__mysql_dynamic_electricity_carbon.sql"));
            ScriptUtils.executeSqlScript(c,new FileSystemResource("src/env/init/V105__mysql_dynamic_electricity_tasks.sql"));
        }
        jdbc.execute("CREATE TABLE biz_data_point(building_id VARCHAR(32),point_id VARCHAR(80),equip_id VARCHAR(32),del_flag INT,unit VARCHAR(32),status VARCHAR(16))");
        var mapper=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        tasks=spy(new DynamicElectricityLiveRepository(jdbc,mapper));
        results=new DynamicElectricityRepository(jdbc,mapper);
        doReturn("metadata-v1").when(tasks).metadataVersion(anyString(),any());
        auth=mock(CarbonAuthorization.class);devices=mock(DeviceIdentityProvider.class);users=mock(SysUserMapper.class);roles=mock(SysRoleMapper.class);
        var user=new SysUser();user.setStatus(1);user.setDelFlag(0);user.setActivationPending(0);
        when(users.selectSecurityState(1L)).thenReturn(user);when(roles.selectRoleKeysByUserId(1L)).thenReturn(role);
        when(devices.find(any())).thenAnswer(a->{DeviceIdentityKey key=a.getArgument(0);return Optional.of(new DeviceIdentityBinding("I",key,key.value(),key.value(),"B","METER",null,null,null,null));});
        classifications=mock(ClassificationService.class);
        var projects=mock(CarbonProjectService.class);
        var project=new CarbonProjectContracts.Configuration("C",List.of(new CarbonProjectContracts.Energy("ELEC","PURCHASED_ELECTRICITY","ENABLED")),List.of());
        when(projects.effective(eq("B"),any(),any())).thenReturn(new CarbonProjectContracts.Version("PR","B",1,1,"ACTIVE",from,null,1,2L,project));
        catalog=mock(EnergyCatalogService.class);lookup=mock(EnergyCatalogLookup.class);
        when(catalog.effectiveBindingForPeriod(anyLong(),anyCollection(),anyString(),anyString(),any(),any())).thenAnswer(a->new BindingVersionView("BD","BV",1,"B",a.getArgument(3),"POINT","kWh","ELEC","ITEM",local(from),null,"CONFIRMED","test",1,1L,null,2L,null));
        when(lookup.findApprovedItem(eq("ELEC"),any())).thenReturn(Optional.of(new EnergyCatalogLookup.ApprovedEnergyItem("I","ELEC","ITEM",1,"ELECTRICITY",List.of("PURCHASED_ELECTRICITY"),"TEST","fixture",local(from),null)));
        input=new DynamicElectricityLiveInput(projects,new CarbonProjectCore(),classifications,new ClassificationCore(),catalog,lookup,new EnergyAggregationCore());
        queries=new DynamicElectricityService(auth,results,mock(DynamicElectricityInputReader.class),manager,mock(com.platform.audit.AuditEvidenceWriter.class),new com.platform.audit.AuditGovernanceProperties(),tasks);
        live=new DynamicElectricityLiveService(auth,devices,tasks,results,input,queries,users,roles,manager);
        meters(2,false);
    }
    private static LocalDateTime local(Instant at){return LocalDateTime.ofInstant(at,ZoneId.of("Asia/Shanghai"));}
    private void meters(int count,boolean tree) {
        var meters=new ArrayList<ClassificationCore.Meter>();var rules=new ArrayList<ClassificationContracts.BoundaryRule>();
        for(int i=0;i<count;i++) {
            String id="P"+i,boundary=tree?"BOUNDARY":id;
            jdbc.update("INSERT INTO biz_data_point VALUES ('B',?,?,0,'kWh','ONLINE')",id,id);
            meters.add(new ClassificationCore.Meter(id,tree&&i>0?"P0":null,boundary,tree&&i>0?"SUB":"MAIN","INBOUND","CONFIRMED"));
            if(!tree||i==0)rules.add(new ClassificationContracts.BoundaryRule(boundary,tree?"MAIN_WITH_SUBMETER_BREAKDOWN":"MAIN_METER_TOTAL","CONFIRMED","fixture","ELEC","kWh",true));
        }
        var config=new ClassificationContracts.Configuration("R",List.of(new ClassificationContracts.Scheme("use","用途","USAGE","ADDITIVE")),List.of(),List.of(),rules);
        when(classifications.monitoringIntervalContext(anyLong(),anyCollection(),eq("B"),eq("C"),any(),any(),eq("ELEC")))
                .thenReturn(new ClassificationService.IntervalContext("C","R",1,config,meters,Map.of(),null));
    }
    private DeviceIntervalReceipt upload(String point,Instant at,long revision,String q,String f) {
        return tx.execute(s->live.ingest(1,role,"B",new DeviceIntervalRequest(new DeviceIdentityKey("SERIAL",point),"METER",point,at,at.plusSeconds(900),revision,
                q==null?null:new BigDecimal(q),f==null?null:new BigDecimal(f),"SIMULATED","test-source")));
    }
    private void drain() {jdbc.update("UPDATE biz_dynamic_electricity_task SET next_check=0");live.refresh();}
    @Test void pairsOutOfOrderAvoidsDuplicatesAndWaitsForBothRevisions() {
        upload("P0",from,1,"10",null);upload("P1",from,1,"20","0.8");drain();
        assertThat(tasks.current("B",from,from.plusSeconds(900),"metadata-v1")).isEmpty();
        upload("P0",from,1,null,"0.5");drain();
        var saved=tasks.current("B",from,from.plusSeconds(900),"metadata-v1").getFirst();
        assertThat(saved.values().quantity()).isEqualByComparingTo("30");assertThat(saved.values().emissionKgCo2()).isEqualByComparingTo("21");
        upload("P0",from,1,"10","0.5");drain();
        assertThat(tasks.current("B",from,from.plusSeconds(900),"metadata-v1").getFirst().id()).isEqualTo(saved.id());
        assertThatThrownBy(()->upload("P0",from,1,"11","0.5")).hasMessageContaining("不同值");
        upload("P0",from,2,"12",null);drain();
        assertThat(tasks.current("B",from,from.plusSeconds(900),"metadata-v1")).isEmpty();
        upload("P0",from,2,null,"0.5");drain();
        assertThat(tasks.current("B",from,from.plusSeconds(900),"metadata-v1").getFirst().values().emissionKgCo2()).isEqualByComparingTo("22");
        assertThat(results.byId("B",saved.id()).electricityEvidence().path("P0").path("quantity").asText()).isEqualTo("10");
    }
    @Test void staleLeaseCannotOverwriteNewInputsAndExpiredLeaseRecovers() {
        upload("P0",from,1,"10","0.5");upload("P1",from,1,"20","0.8");
        var old=tasks.locked("B",from,from.plusSeconds(900),1);String token=tasks.claim(old,System.currentTimeMillis());
        upload("P0",from,2,"12","0.5");live.process(old,token);
        assertThat(tasks.current("B",from,from.plusSeconds(900),"metadata-v1")).isEmpty();
        jdbc.update("UPDATE biz_dynamic_electricity_task SET state='RUNNING',lease_token='dead',lease_until=0,next_check=0");
        live.refresh();
        assertThat(tasks.current("B",from,from.plusSeconds(900),"metadata-v1").getFirst().values().emissionKgCo2()).isEqualByComparingTo("22");
        doReturn("metadata-v2").when(tasks).metadataVersion(anyString(),any());live.refreshMetadata();drain();
        assertThat(tasks.current("B",from,from.plusSeconds(900),"metadata-v2")).hasSize(1);
    }
    @Test void oneHundredMetersFullDayAggregateAndCorrectionStaysInItsInterval() {
        jdbc.update("DELETE FROM biz_data_point");meters(100,false);
        for(int slot=0;slot<96;slot++)for(int meter=0;meter<100;meter++)upload("P"+meter,from.plusSeconds(slot*900L),1,"1",meter%2==0?"0.4":"0.6");
        drain();
        var day=queries.query(1,role,"B","MONTH",YearMonth.from(local(from)).toString()).points().get(local(from).getDayOfMonth()-1);
        assertThat(day.status()).isEqualTo("COMPLETE");assertThat(day.electricityKwh()).isEqualByComparingTo("9600");
        assertThat(day.emissionTco2()).isEqualByComparingTo("4.8");assertThat(day.factorKgCo2PerKwh()).isEqualByComparingTo("0.5");
        verify(catalog,times(100)).effectiveBindingForPeriod(anyLong(),anyCollection(),anyString(),anyString(),any(),any());
        verify(lookup,times(1)).findApprovedItem(eq("ELEC"),any());
        var unchanged=tasks.current("B",from.plusSeconds(900),from.plusSeconds(1800),"metadata-v1").getFirst().id();
        upload("P0",from,2,"2","0.4");drain();
        assertThat(tasks.current("B",from.plusSeconds(900),from.plusSeconds(1800),"metadata-v1").getFirst().id()).isEqualTo(unchanged);
        assertThat(queries.query(1,role,"B","MONTH",YearMonth.from(local(from)).toString()).points().get(local(from).getDayOfMonth()-1).emissionTco2()).isEqualByComparingTo("4.8004");
    }
    @Test void openDayAndMonthReturnOnlyTheContinuousComputedPrefix() {
        from=LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        org.junit.jupiter.api.Assumptions.assumeTrue(Instant.now().isAfter(from.plusSeconds(1800)));
        upload("P0",from,1,"10","0.5");upload("P1",from,1,"20","0.8");drain();
        var daily=queries.query(1,role,"B","MONTH",YearMonth.from(local(from)).toString()).points().get(local(from).getDayOfMonth()-1);
        assertThat(daily.status()).isEqualTo("ACCUMULATING");assertThat(daily.computedThrough()).isEqualTo(from.plusSeconds(900));
        assertThat(daily.emissionTco2()).isEqualByComparingTo("0.021");
        upload("P0",from.plusSeconds(1800),1,"10","0.5");upload("P1",from.plusSeconds(1800),1,"20","0.8");drain();
        assertThat(queries.query(1,role,"B","MONTH",YearMonth.from(local(from)).toString()).points().get(local(from).getDayOfMonth()-1).computedThrough()).isEqualTo(from.plusSeconds(900));
    }

    @Test void intradayBindingChangesFallBackToTheExactQuarterWithoutReusingAnotherVersion() {
        when(catalog.effectiveBindingForPeriod(anyLong(),anyCollection(),anyString(),anyString(),any(),any())).thenAnswer(a->{
            LocalDateTime start=a.getArgument(4),end=a.getArgument(5);
            if(Duration.between(start,end).getSeconds()>900)throw new com.platform.framework.exception.BusinessException(409,"BINDING_GAP","日内换版");
            return new BindingVersionView("BD","BV-"+start.getMinute(),1,"B",a.getArgument(3),"POINT","kWh","ELEC","ITEM",start,end,"CONFIRMED","test",1,1L,null,2L,null);
        });
        for(int slot=0;slot<2;slot++){upload("P0",from.plusSeconds(slot*900L),1,"10","0.5");upload("P1",from.plusSeconds(slot*900L),1,"20","0.8");}
        drain();
        assertThat(tasks.current("B",from,from.plusSeconds(1800),"metadata-v1")).hasSize(2);
        verify(catalog,times(8)).effectiveBindingForPeriod(anyLong(),anyCollection(),anyString(),anyString(),any(),any());
    }

    @Test void partialDeviceReplacementCannotBeHiddenByAnOldCoarseResult() {
        var values=new DynamicElectricityCore.Applied(new BigDecimal("999"),new BigDecimal("499.5"),new BigDecimal("0.5"),List.of(),List.of("SIMULATED"),false);
        var old=new DynamicElectricityRepository.Result("coarse","B","DAY",from,from.plusSeconds(86400),"old","REVIEWED_INTERVAL",values,"source",CarbonEvidence.tree(Map.of()),"f",1);
        results.save(old);
        upload("P0",from,1,"10","0.5");upload("P1",from,1,"20","0.8");drain();
        var point=queries.query(1,role,"B","MONTH",YearMonth.from(local(from)).toString()).points().get(local(from).getDayOfMonth()-1);
        assertThat(point.emissionTco2()).isNull();assertThat(point.resultIds()).doesNotContain("coarse");
    }

    @Test void totalAndSubmetersUseDisjointContributionsAndBuildingScopeIsEnforced() {
        jdbc.update("DELETE FROM biz_data_point");meters(3,true);
        upload("P0",from,1,"100","0.5");upload("P1",from,1,"30","0.6");upload("P2",from,1,"20","0.7");drain();
        var value=tasks.current("B",from,from.plusSeconds(900),"metadata-v1").getFirst().values();
        assertThat(value.quantity()).isEqualByComparingTo("100");assertThat(value.emissionKgCo2()).isEqualByComparingTo("57");
        doReturn(Optional.of(new DeviceIdentityBinding("I",new DeviceIdentityKey("SERIAL","X"),"P0","P0","OTHER","METER",null,null,null,null))).when(devices).find(any());
        assertThatThrownBy(()->upload("P0",from,2,"100","0.5")).hasMessageContaining("不匹配");
    }
}
