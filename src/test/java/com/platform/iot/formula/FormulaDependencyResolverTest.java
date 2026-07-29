package com.platform.iot.formula;

import com.platform.hvac.model.entity.BizIndicator;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FormulaDependencyResolverTest {

    private final DataPointConfigProvider pointConfigProvider =
            mock(DataPointConfigProvider.class);
    private final FormulaDependencyResolver resolver =
            new FormulaDependencyResolver(pointConfigProvider);

    @Test
    void devicePointOnlyAffectsSameBuildingDeviceAndSemanticDependency() {
        PointRuntimeConfig point = point("P1", "BLD1", "E1", "MAIN", "GW");
        when(pointConfigProvider.findAll()).thenReturn(List.of(point));
        IndicatorFormula chiller = formula("WCR_COP", Set.of(FormulaKeys.CHILLER_FLOW));
        IndicatorFormula pump = formula("PUMP_EFF", Set.of(FormulaKeys.PUMP_FLOW));

        Set<String> affected = resolver.resolve(List.of(
                        indicator("I1", "WCR_COP", "BLD1", "E1"),
                        indicator("I2", "WCR_COP", "BLD1", "E2"),
                        indicator("I3", "PUMP_EFF", "BLD1", "E1"),
                        indicator("I4", "WCR_COP", "BLD2", "E1")),
                Set.of("P1"), List.of(chiller, pump));

        assertThat(affected).containsExactly("I1");
    }

    @Test
    void environmentPointAffectsAllDependentIndicatorsInSameBuilding() {
        PointRuntimeConfig point = environmentPoint(
                "P1", "BLD1", "DBO", "TDB");
        when(pointConfigProvider.findAll()).thenReturn(List.of(point));
        IndicatorFormula tower = formula(
                "TOWER_EFF", Set.of(FormulaKeys.OUTDOOR_TDB));

        Set<String> affected = resolver.resolve(List.of(
                        indicator("I1", "TOWER_EFF", "BLD1", "E1"),
                        indicator("I2", "TOWER_EFF", "BLD1", "E2"),
                        indicator("I3", "TOWER_EFF", "BLD2", "E3")),
                Set.of("P1"), List.of(tower));

        assertThat(affected).containsExactlyInAnyOrder("I1", "I2");
    }

    @Test
    void unknownNonCalculationAndUnrelatedPointsDoNotTriggerRecalculation() {
        PointRuntimeConfig unrelated = point("P1", "BLD1", "E1", "MAIN", "Other");
        PointRuntimeConfig disabled = new PointRuntimeConfig(
                "P2", "P2", "P2", "BLD1", "G1", "E1", "E1",
                "FAMILY", "MAIN", "GW", "ANALOG", null, "ONLINE", 0, null, null);
        when(pointConfigProvider.findAll()).thenReturn(List.of(unrelated, disabled));

        assertThat(resolver.resolve(
                List.of(indicator("I1", "WCR_COP", "BLD1", "E1")),
                Set.of("UNKNOWN", "P1", "P2"),
                List.of(formula("WCR_COP", Set.of(FormulaKeys.CHILLER_FLOW)))))
                .isEmpty();
    }

    @Test
    void formulasDeclarePrimaryAndFallbackInputsAsDependencies() {
        assertThat(new ChillerCopFormula().requiredInputKeys())
                .contains(FormulaKeys.CHILLER_PPE, FormulaKeys.CHILLER_VOLTAGE,
                        FormulaKeys.CHILLER_CURRENT, FormulaKeys.CHILLER_PF);
        assertThat(new CoolingTowerEfficiencyFormula(
                new PsychrometricWetBulbCalculator(),
                new com.platform.config.FormulaProperties()).requiredInputKeys())
                .contains(FormulaKeys.TOWER_TWB, FormulaKeys.OUTDOOR_TDB,
                        FormulaKeys.OUTDOOR_RH);
    }

    private static IndicatorFormula formula(String code, Set<String> dependencies) {
        IndicatorFormula formula = mock(IndicatorFormula.class);
        when(formula.indicatorCode()).thenReturn(code);
        when(formula.requiredInputKeys()).thenReturn(dependencies);
        return formula;
    }

    private static BizIndicator indicator(
            String id, String code, String buildingId, String equipId) {
        BizIndicator indicator = new BizIndicator();
        indicator.setIndicatorId(id);
        indicator.setIndicatorCode(code);
        indicator.setBuildingId(buildingId);
        indicator.setEquipId(equipId);
        indicator.setStatus(1);
        return indicator;
    }

    private static PointRuntimeConfig point(
            String pointId,
            String buildingId,
            String equipId,
            String componentCode,
            String suffixCode) {
        return new PointRuntimeConfig(
                pointId, pointId, pointId, buildingId, "G1", equipId, equipId,
                "FAMILY", componentCode, suffixCode,
                "ANALOG", null, "ONLINE", 1, null, null);
    }

    private static PointRuntimeConfig environmentPoint(
            String pointId,
            String buildingId,
            String familyCode,
            String suffixCode) {
        return new PointRuntimeConfig(
                pointId, pointId, pointId, buildingId, "G1", null, null,
                familyCode, null, suffixCode,
                "ANALOG", null, "ONLINE", 1, null, null);
    }
}
