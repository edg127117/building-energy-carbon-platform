package com.platform.iot.deviceparameter.query;

import com.platform.iot.deviceparameter.DeviceParameterModels.ResolvedParameters;

import java.time.LocalDateTime;
import java.util.Collection;

/** 公式按业务时间和认知时间批量读取正式设备参数的唯一边界。 */
public interface DeviceParameterResolver {
    ResolvedParameters resolve(
            String equipmentId,
            LocalDateTime businessTime,
            LocalDateTime knowledgeTime,
            Collection<String> requiredParameterCodes);
}
