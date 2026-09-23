package com.platform.iot.daikin.onboarding;

/** 待接入页面的人工作业位置；资产参考编号不是正式设备编码。 */
public record DaikinPendingLocationView(String roomSpaceId, String roomCode,
                                        String monitorAddress, String assetReferenceCode) {
}
