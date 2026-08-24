package com.platform.iot.quality;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.hvac.mapper.BizDataPointMapper;
import com.platform.hvac.mapper.BizEquipmentMapper;
import com.platform.hvac.mapper.BizPointAliasMapper;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.model.entity.BizEquipment;
import com.platform.hvac.model.entity.BizPointAlias;
import com.platform.iot.collection.mapper.BizDataSourceMapper;
import com.platform.iot.collection.model.CollectionModels.SourceStatus;
import com.platform.iot.collection.model.entity.BizDataSource;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
/**
 * MySQL 工业测点身份缓存。
 *
 * <p>一次构建“来源别名→pointId”和“pointId→标准配置”两张不可变Map，
 * 再原子替换快照。MQTT热路径只查内存，刷新失败继续使用最后一份完整配置。</p>
 */
public class MySqlDataPointConfigProvider implements DataPointConfigProvider {

    private final BizDataPointMapper pointMapper;
    private final BizPointAliasMapper aliasMapper;
    private final BizEquipmentMapper equipmentMapper;
    private final BizDataSourceMapper sourceMapper;
    private volatile ConfigSnapshot snapshot = ConfigSnapshot.empty();
    private volatile boolean snapshotAvailable;

    /** 在定时任务启动前构建首份测点身份快照。 */
    @PostConstruct
    public void initialize() {
        refreshAll();
    }

    /**
     * 从 MySQL 批量重建外部别名索引和标准测点配置。
     *
     * <p>设备表补充测点的上报设备编码，别名表只接收启用且能关联到标准测点的记录。
     * 两张新索引全部构建完成后才原子替换 {@code volatile} 快照，因此 MQTT 热路径
     * 不会观察到半刷新状态；任一查询失败时继续使用上一完整版本。</p>
     */
    @Scheduled(fixedDelayString = "${ingestion.point-config-refresh-ms:60000}")
    public void refreshAll() {
        try {
            refreshAllOrThrow();
        } catch (RuntimeException exception) {
            log.warn("测点身份缓存刷新失败，继续使用上一完整版本: {}", exception.getMessage());
        }
    }

    /**
     * 为治理发布流程提供可感知失败的完整刷新。
     * 数据库事务已经提交后才调用；失败由运行状态记录，不撤销正式配置。
     */
    public void refreshAllOrThrow() {
            List<BizDataSource> sources = sourceMapper.selectList(new LambdaQueryWrapper<>());
            Map<String, BizDataSource> enabledSourceById = new LinkedHashMap<>();
            sources.stream()
                    .filter(source -> SourceStatus.ENABLED.name().equals(source.getStatus()))
                    .forEach(source -> enabledSourceById.put(source.getSourceId(), source));

            List<BizEquipment> equipment = equipmentMapper.selectList(new LambdaQueryWrapper<>());
            Map<String, BizEquipment> equipmentById = new LinkedHashMap<>();
            equipment.forEach(item -> equipmentById.put(item.getEquipId(), item));

            List<BizDataPoint> points = pointMapper.selectList(new LambdaQueryWrapper<>());
            Map<String, PointRuntimeConfig> pointById = new LinkedHashMap<>();
            for (BizDataPoint point : points) {
                BizEquipment owner = point.getEquipId() == null
                        ? null : equipmentById.get(point.getEquipId());
                pointById.put(point.getPointId(), new PointRuntimeConfig(
                        point.getPointId(),
                        point.getPointCode(),
                        point.getPointName(),
                        point.getBuildingId(),
                        point.getSystemGroupId(),
                        point.getEquipId(),
                        owner == null ? null : owner.getEquipCode(),
                        point.getFamilyCode(),
                        point.getComponentCode(),
                        point.getSuffixCode(),
                        point.getDataType(),
                        point.getUnit(),
                        point.getStatus(),
                        Integer.valueOf(1).equals(point.getIsForCalc()) ? 1 : 0,
                        point.getValueMin(),
                        point.getValueMax()
                ));
            }

            List<BizPointAlias> aliases = aliasMapper.selectList(new LambdaQueryWrapper<>());
            Map<PointAliasKey, String> aliasToPointId = new LinkedHashMap<>();
            for (BizPointAlias alias : aliases) {
                if (!Integer.valueOf(1).equals(alias.getStatus())
                        || !enabledSourceById.containsKey(alias.getSourceId())
                        || !pointById.containsKey(alias.getPointId())) {
                    continue;
                }
                aliasToPointId.put(new PointAliasKey(
                        alias.getBuildingId(),
                        alias.getSourceSystem(),
                        alias.getSourcePointCode()), alias.getPointId());
            }
            snapshot = new ConfigSnapshot(
                    Collections.unmodifiableMap(new LinkedHashMap<>(aliasToPointId)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(pointById)));
            snapshotAvailable = true;
            log.debug("测点身份缓存刷新完成: points={}, aliases={}",
                    pointById.size(), aliasToPointId.size());
    }

    @Override
    public Optional<PointRuntimeConfig> find(PointAliasKey aliasKey) {
        requireSnapshot();
        String pointId = snapshot.aliasToPointId().get(aliasKey);
        return pointId == null
                ? Optional.empty()
                : Optional.ofNullable(snapshot.pointById().get(pointId));
    }

    @Override
    public Optional<PointRuntimeConfig> findByPointId(String pointId) {
        requireSnapshot();
        return Optional.ofNullable(snapshot.pointById().get(pointId));
    }

    @Override
    public Collection<PointRuntimeConfig> findAll() {
        requireSnapshot();
        return snapshot.pointById().values();
    }

    private void requireSnapshot() {
        if (!snapshotAvailable) {
            throw new DataPointConfigSnapshotUnavailableException("测点身份快照尚未成功加载");
        }
    }

    private record ConfigSnapshot(
            Map<PointAliasKey, String> aliasToPointId,
            Map<String, PointRuntimeConfig> pointById) {

        private static ConfigSnapshot empty() {
            return new ConfigSnapshot(Map.of(), Map.of());
        }
    }
}
