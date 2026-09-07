package com.platform.carbon;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ElectricityGridRegionsTest {

    @Test
    void preservesLegacyProvinceNormalizationForAdministrativeAndOtherCodes() {
        assertThat(ElectricityGridRegions.provinceCode(" 310101 ")).isEqualTo("310000");
        assertThat(ElectricityGridRegions.provinceCode("150400")).isEqualTo("150000");
        assertThat(ElectricityGridRegions.provinceCode(" building-a ")).isEqualTo("BUILDING-A");
        assertThat(ElectricityGridRegions.provinceCode(null)).isNull();
    }

    @Test
    void mapsKnownProvincialAreasToTheOfficialGridRegions() {
        assertThat(ElectricityGridRegions.gridRegionCode("110101")).isEqualTo("GRID_NORTH");
        assertThat(ElectricityGridRegions.gridRegionCode("210100")).isEqualTo("GRID_NORTHEAST");
        assertThat(ElectricityGridRegions.gridRegionCode("310101")).isEqualTo("GRID_EAST");
        assertThat(ElectricityGridRegions.gridRegionCode("360100")).isEqualTo("GRID_CENTRAL");
        assertThat(ElectricityGridRegions.gridRegionCode("610100")).isEqualTo("GRID_NORTHWEST");
        assertThat(ElectricityGridRegions.gridRegionCode("440100")).isEqualTo("GRID_SOUTH");
        assertThat(ElectricityGridRegions.gridRegionCode("500101")).isEqualTo("GRID_SOUTHWEST");
    }

    @Test
    void distinguishesEasternAndWesternInnerMongoliaOnlyWhenThePrefectureIsKnown() {
        assertThat(ElectricityGridRegions.gridRegionCode("150400")).isEqualTo("GRID_NORTHEAST");
        assertThat(ElectricityGridRegions.gridRegionCode("150500")).isEqualTo("GRID_NORTHEAST");
        assertThat(ElectricityGridRegions.gridRegionCode("150700")).isEqualTo("GRID_NORTHEAST");
        assertThat(ElectricityGridRegions.gridRegionCode("152200")).isEqualTo("GRID_NORTHEAST");

        assertThat(ElectricityGridRegions.gridRegionCode("150100")).isEqualTo("GRID_NORTH");
        assertThat(ElectricityGridRegions.gridRegionCode("150200")).isEqualTo("GRID_NORTH");
        assertThat(ElectricityGridRegions.gridRegionCode("150300")).isEqualTo("GRID_NORTH");
        assertThat(ElectricityGridRegions.gridRegionCode("150600")).isEqualTo("GRID_NORTH");
        assertThat(ElectricityGridRegions.gridRegionCode("150800")).isEqualTo("GRID_NORTH");
        assertThat(ElectricityGridRegions.gridRegionCode("150900")).isEqualTo("GRID_NORTH");
        assertThat(ElectricityGridRegions.gridRegionCode("152500")).isEqualTo("GRID_NORTH");
        assertThat(ElectricityGridRegions.gridRegionCode("152900")).isEqualTo("GRID_NORTH");

        assertThat(ElectricityGridRegions.gridRegionCode("150000")).isNull();
        assertThat(ElectricityGridRegions.gridRegionCode("159999")).isNull();
    }

    @Test
    void leavesUnsupportedOrNonAdministrativeLocationsWithoutAGridGuess() {
        assertThat(ElectricityGridRegions.gridRegionCode("540100")).isNull();
        assertThat(ElectricityGridRegions.gridRegionCode("BUILDING-A")).isNull();
        assertThat(ElectricityGridRegions.gridRegionCode(null)).isNull();
    }
}
