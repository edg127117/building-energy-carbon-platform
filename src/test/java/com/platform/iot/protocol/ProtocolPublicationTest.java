package com.platform.iot.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Receipt;
import com.platform.audit.sensitive.*;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.protocol.api.ProtocolContracts.*;
import com.platform.iot.protocol.api.ProtocolPublicationContracts.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="audit-governance.allow-self-approval=false")
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class ProtocolPublicationTest {
    @Autowired ProtocolPublicationService service;
    @Autowired ProtocolDraftService drafts;
    @Autowired SensitiveChangeService changes;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    String product;
    @BeforeEach void setup() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test-admin",null,List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))));
        product=UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO biz_device_product(product_id,product_code,product_name,equipment_type_code,expected_profile_code,identity_type,status) VALUES(?,?,?,'WCR','TEST_PUBLICATION','SN','ENABLED')",product,product,"测试发布");
        jdbc.update("INSERT INTO biz_product_point_template(template_point_id,product_id,metric_code,point_name_template,suffix_code,unit,required_flag,status) VALUES(?,?, 'POWER','功率','P','kW',1,1)",UUID.randomUUID().toString().replace("-",""),product);
        jdbc.update("DELETE FROM sys_user_backend_duty WHERE user_id IN (1,2)");
        grant(1,"BACKOFFICE_CHANGE_SUBMITTER");grant(1,"BACKOFFICE_CHANGE_REVIEWER");grant(2,"BACKOFFICE_CHANGE_REVIEWER");
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private void grant(long user,String duty){jdbc.update("INSERT INTO sys_user_backend_duty(assignment_id,user_id,duty_key,status,effective_at,created_by,created_at) VALUES(?,?,?,'ACTIVE',?,1,?)",UUID.randomUUID().toString().replace("-",""),user,duty,Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)),Timestamp.valueOf(LocalDateTime.now()));}
    private Configuration config(String condition){return new Configuration("测试发布",product,"TEST_PUBLICATION","device/raw/publication","SN","/SN","/kind",condition,null,
            List.of(new Mapping("/P","POWER","kW","kW",BigDecimal.ONE,BigDecimal.ZERO,true,true,1)));}
    private VersionView version(String condition){var d=drafts.create(config(condition),1L,ProtocolPublicationService.ADMIN);return service.freeze(d.id(),d.revision(),1,ProtocolPublicationService.ADMIN);}
    private TargetCreated target(){return service.register(new TargetRequest("隔离目标","V1",List.of("device/raw/publication")),1,ProtocolPublicationService.ADMIN);}
    private FrozenCommand command(TargetCreated target,VersionView version,long sequence){return service.prepare(new PublishRequest(target.target().targetId(),sequence,List.of(version.versionId()),UUID.randomUUID().toString()),ProtocolPublicationService.ADMIN);}
    private void approve(FrozenCommand command){var r=changes.createDraft(1,"PUBLISH_PROTOCOL_CONFIGURATION",mapper.valueToTree(command),UUID.randomUUID().toString());changes.submit(1,r.requestId());changes.approve(2,r.requestId(),"独立测试审核");changes.execute(2,r.requestId());}
    @Test void publicationRequiresContactAndApprovalThenExactReceiptAndRollback() {
        var t=target();var v=version("INDOOR");String id=t.target().targetId();
        assertThatThrownBy(()->command(t,v,0)).isInstanceOf(BusinessException.class);
        assertThat(service.pull(id,1,"V1")).isNull();
        var frozen=command(t,v,0);approve(frozen);
        assertThat(service.deployments(id,ProtocolPublicationService.ADMIN).getFirst().status()).isEqualTo("PENDING_SYNC");
        var envelope=service.pull(id,1,"V1");
        assertThat(envelope.sequence()).isEqualTo(1);
        assertThatThrownBy(()->service.receipt(id,new Receipt(1,"wrong","LOADED",null))).isInstanceOf(BusinessException.class);
        service.receipt(id,new Receipt(1,envelope.digest(),"LOADED",null));
        service.receipt(id,new Receipt(1,envelope.digest(),"FAILED","LATE_FAILURE"));
        assertThat(service.deployments(id,ProtocolPublicationService.ADMIN).getFirst().status()).isEqualTo("LOADED");
        approve(command(t,version("OUTDOOR"),1));
        service.receipt(id,new Receipt(1,envelope.digest(),"LOADED",null));
        assertThat(service.deployments(id,ProtocolPublicationService.ADMIN).getFirst().status()).isEqualTo("PENDING_SYNC");
        approve(service.rollback(new RollbackRequest(id,1,2,"rollback"),ProtocolPublicationService.ADMIN));
        assertThat(service.pull(id,1,"V1").sequence()).isEqualTo(3);
        assertThat(service.pull(id,1,"V1").digest()).isEqualTo(envelope.digest());
    }
    @Test void rejectsOverlappingRulesStaleApprovalAndChangedProduct() {
        var t=target();service.pull(t.target().targetId(),1,"V1");var a=version("SAME");var b=version("SAME");
        assertThatThrownBy(()->service.prepare(new PublishRequest(t.target().targetId(),0,List.of(a.versionId(),b.versionId()),"x"),ProtocolPublicationService.ADMIN)).isInstanceOf(BusinessException.class);
        var command=command(t,a,0);approve(command);
        assertThatThrownBy(()->approve(command)).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE biz_product_point_template SET unit='W' WHERE product_id=?",product);
        assertThatThrownBy(()->command(t,a,1)).isInstanceOf(BusinessException.class);
    }
    @Test void preventsSelfApprovalAndPayloadTampering() {
        var t=target();service.pull(t.target().targetId(),1,"V1");var c=command(t,version("A"),0);
        var r=changes.createDraft(1,"PUBLISH_PROTOCOL_CONFIGURATION",mapper.valueToTree(c),"unique");
        assertThat(changes.createDraft(1,"PUBLISH_PROTOCOL_CONFIGURATION",mapper.valueToTree(c),"unique").requestId()).isEqualTo(r.requestId());
        changes.submit(1,r.requestId());
        assertThatThrownBy(()->changes.approve(1,r.requestId(),"self")).isInstanceOf(BusinessException.class);
        var changed=new FrozenCommand(c.targetId(),0,c.digest(),"{}",c.versionIds());
        assertThatThrownBy(()->changes.createDraft(1,"PUBLISH_PROTOCOL_CONFIGURATION",mapper.valueToTree(changed),"tampered")).isInstanceOf(BusinessException.class);
    }
    @Test void authenticatesOnlyScopedKeyOverSecureTransport() throws Exception {
        var t=target();String path="/v1/adapter-configurations/"+t.target().targetId();
        mvc.perform(get(path).secure(true).header("X-Adapter-Key","wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("X-Adapter-Key",t.oneTimeKey())).andExpect(status().isUnauthorized());
        mvc.perform(get(path).secure(true).header("X-Adapter-Key",t.oneTimeKey()).header("X-Adapter-Schema-Version",1).header("X-Adapter-Output-Version","V1")).andExpect(status().isOk());
        var other=target();
        mvc.perform(get("/v1/adapter-configurations/"+other.target().targetId()).secure(true).header("X-Adapter-Key",t.oneTimeKey())).andExpect(status().isUnauthorized());
        assertThat(service.json(service.targets(ProtocolPublicationService.ADMIN))).doesNotContain(t.oneTimeKey(),"keyHash");
    }
    @Test void importsCompleteSnapshotWithoutPublishingOrChangingAdvancedFields() throws Exception {
        var t=target();String targetId=t.target().targetId();service.pull(targetId,1,"V1");
        var original=command(t,version("LEGACY"),0);
        var snapshot=mapper.readTree(original.contentJson());
        String profileId=snapshot.path("profiles").get(0).path("profile").path("profileId").asText();
        var imported=service.importLegacy(new ImportRequest(original.contentJson(),"{\"schemaVersion\":1,\"outputVersion\":\"V1\",\"profiles\":[]}",Map.of(profileId,product)),1,ProtocolPublicationService.ADMIN);
        assertThat(imported).hasSize(1);
        assertThat(service.deployments(targetId,ProtocolPublicationService.ADMIN)).isEmpty();
        var importedCommand=command(t,imported.getFirst(),0);
        assertThat(importedCommand.contentJson()).isEqualTo(original.contentJson());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_protocol_migration_archive",Integer.class)).isGreaterThan(0);
        assertThatThrownBy(()->service.importLegacy(new ImportRequest(original.contentJson(),null,Map.of()),1,ProtocolPublicationService.ADMIN)).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsCapabilityMismatchOutOfScopeAndMissingExistingAlias() {
        var t=target();String targetId=t.target().targetId();
        assertThatThrownBy(()->service.pull(targetId,2,"V1")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.pull(targetId,1,"V2")).isInstanceOf(BusinessException.class);
        service.pull(targetId,1,"V1");var v=version("A");
        var outside=service.register(new TargetRequest("范围测试","V1",List.of("other/topic")),1,ProtocolPublicationService.ADMIN);
        service.pull(outside.target().targetId(),1,"V1");
        assertThatThrownBy(()->command(outside,v,0)).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE biz_equipment SET product_id=? WHERE equip_id='EQUIP_WCR_B1'",product);
        jdbc.update("INSERT INTO biz_device_identity(identity_id,identity_type,identity_value,equip_id,building_id,expected_profile_code,status) VALUES(?,'SN',?,'EQUIP_WCR_B1','BLD001','TEST_PUBLICATION',1)",UUID.randomUUID().toString().replace("-",""),UUID.randomUUID().toString());
        assertThatThrownBy(()->command(t,v,0)).isInstanceOf(BusinessException.class);
    }
}
