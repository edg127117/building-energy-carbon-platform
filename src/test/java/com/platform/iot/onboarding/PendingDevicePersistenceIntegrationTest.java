package com.platform.iot.onboarding;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.iot.identity.DeviceIdentityKey;
import com.platform.iot.onboarding.mapper.BizDeviceProductMapper;
import com.platform.iot.onboarding.mapper.BizPendingDeviceMapper;
import com.platform.iot.onboarding.mapper.BizProductPointTemplateMapper;
import com.platform.iot.onboarding.model.entity.BizDeviceProduct;
import com.platform.iot.onboarding.model.entity.BizPendingDevice;
import com.platform.iot.onboarding.model.entity.BizProductPointTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PendingDevicePersistenceIntegrationTest {

    private static final DeviceIdentityKey IDENTITY =
            new DeviceIdentityKey("MAC", "PENDING-CONCURRENT-001");

    @Autowired
    private PendingDeviceRepository repository;

    @Autowired
    private BizPendingDeviceMapper mapper;

    @Autowired
    private BizDeviceProductMapper productMapper;

    @Autowired
    private BizProductPointTemplateMapper pointTemplateMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanPendingDevices() {
        jdbcTemplate.update("DELETE FROM biz_pending_device");
        jdbcTemplate.update("DELETE FROM biz_product_point_template");
        jdbcTemplate.update("DELETE FROM biz_device_product");
    }

    @Test
    void productAndPointTemplateTablesEnforceBusinessUniqueKeys() {
        BizDeviceProduct product = product("PRODUCT_A", "PRODUCT_CODE_A");
        productMapper.insert(product);
        pointTemplateMapper.insert(pointTemplate("TEMPLATE_A", product.getProductId()));

        assertThat(productMapper.selectById(product.getProductId()).getProductCode())
                .isEqualTo("PRODUCT_CODE_A");
        assertThat(pointTemplateMapper.selectById("TEMPLATE_A").getMetricCode())
                .isEqualTo("CURRENT_ENERGY");
        assertThatThrownBy(() -> productMapper.insert(
                product("PRODUCT_B", "PRODUCT_CODE_A")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> pointTemplateMapper.insert(
                pointTemplate("TEMPLATE_B", product.getProductId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentUpsertCreatesOneRowAndAtomicallyCountsEveryReport() throws Exception {
        int reports = 24;
        LocalDateTime base = LocalDateTime.of(2026, 8, 17, 10, 0);
        CountDownLatch ready = new CountDownLatch(reports);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(reports)) {
            for (int index = 0; index < reports; index++) {
                int reportIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    repository.upsertDiscovery(discovery(
                            base.plusSeconds(reportIndex),
                            reportIndex,
                            "{\"report\":" + reportIndex + "}"));
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        List<BizPendingDevice> rows = mapper.selectList(
                new LambdaQueryWrapper<BizPendingDevice>()
                        .eq(BizPendingDevice::getIdentityType, IDENTITY.type())
                        .eq(BizPendingDevice::getIdentityValue, IDENTITY.value()));
        assertThat(rows).hasSize(1);
        BizPendingDevice pending = rows.getFirst();
        assertThat(pending.getReportCount()).isEqualTo(reports);
        assertThat(pending.getFirstSeenTime()).isEqualTo(base);
        assertThat(pending.getLastSeenTime()).isEqualTo(base.plusSeconds(reports - 1L));
        assertThat(pending.getLatestMetricsJson())
                .isEqualTo("{\"report\":" + (reports - 1) + "}");
        assertThat(pending.getStatus()).isEqualTo("DISCOVERED");
    }

    @Test
    void olderLateArrivalOnlyIncrementsCountAndCannotOverwriteLatestSample() {
        LocalDateTime newest = LocalDateTime.of(2026, 8, 17, 11, 0);
        repository.upsertDiscovery(discovery(newest, 3, "{\"report\":\"new\"}"));
        repository.upsertDiscovery(discovery(
                newest.minusMinutes(5), 2, "{\"report\":\"old\"}"));

        BizPendingDevice pending = mapper.selectList(
                new LambdaQueryWrapper<BizPendingDevice>()
                        .eq(BizPendingDevice::getIdentityType, IDENTITY.type())
                        .eq(BizPendingDevice::getIdentityValue, IDENTITY.value()))
                .getFirst();
        assertThat(pending.getReportCount()).isEqualTo(2L);
        assertThat(pending.getLastSeenTime()).isEqualTo(newest);
        assertThat(pending.getLastProfileVersion()).isEqualTo(3);
        assertThat(pending.getLatestMetricsJson()).isEqualTo("{\"report\":\"new\"}");
    }

    @Test
    void repeatedReportUpdatesIgnoredRecordWithoutRestoringDiscoveredStatus() {
        LocalDateTime earlier = LocalDateTime.of(2026, 8, 17, 9, 0);
        BizPendingDevice ignored = stored(IDENTITY.value(), "IGNORED", earlier);
        ignored.setIdentityType(IDENTITY.type());
        mapper.insert(ignored);

        repository.upsertDiscovery(discovery(
                earlier.plusMinutes(1), 2, "{\"report\":\"later\"}"));

        BizPendingDevice pending = mapper.selectList(
                new LambdaQueryWrapper<BizPendingDevice>()
                        .eq(BizPendingDevice::getIdentityType, IDENTITY.type())
                        .eq(BizPendingDevice::getIdentityValue, IDENTITY.value()))
                .getFirst();
        assertThat(pending.getStatus()).isEqualTo("IGNORED");
        assertThat(pending.getReportCount()).isEqualTo(2L);
        assertThat(pending.getLastSeenTime()).isEqualTo(earlier.plusMinutes(1));
        assertThat(pending.getLatestMetricsJson()).isEqualTo("{\"report\":\"later\"}");
    }

    @Test
    void cleanupDeletesOnlyExpiredDiscoveredOrIgnoredRows() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 7, 18, 0, 0);
        mapper.insert(stored("OLD_DISCOVERED", "DISCOVERED", cutoff.minusDays(1)));
        mapper.insert(stored("OLD_IGNORED", "IGNORED", cutoff.minusHours(1)));
        mapper.insert(stored("OLD_BOUND", "BOUND", cutoff.minusDays(2)));
        mapper.insert(stored("FRESH_DISCOVERED", "DISCOVERED", cutoff.plusSeconds(1)));

        int deleted = repository.deleteExpired(cutoff, 10);

        assertThat(deleted).isEqualTo(2);
        assertThat(mapper.selectList(new LambdaQueryWrapper<>()))
                .extracting(BizPendingDevice::getIdentityValue)
                .containsExactlyInAnyOrder("OLD_BOUND", "FRESH_DISCOVERED");
    }

    private PendingDeviceDiscovery discovery(
            LocalDateTime seenAt,
            int profileVersion,
            String json) {
        return new PendingDeviceDiscovery(
                id(), IDENTITY, "ENERGY_METER_V1", profileVersion,
                seenAt, 1_785_398_400_000L + profileVersion,
                "DEVICE_REPORTED", json, false);
    }

    private BizPendingDevice stored(
            String identityValue,
            String status,
            LocalDateTime seenAt) {
        BizPendingDevice pending = new BizPendingDevice();
        pending.setPendingId(id());
        pending.setIdentityType("MAC");
        pending.setIdentityValue(identityValue);
        pending.setProfileCode("ENERGY_METER_V1");
        pending.setLastProfileVersion(1);
        pending.setFirstSeenTime(seenAt);
        pending.setLastSeenTime(seenAt);
        pending.setReportCount(1L);
        pending.setLatestEventTime(seenAt);
        pending.setLatestTimeSource("SERVER_RECEIVED");
        pending.setLatestMetricsJson("{\"metrics\":[]}");
        pending.setSampleTruncated(0);
        pending.setStatus(status);
        return pending;
    }

    private BizDeviceProduct product(String productId, String productCode) {
        BizDeviceProduct product = new BizDeviceProduct();
        product.setProductId(productId);
        product.setProductCode(productCode);
        product.setProductName("电表型号");
        product.setEquipmentTypeCode("WCR");
        product.setExpectedProfileCode("ENERGY_METER_V1");
        product.setIdentityType("MAC");
        product.setStatus("DRAFT");
        return product;
    }

    private BizProductPointTemplate pointTemplate(
            String templatePointId,
            String productId) {
        BizProductPointTemplate template = new BizProductPointTemplate();
        template.setTemplatePointId(templatePointId);
        template.setProductId(productId);
        template.setMetricCode("CURRENT_ENERGY");
        template.setPointNameTemplate("累计电量");
        template.setUnit("kWh");
        template.setForCalc(0);
        template.setRequiredFlag(1);
        template.setSortOrder(1);
        template.setStatus(1);
        return template;
    }

    private String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
