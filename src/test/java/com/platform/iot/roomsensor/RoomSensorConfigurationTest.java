package com.platform.iot.roomsensor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.TdengineProperties;
import com.platform.iot.communication.CommunicationEvidenceRecorder;
import com.platform.iot.communication.DeviceCommunicationService;
import com.platform.iot.communication.RoomSensorCommunicationQueue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 装配验证使用内存 MySQL 替身和 TDengine Mock，不建立真实外部连接。 */
class RoomSensorConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Scheduling.class, RoomSensorSchedulingConfig.class, RoomSensorIngestor.class,
                    RoomSensorParser.class, RoomSensorWorker.class, RoomSensorHistoryStore.class, RoomSensorCommunicationQueue.class,
                    RoomSensorDoorStateService.class, RoomSensorRadarStateService.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(CommunicationEvidenceRecorder.class, () -> mock(CommunicationEvidenceRecorder.class))
            .withBean(DeviceCommunicationService.class, () -> mock(DeviceCommunicationService.class))
            .withBean(TdengineProperties.class, () -> {
                var properties = new TdengineProperties(); properties.setDatabase("test_room_sensor"); return properties;
            })
            .withBean("mysqlJdbcTemplate", JdbcTemplate.class, () -> {
                var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:room-config-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "sa", ""));
                jdbc.execute("CREATE TABLE biz_room_sensor_ingest_pending(event_id VARCHAR(36),observation_json VARCHAR(1000),received_at BIGINT,attempts INT,next_attempt_at BIGINT)");
                jdbc.execute("CREATE TABLE biz_room_sensor_comm_pending(evidence_id VARCHAR(36),received_at BIGINT)");
                return jdbc;
            })
            .withBean("taosJdbcTemplate", JdbcTemplate.class, () -> mock(JdbcTemplate.class));

    @Test void disabledByDefaultCreatesNoWriterOrScheduler() {
        context.run(app -> assertThat(app).hasNotFailed().doesNotHaveBean(RoomSensorIngestor.class)
                .doesNotHaveBean(RoomSensorWorker.class).doesNotHaveBean(RoomSensorHistoryStore.class)
                .doesNotHaveBean(RoomSensorDoorStateService.class)
                .doesNotHaveBean(RoomSensorRadarStateService.class)
                .doesNotHaveBean(RoomSensorCommunicationQueue.class)
                .doesNotHaveBean("roomSensorTaskScheduler"));
    }

    @Test void enabledModuleUsesDedicatedSchedulerAndExplicitDataSources() {
        context.withPropertyValues("room-sensor.enabled=true", "room-sensor.flush-delay-ms=3600000",
                "room-sensor.cleanup-delay-ms=3600000").run(app -> assertThat(app).hasNotFailed()
                .hasSingleBean(RoomSensorIngestor.class).hasSingleBean(RoomSensorWorker.class)
                .hasSingleBean(RoomSensorHistoryStore.class).hasSingleBean(RoomSensorDoorStateService.class)
                .hasSingleBean(RoomSensorRadarStateService.class)
                .hasSingleBean(RoomSensorCommunicationQueue.class)
                .hasBean("roomSensorTaskScheduler"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {
        @Bean PlatformTransactionManager transactionManager(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql) {
            return new DataSourceTransactionManager(mysql.getDataSource());
        }
    }
}
