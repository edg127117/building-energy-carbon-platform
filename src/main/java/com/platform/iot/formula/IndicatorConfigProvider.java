package com.platform.iot.formula;

import com.platform.hvac.model.entity.BizIndicator;

import java.util.Collection;
import java.util.Optional;

/**
 * Active formula indicator configuration boundary.
 */
public interface IndicatorConfigProvider {

    Collection<BizIndicator> findAllActive();

    Optional<BizIndicator> findActive(String indicatorId);
}
