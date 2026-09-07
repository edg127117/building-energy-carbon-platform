package com.platform.carbon;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 将已知六位行政区划编码映射到官方区域电网边界。
 *
 * <p>映射依据《2021年电力二氧化碳排放因子计算说明》表1
 * （https://www.mee.gov.cn/xxgk2018/xxgk/xxgk01/202404/W020240412827267566470.pdf）：
 * 内蒙古仅在明确到盟市时区分蒙东和蒙西；省级代码 {@code 150000} 不臆测电网归属。
 * 非六位数字编码继续按旧行为规范化，但不会被当作行政区划参与电网匹配。
 */
public final class ElectricityGridRegions {
    private static final Set<String> MONGOLIA_EASTERN_PREFECTURES = Set.of(
            "1504", "1505", "1507", "1522");
    private static final Set<String> MONGOLIA_WESTERN_PREFECTURES = Set.of(
            "1501", "1502", "1503", "1506", "1508", "1509", "1525", "1529");
    private static final Map<String, String> PROVINCE_GRID_REGIONS = Map.ofEntries(
            Map.entry("11", "GRID_NORTH"),
            Map.entry("12", "GRID_NORTH"),
            Map.entry("13", "GRID_NORTH"),
            Map.entry("14", "GRID_NORTH"),
            Map.entry("37", "GRID_NORTH"),
            Map.entry("21", "GRID_NORTHEAST"),
            Map.entry("22", "GRID_NORTHEAST"),
            Map.entry("23", "GRID_NORTHEAST"),
            Map.entry("31", "GRID_EAST"),
            Map.entry("32", "GRID_EAST"),
            Map.entry("33", "GRID_EAST"),
            Map.entry("34", "GRID_EAST"),
            Map.entry("35", "GRID_EAST"),
            Map.entry("36", "GRID_CENTRAL"),
            Map.entry("41", "GRID_CENTRAL"),
            Map.entry("42", "GRID_CENTRAL"),
            Map.entry("43", "GRID_CENTRAL"),
            Map.entry("44", "GRID_SOUTH"),
            Map.entry("45", "GRID_SOUTH"),
            Map.entry("46", "GRID_SOUTH"),
            Map.entry("52", "GRID_SOUTH"),
            Map.entry("53", "GRID_SOUTH"),
            Map.entry("50", "GRID_SOUTHWEST"),
            Map.entry("51", "GRID_SOUTHWEST"),
            Map.entry("61", "GRID_NORTHWEST"),
            Map.entry("62", "GRID_NORTHWEST"),
            Map.entry("63", "GRID_NORTHWEST"),
            Map.entry("64", "GRID_NORTHWEST"),
            Map.entry("65", "GRID_NORTHWEST"));

    private ElectricityGridRegions() {
    }

    public static String provinceCode(String buildingRegionCode) {
        if (buildingRegionCode == null) return null;
        String normalized = buildingRegionCode.trim().toUpperCase(Locale.ROOT);
        if (normalized.matches("\\d{6}")) {
            return normalized.substring(0, 2) + "0000";
        }
        return normalized;
    }

    public static String gridRegionCode(String buildingRegionCode) {
        if (buildingRegionCode == null) return null;
        String normalized = buildingRegionCode.trim();
        if (!normalized.matches("\\d{6}")) return null;
        String province = normalized.substring(0, 2);
        if (!"15".equals(province)) return PROVINCE_GRID_REGIONS.get(province);
        String prefecture = normalized.substring(0, 4);
        if (MONGOLIA_EASTERN_PREFECTURES.contains(prefecture)) return "GRID_NORTHEAST";
        if (MONGOLIA_WESTERN_PREFECTURES.contains(prefecture)) return "GRID_NORTH";
        return null;
    }
}
