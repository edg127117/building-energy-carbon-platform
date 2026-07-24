package com.platform.iot.temporal.impl;

import com.platform.config.TdengineProperties;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * TDengine 正式分钟汇总仓储。
 */
@Slf4j
@Repository
public class TdengineHvacMinuteRepository implements HvacMinuteRepository {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private final JdbcTemplate template;
    private final TdengineProperties properties;
    private final Set<String> identityTagChecked = ConcurrentHashMap.newKeySet();

    public TdengineHvacMinuteRepository(
            @Qualifier("taosJdbcTemplate") JdbcTemplate template,
            TdengineProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    @Override
    public Set<String> findExistingPointIds(long minuteStart) {
        String stable = safe(properties.getDatabase()) + "." + safe(properties.getStRawMinute());
        // 恢复时一次读取该分钟全部已完成测点，替代逐子表执行 COUNT(*)。
        List<String> pointIds = template.queryForList(
                "SELECT point_id FROM " + stable + " WHERE ts="
                        + quote(new Timestamp(minuteStart).toString()),
                String.class);
        return new LinkedHashSet<>(pointIds);
    }

    @Override
    public void saveAll(List<RawMinuteAggregate> aggregates) {
        if (aggregates.isEmpty()) {
            return;
        }
        aggregates.forEach(this::ensureExistingChildBuildingTag);
        String stable = safe(properties.getDatabase()) + "." + safe(properties.getStRawMinute());
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        for (RawMinuteAggregate aggregate : aggregates) {
            // TDengine允许一条INSERT同时写多个子表；同一pointCode+minuteStart再次写入
            // 会覆盖相同时间戳行，因此启动恢复可以安全重试而不产生重复记录。
            sql.append(qualifiedChild(aggregate.pointId())).append(" USING ").append(stable)
                .append(" (point_id,point_code,building_id,system_group_id,equip_id,equip_code,")
                .append("family_code,component_code,suffix_code,is_for_calc) TAGS (")
                .append(quote(aggregate.pointId())).append(",")
                .append(quote(aggregate.pointCode())).append(",")
                .append(quote(aggregate.buildingId())).append(",")
                .append(nullableQuote(aggregate.systemGroupId())).append(",")
                .append(nullableQuote(aggregate.equipId())).append(",")
                .append(nullableQuote(aggregate.equipCode())).append(",")
                .append(quote(aggregate.familyCode())).append(",")
                .append(nullableQuote(aggregate.componentCode())).append(",")
                .append(quote(aggregate.suffixCode())).append(",")
                .append(aggregate.isForCalc()).append(") ")
                .append("(ts,val,data_quality,avg_val,min_val,max_val,sample_count,")
                .append("first_received_time,last_received_time,finalized_at) VALUES (")
                .append(quote(new Timestamp(aggregate.minuteStart()).toString())).append(",")
                .append(number(aggregate.averageValue())).append(",")
                .append(aggregate.dataQuality()).append(",")
                .append(number(aggregate.averageValue())).append(",")
                .append(number(aggregate.minimumValue())).append(",")
                .append(number(aggregate.maximumValue())).append(",")
                .append(aggregate.sampleCount()).append(",")
                .append(quote(new Timestamp(aggregate.firstReceivedTime()).toString())).append(",")
                .append(quote(new Timestamp(aggregate.lastReceivedTime()).toString())).append(",")
                .append(quote(new Timestamp(aggregate.finalizedAt()).toString())).append(") ");
        }
        template.execute(sql.toString().trim());
    }

    private void ensureExistingChildBuildingTag(RawMinuteAggregate aggregate) {
        if (!identityTagChecked.add(aggregate.pointId())) return;
        try {
            // 旧版子表在新增 building_id 标签后值为 NULL，首次写入时补齐审计维度。
            template.execute("ALTER TABLE " + qualifiedChild(aggregate.pointId())
                    + " SET TAG building_id=" + quote(aggregate.buildingId()));
        } catch (org.springframework.dao.DataAccessException exception) {
            // 新测点子表此时可能尚不存在，随后的 INSERT USING 会带标签自动创建。
            log.debug("分钟子表建筑标签将在首次创建时写入: pointCode={}", aggregate.pointCode());
        }
    }

    private String qualifiedChild(String pointId) {
        return safe(properties.getDatabase()) + "."
                + safe(properties.getStRawMinute() + "_" + pointId);
    }

    private String safe(String value) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("非法TDengine标识符: " + value);
        }
        return value;
    }

    private String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private String nullableQuote(String value) {
        return value == null ? "NULL" : quote(value);
    }

    private String number(double value) {
        return String.format(Locale.ROOT, "%.12f", value);
    }
}
