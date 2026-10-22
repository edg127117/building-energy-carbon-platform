package com.platform.energy.parameters;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.UUID;

/** 全局参数包不借用建筑范围；单独锁定导入范围并保存内容绑定的幂等回执。 */
@Repository
public class EnergyParameterPackageImportRepository {
    static final String GLOBAL_SCOPE = "GLOBAL_PARAMETER_PACKAGES";

    private final JdbcTemplate jdbc;

    public EnergyParameterPackageImportRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockGlobalScope() {
        jdbc.queryForObject("SELECT scope_key FROM biz_energy_parameter_package_import_lock "
                + "WHERE scope_key=? FOR UPDATE", String.class, GLOBAL_SCOPE);
    }

    public Receipt receipt(String idempotencyKey) {
        return jdbc.query("""
                SELECT actor_id,request_hash,response_json,status
                FROM biz_energy_parameter_package_import_receipt
                WHERE scope_key=? AND idempotency_key=?
                """, (row, number) -> new Receipt(row.getLong("actor_id"), row.getString("request_hash"),
                row.getString("response_json"), row.getString("status")), GLOBAL_SCOPE, idempotencyKey)
                .stream().findFirst().orElse(null);
    }

    public void success(String idempotencyKey, String requestHash, long actorId, String packageCode,
                        String responseJson) {
        jdbc.update("""
                INSERT INTO biz_energy_parameter_package_import_receipt
                (receipt_id,scope_key,idempotency_key,request_hash,actor_id,package_code,status,response_json,
                 created_at,completed_at)
                VALUES (?,?,?,?,?,?, 'SUCCEEDED', ?,?,?)
                """, id(), GLOBAL_SCOPE, idempotencyKey, requestHash, actorId, packageCode, responseJson,
                LocalDateTime.now(), LocalDateTime.now());
    }

    public record Receipt(long actorId, String requestHash, String responseJson, String status) {
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}