package com.platform.relation;

import com.platform.relation.api.RelationContracts.*;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static com.platform.relation.RelationStagingContracts.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RelationAssetStagingServiceTest {
    private static final Set<String> ENERGY=Set.of("ENERGY_MANAGER"),ADMIN=Set.of("PLATFORM_ADMIN");
    @Autowired RelationAssetStagingService staging;
    @Autowired RelationGovernanceService relations;
    @Autowired RelationGovernanceRepository repository;
    @Autowired RelationStagingRepository stagedRepository;
    @Autowired JdbcTemplate jdbc;
    @MockBean BuildingScopeService scope;
    private VersionView draft;

    @BeforeEach void prepare() {
        org.mockito.Mockito.when(scope.canAccess(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyCollection(),org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        for(String table: new String[]{"biz_relation_staged_asset","biz_relation_validation_issue","biz_relation_review_request",
                "biz_relation_audit_log","biz_meter_structure_version_item","biz_metering_assignment_version_item",
                "biz_semantic_relation_version_item","biz_asset_assignment_version_item","biz_space_parent_version_item",
                "biz_relation_node","biz_metering_boundary","biz_relation_version","biz_relation_model"}) jdbc.update("DELETE FROM "+table);
        var first=relations.initialize(101L,ENERGY,"BLD001","init-stage","初始化");
        publish(first.versionId(),first.revision());
        draft=relations.createVersion(101L,ENERGY,"BLD001","new-stage",new CreateVersionRequest("新档案",first.versionId(),relations.model(101L,ENERGY,"BLD001").modelRevision()));
    }
    @Test void publishesEquipmentAndPointOnlyWithApprovedVersion() {
        var equipment=equipment(draft.revision(),"SPACE001");
        var asset=equipment.assets().getFirst();
        assertThat(count("biz_equipment","equip_id",asset.objectId())).isZero();
        assertThat(repository.findNodeByCode(draft.versionId(),"BLD001","EQUIPMENT",asset.code())).isPresent();
        assertThat(repository.findNodeByCode("BLD001","EQUIPMENT",asset.code())).isEmpty();
        assertThat(repository.nodeCatalogue(draft.versionId(),"BLD001","EQUIPMENT",100,0)).extracting("businessObjectId").contains(asset.objectId());
        assertThat(repository.nodeCatalogue("another-version","BLD001","EQUIPMENT",100,0)).extracting("businessObjectId").doesNotContain(asset.objectId());
        var point=staging.point(101L,ENERGY,"BLD001",draft.versionId(),new PointRequest(equipment.revision(),"明确测点单位","累计量","RULE_WCR_MAIN",null,"Energy",asset.objectId(),"GROUP001","ACCUMULATE","kWh",1,"OFFLINE"));
        var meter=point.assets().stream().filter(a->a.objectType().equals("POINT")).findFirst().orElseThrow();
        assertThat(count("biz_data_point","point_id",meter.objectId())).isZero();
        publish(draft.versionId(),point.revision());
        assertThat(count("biz_equipment","equip_id",asset.objectId())).isOne();
        assertThat(count("biz_data_point","point_id",meter.objectId())).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM biz_data_point WHERE point_id=?",String.class,meter.objectId())).isEqualTo("OFFLINE");
        assertThat(staging.list(101L,ENERGY,"BLD001",draft.versionId()).assets()).allMatch(a->a.status().equals("PUBLISHED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_device_identity WHERE equip_id=?",Long.class,asset.objectId())).isZero();
    }
    @Test void missingOwnershipStaysDraftAndBlocksSubmission() {
        var equipment=equipment(draft.revision(),null);
        assertThat(relations.validate(101L,ENERGY,draft.versionId()).errorCount()).isPositive();
        assertThatThrownBy(()->relations.submit(101L,ENERGY,draft.versionId(),"unknown-submit",new RevisionReasonRequest(equipment.revision(),"未确认"))).isInstanceOfSatisfying(com.platform.framework.exception.BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(RelationErrors.VALIDATION_FAILED));
        assertThat(count("biz_equipment","equip_id",equipment.assets().getFirst().objectId())).isZero();
    }
    @Test void staleRevisionAndCrossBuildingAreRejectedWithoutWriting() {
        equipment(draft.revision(),"SPACE001");
        assertThatThrownBy(()->equipment(draft.revision(),"SPACE001")).hasMessageContaining("修订");
        assertThatThrownBy(()->staging.list(101L,ENERGY,"BLD002",draft.versionId())).hasMessageContaining("建筑");
        assertThatThrownBy(()->staging.list(101L,Set.of("BUILDING_OWNER"),"BLD001",draft.versionId())).hasMessageContaining("人员");
        assertThat(staging.list(101L,ENERGY,"BLD001",draft.versionId()).assets()).hasSize(1);
    }
    @Test void catalogueChangesInvalidateApprovedDefinition() {
        var equipment=equipment(draft.revision(),"SPACE001");
        jdbc.update("UPDATE biz_equipment_type SET asset_code_prefix='CHANGED' WHERE type_code='WCR'");
        assertThat(relations.validate(101L,ENERGY,draft.versionId()).issues()).anyMatch(i->i.message().contains("类型已变化"));
        assertThat(count("biz_equipment","equip_id",equipment.assets().getFirst().objectId())).isZero();
    }
    @Test void cancelledCodeIsReservedAndReferencedEquipmentCannotBeCancelled() {
        var one=equipment(draft.revision(),"SPACE001");
        var first=one.assets().getFirst();
        var cancelled=staging.remove(101L,ENERGY,"BLD001",draft.versionId(),first.stagedId(),new RemoveRequest(one.revision(),"取消"));
        var two=equipment(cancelled.revision(),"SPACE001");
        assertThat(two.assets().getFirst().code()).isNotEqualTo(first.code());
        var eq=two.assets().getFirst();
        var point=staging.point(101L,ENERGY,"BLD001",draft.versionId(),new PointRequest(two.revision(),"明确","测点","RULE_WCR_MAIN",null,"Energy",eq.objectId(),"GROUP001","ACCUMULATE","kWh",1,"OFFLINE"));
        assertThatThrownBy(()->staging.remove(101L,ENERGY,"BLD001",draft.versionId(),eq.stagedId(),new RemoveRequest(point.revision(),"误删"))).hasMessageContaining("引用");
    }
    @Test void publicationFailureRollsBackFormalAssetsAndVersionPointer() {
        var equipment=equipment(draft.revision(),"SPACE001");
        var review=relations.submit(101L,ENERGY,draft.versionId(),"submit-fail",new RevisionReasonRequest(equipment.revision(),"提交"));
        relations.approve(202L,ADMIN,review.requestId(),"approve-fail",new ReviewDecisionRequest("通过"));
        String previous=relations.model(101L,ENERGY,"BLD001").activeVersionId();
        // 正式档案唯一键冲突模拟发布失败，不能只回滚关系指针而遗留运行资产。
        var asset=equipment.assets().getFirst();
        jdbc.update("INSERT INTO biz_equipment(equip_id,equip_code,equip_name,type_code,equip_category,building_id,space_id,system_group_id,del_flag) VALUES ('conflicting-stage',?,'冲突','WCR','CHILLER','BLD001','SPACE001','GROUP001',0)",asset.code());
        assertThatThrownBy(()->relations.activate(202L,ADMIN,draft.versionId(),"activate-fail",new ActivationRequest(relations.model(202L,ADMIN,"BLD001").modelRevision(),"发布"))).isInstanceOf(RuntimeException.class);
        assertThat(relations.model(101L,ENERGY,"BLD001").activeVersionId()).isEqualTo(previous);
        assertThat(count("biz_equipment","equip_id",asset.objectId())).isZero();
        assertThat(stagedRepository.list(draft.versionId()).getFirst().status()).isEqualTo("STAGED");
    }
    private Mutation equipment(long revision,String space) {return staging.equipment(101L,ENERGY,"BLD001",draft.versionId(),new EquipmentRequest(revision,"依据档案","新设备","WCR",null,null,space,"GROUP001"));}
    private long count(String table,String column,String id) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+column+"=?",Long.class,id);}
    private void publish(String version,long revision) {
        var review=relations.submit(101L,ENERGY,version,"submit-"+version,new RevisionReasonRequest(revision,"提交"));
        relations.approve(202L,ADMIN,review.requestId(),"approve-"+version,new ReviewDecisionRequest("通过"));
        relations.activate(202L,ADMIN,version,"activate-"+version,new ActivationRequest(relations.model(202L,ADMIN,"BLD001").modelRevision(),"发布"));
    }
}
