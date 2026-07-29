package com.platform.iot.dataquality;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DataQualitySchemaTest {

    @Test
    void testSchemaContainsTypicalValueAndFillTaskAuditStructures() throws IOException {
        String schema = readClasspathResource("schema-test.sql").toLowerCase();

        assertThat(schema).contains(
                "create table biz_point_typical_value_config",
                "constraint uk_typical_point_version unique (point_id, version)",
                "create index idx_typical_building_status",
                "create index idx_typical_effective",
                "create table biz_data_quality_fill_task",
                "constraint uk_fill_idempotency unique (idempotency_key)",
                "create index idx_fill_building_range",
                "create index idx_fill_point_range",
                "create index idx_fill_status_update");

        String fillTaskTable = schema.substring(
                schema.indexOf("create table biz_data_quality_fill_task"),
                schema.indexOf(");", schema.indexOf("create table biz_data_quality_fill_task")));
        assertThat(fillTaskTable).doesNotContain(
                "review_status",
                "reviewer_id",
                "review_comment",
                "reviewed_at");
    }

    private String readClasspathResource(String name) throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(name)) {
            assertThat(stream).as("classpath resource %s", name).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
