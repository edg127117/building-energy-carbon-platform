package com.platform.relation.importing;

import com.platform.relation.RelationErrors;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@Service
/** 生成平台标准 V1 输入契约；示例说明不代表真实建筑或专家确认事实。 */
public class MeteringTemplateService {
    static final String VERSION = "V1";
    static final String INSTRUCTION_SHEET = "填写说明";
    static final String DATA_SHEET = "计量关系";
    static final List<String> HEADERS = List.of(
            "模板版本", "边界编码", "边界名称", "能源类型", "表计测点编码",
            "表计角色", "上级表计编码", "计量方向", "覆盖对象类型", "覆盖对象编码",
            "分配状态", "原因编码", "原因说明", "证据引用", "补充说明");

    public byte[] create(String templateVersion) {
        if (!VERSION.equalsIgnoreCase(templateVersion)) {
            throw RelationErrors.error(400, RelationErrors.VALIDATION_FAILED,
                    "不支持的表计导入模板版本: " + templateVersion);
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle title = titleStyle(workbook);
            CellStyle header = headerStyle(workbook);
            Sheet instructions = workbook.createSheet(INSTRUCTION_SHEET);
            addInstruction(instructions, title);
            XSSFSheet data = workbook.createSheet(DATA_SHEET);
            Row headerRow = data.createRow(0);
            for (int index = 0; index < HEADERS.size(); index++) {
                headerRow.createCell(index).setCellValue(HEADERS.get(index));
                headerRow.getCell(index).setCellStyle(header);
                data.setColumnWidth(index, columnWidth(index));
            }
            data.createFreezePane(0, 1);
            data.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, HEADERS.size() - 1));
            addListValidation(data, 5, new String[]{"MAIN", "SUB", "INDEPENDENT", "UNKNOWN"});
            addListValidation(data, 7,
                    new String[]{"INBOUND", "OUTBOUND", "BIDIRECTIONAL", "UNKNOWN"});
            addListValidation(data, 8, new String[]{"SPACE", "SYSTEM", "EQUIPMENT"});
            addListValidation(data, 10,
                    new String[]{"ASSIGNED", "UNASSIGNED", "PENDING_EXPERT"});
            workbook.getProperties().getCustomProperties()
                    .addProperty("templateVersion", VERSION);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("表计导入模板生成失败", exception);
        }
    }

    private static void addInstruction(Sheet sheet, CellStyle title) {
        List<String[]> rows = List.of(
                new String[]{"平台表计关系标准模板", "templateVersion=V1"},
                new String[]{"用途", "上传预检通过后，仅写入指定关系草稿；不会自动提交、审核或生效。"},
                new String[]{"专业边界", "不确定角色或方向填写 UNKNOWN，分配状态填写 PENDING_EXPERT。"},
                new String[]{"生效限制", "待专家确认的数据可以保存草稿，但不能审核通过或生效。"},
                new String[]{"编码规则", "只接受当前建筑平台稳定编码，不模糊匹配，不按名称推断。"},
                new String[]{"表计角色", "MAIN 总表；SUB 分表；INDEPENDENT 独立表；UNKNOWN 尚未确认。"},
                new String[]{"计量方向", "INBOUND 流入；OUTBOUND 流出；BIDIRECTIONAL 双向；UNKNOWN 尚未确认。"},
                new String[]{"分配状态", "ASSIGNED 必须填写边界、表计、覆盖对象和证据；其他状态必须填写原因编码。"},
                new String[]{"层级规则", "SUB 必须填写上级表计；MAIN 和 INDEPENDENT 禁止填写上级表计。"},
                new String[]{"示例声明", "模板不预置业务数据；任何示例均为明确标记的合成数据，不能视为真实业务验收。"});
        for (int index = 0; index < rows.size(); index++) {
            Row row = sheet.createRow(index);
            row.createCell(0).setCellValue(rows.get(index)[0]);
            row.createCell(1).setCellValue(rows.get(index)[1]);
            if (index == 0) {
                row.getCell(0).setCellStyle(title);
                row.getCell(1).setCellStyle(title);
            }
        }
        sheet.setColumnWidth(0, 22 * 256);
        sheet.setColumnWidth(1, 92 * 256);
        sheet.createFreezePane(0, 1);
    }

    private static CellStyle titleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        var font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        var font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setWrapText(true);
        return style;
    }

    private static void addListValidation(XSSFSheet sheet, int column, String[] values) {
        XSSFDataValidationHelper helper = new XSSFDataValidationHelper(sheet);
        var constraint = helper.createExplicitListConstraint(values);
        var regions = new CellRangeAddressList(1, 5_000, column, column);
        var validation = helper.createValidation(constraint, regions);
        validation.setShowErrorBox(true);
        validation.setSuppressDropDownArrow(true);
        sheet.addValidationData(validation);
    }

    private static int columnWidth(int index) {
        return switch (index) {
            case 0, 5, 7, 8, 10 -> 20 * 256;
            case 12, 13, 14 -> 36 * 256;
            default -> 24 * 256;
        };
    }
}
