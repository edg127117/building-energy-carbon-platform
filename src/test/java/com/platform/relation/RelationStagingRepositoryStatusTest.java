package com.platform.relation;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelationStagingRepositoryStatusTest {
    @Test
    void preservesOnlyTheDatabaseValueOneAsEnabled() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("status+0 AS status FROM biz_equipment_type"), eq("IDU")))
                .thenReturn(List.of(new HashMap<>(Map.of("status", 1L))));
        when(jdbc.queryForList(contains("status+0 AS status FROM biz_point_naming_rule"), eq("DISABLED")))
                .thenReturn(List.of(new HashMap<>(Map.of("status", 0L))));
        when(jdbc.queryForList(contains("status+0 AS status FROM biz_point_naming_rule"), eq("UNKNOWN")))
                .thenReturn(List.of(new HashMap<>(Map.of("status", 2L))));
        when(jdbc.queryForList(contains("status+0 AS status FROM biz_point_naming_rule"), eq("MISSING")))
                .thenReturn(List.of(new HashMap<>()));

        var repository = new RelationStagingRepository(jdbc);
        assertThat(repository.type("IDU")).containsEntry("status", 1);
        assertThat(RelationStagingRepository.namingRule(repository.rule("DISABLED")).getStatus()).isZero();
        assertThat(RelationStagingRepository.namingRule(repository.rule("UNKNOWN")).getStatus()).isEqualTo(2);
        assertThat(RelationStagingRepository.namingRule(repository.rule("MISSING")).getStatus()).isNull();
    }
}
