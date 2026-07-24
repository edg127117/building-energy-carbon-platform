package com.platform.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.generator.mapper.GenColumnMapper;
import com.platform.generator.mapper.GenTableMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 生成器 HTTP 验收测试，重点验证 JWT/四角色边界以及预览、下载的完整调用链。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GeneratorControllerFlowTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private GenColumnMapper columnMapper;
    @Autowired private GenTableMapper tableMapper;

    /** 避免测试之间共享已导入配置，业务表和原有权限数据保持不变。 */
    @BeforeEach
    void cleanGeneratorConfigs() {
        columnMapper.delete(null);
        tableMapper.delete(null);
    }

    /** 未登录和普通角色不能访问，平台管理员可以完成发现、导入、预览和 ZIP 下载。 */
    @Test
    void should_allow_only_platform_admin_to_preview_and_download_generated_backend() throws Exception {
        mockMvc.perform(get("/system/generator/tables"))
                .andExpect(status().isUnauthorized());

        String ownerName = "generator_owner_" + System.nanoTime();
        register(ownerName);
        String ownerToken = login(ownerName, "123456");
        mockMvc.perform(get("/system/generator/tables").header(auth(), bearer(ownerToken)))
                .andExpect(status().isForbidden());

        String adminToken = login("admin", "123456");
        MvcResult tables = mockMvc.perform(get("/system/generator/tables")
                        .header(auth(), bearer(adminToken)))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(tables).path("data").toString()).contains("biz_equipment");

        MvcResult imported = mockMvc.perform(post("/system/generator/import")
                        .header(auth(), bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tableName":"biz_equipment","moduleName":"hvacgenerated",
                                 "businessName":"equipmentGenerated","className":"GeneratedEquipment",
                                 "packageName":"com.platform"}
                                """))
                .andExpect(status().isOk()).andReturn();
        long id = json(imported).path("data").path("id").asLong();
        assertThat(id).isPositive();

        MvcResult preview = mockMvc.perform(post("/system/generator/{id}/preview", id)
                        .header(auth(), bearer(adminToken)))
                .andExpect(status().isOk()).andReturn();
        JsonNode generatedFiles = json(preview).path("data");
        assertThat(generatedFiles.size()).isEqualTo(6);
        assertThat(generatedFiles.toString()).contains("GeneratedEquipmentController.java");

        MvcResult download = mockMvc.perform(post("/system/generator/{id}/download", id)
                        .header(auth(), bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("GeneratedEquipment-backend.zip")))
                .andReturn();
        assertThat(zipEntries(download.getResponse().getContentAsByteArray())).hasSize(6)
                .contains("README.md")
                .anyMatch(name -> name.endsWith("GeneratedEquipmentController.java"));
    }

    /** 从响应 ZIP 中读取条目名，用于确认下载内容完整且路径可解析。 */
    private Set<String> zipEntries(byte[] bytes) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private void register(String username) throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"123456","nickname":"生成器越权测试"}
                                """.formatted(username)))
                .andExpect(status().isOk());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk()).andReturn();
        String token = json(result).path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String auth() { return "Authorization"; }
    private String bearer(String token) { return "Bearer " + token; }
}
