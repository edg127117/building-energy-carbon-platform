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
    return docker("exec", "-i", "-e", "MYSQL_PWD", instance[0], "mysql", "-uroot", "-N", "-B",
                  data=statement.encode()).decode().strip()


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


def java(instance, action, *, packaged=True, expected=True, frozen=True, user="platform_migrator"):
    url = "jdbc:mysql://127.0.0.1:" + instance[1] + "/iot_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
    env = {**ENV, "D01_JDBC_URL": url, "D01_JDBC_USER": user, "D01_JDBC_PASSWORD": PASSWORD,
           "D01_WRITERS_FROZEN": str(frozen).lower(), "D01_UPGRADE_ISOLATED": "true"}
    if packaged:
        args = [JAVA, "-Dloader.main=com.platform.tools.D01SchemaMigration", "-cp", str(JAR),
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
                  "--no-tablespaces", "--skip-comments", *args)


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
    sql(instance, backup.decode())
    accounts(instance)
    assert old_rows(instance, tables) == expected
    java(instance, "validate" + schema)
    return instance


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
        java(source, "upgrade88")
        java(source, "validate88")
        java(source, "upgrade88", expected=False)
        assert old_rows(source, tables) == before
        assert sql(source, "SELECT COUNT(*) FROM iot_platform.flyway_schema_history WHERE version='88' AND success=1") == "1"
        emit("V87_TO_V88_PACKAGED_MIGRATOR_EXACTLY_ONCE_UNFROZEN_AND_APP_DDL_REJECTED_ROWS_PRESERVED")
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
