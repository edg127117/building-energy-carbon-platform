package com.platform.iot.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证协议组件可被端点构造器注入，且 test Profile 不会开放真实 WebSocket 端口。 */
@SpringBootTest
@ActiveProfiles("test")
class HvacRealtimeSpringContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void startsTheRealtimeEndpointDependenciesWithoutExportingAnEndpoint() {
        assertThat(applicationContext.getBean(HvacRealtimeProtocol.class)).isNotNull();
        assertThat(applicationContext.getBean(HvacRealtimeSessionRegistry.class)).isNotNull();
        assertThat(applicationContext.getBeansOfType(ServerEndpointExporter.class)).isEmpty();
    }
}
