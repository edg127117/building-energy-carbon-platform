package com.platform.carbon;

import com.platform.audit.AuditEvidenceWriter;
import com.platform.energy.classification.ClassificationContracts;
import com.platform.energy.classification.ClassificationService;
import com.platform.energy.period.EnergyPeriodAuthorization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.time.Instant;
import java.util.*;
import static com.platform.carbon.JointFlowConfigurationContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 本地H2验证版本、选用及审计原子性；建筑与职责调用独立断言，不访问运行库。 */
@SpringBootTest
@ActiveProfiles("test")
@org.springframework.test.context.jdbc.Sql("/joint-flow-schema-test.sql")
class JointFlowConfigurationIntegrationTest {
    @Autowired JointFlowConfigurationService service;
    @Autowired JdbcTemplate jdbc;
    @MockBean ClassificationService classifications;
    @MockBean EnergyPeriodAuthorization authorization;
    @MockBean AuditEvidenceWriter audit;
    private static final List<String> ROLES=List.of("ENERGY_MANAGER");
    @BeforeEach void clear() {
        jdbc.update("DELETE FROM biz_joint_flow_selection");jdbc.update("DELETE FROM biz_joint_flow_configuration");
        clearOriginal();
    }
    @AfterEach void clearOriginal(){jdbc.update("DELETE FROM biz_energy_classification_version WHERE version_id='FLOW_COMPAT_ORIGINAL'");}
    static Configuration config(List<Dimension> dimensions,List<ClassificationContracts.Configuration> mappings) {
        return new Configuration(1,Instant.parse("2024-01-01T00:00:00Z"),Instant.parse("2028-01-01T00:00:00Z"),List.of("REL"),dimensions,mappings,List.of(),"经确认的计量与展示依据",List.of());
    }
    static Dimension energy(){return new Dimension("ENERGY_TYPE","能源类型","ENERGY_ITEM",null,null,List.of(new DisplayNode("ELECTRICITY","外购电力",0)));}
    private Version draft() {
        var draft=service.create(7,ROLES,"BLD001",new Create("建立展示版本",null));
        return service.save(7,ROLES,"BLD001",draft.versionId(),new Save(draft.revision(),config(List.of(energy()),List.of()),"保存明确配置"));
    }
    private Version approve(Version draft) {
        var pending=service.transition(7,ROLES,"BLD001",draft.versionId(),new Transition(draft.revision(),"提交"),"submit");
        return service.transition(8,ROLES,"BLD001",draft.versionId(),new Transition(pending.revision(),"独立审核"),"approve");
    }
    @Test void approvalIsImmutableAndNeverSelectsOrClosesFormalClassification() {
        jdbc.update("INSERT INTO biz_energy_classification_version(version_id,building_id,version_no,revision,status,effective_from,effective_to,configuration_json,reason,created_by,created_at) VALUES ('FLOW_COMPAT_ORIGINAL','BLD001',9999,4,'APPROVED',123,NULL,'{}','本地有效期回归夹具',7,1)");
        var original=jdbc.queryForList("SELECT * FROM biz_energy_classification_version");
        var first=approve(draft());
        assertThat(service.selection(7,ROLES,"BLD001").versionId()).isNull();
        assertThatThrownBy(()->service.save(7,ROLES,"BLD001",first.versionId(),new Save(first.revision(),first.configuration(),"禁止改已批准"))).hasMessageContaining("状态或修订号");
        var selected=service.select(7,ROLES,"BLD001",new Select(0L,first.versionId(),"明确选用"));
        assertThat(selected.revision()).isEqualTo(1);
        var second=approve(draft());
        assertThat(service.selection(7,ROLES,"BLD001").versionId()).isEqualTo(first.versionId());
        assertThatThrownBy(()->service.select(7,ROLES,"BLD001",new Select(0L,second.versionId(),"过期写入"))).hasMessageContaining("修订号");
        service.select(7,ROLES,"BLD001",new Select(1L,second.versionId(),"切换"));
        service.select(7,ROLES,"BLD001",new Select(2L,first.versionId(),"回退"));
        assertThat(service.resolve(7,ROLES,"BLD001",null,false).versionId()).isEqualTo(first.versionId());
        assertThat(jdbc.queryForList("SELECT * FROM biz_energy_classification_version")).isEqualTo(original);
        verify(authorization,times(2)).requireSeparation(7,8);
        verify(authorization,atLeastOnce()).requirePolicyMaintainer(7,ROLES);
        verify(authorization,atLeastOnce()).requirePolicyReviewer(8,ROLES);
        verify(classifications,atLeastOnce()).validateDisplayMapping(eq(7L),eq(ROLES),eq("BLD001"),any());
        verify(audit,atLeastOnce()).append(argThat(e->"SELECT_FLOW_CONFIGURATION".equals(e.actionType())&&"BLD001".equals(e.buildingId())));
    }
    @Test void draftsPreviewButNeverEnterFormalScreenAndScopeIsCheckedBeforeRead() {
        var draft=draft();
        assertThatThrownBy(()->service.save(7,ROLES,"BLD001",draft.versionId(),new Save(0L,draft.configuration(),"陈旧草稿编辑"))).hasMessageContaining("修订号");
        assertThat(service.resolve(7,ROLES,"BLD001",draft.versionId(),true).status()).isEqualTo("DRAFT");
        assertThatThrownBy(()->service.resolve(7,ROLES,"BLD001",draft.versionId(),false)).hasMessageContaining("已批准");
        assertThatThrownBy(()->service.select(7,ROLES,"BLD001",new Select(0L,draft.versionId(),"不许选草稿"))).hasMessageContaining("已批准");
        assertThatThrownBy(()->service.get(7,ROLES,"OTHER",draft.versionId())).hasMessageContaining("不属于");
        doThrow(CarbonErrors.error(403,CarbonErrors.FORBIDDEN,"跨建筑拒绝")).when(authorization).checkBuilding(9,ROLES,"BLD001");
        assertThatThrownBy(()->service.get(9,ROLES,"BLD001",draft.versionId())).hasMessageContaining("跨建筑拒绝");
        var pending=service.transition(7,ROLES,"BLD001",draft.versionId(),new Transition(draft.revision(),"提交"),"submit");
        var rejected=service.transition(8,ROLES,"BLD001",draft.versionId(),new Transition(pending.revision(),"证据不足"),"reject");
        assertThat(rejected.status()).isEqualTo("REJECTED");
        var copy=service.create(7,ROLES,"BLD001",new Create("复制后修订",rejected.versionId()));
        assertThat(copy.configuration()).isEqualTo(draft.configuration());
    }
    @Test void failedAuditRollsBackVersionAndSelectionAndStaleEditsReject() {
        doThrow(new IllegalStateException("审计不可用")).when(audit).append(any());
        assertThatThrownBy(()->service.create(7,ROLES,"BLD001",new Create("事务失败",null))).hasMessageContaining("审计不可用");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_joint_flow_configuration",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_joint_flow_selection",Integer.class)).isZero();
        reset(audit);var approved=approve(draft());
        doThrow(new IllegalStateException("审计不可用")).when(audit).append(any());
        assertThatThrownBy(()->service.select(7,ROLES,"BLD001",new Select(0L,approved.versionId(),"选择失败"))).hasMessageContaining("审计不可用");
        assertThat(service.selection(7,ROLES,"BLD001").revision()).isZero();
        assertThat(service.selection(7,ROLES,"BLD001").versionId()).isNull();
    }
    @Test void rejectsUnknownRepeatedDimensionsConflictingBindingsAndRoomAsUndeclaredFloor() {
        assertThatThrownBy(()->service.validate(config(List.of(energy(),energy()),List.of()))).hasMessageContaining("重复");
        assertThatThrownBy(()->service.validate(config(List.of(new Dimension("SCRIPT","脚本","ENERGY_ITEM",null,null,List.of())),List.of()))).hasMessageContaining("未知");
        assertThatThrownBy(()->service.validate(config(List.of(new Dimension("FLOOR","楼层","FROZEN_SCHEME","ROOMS",null,List.of())),List.of()))).hasMessageContaining("楼层级");
        var bindings=List.of(new ClassificationContracts.Binding("USE","A","POINT","P","P","METERING","CONFIRMED","证据"),new ClassificationContracts.Binding("USE","B","POINT","P","P","METERING","CONFIRMED","证据"));
        var mapping=new ClassificationContracts.Configuration("REL",List.of(new ClassificationContracts.Scheme("USE","用途","USAGE","ADDITIVE")),
                List.of(new ClassificationContracts.Node("USE","A","A",null),new ClassificationContracts.Node("USE","B","B",null)),bindings,List.of());
        assertThatThrownBy(()->service.validate(config(List.of(new Dimension("USAGE","用途","MAPPED_SCHEME","USE",null,List.of())),List.of(mapping)))).hasMessageContaining("重复归属");
    }
}
