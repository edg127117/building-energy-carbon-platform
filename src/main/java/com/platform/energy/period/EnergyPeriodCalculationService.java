package com.platform.energy.period;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.energy.aggregation.EnergyAggregationExecutor;
import com.platform.energy.aggregation.EnergyAggregationInputAssembler;
import com.platform.energy.aggregation.EnergyAggregationModels.AggregationQuery;
import com.platform.energy.aggregation.EnergyAggregationModels.AggregationResult;
import com.platform.energy.conversion.EnergyConversionService;
import com.platform.energy.conversion.api.EnergyConversionContracts.SimulationRequest;
import com.platform.energy.conversion.api.EnergyConversionContracts.SimulationResultView;
import com.platform.energy.period.EnergyPeriodModels.ConversionSelection;
import com.platform.energy.period.EnergyPeriodModels.PeriodPolicyVersion;
import com.platform.energy.period.EnergyPeriodModels.PeriodWindow;
import com.platform.energy.period.EnergyPeriodModels.ProjectionCalculation;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
/** 组合已冻结的公共聚合与折标契约，生成可追溯研发周期结果。 */
public class EnergyPeriodCalculationService {
    public static final String TCE_RULE_UNAVAILABLE = "TCE_RULE_UNAVAILABLE";

    private final EnergyAggregationExecutor aggregationExecutor;
    private final EnergyAggregationInputAssembler inputAssembler;
    private final EnergyConversionService conversionService;
    private final ObjectMapper objectMapper;

    public ProjectionCalculation calculate(
            long userId, Collection<String> roles, String buildingId, String pointId,
            PeriodWindow window, PeriodPolicyVersion policy, ConversionSelection selection,
            Instant calculationAsOf) {
        AggregationResult aggregation = aggregationExecutor.execute(new AggregationQuery(
                        buildingId, pointId, window.startInclusive(), window.endExclusive(),
                        calculationAsOf),
                query -> inputAssembler.load(userId, roles, query));
        List<String> issues = new ArrayList<>();
        SimulationResultView conversion = null;
        if (selection == null) {
            issues.add(TCE_RULE_UNAVAILABLE);
        } else {
            try {
                conversion = conversionService.simulate(userId, roles, new SimulationRequest(
                        buildingId, aggregation.energyItemCode(), aggregation.quantity(),
                        aggregation.resultUnitCode(), selection.method(), selection.perspective(),
                        selection.consumptionScope(), selection.regionCode(),
                        LocalDateTime.ofInstant(window.startInclusive(), ZoneId.of(window.timezoneId()))));
            } catch (BusinessException exception) {
                if (exception.getCode() == 409 || exception.getCode() == 404) {
                    issues.add(TCE_RULE_UNAVAILABLE);
                } else {
                    throw exception;
                }
            }
        }
        Map<String, Object> evidence = evidence(aggregation, policy, conversion);
        String evidenceJson = json(evidence);
        return new ProjectionCalculation("DEVELOPMENT_SIMULATION", buildingId, pointId, window,
                policy.versionId(), aggregation.energyItemCode(), aggregation.quantity(),
                aggregation.resultUnitCode(), conversion == null ? null : conversion.tce(),
                conversion == null ? null : conversion.resultUnitCode(), aggregation.coverageRatio(),
                issues, evidenceJson, digest(evidenceJson), selection == null ? null : json(selection),
                aggregation.activityWatermark(), LocalDateTime.now());
    }

    public ConversionSelection readSelection(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readValue(value, ConversionSelection.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored conversion selection is invalid", exception);
        }
    }

    private static Map<String, Object> evidence(
            AggregationResult aggregation, PeriodPolicyVersion policy,
            SimulationResultView conversion) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("periodPolicyVersionId", policy.versionId());
        value.put("timezoneId", policy.timezoneId());
        value.put("activityWatermark", aggregation.activityWatermark());
        value.put("relationVersionId", aggregation.relationVersionId());
        value.put("pointBindingVersionId", aggregation.pointBindingVersionId());
        value.put("qualityPolicyVersions", aggregation.qualityPolicyVersions());
        value.put("meterEventVersions", aggregation.meterEventVersions());
        value.put("correctionVersions", aggregation.correctionVersions());
        value.put("integrationPolicyVersionId", aggregation.integrationPolicyVersionId());
        value.put("coverageRatio", aggregation.coverageRatio());
        value.put("maximumObservedGapSeconds", aggregation.maximumObservedGapSeconds());
        if (conversion != null) {
            value.put("energyItemVersionId", conversion.energyItemVersionId());
            value.put("inputUnitVersionId", conversion.inputUnitVersionId());
            value.put("applicableInputUnitVersionId", conversion.applicableInputUnitVersionId());
            value.put("parameterVersionId", conversion.parameterVersionId());
            value.put("formulaVersionId", conversion.formulaVersionId());
            value.put("standardCoalLhvVersionId", conversion.standardCoalLhvVersionId());
            value.put("algorithmCode", conversion.algorithmCode());
        }
        return value;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Energy period evidence serialization failed", exception);
        }
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
