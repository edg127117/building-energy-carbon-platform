package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.*;
import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.energy.classification.ClassificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.time.Instant;
import java.util.*;
import static com.platform.carbon.CarbonProjectContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 隔离H2执行新增DDL和事务，外部关系及专业职责用替身，不连接现场资源。 */
class CarbonProjectServiceTest {
    private CarbonProjectService service;private CarbonAuthorization auth;private CarbonRuleRepository rules;
    private JdbcTemplate jdbc;
    private ClassificationService classifications;
    private EnergyCatalogService catalog;
    private final Set<String> roles=Set.of("ENERGY_MANAGER");
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:project_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);jdbc.execute("CREATE TABLE building(building_id VARCHAR(32),del_flag INT)");jdbc.update("INSERT INTO building VALUES ('B',0)");
        new ResourceDatabasePopulator(new FileSystemResource("src/env/init/V77__mysql_project_energy_configuration.sql")).execute(ds);
        auth=mock(CarbonAuthorization.class);rules=mock(CarbonRuleRepository.class);
        classifications=mock(ClassificationService.class);catalog=mock(EnergyCatalogService.class);
        var target=new CarbonProjectService(new CarbonProjectRepository(jdbc),auth,new CarbonProjectCore(),classifications,
                catalog,new ObjectMapper().findAndRegisterModules(),mock(AuditEvidenceWriter.class),new AuditGovernanceProperties(),rules);
        ProxyFactory factory=new ProxyFactory(target);factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        service=(CarbonProjectService)factory.getProxy();
    }
    private Version draft(){return service.create(1,roles,"B",new Create("初始化",null));}
    private Version save(Version v){return service.save(1,roles,"B",v.versionId(),new Save(v.revision(),new Configuration(null,List.of(new Energy("WATER","WATER","RESERVED")),List.of()),"预留水"));}
    private Version transition(long user,Version v,String action){return service.transition(user,roles,"B",v.versionId(),action,new Transition(v.revision(),action,null));}
    private Version activate(Version v,Instant from){return service.activate(3,roles,"B",v.versionId(),new Transition(v.revision(),"生效",from));}
    @Test void lifecycleHasSeparateReviewActivationAndStableHistory() {
        Version v=save(draft());assertThat(service.readiness(1,roles,"B",v.versionId()).issues()).singleElement().satisfies(i->assertThat(i.code()).isEqualTo("RESERVED"));
        v=transition(1,v,"submit");v=transition(2,v,"approve");
        verify(auth).requireSeparation(1,2);verify(auth).requireRuleReviewer(2,roles,"B");
        Instant first=Instant.parse("2026-01-01T00:00:00Z");v=activate(v,first);
        assertThat(service.effective("B",first,first.plusSeconds(3600)).versionId()).isEqualTo(v.versionId());
        Version next=save(draft());next=transition(1,next,"submit");next=transition(2,next,"approve");next=activate(next,Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(service.effective("B",first,Instant.parse("2026-02-01T00:00:00Z")).versionId()).isEqualTo(v.versionId());
        assertThat(service.effective("B",Instant.parse("2025-01-01T00:00:00Z"),first)).isNull();
        assertThatThrownBy(()->service.effective("B",first,Instant.parse("2026-03-01T00:00:00Z"))).hasMessageContaining("完整期间");
    }
    @Test void historicalProjectKeepsCurrentAndDoesNotScheduleOldReportRecalculation() {
        Version current = save(draft()); current = transition(1, current, "submit"); current = transition(2, current, "approve");
        Instant january = Instant.parse("2026-01-01T00:00:00Z");
        current = activate(current, january);
        Version past = save(draft()); past = transition(1, past, "submit"); past = transition(2, past, "approve");
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        var history = service.activateHistorical(3, roles, "B", past.versionId(), new HistoricalActivation(past.revision(), "历史适用依据", start, january));
        assertThat(history.status()).isEqualTo("RETIRED");
        assertThat(service.effective("B", start, january).versionId()).isEqualTo(history.versionId());
        assertThat(service.effective("B", january, january.plusSeconds(3600)).versionId()).isEqualTo(current.versionId());
        verify(rules, times(1)).insertDependencyChange(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        Version next = save(draft()); next = transition(1, next, "submit"); next = transition(2, next, "approve");
        activate(next, Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(service.effective("B", start, january).effectiveTo()).isEqualTo(january);
    }

    @Test void firstCurrentProjectCannotOverlapAlreadyRegisteredHistory() {
        Instant start = Instant.parse("2025-01-01T00:00:00Z"), end = Instant.parse("2025-09-01T00:00:00Z");
        Version past = save(draft()); past = transition(1, past, "submit"); past = transition(2, past, "approve");
        var history = service.activateHistorical(3, roles, "B", past.versionId(),
                new HistoricalActivation(past.revision(), "历史适用依据", start, end));
        Version next = save(draft()); next = transition(1, next, "submit"); next = transition(2, next, "approve");
        Version approved = next;
        assertThatThrownBy(() -> activate(approved, end.minusSeconds(1))).hasMessageContaining("修订");
        assertThat(service.effective("B", start, end).versionId()).isEqualTo(history.versionId());
        assertThat(activate(approved, end).status()).isEqualTo("ACTIVE");
        assertThat(service.effective("B", start, end).effectiveTo()).isEqualTo(end);
    }

    @Test void historicalProjectRejectsOverlapAndIncompleteClassificationCoverage() {
        Version current = save(draft()); current = transition(1, current, "submit"); current = transition(2, current, "approve");
        Instant january = Instant.parse("2026-01-01T00:00:00Z"); activate(current, january);
        Version past = save(draft()); past = transition(1, past, "submit"); past = transition(2, past, "approve");
        Version approved = past;
        assertThatThrownBy(() -> service.activateHistorical(3, roles, "B", approved.versionId(),
                new HistoricalActivation(approved.revision(), "重叠", january.minusSeconds(1), january.plusSeconds(1))))
                .hasMessageContaining("重叠");
        jdbc.update("UPDATE biz_carbon_project_version SET configuration_json=? WHERE version_id=?",
                "{\"classificationVersionId\":\"CLASS\",\"energies\":[{\"energyItemCode\":\"WATER\",\"pathway\":\"WATER\",\"capability\":\"RESERVED\"}],\"exclusions\":[]}", past.versionId());
        var start = Instant.parse("2025-01-01T00:00:00Z");
        when(classifications.version(anyLong(), anyCollection(), eq("B"), eq("CLASS"))).thenReturn(
                new com.platform.energy.classification.ClassificationContracts.VersionView("CLASS", "B", 2, 1,
                        "APPROVED", start, january.minusSeconds(1), 1, 2L, null, List.of()));
        assertThatThrownBy(() -> service.activateHistorical(3, roles, "B", approved.versionId(),
                new HistoricalActivation(approved.revision(), "分类范围不足", start, january)))
                .hasMessageContaining("整个历史项目期间");
        assertThat(service.versions(1, roles, "B").getFirst().status()).isEqualTo("APPROVED");
    }

    @Test void revisionWrongAuthorAndCrossBuildingAreRejected() {
        Version v=draft();
        assertThatThrownBy(()->service.save(2,roles,"B",v.versionId(),new Save(v.revision(),new Configuration(null,List.of(),List.of()),"修改"))).hasMessageContaining("本人草稿");
        assertThatThrownBy(()->service.save(1,roles,"B",v.versionId(),new Save(99L,new Configuration(null,List.of(),List.of()),"修改"))).hasMessageContaining("修订");
        jdbc.update("INSERT INTO building VALUES ('OTHER',0)");
        assertThatThrownBy(()->service.save(1,roles,"OTHER",v.versionId(),new Save(0L,new Configuration(null,List.of(),List.of()),"修改"))).hasMessageContaining("不存在");
    }
    @Test void emptyDraftCannotBeSubmittedAndErrorsRollbackActivation() {
        Version empty=draft();assertThatThrownBy(()->transition(1,empty,"submit")).hasMessageContaining("阻塞项");
        Version v=save(empty);v=transition(1,v,"submit");v=transition(2,v,"approve");
        doThrow(new IllegalStateException("audit-dependency-unavailable")).when(rules).insertDependencyChange(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        Version approved=v;assertThatThrownBy(()->activate(approved,Instant.parse("2026-01-01T00:00:00Z"))).hasMessageContaining("unavailable");
        assertThat(service.versions(1,roles,"B").getFirst().status()).isEqualTo("APPROVED");
    }

    @Test void enabledCustomEnergyRequiresApprovedCatalogAndClassificationCoveringActivation() {
        var tree=new com.platform.energy.classification.ClassificationContracts.Configuration("REL",
                List.of(new com.platform.energy.classification.ClassificationContracts.Scheme("U","用途","USAGE","ADDITIVE")),
                List.of(new com.platform.energy.classification.ClassificationContracts.Node("U","N","空调",null)),
                List.of(new com.platform.energy.classification.ClassificationContracts.Binding("U","N","POINT","A",null,"METERING","CONFIRMED","专业确认单")),
                List.of(new com.platform.energy.classification.ClassificationContracts.BoundaryRule("BOUNDARY","MAIN_METER_TOTAL","CONFIRMED","确认单","CUSTOM_ELEC","KWH")));
        var classFrom=Instant.parse("2026-02-01T00:00:00Z");
        when(classifications.version(anyLong(),any(),eq("B"),eq("CLASS"))).thenReturn(
                new com.platform.energy.classification.ClassificationContracts.VersionView("CLASS","B",1,3,"APPROVED",classFrom,null,1,2L,tree,List.of()));
        var item=new com.platform.energy.catalog.api.EnergyCatalogContracts.ItemVersionView("ITEM","CUSTOM_ELEC","ITEM_VERSION",1,"自定义电力","ELECTRICITY",List.of("PURCHASED_ELECTRICITY"),"APPROVED","MANUAL","专业目录",null,null,1,1L,null,2L,null);
        when(catalog.listItems(any())).thenReturn(List.of(item));
        Version v=draft();
        Configuration cfg=new Configuration("CLASS",List.of(new Energy("CUSTOM_ELEC","PURCHASED_ELECTRICITY","ENABLED")),List.of(new Exclusion("CUSTOM_ELEC",List.of("A"),"不纳入边界","确认单")));
        v=service.save(1,roles,"B",v.versionId(),new Save(v.revision(),cfg,"保存"));
        assertThat(service.readiness(1,roles,"B",v.versionId()).ready()).isTrue();
        v=transition(1,v,"submit");v=transition(2,v,"approve");
        Version approved=v;
        assertThatThrownBy(()->activate(approved,Instant.parse("2026-01-01T00:00:00Z"))).hasMessageContaining("分类版本");
        assertThat(activate(approved,classFrom).status()).isEqualTo("ACTIVE");
        when(catalog.listItems(any())).thenReturn(List.of());
        assertThat(service.readiness(1,roles,"B",v.versionId()).issues()).anySatisfy(issue->assertThat(issue.code()).isEqualTo("CONFIGURATION_INVALID"));
    }

    @Test void lostClassificationReturnsBlockingIssueButRevokedPermissionIsNotSwallowed() {
        Version v=draft();
        jdbc.update("UPDATE biz_carbon_project_version SET configuration_json=? WHERE version_id=?",
                "{\"classificationVersionId\":\"MISSING\",\"energies\":[{\"energyItemCode\":\"WATER\",\"pathway\":\"WATER\",\"capability\":\"RESERVED\"}],\"exclusions\":[]}",v.versionId());
        when(classifications.version(1,roles,"B","MISSING")).thenThrow(CarbonErrors.error(404,CarbonErrors.NOT_FOUND,"分类不存在"));
        assertThat(service.readiness(1,roles,"B",v.versionId())).satisfies(result->{
            assertThat(result.ready()).isFalse();
            assertThat(result.issues()).anySatisfy(issue->assertThat(issue.code()).isEqualTo("CLASSIFICATION_UNAVAILABLE"));
        });
        doThrow(CarbonErrors.error(403,CarbonErrors.FORBIDDEN,"权限已撤回")).when(classifications).version(1,roles,"B","MISSING");
        assertThatThrownBy(()->service.readiness(1,roles,"B",v.versionId())).hasMessageContaining("已撤回");
    }
}
