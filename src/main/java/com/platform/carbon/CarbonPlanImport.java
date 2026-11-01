package com.platform.carbon;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import static com.platform.carbon.CarbonPlanContracts.*;

/** 复用已安装POI读取单张12月基准表；拒绝公式缓存、宏、链接，预览不写库。 */
@Component
class CarbonPlanImport {
    private static final List<String> HEADERS=List.of("月份","月碳排","单位","来源");
    Baseline parse(MultipartFile file,String nature,String source,String boundary) {
        if(file==null || file.isEmpty() || file.getSize()>256*1024 || file.getOriginalFilename()==null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx")) CarbonPlanCore.invalid("只接受256KB以内的无宏.xlsx基准表");
        CarbonPlanCore.text(source,400,"来源说明");
        try {
            byte[] bytes=file.getBytes();
            try(OPCPackage pack=OPCPackage.open(new ByteArrayInputStream(bytes));XSSFWorkbook book=new XSSFWorkbook(pack)) {
                if(book.getNumberOfSheets()!=1 || !book.getExternalLinksTable().isEmpty()
                        || pack.getParts().stream().anyMatch(p->p.getPartName().toString().toLowerCase(Locale.ROOT).contains("vbaproject"))) CarbonPlanCore.invalid("基准表只接受一张工作表，禁止宏及外部链接");
                Sheet sheet=book.getSheetAt(0);
                if(sheet.getLastRowNum()!=12 || sheet.getRow(0)==null || sheet.getRow(0).getLastCellNum()!=4) CarbonPlanCore.invalid("表头须为月份、月碳排、单位、来源，随后完整12行");
                for(int i=0;i<4;i++) if(!HEADERS.get(i).equals(text(sheet.getRow(0).getCell(i)))) CarbonPlanCore.invalid("基准表表头无效");
                List<BaselineMonth> months=new ArrayList<>();
                for(int i=1;i<=12;i++) {
                    Row row=sheet.getRow(i);
                    if(row==null || row.getLastCellNum()!=4) CarbonPlanCore.invalid("基准表每行须有4列");
                    int month=new BigDecimal(text(row.getCell(0))).intValueExact();
                    BigDecimal value=new BigDecimal(text(row.getCell(1)));
                    months.add(new BaselineMonth(month,value,text(row.getCell(2)),text(row.getCell(3)),null,null,null));
                }
                String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                return new Baseline(nature,null,CarbonPlanCore.ZONE,boundary,source+";xlsx-sha256="+hash,months,null,null);
            }
        } catch(com.platform.framework.exception.BusinessException exception) { throw exception; }
        catch(Exception exception) { throw CarbonErrors.error(400,CarbonErrors.VALIDATION_FAILED,"基准表读取失败：请检查月份、十进制值及无公式.xlsx格式"); }
    }
    private String text(Cell c) {
        if(c==null) return "";
        return switch(c.getCellType()) {
            case STRING -> c.getStringCellValue().trim();
            case NUMERIC -> NumberToTextConverter.toText(c.getNumericCellValue());
            case BLANK -> "";
            default -> throw CarbonErrors.error(400,CarbonErrors.VALIDATION_FAILED,"不接受公式、错误或布尔单元格");
        };
    }
}
