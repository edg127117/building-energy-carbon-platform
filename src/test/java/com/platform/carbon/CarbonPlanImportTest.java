package com.platform.carbon;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.ByteArrayOutputStream;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CarbonPlanImportTest {
    @Test void importsTwelveMonthsWithFileHashAndRejectsFormulasMissingAndDuplicateMonths() throws Exception {
        var parser=new CarbonPlanImport();var core=new CarbonPlanCore();
        var workbook=file("valid");
        // 可复现的手工联调上传夹具；只生成在构建目录，不自动写入任何运行库。
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        java.nio.file.Files.write(java.nio.file.Path.of("target","carbon-plan-baseline-test.xlsx"),workbook.getBytes());
        var baseline=core.normalize(parser.parse(workbook,"EXTERNAL_CONFIRMED","专业确认资料","建筑范围一、二"),"b");
        assertThat(baseline.annualBaseline()).isEqualByComparingTo("160");
        assertThat(baseline.source()).contains("xlsx-sha256=");
        for(String type:List.of("formula","missing","duplicate","unit","negative","extra")) {
            assertThatThrownBy(()->core.normalize(parser.parse(file(type),"EXTERNAL_CONFIRMED","资料","边界"),"b")).as(type).isInstanceOf(com.platform.framework.exception.BusinessException.class);
        }
    }
    private MockMultipartFile file(String type) throws Exception {
        try(var book=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("年度基准");var header=sheet.createRow(0);var names=List.of("月份","月碳排","单位","来源");
            for(int i=0;i<4;i++) header.createCell(i).setCellValue(names.get(i));
            for(int m=1;m<=(type.equals("missing")?11:12);m++) {
                var row=sheet.createRow(m);row.createCell(0).setCellValue(type.equals("duplicate")?1:m);
                row.createCell(1).setCellValue(type.equals("negative")?-1:m<=8?10:20);
                row.createCell(2).setCellValue(type.equals("unit")?"kgCO2e":"tCO2e");row.createCell(3).setCellValue("TEST / 单元测试");
            }
            if(type.equals("formula")) sheet.getRow(1).getCell(1).setCellFormula("1+1");
            if(type.equals("extra")) book.createSheet("多余");
            book.write(bytes);return new MockMultipartFile("file","baseline.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes.toByteArray());
        }
    }
}
