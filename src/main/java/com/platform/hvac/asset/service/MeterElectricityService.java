package com.platform.hvac.asset.service;

import com.platform.hvac.asset.api.MeterElectricityContracts;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.service.BizDataPointService;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.platform.iot.qualityusage.QualityUsageModels.POINT_HISTORY_VIEW;

/** 单表 EPP 分析入口，复用已部署的表计档案，不依赖整楼正式能源核算关系。 */
@Service
@RequiredArgsConstructor
public class MeterElectricityService {
    private final MeterCoverageRepository meters;
    private final MeterCoverageService coverage;
    private final BizDataPointService points;
    private final HvacRawEventRepository rawEvents;
    private final QualityUsagePolicyResolver quality;

    public MeterElectricityContracts.View analyze(String meterId, String buildingId, int days,
                                                   Collection<String> roles) {
        if (roles == null || roles.stream().noneMatch("PLATFORM_ADMIN"::equalsIgnoreCase)) {
            throw AssetErrors.error(403, "ASSET_FORBIDDEN", "只有平台管理员可以查看电表分析");
        }
        if (days != 7 && days != 14 && days != 30) {
            throw AssetErrors.error(400, AssetErrors.VALIDATION_FAILED, "仅支持最近 7、14 或 30 个完整自然日");
        }
        var meter = meters.equipment(meterId, false).orElseThrow(() ->
                AssetErrors.error(404, AssetErrors.NOT_FOUND, "表计不存在"));
        if (!meter.meter()) throw AssetErrors.error(400, AssetErrors.VALIDATION_FAILED, "该设备不是电表");
        if (buildingId != null && !buildingId.equals(meter.buildingId())) {
            throw AssetErrors.error(400, AssetErrors.VALIDATION_FAILED, "表计不属于指定建筑");
        }
        List<BizDataPoint> epp = points.listByEquip(meterId).getData().stream()
                .filter(point -> meter.buildingId().equals(point.getBuildingId()))
                .filter(point -> "ONLINE".equalsIgnoreCase(point.getStatus()))
                .filter(point -> "kWh".equalsIgnoreCase(point.getUnit()))
                .filter(point -> point.getPointCode() != null
                        && point.getPointCode().toUpperCase(Locale.ROOT).endsWith("_EPP"))
                .toList();
        if (epp.size() != 1) {
            throw AssetErrors.error(409, "METER_EPP_UNCONFIGURED", "电表需有且仅有一个启用的 EPP 累计电能测点");
        }
        BizDataPoint point = epp.getFirst();
        LocalDate today = LocalDate.now(MeterElectricityCalculator.ZONE);
        long from = today.minusDays(days + 1L).atStartOfDay(MeterElectricityCalculator.ZONE)
                .toInstant().toEpochMilli() - MeterElectricityCalculator.BOUNDARY_WINDOW_MS;
        long to = today.atStartOfDay(MeterElectricityCalculator.ZONE).toInstant().toEpochMilli() + 1;
        List<RawTelemetryEvent> events = new ArrayList<>();
        Long cursor = null;
        while (true) {
            List<RawTelemetryEvent> page = rawEvents.findMeterPointHistory(
                    meter.buildingId(), meterId, point.getPointId(), from, to, cursor, 1000);
            events.addAll(page);
            if (page.size() < 1000) break;
            if (events.size() >= 100_000) {
                throw AssetErrors.error(409, "METER_HISTORY_TOO_DENSE", "原始历史超过单次分析上限");
            }
            cursor = page.getLast().eventTime();
        }
        var context = events.isEmpty() ? null : quality.historyContext(Set.of(point.getPointId()),
                POINT_HISTORY_VIEW, from, to);
        var daily = context == null
                ? MeterElectricityCalculator.calculate(List.of(), today, days, point.getPointId(), quality, null)
                : MeterElectricityCalculator.calculate(events, today, days, point.getPointId(), quality, context);
        return new MeterElectricityContracts.View(meterId, meter.code(), meter.name(), meter.buildingId(),
                point.getPointCode(), "kWh", MeterElectricityCalculator.ZONE.getId(), 6,
                coverage.current(meterId, roles), daily);
    }
}
