package com.platform.iot.deviceparameter;

import com.platform.iot.deviceparameter.DeviceParameterModels.Definition;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.ApplicabilityRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.DefinitionRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.UnitRequest;
import com.platform.iot.deviceparameter.importing.DeviceParameterExcelImportService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DeviceParameterExcelImportIntegrationTest {
    private static final long USER_ID = 1L;
    private static final List<String> ADMIN = List.of("PLATFORM_ADMIN");

    @Autowired private DeviceParameterCatalogService catalogService;
    @Autowired private DeviceParameterCandidateService candidateService;
    @Autowired private DeviceParameterExcelImportService importService;

    @Test
    void validatesThenExplicitlyConfirmsWholeBatch() throws Exception {
        prepareCatalog();
        MockMultipartFile file = workbook(false);

        var validated = importService.validate(USER_ID, ADMIN, "BLD001", file);

        assertThat(validated.status()).isEqualTo("VALIDATED");
        assertThat(candidateService.listCandidates(USER_ID, ADMIN,
                "EQUIP_WCR_B1", false)).isEmpty();

        var imported = importService.confirm(USER_ID, ADMIN, validated.importBatchId());

        assertThat(imported.status()).isEqualTo("IMPORTED");
        assertThat(candidateService.listCandidates(USER_ID, ADMIN,
                "EQUIP_WCR_B1", false)).singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.sourceType()).isEqualTo(
                            DeviceParameterModels.SourceType.EXCEL);
                    assertThat(candidate.normalizedValue()).isEqualByComparingTo("60");
                });
    }

    @Test
    void rejectsFormulaCellWithoutCreatingCandidates() throws Exception {
        prepareCatalog();

        var rejected = importService.validate(USER_ID, ADMIN, "BLD001", workbook(true));

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.rows()).singleElement()
                .satisfies(row -> assertThat(row.errorCode())
                        .isEqualTo("IMPORT_FORMULA_CELL_REJECTED"));
        assertThat(candidateService.listCandidates(USER_ID, ADMIN,
                "EQUIP_WCR_B1", false)).isEmpty();
    }

    private void prepareCatalog() {
        catalogService.createUnit(USER_ID, ADMIN,
                new UnitRequest("KW", "POWER", "kW", "evidence://unit", -1));
        catalogService.updateUnit(USER_ID, ADMIN, "KW",
                new UnitRequest("KW", "POWER", "kW", "evidence://unit", 0), "ENABLED");
        Definition definition = catalogService.createDefinition(USER_ID, ADMIN,
                new DefinitionRequest("RATED_POWER_TEST", "测试额定功率",
                        "仅用于自动化验证的合成标准参数", "POWER", "KW",
                        2, 1, "evidence://definition", -1));
        definition = catalogService.updateDefinition(USER_ID, ADMIN, definition.definitionId(),
                new DefinitionRequest(definition.parameterCode(), definition.parameterName(),
                        definition.businessDefinition(), definition.quantityKind(),
                        definition.standardUnit(), definition.storageScale(), definition.displayScale(),
                        definition.evidenceReference(), 0), "ENABLED");
        catalogService.saveApplicability(USER_ID, ADMIN,
                new ApplicabilityRequest("WCR", definition.definitionId(), true, false,
                        BigDecimal.ZERO, new BigDecimal("1000"), BigDecimal.ZERO,
                        new BigDecimal("1000"), BigDecimal.ZERO,
                        "evidence://applicability", -1), "ENABLED");
    }

    private static MockMultipartFile workbook(boolean formula) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("parameters");
            var header = sheet.createRow(0);
            List<String> headers = List.of("equipmentCode", "parameterCode", "value",
                    "unit", "observedAt", "sourceReference");
            for (int index = 0; index < headers.size(); index++) {
                header.createCell(index).setCellValue(headers.get(index));
            }
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("WCR1");
            row.createCell(1).setCellValue("RATED_POWER_TEST");
            if (formula) {
                row.createCell(2).setCellFormula("30+30");
            } else {
                row.createCell(2).setCellValue(60);
            }
            row.createCell(3).setCellValue("KW");
            row.createCell(4).setCellValue("2026-08-01T00:00:00");
            row.createCell(5).setCellValue("evidence://excel-row");
            workbook.write(output);
            return new MockMultipartFile("file", "parameters.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    output.toByteArray());
        }
    }
}
