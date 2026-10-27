package com.platform.energy.reporting;

import com.platform.energy.catalog.EnergyCatalogLookup;
import com.platform.framework.exception.BusinessException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.platform.energy.reporting.ReportingContracts.*;

/** 只读取明确输入常量；原资料其他工作表的计算公式不执行，导入预览不写业务数据。 */
@Service
public class ReportingWorkbookService {
    static final List<String> HEADERS=List.of("来源标识","来源名称","用量","开始日期","结束日期（不含）","凭证引用","数据性质","确认状态","说明","碳排除量","排除原因","排除依据");
    private final ReportingService reporting;private final EnergyCatalogLookup lookup;
    public ReportingWorkbookService(ReportingService reporting,EnergyCatalogLookup lookup){this.reporting=reporting;this.lookup=lookup;}
    public record Mapping(String sourceId,String sheetName,String quantityCell,String unitCode,LocalDate startDate,
                          LocalDate endDate,String dataNature,String evidence) { }
    public record Request(String format,List<Mapping> mappings) { }
    public record CellValue(String sourceId,String sheetName,String cell,String originalValue,String originalUnit,String note) { }
    public record Preview(List<ReportingContracts.Row> rows,List<Issue> issues,List<CellValue> cells) { }
    public byte[] template(long user,Collection<String> roles,String building,String batchId) {
        var batch=reporting.batch(user,roles,building,batchId);var context=reporting.context(user,roles,building,batch.month());
        Map<String,Source> sources=new HashMap<>();context.sources().forEach(s->sources.put(s.sourceId(),s));
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("月度填报");var header=sheet.createRow(0);
            for(int i=0;i<HEADERS.size();i++){header.createCell(i).setCellValue(HEADERS.get(i));sheet.setColumnWidth(i,24*256);}
            sheet.setColumnHidden(0,true);sheet.createFreezePane(2,1);
            for(int n=0;n<batch.rows().size();n++) {
                var input=batch.rows().get(n);var r=sheet.createRow(n+1);var source=sources.get(input.sourceId());
                List<String> values=List.of(input.sourceId(),source==null?"来源待确认":source.configuration().name(),str(input.quantity()),
                    input.startDate().toString(),input.endDate().toString(),str(input.evidence()),str(input.dataNature()),input.valueStatus(),
                    str(input.note()),str(input.exclusionQuantity()),str(input.exclusionReason()),str(input.exclusionEvidence()));
                for(int c=0;c<values.size();c++)r.createCell(c,CellType.STRING).setCellValue(values.get(c));
            }
            var notes=book.createSheet("说明");
            notes.createRow(0).createCell(0).setCellValue("保留实际账期，结束日期不含当天；确认状态CONFIRMED/MISSING/PENDING_CONFIRMATION/NOT_APPLICABLE。空用量不等于零。金额估算标记AMOUNT_ESTIMATE；原生实物量标记NATIVE_QUANTITY。数值超过15位精度须存文本。来源、单位、审核状态和因子不由工作簿变更。");
            book.write(out);return out.toByteArray();
        }catch(IOException e){throw invalid("账单模板无法生成");}
    }
    public Preview preview(long user,Collection<String> roles,String building,String batchId,MultipartFile file,Request request) {
        var batch=reporting.batch(user,roles,building,batchId);var context=reporting.context(user,roles,building,batch.month());
        Map<String,Source> sources=new HashMap<>();context.sources().forEach(s->sources.put(s.sourceId(),s));
        if(file==null||file.isEmpty()||file.getSize()>1024*1024||file.getOriginalFilename()==null
            ||!file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw invalid("仅接受不超过1MiB的.xlsx资料");
        List<ReportingContracts.Row> rows=new ArrayList<>();List<Issue> issues=new ArrayList<>();List<CellValue> cells=new ArrayList<>();
        try(var pack=OPCPackage.open(new ByteArrayInputStream(file.getBytes()));var book=new XSSFWorkbook(pack)) {
            if(pack.getParts().size()>2000||book.getNumberOfSheets()>40)throw invalid("工作簿结构超过上限");
            for(var part:pack.getParts()) {
                var name=part.getPartName().getName().toLowerCase(Locale.ROOT);var type=part.getContentType().toLowerCase(Locale.ROOT);
                if(name.contains("vba")||name.contains("externallink")||name.contains("embedding")||type.contains("macroenabled"))throw invalid("不接受宏、嵌入对象或外部链接");
                // 资料中的普通超链接只是展示元数据：既不跟随也不回传目标，不等同于外部工作簿数据依赖。
                if(!part.isRelationshipPart())for(var rel:part.getRelationships())if(rel.getTargetMode()==TargetMode.EXTERNAL
                        &&!"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink".equals(rel.getRelationshipType()))
                    throw invalid("不接受外部工作簿或资源引用");
            }
            if(!book.getExternalLinksTable().isEmpty())throw invalid("不接受外部链接");
            if(request==null||"STANDARD_MONTHLY".equals(request.format())) {
                var sheet=book.getSheet("月度填报");if(sheet==null||sheet.getLastRowNum()>100)throw invalid("请使用月度填报模板，最多100行");
                for(int c=0;c<HEADERS.size();c++)if(!HEADERS.get(c).equals(text(sheet.getRow(0)==null?null:sheet.getRow(0).getCell(c))))throw invalid("表头与模板不一致");
                for(int n=1;n<=sheet.getLastRowNum();n++) {
                    var r=sheet.getRow(n);if(r==null)continue;
                    try {
                        var id=text(r.getCell(0));if(id.isBlank())continue;
                        var source=sources.get(id);if(source==null)throw invalid("来源不属于本建筑，不能按名称猜测");
                        var quantity=text(r.getCell(2));
                        rows.add(new ReportingContracts.Row(id,source.revision(),decimal(quantity),text(r.getCell(7)),LocalDate.parse(text(r.getCell(3))),
                            LocalDate.parse(text(r.getCell(4))),text(r.getCell(5)),text(r.getCell(8)),decimal(text(r.getCell(9))),text(r.getCell(10)),text(r.getCell(11)),text(r.getCell(6))));
                        cells.add(new CellValue(id,sheet.getSheetName(),"C"+(n+1),quantity,source.configuration().unitCode(),"标准模板原生用量"));
                    }catch(Exception e){issues.add(issue("行"+(n+1),e));}
                }
            } else if("CELL_MAPPING".equals(request.format())) {
                if(request.mappings()==null||request.mappings().isEmpty()||request.mappings().size()>100)throw invalid("须明确映射1至100个输入单元格");
                for(var mapping:request.mappings())try {
                    var source=sources.get(mapping.sourceId());if(source==null)throw invalid("映射来源不存在或不属于当前建筑");
                    var sheet=book.getSheet(mapping.sheetName());if(sheet==null)throw invalid("映射工作表不存在");
                    if(mapping.quantityCell()==null||!mapping.quantityCell().matches("[A-Z]{1,3}[1-9][0-9]{0,3}"))throw invalid("输入单元格引用无效");
                    var ref=new CellReference(mapping.quantityCell());var r=sheet.getRow(ref.getRow());String raw=text(r==null?null:r.getCell(ref.getCol()));
                    if(raw.isBlank())throw invalid("原单元格未填，不能将汇总区零值当实际用量");
                    if(mapping.startDate()==null||mapping.endDate()==null)throw invalid("须明确原资料的实际账期");
                    Integer sourceYear=sourceYear(sheet);
                    if(sourceYear!=null&&sourceYear!=mapping.startDate().getYear())throw invalid("原资料标注"+sourceYear+"年，不能映射到其他年度账期");
                    String originalUnit=originalUnit(sheet,ref);
                    if(originalUnit!=null&&!originalUnit.equalsIgnoreCase(mapping.unitCode()))throw invalid("原资料用量单位为"+originalUnit+"，不能重新标记为"+mapping.unitCode());
                    var quantity=decimal(raw);var converted=lookup.convert(quantity,mapping.unitCode(),source.configuration().unitCode(),mapping.startDate().atStartOfDay())
                        .orElseThrow(()->invalid("原资料单位与来源单位不兼容；标准体积、密度或估算不得自动换算"));
                    String annotation=annotation(sheet,ref),nature=mapping.dataNature();
                    if(annotation.contains("金额")||annotation.contains("单价折算"))nature="AMOUNT_ESTIMATE";
                    // 采纳后行说明随审核冻结，保留原值、原单位及转换版本，不能只留下换算结果。
                    rows.add(new ReportingContracts.Row(source.sourceId(),source.revision(),converted.convertedQuantity(),"CONFIRMED",mapping.startDate(),mapping.endDate(),
                        mapping.evidence(),"原资料 "+mapping.sheetName()+"!"+mapping.quantityCell()+"；原值 "+raw+" "+mapping.unitCode()
                            +"；填入 "+converted.convertedQuantity().toPlainString()+" "+source.configuration().unitCode()
                            +"；单位版本 "+converted.inputUnit().versionId()+" → "+converted.targetUnit().versionId()
                            +"；"+annotation,null,"","",nature));
                    cells.add(new CellValue(source.sourceId(),mapping.sheetName(),mapping.quantityCell(),raw,mapping.unitCode(),annotation));
                    if("AMOUNT_ESTIMATE".equals(nature))issues.add(new Issue("AMOUNT_ESTIMATE",mapping.quantityCell(),"原资料注明金额或单价折算；仅可保存待确认草稿，不直接进入正式核算"));
                }catch(Exception e){issues.add(issue(mapping==null?null:mapping.quantityCell(),e));}
            } else throw invalid("导入格式无效");
        }catch(BusinessException e){throw e;}catch(Exception e){throw invalid("工作簿无效、被加密或结构不支持");}
        if(rows.stream().map(ReportingContracts.Row::sourceId).distinct().count()!=rows.size())issues.add(new Issue("DUPLICATE_SOURCE",batchId,"同来源映射了多个值，请先明确期间及合并依据"));
        if(rows.isEmpty())issues.add(new Issue("EMPTY",batchId,"没有可预览的输入值"));
        return new Preview(List.copyOf(rows),List.copyOf(issues),List.copyOf(cells));
    }
    /** 已提供的项目工作簿把数据性质写在同一输入行，必须保留该明确标注，不使用计算区缓存。 */
    private static String annotation(Sheet sheet,CellReference ref) {
        String name=sheet.getSheetName().trim();int col=-1;
        if("8-电力".equals(name)&&ref.getCol()>=20&&ref.getCol()<=31)col=33;
        if("4-固定源化石燃料".equals(name)&&ref.getCol()>=29&&ref.getCol()<=40)col=42;
        var row=sheet.getRow(ref.getRow());if(col<0||row==null)return "";
        var cell=row.getCell(col);return cell!=null&&cell.getCellType()==CellType.STRING?text(cell):"";
    }
    private static Integer sourceYear(Sheet sheet) {
        Set<Integer> years=new HashSet<>();var pattern=java.util.regex.Pattern.compile("(20[0-9]{2})年数据");
        for(int n=0;n<Math.min(5,sheet.getLastRowNum()+1);n++) {
            var row=sheet.getRow(n);if(row==null)continue;
            for(var cell:row)if(cell.getColumnIndex()<50&&cell.getCellType()==CellType.STRING) {
                var match=pattern.matcher(cell.getStringCellValue());while(match.find())years.add(Integer.valueOf(match.group(1)));
            }
        }
        if(years.size()>1)throw invalid("原资料包含多个数据年度，须先明确对应输入区");
        return years.stream().findFirst().orElse(null);
    }
    private static String originalUnit(Sheet sheet,CellReference ref) {
        int column=switch(sheet.getSheetName().trim()){case "8-电力"->19;case "4-固定源化石燃料"->26;default->-1;};
        var row=sheet.getRow(ref.getRow());if(column<0||row==null)return null;var cell=row.getCell(column);
        if(cell==null||cell.getCellType()!=CellType.STRING)return null;
        String declared=text(cell).replace(" ","").toUpperCase(Locale.ROOT);
        if(declared.isBlank())return null;
        // 明确写出的费用或未知单位也是约束，不能按“未提供”跳过后重新标成实物用量。
        return switch(declared) {
            case "KWH","千瓦时"->"KWH";case "MWH","兆瓦时"->"MWH";
            case "立方米","M3","M³"->"M3";case "NM3","NM³","标准立方米"->"NM3";
            case "KG","千克"->"KG";case "T","吨"->"T";default->declared;
        };
    }
    private static String text(Cell c) {
        if(c==null||c.getCellType()==CellType.BLANK)return "";
        String value=switch(c.getCellType()){
            case STRING->c.getStringCellValue();
            case NUMERIC->{if(DateUtil.isCellDateFormatted(c))throw invalid("日期须使用明确文本");String n=NumberToTextConverter.toText(c.getNumericCellValue());
                if(new BigDecimal(n).precision()>15)throw invalid("长数值请存文本，避免Excel丢失精度");yield n;}
            default->throw invalid("输入单元格不能是公式或错误值");
        };
        if(value.length()>1000)throw invalid("单元格文本过长");return value.trim();
    }
    private static BigDecimal decimal(String s){
        if(s.isBlank())return null;var n=new BigDecimal(s);
        if(n.signum()<0||n.precision()>28||n.scale()>12||(long)n.precision()-n.scale()>28)throw invalid("用量非负，整数最多28位、小数最多12位");return n;
    }
    private static String str(Object value){return value==null?"":value.toString();}
    private static Issue issue(String ref,Exception e){return new Issue("INPUT_CELL_INVALID",ref,e instanceof BusinessException?e.getMessage():"数值、日期或来源映射无效");}
    private static BusinessException invalid(String message){return new BusinessException(400,"REPORTING_WORKBOOK_INVALID",message);}
}
