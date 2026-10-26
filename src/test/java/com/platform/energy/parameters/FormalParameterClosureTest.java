package com.platform.energy.parameters;

import com.platform.carbon.CarbonCalculationCore;
import com.platform.carbon.CarbonCalculationService;
import com.platform.carbon.CarbonModels.*;
import com.platform.carbon.CarbonRuleService;
import com.platform.carbon.api.CarbonContracts;
import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.energy.catalog.api.EnergyCatalogContracts;
import com.platform.energy.conversion.EnergyConversionService;
import com.platform.energy.conversion.api.EnergyConversionContracts;
import com.platform.energy.reporting.ReportingContracts;
import com.platform.energy.reporting.ReportingCarbonInput;
import com.platform.energy.reporting.ReportingService;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.platform.framework.exception.BusinessException;

import static com.platform.energy.parameters.EnergyParameterPackageContracts.*;
import static org.assertj.core.api.Assertions.*;

/** 材料数值进入真实治理服务；独立测试建筑和标准状态不代表目标楼宇已确认或发布。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:formal_parameter_closure;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "audit-governance.allow-self-approval=false", "telemetry-receipt.retention-cleanup-enabled=false"})
@Sql("classpath:energy-reporting-carbon-schema-test.sql")
@ActiveProfiles("test")
@Transactional
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FormalParameterClosureTest {
    private static final long AUTHOR = 91001, REVIEWER = 91002;
    private static final Set<String> ROLES = Set.of("PLATFORM_ADMIN");
    private static final LocalDateTime FROM = LocalDateTime.of(2021, 4, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2027, 1, 1, 0, 0);
    private static final String BUILDING = "FORMAL_TEST";
    private static final String BASIS = "ISOLATED_TEST_NORMAL_VOLUME";
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EnergyCatalogService catalog;
    @Autowired private EnergyConversionService conversion;
    @Autowired private EnergyParameterPackageService packages;
    @Autowired private CarbonRuleService carbon;
    @Autowired private CarbonCalculationCore core;
    @Autowired private ReportingService reporting;
    @Autowired private ReportingCarbonInput reportingInput;
    @Autowired private CarbonCalculationService calculations;

    @DynamicPropertySource
    static void explicitlyOwnedMysqlOnly(DynamicPropertyRegistry properties) {
        if (!"true".equals(System.getenv("FORMAL_PARAMETER_MYSQL_ISOLATED"))) return;
        String url = System.getenv("FORMAL_PARAMETER_MYSQL_URL");
        if (url == null || !url.startsWith("jdbc:mysql://127.0.0.1:39493/iot_platform?")) {
            throw new IllegalStateException("FORMAL_PARAMETER_OWNED_LOOPBACK_REQUIRED");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        properties.add("spring.datasource.username", () -> "root");
        properties.add("spring.datasource.password", () -> System.getenv("FORMAL_PARAMETER_MYSQL_PASSWORD"));
        properties.add("spring.sql.init.mode", () -> "never");
    }

    @BeforeEach
    void isolateActorsAndRegisterEngineDefinitions() {
        for (long actor : List.of(AUTHOR, REVIEWER)) {
            jdbc.update("INSERT INTO sys_user(id,username,password,nickname,status,del_flag) VALUES (?,?,?,'隔离验收',1,0)",
                    actor, "formal-parameter-" + actor, "not-a-login-credential");
            for (String duty : List.of("ENERGY_CATALOG_MAINTAIN", "ENERGY_CATALOG_REVIEW",
                    "ENERGY_RULE_MAINTAIN", "ENERGY_RULE_REVIEW", "CARBON_RULE_MAINTAIN",
                    "CARBON_RULE_REVIEW", "CARBON_RULE_ACTIVATE", "ENERGY_LOCK_APPROVE", "CARBON_CALCULATION_RUN")) {
                jdbc.update("INSERT INTO sys_user_backend_duty(assignment_id,user_id,duty_key,status,effective_at,created_by) VALUES (?,?,?,'ACTIVE','2020-01-01',1)",
                        UUID.randomUUID().toString().replace("-", ""), actor, duty);
            }
        }
        jdbc.update("INSERT INTO building(building_id,building_name,building_type,total_gfa,climate_zone,region_code) VALUES (?,'独立参数验收建筑','办公',1000,'夏热冬冷','420000')", BUILDING);
        // 引擎公式对应 V39/V41/V87；业务参数与审核状态只能通过下方治理服务创建。
        formula("CFV_STATIONARY_CO2_V1", "STATIONARY_COMBUSTION_CO2", "CO2_ONLY_FOR_STATIONARY_COMBUSTION");
        formula("CFV_ELECTRICITY_CO2_V1", "PURCHASED_ELECTRICITY_LOCATION_CO2", "CO2_ONLY_ELECTRICITY");
        formula("CFV_HEAT_CO2_V1", "PURCHASED_HEAT_CO2", "CO2_ONLY_HEAT");
    }

    @Test
    @Order(1)
    void importsReviewsAndEvaluatesB01WithoutPromotingDevelopmentSeeds() {
        units();
        item("NATURAL_GAS", "NATURAL_GAS", "STATIONARY_COMBUSTION");
        item("RAW_COAL", "FUEL", "STATIONARY_COMBUSTION");
        var source = source("GB_T_2589_2020", "综合能耗计算通则", "GB/T 2589—2020", 2020);
        var initial = packages.importPackage(AUTHOR, ROLES, request("B01", List.of("STANDARD_COAL_LHV",
                "NATURAL_GAS_LHV_FORMULA", "RAW_COAL_DIRECT_TCE_FORMULA"), source, null, null, null));
        String coal = id(initial, "STANDARD_COAL_LHV"), gasFormula = id(initial, "NATURAL_GAS_LHV_FORMULA");
        String rawFormula = id(initial, "RAW_COAL_DIRECT_TCE_FORMULA");
        assertThat(initial.awaitingIndependentReview()).isTrue();
        assertThatThrownBy(() -> conversion.approveStandardCoal(AUTHOR, ROLES, coal, approval()))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo("BACKOFFICE_SELF_APPROVAL_DENIED"));
        conversion.approveStandardCoal(REVIEWER, ROLES, coal, approval());
        conversion.approveFormula(REVIEWER, ROLES, gasFormula, approval());
        conversion.approveFormula(REVIEWER, ROLES, rawFormula, approval());
        var dependencies = new DependenciesRequest(coal, gasFormula, rawFormula, "RAW_COAL");
        var gasSource = source("CONFIRMED_WORKBOOK_GAS", "已确认工作簿天然气参数", "工作簿参数页 E14/F14/H14", 2022);
        var gas = packages.importPackage(AUTHOR, ROLES, request("B01", List.of("NATURAL_GAS_LHV_PARAMETER"), gasSource, dependencies, null, null));
        var raw = packages.importPackage(AUTHOR, ROLES, request("B01", List.of("RAW_COAL_DIRECT_TCE_PARAMETER"), source, dependencies, null, null));
        conversion.approveParameter(REVIEWER, ROLES, id(gas, "NATURAL_GAS_LHV_PARAMETER"), approval());
        conversion.approveParameter(REVIEWER, ROLES, id(raw, "RAW_COAL_DIRECT_TCE_PARAMETER"), approval());
        var gasResult = conversion.evaluateFormalRules(AUTHOR, ROLES,
                input("NATURAL_GAS", "53173", "NM3", "LOWER_HEATING_VALUE"), at(2023));
        BigDecimal independent = new BigDecimal("5.3173").multiply(new BigDecimal("389.31"))
                .divide(new BigDecimal("29.3076"), MathContext.DECIMAL128);
        assertThat(gasResult.tce().setScale(9, RoundingMode.HALF_UP))
                .isEqualByComparingTo(independent.setScale(9, RoundingMode.HALF_UP));
        assertThat(gasResult.parameterVersionId()).isEqualTo(id(gas, "NATURAL_GAS_LHV_PARAMETER"));
        assertThat(gasResult.standardCoalVersionId()).isEqualTo(coal);
        assertThat(conversion.evaluateFormalRules(AUTHOR, ROLES,
                input("RAW_COAL", "1000", "KG", "DIRECT_TCE_FACTOR"), at(2023)).tce())
                .isEqualByComparingTo("0.7143");
        assertThat(conversion.listStandardCoalVersions(ROLES).stream()
                .filter(v -> v.versionId().equals("SCLV_STANDARD_1")).findFirst().orElseThrow().status())
                .isEqualTo("PENDING_EXPERT");
        assertThatThrownBy(() -> conversion.evaluateFormalRules(AUTHOR, ROLES,
                input("NATURAL_GAS", "53173", "M3", "LOWER_HEATING_VALUE"), at(2023)))
                .hasMessageContaining("单位");
    }

    @Test
    @Order(2)
    void reviewsActivatesAndTracesB02MaterialResultsWithMissingDenominatorsKeptUnavailable() {
        units();
        item("ELECTRICITY", "ELECTRICITY", "PURCHASED_ELECTRICITY");
        item("HEAT", "HEAT", "PURCHASED_HEAT");
        item("NATURAL_GAS", "NATURAL_GAS", "STATIONARY_COMBUSTION");
        var electricity = activate(request("B02", List.of("ELECTRICITY_FACTOR"),
                source("MEE_2023_43", "工作簿外购电力因子", "环办气候函〔2023〕43号，工作簿参数页 B67", 2023),
                null, new ElectricityContextRequest("NATIONAL", null, null, 2022), null));
        var heat = activate(request("B02", List.of("HEAT_FACTOR"),
                source("CONFIRMED_HEAT_011", "公共建筑运营企业温室气体排放核算方法和报告指南", "工作簿参数页 B71/J69", 2015), null, null, null));
        var gas = activate(request("B02", List.of("NATURAL_GAS_STATIONARY_FACTOR"),
                source("CONFIRMED_WORKBOOK_GAS", "工作簿固定源天然气", "工作簿参数清单E14/F14/H14", 2022), null, null, BASIS));
        var gasActivity = activity("NATURAL_GAS", "53173", "NM3", 2022);
        var gasResult = core.calculate(gasActivity, core.match(gasActivity, "420000", ResultNature.FORMAL, List.of(gas)), gwp());
        assertThat(gasResult.exactEmissionKgCo2e().divide(new BigDecimal("1000")).setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("114.97");
        // 工作簿注明该电量由金额、单价折算；这里仅核对算术，不把它伪装为正式原生账单。
        var electricActivity = activity("ELECTRICITY", "928127", "KWH", 2022);
        var electricityResult = core.calculate(electricActivity,
                core.match(electricActivity, "420000", ResultNature.FORMAL, List.of(electricity)), gwp());
        assertThat(electricityResult.exactEmissionKgCo2e()).isEqualByComparingTo("529310.8281");
        assertThat(electricityResult.exactEmissionKgCo2e().divide(new BigDecimal("1000")).setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("529.31");
        assertThat(electricityResult.factor().sourceVersionId()).isEqualTo(electricity.sourceVersionId());
        assertThat(electricityResult.gwpVersionId()).isEqualTo("CGWP_CO2_V1");
        assertThat(electricity.dataYear()).isNull();
        assertThat(electricity.accountingYear()).isEqualTo(2022);
        var heatActivity = activity("HEAT", "1", "GJ", 2022);
        assertThat(core.calculate(heatActivity, core.match(heatActivity, "420000", ResultNature.FORMAL, List.of(heat)), gwp())
                .exactEmissionKgCo2e()).isEqualByComparingTo("110");
        var summaries = core.summarize(List.of(electricityResult), PeriodType.YEAR, null, null);
        assertThat(summaries.stream().filter(m -> m.denominatorVersionId() == null && m.unavailableReason() != null))
                .isNotEmpty();
        var hubei = activate(request("B02", List.of("HUBEI_ELECTRICITY_FACTOR_2023"),
                source("MEE_2025_47", "2023年电力二氧化碳排放因子", "生态环境部、国家统计局公告2025年第47号", 2025),
                null, new ElectricityContextRequest("PROVINCE", "420000", 2023, 2025), null));
        var hubeiActivity = activity("ELECTRICITY", "1000", "KWH", 2025);
        assertThat(core.calculate(hubeiActivity, core.match(hubeiActivity, "420000", ResultNature.FORMAL, List.of(hubei)), gwp())
                .exactEmissionKgCo2e()).isEqualByComparingTo("404.4");
        assertThatThrownBy(() -> core.match(hubeiActivity, "320000", ResultNature.FORMAL, List.of(hubei)))
                .hasMessageContaining("因子");
        assertThatThrownBy(() -> core.match(activity("ELECTRICITY", "1000", "KWH", 2024), "420000", ResultNature.FORMAL, List.of(hubei)))
                .hasMessageContaining("因子");
    }

    @Test
    @Order(3)
    void sealsIndependentlyReviewedNativeBillAndCalculatesExclusionWithoutTce() {
        units();
        item("ELECTRICITY", "ELECTRICITY", "PURCHASED_ELECTRICITY");
        var factor = activate(request("B02", List.of("ELECTRICITY_FACTOR"),
                source("MEE_2023_43", "工作簿外购电力因子", "环办气候函〔2023〕43号", 2023),
                null, new ElectricityContextRequest("NATIONAL", null, null, 2022), null));
        // 碳批次使用独立事务，必须提交隔离夹具才能验证真实读链；该测试最后执行，数据库仅供本类使用。
        TestTransaction.flagForCommit();
        TestTransaction.end();
        var month = LocalDate.of(2022, 1, 1);
        var config = new ReportingContracts.SourceConfig("独立测试电力账单", "ELECTRICITY", "KWH", "BILL",
                "PURCHASED_ELECTRICITY", "PRIMARY", "WHOLE_BUILDING", "NO_METER", "Asia/Shanghai",
                month, month.plusMonths(1), "合成验收凭证，不代表现场账单", "验证来源审核", null, null, null, null);
        var source = reporting.createSource(AUTHOR, ROLES, BUILDING, new ReportingContracts.CreateSource(config, "test-source"));
        String sourceId = source.sourceId();
        reporting.sourceCommand(AUTHOR, ROLES, BUILDING, source.sourceId(), "submit", command(0, "source-submit"));
        assertThatThrownBy(() -> reporting.sourceCommand(AUTHOR, ROLES, BUILDING, sourceId, "approve", command(1, "source-self")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo("REPORTING_REVIEW_SEPARATION"));
        source = reporting.sourceCommand(REVIEWER, ROLES, BUILDING, source.sourceId(), "approve", command(1, "source-review"));
        var batch = reporting.createBatch(AUTHOR, ROLES, BUILDING, new ReportingContracts.CreateBatch(month, "合成闭环验收", "test-batch", null));
        String batchId = batch.batchId();
        var estimated = new ReportingContracts.Row(source.sourceId(), source.revision(), new BigDecimal("1000"),
                "CONFIRMED", month, month.plusMonths(1), "合成金额估算凭证", null, null, null, null, "MONEY_ESTIMATE");
        reporting.saveBatch(AUTHOR, ROLES, BUILDING, batchId, new ReportingContracts.SaveBatch(0L, List.of(estimated), "核对估算边界"));
        assertThat(reporting.preview(AUTHOR, ROLES, BUILDING, batchId).ready()).isFalse();
        assertThatThrownBy(() -> reporting.batchCommand(AUTHOR, ROLES, BUILDING, batchId, "submit", command(1, "estimate-submit")))
                .isInstanceOf(BusinessException.class);
        var nativeRow = new ReportingContracts.Row(source.sourceId(), source.revision(), new BigDecimal("1000"),
                "CONFIRMED", month, month.plusMonths(1), "独立合成原生用量凭证", null, new BigDecimal("100"),
                "合成测试排除项", "独立合成排除凭证", "NATIVE_QUANTITY");
        reporting.saveBatch(AUTHOR, ROLES, BUILDING, batchId, new ReportingContracts.SaveBatch(1L, List.of(nativeRow), "验证原生量与排除"));
        assertThat(reporting.preview(AUTHOR, ROLES, BUILDING, batchId).ready()).isTrue();
        reporting.batchCommand(AUTHOR, ROLES, BUILDING, batchId, "submit", command(2, "batch-submit"));
        assertThatThrownBy(() -> reporting.batchCommand(AUTHOR, ROLES, BUILDING, batchId, "approve", command(3, "batch-self")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo("REPORTING_REVIEW_SEPARATION"));
        reporting.batchCommand(REVIEWER, ROLES, BUILDING, batchId, "approve", command(3, "batch-review"));
        var sealed = reporting.batchCommand(REVIEWER, ROLES, BUILDING, batchId, "seal", command(4, "batch-seal"));
        assertThat(sealed.snapshot().nativeStatus()).isEqualTo("COMPLETE");
        assertThat(sealed.snapshot().tceStatus()).isEqualTo("NOT_CALCULATED");
        var zone = ZoneId.of("Asia/Shanghai");
        var run = new CarbonContracts.RunCalculationRequest(BUILDING, "MONTH", month.atStartOfDay(zone).toInstant(),
                month.plusMonths(1).atStartOfDay(zone).toInstant(), zone.getId(), "FORMAL", "native-bill-carbon");
        var activities = reportingInput.read(BUILDING, run.startInclusive(), run.endExclusive(), 100).activities();
        assertThat(activities).singleElement().satisfies(activity -> {
            assertThat(activity.quantity()).isEqualByComparingTo("900");
            assertThat(core.calculate(activity, core.match(activity, "420000", ResultNature.FORMAL, List.of(factor)), gwp())
                    .exactEmissionKgCo2e()).isEqualByComparingTo("513.27");
        });
        // 完整迁移结构与碳批次落库仅在显式开启、校验所有权的独立 MySQL 验收中执行。
        if (!"true".equals(System.getenv("FORMAL_PARAMETER_MYSQL_ISOLATED"))) return;
        var result = calculations.run(REVIEWER, ROLES, run);
        assertThat(result.batch().status()).isEqualTo("COMPLETED_COMPLETE");
        assertThat(result.failures()).isEmpty();
        assertThat(result.items()).singleElement().satisfies(row -> {
            assertThat(row.activityQuantity()).isEqualByComparingTo("900");
            assertThat(row.emissionKgCo2e()).isEqualByComparingTo("513.27");
            assertThat(row.factorVersionId()).isEqualTo(factor.factorVersionId());
            var evidence = calculations.evidence(REVIEWER, ROLES, result.batch().batchId(), row.calculationItemId()).toString();
            assertThat(evidence).contains(batchId, sourceId, "excludedQuantity", "100", "NOT_CALCULATED");
        });
        assertThat(calculations.run(REVIEWER, ROLES, run).batch().batchId()).isEqualTo(result.batch().batchId());
    }

    private static ReportingContracts.Command command(long revision, String key) {
        return new ReportingContracts.Command(revision, "独立验收操作", key);
    }

    private void units() {
        for (String code : List.of("MJ", "GJ", "KGCE", "TCE", "KG", "KWH", "MWH", "NM3", "TEN_THOUSAND_NM3")) {
            var seed = catalog.listUnits(ROLES).stream().filter(v -> code.equals(v.unitCode())).findFirst().orElseThrow();
            var created = catalog.createUnitVersion(AUTHOR, ROLES, new EnergyCatalogContracts.CreateUnitVersionRequest(
                    code, seed.symbol(), seed.unitName(), seed.dimensionCode(), seed.canonicalUnitCode(), seed.scaleFactor(),
                    seed.conversionType(), code.contains("NM3") ? BASIS : null, seed.precision(), "STANDARD",
                    "已确认资料中的量纲；标准体积状态仅为独立测试夹具", FROM, TO));
            catalog.approveUnit(REVIEWER, ROLES, created.versionId(), new EnergyCatalogContracts.ApproveRequest(0, "独立核对单位及固定比例"));
        }
    }

    private void item(String code, String category, String scope) {
        var created = catalog.createItemVersion(AUTHOR, ROLES, new EnergyCatalogContracts.CreateItemVersionRequest(
                code, code, category, List.of(scope), "STANDARD", "已确认资料中的能源身份", FROM, TO));
        catalog.approveItem(REVIEWER, ROLES, created.versionId(), new EnergyCatalogContracts.ApproveRequest(0, "独立核对能源身份和固定源范围"));
    }

    private PackageRequest request(String code, List<String> items, SourceMetadataRequest source,
                                   DependenciesRequest dependencies, ElectricityContextRequest electricity, String condition) {
        var from = electricity != null && electricity.accountingYear() != null ? at(electricity.accountingYear()) : FROM;
        var to = electricity != null && electricity.accountingYear() != null ? from.plusYears(1) : TO;
        return new PackageRequest(code, items, source, from, to, condition, dependencies, electricity, UUID.randomUUID().toString());
    }

    private SourceMetadataRequest source(String code, String name, String reference, int year) {
        return new SourceMetadataRequest(code, name, "用户已确认资料中的发布来源", reference, reference, year,
                null, "仅在资料给定能源、单位、地区和核算年度内使用", "用户已确认材料；独立环境数值核对", "STANDARD");
    }

    private FactorVersion activate(PackageRequest request) {
        var preview = packages.preview(AUTHOR, ROLES, request);
        assertThat(preview.readyForImport()).withFailMessage("%s", preview.issues()).isTrue();
        var imported = packages.importPackage(AUTHOR, ROLES, request);
        var factor = imported.created().stream().filter(v -> v.objectType().equals("CARBON_FACTOR")).findFirst().orElseThrow();
        var reviewed = carbon.reviewFactor(REVIEWER, ROLES, factor.versionId(), new CarbonContracts.ReviewRequest(0, true, "按已确认资料独立审核"));
        return carbon.activateFactor(REVIEWER, ROLES, factor.versionId(), new CarbonContracts.LifecycleRequest(reviewed.configRevision()));
    }

    private void formula(String id, String code, String coverage) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_formula_version WHERE formula_version_id=?", Integer.class, id) != 0) {
            assertThat(jdbc.queryForObject("SELECT algorithm_code FROM biz_carbon_formula_version WHERE formula_version_id=?", String.class, id))
                    .isEqualTo(code + "_V1");
            return;
        }
        jdbc.update("INSERT INTO biz_carbon_formula_version(formula_version_id,formula_code,version_no,algorithm_code,result_basis,gas_coverage,usage_nature,status,effective_from) VALUES (?,?,1,?,'GAS_MASS',?,'FORMAL','ACTIVE','2000-01-01')",
                id, code, code + "_V1", coverage);
    }

    private static String id(ImportView view, String item) {
        return view.created().stream().filter(v -> item.equals(v.itemCode())).findFirst().orElseThrow().versionId();
    }

    private static EnergyConversionContracts.ApproveRequest approval() {
        return new EnergyConversionContracts.ApproveRequest(0, "按已确认资料独立审核数值、量纲、来源和适用期");
    }

    private static EnergyConversionContracts.SimulationRequest input(String energy, String quantity, String unit, String method) {
        return new EnergyConversionContracts.SimulationRequest(BUILDING, energy, new BigDecimal(quantity), unit,
                method, "CALORIFIC_EQUIVALENT", "STATIONARY_COMBUSTION", "GLOBAL", at(2022));
    }

    private static ActivitySegment activity(String energy, String quantity, String unit, int year) {
        var zone = ZoneId.of("Asia/Shanghai");
        return new ActivitySegment("MATERIAL_VECTOR_" + energy + year, BUILDING, PeriodType.YEAR,
                at(year).atZone(zone).toInstant(), at(year + 1).atZone(zone).toInstant(), zone.getId(),
                energy, new BigDecimal(quantity), unit, "SEALED", "COMPLETE", ResultNature.FORMAL, "isolated-material-vector");
    }

    private static GwpVersion gwp() {
        return new GwpVersion("CGWP_CO2_V1", "CO2", BigDecimal.ONE, "CO2定义值", UsageNature.FORMAL, at(2000), null);
    }

    private static LocalDateTime at(int year) { return LocalDate.of(year, 1, 1).atStartOfDay(); }
}
