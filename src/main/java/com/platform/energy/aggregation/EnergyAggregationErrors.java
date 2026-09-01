package com.platform.energy.aggregation;

import com.platform.framework.exception.BusinessException;

/** 活动量聚合对外使用的稳定错误码。 */
public final class EnergyAggregationErrors {
    public static final String UNAUTHORIZED = "ENERGY_AGGREGATION_UNAUTHORIZED";
    public static final String FORBIDDEN = "ENERGY_AGGREGATION_FORBIDDEN";
    public static final String VALIDATION_FAILED = "ENERGY_AGGREGATION_VALIDATION_FAILED";
    public static final String INPUT_INCOMPLETE = "ENERGY_AGGREGATION_INPUT_INCOMPLETE";
    public static final String ANCHOR_MISSING = "ENERGY_AGGREGATION_ANCHOR_MISSING";
    public static final String NEGATIVE_DELTA_UNCLASSIFIED = "ENERGY_AGGREGATION_NEGATIVE_DELTA_UNCLASSIFIED";
    public static final String EVENT_EVIDENCE_CONFLICT = "ENERGY_AGGREGATION_EVENT_EVIDENCE_CONFLICT";
    public static final String CORRECTION_CONFLICT = "ENERGY_AGGREGATION_CORRECTION_CONFLICT";
    public static final String PERIOD_COVERAGE_INVALID = "ENERGY_AGGREGATION_PERIOD_COVERAGE_INVALID";
    public static final String INTEGRATION_POLICY_REQUIRED = "ENERGY_AGGREGATION_INTEGRATION_POLICY_REQUIRED";
    public static final String COVERAGE_INSUFFICIENT = "ENERGY_AGGREGATION_COVERAGE_INSUFFICIENT";

    private EnergyAggregationErrors() {
    }

    public static BusinessException error(String code, String message) {
        return new BusinessException(409, code, message);
    }
}
