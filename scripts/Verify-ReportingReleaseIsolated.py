"""仅创建本机一次性 MySQL：演练打包迁移入口、双库备份恢复及失败保留，不接受服务器地址。"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DOCKER = shutil.which("docker") or str(Path(os.environ.get("LOCALAPPDATA", "")) / "Programs/DockerDesktop/resources/bin/docker.exe")
HOST = "npipe:////./pipe/dockerDesktopLinuxEngine" if os.name == "nt" else "unix:///var/run/docker.sock"
JAVA = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java"))
NONCE = uuid.uuid4().hex
LABEL = "bec.reporting.release=" + NONCE
PASSWORD = uuid.uuid4().hex
ENV = {**os.environ, "MYSQL_ROOT_PASSWORD": PASSWORD, "MYSQL_PWD": PASSWORD}
RESOURCES = []
JAR = ROOT / "target/iot-platform-demo-1.0-SNAPSHOT.jar"
RESULTS = []


def emit(value):
    print(value, flush=True)
    RESULTS.append(value)


def docker(*args, data=None, timeout=120):
    result = subprocess.run([DOCKER, "--host", HOST, *args], input=data, env=ENV,
                            capture_output=True, timeout=timeout)
    if result.returncode:
        # 不打印 Docker 参数、数据库 stderr 或环境，随机测试密码也不进入验收证据。
        raise RuntimeError("REPORTING_ISOLATED_DOCKER_FAILED_" + args[0])
    return result.stdout


def owned(kind, name):
    template = '{{index .Config.Labels "bec.reporting.release"}}' if kind == "container" else '{{index .Labels "bec.reporting.release"}}'
    assert docker(kind, "inspect", "--format", template, name).decode().strip() == NONCE


def sql(instance, statement):
    owned("container", instance[0])
    payload = statement.encode("utf-8") if isinstance(statement, str) else statement
    # 初始化临时服务只开放 socket；使用 TCP，避免把临时服务误判为最终就绪后立即遇到重启。
    return docker("exec", "-i", "-e", "MYSQL_PWD", instance[0], "mysql", "-h127.0.0.1", "--default-character-set=utf8mb4", "-uroot", "-N", "-B",
                  data=b"SET SESSION time_zone='+00:00';\n" + payload).decode("utf-8").strip()


def new_mysql():
    name = "reporting-release-" + uuid.uuid4().hex[:16]
    volume = name + "-data"
    docker("volume", "create", "--label", LABEL, volume)
    RESOURCES.append(("volume", volume))
    docker("run", "-d", "--name", name, "--label", LABEL, "--network", NETWORK,
           "--memory", "1g", "--cpus", "2", "-e", "MYSQL_ROOT_PASSWORD", "-e", "MYSQL_DATABASE=iot_platform",
           "-p", "127.0.0.1::3306", "--mount", "type=volume,source=" + volume + ",target=/var/lib/mysql", "mysql:8.4")
    RESOURCES.append(("container", name))
    owned("container", name)
    requested = json.loads(docker("inspect", "--format", '{{json .HostConfig.PortBindings}}', name))["3306/tcp"]
    assert len(requested) == 1 and requested[0]["HostIp"] == "127.0.0.1"
    deadline = time.monotonic() + 20
    while True:
        bindings = json.loads(docker("inspect", "--format", '{{json .NetworkSettings.Ports}}', name)).get("3306/tcp")
        if bindings:
            break
        if time.monotonic() >= deadline:
            raise RuntimeError("REPORTING_ISOLATED_LOOPBACK_PORT_NOT_PUBLISHED")
        time.sleep(1)
    assert len(bindings) == 1 and bindings[0]["HostIp"] == "127.0.0.1"
    instance = (name, bindings[0]["HostPort"])
    deadline = time.monotonic() + 150
    while True:
        try:
            if sql(instance, "SELECT 1") == "1":
                return instance
        except RuntimeError:
            pass
        if time.monotonic() >= deadline:
            raise RuntimeError("REPORTING_ISOLATED_MYSQL_NOT_READY")
        time.sleep(1)


def java(instance, action, *, packaged=True, expected=True, frozen=True, user="platform_migrator", archive=None):
    url = "jdbc:mysql://127.0.0.1:" + instance[1] + "/iot_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
    env = {**ENV, "D01_JDBC_URL": url, "D01_JDBC_USER": user, "D01_JDBC_PASSWORD": PASSWORD,
           "D01_WRITERS_FROZEN": str(frozen).lower(), "D01_UPGRADE_ISOLATED": "true"}
    if packaged:
        args = [JAVA, "-Dloader.main=com.platform.tools.D01SchemaMigration", "-cp", str(archive or JAR),
                "org.springframework.boot.loader.launch.PropertiesLauncher", action]
    else:
        cp = os.pathsep.join((str(ROOT / "target/test-classes"), str(ROOT / "target/classes"),
                             (ROOT / "target/reporting-release-classpath.txt").read_text(encoding="utf-8-sig").strip()))
        args = [JAVA, "-cp", cp, "com.platform.iot.reliability.D01UpgradeProcess", action]
    result = subprocess.run(args, env=env, capture_output=True, timeout=180)
    assert (result.returncode == 0) == expected, "REPORTING_MIGRATION_OUTCOME_" + action
    if expected and packaged:
        assert ("D01_SCHEMA_VALIDATED=" + action[-2:]).encode() in result.stdout.splitlines()
    if not expected:
        assert b"D01_SCHEMA_VALIDATED=" not in result.stdout


def accounts(instance):
    sql(instance, "CREATE USER 'platform_migrator'@'%' IDENTIFIED BY '" + PASSWORD + "'; "
        + " ".join("GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON " + db
                   + ".* TO 'platform_migrator'@'%';" for db in ("iot_platform", "iot_platform_archive"))
        + "CREATE USER 'platform_app'@'%' IDENTIFIED BY '" + PASSWORD + "'; GRANT SELECT ON iot_platform.* TO 'platform_app'@'%'")


def dump(instance, *args):
    owned("container", instance[0])
    return docker("exec", "-e", "MYSQL_PWD", instance[0], "mysqldump", "-uroot", "--set-gtid-purged=OFF",
                  "--no-tablespaces", "--skip-comments", "--default-character-set=utf8mb4", "--hex-blob", *args)


def old_rows(instance, tables):
    values = {}
    for db, names in tables.items():
        values[db] = hashlib.sha256(dump(instance, "--no-create-info", "--skip-add-locks", "--skip-disable-keys",
             "--order-by-primary", "--hex-blob", db, *names)).hexdigest()
    values["history"] = sql(instance, "SELECT version,checksum,success FROM iot_platform.flyway_schema_history WHERE version NOT IN ('86','87','88') ORDER BY installed_rank")
    values["formulas"] = hashlib.sha256(dump(instance, "--no-create-info", "--skip-add-locks", "--skip-disable-keys",
             "--order-by-primary", "--where=formula_version_id<>'CFV_HEAT_CO2_V1'", "iot_platform", "biz_carbon_formula_version")).hexdigest()
    return values


def restore_new(backup, expected, tables, schema="85"):
    instance = new_mysql()
    # 新镜像预建的空库可能使用不同默认 collation；CREATE IF NOT EXISTS 不会还原这些库级元数据。
    # 仅清理由本任务刚创建且已验明无业务表的新实例空库，绝不对来源或非空目标执行 DROP。
    assert sql(instance, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema IN ('iot_platform','iot_platform_archive')") == "0"
    emit("RESTORE_NEW_EMPTY_DATABASE_DEFAULTS=" + sql(instance,
         "SELECT SCHEMA_NAME,DEFAULT_COLLATION_NAME FROM information_schema.schemata WHERE schema_name IN ('iot_platform','iot_platform_archive') ORDER BY SCHEMA_NAME"))
    sql(instance, "DROP DATABASE IF EXISTS iot_platform; DROP DATABASE IF EXISTS iot_platform_archive")
    sql(instance, backup)
    accounts(instance)
    assert old_rows(instance, tables) == expected
    java(instance, "validate" + schema)
    return instance


def identifier(name):
    return "`" + name.replace("`", "``") + "`"


def row_digest(expressions):
    # NULL、空串和二进制值不混淆；HEX 不含分隔符，保留每列边界和重复行。
    values = ["IF(" + value + " IS NULL,'N',CONCAT('V',HEX(CAST(" + value + " AS BINARY))))" for value in expressions]
    return "SHA2(CONCAT_WS('|'," + ",".join(values) + "),256)"


def database_image(instance):
    image = {}
    metadata = (
        ("table", "tables", "TABLE_SCHEMA", "TABLE_NAME", "TABLE_NAME TABLE_TYPE ENGINE ROW_FORMAT TABLE_COLLATION CREATE_OPTIONS TABLE_COMMENT AUTO_INCREMENT"),
        ("columns", "columns", "TABLE_SCHEMA", "TABLE_NAME", "TABLE_NAME COLUMN_NAME ORDINAL_POSITION COLUMN_DEFAULT IS_NULLABLE DATA_TYPE CHARACTER_MAXIMUM_LENGTH CHARACTER_OCTET_LENGTH NUMERIC_PRECISION NUMERIC_SCALE DATETIME_PRECISION CHARACTER_SET_NAME COLLATION_NAME COLUMN_TYPE COLUMN_KEY EXTRA COLUMN_COMMENT GENERATION_EXPRESSION"),
        ("indexes", "statistics", "TABLE_SCHEMA", "TABLE_NAME", "TABLE_NAME INDEX_NAME NON_UNIQUE SEQ_IN_INDEX COLUMN_NAME COLLATION SUB_PART PACKED NULLABLE INDEX_TYPE COMMENT INDEX_COMMENT IS_VISIBLE EXPRESSION"),
        ("constraints", "table_constraints", "TABLE_SCHEMA", "TABLE_NAME", "TABLE_NAME CONSTRAINT_NAME CONSTRAINT_TYPE ENFORCED"),
        ("keys", "key_column_usage", "TABLE_SCHEMA", "TABLE_NAME", "TABLE_NAME CONSTRAINT_NAME COLUMN_NAME ORDINAL_POSITION POSITION_IN_UNIQUE_CONSTRAINT REFERENCED_TABLE_SCHEMA REFERENCED_TABLE_NAME REFERENCED_COLUMN_NAME"),
        ("references", "referential_constraints", "CONSTRAINT_SCHEMA", "TABLE_NAME", "TABLE_NAME CONSTRAINT_NAME UNIQUE_CONSTRAINT_SCHEMA UNIQUE_CONSTRAINT_NAME MATCH_OPTION UPDATE_RULE DELETE_RULE REFERENCED_TABLE_NAME"),
        ("views", "views", "TABLE_SCHEMA", "TABLE_NAME", "TABLE_NAME VIEW_DEFINITION CHECK_OPTION IS_UPDATABLE DEFINER SECURITY_TYPE CHARACTER_SET_CLIENT COLLATION_CONNECTION"),
    )
    for db in ("iot_platform", "iot_platform_archive"):
        defaults = sql(instance, "SELECT DEFAULT_CHARACTER_SET_NAME,DEFAULT_COLLATION_NAME,DEFAULT_ENCRYPTION "
                       "FROM information_schema.schemata WHERE schema_name='" + db + "'")
        image[db + "::<database>"] = {"schema": hashlib.sha256(defaults.encode()).hexdigest(), "count": 0, "rows": ""}
        structures = {}
        # SHOW CREATE 的显式/继承 COLLATE 展开可能在恢复后改变；比较存储元数据，不能删去真实字符集/约束检查。
        for part, source, scope, table, fields in metadata:
            query = "SELECT " + identifier(table) + "," + row_digest([identifier(name) for name in fields.split()])
            query += " FROM information_schema." + source + " WHERE " + identifier(scope) + "='" + db + "'"
            for line in sql(instance, query).splitlines():
                name, digest = line.split("\t")
                structures.setdefault(name, {}).setdefault(part, []).append(digest)
        check_query = "SELECT t.TABLE_NAME," + row_digest(["c.CONSTRAINT_NAME", "c.CHECK_CLAUSE"])
        check_query += (" FROM information_schema.check_constraints c JOIN information_schema.table_constraints t "
                        "ON t.CONSTRAINT_SCHEMA=c.CONSTRAINT_SCHEMA AND t.CONSTRAINT_NAME=c.CONSTRAINT_NAME "
                        "WHERE t.TABLE_SCHEMA='" + db + "' AND t.CONSTRAINT_TYPE='CHECK'")
        for line in sql(instance, check_query).splitlines():
            name, digest = line.split("\t")
            structures.setdefault(name, {}).setdefault("checks", []).append(digest)
        columns = {}
        for row in sql(instance, "SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.columns WHERE table_schema='" + db
                       + "' ORDER BY TABLE_NAME,ORDINAL_POSITION").splitlines():
            table, column = row.split("\t")
            columns.setdefault(table, []).append(column)
        assert set(columns) == set(structures), "REPORTING_V88_SCHEMA_OBJECT_SET_MISMATCH"
        for table, names in columns.items():
            target = identifier(db) + "." + identifier(table)
            parts = {key: sorted(values) for key, values in structures[table].items()}
            rows = sql(instance, "SELECT " + row_digest([identifier(name) for name in names]) + " FROM " + target).splitlines()
            image[db + "." + table] = {"schema": hashlib.sha256(json.dumps(parts, sort_keys=True).encode()).hexdigest(),
                "count": len(rows), "rows": hashlib.sha256("\n".join(sorted(rows)).encode()).hexdigest(),
                "parts": parts, "rendering": hashlib.sha256(sql(instance, "SHOW CREATE TABLE " + target).encode()).hexdigest()}
    return image


def assert_database_image(instance, expected, stage):
    actual = database_image(instance)
    different = []
    rendered = []
    for name in sorted(actual.keys() | expected.keys()):
        changed = [key for key in ("schema", "count", "rows")
                   if actual.get(name, {}).get(key) != expected.get(name, {}).get(key)]
        if changed:
            old, new = expected.get(name, {}).get("parts", {}), actual.get(name, {}).get("parts", {})
            different.append({"table": name, "parts": changed,
                              "schema_parts": [key for key in sorted(old.keys() | new.keys()) if old.get(key) != new.get(key)]})
        elif actual[name].get("rendering") != expected[name].get("rendering"):
            rendered.append(name)
    if different:
        # 只输出差异对象和类型，不回显行值、SQL 或凭据。
        emit("V88_SNAPSHOT_DIFFERENCE_" + stage + "=" + json.dumps(different, sort_keys=True))
        raise AssertionError("REPORTING_V88_SNAPSHOT_MISMATCH_" + stage)
    if rendered:
        emit("V88_SCHEMA_RENDERING_DIFFERENCE_WITH_IDENTICAL_METADATA=" + json.dumps(rendered))


def application_only(instance, legacy, tables):
    # 合成二进制与重复/空行仅存在新建隔离实例，覆盖天气等 BLOB 证据的备份保真。
    sql(instance, "CREATE TABLE iot_platform.application_only_binary_fixture (payload MEDIUMBLOB, marker VARBINARY(16)); "
        "INSERT INTO iot_platform.application_only_binary_fixture VALUES (0x00FF80C328,0x0001),(0x00FF80C328,0x0001),(NULL,NULL)")
    sql(instance, "INSERT INTO iot_platform.biz_energy_reporting_scope VALUES ('SYNTHETIC_APP_ONLY',9); "
        "INSERT INTO iot_platform.biz_energy_reporting_record VALUES ('app-only-keep','SYNTHETIC_APP_ONLY','BATCH','DRAFT',7,'2026-10-01','{\"retained\":true}'); "
        "INSERT INTO iot_platform.biz_energy_parameter_package_import_receipt "
        "(receipt_id,scope_key,idempotency_key,request_hash,actor_id,package_code,status,response_json,created_at) "
        "VALUES ('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','GLOBAL_PARAMETER_PACKAGES','app-only-keep','" + "a" * 64
        + "',1,'B01','SUCCEEDED','{\"fixture\":true}','2026-10-01')")
    before = database_image(instance)
    backup88 = dump(instance, "--single-transaction", "--routines", "--events", "--triggers", "--databases", "iot_platform", "iot_platform_archive")
    # 仅改新建隔离实例的测试账号，证明 validate88 不依赖写入或 DDL 权限。
    for db in ("iot_platform", "iot_platform_archive"):
        sql(instance, "REVOKE ALL PRIVILEGES ON " + db + ".* FROM 'platform_migrator'@'%'; "
            "GRANT SELECT ON " + db + ".* TO 'platform_migrator'@'%'")
    java(instance, "validate88")
    java(instance, "validate88")
    assert_database_image(instance, before, "SOURCE_UNCHANGED")
    assert old_rows(instance, tables) == legacy
    with tempfile.TemporaryDirectory(prefix="v88-application-only-") as directory:
        altered = Path(directory) / "changed-migration.jar"
        migration = "BOOT-INF/classes/db/migration/mysql/V88__mysql_default_electricity_factor_year.sql"
        with zipfile.ZipFile(JAR) as source, zipfile.ZipFile(altered, "w") as target:
            for item in source.infolist():
                data = source.read(item.filename)
                target.writestr(item, data + b"\nSELECT 1;\n" if item.filename == migration else data)
        java(instance, "validate88", expected=False, archive=altered)
    assert_database_image(instance, before, "SOURCE_UNCHANGED")
    restored = restore_new(backup88, legacy, tables, "88")
    assert_database_image(restored, before, "RESTORED_V88")
    emit("V88_APPLICATION_ONLY_SELECT_ONLY_VALIDATION_CHECKSUM_REJECTION_ALL_ROWS_HISTORY_AND_DUAL_DATABASE_RESTORE_VERIFIED")


def main():
    assert os.environ.get("REPORTING_RELEASE_ISOLATED") == "true", "EXPLICIT_ISOLATED_OPT_IN_REQUIRED"
    assert JAR.is_file() and (ROOT / "target/reporting-release-classpath.txt").is_file()
    global NETWORK
    NETWORK = "reporting-release-" + NONCE[:16]
    try:
        assert docker("info", "--format", "{{.OSType}}").strip() == b"linux"
        docker("network", "create", "--driver", "bridge", "--opt", "com.docker.network.bridge.host_binding_ipv4=127.0.0.1", "--label", LABEL, NETWORK)
        RESOURCES.append(("network", NETWORK))
        source = new_mysql()
        java(source, "init83", packaged=False, user="root")
        java(source, "upgrade84", user="root")
        java(source, "upgrade85", user="root")
        accounts(source)
        tables = {db: [name for name in sql(source, "SHOW TABLES FROM " + db).splitlines()
                       if name not in ("flyway_schema_history", "biz_carbon_formula_version")]
                  for db in ("iot_platform", "iot_platform_archive")}
        before = old_rows(source, tables)
        backup = dump(source, "--single-transaction", "--routines", "--events", "--triggers", "--databases", "iot_platform", "iot_platform_archive")
        assert backup
        java(source, "upgrade87", expected=False)
        java(source, "upgrade88", expected=False)
        java(source, "upgrade86", expected=False, frozen=False)
        java(source, "upgrade86", expected=False, user="platform_app")
        assert old_rows(source, tables) == before
        emit("V85_WRONG_BASELINE_UNFROZEN_AND_APP_DDL_REJECTED")
        java(source, "upgrade86")
        java(source, "validate86")
        java(source, "upgrade88", expected=False)
        assert old_rows(source, tables) == before
        assert sql(source, "SELECT COUNT(*) FROM iot_platform.flyway_schema_history WHERE version='86' AND success=1") == "1"
        java(source, "upgrade86", expected=False)
        emit("V85_TO_V86_PACKAGED_MIGRATOR_VERIFIED_EXISTING_ROWS_UNCHANGED")
        java(source, "upgrade87")
        java(source, "validate87")
        java(source, "validate87")
        java(source, "upgrade87", expected=False)
        assert old_rows(source, tables) == before
        assert sql(source, "SELECT COUNT(*) FROM iot_platform.biz_carbon_formula_version WHERE formula_version_id='CFV_HEAT_CO2_V1'") == "1"
        assert sql(source, "SELECT COUNT(*) FROM iot_platform.biz_energy_reporting_record") == "0"
        assert sql(source, "SHOW GRANTS FOR 'platform_app'@'%'").count("GRANT") == 2
        emit("V86_TO_V87_EXACTLY_ONCE_VALIDATED_NO_BUILDING_CONFIGURATION_OR_DUTY_CHANGES")
        backup87 = dump(source, "--single-transaction", "--routines", "--events", "--triggers", "--databases", "iot_platform", "iot_platform_archive")
        java(source, "upgrade88", expected=False, frozen=False)
        java(source, "upgrade88", expected=False, user="platform_app")
        java(source, "validate87")
        java(source, "validate88", expected=False)
        java(source, "upgrade88")
        java(source, "validate88")
        java(source, "upgrade88", expected=False)
        assert old_rows(source, tables) == before
        assert sql(source, "SELECT COUNT(*) FROM iot_platform.flyway_schema_history WHERE version='88' AND success=1") == "1"
        emit("V87_TO_V88_PACKAGED_MIGRATOR_EXACTLY_ONCE_UNFROZEN_AND_APP_DDL_REJECTED_ROWS_PRESERVED")
        application_only(source, before, tables)
        failed88 = restore_new(backup87, before, tables, "87")
        sql(failed88, "ALTER TABLE iot_platform.biz_carbon_factor_version DROP CHECK chk_carbon_electricity_years")
        java(failed88, "upgrade88", expected=False)
        assert sql(failed88, "SELECT COUNT(*) FROM iot_platform.flyway_schema_history WHERE version='88' AND success=0") == "1"
        java(failed88, "upgrade88", expected=False)
        java(failed88, "validate88", expected=False)
        assert old_rows(failed88, tables) == before
        recovered88 = restore_new(backup87, before, tables, "87")
        java(recovered88, "upgrade88")
        java(recovered88, "validate88")
        assert old_rows(recovered88, tables) == before
        emit("V88_FAILED_HISTORY_RETAINED_RETRY_REJECTED_V87_DUAL_DATABASE_BACKUP_RESTORED_TO_NEW_INSTANCE")
        restored = restore_new(backup, before, tables)
        emit("MYSQL_MAIN_ARCHIVE_BACKUP_RESTORED_TO_NEW_INSTANCE_SOURCE_NOT_OVERWRITTEN")
        sql(restored, "CREATE TABLE iot_platform.biz_energy_reporting_receipt(blocker INT)")
        java(restored, "upgrade86", expected=False)
        assert sql(restored, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='iot_platform' AND table_name IN ('biz_energy_reporting_scope','biz_energy_reporting_record')") == "2"
        assert sql(restored, "SELECT COUNT(*) FROM iot_platform.flyway_schema_history WHERE version='86' AND success=0") == "1"
        assert old_rows(restored, tables) == before
        java(restored, "upgrade86", expected=False)
        java(restored, "validate86", expected=False)
        java(restored, "upgrade87", expected=False)
        emit("PARTIAL_V86_DDL_FAILED_HISTORY_AND_ROWS_PRESERVED_RETRY_AND_CUTOVER_REJECTED")
        next_instance = restore_new(backup, before, tables)
        java(next_instance, "upgrade86")
        # 让 V87 的单条 INSERT 碰撞，验证 V86 成功也不能冒充整个发布成功。
        sql(next_instance, "INSERT INTO iot_platform.biz_carbon_formula_version (formula_version_id,formula_code,version_no,algorithm_code,result_basis,gas_coverage,usage_nature,status,effective_from) VALUES ('CFV_HEAT_CO2_V1','PURCHASED_HEAT_CO2',1,'PURCHASED_HEAT_CO2_V1','GAS_MASS','CO2_ONLY_HEAT','FORMAL','ACTIVE','2000-01-01')")
        java(next_instance, "upgrade87", expected=False)
        assert sql(next_instance, "SELECT COUNT(*) FROM iot_platform.flyway_schema_history WHERE version='86' AND success=1") == "1"
        java(next_instance, "validate87", expected=False)
        java(next_instance, "upgrade87", expected=False)
        assert old_rows(source, tables) == before
        emit("V87_FAILURE_AFTER_V86_PRESERVED_NO_REPAIR_NO_LEGACY_RESTART")
        emit("REPORTING_RELEASE_ISOLATED_PASSED")
        emit("BOUNDARY: synthetic nonempty data; real MySQL8.4 packaged Flyway and dual-database backup/restore; service start/stop and control preservation covered separately by offline Linux tests; no server deployment, TDengine restore or field acceptance.")
    finally:
        for kind, name in reversed(RESOURCES):
            owned(kind, name)
            docker(kind, "rm", "-f", name) if kind == "container" else docker(kind, "rm", name)
        emit("ONLY_OWNED_REPORTING_RELEASE_RESOURCES_REMOVED")
        (ROOT / "target/reporting-release-results.txt").write_text("\n".join(RESULTS) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
