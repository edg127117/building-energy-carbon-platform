package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import com.platform.security.JwtService;
import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.energy.classification.ClassificationContracts.Configuration;
import com.platform.energy.classification.ClassificationContracts.VersionView;
import com.platform.energy.classification.ClassificationService;
import com.platform.energy.conversion.EnergyConversionService;
import com.platform.energy.conversion.api.EnergyConversionContracts.CreateFormulaVersionRequest;
import com.platform.energy.conversion.api.EnergyConversionContracts.CreateParameterVersionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static com.platform.carbon.CarbonModels.*;
import static com.platform.carbon.JointFlowContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 本地隔离回放原始证据；新映射的APPROVED是测试替身，不表示运行实例已审批或应用。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Tag("isolated-acceptance")
@EnabledIfSystemProperty(named="jointFlow.replay",matches=".+")
class JointFlowReplayAcceptanceTest {
    record EvidenceRow(String itemId,String json,String hash) { }
    record Bundle(String buildingId,String month,CalculationBatch batch,List<StoredCalculationItem> items,
                  List<SummaryMetric> summaries,List<CalculationFailure> failures,String sharedEvidence,List<EvidenceRow> evidenceRows) { }
    @Autowired ObjectMapper mapper;
    @Autowired JointFlowService service;
    @Autowired JointFlowConfigurationService configurations;
    @Autowired EnergyCatalogService catalog;
    @Autowired EnergyConversionService conversion;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockBean TokenCacheService tokens;
    @MockBean CarbonCalculationRepository repository;
    @MockBean ClassificationService classifications;
    private static final long USER=101L;
    private static final Set<String> ROLES=Set.of("PLATFORM_ADMIN");

    @Test void replaysOriginalCompactedEvidenceThroughRealRuleEngineWithoutChangingOldReport() throws Exception {
        byte[] bytes=Files.readAllBytes(Path.of(System.getProperty("jointFlow.replay")));
        String expected=System.getProperty("jointFlow.replaySha256");
        assertThat(expected).as("回放包必须有交接SHA256").isNotBlank();
        assertThat(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(expected);
        Bundle bundle=mapper.readValue(bytes,Bundle.class);
        var configRoot=mapper.readTree(Files.readString(Path.of(System.getProperty("jointFlow.mapping"))));
        assertThat(configRoot.path("state").asText()).isEqualTo("PREPARED_NOT_APPLIED");
        Configuration config=mapper.treeToValue(configRoot.path("configuration"),Configuration.class);

        var detail=new CalculationDetail(bundle.batch(),bundle.items(),bundle.failures(),bundle.summaries());
        when(repository.findJointFlowMonth(bundle.buildingId(),bundle.batch().periodStart(),bundle.batch().periodEnd())).thenReturn(List.of(bundle.batch()));
        when(repository.detail(bundle.batch().batchId())).thenReturn(detail);
        when(repository.sharedEvidence(bundle.batch().batchId())).thenReturn(bundle.sharedEvidence());
        when(repository.listEvidence(bundle.batch().batchId())).thenReturn(bundle.evidenceRows().stream()
                .map(e->new CarbonCalculationRepository.StoredEvidence(e.itemId(),e.json(),e.hash())).toList());
        var calculations=mock(CarbonCalculationService.class);
        when(calculations.detail(USER,ROLES,bundle.batch().batchId())).thenReturn(detail);
        var oldReports=new CarbonClassificationReportService(calculations,repository);
        String before=mapper.writeValueAsString(oldReports.report(USER,ROLES,bundle.batch().batchId()));

        // 只在H2测试事务中经现有治理服务建立同系数规则；不使用运行库凭据或改动运行参数。
        for(String duty:List.of("ENERGY_CATALOG_REVIEW","ENERGY_RULE_MAINTAIN","ENERGY_RULE_REVIEW")) {
            jdbc.update("INSERT INTO sys_user_backend_duty(assignment_id,user_id,duty_key,status,effective_at,created_by) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP,1)","REPLAY_"+duty,USER,duty);
        }
        var approval=new com.platform.energy.catalog.api.EnergyCatalogContracts.ApproveRequest(0,"隔离回放审核");
        catalog.approveItem(USER,ROLES,"EIV_ELECTRICITY_1",approval);
        for(String unit:List.of("EUV_MJ_1","EUV_GJ_1","EUV_KWH_1","EUV_KGCE_1","EUV_TCE_1")) catalog.approveUnit(USER,ROLES,unit,approval);
        var at=LocalDateTime.ofInstant(bundle.batch().periodStart(),ZoneId.of("Asia/Shanghai"));
        var ruleApproval=new com.platform.energy.conversion.api.EnergyConversionContracts.ApproveRequest(0,"隔离回放审核");
        var formula=conversion.createFormulaVersion(USER,ROLES,new CreateFormulaVersionRequest("REPLAY_DIRECT","DIRECT_TCE_FACTOR",
                "CALORIFIC_EQUIVALENT","DIRECT_TCE_FACTOR_V1","KWH","TCE","TCE_PER_INPUT_UNIT","MANUAL","交接正式规则的本地同算法夹具",at,null));
        conversion.approveFormula(USER,ROLES,formula.versionId(),ruleApproval);
        var parameter=conversion.createParameterVersion(USER,ROLES,new CreateParameterVersionRequest("REPLAY_PARAMETER","ELECTRICITY",formula.versionId(),
                new BigDecimal("0.0001229"),"TCE_PER_INPUT_UNIT",null,"PURCHASED_ELECTRICITY","GLOBAL","PRODUCTION","MANUAL","隔离回放规则夹具",at,null));
        conversion.approveParameter(USER,ROLES,parameter.versionId(),ruleApproval);
        jdbc.update("INSERT INTO sys_user_backend_duty(assignment_id,user_id,duty_key,status,effective_at,created_by) VALUES ('REPLAY_REVIEWER',102,'ENERGY_RULE_REVIEW','ACTIVE',CURRENT_TIMESTAMP,1)");
        var floorPath=Path.of(System.getProperty("jointFlow.mapping")).getParent().resolve("历史楼层引用对照.json");
        var floorRoot=mapper.readTree(Files.readString(floorPath));
        var refs=new ArrayList<JointFlowConfigurationContracts.FloorReference>();
        floorRoot.path("floorReferences").forEach(ref->refs.add(new JointFlowConfigurationContracts.FloorReference(ref.path("relationVersionId").asText(),ref.path("schemeCode").asText(),ref.path("nodeCode").asText(),ref.path("spaceId").asText())));
        var dimensions=List.of(new JointFlowConfigurationContracts.Dimension("USAGE","业务用途","MAPPED_SCHEME","CXG_USE",null,List.of()),
            new JointFlowConfigurationContracts.Dimension("FLOOR","楼层","FROZEN_SCHEME","CXG_SCOPE","FLOOR",List.of()));
        var display=new JointFlowConfigurationContracts.Configuration(1,bundle.batch().periodStart(),bundle.batch().periodEnd(),List.of(config.relationVersionId()),dimensions,List.of(config),
            List.of(new Conversion("ELECTRICITY","KWH",parameter.versionId())),"交接原始包及独立六类用途证据",refs);
        var mapping=approved(bundle.buildingId(),display);
        int audits=jdbc.queryForObject("SELECT COUNT(*) FROM sys_security_audit_event",Integer.class);

        var request=new Request(bundle.buildingId(),"MONTH",bundle.month(),null,mapping.versionId());
        var result=service.query(USER,ROLES,request);
        assertThat(result.state()).isEqualTo("READY");assertThat(result.classificationBasis()).isEqualTo("CONFIGURED_VIEW");
        assertThat(bundle.items()).hasSize(220);
        assertThat(result.carbon().nodes().stream().filter(n->n.depth()==0)).hasSize(6);
        assertThat(result.carbon().nodes().stream().filter(n->n.depth()==1)).hasSize(8);
        assertThat(result.carbon().total()).isEqualByComparingTo("23.9463373614");
        assertThat(result.sources().getFirst().publishedCarbonTons()).isEqualByComparingTo("23.946337");
        assertThat(result.sources().getFirst().sourceNature()).isEqualTo("DECLARED_TEST_SOURCE");
        assertThat(result.energy().total()).isEqualByComparingTo("5.0506347378");
        assertThat(result.carbon().unallocated()).isZero();assertThat(result.energy().unallocated()).isZero();
        for(Graph graph:List.of(result.carbon(),result.energy())) {
            for(int i=0;i<3;i++) { final int depth=i;
                assertThat(graph.nodes().stream().filter(n->n.depth()==depth).map(Node::value).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(graph.total());
            }
            for(int i=0;i<2;i++) { final int depth=i;
                Set<String> ids=new HashSet<>();graph.nodes().stream().filter(n->n.depth()==depth).forEach(n->ids.add(n.id()));
                assertThat(graph.links().stream().filter(l->ids.contains(l.source())).map(Link::value).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(graph.total());
            }
        }
        assertThat(mapper.writeValueAsString(oldReports.report(USER,ROLES,bundle.batch().batchId()))).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_security_audit_event",Integer.class)).isEqualTo(audits);
        assertThat(mockingDetails(repository).getInvocations()).allSatisfy(invocation->
                assertThat(invocation.getMethod().getName()).isIn("findJointFlowMonth","detail","sharedEvidence","listEvidence"));
        when(tokens.validateActiveToken(anyLong(),anyString())).thenReturn(TokenValidationResult.ACTIVE);

        String token=jwt.generateToken(1L,"isolated-replay-reader",List.of("PLATFORM_ADMIN"),1L);
        var http=mvc.perform(post("/v1/carbon-management/joint-flow/query").contentType("application/json")
                .content(mapper.writeValueAsString(request)).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.carbon.total").isString())
                .andExpect(jsonPath("$.data.energy.state").value("READY")).andReturn();
        assertThat(new BigDecimal(mapper.readTree(http.getResponse().getContentAsString()).path("data").path("carbon").path("total").asText()))
                .isEqualByComparingTo(result.carbon().total());
        // 以独立十进制tuple对照核查不同层数与顺序，不让组图代码自行生成期望值。
        var oracle=mapper.readTree(Files.readString(Path.of(System.getProperty("jointFlow.mapping")).getParent().resolve("配置化能碳流离线对照值.json")));
        var cases=new ArrayList<Object>();
        for(var sample:oracle.path("cases")) {
            var ds=new ArrayList<JointFlowConfigurationContracts.Dimension>();
            for(var key:sample.path("dimensionOrder")) ds.add(switch(key.asText()) {
                case "ENERGY_TYPE"->new JointFlowConfigurationContracts.Dimension("ENERGY_TYPE","能源类型","ENERGY_ITEM",null,null,List.of());
                case "USAGE"->dimensions.get(0);
                case "FLOOR"->dimensions.get(1);
                case "DEVICE_BREAKDOWN"->new JointFlowConfigurationContracts.Dimension("DEVICE_BREAKDOWN","设备分项","MAPPED_SCHEME","DEVICE",null,List.of());
                default->throw new AssertionError("未知对照维度");
            });
            var schemes=new ArrayList<>(config.schemes());schemes.add(new com.platform.energy.classification.ClassificationContracts.Scheme("DEVICE","明确空调设备分组","CIRCUIT","ADDITIVE"));
            var nodes=new ArrayList<>(config.nodes());nodes.add(new com.platform.energy.classification.ClassificationContracts.Node("DEVICE","AC","空调",null));
            var bindings=new ArrayList<>(config.bindings());config.bindings().stream().filter(b->"CXG_USE".equals(b.schemeCode())&&"AC".equals(b.nodeCode())).forEach(b->bindings.add(new com.platform.energy.classification.ClassificationContracts.Binding("DEVICE","AC",b.objectType(),b.objectId(),b.meterPointId(),b.semantics(),b.status(),b.evidence())));
            var prepared=new Configuration(config.relationVersionId(),schemes,nodes,bindings,config.boundaryRules());
            var c=new JointFlowConfigurationContracts.Configuration(1,display.startInclusive(),display.endExclusive(),display.relationVersionIds(),ds,List.of(prepared),display.conversions(),display.sourceReference(),
                    ds.stream().anyMatch(d->"FLOOR".equals(d.key()))?refs:List.of());
            var v=approved(bundle.buildingId(),c);var actual=service.query(USER,ROLES,new Request(bundle.buildingId(),"MONTH",bundle.month(),null,v.versionId()));
            assertThat(actual.state()).isEqualTo(sample.path("expectedState").asText());
            assertThat(actual.coverage().totalAtoms()).isEqualTo(220);
            for(var entry:Map.of("tCO2e",actual.carbon(),"tce",actual.energy()).entrySet()) {
                Graph g=entry.getValue();assertThat(g.total()).isEqualByComparingTo(sample.path("total").path(entry.getKey()).asText());
                for(int depth=0;depth<=ds.size();depth++) {
                    int d=depth;assertThat(g.nodes().stream().filter(n->n.depth()==d).map(Node::value).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(g.total());
                }
                // 从独立完整tuple合计各相邻边，包括未分配，逐边比较而非只比合计。
                Map<String,BigDecimal> expectedLinks=new TreeMap<>();
                for(var cell:sample.path("cells"))for(int d=0;d<ds.size();d++) {
                    String from=cell.path("keys").get(d).asText(),to=d+1==ds.size()?"TOTAL":cell.path("keys").get(d+1).asText();
                    expectedLinks.merge(d+"/"+from+"/"+to,new BigDecimal(cell.path("values").path(entry.getKey()).asText()),BigDecimal::add);
                }
                Map<String,Node> byId=new HashMap<>();g.nodes().forEach(n->byId.put(n.id(),n));
                Map<String,BigDecimal> actualLinks=new TreeMap<>();g.links().forEach(l->{var from=byId.get(l.source());var to=byId.get(l.target());actualLinks.put(from.depth()+"/"+from.code()+"/"+to.code(),l.value());});
                assertThat(actualLinks.keySet()).isEqualTo(expectedLinks.keySet());
                expectedLinks.forEach((key,value)->assertThat(actualLinks.get(key)).as(key).isEqualByComparingTo(value));
            }
            cases.add(actual);
        }
        String casesOutput=System.getProperty("jointFlow.casesOutput");if(casesOutput!=null)Files.writeString(Path.of(casesOutput),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(cases));
        String output=System.getProperty("jointFlow.output");
        if(output!=null)Files.writeString(Path.of(output),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
    private JointFlowConfigurationContracts.Version approved(String building,JointFlowConfigurationContracts.Configuration c) {
        var d=configurations.create(USER,ROLES,building,new JointFlowConfigurationContracts.Create("隔离回放创建",null));
        d=configurations.save(USER,ROLES,building,d.versionId(),new JointFlowConfigurationContracts.Save(d.revision(),c,"隔离回放配置"));
        var preview=service.preview(USER,ROLES,new Request(building,"MONTH","2026-09",null,d.versionId()));
        assertThat(preview.classificationBasis()).isEqualTo("DRAFT_PREVIEW");
        String draftId=d.versionId();
        assertThatThrownBy(()->service.query(USER,ROLES,new Request(building,"MONTH","2026-09",null,draftId))).hasMessageContaining("已批准");
        d=configurations.transition(USER,ROLES,building,d.versionId(),new JointFlowConfigurationContracts.Transition(d.revision(),"提交隔离审核"),"submit");
        return configurations.transition(102,ROLES,building,d.versionId(),new JointFlowConfigurationContracts.Transition(d.revision(),"独立隔离审核"),"approve");
    }

}
