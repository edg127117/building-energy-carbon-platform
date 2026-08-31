package com.platform.relation.importing;

import com.platform.relation.RelationErrors;
import com.platform.relation.api.RelationContracts.MeteringImportIssue;
import lombok.RequiredArgsConstructor;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.platform.relation.importing.MeteringImportModels.*;

@Component
@RequiredArgsConstructor
/** V1 适配器只解析固定输入契约；对象、层级和分配规则由领域校验服务执行。 */
public class MeteringImportV1Parser {
    private final MeteringImportProperties properties;

    public ParsedWorkbook parse(MultipartFile file) {
        byte[] bytes = readFile(file);
        try (OPCPackage pack = OPCPackage.open(new ByteArrayInputStream(bytes));
             XSSFWorkbook workbook = new XSSFWorkbook(pack)) {
            rejectUnsafePackage(pack, workbook);
            List<MeteringImportIssue> issues = new ArrayList<>();
            Sheet instructions = workbook.getSheet(MeteringTemplateService.INSTRUCTION_SHEET);
            Sheet data = workbook.getSheet(MeteringTemplateService.DATA_SHEET);
            if (instructions == null || data == null) {
                issues.add(error("工作簿", 0, "sheet", "IMPORT_REQUIRED_SHEET_MISSING",
                        "必须包含“填写说明”和“计量关系”工作表"));
                return new ParsedWorkbook(null, 0, List.of(), List.copyOf(issues));
            }
            String declaredVersion = instructionVersion(instructions);
            Map<String, Integer> columns = headerColumns(data.getRow(0), issues);
            if (!issues.isEmpty()) {
                return new ParsedWorkbook(declaredVersion, countDataRows(data),
                        List.of(), List.copyOf(issues));
            }
            if (data.getLastRowNum() > properties.getMaxRows()) {
                issues.add(error(MeteringTemplateService.DATA_SHEET, 0, "file",
                        "IMPORT_ROW_LIMIT_EXCEEDED", "数据行数超过平台配置上限"));
                return new ParsedWorkbook(declaredVersion, data.getLastRowNum(),
                        List.of(), List.copyOf(issues));
            }
            List<ParsedRow> rows = new ArrayList<>();
            int totalRows = 0;
            for (int index = 1; index <= data.getLastRowNum(); index++) {
                Row row = data.getRow(index);
                if (row == null || empty(row, columns.values())) continue;
                totalRows++;
                int rowNumber = index + 1;
                if (containsFormulaOrError(row)) {
                    issues.add(error(MeteringTemplateService.DATA_SHEET, rowNumber, "row",
                            "IMPORT_FORMULA_OR_ERROR_CELL_REJECTED", "不接受公式或错误单元格"));
                    continue;
                }
                ParsedRow parsed = parseRow(rowNumber, row, columns);
                if (tooLong(parsed)) {
                    issues.add(error(MeteringTemplateService.DATA_SHEET, rowNumber, "row",
                            "IMPORT_TEXT_LIMIT_EXCEEDED", "单元格文本超过平台配置上限"));
                    continue;
                }
                rows.add(parsed);
            }
            String version = rows.isEmpty() ? declaredVersion : rows.getFirst().templateVersion();
            if (!MeteringTemplateService.VERSION.equalsIgnoreCase(version)) {
                issues.add(error(MeteringTemplateService.DATA_SHEET,
                        rows.isEmpty() ? 0 : rows.getFirst().rowNumber(), "模板版本",
                        RelationErrors.IMPORT_TEMPLATE_UNSUPPORTED, "仅支持平台标准模板 V1"));
            }
            for (ParsedRow row : rows) {
                if (!MeteringTemplateService.VERSION.equalsIgnoreCase(row.templateVersion())) {
                    issues.add(error(MeteringTemplateService.DATA_SHEET, row.rowNumber(), "模板版本",
                            RelationErrors.IMPORT_TEMPLATE_UNSUPPORTED, "同一工作簿只能使用 V1 模板版本"));
                }
            }
            return new ParsedWorkbook(version, totalRows, List.copyOf(rows), List.copyOf(issues));
        } catch (org.apache.poi.openxml4j.exceptions.OLE2NotOfficeXmlFileException exception) {
            throw RelationErrors.error(400, RelationErrors.IMPORT_REJECTED,
                    "只接受无宏、未加密的 .xlsx 文件");
        } catch (Exception exception) {
            if (exception instanceof com.platform.framework.exception.BusinessException business) {
                throw business;
            }
            throw RelationErrors.error(400, RelationErrors.IMPORT_REJECTED,
                    "Excel 文件无效、已加密或无法安全解析");
        }
    }

    private void rejectUnsafePackage(OPCPackage pack, XSSFWorkbook workbook) throws Exception {
        boolean macro = pack.getParts().stream().map(PackagePart::getPartName)
                .map(Object::toString).anyMatch(name -> name.toLowerCase(Locale.ROOT)
                        .contains("vbaproject"));
        if (macro || !workbook.getExternalLinksTable().isEmpty()) {
            throw RelationErrors.error(400, RelationErrors.IMPORT_REJECTED,
                    "不接受包含宏或外部链接的工作簿");
        }
    }

    private Map<String, Integer> headerColumns(Row header, List<MeteringImportIssue> issues) {
        Map<String, Integer> columns = new HashMap<>();
        if (header != null) {
            for (Cell cell : header) {
                if (cell.getCellType() == CellType.FORMULA) {
                    issues.add(error(MeteringTemplateService.DATA_SHEET, 1, "header",
                            RelationErrors.IMPORT_HEADER_INVALID, "表头不能使用公式"));
                    continue;
                }
                String value = text(cell);
                if (value.isBlank()) continue;
                if (columns.putIfAbsent(value, cell.getColumnIndex()) != null) {
                    issues.add(error(MeteringTemplateService.DATA_SHEET, 1, value,
                            RelationErrors.IMPORT_HEADER_INVALID, "表头重复: " + value));
                }
            }
        }
        for (String required : MeteringTemplateService.HEADERS) {
            if (!columns.containsKey(required)) {
                issues.add(error(MeteringTemplateService.DATA_SHEET, 1, required,
                        RelationErrors.IMPORT_HEADER_INVALID, "缺少必要表头: " + required));
            }
        }
        return columns;
    }

    private ParsedRow parseRow(int rowNumber, Row row, Map<String, Integer> columns) {
        return new ParsedRow(rowNumber,
                value(row, columns, "模板版本"), value(row, columns, "边界编码"),
                value(row, columns, "边界名称"), value(row, columns, "能源类型"),
                value(row, columns, "表计测点编码"), upper(row, columns, "表计角色"),
                value(row, columns, "上级表计编码"), upper(row, columns, "计量方向"),
                upper(row, columns, "覆盖对象类型"), value(row, columns, "覆盖对象编码"),
                upper(row, columns, "分配状态"), upper(row, columns, "原因编码"),
                value(row, columns, "原因说明"), value(row, columns, "证据引用"),
                value(row, columns, "补充说明"));
    }

    private boolean tooLong(ParsedRow row) {
        return java.util.Arrays.stream(new String[]{
                row.templateVersion(), row.boundaryCode(), row.boundaryName(), row.energyType(),
                row.meterPointCode(), row.meterRole(), row.parentMeterCode(), row.meterDirection(),
                row.targetType(), row.targetCode(), row.allocationStatus(), row.reasonCode(),
                row.reasonText(), row.evidenceReference(), row.description()})
                .anyMatch(value -> value != null && value.length() > properties.getMaxTextLength());
    }

    private byte[] readFile(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx")
                || file.getSize() > properties.getMaxFileBytes()) {
            throw RelationErrors.error(400, RelationErrors.IMPORT_REJECTED,
                    "只接受大小受限的无宏 .xlsx 文件");
        }
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw RelationErrors.error(400, RelationErrors.IMPORT_REJECTED, "Excel 文件读取失败");
        }
    }

    private static String instructionVersion(Sheet sheet) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                String value = text(cell);
                if (value.startsWith("templateVersion=")) {
                    return value.substring("templateVersion=".length()).trim();
                }
            }
        }
        return null;
    }

    private static boolean containsFormulaOrError(Row row) {
        for (Cell cell : row) {
            if (cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.ERROR) {
                return true;
            }
        }
        return false;
    }

    private static boolean empty(Row row, java.util.Collection<Integer> columns) {
        for (int index : new HashSet<>(columns)) {
            if (!text(row.getCell(index)).isBlank()) return false;
        }
        return true;
    }

    private static int countDataRows(Sheet sheet) {
        int count = 0;
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null) continue;
            boolean populated = false;
            for (Cell cell : row) {
                if (!text(cell).isBlank() || cell.getCellType() == CellType.FORMULA
                        || cell.getCellType() == CellType.ERROR) {
                    populated = true;
                    break;
                }
            }
            if (populated) count++;
        }
        return count;
    }

    private static String value(Row row, Map<String, Integer> columns, String header) {
        String value = text(row.getCell(columns.get(header)));
        return value.isBlank() ? null : value;
    }

    private static String upper(Row row, Map<String, Integer> columns, String header) {
        String value = value(row, columns, header);
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String text(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> NumberToTextConverter.toText(cell.getNumericCellValue());
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            default -> "";
        };
    }

    static MeteringImportIssue error(
            String sheet, int row, String field, String code, String message) {
        return new MeteringImportIssue("ERROR", sheet, row, field, code, message);
    }
}
