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
            case "BATCH_SOURCE_BACKPRESSURE" -> "厂家限流或忙碌，本批次该来源的未发送项已停止";
            case "SOURCE_PAUSED" -> "厂家来源暂不可用，请稍后重新核验";
            case "PREVIEW_EXPIRED" -> "批量预检已过期，请重新核验设备能力";
            case "PREVIEW_BUSY" -> "批量能力读取中，请稍后重试";
            case "PREVIEW_NOT_FOUND", "BATCH_NOT_FOUND" -> "批次或预检记录不存在，或不属于当前账号";
            case "LINKED_CONFIRMATION_REQUIRED" -> "操作可能影响未选空调，请确认关联范围";
            case "LINKED_SCOPE_CHANGED" -> "关联范围已变化，本次操作未发送，请重新预检";
            case "DISPATCH_EXPIRED" -> "派发租约已失效，本次操作未发送";
            case "IDEMPOTENCY_CONFLICT" -> "重复请求的参数不一致，请核对原批次";
            default -> "当前设备不满足控制条件，请刷新设备状态或查看操作记录";
        });
    }
}
