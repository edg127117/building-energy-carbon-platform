package com.platform.carbon;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 只读加载生态环境部、国家统计局公开表中的电力平均因子目录。
 *
 * <p>目录值保持原始单位 {@code kgCO2/kWh}，只包含全国、区域和省级“电力平均”口径；
 * 不创建、审核或激活碳因子。{@code dataYear} 表示官方因子数据年度，
 * {@code accountingYear} 表示本项目已确认的核算年度选用关系，{@code publishedOn} 是公开发布日期；
 * 三个时间字段不能互换。
 */
@Component
public class ElectricityFactorCatalog {
    private static final String RESOURCE = "carbon/electricity-average-factors.csv";
    private static final String HEADER = String.join(",",
            "entryCode", "accountingYear", "dataYear", "applicabilityLevel", "regionCode",
            "regionName", "value", "sourceCode", "sourceName", "publisher",
            "documentReference", "publishedOn", "evidenceReference");
    private static final int EXPECTED_ENTRY_COUNT = 114;

    private final List<Entry> entries;
    private final Map<String, Entry> entriesByCode;

    public ElectricityFactorCatalog() {
        this.entries = load();
        Map<String, Entry> indexed = new HashMap<>();
        for (Entry entry : entries) {
            if (indexed.put(entry.entryCode(), entry) != null) {
                throw new IllegalStateException("电力平均因子目录存在重复编码: " + entry.entryCode());
            }
        }
        this.entriesByCode = Map.copyOf(indexed);
    }

    public List<Entry> entries() {
        return entries;
    }

    public Optional<Entry> entry(String entryCode) {
        if (entryCode == null) return Optional.empty();
        return Optional.ofNullable(entriesByCode.get(entryCode.trim().toUpperCase(Locale.ROOT)));
    }

    private static List<Entry> load() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        try (InputStream input = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (!HEADER.equals(header)) {
                throw new IllegalStateException("电力平均因子目录表头不符合约定");
            }
            List<Entry> loaded = reader.lines()
                    .filter(line -> !line.isBlank())
                    .map(ElectricityFactorCatalog::parse)
                    .toList();
            if (loaded.size() != EXPECTED_ENTRY_COUNT) {
                throw new IllegalStateException("电力平均因子目录条数应为" + EXPECTED_ENTRY_COUNT
                        + "，实际为" + loaded.size());
            }
            return List.copyOf(loaded);
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载电力平均因子目录: " + RESOURCE, exception);
        }
    }

    private static Entry parse(String line) {
        String[] fields = line.split(",", -1);
        if (fields.length != 13) {
            throw new IllegalStateException("电力平均因子目录列数无效: " + line);
        }
        try {
            return new Entry(required(fields[0], "entryCode"), Integer.parseInt(required(fields[1], "accountingYear")),
                    Integer.parseInt(required(fields[2], "dataYear")),
                    required(fields[3], "applicabilityLevel"), optional(fields[4]),
                    required(fields[5], "regionName"), new BigDecimal(required(fields[6], "value")),
                    required(fields[7], "sourceCode"), required(fields[8], "sourceName"),
                    required(fields[9], "publisher"), required(fields[10], "documentReference"),
                    LocalDate.parse(required(fields[11], "publishedOn")),
                    required(fields[12], "evidenceReference"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("电力平均因子目录数据无效: " + line, exception);
        }
    }

    private static String required(String value, String field) {
        String normalized = optional(value);
        if (normalized == null) throw new IllegalStateException("电力平均因子目录缺少" + field);
        return normalized;
    }

    private static String optional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 一条目录记录对应一个核算年度、地区适用层级和官方平均因子；{@code value} 的单位为
     * {@code kgCO2/kWh}，全国条目的 {@code regionCode} 为 {@code null}。
     */
    public record Entry(
            String entryCode, int accountingYear, int dataYear, String applicabilityLevel,
            String regionCode, String regionName, BigDecimal value, String sourceCode,
            String sourceName, String publisher, String documentReference, LocalDate publishedOn,
            String evidenceReference) {
    }
}
