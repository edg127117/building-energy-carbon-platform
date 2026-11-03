package com.platform.carbon;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.platform.hvac.mapper.BuildingMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import static com.platform.carbon.CarbonModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CarbonIntensityReaderTest {
    @Test void keepsFrozenValueAndReportsEvidenceUnknownInsteadOfRelabelingTheSummary() {
        var carbon=mock(CarbonCalculationRepository.class);var reader=new StoredCarbonIntensityReader(carbon);
        var batch=mock(CalculationBatch.class);when(batch.batchId()).thenReturn("frozen-year");
        when(carbon.findCurrentFormal(anyString(),any(),any(),any(),anyString())).thenReturn(batch);
        when(carbon.listSummaries("frozen-year")).thenReturn(List.of(new SummaryMetric("AREA_INTENSITY","TOTAL",new BigDecimal("8.1234564"),new BigDecimal("8.123456"),"kgCO2e/m²","area-v1",null,"hash")));
        var result=reader.read("B","FORMAL_YEAR",LocalDate.of(2026,1,1),LocalDate.of(2027,1,1),ZoneId.of("Asia/Shanghai"));
        assertThat(result.value()).isEqualByComparingTo("8.123456");assertThat(result.unit()).isEqualTo("kgCO2e/m²");
        assertThat(result.gasBasis()).isEqualTo("UNKNOWN");assertThat(result.denominatorVersionId()).isEqualTo("area-v1");
        verify(carbon).findCurrentFormal("B",PeriodType.YEAR,Instant.parse("2025-12-31T16:00:00Z"),Instant.parse("2026-12-31T16:00:00Z"),"Asia/Shanghai");
    }
    @Test void co2IdentityRequiresExplicitCoverageAndFrozenCo2GwpOne() {
        var node=CarbonEvidence.parse("""
                {"factor":{"resultBasis":"GAS_MASS","gasCode":"CO2","gasCoverage":"CO2_ONLY_ELECTRICITY"},"gwp":{"gasCode":"CO2"},"gwpValue":"1"}
                """);
        assertThat(StoredCarbonIntensityReader.gasBasis(List.of(node))).isEqualTo("CO2_ONLY");
        var mixed=CarbonEvidence.parse("{\"factor\":{\"resultBasis\":\"CO2E_DIRECT\"}}");
        assertThat(StoredCarbonIntensityReader.gasBasis(List.of(node,mixed))).isEqualTo("GHG_CO2E");
        assertThat(StoredCarbonIntensityReader.gasBasis(List.of(CarbonEvidence.object()))).isEqualTo("UNKNOWN");
    }
    @Test void authorizationPrecedesAllBuildingAndResultReads() {
        var auth=mock(CarbonAuthorization.class);var buildings=mock(BuildingMapper.class);var catalog=mock(CarbonIntensityCatalogService.class);var actual=mock(StoredCarbonIntensityReader.class);
        @SuppressWarnings("unchecked") ObjectProvider<CarbonIntensityYtdReader> ytd=mock(ObjectProvider.class);
        var service=new CarbonIntensityService(auth,buildings,catalog,actual,ytd);
        doThrow(CarbonIntensityCatalogService.error(403,"FORBIDDEN","禁止跨建筑")).when(auth).requireReader(7,List.of("BUILDING_OWNER"),"B");
        assertThatThrownBy(()->service.query(7,List.of("BUILDING_OWNER"),"B","FORMAL_YEAR",2026,null,"Asia/Shanghai")).hasMessageContaining("跨建筑");
        verifyNoInteractions(buildings,catalog,actual,ytd);
        assertThatThrownBy(()->service.query(7,List.of(),"B","MONTH",2026,13,"Asia/Shanghai")).hasMessageContaining("月份");
        assertThatThrownBy(()->service.query(7,List.of(),"B","FORMAL_YEAR",2026,1,"Asia/Shanghai")).hasMessageContaining("月份");
    }
    @Test void cumulativeGasIdentityReadsVerifiedFrozenSourceBatchesWithoutRequiringMonthlyIntensity() {
        var carbon=mock(CarbonCalculationRepository.class);var reader=new StoredCarbonIntensityReader(carbon);
        var batch=mock(CalculationBatch.class);when(batch.buildingId()).thenReturn("B");when(batch.resultNature()).thenReturn(ResultNature.FORMAL);
        when(batch.status()).thenReturn("COMPLETED_COMPLETE");when(batch.publicationStatus()).thenReturn("DIRECT");when(batch.detailCount()).thenReturn(1);
        when(carbon.findBatch("source")).thenReturn(batch);
        var node=CarbonEvidence.parse("{\"schemaVersion\":1,\"factor\":{\"resultBasis\":\"GAS_MASS\",\"gasCode\":\"CO2\",\"gasCoverage\":\"CO2_ONLY_ELECTRICITY\"},\"gwp\":{\"gasCode\":\"CO2\"},\"gwpValue\":\"1\"}");
        String json=CarbonEvidence.canonical(node),hash=CarbonCalculationCore.sha256(json);
        when(carbon.listEvidence("source")).thenReturn(List.of(new CarbonCalculationRepository.StoredEvidence("item",json,hash)));
        assertThat(reader.gasBasisForBatches("B",List.of("source"))).isEqualTo("CO2_ONLY");
        verify(carbon,never()).listSummaries(anyString());
        assertThat(reader.gasBasisForBatches("OTHER",List.of("source"))).isEqualTo("UNKNOWN");
        when(carbon.listEvidence("source")).thenReturn(List.of(new CarbonCalculationRepository.StoredEvidence("item",json,"invalid")));
        assertThat(reader.gasBasisForBatches("B",List.of("source"))).isEqualTo("EVIDENCE_INVALID");
    }
}
