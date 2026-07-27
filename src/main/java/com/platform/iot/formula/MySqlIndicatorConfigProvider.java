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
 * MySQL-backed immutable snapshot of active formula indicators.
 *
 * <p>A refresh builds a complete ID-indexed snapshot before atomically replacing
 * the volatile reference. If MySQL is unavailable, readers continue using the
 * last complete snapshot.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MySqlIndicatorConfigProvider implements IndicatorConfigProvider {

    private final BizIndicatorMapper indicatorMapper;
    private volatile Map<String, BizIndicator> activeById = Map.of();

    @PostConstruct
    public void initialize() {
        refreshAll();
    }

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
