package com.platform.energy.reporting;

import com.platform.energy.catalog.EnergyCatalogLookup;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.platform.energy.reporting.ReportingContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ReportingWorkbookTest {
    ReportingService reporting;EnergyCatalogLookup lookup;ReportingWorkbookService service;
    static final LocalDate MONTH=LocalDate.of(2022,1,1);static final Set<String> ROLES=Set.of("BUILDING_OWNER");
    @BeforeEach void setup(){
        reporting=mock(ReportingService.class);lookup=mock(EnergyCatalogLookup.class);service=new ReportingWorkbookService(reporting,lookup);
        var config=new SourceConfig("全楼电费账单","ELECTRICITY","KWH","BILL","PURCHASED_ELECTRICITY","PRIMARY","WHOLE_BUILDING","NO_METER",
            "Asia/Shanghai",LocalDate.of(2020,1,1),null,"合同依据","配置",null,null,null,null);
        var source=new Source("SOURCE",2,"APPROVED",2,3L,config);
        var row=new Row("SOURCE",2,null,"MISSING",MONTH,MONTH.plusMonths(1),"","",null,"","","NATIVE_QUANTITY");
        var batch=new Batch("BATCH","B",MONTH,"DRAFT",0,1,null,null,List.of(row),null);
        when(reporting.batch(1,ROLES,"B","BATCH")).thenReturn(batch);
        when(reporting.context(1,ROLES,"B",MONTH)).thenReturn(new Context("B",MONTH,List.of(source),List.of(batch),List.of(),List.of(),List.of(),new Capabilities(false,false,false,false)));
        when(lookup.convert(any(),eq("KWH"),eq("KWH"),any())).thenAnswer(i->{
            var unit=new EnergyCatalogLookup.ApprovedUnit("u","KWH","uv",1,"kWh","ENERGY","KWH",BigDecimal.ONE,"IDENTITY",null,"STANDARD","依据",LocalDateTime.of(2000,1,1,0,0),null);
            return Optional.of(new EnergyCatalogLookup.UnitConversion(i.getArgument(0),unit,i.getArgument(0),unit));
        });
    }
    @Test void templateRoundTripPreservesLongDecimalAndDoesNotExecuteUnrelatedFormulas() throws Exception {
        byte[] template=service.template(1,ROLES,"B","BATCH");byte[] file;
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(template));var out=new ByteArrayOutputStream()){
            var row=book.getSheet("月度填报").getRow(1);row.getCell(2).setCellValue("928127.123456789012");row.getCell(5).setCellValue("原始账单");row.getCell(7).setCellValue("CONFIRMED");
            book.createSheet("计算结果").createRow(0).createCell(0).setCellFormula("1+2");book.write(out);file=out.toByteArray();
        }
        var preview=service.preview(1,ROLES,"B","BATCH",file(file),null);
        assertThat(preview.issues()).isEmpty();assertThat(preview.rows()).singleElement().satisfies(r->assertThat(r.quantity()).isEqualByComparingTo("928127.123456789012"));
        verify(reporting,never()).saveBatch(anyLong(),any(),anyString(),anyString(),any());
    }
    @Test void legacyInputMappingExposesAmountEstimateAndIgnoresResultFormula() throws Exception {
        byte[] file;
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            var sheet=book.createSheet("8-电力");var row=sheet.createRow(4);row.createCell(20).setCellValue(88375);row.createCell(33).setCellValue("金额、单价折算");
            row.createCell(32).setCellFormula("SUM(U5:AF5)");book.write(out);file=out.toByteArray();
        }
        var request=new ReportingWorkbookService.Request("CELL_MAPPING",List.of(new ReportingWorkbookService.Mapping("SOURCE","8-电力","U5","KWH",MONTH,MONTH.plusMonths(1),"NATIVE_QUANTITY","原工作簿")));
        var preview=service.preview(1,ROLES,"B","BATCH",file(file),request);
        assertThat(preview.rows().getFirst().quantity()).isEqualByComparingTo("88375");
        assertThat(preview.rows().getFirst().dataNature()).isEqualTo("AMOUNT_ESTIMATE");
        assertThat(preview.rows().getFirst().note()).contains("原值 88375 KWH","填入 88375 KWH","单位版本 uv → uv");
        assertThat(preview.issues()).anyMatch(i->"AMOUNT_ESTIMATE".equals(i.code()));
    }
    @Test void mappedUnitConversionKeepsOriginalInputAndBothUnitVersionsInAdoptedRow() throws Exception {
        var target=lookup.convert(BigDecimal.ONE,"KWH","KWH",MONTH.atStartOfDay()).orElseThrow().targetUnit();
        var input=new EnergyCatalogLookup.ApprovedUnit("mwh","MWH","mwh-v1",1,"MWh","ENERGY","KWH",new BigDecimal("1000"),
                "SCALE",null,"STANDARD","依据",MONTH.atStartOfDay(),null);
        when(lookup.convert(any(),eq("MWH"),eq("KWH"),any())).thenAnswer(i->Optional.of(
                new EnergyCatalogLookup.UnitConversion(i.getArgument(0),input,((BigDecimal)i.getArgument(0)).multiply(new BigDecimal("1000")),target)));
        byte[] bytes;
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            book.createSheet("输入").createRow(0).createCell(0).setCellValue("1.25");book.write(out);bytes=out.toByteArray();
        }
        var request=new ReportingWorkbookService.Request("CELL_MAPPING",List.of(new ReportingWorkbookService.Mapping(
                "SOURCE","输入","A1","MWH",MONTH,MONTH.plusMonths(1),"NATIVE_QUANTITY","原工作簿")));
        var result=service.preview(1,ROLES,"B","BATCH",file(bytes),request);
        assertThat(result.rows()).singleElement().satisfies(row->{
            assertThat(row.quantity()).isEqualByComparingTo("1250");
            assertThat(row.note()).contains("原值 1.25 MWH","填入 1250.00 KWH","单位版本 mwh-v1 → uv");
        });
    }
    @Test void ordinaryHyperlinkIsIgnoredButExternalDataRelationshipIsRejected() throws Exception {
        byte[] bytes;
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var cell=book.createSheet("输入").createRow(0).createCell(0);cell.setCellValue("88375");
            var link=book.getCreationHelper().createHyperlink(org.apache.poi.common.usermodel.HyperlinkType.URL);
            link.setAddress("https://invalid.example/never-followed");cell.setHyperlink(link);book.write(out);bytes=out.toByteArray();
        }
        var request=new ReportingWorkbookService.Request("CELL_MAPPING",List.of(new ReportingWorkbookService.Mapping(
                "SOURCE","输入","A1","KWH",MONTH,MONTH.plusMonths(1),"NATIVE_QUANTITY","原工作簿")));
        var preview=service.preview(1,ROLES,"B","BATCH",file(bytes),request);
        assertThat(preview.rows()).singleElement().satisfies(r->assertThat(r.quantity()).isEqualByComparingTo("88375"));
        assertThat(preview.toString()).doesNotContain("invalid.example");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes));var out=new ByteArrayOutputStream()) {
            book.getSheetAt(0).getPackagePart().addExternalRelationship("https://invalid.example/data",
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image");book.write(out);bytes=out.toByteArray();
        }
        var external=bytes;
        assertThatThrownBy(()->service.preview(1,ROLES,"B","BATCH",file(external),request))
                .isInstanceOf(com.platform.framework.exception.BusinessException.class);
    }
    @Test void selectedFormulaAndMissingCellNeverUseCachedResultOrZero() throws Exception {
        byte[] file;
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            book.createSheet("输入").createRow(0).createCell(0).setCellFormula("1+2");book.write(out);file=out.toByteArray();
        }
        for(String cell:List.of("A1","B1")){
            var request=new ReportingWorkbookService.Request("CELL_MAPPING",List.of(new ReportingWorkbookService.Mapping("SOURCE","输入",cell,"KWH",MONTH,MONTH.plusMonths(1),"NATIVE_QUANTITY","原工作簿")));
            var preview=service.preview(1,ROLES,"B","BATCH",file(file),request);
            assertThat(preview.rows()).isEmpty();assertThat(preview.issues()).isNotEmpty();
        }
    }
    @Test void doesNotAcceptForeignSourceOrExponentOutsideQuantityPrecision() throws Exception {
        byte[] file;
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            book.createSheet("输入").createRow(0).createCell(0).setCellValue("1E99999999");book.write(out);file=out.toByteArray();
        }
        var request=new ReportingWorkbookService.Request("CELL_MAPPING",List.of(new ReportingWorkbookService.Mapping("SOURCE","输入","A1","KWH",MONTH,MONTH.plusMonths(1),"NATIVE_QUANTITY","原工作簿")));
        assertThat(service.preview(1,ROLES,"B","BATCH",file(file),request).issues()).isNotEmpty();
    }
    @Test void explicitOriginalDataYearCannotBeRelabelledAsAnotherAccountingPeriod() throws Exception {
        byte[] bytes;
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("8-电力");sheet.createRow(1).createCell(20).setCellValue("2021年数据");
            var row=sheet.createRow(4);row.createCell(19).setCellValue("kWh");row.createCell(20).setCellValue(88375);book.write(out);bytes=out.toByteArray();
        }
        var request=new ReportingWorkbookService.Request("CELL_MAPPING",List.of(new ReportingWorkbookService.Mapping("SOURCE","8-电力","U5","KWH",MONTH,MONTH.plusMonths(1),"NATIVE_QUANTITY","原工作簿")));
        var preview=service.preview(1,ROLES,"B","BATCH",file(bytes),request);
        assertThat(preview.rows()).isEmpty();assertThat(preview.issues()).anyMatch(i->i.message().contains("2021年"));
    }
    @Test void explicitMonetaryOrUnknownUnitCannotBeRelabelledAsPhysicalEnergy() throws Exception {
        for(String originalUnit:List.of("元","万元","待确认单位")) {
            byte[] bytes;
            try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
                var row=book.createSheet("8-电力").createRow(4);
                row.createCell(19).setCellValue(originalUnit);row.createCell(20).setCellValue("88375");
                book.write(out);bytes=out.toByteArray();
            }
            var request=new ReportingWorkbookService.Request("CELL_MAPPING",List.of(new ReportingWorkbookService.Mapping(
                    "SOURCE","8-电力","U5","KWH",MONTH,MONTH.plusMonths(1),"NATIVE_QUANTITY","原工作簿")));
            var preview=service.preview(1,ROLES,"B","BATCH",file(bytes),request);
            assertThat(preview.rows()).as("明确标注的单位不能被忽略: %s",originalUnit).isEmpty();
            assertThat(preview.issues()).anyMatch(i->i.message().contains(originalUnit));
        }
        verify(lookup,never()).convert(any(),any(),any(),any());
    }
    private static MockMultipartFile file(byte[] bytes){return new MockMultipartFile("file","input.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes);}
}
