package com.platform.iot.reliability;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.*;
import com.platform.config.*;
import com.platform.energy.aggregation.*;
import com.platform.iot.identity.*;
import com.platform.iot.ingest.*;
import com.platform.iot.ingest.standard.*;
import com.platform.iot.ingest.v2.*;
import com.platform.iot.quality.TelemetryQualityValidator;
import com.platform.iot.reliability.mapper.*;
import com.platform.iot.temporal.impl.TdengineHvacRawEventRepository;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import com.platform.system.service.BuildingScopeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 由本机一次性容器脚本调用；真实 MySQL/TDengine，独立 JVM 和强制退出，不属于普通测试。 */
public class D01AcceptanceProcess {
    static String env(String key){return Objects.requireNonNull(System.getenv(key),key);}
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static long time(){return Long.parseLong(env("D01_EVENT_TIME"));}
    static RawTelemetryEvent event(String point,double value){return new RawTelemetryEvent(point,"TEMP","MQTT_STANDARD_V1","MAC:A:TEMP","DEV","B1",null,"E_"+point,"DEV","TEMP",null,"TEMP",value,time(),time()+1000,0,0,false);}
    public static void main(String[] args)throws Exception {
        check("true".equals(env("D01_PROBE_ISOLATED")),"Isolated acknowledgement missing");
        check(env("D01_DATABASE").matches("d01_[a-f0-9]+"),"Unexpected database");
        check(env("D01_MYSQL_URL").startsWith("jdbc:mysql://127.0.0.1:"),"Nonlocal MySQL refused");
        check(env("D01_TD_URL").startsWith("jdbc:TAOS-RS://127.0.0.1:"),"Nonlocal TDengine refused");
        String mode=args[0],stage=args.length>4?args[4]:"none",database=env("D01_DATABASE");
        var ds=new DriverManagerDataSource(env("D01_MYSQL_URL"),env("D01_MYSQL_USER"),env("D01_MYSQL_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);var mapper=new ObjectMapper().findAndRegisterModules();var codec=new RawTelemetryCodec(mapper);
        var tdSource=new DriverManagerDataSource(env("D01_TD_URL"),"root",env("D01_TD_PASSWORD"));
        var td=new JdbcTemplate(tdSource){@Override public void execute(String sql){super.execute(sql);if(sql.startsWith("INSERT INTO")&&stage.equals("raw"))Runtime.getRuntime().halt(32);}};
        var properties=new TdengineProperties();properties.setDatabase(database);properties.setStRawEvent("st_raw_event");
        if(mode.equals("init")) {
            jdbc.execute("CREATE TABLE sys_menu(id BIGINT AUTO_INCREMENT PRIMARY KEY,parent_id BIGINT,menu_name VARCHAR(50),menu_type CHAR(1),path VARCHAR(200),icon VARCHAR(100),visible INT,status INT,sort_order INT)");
            jdbc.execute("CREATE TABLE sys_role(id BIGINT PRIMARY KEY,role_key VARCHAR(50),status INT)");
            jdbc.execute("CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT,UNIQUE(role_id,menu_id))");
            jdbc.update("INSERT INTO sys_menu(parent_id,menu_name,menu_type,path) VALUES(0,'运维','M','/operations')");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/mysql/V84__mysql_telemetry_immutable_conflicts.sql")).execute(ds);
            jdbc.execute("CREATE TABLE biz_device_identity(identity_id VARCHAR(32) PRIMARY KEY)");jdbc.update("INSERT INTO biz_device_identity VALUES('I1')");
            String v22=new ClassPathResource("db/migration/mysql/V22__mysql_telemetry_reliability_v2.sql").getContentAsString(StandardCharsets.UTF_8);
            v22=v22.substring(v22.indexOf("CREATE TABLE IF NOT EXISTS `biz_telemetry_receipt`"),v22.indexOf("CREATE TABLE IF NOT EXISTS `biz_mqtt_failure_aggregate`"));
            new ResourceDatabasePopulator(new ByteArrayResource(v22.getBytes(StandardCharsets.UTF_8))).execute(ds);
            jdbc.execute("CREATE TABLE biz_energy_activity_correction(correction_id VARCHAR(32) PRIMARY KEY,meter_point_id VARCHAR(32),building_id VARCHAR(32),original_fact_identity VARCHAR(160))");
            jdbc.execute("CREATE TABLE biz_energy_activity_correction_version(correction_version_id VARCHAR(32),correction_id VARCHAR(32),original_value DECIMAL(30,12),corrected_value DECIMAL(30,12),status VARCHAR(20))");
            jdbc.execute("CREATE TABLE biz_energy_point_profile(point_id VARCHAR(32))");
            jdbc.execute("CREATE TABLE d01_audit(action_type VARCHAR(80))");
            td.execute("CREATE DATABASE "+database+" KEEP 365 DURATION 1d WAL_LEVEL 1");org.springframework.test.util.ReflectionTestUtils.invokeMethod(new TdengineConfig(properties),"initializeHvacSchema",td);
            System.out.println("MYSQL_VERSION="+jdbc.queryForObject("SELECT VERSION()",String.class));
            System.out.println("TDENGINE_VERSION="+td.queryForObject("SELECT SERVER_VERSION()",String.class));return;
        }
        var ledger=new RawTelemetryLedger(jdbc,codec,16,50000);
        var raw=new TdengineHvacRawEventRepository(td,properties,ledger,codec);
        var journal=new TelemetryMessageJournal(jdbc,mapper){
            @Override public Entry freeze(String hash,Snapshot snapshot){var result=super.freeze(hash,snapshot);if(stage.equals("registered"))Runtime.getRuntime().halt(31);return result;}
            @Override public <T>T locked(String id,java.util.function.Function<Entry,T> action){T result=super.locked(id,action);if(stage.equals("committed"))Runtime.getRuntime().halt(35);return result;}
        };
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.addMapper(TelemetryReceiptMapper.class);config.addMapper(TelemetryReceiptFailureMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        var session=new SqlSessionTemplate(factory.getObject());var receipt=spy(session.getMapper(TelemetryReceiptMapper.class));
        doAnswer(inv->{if(stage.equals("receipt"))Runtime.getRuntime().halt(33);Object result=inv.callRealMethod();if(stage.equals("receipt_inserted"))Runtime.getRuntime().halt(34);return result;}).when(receipt).insert(any(com.platform.iot.reliability.model.TelemetryReceipt.class));
        var identities=mock(DeviceIdentityProvider.class);var unknown=mock(UnknownDeviceHandler.class);
        var binding=new DeviceIdentityBinding("I1",new DeviceIdentityKey("MAC","A"),"E_"+(args.length>2?args[2]:"none"),"DEV","B1","P1",AckMode.EVIDENCE_ONLY,CorrelationPolicy.NONE,null,null);
        when(identities.find(any())).thenReturn(Optional.of(binding));
        var hvac=new HvacIngestionService(mock(TelemetryQualityValidator.class),raw,new SimpleMeterRegistry(),ignored->{},30,"MQTT_STANDARD_V1");
        var standard=spy(new StandardTelemetryIngestionService(identities,unknown,mock(com.platform.iot.quality.DataPointConfigProvider.class),hvac,"MQTT_STANDARD_V1"));
        var ingestion=new TelemetryV2IngestionService(identities,unknown,standard,receipt,session.getMapper(TelemetryReceiptFailureMapper.class),new AckModeResolver(),new SimpleMeterRegistry(),"unused",journal);
        if(mode.equals("seed")) {
            String point=args[1];check(point.matches("[A-Za-z0-9_]{1,28}"),"Invalid synthetic point");
            double value=Double.parseDouble(args[2]);var e=event(point,value);
            td.execute("CREATE TABLE "+database+".st_raw_event_"+point+" USING "+database+".st_raw_event (point_id,point_code,building_id,system_group_id,equip_id,equip_code,family_code,component_code,suffix_code,is_for_calc) TAGS ('"+point+"','TEMP','B1',NULL,'E_"+point+"','DEV','TEMP',NULL,'TEMP',0)");
            td.execute("INSERT INTO "+database+".st_raw_event_"+point+" (ts,received_time,val,data_quality,late_flag,source_system,source_point_code,source_device_id) VALUES ("+time()+","+(time()+1000)+","+value+",0,0,'MQTT_STANDARD_V1','MAC:A:TEMP','DEV')");
            System.out.println("VERIFIED historical_seed_without_ledger="+point);return;
        }
        if(mode.equals("write")) {
            String id=args[1],point=args[2];double value=Double.parseDouble(args[3]);
            var events=List.of(event(point,value),event(point+"_2",value+1));
            doReturn(new StandardTelemetryIngestionService.PreparedBatch(StandardTelemetryResult.accepted(2),events)).when(standard).prepareImmutable(any(),anyLong());
            var message=new TelemetryV2Message("2.0","P1",1,new DeviceIdentityKey("MAC","A"),id,id,null,null,time(),time()+1000,null,null,null,null,"DEVICE_REPORTED","DEVICE_REPORTED","EXACT","EVIDENCE_ONLY","NONE",List.of(new StandardMetric("TEMP",BigDecimal.valueOf(value),"C","/temp"),new StandardMetric("TEMP2",BigDecimal.valueOf(value+1),"C","/temp2")));
            var result=ingestion.ingest(message,time()+1000);System.out.println("RESULT="+result.status());check(!result.retryable(),"Unexpected retryable write");return;
        }
        if(mode.equals("recover")) {
            for(var e:ledger.pending(100,System.currentTimeMillis()))raw.replayFrozen(e);
            for(var entry:journal.pending(System.currentTimeMillis(),100))check(!ingestion.recover(entry.id()).retryable(),"Recovery incomplete");
            for(String id:journal.conflictMessages())journal.materializeConflicts(id,ledger,codec);
            System.out.println("RECOVERED=yes");return;
        }
        if(mode.equals("verify")) {
            String point=args[1];double value=Double.parseDouble(args[2]);
            var values=td.queryForList("SELECT val FROM "+database+".st_raw_event WHERE point_id='"+point+"' AND ts="+time());
            check(values.size()==1&&Double.compare(((Number)values.getFirst().get("val")).doubleValue(),value)==0,"Immutable value mismatch");
            check(jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_receipt WHERE equip_id=?",Integer.class,"E_"+point)==Integer.parseInt(args[3]),"Receipt count mismatch");
            check(jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_raw_intent WHERE point_id=? AND storage_state<>'PERSISTED'",Integer.class,point)==0,"Pending raw");
            System.out.println("VERIFIED point="+point+" original="+value);return;
        }
        if(mode.equals("correct")) {
            String point=args[1];var scope=mock(BuildingScopeService.class);
            var service=new TelemetryConflictService(jdbc,ledger,codec,new EnergyAggregationAuthorization(mock(BackendDutyService.class),scope),mock(EnergyAggregationGovernanceService.class),scope,e->jdbc.update("INSERT INTO d01_audit VALUES (?)",e.actionType()),new AuditGovernanceProperties());
            jdbc.update("INSERT INTO biz_telemetry_conflict_owner VALUES ('B1',7,1,0)");
            var view=service.detail(7,List.of("BUILDING_OWNER"),point,time());
            double chosen=Double.parseDouble(args[2]);String evidence=view.variants().stream().filter(v->v.event().value()==chosen).findFirst().orElseThrow().evidenceId();
            var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.executeWithoutResult(s->service.resolve(7,List.of("BUILDING_OWNER"),point,time(),new TelemetryConflictService.ResolveRequest(evidence,view.revision(),"设备报文核实","隔离验收工单")));
            check(raw.findMeterPointHistory("B1","E_"+point,point,time(),time()+1,null,10).getFirst().value()==chosen,"History correction missing");
            check(raw.findLatestByEquipmentPoints("B1","E_"+point,List.of(point)).getFirst().value()==chosen,"Latest correction missing");
            check(Math.abs(raw.findEquipmentTrend("B1","E_"+point,List.of(point),time(),time()+1,60).getFirst().average()-chosen)<1e-9,"Trend correction missing");
            check(raw.findWindow(time(),time()+1,true).stream().filter(e->e.pointId().equals(point)).findFirst().orElseThrow().value()==chosen,"Calculation correction missing");
            System.out.println("VERIFIED effective_reads_and_calculation="+chosen+" original="+view.original().value());
        }
    }
}
