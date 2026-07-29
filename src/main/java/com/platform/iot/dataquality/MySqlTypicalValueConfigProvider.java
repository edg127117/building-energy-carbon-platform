package com.platform.iot.dataquality;

import com.platform.iot.dataquality.mapper.BizPointTypicalValueConfigMapper;
import com.platform.iot.dataquality.model.TypicalValueStatus;
import com.platform.iot.dataquality.model.entity.BizPointTypicalValueConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 从 MySQL 加载批准典型值并向分钟热路径提供内存快照。
 *
 * <p>刷新采用完整列表的原子替换，查询不会看到半份新旧数据。刷新失败时保留上一份快照；
 * 如果应用启动后从未成功加载，则返回空，宁可记录缺失也不会生成没有审批依据的质量 2 数据。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "data-quality", name = "enabled", havingValue = "true")
public class MySqlTypicalValueConfigProvider implements TypicalValueConfigProvider {

    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    private final BizPointTypicalValueConfigMapper mapper;
    private final AtomicReference<List<BizPointTypicalValueConfig>> snapshot =
            new AtomicReference<>(List.of());

    @PostConstruct
    void initialize() {
        refresh();
    }

    @Override
    public Optional<BizPointTypicalValueConfig> findApproved(String pointId, long minuteStart) {
        if (pointId == null || pointId.isBlank()) {
            return Optional.empty();
        }
        LocalDateTime minute = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(minuteStart), PROJECT_ZONE);
        return snapshot.get().stream()
                .filter(config -> pointId.equals(config.getPointId()))
                .filter(config -> config.getStatus() == TypicalValueStatus.APPROVED)
                .filter(config -> isEffective(config, minute))
                // 正常数据不会重叠；这里仍确定性选择最高版本，避免脏数据导致运行结果随机漂移。
                .max(Comparator.comparing(
                        BizPointTypicalValueConfig::getVersion,
                        Comparator.nullsFirst(Integer::compareTo)));
    }

    @Override
    public List<BizPointTypicalValueConfig> snapshot() {
        return snapshot.get();
    }

    @Override
    @Scheduled(fixedDelayString = "${data-quality.typical-config-refresh-ms:60000}")
    public void refresh() {
        try {
            List<BizPointTypicalValueConfig> loaded = Objects.requireNonNull(
                    mapper.selectApprovedSnapshot(),
                    "典型值配置查询不得返回 null");
            List<BizPointTypicalValueConfig> approved = loaded.stream()
                    .filter(config -> config != null
                            && config.getStatus() == TypicalValueStatus.APPROVED)
                    .toList();
            snapshot.set(List.copyOf(approved));
        } catch (RuntimeException exception) {
            // 不能用一次临时 MySQL 故障清空合法快照，否则所有测点会同时失去 Q2 依据。
            log.warn("刷新典型值配置快照失败，继续使用上一份完整快照", exception);
        }
    }

    private static boolean isEffective(
            BizPointTypicalValueConfig config,
            LocalDateTime minute) {
        return config.getValidFrom() != null
                && !minute.isBefore(config.getValidFrom())
                && (config.getValidTo() == null || minute.isBefore(config.getValidTo()));
    }
}
