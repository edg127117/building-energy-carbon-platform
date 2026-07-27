package com.platform.iot.temporal;

import com.platform.iot.formula.model.FormulaCalculationException;
import com.platform.iot.formula.model.IndicatorMinuteKey;
import com.platform.iot.formula.model.IndicatorMinuteResult;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface IndicatorMinuteRepository {
    void saveSuccesses(List<IndicatorMinuteResult> results);

    void saveExceptions(List<FormulaCalculationException> exceptions);

    Optional<IndicatorMinuteResult> findSuccess(String indicatorId, long minuteStart);

    Optional<FormulaCalculationException> findException(String indicatorId, long minuteStart);

    List<IndicatorMinuteResult> findLatestSuccesses(List<String> indicatorIds);

    List<FormulaCalculationException> findLatestExceptions(List<String> indicatorIds);

    List<IndicatorMinuteResult> findHistory(
            String indicatorId, long fromInclusive, long toExclusive);

    Set<IndicatorMinuteKey> findSuccessfulKeys(
            List<String> indicatorIds, long fromInclusive, long toExclusive);
}
