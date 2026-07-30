package com.platform.iot.dataquality.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 补全任务写入分钟数据时使用的强类型来源证据。
 *
 * <p>典型值证据保留配置版本、有效期和最终应用区间；插值证据保留两个真实质量
 * 端点。业务服务只能通过 {@code FillTaskEvidenceCodec} 将它与 MySQL JSON 字段
 * 互转，避免各处自行拼接 JSON 后产生不可恢复的审计数据。</p>
 */
public sealed interface FillTaskEvidence {

    /**
     * 质量 2 典型值的配置快照与应用区间。
     */
    record Typical(
            String configId,
            int version,
            BigDecimal value,
            String unit,
            long validFrom,
            Long validTo,
            long hourStart,
            String algorithmVersion,
            List<MinuteSegment> appliedSegments) implements FillTaskEvidence {
    }

    /**
     * 质量 1 线性插值使用的两个质量 0 真实端点。
     */
    record Interpolation(
            long leftMinute,
            double leftValue,
            long rightMinute,
            double rightValue,
            String algorithmVersion) implements FillTaskEvidence {
    }

    /**
     * 左闭右开的连续分钟区间，使用 Unix 毫秒表示。
     */
    record MinuteSegment(long fromInclusive, long toExclusive) {
    }
}
