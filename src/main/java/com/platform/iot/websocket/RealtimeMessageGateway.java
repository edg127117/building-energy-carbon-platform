package com.platform.iot.websocket;

/**
 * 公式模块向实时消息通道广播数据的边界接口。
 *
 * <p>业务发布器依赖接口而不是静态 WebSocket 服务，既保持模块边界，也便于
 * 自动化测试替换为 Fake 实现而不启动真实网络端点。</p>
 */
public interface RealtimeMessageGateway {

    /** 向当前已连接的大屏会话广播一条完整消息。 */
    void broadcast(String message);
}
