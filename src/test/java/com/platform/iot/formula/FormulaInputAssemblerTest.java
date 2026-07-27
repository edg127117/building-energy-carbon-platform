package com.platform.iot.formula;

import com.platform.hvac.model.entity.BizIndicator;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormulaInputAssemblerTest {
    private static final long MINUTE = 1_800_000_000_000L;
    private final FormulaInputAssembler assembler = new FormulaInputAssembler();

    @Test
    void isolatesBuildingEquipmentMinuteAndCalculationFlag() {
        FormulaInputs inputs = assembler.assemble(indicator("BLD001", "EQUIP_SHARED"),
                MINUTE, List.of(
                        point("P1", "BLD001", "EQUIP_SHARED", "WCR", "MAIN", "GW", 1, MINUTE, 100),
                        point("P2", "BLD001", "OTHER", "WCR", "MAIN", "GW", 1, MINUTE, 200),
                        point("P3", "BLD002", "EQUIP_SHARED", "WCR", "MAIN", "GW", 1, MINUTE, 300),
                        point("P4", "BLD001", "EQUIP_SHARED", "WCR", "MAIN", "PPE", 0, MINUTE, 400),
                        point("P5", "BLD001", "EQUIP_SHARED", "WCR", "MAIN", "TWin", 1,
                                MINUTE + 60_000L, 12)));

        assertThat(inputs.asMap()).containsOnlyKeys("MAIN/GW");
        assertThat(inputs.find("MAIN/GW")).get()
                .extracting(input -> input.value()).isEqualTo(100.0);
    }

    @Test
    void environmentInputsUseFamilyAndRemainBuildingScoped() {
        FormulaInputs inputs = assembler.assemble(indicator("BLD001", "EQUIP_TOWER"),
                MINUTE, List.of(
                        point("P1", "BLD001", null, "DBO", "ENV", "TDB", 1, MINUTE, 35),
                        point("P2", "BLD001", null, "RHO", "ENV", "RH", 1, MINUTE, 60),
                        point("P3", "BLD002", null, "DBO", "ENV", "TDB", 1, MINUTE, 99)));

        assertThat(inputs.asMap()).containsOnlyKeys("DBO/TDB", "RHO/RH");
    }

    @Test
    void duplicateSemanticKeysAreRejectedInsteadOfUsingListOrder() {
        assertThatThrownBy(() -> assembler.assemble(indicator("BLD001", "EQUIP_SHARED"),
                MINUTE, List.of(
                        point("P1", "BLD001", "EQUIP_SHARED", "WCR", "MAIN", "GW", 1, MINUTE, 100),
                        point("P2", "BLD001", "EQUIP_SHARED", "WCR", "MAIN", "GW", 1, MINUTE, 200))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAIN/GW");
    }

    private static BizIndicator indicator(String buildingId, String equipId) {
        BizIndicator indicator = new BizIndicator();
        indicator.setBuildingId(buildingId);
        indicator.setEquipId(equipId);
        return indicator;
    }

    private static RawMinuteAggregate point(
            String pointId, String buildingId, String equipId, String family,
            String component, String suffix, int isForCalc, long minute, double value) {
        return new RawMinuteAggregate(pointId, pointId, buildingId, "GROUP001",
                equipId, equipId, family, component, suffix, isForCalc, minute,
                value, value, value, 1, 0, minute, minute, minute);
    }
}
