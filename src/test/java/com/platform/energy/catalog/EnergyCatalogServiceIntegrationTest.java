package com.platform.energy.catalog;

import com.platform.energy.catalog.api.EnergyCatalogContracts.ApproveRequest;
import com.platform.energy.catalog.api.EnergyCatalogContracts.CreateBindingVersionRequest;
import com.platform.energy.catalog.api.EnergyCatalogContracts.CreateCompatibilityVersionRequest;
import com.platform.energy.catalog.api.EnergyCatalogContracts.CreateItemVersionRequest;
import com.platform.energy.catalog.api.EnergyCatalogContracts.CreateUnitVersionRequest;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.energymetadata.EnergyPointProfileService;
import com.platform.iot.energymetadata.api.EnergyPointProfileContracts.CreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EnergyCatalogServiceIntegrationTest {
    private static final long USER = 101L;
    private static final Set<String> ENERGY = Set.of("ENERGY_MANAGER");
    private static final LocalDateTime EFFECTIVE = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Autowired private EnergyCatalogService service;
    @Autowired private EnergyCatalogRepository repository;
    @Autowired private EnergyCatalogLookup lookup;
    @Autowired private EnergyPointProfileService profileService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void prepareAuthorityAndPoint() {
        jdbc.update("INSERT INTO sys_user_building(user_id,building_id) VALUES (?,?)", USER, "BLD001");
        assign("ENERGY_CATALOG_MAINTAIN", "DUTY_ENERGY_MAINTAIN");
        assign("ENERGY_CATALOG_REVIEW", "DUTY_ENERGY_REVIEW");
        jdbc.update("""
                INSERT INTO biz_data_point
                (point_id,point_code,point_name,building_id,naming_rule_id,family_code,
                 component_code,suffix_code,data_type,unit,is_for_calc,status,del_flag)
                VALUES ('ENERGY_KWH_POINT','METER_ELECTRICITY_TOTAL','模拟电量累计点','BLD001',
                        'RULE_WCR_MAIN','WCR','MAIN','PPE','ACCUMULATE','kWh',0,'ONLINE',0)
                """);
    }

    @Test
    void approvesCatalogCompatibilityAndBindingWithVersionEvidence() {
        approveSeededItemAndUnit();
        var compatibility = service.createCompatibilityVersion(USER, ENERGY,
                compatibility("ELECTRICITY", "KWH", "CUMULATIVE", "NONE"));
        var approvedCompatibility = service.approveCompatibility(USER, ENERGY,
                compatibility.versionId(), approve(0));
        assertThat(approvedCompatibility.status()).isEqualTo("APPROVED");

        profileService.create(USER, ENERGY, new CreateRequest("BLD001", "ENERGY_KWH_POINT",
                "ELECTRICITY", "GRID_PURCHASED", "CUMULATIVE", "MONTH", true,
                "CONFIRMED", "研发模拟测点专业属性"));
        var pending = service.createBindingVersion(USER, ENERGY,
                new CreateBindingVersionRequest("BLD001", "ENERGY_KWH_POINT", "ELECTRICITY",
                        LocalDateTime.of(2026, 2, 1, 0, 0), null, "研发模拟绑定依据"));
        assertThat(pending.confirmationStatus()).isEqualTo("PENDING_EXPERT");
        var approved = service.approveBinding(USER, ENERGY, pending.bindingVersionId(), approve(0));
        var effective = service.effectiveBinding(USER, ENERGY, "BLD001", "ENERGY_KWH_POINT",
                LocalDateTime.of(2026, 3, 1, 0, 0));

        assertThat(approved.confirmationStatus()).isEqualTo("CONFIRMED");
        assertThat(effective.bindingVersionId()).isEqualTo(approved.bindingVersionId());
        assertThat(effective.rawUnit()).isEqualTo("kWh");
        assertThat(effective.energyItemVersionId()).isEqualTo("EIV_ELECTRICITY_1");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_security_audit_event
                WHERE source_module='ENERGY_CATALOG' AND object_type='ENERGY_POINT_ITEM_BINDING'
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void safelyRejectsMissingCompatibilityUnconfirmedProfileAndCategoryMismatch() {
        approveSeededItemAndUnit();
        assertCode(() -> service.createBindingVersion(USER, ENERGY,
                        new CreateBindingVersionRequest("BLD001", "ENERGY_KWH_POINT", "ELECTRICITY",
                                EFFECTIVE, null, "尚无专业属性")),
                409, EnergyCatalogErrors.POINT_PROFILE_REQUIRED);

        profileService.create(USER, ENERGY, new CreateRequest("BLD001", "ENERGY_KWH_POINT",
                "ELECTRICITY", "GRID_PURCHASED", "CUMULATIVE", "MONTH", true,
                "CONFIRMED", "研发模拟测点专业属性"));
        assertCode(() -> service.createBindingVersion(USER, ENERGY,
                        new CreateBindingVersionRequest("BLD001", "ENERGY_KWH_POINT", "ELECTRICITY",
                                EFFECTIVE, null, "缺兼容规则")),
                409, EnergyCatalogErrors.UNIT_INCOMPATIBLE);

        service.approveItem(USER, ENERGY, "EIV_NATURAL_GAS_1", approve(0));
        assertCode(() -> service.createBindingVersion(USER, ENERGY,
                        new CreateBindingVersionRequest("BLD001", "ENERGY_KWH_POINT", "NATURAL_GAS",
                                EFFECTIVE, null, "粗分类冲突")),
                409, EnergyCatalogErrors.ITEM_CATEGORY_CONFLICT);
    }

    @Test
    void rejectsMobileScopeOutputUnitsAndInventedFixedConversions() {
        assertCode(() -> service.createItemVersion(USER, ENERGY,
                        new CreateItemVersionRequest("MOBILE_DIESEL", "移动柴油", "FUEL",
                                List.of("MOBILE_COMBUSTION"), "MANUAL", "研发错误样例",
                                EFFECTIVE, null)),
                400, EnergyCatalogErrors.MOBILE_SCOPE_REJECTED);

        assertCode(() -> service.createUnitVersion(USER, ENERGY,
                        new CreateUnitVersionRequest("CUSTOM_TON", "ct", "自定义吨", "MASS", "KG",
                                new BigDecimal("999"), "FIXED_SCALE", null, 6,
                                "MANUAL", "研发错误样例", EFFECTIVE, null)),
                400, EnergyCatalogErrors.VALIDATION_FAILED);

        service.approveItem(USER, ENERGY, "EIV_ELECTRICITY_1", approve(0));
        service.approveUnit(USER, ENERGY, "EUV_KGCE_1", approve(0));
        service.approveUnit(USER, ENERGY, "EUV_TCE_1", approve(0));
        assertCode(() -> service.createCompatibilityVersion(USER, ENERGY,
                        compatibility("ELECTRICITY", "TCE", "CUMULATIVE", "NONE")),
                400, EnergyCatalogErrors.UNIT_INCOMPATIBLE);
    }

    @Test
    void acceptsControlledTonneIdentityWithoutRelaxingScaleDimensionOrReview() {
        service.approveUnit(USER, ENERGY, "EUV_KG_1", approve(0));
        var tonne = service.createUnitVersion(USER, ENERGY,
                new CreateUnitVersionRequest(" t ", "t", "吨", "MASS", "KG",
                        new BigDecimal("1000"), "FIXED_SCALE", null, 6,
                        "STANDARD", "受控吨单位回归", EFFECTIVE, null));
        assertThat(tonne.unitCode()).isEqualTo("T");
        assertThat(tonne.status()).isEqualTo("PENDING_EXPERT");
        assertThat(lookup.convert(BigDecimal.ONE, "T", "KG", EFFECTIVE)).isEmpty();
        service.approveUnit(USER, ENERGY, tonne.versionId(), approve(0));
        assertThat(lookup.convert(BigDecimal.ONE, "T", "KG", EFFECTIVE).orElseThrow().convertedQuantity())
                .isEqualByComparingTo("1000");
        assertThat(lookup.convert(new BigDecimal("1000"), "KG", "T", EFFECTIVE).orElseThrow().convertedQuantity())
                .isEqualByComparingTo("1");
        service.approveItem(USER, ENERGY, "EIV_DIESEL_1", approve(0));
        var compatibility = service.createCompatibilityVersion(USER, ENERGY,
                compatibility("DIESEL", "T", "CUMULATIVE", "NONE"));
        assertThat(compatibility.status()).isEqualTo("PENDING_EXPERT");
        for (String invalid : List.of("X", "TONNE")) {
            assertCode(() -> service.createUnitVersion(USER, ENERGY,
                    new CreateUnitVersionRequest(invalid, "t", "吨", "MASS", "KG",
                            new BigDecimal("1000"), "FIXED_SCALE", null, 6,
                            "STANDARD", "错误身份", EFFECTIVE, null)), 400, EnergyCatalogErrors.VALIDATION_FAILED);
        }
        assertCode(() -> service.createUnitVersion(USER, ENERGY,
                new CreateUnitVersionRequest("T", "t", "吨", "MASS", "KG", new BigDecimal("999"),
                        "FIXED_SCALE", null, 6, "STANDARD", "错误比例", EFFECTIVE, null)),
                400, EnergyCatalogErrors.VALIDATION_FAILED);
        assertCode(() -> service.createUnitVersion(USER, ENERGY,
                new CreateUnitVersionRequest("T", "t", "吨", "ENERGY", "KG", new BigDecimal("1000"),
                        "FIXED_SCALE", null, 6, "STANDARD", "错误量纲", EFFECTIVE, null)),
                400, EnergyCatalogErrors.VALIDATION_FAILED);
        assertCode(() -> service.createUnitVersion(USER, ENERGY,
                new CreateUnitVersionRequest("T", "t", "吨", "MASS", "T", BigDecimal.ONE,
                        "IDENTITY", null, 6, "STANDARD", "不能绕开千克基准", EFFECTIVE, null)),
                400, EnergyCatalogErrors.VALIDATION_FAILED);
        assertCode(() -> service.createItemVersion(USER, ENERGY,
                new CreateItemVersionRequest("T", "错误能源编码", "FUEL", List.of("STATIONARY_COMBUSTION"),
                        "STANDARD", "单位兼容不放宽能源身份", EFFECTIVE, null)),
                400, EnergyCatalogErrors.VALIDATION_FAILED);
    }

    @Test
    void requiresDynamicMaintainDutyAndRejectsStaleApprovalRevision() {
        jdbc.update("DELETE FROM sys_user_backend_duty WHERE user_id=?", USER);
        assertCode(() -> service.createItemVersion(USER, ENERGY,
                        new CreateItemVersionRequest("TEST_FUEL", "测试燃料", "FUEL",
                                List.of("STATIONARY_COMBUSTION"), "MANUAL", "研发测试",
                                EFFECTIVE, null)),
                403, EnergyCatalogErrors.FORBIDDEN);

        assign("ENERGY_CATALOG_REVIEW", "DUTY_REVIEW_ONLY");
        service.approveItem(USER, ENERGY, "EIV_ELECTRICITY_1", approve(0));
        assertCode(() -> service.approveItem(USER, ENERGY, "EIV_ELECTRICITY_1", approve(0)),
                409, EnergyCatalogErrors.STATUS_CONFLICT);
    }

    @Test
    void backfillsVersionTwoAtHistoricalBoundaryAndFutureApprovalClosesCurrentVersion() {
        var current = service.createItemVersion(USER, ENERGY,
                item("HISTORICAL_FUEL", "2020-01-01T00:00:00", null));
        service.approveItem(USER, ENERGY, current.versionId(), approve(0));

        var historical = service.createItemVersion(USER, ENERGY,
                item("HISTORICAL_FUEL", "2010-01-01T00:00:00", "2020-01-01T00:00:00"));
        var approvedHistory = service.approveItemHistorical(USER, ENERGY, historical.versionId(), approve(0));
        assertThat(approvedHistory.status()).isEqualTo("APPROVED");
        assertThat(approvedHistory.versionNo()).isEqualTo(2);
        assertThat(approvedHistory.approvedAt()).isNotNull();

        var future = service.createItemVersion(USER, ENERGY,
                item("HISTORICAL_FUEL", "2030-01-01T00:00:00", null));
        service.approveItem(USER, ENERGY, future.versionId(), approve(0));

        assertThat(jdbc.queryForObject("""
                SELECT effective_to FROM biz_energy_item_version WHERE version_id=?
                """, java.sql.Timestamp.class, current.versionId()).toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2030, 1, 1, 0, 0));
        assertThat(jdbc.queryForObject("""
                SELECT effective_to FROM biz_energy_item_version WHERE version_id=?
                """, java.sql.Timestamp.class, historical.versionId()).toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2020, 1, 1, 0, 0));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_security_audit_event
                WHERE source_module='ENERGY_CATALOG' AND action_type='APPROVE_HISTORICAL_ITEM_VERSION'
                  AND object_id=? AND version_id=?
                """, Integer.class, approvedHistory.itemId(), approvedHistory.versionId())).isEqualTo(1);
    }

    @Test
    void rejectsOverlappingOpenAndNotYetEndedHistoricalIntervals() {
        var current = service.createItemVersion(USER, ENERGY,
                item("HISTORICAL_OVERLAP", "2020-01-01T00:00:00", null));
        service.approveItem(USER, ENERGY, current.versionId(), approve(0));

        var overlapping = service.createItemVersion(USER, ENERGY,
                item("HISTORICAL_OVERLAP", "2021-01-01T00:00:00", "2022-01-01T00:00:00"));
        assertCode(() -> service.approveItemHistorical(USER, ENERGY, overlapping.versionId(), approve(0)),
                409, EnergyCatalogErrors.EFFECTIVE_TIME_CONFLICT);

        var open = service.createItemVersion(USER, ENERGY,
                item("HISTORICAL_OVERLAP", "2010-01-01T00:00:00", null));
        assertCode(() -> service.approveItemHistorical(USER, ENERGY, open.versionId(), approve(0)),
                400, EnergyCatalogErrors.VALIDATION_FAILED);

        LocalDateTime futureStart = LocalDateTime.now().plusDays(1).withNano(0);
        LocalDateTime futureEnd = futureStart.plusDays(1);
        var future = service.createItemVersion(USER, ENERGY,
                new CreateItemVersionRequest("HISTORICAL_OVERLAP", "历史测试燃料", "FUEL",
                        List.of("STATIONARY_COMBUSTION"), "MANUAL", "历史区间边界测试",
                        futureStart, futureEnd));
        assertCode(() -> service.approveItemHistorical(USER, ENERGY, future.versionId(), approve(0)),
                400, EnergyCatalogErrors.VALIDATION_FAILED);
    }

    @Test
    void rejectsHistoricalBindingWhenCompatibilityHasGapAndKeepsBuildingsIsolated() {
        approveSeededItemAndUnit();
        profileService.create(USER, ENERGY, new CreateRequest("BLD001", "ENERGY_KWH_POINT",
                "ELECTRICITY", "GRID_PURCHASED", "CUMULATIVE", "MONTH", true,
                "CONFIRMED", "研发模拟测点专业属性"));
        var partialCompatibility = service.createCompatibilityVersion(USER, ENERGY,
                new CreateCompatibilityVersionRequest("ELECTRICITY", "KWH", "CUMULATIVE", true,
                        "NONE", "MANUAL", "历史区间缺口测试", LocalDateTime.of(2000, 1, 1, 0, 0),
                        LocalDateTime.of(2010, 1, 1, 0, 0)));
        service.approveCompatibility(USER, ENERGY, partialCompatibility.versionId(), approve(0));
        var gappedBinding = service.createBindingVersion(USER, ENERGY,
                new CreateBindingVersionRequest("BLD001", "ENERGY_KWH_POINT", "ELECTRICITY",
                        LocalDateTime.of(2005, 1, 1, 0, 0), LocalDateTime.of(2015, 1, 1, 0, 0),
                        "历史绑定完整区间验证"));
        assertCode(() -> service.approveBindingHistorical(USER, ENERGY, gappedBinding.bindingVersionId(),
                        approve(0)), 409, EnergyCatalogErrors.UNIT_INCOMPATIBLE);

        // 两栋建筑的相同测点编码有不同pointId和绑定身份，历史审批互不影响。
        jdbc.update("""
                INSERT INTO biz_data_point
                (point_id,point_code,point_name,building_id,naming_rule_id,family_code,
                 component_code,suffix_code,data_type,unit,is_for_calc,status,del_flag)
                VALUES ('ENERGY_KWH_POINT_BLD002','METER_ELECTRICITY_TOTAL','另一建筑模拟电量点','BLD002',
                        'RULE_WCR_MAIN','WCR','MAIN','PPE','ACCUMULATE','kWh',0,'ONLINE',0)
                """);
        jdbc.update("""
                INSERT INTO biz_energy_point_profile
                (profile_id,point_id,building_id,energy_type,energy_subtype,value_semantics,
                 reporting_period,annual_summary,confirmation_status,evidence_reference,config_revision,
                 create_by,update_by)
                VALUES ('PROFILE_ENERGY_BLD002','ENERGY_KWH_POINT_BLD002','BLD002','ELECTRICITY',
                        'GRID_PURCHASED','CUMULATIVE','MONTH',TRUE,'CONFIRMED','另一建筑研发模拟专业属性',0,101,101)
                """);
        var fullCompatibility = service.createCompatibilityVersion(USER, ENERGY,
                new CreateCompatibilityVersionRequest("ELECTRICITY", "KWH", "CUMULATIVE", true,
                        "NONE", "MANUAL", "另一建筑历史绑定隔离测试",
                        LocalDateTime.of(2015, 1, 1, 0, 0), null));
        service.approveCompatibility(USER, ENERGY, fullCompatibility.versionId(), approve(0));
        var buildingOneBinding = service.createBindingVersion(USER, ENERGY,
                new CreateBindingVersionRequest("BLD001", "ENERGY_KWH_POINT", "ELECTRICITY",
                        LocalDateTime.of(2016, 1, 1, 0, 0), LocalDateTime.of(2017, 1, 1, 0, 0),
                        "建一历史绑定"));
        var buildingTwoBinding = service.createBindingVersion(USER, Set.of("PLATFORM_ADMIN"),
                new CreateBindingVersionRequest("BLD002", "ENERGY_KWH_POINT_BLD002", "ELECTRICITY",
                        LocalDateTime.of(2016, 1, 1, 0, 0), LocalDateTime.of(2017, 1, 1, 0, 0),
                        "建二历史绑定"));
        service.approveBindingHistorical(USER, ENERGY, buildingOneBinding.bindingVersionId(), approve(0));
        service.approveBindingHistorical(USER, Set.of("PLATFORM_ADMIN"), buildingTwoBinding.bindingVersionId(), approve(0));
        assertThat(service.effectiveBindingForPeriod(USER, Set.of("PLATFORM_ADMIN"), "BLD002", "ENERGY_KWH_POINT_BLD002",
                LocalDateTime.of(2016, 2, 1, 0, 0), LocalDateTime.of(2016, 3, 1, 0, 0))
                .bindingVersionId()).isEqualTo(buildingTwoBinding.bindingVersionId());
    }

    @Test
    void serializesConcurrentHistoricalApprovalsForOneIdentity() throws Exception {
        String itemId = "EI_HISTORY_RACE";
        String itemCode = "HISTORY_RACE";
        String firstVersion = "EIV_HISTORY_RACE_1";
        String secondVersion = "EIV_HISTORY_RACE_2";
        LocalDateTime from = LocalDateTime.of(2010, 1, 1, 0, 0);
        LocalDateTime firstTo = LocalDateTime.of(2020, 1, 1, 0, 0);
        LocalDateTime secondFrom = LocalDateTime.of(2015, 1, 1, 0, 0);
        LocalDateTime secondTo = LocalDateTime.of(2025, 1, 1, 0, 0);
        transaction(TransactionDefinition.PROPAGATION_REQUIRES_NEW).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO biz_energy_item(item_id,item_code,created_by) VALUES (?,?,?)",
                    itemId, itemCode, USER);
            insertPendingItemVersion(firstVersion, itemId, 1, from, firstTo);
            insertPendingItemVersion(secondVersion, itemId, 2, secondFrom, secondTo);
        });

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> transaction(
                    TransactionDefinition.PROPAGATION_REQUIRES_NEW).execute(status -> {
                assertThat(repository.findItemIdForUpdate(itemCode)).isEqualTo(itemId);
                firstLocked.countDown();
                await(releaseFirst);
                assertThat(repository.hasApprovedItemOverlap(itemId, firstVersion, from, firstTo)).isFalse();
                return repository.approveItem(firstVersion, 0, USER, LocalDateTime.now()) == 1;
            }));
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> second = executor.submit(() -> transaction(
                    TransactionDefinition.PROPAGATION_REQUIRES_NEW).execute(status -> {
                secondStarted.countDown();
                assertThat(repository.findItemIdForUpdate(itemCode)).isEqualTo(itemId);
                return repository.hasApprovedItemOverlap(itemId, secondVersion, secondFrom, secondTo);
            }));
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(second.isDone()).isFalse();
            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            transaction(TransactionDefinition.PROPAGATION_REQUIRES_NEW).executeWithoutResult(status -> {
                jdbc.update("DELETE FROM biz_energy_item_version_scope WHERE version_id IN (?,?)",
                        firstVersion, secondVersion);
                jdbc.update("DELETE FROM biz_energy_item_version WHERE item_id=?", itemId);
                jdbc.update("DELETE FROM biz_energy_item WHERE item_id=?", itemId);
            });
        }
    }

    private void approveSeededItemAndUnit() {
        service.approveItem(USER, ENERGY, "EIV_ELECTRICITY_1", approve(0));
        service.approveUnit(USER, ENERGY, "EUV_KWH_1", approve(0));
    }

    private CreateCompatibilityVersionRequest compatibility(String itemCode, String unitCode,
                                                              String semantics, String requirement) {
        return new CreateCompatibilityVersionRequest(itemCode, unitCode, semantics, true, requirement,
                "MANUAL", "研发模拟兼容依据", EFFECTIVE, null);
    }

    private CreateItemVersionRequest item(String code, String from, String to) {
        return new CreateItemVersionRequest(code, "历史测试燃料", "FUEL", List.of("STATIONARY_COMBUSTION"),
                "MANUAL", "能源字典历史补录集成测试", LocalDateTime.parse(from),
                to == null ? null : LocalDateTime.parse(to));
    }

    private void insertPendingItemVersion(String versionId, String itemId, int version,
                                          LocalDateTime from, LocalDateTime to) {
        jdbc.update("""
                INSERT INTO biz_energy_item_version
                (version_id,item_id,version_no,item_name,compatible_category,status,source_type,
                 source_reference,effective_from,effective_to,config_revision,created_by,created_at)
                VALUES (?,?,?,'并发历史测试','FUEL','PENDING_EXPERT','MANUAL','并发锁验证',?,?,0,?,CURRENT_TIMESTAMP)
                """, versionId, itemId, version, java.sql.Timestamp.valueOf(from),
                java.sql.Timestamp.valueOf(to), USER);
    }

    private TransactionTemplate transaction(int propagation) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(propagation);
        return template;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("并发测试栅栏等待超时");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试被中断", ex);
        }
    }

    private ApproveRequest approve(int revision) {
        return new ApproveRequest(revision, "研发环境专业流程模拟审核");
    }

    private void assign(String duty, String assignmentId) {
        jdbc.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by)
                VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP,1)
                """, assignmentId, USER, duty);
    }

    private static void assertCode(Runnable invocation, int status, String code) {
        assertThatThrownBy(invocation::run).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getCode()).isEqualTo(status);
            assertThat(error.getErrorCode()).isEqualTo(code);
        });
    }
}
