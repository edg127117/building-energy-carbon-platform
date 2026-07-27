package com.platform.iot.formula;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.hvac.mapper.BizIndicatorMapper;
import com.platform.hvac.model.entity.BizIndicator;
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

/**
 * 从 MySQL 加载活动指标，并向公式模块提供不可变内存快照。
 *
 * <p>每次刷新先构建完整的新快照，再原子替换 {@code volatile} 引用；如果
 * MySQL 暂时不可用，读取方继续使用上一份完整配置。这样不会把刷新到一半的
 * 指标集合暴露给分钟计算，也不会因短暂配置库故障中断已有指标。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MySqlIndicatorConfigProvider implements IndicatorConfigProvider {

    private final BizIndicatorMapper indicatorMapper;
    private volatile Map<String, BizIndicator> activeById = Map.of();

    /** 应用启动时建立首份指标快照；失败时保留空快照，由后续定时刷新恢复。 */
    @PostConstruct
    public void initialize() {
        refreshAll();
    }

    /**
     * 定时刷新全部活动指标。
     *
     * <p>刷新失败只降级到上一完整快照，不抛出异常拖垮调度线程。</p>
     */
    @Scheduled(fixedDelayString = "${formula.indicator-config-refresh-ms:60000}")
    public void refreshAll() {
        try {
            List<BizIndicator> indicators = indicatorMapper.selectList(
                    new LambdaQueryWrapper<BizIndicator>()
                            .eq(BizIndicator::getStatus, 1));
            Map<String, BizIndicator> nextSnapshot = new LinkedHashMap<>();
            for (BizIndicator indicator : indicators) {
                if (Integer.valueOf(1).equals(indicator.getStatus())) {
                    nextSnapshot.put(indicator.getIndicatorId(), indicator);
                }
            }
            activeById = Collections.unmodifiableMap(
                    new LinkedHashMap<>(nextSnapshot));
            log.debug("指标配置缓存刷新完成: indicators={}", nextSnapshot.size());
        } catch (RuntimeException exception) {
            log.warn("指标配置缓存刷新失败，继续使用上一完整版本: {}",
                    exception.getMessage());
        }
    }

    @Override
    public Collection<BizIndicator> findAllActive() {
        return activeById.values();
    }

    @Override
    public Optional<BizIndicator> findActive(String indicatorId) {
        return Optional.ofNullable(activeById.get(indicatorId));
    }
}
