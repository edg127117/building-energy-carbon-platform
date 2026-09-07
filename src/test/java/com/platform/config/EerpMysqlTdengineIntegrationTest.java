package com.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.platform.energy.period.*;
import com.platform.energy.period.NativePeriodSnapshotService.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 显式 opt-in 的一次性目标引擎验证；普通测试不建立任何外部连接。 */
@EnabledIfEnvironmentVariable(named="EERP_IT_ENABLED",matches="true")
class EerpMysqlTdengineIntegrationTest {
    @Test void migratesIsolatedMysqlAndPublishesNativeValuesOnlyAfterTdengineSuccess() {
        assertThat(System.getenv("EERP_IT_ISOLATED")).isEqualTo("true");
        String mysqlUrl=System.getenv("EERP_IT_MYSQL_URL");
        assertThat(mysqlUrl).contains("/iot_platform");
        var mysql=new DriverManagerDataSource(mysqlUrl,System.getenv("EERP_IT_MYSQL_USER"),System.getenv("EERP_IT_MYSQL_PASSWORD"));
        JdbcTemplate index=new JdbcTemplate(mysql);
        assertThat(index.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class))
                .as("调用方必须提供一次性空MySQL库").isZero();
        Flyway.configure().dataSource(mysql).locations("filesystem:src/env/init").load().migrate();
        assertThat(index.queryForObject("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1",String.class)).isEqualTo("42");
        var taos=new JdbcTemplate(new DriverManagerDataSource(System.getenv("EERP_IT_TDENGINE_URL"),
                System.getenv("EERP_IT_TDENGINE_USER"),System.getenv("EERP_IT_TDENGINE_PASSWORD")));
        String database="eerp_it_"+UUID.randomUUID().toString().replace("-","");
        var properties=new TdengineProperties();properties.setDatabase(database);
        taos.execute("CREATE DATABASE "+database+" KEEP 30 DURATION 1d WAL_LEVEL 1");
        try {
            new TdengineConfig(properties).initializeEnergyPeriodSchema(taos);
            var realStore=new TdengineEnergyPeriodValueStore(taos,properties);
            AtomicBoolean fail=new AtomicBoolean(true);
            EnergyPeriodValueStore store=value -> {if(fail.get())throw new IllegalStateException("isolated injected write failure");realStore.write(value);};
            var nativePeriods=new NativePeriodSnapshotService(index,store,mock(EnergyPeriodAuthorization.class),new ObjectMapper().registerModule(new JavaTimeModule()));
            String id="native_"+UUID.randomUUID().toString().replace("-","");
            var snapshot=new Snapshot(id,"BLD_EERP_IT","cold-source","COOLING_ENERGY","kWh","a".repeat(64),
                    List.of(new NumericSample(Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS),new BigDecimal("580.555555555556"),BigDecimal.ONE)));
            assertThatThrownBy(() -> nativePeriods.publish(1,List.of("ENERGY_MANAGER"),snapshot)).isInstanceOf(IllegalStateException.class);
            assertThat(index.queryForObject("SELECT status FROM energy_native_quantity_snapshot WHERE snapshot_id=?",String.class,id)).isEqualTo("PENDING");
            fail.set(false);nativePeriods.publish(1,List.of("ENERGY_MANAGER"),snapshot);nativePeriods.publish(1,List.of("ENERGY_MANAGER"),snapshot);
            assertThat(nativePeriods.read(1,List.of("ENERGY_MANAGER"),"BLD_EERP_IT",id)).isEqualTo(snapshot);
            var rows=taos.queryForList("SELECT native_quantity_decimal,tce_value_decimal FROM "+database+".st_energy_period_result WHERE result_key='"+id+"'");
            assertThat(rows).hasSize(1);
            Object exact=rows.getFirst().values().stream().filter(v -> v!=null).findFirst().orElseThrow();
            assertThat(exact instanceof byte[] bytes?new String(bytes,StandardCharsets.UTF_8):exact.toString()).isEqualTo("580.555555555556");
            assertThat(rows.getFirst().values().stream().filter(v -> v==null).count()).isEqualTo(1);
        } finally {
            // 仅删除本次随机创建的专用时序库，MySQL由隔离环境提供方回收。
            taos.execute("DROP DATABASE "+database);
        }
    }
}
