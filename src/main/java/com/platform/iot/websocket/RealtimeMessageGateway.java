package com.platform.iot.websocket;

/**
 * 公式模块向建筑定向实时消息通道发布数据的边界接口。
 *
 * <p>业务发布器依赖接口而不是静态 WebSocket 服务，既保持模块边界，也便于
 * 自动化测试替换为 Fake 实现而不启动真实网络端点。</p>
 */
public interface RealtimeMessageGateway {

    /** 仅向已认证并订阅目标建筑的会话尽力发送一条完整指标消息。 */
    void sendToBuilding(String buildingId, String message);
}
