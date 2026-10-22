package com.platform.iot.reliability;

import com.platform.audit.*;
import com.platform.energy.activity.EnergyActivityDataService;
import com.platform.energy.activity.EnergyActivityDataContracts.*;
import com.platform.energy.aggregation.*;
import com.platform.energy.aggregation.api.EnergyAggregationContracts.*;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** H2 事务验证普通选择向既有能源审核移交；活动读取为受控夹具，不代表现场核算。 */
class TelemetryConflictEnergyTransitionTest {
    TelemetryConflictTest f;
    EnergyAggregationGovernanceRepository repository;
    EnergyAggregationGovernanceService energy;
    BackendDutyService duties;
    long time;

    @BeforeEach void setup() {
        f=new TelemetryConflictTest();f.setup();time=f.event(0).eventTime();
        f.jdbc.update("INSERT INTO building(building_id,building_name) VALUES ('B1','建筑1')");
        f.jdbc.update("""
                INSERT INTO biz_data_point
                (point_id,point_code,point_name,building_id,naming_rule_id,family_code,
                 component_code,suffix_code,data_type,unit,is_for_calc,status,del_flag)
                VALUES ('P1','METER_TOTAL','累计测点','B1','RULE_WCR_MAIN','WCR','MAIN','PPE',
                        'ACCUMULATE','kWh',1,'ONLINE',0)
                """);
        duties=mock(BackendDutyService.class);
        when(duties.hasDuty(7,BackendDuty.ENERGY_RULE_MAINTAIN)).thenReturn(true);
        when(duties.hasDuty(9,BackendDuty.ENERGY_RULE_REVIEW)).thenReturn(true);
        var auth=new EnergyAggregationAuthorization(duties,f.scope);
        var activity=mock(EnergyActivityDataService.class);
        var raw=new RawActivityDataView("P1","METER_TOTAL","kWh","ELECTRICITY","GRID_PURCHASED",
                "CUMULATIVE","CONFIRMED",0,"MQTT_STANDARD_V1","MAC:A:TEMP","DEV1",100,time,time+1000,
                "Q0",false,"SYSTEM_DEFAULT_Q0_ONLY",null,0);
        when(activity.aggregationSnapshot(anyLong(),anyCollection(),eq("B1"),eq("P1"),eq(time),eq(time+1),anyLong()))
                .thenReturn(new AggregationActivitySnapshot("B1","P1","kWh","CUMULATIVE","CONFIRMED",0,
                        time,time+1,time+1000,time+1000,"POINT_ID_EVENT_TIME","APPEND_ONLY",List.of(raw)));
        repository=new EnergyAggregationGovernanceRepository(f.jdbc);
        energy=new EnergyAggregationGovernanceService(auth,repository,activity,f.audit,new AuditGovernanceProperties());
        f.conflicts=new TelemetryConflictService(f.jdbc,f.ledger,f.codec,auth,energy,f.scope,f.audit,new AuditGovernanceProperties());
        f.conflict();resolve(List.of("BUILDING_OWNER"),120);
    }

    @ParameterizedTest @ValueSource(ints={100,130})
    void existingEnergyApiTransfersEffectiveValueOnlyAfterApproval(int corrected) {
        addEnergyProfile();var pending=create(corrected);
        assertEffective(120,"telemetry:1");
        var approved=approve(pending.correctionVersionId());
        assertEffective(corrected,approved.correctionVersionId());
        assertThat(f.ledger.find("P1",time).selectedJson()).isNull();
        assertThat(f.codec.decode(f.ledger.find("P1",time).snapshotJson()).value()).isEqualTo(100);
        assertThat(f.jdbc.queryForObject("SELECT selected_json FROM biz_telemetry_resolution WHERE version_no=1",String.class))
                .contains("120.0");
        verify(duties).requireSeparation(7,9);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_change_task",Integer.class)).isEqualTo(1);
    }

    @Test void d01PendingSubmissionPreservesPriorValueAndItsEvidenceVersion() {
        addEnergyProfile();f.ledger.reserve(f.event(130),()->null);
        var pending=resolve(List.of("ENERGY_MANAGER"),130);
        assertThat(pending.state()).isEqualTo("PENDING_REVIEW");
        assertEffective(120,"telemetry:1");
        var approved=approve(pending.correctionVersionId());assertEffective(130,approved.correctionVersionId());
    }

    @Test void approvalAuditFailureRollsBackHandoffAndKeepsPriorValue() {
        addEnergyProfile();var pending=create(130);
        doThrow(new IllegalStateException("audit unavailable")).when(f.audit).append(any());
        assertThatThrownBy(()->approve(pending.correctionVersionId())).hasMessageContaining("audit unavailable");
        assertThat(repository.findCorrectionVersion(pending.correctionVersionId()).status()).isEqualTo("PENDING_REVIEW");
        assertEffective(120,"telemetry:1");
    }

    @Test void derivedCapacityFailureRollsBackApprovalAndRetainsOrdinaryChoice() {
        addEnergyProfile();var pending=create(130);
        f.jdbc.update("UPDATE biz_telemetry_change_task SET state='COMPLETE'");
        f.jdbc.update("UPDATE biz_telemetry_conflict_budget SET used_rows=0 WHERE budget_key='PENDING_DERIVED'");
        TelemetryConflictService.enqueue(f.jdbc,"P2","B1",time,"other",1);
        ReflectionTestUtils.setField(repository,"maxDerived",1);
        assertThatThrownBy(()->approve(pending.correctionVersionId())).hasMessageContaining("capacity");
        assertThat(repository.findCorrectionVersion(pending.correctionVersionId()).status()).isEqualTo("PENDING_REVIEW");
        assertEffective(120,"telemetry:1");
    }

    @Test void rollbackAfterHandoffRestoresBothApprovalAndOrdinaryPointer() {
        addEnergyProfile();var pending=create(130);
        assertThatThrownBy(()->f.transaction(()->{
            energy.approveCorrection(9,List.of("ENERGY_MANAGER"),pending.correctionVersionId(),new ApproveEvidenceRequest(0,"核实通过"));
            assertThat(f.ledger.find("P1",time).selectedJson()).isNull();
            throw new IllegalStateException("transaction interrupted");
        })).hasMessageContaining("transaction interrupted");
        assertEffective(120,"telemetry:1");
        assertThat(repository.findCorrectionVersion(pending.correctionVersionId()).status()).isEqualTo("PENDING_REVIEW");
    }

    @Test void transitionRetainsReviewerDutySeparationAndBuildingChecks() {
        addEnergyProfile();var pending=create(130);
        when(duties.hasDuty(9,BackendDuty.ENERGY_RULE_REVIEW)).thenReturn(false);
        assertThatThrownBy(()->approve(pending.correctionVersionId())).isInstanceOf(BusinessException.class);
        when(duties.hasDuty(9,BackendDuty.ENERGY_RULE_REVIEW)).thenReturn(true);
        doThrow(new BusinessException(403,"职责分离")).when(duties).requireSeparation(7,9);
        assertThatThrownBy(()->approve(pending.correctionVersionId())).hasMessageContaining("职责分离");
        doNothing().when(duties).requireSeparation(7,9);
        doThrow(new BusinessException(403,"建筑越界")).when(f.scope).checkAccess(9L,List.of("ENERGY_MANAGER"),"B1");
        assertThatThrownBy(()->approve(pending.correctionVersionId())).hasMessageContaining("建筑越界");
        assertEffective(120,"telemetry:1");
        assertThat(repository.findCorrectionVersion(pending.correctionVersionId()).status()).isEqualTo("PENDING_REVIEW");
    }

    private void addEnergyProfile() {
        f.jdbc.update("""
                INSERT INTO biz_energy_point_profile
                (profile_id,point_id,building_id,energy_type,energy_subtype,value_semantics,reporting_period,
                 annual_summary,confirmation_status,evidence_reference,create_by,update_by)
                VALUES ('EP1','P1','B1','ELECTRICITY','GRID_PURCHASED','CUMULATIVE','MONTH',true,'CONFIRMED','工单',7,7)
                """);
    }
    private CorrectionVersionView create(int value) {
        return f.transaction(()->energy.createCorrection(7,List.of("ENERGY_MANAGER"),new CreateCorrectionVersionRequest(null,
                "B1","P1",RawTelemetryLedger.identity("P1",time),BigDecimal.valueOf(100),BigDecimal.valueOf(value),"核实","MANUAL","工单")));
    }
    private CorrectionVersionView approve(String id) {
        return f.transaction(()->energy.approveCorrection(9,List.of("ENERGY_MANAGER"),id,new ApproveEvidenceRequest(0,"核实通过")));
    }
    private TelemetryConflictService.ConflictView resolve(List<String> roles,double value) {
        var view=f.conflicts.detail(7,roles,"P1",time);
        String evidence=view.variants().stream().filter(v->v.event().value()==value).findFirst().orElseThrow().evidenceId();
        return f.transaction(()->f.conflicts.resolve(7,roles,"P1",time,
                new TelemetryConflictService.ResolveRequest(evidence,view.revision(),"核实","工单")));
    }
    private void assertEffective(double value,String version) {
        assertThat(f.ledger.effective(List.of(f.event(100))).getFirst().value()).isEqualTo(value);
        assertThat(f.ledger.selections(List.of("P1"),time,time+1).get(RawTelemetryLedger.identity("P1",time)).versionId())
                .isEqualTo(version);
    }
}
