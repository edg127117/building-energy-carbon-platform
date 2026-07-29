package com.platform.iot.dataquality.event;

/**
 * 一条迟到真实事件已经成功写入 TDengine 的通知。
 *
 * <p>接入层只发布原始证据已经落盘的事件；质量修正层随后按测点和分钟重新读取
 * 全部真实样本，避免把单条 MQTT 载荷误当成完整分钟。</p>
 *
 * @param pointId 内部测点 ID
 * @param buildingId 测点所属建筑 ID
 * @param minuteStart 设备采集时间所在自然分钟起点
 * @param receivedAt 服务器接收本次真实事件的时间
 */
public record HvacLateRealEventStoredEvent(
        String pointId,
        String buildingId,
        long minuteStart,
        long receivedAt) {
}
