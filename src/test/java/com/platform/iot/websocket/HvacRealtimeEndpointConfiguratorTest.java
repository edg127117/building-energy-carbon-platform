package com.platform.iot.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HvacRealtimeEndpointConfiguratorTest {

    @Test
    void createsEndpointThroughTheRegisteredSpringBeanFactory() throws Exception {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        AutowireCapableBeanFactory beanFactory = mock(AutowireCapableBeanFactory.class);
        WebSocketServer endpoint = mock(WebSocketServer.class);
        when(applicationContext.getAutowireCapableBeanFactory()).thenReturn(beanFactory);
        when(beanFactory.createBean(WebSocketServer.class)).thenReturn(endpoint);

        HvacRealtimeEndpointConfigurator configurator =
                new HvacRealtimeEndpointConfigurator();
        configurator.setApplicationContext(applicationContext);
        try {
            assertThat(configurator.getEndpointInstance(WebSocketServer.class))
                    .isSameAs(endpoint);
        } finally {
            configurator.destroy();
        }
    }

    @Test
    void rejectsEndpointCreationAfterItsSpringContextCloses() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        AutowireCapableBeanFactory beanFactory = mock(AutowireCapableBeanFactory.class);
        when(applicationContext.getAutowireCapableBeanFactory()).thenReturn(beanFactory);

        HvacRealtimeEndpointConfigurator configurator =
                new HvacRealtimeEndpointConfigurator();
        configurator.setApplicationContext(applicationContext);
        configurator.destroy();

        assertThatThrownBy(() -> configurator.getEndpointInstance(WebSocketServer.class))
                .isInstanceOf(InstantiationException.class)
                .hasMessageContaining("Spring context is unavailable");
    }
}
