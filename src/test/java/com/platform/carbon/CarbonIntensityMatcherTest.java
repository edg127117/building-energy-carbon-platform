package com.platform.carbon;

import com.platform.hvac.model.entity.Building;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static com.platform.carbon.CarbonIntensityContracts.*;
import static org.assertj.core.api.Assertions.*;

class CarbonIntensityMatcherTest {
    static final LocalDate START=LocalDate.of(2026,1,1),END=START.plusYears(1);
    static Rule rule(String region,String category,String time,LocalDate from,LocalDate to,String constraint) {
        return new Rule(region,category,null,"标准分区",new BigDecimal(constraint),new BigDecimal("35.56"),new BigDecimal("20.72"),
                "CO2_ONLY","WHOLE_BUILDING_ENERGY","BUILDING_AREA","","",time,from,to);
    }
    static Version version(String catalog,String id,int revision,Rule... entries) {
        return new Version(catalog,id,revision,"测试标准","测试版本","test://source",START,7,null,"hash-"+id,List.of(entries));
    }
    static Building building(String region,String type) {
        Building b=new Building();b.setBuildingId("B");b.setBuildingName("办公楼名称不能代替用途");b.setRegionCode(region);b.setBuildingType(type);b.setClimateZone("夏热冬冷");return b;
    }
    @Test void matchesUniqueRegionAndCategoryWithoutUsingNameClimateOrSize() {
        var catalog=List.of(version("js","v1",1,rule("3201","办公","KNOWN",START,null,"56.71")));
        var b=building("320115","办公");
        assertThat(CarbonIntensityMatcher.match(catalog,b,START,END).status()).isEqualTo("MATCHED");
        b.setTotalGfa(new BigDecimal("1000000"));
        assertThat(CarbonIntensityMatcher.match(catalog,b,START,END).reference().rule().constraintValue()).isEqualByComparingTo("56.71");
        assertThat(CarbonIntensityMatcher.match(catalog,building("450103","办公"),START,END).status()).isEqualTo("NO_APPLICABLE_REFERENCE");
        assertThat(CarbonIntensityMatcher.match(catalog,building("320000","办公"),START,END).reference()).isNull();
        assertThat(CarbonIntensityMatcher.match(catalog,building("320115","医疗"),START,END).reference()).isNull();
    }
    @Test void supportsNanningByDataAndUsesHistoricalVersionsInsteadOfLatestVersion() {
        var versions=List.of(version("nn","v2",2,rule("4501","办公","KNOWN",LocalDate.of(2027,1,1),null,"70")),
                version("nn","v1",1,rule("4501","办公","KNOWN",START,null,"60")));
        assertThat(CarbonIntensityMatcher.match(versions,building("450103","办公"),START,END).reference().versionId()).isEqualTo("v1");
        assertThat(CarbonIntensityMatcher.match(versions,building("450103","办公"),END,END.plusYears(1)).reference().versionId()).isEqualTo("v2");
        assertThat(CarbonIntensityMatcher.match(versions,building("450103","办公"),START.minusYears(1),START).reference()).isNull();
    }
    @Test void unknownTimeIsVisibleButNeverMatchedToAllYearsAndPublicationIsNotApplicability() {
        var v=version("js","v1",1,rule("3201","办公","PENDING",null,null,"56.71"));
        var match=CarbonIntensityMatcher.match(List.of(v),building("320115","办公"),START.minusYears(5),START.minusYears(4));
        assertThat(match.status()).isEqualTo("APPLICABILITY_TIME_UNCLEAR");
        assertThat(match.reference().versionId()).isEqualTo("v1");
        assertThatCode(()->CarbonIntensityCatalogService.validate(v.entries().getFirst())).doesNotThrowAnyException();
        assertThatThrownBy(()->CarbonIntensityCatalogService.validate(rule("3201","办公","PENDING",START,null,"56.71"))).hasMessageContaining("推定日期");
    }
    @Test void expiredSuccessorDoesNotResurrectOpenEndedOldVersionAndAdministrativeCodesAreNormalized() {
        var versions=List.of(version("nn","v2",2,rule("450100","办公","KNOWN",LocalDate.of(2025,1,1),LocalDate.of(2026,1,1),"70")),
                version("nn","v1",1,rule("4501","办公","KNOWN",LocalDate.of(2024,1,1),null,"60")));
        assertThat(versions.getFirst().entries().getFirst().regionPrefix()).isEqualTo("4501");
        assertThat(CarbonIntensityMatcher.match(versions,building("450103","办公"),START,END).reference()).isNull();
    }
    @Test void overlappingProvinceAndCityRulesDoNotAcquireImplicitPriorityFromTheirDates() {
        var rules=version("nn","v1",1,rule("45","办公","KNOWN",START.minusYears(1),null,"70"),rule("4501","办公","KNOWN",START,null,"60"));
        assertThat(CarbonIntensityMatcher.match(List.of(rules),building("450103","办公"),START,END).status()).isEqualTo("MULTIPLE_APPLICABLE_REFERENCES");
    }
    @Test void ambiguousUseConflictingCatalogsAndWithinYearVersionChangesAreExplicit() {
        var v=version("js","v1",1,rule("3201","办公","KNOWN",START,null,"56.71"));
        var mixed=building("320115","综合");
        assertThat(CarbonIntensityMatcher.match(List.of(v),mixed,START,END).status()).isEqualTo("BUILDING_CATEGORY_AMBIGUOUS");
        mixed.setCarbonEvaluationCategory("办公");
        assertThat(CarbonIntensityMatcher.match(List.of(v),mixed,START,END).status()).isEqualTo("MATCHED");
        assertThat(CarbonIntensityMatcher.match(List.of(v,version("other","v2",1,v.entries().getFirst())),mixed,START,END).status()).isEqualTo("MULTIPLE_APPLICABLE_REFERENCES");
        var changed=version("js","v3",2,rule("3201","办公","KNOWN",START.plusMonths(5),null,"65"));
        assertThat(CarbonIntensityMatcher.match(List.of(v,changed),mixed,START,END).status()).isEqualTo("PERIOD_VERSION_CHANGE");
    }
    @Test void zeroAndOver100AreRealValuesWhileMonthlyAndMismatchedBasisHaveNoPercentage() {
        var match=CarbonIntensityMatcher.match(List.of(version("js","v1",1,rule("3201","办公","KNOWN",START,null,"56.71"))),building("320115","办公"),START,END);
        var zero=actual("0","CO2_ONLY");
        assertThat(CarbonIntensityService.evaluate("B","FORMAL_YEAR",START,END,"Asia/Shanghai",zero,match).percentage()).isEqualByComparingTo("0");
        var over=actual("85.065","CO2_ONLY");
        assertThat(CarbonIntensityService.evaluate("B","FORMAL_YEAR",START,END,"Asia/Shanghai",over,match).percentage()).isEqualByComparingTo("150");
        assertThat(CarbonIntensityService.evaluate("B","MONTH",START,START.plusMonths(1),"Asia/Shanghai",over,match).percentage()).isNull();
        assertThat(CarbonIntensityService.evaluate("B","MONTHLY_YTD",START,START.plusMonths(9),"Asia/Shanghai",over,match).percentage()).isEqualByComparingTo("150");
        assertThat(CarbonIntensityService.evaluate("B","FORMAL_YEAR",START,END,"Asia/Shanghai",actual("85","GHG_CO2E"),match).reasons()).contains("GAS_BASIS_MISMATCH_OR_UNKNOWN");
        assertThat(CarbonIntensityService.evaluate("B","FORMAL_YEAR",START,END,"Asia/Shanghai",StoredCarbonIntensityReader.unavailable("MISSING"),match).percentage()).isNull();
    }
    @Test void uniqueUndatedDirectoryAutomaticallyServesAnnualAndYtdReferencesInEitherRegion() {
        for(String region:List.of("3201","4501")) for(String time:List.of("PENDING","NOT_STATED")) {
            var entry=rule(region,"办公",time,null,null,"56.71");
            var profile=building(region+"03","办公");
            for(String mode:List.of("FORMAL_YEAR","MONTHLY_YTD")) {
                var end="FORMAL_YEAR".equals(mode)?END:START.plusMonths(9);
                var match=CarbonIntensityMatcher.match(List.of(version("catalog","source-v1",1,entry)),profile,START,end);
                var view=CarbonIntensityService.evaluate("B",mode,START,end,"Asia/Shanghai",actual("28.355","CO2_ONLY"),match);
                assertThat(view.matchStatus()).isEqualTo("APPLICABILITY_TIME_UNCLEAR");
                assertThat(view.percentage()).isEqualByComparingTo("50");
                assertThat(view.percentageStatus()).isEqualTo("AVAILABLE");assertThat(view.percentageReasons()).isEmpty();
                assertThat(view.comparisonStatus()).isEqualTo("NOT_COMPARABLE");assertThat(view.reasons()).contains("APPLICABILITY_TIME_UNCLEAR");
                assertThat(view.percentageBasis()).isEqualTo("FORMAL_YEAR".equals(mode)?"ANNUAL_REFERENCE":"YTD_SHARE_OF_ANNUAL_REFERENCE");
                assertThat(view.reference().rule().applicableFrom()).isNull();
            }
        }
    }
    @Test void missingOrAmbiguousDirectoryNeverGuessesAReferenceOrPercentage() {
        var one=version("one","v1",1,rule("4501","办公","PENDING",null,null,"56.71"));
        for(var entries:List.of(List.<Version>of(),List.of(one,version("other","v2",1,one.entries().getFirst())))) {
            var match=CarbonIntensityMatcher.match(entries,building("450103","办公"),START,END);
            var view=CarbonIntensityService.evaluate("B","MONTHLY_YTD",START,START.plusMonths(9),"Asia/Shanghai",actual("10","CO2_ONLY"),match);
            assertThat(view.reference()).isNull();assertThat(view.percentage()).isNull();assertThat(view.percentageStatus()).isEqualTo("UNAVAILABLE");
        }
    }
    @Test void latestUndatedRevisionReplacesItsOwnPriorReferenceWithoutCompetingWithHistory() {
        var older=version("nn","v1",1,rule("4501","办公","KNOWN",START.minusYears(1),null,"60"));
        var newer=version("nn","v2",2,rule("4501","办公","PENDING",null,null,"70"));
        var match=CarbonIntensityMatcher.match(List.of(older,newer),building("450103","办公"),START,END);
        assertThat(match.status()).isEqualTo("APPLICABILITY_TIME_UNCLEAR");assertThat(match.reference().versionId()).isEqualTo("v2");
        assertThat(match.reference().rule().applicableFrom()).isNull();
        assertThat(CarbonIntensityService.evaluate("B","FORMAL_YEAR",START,END,"Asia/Shanghai",actual("35","CO2_ONLY"),match).percentage()).isEqualByComparingTo("50");
        var conflict=version("other","v3",1,newer.entries().getFirst());
        assertThat(CarbonIntensityMatcher.match(List.of(older,newer,conflict),building("450103","办公"),START,END).status()).isEqualTo("MULTIPLE_APPLICABLE_REFERENCES");
    }
    @Test void automaticReferenceKeepsGasUnitAreaAndMissingDataChecksWhileSeparatingStandardBoundary() {
        var standard=new Rule("4501","办公",null,"分区",new BigDecimal("56.71"),new BigDecimal("35.56"),new BigDecimal("20.72"),
                "CO2_ONLY","STANDARD_SPECIFIC","BUILDING_AREA","标准专门边界","","PENDING",null,null);
        var match=CarbonIntensityMatcher.match(List.of(version("nn","v1",1,standard)),building("450103","办公"),START,END);
        var view=CarbonIntensityService.evaluate("B","MONTHLY_YTD",START,START.plusMonths(9),"Asia/Shanghai",actual("28.355","CO2_ONLY"),match);
        assertThat(view.percentage()).isEqualByComparingTo("50");assertThat(view.percentageReasons()).isEmpty();
        assertThat(view.reasons()).contains("BOUNDARY_MISMATCH_OR_UNKNOWN","APPLICABILITY_TIME_UNCLEAR","YTD_NOT_ANNUAL_COMPARISON");
        assertThat(CarbonIntensityService.evaluate("B","MONTHLY_YTD",START,END,"Asia/Shanghai",actual("0","CO2_ONLY"),match).percentage()).isEqualByComparingTo("0");
        for(String gas:List.of("UNKNOWN","GHG_CO2E","EVIDENCE_INVALID"))
            assertThat(CarbonIntensityService.evaluate("B","MONTHLY_YTD",START,END,"Asia/Shanghai",actual("20",gas),match).percentage()).isNull();
        var unit=new Actual(BigDecimal.TEN,"kg/person","AVAILABLE","CO2_ONLY","UNKNOWN","BUILDING_AREA","batch","hash","area");
        assertThat(CarbonIntensityService.evaluate("B","FORMAL_YEAR",START,END,"Asia/Shanghai",unit,match).percentageReasons()).contains("UNIT_MISMATCH");
        var area=new Actual(BigDecimal.TEN,"kgCO2e/m²","AVAILABLE","CO2_ONLY","UNKNOWN","UNKNOWN","batch","hash",null);
        assertThat(CarbonIntensityService.evaluate("B","FORMAL_YEAR",START,END,"Asia/Shanghai",area,match).percentageReasons()).contains("AREA_BASIS_MISMATCH_OR_UNKNOWN");
        assertThat(CarbonIntensityService.evaluate("B","FORMAL_YEAR",START,END,"Asia/Shanghai",StoredCarbonIntensityReader.unavailable("MISSING_MONTHS"),match).percentage()).isNull();
    }
    static Actual actual(String value,String gas) { return new Actual(new BigDecimal(value),"kgCO2e/m²","AVAILABLE",gas,"WHOLE_BUILDING_ENERGY","BUILDING_AREA","b","h","a"); }
}
