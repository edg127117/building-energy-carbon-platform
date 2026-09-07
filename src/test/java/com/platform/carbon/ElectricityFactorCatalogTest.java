package com.platform.carbon;

import com.platform.carbon.ElectricityFactorCatalog.Entry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ElectricityFactorCatalogTest {
    private static final Set<String> PROVINCES = Set.of(
            "110000", "120000", "130000", "140000", "150000",
            "210000", "220000", "230000", "310000", "320000",
            "330000", "340000", "350000", "360000", "370000",
            "410000", "420000", "430000", "440000", "450000",
            "460000", "500000", "510000", "520000", "530000",
            "610000", "620000", "630000", "640000", "650000");
    private static final Set<String> GRID_REGIONS = Set.of(
            "GRID_NORTH", "GRID_NORTHEAST", "GRID_EAST", "GRID_CENTRAL",
            "GRID_NORTHWEST", "GRID_SOUTH", "GRID_SOUTHWEST");

    private final ElectricityFactorCatalog catalog = new ElectricityFactorCatalog();

    @Test
    void loadsUniqueEntriesForAllConfirmedAccountingYears() {
        List<Entry> entries = catalog.entries();

        assertThat(entries).hasSize(114);
        assertThat(entries).extracting(Entry::entryCode).doesNotHaveDuplicates();
        assertThat(catalog.entry(" electricity_average_2023_national "))
                .hasValueSatisfying(value -> assertThat(value.value())
                        .isEqualByComparingTo("0.5568"));
        assertThat(catalog.entry("ELECTRICITY_AVERAGE_2099_NATIONAL")).isEmpty();
    }

    @Test
    void keepsTheFullProvinceAndGridRegionSetsForEachSourceYear() {
        Map<Integer, Integer> dataYears = Map.of(2023, 2021, 2024, 2022, 2025, 2023);

        dataYears.forEach((accountingYear, dataYear) -> {
            List<Entry> yearEntries = catalog.entries().stream()
                    .filter(value -> value.accountingYear() == accountingYear)
                    .toList();

            assertThat(yearEntries).hasSize(38);
            assertThat(yearEntries).allSatisfy(value -> assertThat(value.dataYear()).isEqualTo(dataYear));
            assertThat(yearEntries).filteredOn(value -> "PROVINCE".equals(value.applicabilityLevel()))
                    .extracting(Entry::regionCode)
                    .containsExactlyInAnyOrderElementsOf(PROVINCES);
            assertThat(yearEntries).filteredOn(value -> "GRID_REGION".equals(value.applicabilityLevel()))
                    .extracting(Entry::regionCode)
                    .containsExactlyInAnyOrderElementsOf(GRID_REGIONS);
            assertThat(yearEntries).filteredOn(value -> "NATIONAL".equals(value.applicabilityLevel()))
                    .singleElement()
                    .satisfies(value -> assertThat(value.regionCode()).isNull());
        });
    }

    @Test
    void retainsOfficialValuesAndPublicSourceMetadata() {
        // PDF 跨页提取会将下一页页码粘到安徽数值末尾，目录只保留表中四位小数。
        assertEntry("ELECTRICITY_AVERAGE_2023_PROVINCE_340000", "0.7075", 2023, 2021,
                "公告2024年第12号", LocalDate.of(2024, 4, 12),
                "W020240412827267102800.pdf");
        assertEntry("ELECTRICITY_AVERAGE_2023_GRID_NORTH", "0.7120", 2023, 2021,
                "公告2024年第12号", LocalDate.of(2024, 4, 12),
                "W020240412827267102800.pdf");
        assertEntry("ELECTRICITY_AVERAGE_2024_PROVINCE_440000", "0.4403", 2024, 2022,
                "公告2024年第33号", LocalDate.of(2024, 12, 26),
                "t20241226_1099413.html");
        assertEntry("ELECTRICITY_AVERAGE_2025_PROVINCE_510000", "0.1564", 2025, 2023,
                "公告2025年第47号", LocalDate.of(2025, 12, 31),
                "W020251231726284332528.pdf");
    }

    @Test
    void containsOnlyTheAverageFactorScopeAndExcludesOtherPublishedValues() {
        assertThat(catalog.entries()).allSatisfy(value -> {
            assertThat(value.value().scale()).isEqualTo(4);
            assertThat(value.sourceCode()).contains("ELECTRICITY_AVERAGE");
            assertThat(value.sourceName()).contains("电力平均");
            assertThat(value.evidenceReference()).startsWith("https://www.mee.gov.cn/");
        });
        assertThat(catalog.entries()).extracting(Entry::value).doesNotContain(
                new BigDecimal("0.5703"), new BigDecimal("0.5942"), new BigDecimal("0.8426"),
                new BigDecimal("0.5856"), new BigDecimal("0.8325"), new BigDecimal("0.6096"),
                new BigDecimal("0.8273"));
        assertThat(catalog.entries()).filteredOn(value -> "NATIONAL".equals(value.applicabilityLevel()))
                .extracting(Entry::value)
                .containsExactlyInAnyOrder(
                        new BigDecimal("0.5568"), new BigDecimal("0.5366"), new BigDecimal("0.5306"));
    }

    private void assertEntry(String code, String factor, int accountingYear, int dataYear,
                             String documentReference, LocalDate publishedOn, String sourceUrlPart) {
        Entry entry = catalog.entry(code).orElseThrow();

        assertThat(entry.value()).isEqualByComparingTo(factor);
        assertThat(entry.accountingYear()).isEqualTo(accountingYear);
        assertThat(entry.dataYear()).isEqualTo(dataYear);
        assertThat(entry.documentReference()).isEqualTo(documentReference);
        assertThat(entry.publisher()).isEqualTo("生态环境部、国家统计局");
        assertThat(entry.publishedOn()).isEqualTo(publishedOn);
        assertThat(entry.evidenceReference()).contains(sourceUrlPart);
    }
}
