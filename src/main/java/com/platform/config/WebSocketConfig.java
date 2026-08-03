package com.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

/**
 * 在非测试环境注册 Jakarta WebSocket 端点导出器。
 *
 * <p>{@link ServerEndpointExporter} 负责发现并发布 {@code /ws/hvac}；测试 Profile
 * 不创建该 Bean，避免普通 Spring 测试依赖真实 Servlet 容器或开放网络端点。</p>
 */
@Configuration
@Profile("!test")
public class WebSocketConfig {

    /** 返回负责注册所有 {@code @ServerEndpoint} 类的容器桥接组件。 */
    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }
}
