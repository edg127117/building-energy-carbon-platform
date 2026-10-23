"""D01 V85 专用 MySQL 回归：只新建本机隔离资源，不接受服务器地址。"""
import importlib.util
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import uuid

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("d01_upgrade", ROOT / "scripts/Verify-D01UpgradeIsolated.py")
upgrade = importlib.util.module_from_spec(spec)
spec.loader.exec_module(upgrade)
probe = upgrade.probe


def sql(container, statement, expected=True):
    probe.owned("container", container)
    result = subprocess.run([probe.DOCKER, "--host", "npipe:////./pipe/dockerDesktopLinuxEngine",
        "exec", "-i", container, "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B --default-character-set=utf8mb4 iot_platform'],
        input=statement.encode("utf-8"), env=probe.ENV, capture_output=True, timeout=90)
    if expected and result.returncode:
        raise RuntimeError("D01 archive isolated SQL failed: " + result.stderr.decode(errors="replace")[-400:])
    if not expected:
        assert result.returncode != 0, "invalid reference was accepted"
    return result.stdout.decode("utf-8").strip()


def main():
    assert len(sys.argv) == 1, "does not accept an existing endpoint"
    try:
        assert probe.docker("info", "--format", "{{.OSType}}") == "linux"
        network = "d01-archive-" + probe.NONCE
        probe.docker("network", "create", "--label", probe.LABEL, network)
        probe.RESOURCES["networks"].append(network)
        mysql, _ = upgrade.new_mysql(network, "archive")
        upgrade.java("init83")
        upgrade.java("upgrade84")
        sql(mysql, """
            INSERT INTO building(building_id,building_name,building_type,total_gfa,climate_zone,region_code)
            VALUES('D01_ARCHIVE_PROBE','D01 synthetic archive fixture','办公',100,'夏热冬冷','320100');
            INSERT INTO biz_telemetry_raw_intent
            (point_key,event_ms,point_id,building_id,payload_hash,snapshot_json,storage_state,retry_count)
            VALUES('D01_ARCHIVE_KEY',1,'D01_ARCHIVE_POINT','D01_ARCHIVE_PROBE',REPEAT('a',64),'{}','FAILED',10);
            INSERT INTO biz_telemetry_message_intent
            (canonical_message_id,building_id,payload_hash,snapshot_json,state,created_ms,retry_count)
            VALUES('D01_ARCHIVE_MESSAGE','D01_ARCHIVE_PROBE',REPEAT('b',64),'{}','FAILED',1,10);
            """)
        tables = sql(mysql, """
            SELECT CONCAT('`',table_schema,'`.`',table_name,'`') FROM information_schema.tables
            WHERE table_schema IN ('iot_platform','iot_platform_archive')
              AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history' ORDER BY table_schema,table_name
            """).splitlines()
        checksums = "CHECKSUM TABLE " + ",".join(tables) + " EXTENDED"
        before = sql(mysql, checksums)
        assert not any(line.endswith("\tNULL") for line in before.splitlines()), "engine did not compute a table checksum"
        history_query = "SELECT * FROM flyway_schema_history WHERE version IS NULL OR CAST(version AS UNSIGNED)<=84 ORDER BY installed_rank"
        history = sql(mysql, history_query)
        parent_collation = sql(mysql, "SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='iot_platform' AND TABLE_NAME='building' AND COLUMN_NAME='building_id'")
        database_collation = sql(mysql, "SELECT DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='iot_platform'")
        assert parent_collation != database_collation, "fixture must exercise changed database default collation"
        # 执行实际发布 JAR 内的独立入口，不能用测试辅助 Flyway 代替发布证明。
        upgrade.java("validate84", packaged=True)
        password = uuid.uuid4().hex
        sql(mysql, "CREATE USER 'platform_migrator'@'%' IDENTIFIED BY '" + password + "'; "
            "GRANT SELECT,INSERT,UPDATE,DELETE,CREATE ON iot_platform.* TO 'platform_migrator'@'%'; "
            "GRANT SELECT,INSERT,UPDATE,DELETE,CREATE ON iot_platform_archive.* TO 'platform_migrator'@'%';")
        probe.ENV.update(D01_JDBC_USER="platform_migrator", D01_JDBC_PASSWORD=password)
        upgrade.java("upgrade85", expected=False, packaged=True)
        assert before == sql(mysql, checksums), "denied migration changed existing evidence"
        assert sql(mysql, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='iot_platform' AND table_name='biz_test_building_retirement'") == "0"
        # MySQL/Flyway 的失败记录必须保留；此实例不 repair 或再次迁移。
        assert sql(mysql, "SELECT COUNT(*) FROM flyway_schema_history WHERE version='85' AND success=0") == "1"
        upgrade.java("validate85", expected=False, packaged=True)
        failed_mysql = mysql
        failed_checksum = sql(failed_mysql, checksums)
        mysql, _ = upgrade.new_mysql(network, "archive-authorized")
        upgrade.java("init83")
        upgrade.java("upgrade84", packaged=True)
        # 备份恢复同一份非空 V84 数据，在另一新实例验证具有最小所需权限的迁移。
        dump = subprocess.run([probe.DOCKER, "--host", "npipe:////./pipe/dockerDesktopLinuxEngine",
            "exec", failed_mysql, "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --set-gtid-purged=OFF --no-tablespaces --ignore-table=iot_platform.flyway_schema_history --databases iot_platform iot_platform_archive'],
            env=probe.ENV, capture_output=True, timeout=120)
        assert dump.returncode == 0, "isolated V84 evidence export failed"
        upgrade.input_command(mysql, 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot', dump.stdout)
        assert sql(mysql, checksums) == failed_checksum == before
        sql(mysql, "CREATE USER 'platform_migrator'@'%' IDENTIFIED BY '" + password + "'; "
            "GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,REFERENCES ON iot_platform.* TO 'platform_migrator'@'%'; "
            "GRANT SELECT,INSERT,UPDATE,DELETE,CREATE ON iot_platform_archive.* TO 'platform_migrator'@'%';")
        probe.ENV.update(D01_JDBC_USER="platform_migrator", D01_JDBC_PASSWORD=password)
        # 新实例的 Flyway 时间戳不同，保留本实例升级前历史以逐行比较。
        history = sql(mysql, history_query)
        upgrade.java("upgrade85", expected=False, packaged=True, writers_frozen=False)
        assert sql(mysql, "SELECT COUNT(*) FROM flyway_schema_history WHERE version='85'") == "0"
        upgrade.java("upgrade85", packaged=True)
        upgrade.java("validate85", packaged=True)
        upgrade.java("upgrade85", expected=False, packaged=True)
        assert before == sql(mysql, checksums), "pre-existing table contents changed"
        assert history == sql(mysql, history_query), "pre-existing Flyway history changed"
        assert sql(mysql, "SELECT COUNT(*) FROM flyway_schema_history WHERE version='85' AND success=1") == "1"
        assert sql(mysql, "SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='iot_platform' AND TABLE_NAME='biz_test_building_retirement' AND COLUMN_NAME='building_id'") == parent_collation
        sql(mysql, """
            INSERT INTO biz_test_building_retirement(building_id,status,evidence_sha256,exclusion_request_id,excluded_at)
            VALUES('missing','EXCLUDED',REPEAT('c',64),'review',CURRENT_TIMESTAMP(3))
            """, expected=False)
        sql(mysql, """
            INSERT INTO biz_test_building_retirement(building_id,status,evidence_sha256,exclusion_request_id,excluded_at)
            VALUES('D01_ARCHIVE_PROBE','EXCLUDED',REPEAT('c',64),'review',CURRENT_TIMESTAMP(3))
            """)
        sql(mysql, "DELETE FROM building WHERE building_id='D01_ARCHIVE_PROBE'", expected=False)
        assert sql(mysql, "SELECT storage_state,retry_count FROM biz_telemetry_raw_intent WHERE point_key='D01_ARCHIVE_KEY'") == "FAILED\t10"
        assert sql(mysql, "SELECT state,retry_count FROM biz_telemetry_message_intent WHERE canonical_message_id='D01_ARCHIVE_MESSAGE'") == "FAILED\t10"
        report = {"format": "D01_ARCHIVE_ISOLATED_V1", "mysql_version": sql(mysql, "SELECT VERSION()"),
                  "migration_sha256": hashlib.sha256((ROOT / "src/env/init/V85__mysql_d01_test_building_retirement.sql").read_bytes()).hexdigest(),
                  "existing_tables_preserved": len(tables), "old_flyway_history_preserved": True,
                  "parent_collation": parent_collation, "database_collation": database_collation,
                  "migration85_success": True, "failed_evidence_preserved": True, "foreign_key_verified": True}
        report.update(packaged_migrator=True, separate_migration_account=True, missing_references_rejected=True,
                      failed_history_preserved=True, duplicate_upgrade_rejected=True, nonempty_v84_copy_restored=True)
        report["unfrozen_upgrade_rejected"] = True
        (ROOT / "target/d01-archive-isolated-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print("D01_ARCHIVE_MYSQL_VERIFIED " + json.dumps(report, ensure_ascii=False), flush=True)
    finally:
        for identifier in reversed(probe.RESOURCES["containers"]):
            probe.owned("container", identifier)
            probe.docker("rm", "-f", identifier)
        for identifier in reversed(probe.RESOURCES["networks"]):
            probe.owned("network", identifier)
            probe.docker("network", "rm", identifier)
        for identifier in reversed(probe.RESOURCES["volumes"]):
            probe.owned("volume", identifier)
            probe.docker("volume", "rm", identifier)
        print("D01_ARCHIVE_OWNED_RESOURCES_REMOVED", flush=True)


if __name__ == "__main__":
    main()
