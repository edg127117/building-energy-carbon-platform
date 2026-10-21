package com.platform.energy.classification;

import com.platform.energy.period.EnergyPeriodAuthorization;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.platform.energy.classification.ClassificationContracts.*;

/** 标准人工读数工作簿适配器；只读取常量，不执行公式、不按日期猜时区或倍率。 */
@Service
@RequiredArgsConstructor
public class ReadingWorkbookService {
    public static final List<String> HEADERS=List.of("测点编码","起始时刻","结束时刻","能源编码","单位","读数语义","起始读数","结束读数","期间用量","倍率","已换算","确认状态","来源依据","时间精度","替代事实ID");
    public record Preview(ImportRequest request,ImportView preview) { }
    private final EnergyPeriodAuthorization authorization;
    private final ClassificationRepository repository;
    private final ClassificationService classifications;
    public byte[] template(long user,Collection<String> roles,String building) {
        check(user,roles,building);
        try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=workbook.createSheet("人工读数");var row=sheet.createRow(0);
            for(int i=0;i<HEADERS.size();i++){row.createCell(i,CellType.STRING).setCellValue(HEADERS.get(i));sheet.setColumnWidth(i,22*256);}
            var instructions=workbook.createSheet("说明");
            List<String> notes=List.of("最多500条，每行一个测点精确期间。测点编码按当前建筑解析，不填写内部ID。",
                    "起止时刻使用包含时区的ISO时间，如2026-08-01T00:00:00+08:00；不能仅填日期。",
                    "读数语义：CUMULATIVE或PERIOD_TOTAL；确认状态：CONFIRMED或PENDING_EXPERT；时间精度：EXACT或DATE_ONLY。",
                    "已换算：true或false，必须明确。未知倍率留空且标记PENDING_EXPERT；不得重复应用倍率。",
                    "能源编码和单位必须与已审核测点能源绑定一致；来源依据必填。修订必须明确替代事实ID。",
                    "公式、宏、外部链接不接受；精度超过15位的数请存为文本。预检不写入，导入仍需独立审核，审核不等于正式封账。");
            for(int i=0;i<notes.size();i++)instructions.createRow(i).createCell(0).setCellValue(notes.get(i));
            workbook.write(out);return out.toByteArray();
        }catch(IOException e){throw invalid("工作簿模板无法生成");}
    }
    public Preview preview(long user,Collection<String> roles,String building,MultipartFile file,String key,String reason) {
        check(user,roles,building);
        if(file==null||file.isEmpty()||file.getSize()>5*1024*1024||file.getOriginalFilename()==null||!file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw invalid("只接受不超过5MiB、无宏未加密的标准.xlsx工作簿");
        List<Issue> issues=new ArrayList<>();List<Reading> readings=new ArrayList<>();
        try(var pack=OPCPackage.open(new ByteArrayInputStream(file.getBytes()));var workbook=new XSSFWorkbook(pack)) {
            for(var part:pack.getParts()) {
                String name=part.getPartName().getName().toLowerCase(Locale.ROOT),type=part.getContentType().toLowerCase(Locale.ROOT);
                if(name.contains("vba")||name.contains("externallink")||name.contains("embedding")||type.contains("macroenabled"))throw invalid("不接受宏、嵌入对象或外部链接");
                if(!part.isRelationshipPart())for(var rel:part.getRelationships())if(rel.getTargetMode()==org.apache.poi.openxml4j.opc.TargetMode.EXTERNAL)throw invalid("不接受外部链接");
            }
            if(!workbook.getExternalLinksTable().isEmpty())throw invalid("不接受外部链接");
            var sheet=workbook.getSheet("人工读数");
            if(sheet==null||sheet.getLastRowNum()>500)throw invalid("须包含人工读数工作表，最多500行");
            var header=sheet.getRow(0);Map<String,Integer> columns=new HashMap<>();
            if(header==null||header.getLastCellNum()>32)throw invalid("表头缺失或列数超限");
            for(var cell:header){String value=text(cell);if(!value.isBlank()&&columns.putIfAbsent(value,cell.getColumnIndex())!=null)throw invalid("表头重复");}
            if(!columns.keySet().containsAll(HEADERS))throw invalid("工作簿缺少标准表头，请下载模板");
            for(int n=1;n<=sheet.getLastRowNum();n++) {
                var row=sheet.getRow(n);if(row==null)continue;
                try {
                    if(row.getLastCellNum()>32)throw invalid("行列数超限");
                    for(var cell:row)text(cell);
                    var values=HEADERS.stream().map(h->text(row.getCell(columns.get(h)))).toList();
                    if(values.stream().allMatch(String::isBlank))continue;
                    String point=repository.pointIdForCode(building,values.get(0));if(point==null)throw invalid("测点编码不存在或不属于当前建筑");
                    String scaled=values.get(10);if(!Set.of("true","false").contains(scaled))throw invalid("已换算必须明确为true或false");
                    readings.add(new Reading(point,Instant.parse(values.get(1)),Instant.parse(values.get(2)),values.get(3),values.get(4),values.get(5),
                            decimal(values.get(6)),decimal(values.get(7)),decimal(values.get(8)),decimal(values.get(9)),Boolean.parseBoolean(scaled),values.get(11),values.get(12),values.get(13),values.get(14).isBlank()?null:values.get(14)));
                }catch(Exception e){issues.add(new Issue("ERROR","WORKBOOK_ROW_INVALID",String.valueOf(n+1),e instanceof BusinessException?e.getMessage():"时间或数值无效，起止时刻须包含明确时区"));}
            }
        }catch(BusinessException e){throw e;}
        catch(Exception e){throw invalid("工作簿无效或包含不支持的内容，请使用标准模板");}
        if(readings.isEmpty())issues.add(new Issue("ERROR","WORKBOOK_EMPTY",null,"没有可导入的来源记录"));
        if(!issues.isEmpty())return new Preview(null,new ImportView(null,"INVALID",0,List.copyOf(issues)));
        var request=new ImportRequest(key,List.copyOf(readings),reason);
        return new Preview(request,classifications.preview(user,roles,building,request));
    }
    private void check(long user,Collection<String> roles,String building) {
        authorization.requireReader(roles);authorization.checkBuilding(user,roles,building);
        if(!repository.buildingExists(building))throw invalid("建筑不存在");
    }
    private static String text(Cell cell) {
        if(cell==null||cell.getCellType()==CellType.BLANK)return "";
        String value=switch(cell.getCellType()) {
            case STRING->cell.getStringCellValue();
            case NUMERIC->{String number=NumberToTextConverter.toText(cell.getNumericCellValue());if(new BigDecimal(number).precision()>15||DateUtil.isCellDateFormatted(cell))throw invalid("Excel日期或长数值不能确认时区与精度，请使用明确文本");yield number;}
            case BOOLEAN->Boolean.toString(cell.getBooleanCellValue());default->throw invalid("不接受公式或错误单元格");
        };
        if(value.length()>1000)throw invalid("单元格文本过长");return value.trim();
    }
    private static BigDecimal decimal(String value){return value.isBlank()?null:new BigDecimal(value);}
    private static BusinessException invalid(String message){return new BusinessException(400,"ENERGY_CLASSIFICATION_WORKBOOK_INVALID",message);}
}
