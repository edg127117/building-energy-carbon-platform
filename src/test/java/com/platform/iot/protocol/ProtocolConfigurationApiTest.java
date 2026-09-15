package com.platform.iot.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProtocolConfigurationApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    String token;
    String product;
    static final String BASE="/v1/protocol-configurations";
    static final String SAMPLE="""
        {"SN":"test-only-meter","param":{"ID255":{"M":"1039","1#":{"U":221.401,"I":11.246,"P":2.492,"Pf":1,"F":50.04,"EPP":536.86,"EPN":356.15}}}}
        """;
    static final String[] FIELDS={"U","I","P","Pf","F","EPP","EPN"};
    static final String[] CODES={"VOLTAGE","CURRENT","POWER","POWER_FACTOR","FREQUENCY","POSITIVE_ENERGY","NEGATIVE_ENERGY"};
    static final String[] UNITS={"V","A","kW","1","Hz","kWh","kWh"};

    @BeforeEach
    void setup() throws Exception {
        token=mapper.readTree(mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("token").asText();
        product=UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO biz_device_product(product_id,product_code,product_name,equipment_type_code,expected_profile_code,identity_type,status) VALUES (?,?,?,'WCR','TEST_METER','SN','DRAFT')",product,product,"测试产品");
        for(int i=0;i<FIELDS.length;i++) jdbc.update("INSERT INTO biz_product_point_template(template_point_id,product_id,metric_code,point_name_template,suffix_code,unit,required_flag,status) VALUES(?,?,?,?,?,?,1,1)",
                UUID.randomUUID().toString().replace("-",""),product,CODES[i],CODES[i],FIELDS[i],UNITS[i]);
    }
    ObjectNode configuration() {
        var c=mapper.createObjectNode().put("name","七点测试协议").put("productId",product).put("profileCode","TEST_METER")
                .put("sourceTopic","device/raw/test/up").put("identityType","SN").put("identityPath","/SN")
                .put("discriminatorPath","/param/ID255/M").put("discriminatorValue","1039");
        var mappings=c.putArray("mappings");
        for(int i=0;i<FIELDS.length;i++) mappings.addObject().put("sourcePath","/param/ID255/1#/"+FIELDS[i])
                .put("metricCode",CODES[i]).put("sourceUnit",UNITS[i]).put("targetUnit",UNITS[i])
                .put("scale","1").put("offset","0").put("required",true).put("enabled",true).put("sortOrder",i);
        return c;
    }
    ObjectNode preview(ObjectNode config,String payload) {
        var body=mapper.createObjectNode().put("samplePayload",payload).put("receivedTime",1789380000000L);
        body.set("configuration",config); return body;
    }
    JsonNode postOk(String suffix,JsonNode body) throws Exception {
        return mapper.readTree(mvc.perform(post(BASE+suffix).header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }
    @Test
    void savesOnlyDraftAndRejectsStaleRevision() throws Exception {
        var config=configuration();
        var created=postOk("",config); String id=created.path("id").asText();
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.path("configuration").path("mappings").get(0).path("scale").isTextual()).isTrue();
        var update=mapper.createObjectNode().put("revision",1);update.set("configuration",config.put("name","修改后"));
        mvc.perform(put(BASE+"/"+id).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(update.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2));
        mvc.perform(put(BASE+"/"+id).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(update.toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PROTOCOL_REVISION_CONFLICT"));
        mvc.perform(get(BASE+"/"+id).header("Authorization","Bearer "+token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configuration.name").value("修改后"));
        mvc.perform(get(BASE).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());
        String stored=jdbc.queryForObject("SELECT configuration_json FROM biz_protocol_draft WHERE draft_id=?",String.class,id);
        assertThat(stored).doesNotContain("test-only-meter","samplePayload");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_onboarding_audit_log WHERE object_id=?",Integer.class,id)).isEqualTo(2);
    }
    @Test
    void previewsSevenValuesWithoutCreatingDraftOrDevice() throws Exception {
        long drafts=jdbc.queryForObject("SELECT COUNT(*) FROM biz_protocol_draft",Long.class);
        long devices=jdbc.queryForObject("SELECT COUNT(*) FROM biz_pending_device",Long.class);
        var result=postOk("/preview",preview(configuration(),SAMPLE));
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("metrics").size()).isEqualTo(7);
        assertThat(result.path("metrics").get(0).path("value").asText()).isEqualTo("221.401");
        assertThat(result.path("metrics").get(5).path("value").asText()).isEqualTo("536.86");
        assertThat(result.path("timeSource").asText()).isEqualTo("ADAPTER_RECEIVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_protocol_draft",Long.class)).isEqualTo(drafts);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_pending_device",Long.class)).isEqualTo(devices);
    }
    @Test
    void rejectsMismatchedRuleAndRequiredOrNonNumericValuesWithoutHalfResults() throws Exception {
        var missed=postOk("/preview",preview(configuration(),SAMPLE.replace("1039","OTHER")));
        assertThat(missed.path("errors").get(0).path("code").asText()).isEqualTo("PROFILE_NOT_MATCHED");
        for(String payload:new String[]{SAMPLE.replace("\"U\":221.401,",""),SAMPLE.replace("221.401","\"221.401\"")}) {
            var result=postOk("/preview",preview(configuration(),payload));
            assertThat(result.path("success").asBoolean()).isFalse();
            assertThat(result.path("metrics").isEmpty()).isTrue();
            assertThat(result.path("errors").get(0).path("path").asText()).isEqualTo("/param/ID255/1#/U");
        }
    }
    @Test
    void optionalMissingDoesNotBecomeZeroAndProductUnitCannotBeChanged() throws Exception {
        jdbc.update("UPDATE biz_product_point_template SET required_flag=0 WHERE product_id=? AND metric_code='FREQUENCY'",product);
        var c=configuration(); ((ObjectNode)c.path("mappings").get(4)).put("required",false);
        var result=postOk("/preview",preview(c,SAMPLE.replace("\"F\":50.04,","")));
        assertThat(result.path("metrics").get(4).path("status").asText()).isEqualTo("MISSING_OPTIONAL");
        assertThat(result.path("metrics").get(4).path("value").isNull()).isTrue();
        ((ObjectNode)c.path("mappings").get(0)).put("targetUnit","kW");
        mvc.perform(post(BASE).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(c.toString()))
                .andExpect(status().isBadRequest());
    }
    @Test
    void escapesPointersAndRejectsDuplicateKeysOversizeAndDeepSamples() throws Exception {
        var result=postOk("/inspect",mapper.createObjectNode().put("samplePayload","{\"a/b~c\":[1,null]}"));
        assertThat(result.path("fields").get(0).path("path").asText()).isEqualTo("/a~1b~0c/0");
        for(String payload:new String[]{"{\"U\":1,\"U\":2}","{} {}","{\"x\":\""+"中".repeat(23000)+"\"}",
                "{\"x\":".repeat(22)+"1"+"}".repeat(22),"{\"x\":["+"1,".repeat(1024)+"1]}"})
            mvc.perform(post(BASE+"/inspect").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.createObjectNode().put("samplePayload",payload).toString())).andExpect(status().isBadRequest());
    }
    @Test
    void anonymousCannotAccessAndInvalidMappingNeverPersists() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"protocol_owner\",\"password\":\"123456\",\"nickname\":\"测试业主\"}"))
                .andExpect(status().isOk());
        String owner=mapper.readTree(mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"protocol_owner\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("token").asText();
        mvc.perform(get(BASE).header("Authorization","Bearer "+owner)).andExpect(status().isForbidden());
        mvc.perform(post(BASE+"/preview").header("Authorization","Bearer "+owner).contentType(MediaType.APPLICATION_JSON)
                .content(preview(configuration(),SAMPLE).toString())).andExpect(status().isForbidden());
        var c=configuration();((ObjectNode)c.path("mappings").get(0)).put("sourcePath","/bad~2escape");
        mvc.perform(post(BASE).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(c.toString()))
                .andExpect(status().isBadRequest());
        var limiter=new ProtocolPreviewLimiter(1);
        limiter.acquire(1L);
        assertThatThrownBy(()->limiter.acquire(1L))
                .isInstanceOf(com.platform.framework.exception.BusinessException.class);
    }

    @Test
    void preservesExactDecimalConversionAndRejectsInvalidDeviceTime() throws Exception {
        var c=configuration();
        ((ObjectNode)c.path("mappings").get(0)).put("scale","0.001").put("offset","0.1");
        var result=postOk("/preview",preview(c,SAMPLE.replace("221.401","221.4011234567890123")));
        assertThat(result.path("metrics").get(0).path("value").asText()).isEqualTo("0.3214011234567890123");
        c.put("timestampPath","/time");
        result=postOk("/preview",preview(c,SAMPLE.replace("{\"SN\"","{\"time\":1789380000,\"SN\"")));
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("errors").get(0).path("code").asText()).isEqualTo("INVALID_TIMESTAMP");
    }
}
