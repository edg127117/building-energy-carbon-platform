package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定 MySQL 首次初始化的客户端字符集，避免 UTF-8 中文被按 latin1
 * 读取后再次转码并永久写成乱码。
 */
class MySqlInitializationCharsetContractTest {

    private static final String SET_NAMES =
            "SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;";

    @Test
    void everyAutomaticMySqlInitScriptSetsUtf8BeforeBusinessSql()
            throws IOException {
        List<InitScript> scripts = List.of(
                new InitScript(
                        Path.of("src/env/init/V01__init_tables.sql"),
                        List.of("超级管理员", "建筑业主")),
                new InitScript(
                        Path.of("src/env/init/V03__init_hvac_schema.sql"),
                        List.of("试点大楼", "冷冻水进水温度")));

        for (InitScript script : scripts) {
            String sql = Files.readString(script.path(), StandardCharsets.UTF_8);
            int declaration = sql.indexOf(SET_NAMES);
            int firstCreate = sql.indexOf("CREATE TABLE");
            int firstInsert = sql.indexOf("INSERT");

            assertThat(declaration)
                    .as("%s 必须声明 MySQL 客户端 UTF-8", script.path())
                    .isGreaterThanOrEqualTo(0);
            assertThat(firstCreate).isGreaterThan(declaration);
            assertThat(firstInsert).isGreaterThan(declaration);
            assertThat(script.representativeChinese())
                    .allSatisfy(seed -> assertThat(sql).contains(seed));
        }
    }

    private record InitScript(
            Path path,
            List<String> representativeChinese) {
    }
}
