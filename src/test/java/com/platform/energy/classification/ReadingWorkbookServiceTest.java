package com.platform.energy.classification;
import com.platform.energy.period.EnergyPeriodAuthorization;
import com.platform.framework.exception.BusinessException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadingWorkbookServiceTest {
    private final EnergyPeriodAuthorization auth=mock(EnergyPeriodAuthorization.class);
    private final ClassificationRepository repository=mock(ClassificationRepository.class);
    private final ClassificationService classifications=mock(ClassificationService.class);
    private final ReadingWorkbookService service=new ReadingWorkbookService(auth,repository,classifications);
    @BeforeEach void prepare(){when(repository.buildingExists("B")).thenReturn(true);}
    @Test void parsesCurrentBuildingCodesWithoutGuessingScaleAndDelegatesOriginalPreflight() throws Exception {
        when(repository.pointIdForCode("B","E001")).thenReturn("POINT-1");
        when(classifications.preview(anyLong(),anyCollection(),eq("B"),any())).thenReturn(new ClassificationContracts.ImportView(null,"PREVIEW",0,List.of()));
        var result=service.preview(1L,Set.of("ENERGY_MANAGER"),"B",file(null),"key","依据");
        assertThat(result.preview().status()).isEqualTo("PREVIEW");
        assertThat(result.request().readings().getFirst().pointId()).isEqualTo("POINT-1");
        assertThat(result.request().readings().getFirst().multiplier()).isNull();
        assertThat(result.request().readings().getFirst().status()).isEqualTo("PENDING_EXPERT");
        verify(classifications).preview(eq(1L),anyCollection(),eq("B"),eq(result.request()));
        verifyNoMoreInteractions(classifications);
    }
    @Test void formulaAndUnknownBuildingPointStayInvalidWithoutWriting() throws Exception {
        var result=service.preview(1L,Set.of("ENERGY_MANAGER"),"B",file("1+2"),"key","依据");
        assertThat(result.request()).isNull();assertThat(result.preview().issues()).anyMatch(i->i.message().contains("公式"));
        var missing=service.preview(1L,Set.of("ENERGY_MANAGER"),"B",file(null),"key","依据");
        assertThat(missing.request()).isNull();assertThat(missing.preview().issues()).anyMatch(i->i.message().contains("当前建筑"));
        verifyNoInteractions(classifications);
    }
    @Test void scopeIsCheckedBeforeFileIsRead() {
        doThrow(new BusinessException(403,"DENIED","禁止")).when(auth).checkBuilding(1L,Set.of("ENERGY_MANAGER"),"B");
        var upload=mock(org.springframework.web.multipart.MultipartFile.class);
        assertThatThrownBy(()->service.preview(1L,Set.of("ENERGY_MANAGER"),"B",upload,"key","依据")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(upload,repository,classifications);
    }
    @Test void rejectsLegacyXlsAndGeneratedTemplateHasNoGuessedBusinessRows() throws Exception {
        assertThatThrownBy(()->service.preview(1L,Set.of("ENERGY_MANAGER"),"B",new MockMultipartFile("file","old.xls","application/vnd.ms-excel",new byte[]{1}),"k","原因"))
                .hasMessageContaining(".xlsx");
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(service.template(1L,Set.of("ENERGY_MANAGER"),"B")))) {
            assertThat(workbook.getSheet("人工读数").getLastRowNum()).isZero();
            assertThat(workbook.getSheet("说明").getRow(1).getCell(0).getStringCellValue()).contains("时区");
        }
    }
    private MockMultipartFile file(String formula) throws Exception {
        try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=workbook.createSheet("人工读数");var headers=sheet.createRow(0);
            for(int i=0;i<ReadingWorkbookService.HEADERS.size();i++)headers.createCell(i).setCellValue(ReadingWorkbookService.HEADERS.get(i));
            var row=sheet.createRow(1);String[] values={"E001","2026-08-01T00:00:00+08:00","2026-09-01T00:00:00+08:00","ELEC","kWh","PERIOD_TOTAL","","","1000","","false","PENDING_EXPERT","月报","EXACT",""};
            for(int i=0;i<values.length;i++)row.createCell(i).setCellValue(values[i]);
            if(formula!=null)row.getCell(8).setCellFormula(formula);
            workbook.write(out);return new MockMultipartFile("file","readings.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",out.toByteArray());
        }
    }
}
