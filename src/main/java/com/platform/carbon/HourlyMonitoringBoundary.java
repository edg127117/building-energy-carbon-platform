package com.platform.carbon;

import com.platform.energy.activity.EnergyActivityDataContracts.RawActivityDataView;
import com.platform.framework.exception.BusinessException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 仅供大屏监测的邻近边界；保留实际采样时间，不伪造整点事实或改变正式核算规则。 */
final class HourlyMonitoringBoundary {
    static final long MAX_AGE_MILLIS = 360_000;
    record Window(Instant from, Instant to, List<RawActivityDataView> items) { }

    static Window select(List<RawActivityDataView> items, Instant from, Instant to) {
        return select(items,from,to,false);
    }

    static Window select(List<RawActivityDataView> items, Instant from, Instant to, boolean accumulating) {
        var sorted = items.stream().sorted(Comparator.comparingLong(RawActivityDataView::eventTime)).toList();
        var first = before(sorted, from);
        // 当前小时采用最新到达读数，不把正常的15分钟上报误判成六分钟整点锚缺失。
        var last = accumulating ? sorted.stream().filter(v->v.eventTime()<=to.toEpochMilli()).reduce((a,b)->b).orElse(null) : before(sorted, to);
        if (first == null || last == null || first.eventTime() >= last.eventTime())
            throw new BusinessException(409, "HOURLY_MONITOR_ANCHOR_MISSING", "六分钟范围内缺少小时边界读数");
        var selected = sorted.stream().filter(v -> v.eventTime() >= first.eventTime() && v.eventTime() <= last.eventTime()).toList();
        for (int i = 0; i < selected.size(); i++) {
            var value = selected.get(i);
            if (!Objects.equals(first.sourceDeviceId(), value.sourceDeviceId()) || first.sourceDeviceId() == null
                    || !Objects.equals(first.sourceSystem(), value.sourceSystem())
                    || !Objects.equals(first.sourcePointCode(), value.sourcePointCode()))
                throw new BusinessException(409, "HOURLY_MONITOR_IDENTITY_CHANGED", "统计区间内累计表身份变化");
            if (i > 0 && value.eventTime() == selected.get(i - 1).eventTime())
                throw new BusinessException(409, "HOURLY_MONITOR_DUPLICATE_TIME", "同一采样时刻存在重复读数");
        }
        return new Window(Instant.ofEpochMilli(first.eventTime()), Instant.ofEpochMilli(last.eventTime()), selected);
    }

    private static RawActivityDataView before(List<RawActivityDataView> items, Instant boundary) {
        RawActivityDataView result = null;
        for (var item : items) {
            if (item.eventTime() > boundary.toEpochMilli()) break;
            if (item.eventTime() >= boundary.toEpochMilli() - MAX_AGE_MILLIS) result = item;
        }
        return result;
    }
}
