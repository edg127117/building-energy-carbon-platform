package com.platform.carbon;

import java.util.Collection;
import static com.platform.carbon.CarbonIntensityContracts.Actual;

/** 年内累计由年度概览模块提供；此端口只传递已有强度，不引入另一套累计或面积算法。 */
public interface CarbonIntensityYtdReader {
    Actual read(long user, Collection<String> roles, String buildingId, int year, int throughMonth, String timezoneId);
}
