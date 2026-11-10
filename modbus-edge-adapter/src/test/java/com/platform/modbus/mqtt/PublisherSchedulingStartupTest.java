package com.platform.modbus.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.modbus.config.ModbusEdgeProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 启用实际调度后验证Duration配置；没有采集器，也不创建外部MQTT连接。 */
class PublisherSchedulingStartupTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(Scheduling.class);
    @Configuration(proxyBeanMethods=false)
    @EnableScheduling
    static class Scheduling {
        @Bean MqttTelemetryPublisher publisher() {
            return new MqttTelemetryPublisher(new ModbusEdgeProperties(),mock(MqttSslContextFactory.class),new MqttFailureClassifier(),new ObjectMapper(),new SimpleMeterRegistry(),Clock.systemUTC());
        }
    }
    @Test void defaultSimpleDurationStarts(){runner.run(c->assertThat(c).hasNotFailed());}
    @Test void isoDurationStarts(){runner.withPropertyValues("modbus-edge.mqtt.ack-scan-delay=PT5S").run(c->assertThat(c).hasNotFailed());}
    @Test void millisecondDurationStarts(){runner.withPropertyValues("modbus-edge.mqtt.ack-scan-delay=5000").run(c->assertThat(c).hasNotFailed());}
}
