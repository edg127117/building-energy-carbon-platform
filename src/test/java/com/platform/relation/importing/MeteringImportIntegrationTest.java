package com.platform.relation.importing;

import com.platform.framework.exception.BusinessException;
import com.platform.relation.RelationGovernanceService;
import com.platform.system.service.BuildingScopeService;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class MeteringImportIntegrationTest {
    private static final Set<String> ENERGY = Set.of("ENERGY_MANAGER");

    @Autowired private MeteringTemplateService templateService;
    @Autowired private MeteringImportApplicationService importService;
    @Autowired private RelationGovernanceService governanceService;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private BuildingScopeService buildingScopeService;

    @BeforeEach
    void setUp() {
        when(buildingScopeService.canAccess(anyLong(), any(Collection.class), any())).thenReturn(true);
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void downloadsV1TemplateAndImportsReorderedColumnsAtomicallyAndIdempotently() throws Exception {
        byte[] template = templateService.create("V1");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(template))) {
            assertThat(workbook.getSheet("填写说明")).isNotNull();
            assertThat(workbook.getSheet("计量关系")).isNotNull();
            assertThat(workbook.getSheet("填写说明").getRow(0).getCell(1).getStringCellValue())
                    .isEqualTo("templateVersion=V1");
        }

        var draft = governanceService.initialize(101L, ENERGY, "BLD001",
                "excel-init-ok", "Excel 导入测试");
        List<String> reordered = new ArrayList<>(MeteringTemplateService.HEADERS);
        Collections.rotate(reordered, 4);
        MockMultipartFile file = workbook(reordered, List.of(
                assigned("WCR1_PPE", "MAIN", null, "INBOUND", "SYSTEM", "SG001"),
                assigned("WCR1_Pc_PPE", "SUB", "WCR1_PPE", "OUTBOUND", "EQUIPMENT", "WCR1")));

        long structuresBefore = count("biz_meter_structure_version_item");
        var preflight = importService.preflight(101L, ENERGY, "BLD001",
                draft.versionId(), draft.revision(), file);
        assertThat(preflight.errorCount()).isZero();
        assertThat(preflight.passedRows()).isEqualTo(2);
        assertThat(count("biz_meter_structure_version_item")).isEqualTo(structuresBefore);
        assertThat(governanceService.versionDetail(101L, ENERGY, "BLD001", draft.versionId())
                .version().revision()).isZero();

        var imported = importService.confirm(101L, ENERGY, "BLD001", draft.versionId(),
                draft.revision(), "excel-confirm-ok", file);
        assertThat(imported.revision()).isEqualTo(1L);
        assertThat(imported.structureCount()).isEqualTo(2);
        assertThat(imported.assignmentCount()).isEqualTo(2);
        assertThat(imported.boundaryCreatedCount()).isEqualTo(1);

        var replay = importService.confirm(101L, ENERGY, "BLD001", draft.versionId(),
                draft.revision(), "excel-confirm-ok", file);
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(count("biz_meter_structure_version_item")).isEqualTo(2);
        assertThat(count("biz_metering_assignment_version_item")).isEqualTo(2);
    }

    @Test
    void reportsUnsupportedVersionMissingHeaderFormulaAndStructureConflict() throws Exception {
        var draft = governanceService.initialize(101L, ENERGY, "BLD001",
                "excel-init-errors", "Excel 错误测试");
        Map<String, String> v2 = assigned("WCR1_PPE", "MAIN", null,
                "INBOUND", "SYSTEM", "SG001");
        v2.put("模板版本", "V2");
        var unsupported = importService.preflight(101L, ENERGY, "BLD001", draft.versionId(), 0L,
                workbook(MeteringTemplateService.HEADERS, List.of(v2)));
        assertThat(unsupported.issues()).anyMatch(issue ->
                "RELATION_METERING_TEMPLATE_UNSUPPORTED".equals(issue.code())
                        && issue.rowNumber() == 2 && "模板版本".equals(issue.field()));

        List<String> missingHeaders = new ArrayList<>(MeteringTemplateService.HEADERS);
        missingHeaders.remove("证据引用");
        var missing = importService.preflight(101L, ENERGY, "BLD001", draft.versionId(), 0L,
                workbook(missingHeaders, List.of()));
        assertThat(missing.issues()).anyMatch(issue ->
                "RELATION_METERING_IMPORT_HEADER_INVALID".equals(issue.code())
                        && "证据引用".equals(issue.field()));

        var formulaFile = workbook(MeteringTemplateService.HEADERS,
                List.of(assigned("WCR1_PPE", "MAIN", null, "INBOUND", "SYSTEM", "SG001")));
        byte[] formulaBytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(formulaFile.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.getSheet("计量关系").getRow(1)
                    .getCell(MeteringTemplateService.HEADERS.indexOf("证据引用"))
                    .setCellFormula("1+1");
            workbook.write(output);
            formulaBytes = output.toByteArray();
        }
        var formula = importService.preflight(101L, ENERGY, "BLD001", draft.versionId(), 0L,
                file(formulaBytes));
        assertThat(formula.totalRows()).isEqualTo(1);
        assertThat(formula.issues()).anyMatch(issue -> issue.rowNumber() == 2
                && "IMPORT_FORMULA_OR_ERROR_CELL_REJECTED".equals(issue.code()));

        Map<String, String> conflict = assigned("WCR1_PPE", "MAIN", null,
                "OUTBOUND", "EQUIPMENT", "WCR1");
        var conflictResult = importService.preflight(101L, ENERGY, "BLD001", draft.versionId(), 0L,
                workbook(MeteringTemplateService.HEADERS, List.of(
                        assigned("WCR1_PPE", "MAIN", null, "INBOUND", "SYSTEM", "SG001"),
                        conflict)));
        assertThat(conflictResult.issues())
                .anyMatch(issue -> "IMPORT_METER_STRUCTURE_CONFLICT".equals(issue.code()));
    }

    @Test
    void failedConfirmWritesNothingAndPendingExpertImportsOnlyDraft() throws Exception {
        var draft = governanceService.initialize(101L, ENERGY, "BLD001",
                "excel-init-rollback", "Excel 回滚测试");
        Map<String, String> invalid = assigned("WCR1_PPE", "MAIN", null,
                "INBOUND", "SYSTEM", "SG001");
        invalid.put("证据引用", "");
        MockMultipartFile invalidFile = workbook(MeteringTemplateService.HEADERS, List.of(invalid));
        assertThatThrownBy(() -> importService.confirm(101L, ENERGY, "BLD001", draft.versionId(),
                0L, "excel-confirm-invalid", invalidFile)).isInstanceOf(BusinessException.class);
        assertThat(count("biz_meter_structure_version_item")).isZero();
        assertThat(count("biz_metering_assignment_version_item")).isZero();
        assertThat(count("biz_metering_boundary")).isZero();

        Map<String, String> pending = assigned("WCR1_PPE", "UNKNOWN", null,
                "UNKNOWN", "SYSTEM", "SG001");
        pending.put("分配状态", "PENDING_EXPERT");
        pending.put("原因编码", "EXPERT_REQUIRED");
        pending.put("原因说明", "等待能源专家确认");
        pending.put("能源类型", "");
        var pendingFile = workbook(MeteringTemplateService.HEADERS, List.of(pending));
        var pendingResult = importService.preflight(101L, ENERGY, "BLD001",
                draft.versionId(), 0L, pendingFile);
        assertThat(pendingResult.errorCount()).isZero();
        assertThat(pendingResult.warningCount()).isPositive();
        importService.confirm(101L, ENERGY, "BLD001", draft.versionId(),
                0L, "excel-confirm-pending", pendingFile);
        assertThat(governanceService.validate(101L, ENERGY, draft.versionId())
                .pendingExpertCount()).isPositive();
    }

    private static Map<String, String> assigned(
            String meterCode, String role, String parent, String direction,
            String targetType, String targetCode) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("模板版本", "V1");
        row.put("边界编码", "POWER_B1");
        row.put("边界名称", "合成购电边界");
        row.put("能源类型", "ELECTRICITY");
        row.put("表计测点编码", meterCode);
        row.put("表计角色", role);
        row.put("上级表计编码", parent == null ? "" : parent);
        row.put("计量方向", direction);
        row.put("覆盖对象类型", targetType);
        row.put("覆盖对象编码", targetCode);
        row.put("分配状态", "ASSIGNED");
        row.put("原因编码", "");
        row.put("原因说明", "");
        row.put("证据引用", "SYNTHETIC_EVIDENCE");
        row.put("补充说明", "仅用于软件测试");
        return row;
    }

    private static MockMultipartFile workbook(
            List<String> headers, List<Map<String, String>> rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var instruction = workbook.createSheet("填写说明");
            instruction.createRow(0).createCell(1).setCellValue("templateVersion=V1");
            var data = workbook.createSheet("计量关系");
            var header = data.createRow(0);
            for (int column = 0; column < headers.size(); column++) {
                header.createCell(column).setCellValue(headers.get(column));
            }
            for (int index = 0; index < rows.size(); index++) {
                var excelRow = data.createRow(index + 1);
                for (int column = 0; column < headers.size(); column++) {
                    excelRow.createCell(column, CellType.STRING)
                            .setCellValue(rows.get(index).getOrDefault(headers.get(column), ""));
                }
            }
            workbook.write(output);
            return file(output.toByteArray());
        }
    }

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "metering.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void clean() {
        jdbc.update("DELETE FROM biz_relation_validation_issue");
        jdbc.update("DELETE FROM biz_relation_review_request");
        jdbc.update("DELETE FROM biz_relation_audit_log");
        jdbc.update("DELETE FROM biz_meter_structure_version_item");
        jdbc.update("DELETE FROM biz_metering_assignment_version_item");
        jdbc.update("DELETE FROM biz_semantic_relation_version_item");
        jdbc.update("DELETE FROM biz_asset_assignment_version_item");
        jdbc.update("DELETE FROM biz_space_parent_version_item");
        jdbc.update("DELETE FROM biz_relation_node");
        jdbc.update("DELETE FROM biz_metering_boundary");
        jdbc.update("DELETE FROM biz_relation_version");
        jdbc.update("DELETE FROM biz_relation_model");
    }
}
