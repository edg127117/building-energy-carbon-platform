package com.platform.alarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.alarm.AlarmModels.*;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AlarmDictionaryTest {
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");
    private JdbcTemplate jdbc;
    private AlarmAccess access;
    private AlarmDictionaryService service;

    @BeforeEach
    void setup() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:alarm-dict-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE biz_alarm_dictionary(building_id VARCHAR(32),dictionary_id VARCHAR(36),dictionary_name VARCHAR(100),revision BIGINT,states_json CLOB,updated_at BIGINT,updated_by BIGINT,PRIMARY KEY(building_id,dictionary_id))");
        jdbc.execute("CREATE TABLE biz_alarm_dictionary_version(building_id VARCHAR(32),dictionary_id VARCHAR(36),revision BIGINT,dictionary_name VARCHAR(100),states_json CLOB,updated_at BIGINT,updated_by BIGINT,PRIMARY KEY(building_id,dictionary_id,revision))");
        jdbc.execute("CREATE TABLE biz_alarm_point_dictionary(building_id VARCHAR(32),point_id VARCHAR(32),dictionary_id VARCHAR(36),dictionary_revision BIGINT,PRIMARY KEY(building_id,point_id))");
        jdbc.execute("CREATE TABLE biz_equipment(equip_id VARCHAR(32),building_id VARCHAR(32),del_flag INT)");
        jdbc.execute("CREATE TABLE biz_data_point(point_id VARCHAR(32),equip_id VARCHAR(32),building_id VARCHAR(32),data_type VARCHAR(20),status VARCHAR(16),del_flag INT)");
        jdbc.update("INSERT INTO biz_equipment VALUES('E1','A',0),('E2','B',0)");
        jdbc.update("INSERT INTO biz_data_point VALUES('P1','E1','A','DIGITAL','ONLINE',0),('P2','E1','A','ANALOG','ONLINE',0),('P3','E2','B','DIGITAL','ONLINE',0)");
        access = mock(AlarmAccess.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC);
        service = new AlarmDictionaryService(jdbc, access, new ObjectMapper(), clock);
    }

    @Test
    void createsImmutableVersionsAndResolvesOldRuleSnapshot() {
        String id = service.create(7L, ADMIN, "A", new SaveDictionary("运行状态", 0L, states("停止", "运行")));
        Condition reference = new Condition("P1", "EQ", BigDecimal.ONE, null, List.of(), id, 1L);
        Condition oldSnapshot = service.resolve("A", reference);

        service.update(7L, ADMIN, "A", id, new SaveDictionary("设备状态", 1L, states("关闭", "开启")));

        assertThat(oldSnapshot.states()).extracting(StateOption::label).containsExactly("停止", "运行");
        assertThat(service.getVersion("A", id, 1).name()).isEqualTo("运行状态");
        assertThat(service.getVersion("A", id, 2).states()).extracting(StateOption::label).containsExactly("关闭", "开启");
        assertThat(service.list(7L, ADMIN, "A")).singleElement().satisfies(row -> {
            assertThat(row.revision()).isEqualTo(2);
            assertThat(row.name()).isEqualTo("设备状态");
        });
        assertThatThrownBy(() -> service.update(7L, ADMIN, "A", id,
                new SaveDictionary("并发覆盖", 1L, states("关", "开"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已更新");
        assertThatThrownBy(() -> service.getVersion("B", id, 1)).isInstanceOf(BusinessException.class);
        verify(access, org.mockito.Mockito.times(3)).require(7L, ADMIN, "A", "/configuration/rules/alarmRules", true);
    }

    @Test
    void bindsOnlyOnlineDigitalPointsAndResolvesLegacyInlineStates() {
        String id = service.create(7L, ADMIN, "A", new SaveDictionary("运行状态", null, states("停止", "运行")));
        service.bind(7L, ADMIN, "A", "P1", new DictionaryBinding(id, 1L));

        assertThat(service.binding("A", "P1")).isEqualTo(new DictionaryBinding(id, 1L));
        assertThat(service.binding("B", "P1")).isNull();
        assertThatThrownBy(() -> service.bind(7L, ADMIN, "A", "P2", new DictionaryBinding(id, 1L)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("DIGITAL");
        assertThatThrownBy(() -> service.bind(7L, ADMIN, "A", "P3", new DictionaryBinding(id, 1L)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("DIGITAL");

        Condition legacy = new Condition("P1", "EQ", BigDecimal.ONE, null, states("传统关", "传统开"));
        assertThat(service.resolve("A", legacy)).isEqualTo(legacy);
        assertThatThrownBy(() -> service.resolve("A", new Condition("P1", "EQ", BigDecimal.ONE, null,
                List.of(), id, null))).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsDuplicateNumericCodesAndLabels() {
        assertThatThrownBy(() -> service.create(7L, ADMIN, "A", new SaveDictionary("重复编码", null,
                List.of(new StateOption(BigDecimal.ONE, "关"), new StateOption(new BigDecimal("1.0"), "开")))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("编码");
        assertThatThrownBy(() -> service.create(7L, ADMIN, "A", new SaveDictionary("重复名称", null,
                List.of(new StateOption(BigDecimal.ZERO, "停"), new StateOption(BigDecimal.ONE, "停")))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("名称");
    }

    private static List<StateOption> states(String first, String second) {
        return List.of(new StateOption(BigDecimal.ZERO, first), new StateOption(BigDecimal.ONE, second));
    }
}
