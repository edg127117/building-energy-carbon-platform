package com.platform.iot.daikin.control;

import com.platform.framework.exception.BusinessException;

/** 仅暴露稳定错误码；外部响应、凭据和 SQL 不跨越控制边界。 */
public final class DaikinControlErrors {
    private DaikinControlErrors() { }
    public static BusinessException error(int status, String code) {
        return new BusinessException(status, "HVAC_CONTROL_" + code, switch (code) {
            case "DISABLED" -> "空调控制尚未启用";
            case "FORBIDDEN" -> "无空调控制权限";
            case "BUSY" -> "设备存在未结束的控制操作";
            case "REVISION_CHANGED" -> "设备状态或配置已变化，请刷新后确认";
            case "INVALID_REQUEST" -> "控制参数无效";
            case "SOURCE_PAUSED" -> "厂家来源暂不可用，请稍后重新核验";
            default -> "当前设备不满足控制条件，请刷新设备状态或查看操作记录";
        });
    }
}
