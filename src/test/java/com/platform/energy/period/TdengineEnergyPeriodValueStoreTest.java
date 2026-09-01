package com.platform.energy.period;

import com.platform.config.TdengineProperties;
import com.platform.energy.period.EnergyPeriodModels.NumericResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TdengineEnergyPeriodValueStoreTest {
    @Test
    void writesDeterministicChildWithPinnedEvidenceAndNullableTce() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TdengineProperties properties = new TdengineProperties();
        properties.setDatabase("iot_telemetry");
        var store = new TdengineEnergyPeriodValueStore(jdbc, properties);

        store.write(new NumericResult("a".repeat(64), "BLD001", "POINT001",
                Instant.parse("2026-08-01T00:00:00Z"), new BigDecimal("12.5"), "kWh",
                null, null, new BigDecimal("0.98"), "DEVELOPMENT_SIMULATION",
                "b".repeat(64), 3));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).execute(sql.capture());
        assertThat(sql.getValue())
                .contains("st_energy_period_result", "native_quantity", "NULL", "revision")
                .doesNotContain("POINT001`", "BLD001`");
    }
}
