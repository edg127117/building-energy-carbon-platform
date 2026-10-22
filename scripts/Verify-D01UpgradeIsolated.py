"""仅在本机新建带随机所有权标记的容器演练；不接受现有服务器或数据库地址。"""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
from unittest.mock import patch
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("d01_probe", ROOT / "scripts/Verify-D01Isolated.py")
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
sys.path.insert(0, str(ROOT / "scripts"))
import d01_backup
import d01_release

LOG = []


def emit(line):
    print(line, flush=True)
    LOG.append(line)


def java(mode, *args, expected=True, packaged=False):
    env = {**probe.ENV, "D01_UPGRADE_ISOLATED": "true", "D01_WRITERS_FROZEN": "true"}
    if packaged:
        invocation = [probe.JAVA, "-Dloader.main=com.platform.tools.D01SchemaMigration", "-cp",
                      str(ROOT / "target/iot-platform-demo-1.0-SNAPSHOT.jar"),
                      "org.springframework.boot.loader.launch.PropertiesLauncher", mode]
    else:
        cp = os.pathsep.join([str(ROOT / "target/test-classes"), str(ROOT / "target/classes"),
                              (ROOT / "target/d01-classpath.txt").read_text(encoding="utf-8-sig").strip()])
        invocation = [probe.JAVA, "-Duser.timezone=Asia/Shanghai", "-cp", cp,
                      "com.platform.iot.reliability.D01UpgradeProcess", mode, *args]
    result = subprocess.run(invocation, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=240)
    if expected and result.returncode:
        raise RuntimeError("isolated Java failed " + mode + " " + result.stderr[-1200:])
    if not expected:
        assert result.returncode != 0, "failed schema must not be accepted"
    return result.stdout


def snapshot(max_menu):
    output = java("snapshot", str(max_menu))
    return json.loads(next(line.removeprefix("D01_SNAPSHOT=") for line in output.splitlines() if line.startswith("D01_SNAPSHOT=")))


def sql(container, statement):
    probe.owned("container", container)
    return probe.docker("exec", container, "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names --execute "$1"', "sh", statement)


def input_command(container, command, data):
    probe.owned("container", container)
    result = subprocess.run([probe.DOCKER, "--host", "npipe:////./pipe/dockerDesktopLinuxEngine", "exec", "-i", container,
                             "sh", "-c", command], input=data, env=probe.ENV, capture_output=True, timeout=120)
    assert result.returncode == 0, "isolated restore command failed"


def new_mysql(network, suffix):
    volume = "d01-upgrade-mysql-" + probe.NONCE + "-" + suffix
    probe.docker("volume", "create", "--label", probe.LABEL, volume)
    probe.RESOURCES["volumes"].append(volume)
    identifier, port = create_container("mysql:8.0", 3306, network, volume, suffix,
            ("-e", "MYSQL_ROOT_PASSWORD", "-e", "MYSQL_DATABASE=iot_platform"))
    probe.ENV["D01_JDBC_URL"] = "jdbc:mysql://127.0.0.1:" + str(port) + "/iot_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
    probe.ENV["D01_JDBC_USER"] = "root"
    probe.ENV["D01_JDBC_PASSWORD"] = probe.ENV["MYSQL_ROOT_PASSWORD"]
    probe.ENV["MYSQL_PWD"] = probe.ENV["MYSQL_ROOT_PASSWORD"]
    probe.wait_for(lambda: probe.docker("exec", "-e", "MYSQL_PWD", identifier, "mysql", "-uroot", "--batch", "--skip-column-names", "-e", "SELECT 1") == "1", stage="upgrade mysql")
    return identifier, volume


def create_container(image, port, network, volume, suffix, extra=()):
    identifier = probe.docker("run", "-d", "--name", "d01-upgrade-" + str(port) + "-" + probe.NONCE + "-" + suffix,
            "--label", probe.LABEL, "--network", network, "--memory", "768m", "--cpus", "1",
            "-p", "127.0.0.1::" + str(port), "--mount", "type=volume,source=" + volume + ",target=/var/lib/" +
            ("mysql" if port == 3306 else "taos"), *extra, image, timeout=180)
    probe.RESOURCES["containers"].append(identifier)
    data = probe.owned("container", identifier)
    ports = data["NetworkSettings"]["Ports"][str(port) + "/tcp"]
    assert len(ports) == 1 and ports[0]["HostIp"] == "127.0.0.1"
    return identifier, int(ports[0]["HostPort"])


def main():
    os.environ["DOCKER_HOST"] = "npipe:////./pipe/dockerDesktopLinuxEngine"
    try:
        assert probe.docker("info", "--format", "{{.OSType}}") == "linux"
        network = "d01-upgrade-" + probe.NONCE
        probe.docker("network", "create", "--label", probe.LABEL, "--driver", "bridge",
                     "--opt", "com.docker.network.bridge.host_binding_ipv4=127.0.0.1", network)
        probe.RESOURCES["networks"].append(network)
        mysql, mysql_volume = new_mysql(network, "source")
        td_volume = "d01-upgrade-td-" + probe.NONCE
        probe.docker("volume", "create", "--label", probe.LABEL, td_volume)
        probe.RESOURCES["volumes"].append(td_volume)
        td, port = create_container("tdengine/tdengine:3.2.3.0", 6041, network, td_volume, "source")
        probe.ENV["D01_TD_PORT"] = str(port)
        probe.wait_for(lambda: any("ready" in str(row).lower() for row in probe.taos("SHOW DNODES").get("data", [])), stage="upgrade td")
        td_password = "D01a!" + uuid.uuid4().hex[:14]
        probe.taos("ALTER USER root PASS '" + td_password + "'")
        probe.ENV["D01_TD_PASSWORD"] = td_password
        probe.taos("CREATE DATABASE iot_telemetry KEEP 365")
        probe.taos("CREATE STABLE iot_telemetry.st_raw_event(ts TIMESTAMP,val DOUBLE,data_quality TINYINT) TAGS(point_id NCHAR(32))")
        event_ms = int(probe.ENV["D01_EVENT_TIME"])
        probe.taos("INSERT INTO iot_telemetry.d01_raw USING iot_telemetry.st_raw_event TAGS('D01-EXISTING') VALUES(" + str(event_ms) + ",100,0)")
        td_before = probe.taos("SELECT CAST(ts AS BIGINT),val,data_quality FROM iot_telemetry.st_raw_event")["data"]
        java("init83")
        java("validate83", packaged=True)
        max_menu = int(next(line.split("=", 1)[1] for line in java("menuMax").splitlines() if line.startswith("D01_MENU_MAX=")))
        before = snapshot(max_menu)
        assert before["iot_platform.sys_sensitive_change_request"]["rows"] >= 2
        assert before["iot_platform_archive.sys_menu"]["rows"] > 0
        emit("V83_NONEMPTY_FULL_CHAIN_READY tables=" + str(len(before)) + " synthetic_existing_accounts_approvals_archive=yes")
        with tempfile.TemporaryDirectory(dir=ROOT / "target", prefix="d01-upgrade-") as temp:
            work = Path(temp)
            freeze = work / "frozen.json"
            freeze.write_text(json.dumps({"format": "D01_FROZEN_V1", "phase": "FROZEN", "freeze_id": str(uuid.uuid4()),
                                         "prepared_plan_hash": "a" * 64, "frozen_at": int(time.time()),
                                         "backup_targets": {"mysql_container": probe.owned("container", mysql)["Name"].lstrip("/"),
                                             "mysql_volume": mysql_volume, "tdengine_container": probe.owned("container", td)["Name"].lstrip("/"),
                                             "tdengine_volume": td_volume, "tdengine_database": "iot_telemetry"},
                                         "backend_service": "building-energy-carbon-test-platform.service", "web_service": "building-energy-carbon-test-nginx.service",
                                         "writers_fenced": True, "ingress_retained": True}))
            freeze.chmod(0o600)
            credentials = work / "credentials.json"
            credentials.write_text(json.dumps({"mysql": {"user": "root", "password": probe.ENV["MYSQL_ROOT_PASSWORD"]},
                                               "tdengine": {"user": "root", "password": td_password}}))
            credentials.chmod(0o600)
            # 本机未运行目标 systemd。仅把“服务已停”替换为夹具，备份和恢复引擎均为真实新容器。
            (work / "backups").mkdir()
            with patch.object(d01_backup, "_require_services_inactive", return_value=None):
                backup = d01_backup.backup(freeze_state=freeze, output_root=work / "backups", credentials_file=credentials,
                        mysql_container=probe.owned("container", mysql)["Name"].lstrip("/"), mysql_volume=mysql_volume,
                        tdengine_container=probe.owned("container", td)["Name"].lstrip("/"),
                        tdengine_volume=td_volume, tdengine_database="iot_telemetry", docker_bin=probe.DOCKER, min_free_bytes=0)
            manifest = d01_backup.validate(backup)
            assert manifest["freeze_id"] == json.loads(freeze.read_text())["freeze_id"]
            emit("REAL_BACKUP_VALIDATED mysql_main_archive=yes tdengine_nondefault_password=yes")
            java("upgrade84", packaged=True)
            java("validate84", packaged=True)
            java("validate84", packaged=True)
            assert snapshot(max_menu) == before
            assert sql(mysql, "SELECT COUNT(*) FROM iot_platform.sys_menu WHERE path='/operations/alarms/dataQuality'") == "1"
            assert sql(mysql, "SELECT COUNT(*) FROM iot_platform.sys_role_menu rm JOIN iot_platform.sys_menu m ON m.id=rm.menu_id JOIN iot_platform.sys_role r ON r.id=rm.role_id WHERE m.path='/operations/alarms/dataQuality' AND r.role_key IN ('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')") == "3"
            emit("V83_TO_V84_EXISTING_ROWS_PRESERVED all_old_tables=yes independent_packaged_migrator=yes")
            sql(mysql, "INSERT INTO iot_platform.biz_telemetry_retention_boundary VALUES('D01-AFTER-BACKUP',1)")
            emit("POST_UPGRADE_WRITE_REQUIRES_PRESERVATION no_legacy_restart_or_in_place_restore=yes")
            for identifier in (mysql, td):
                probe.owned("container", identifier)
                probe.docker("stop", identifier)
            restored_mysql, _ = new_mysql(network, "restored")
            input_command(restored_mysql, 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot', (backup / "mysql.sql").read_bytes())
            java("validate83", packaged=True)
            assert snapshot(max_menu) == before
            emit("MYSQL_RESTORE_TO_NEW_INSTANCE_VERIFIED main_archive_all_old_rows=yes source_never_overwritten=yes")
            # TD 恢复另开一次性实例，使用受保护 stdin 密码；不复用或覆盖源卷。
            restore_td_volume = "d01-upgrade-td-restore-" + probe.NONCE
            probe.docker("volume", "create", "--label", probe.LABEL, restore_td_volume)
            probe.RESOURCES["volumes"].append(restore_td_volume)
            restored_td, restore_port = create_container("tdengine/tdengine:3.2.3.0", 6041, network, restore_td_volume, "restored")
            probe.ENV["D01_TD_PORT"] = str(restore_port)
            probe.ENV["D01_TD_PASSWORD"] = "taosdata"
            probe.wait_for(lambda: any("ready" in str(row).lower() for row in probe.taos("SHOW DNODES").get("data", [])), stage="restore td")
            probe.owned("container", restored_td)
            probe.docker("cp", str(backup / "tdengine") + "/.", restored_td + ":/tmp/d01-restore")
            input_command(restored_td, "taosdump -i /tmp/d01-restore -T 1", b"")
            assert probe.taos("SELECT CAST(ts AS BIGINT),val,data_quality FROM iot_telemetry.st_raw_event")["data"] == td_before
            emit("TDENGINE_RESTORE_TO_NEW_INSTANCE_VERIFIED original_timestamp_value_quality=yes")
            probe.owned("container", mysql)
            probe.docker("start", mysql)
            probe.wait_for(lambda: sql(mysql, "SELECT COUNT(*) FROM iot_platform.biz_telemetry_retention_boundary WHERE point_key='D01-AFTER-BACKUP'") == "1", stage="preserved source evidence")
            probe.docker("stop", mysql)
            emit("POST_BACKUP_EVIDENCE_PRESERVED_ON_ORIGINAL_VOLUME baseline_restore_did_not_erase_new_evidence=yes")
            evidence = work / "restore-evidence.json"
            evidence.write_text(json.dumps({"mysql_snapshot": before, "tdengine_rows": td_before,
                                            "source_preserved": True}))
            evidence.chmod(0o600)
            report = work / "restore-report.json"
            report.write_text(json.dumps({"format": "D01_RESTORE_VERIFIED_V1",
                    "backup_manifest_sha256": d01_release.sha(backup / "manifest.json"),
                    "mysql_verified": True, "tdengine_verified": True, "source_preserved": True,
                    "restored_container_ids": [restored_mysql, restored_td],
                    "evidence_file": evidence.name, "evidence_sha256": d01_release.sha(evidence)}))
            report.chmod(0o600)
            d01_release.checked_restore(backup, report)
            emit("RESTORE_REPORT_BOUND_TO_EXACT_BACKUP verified_new_container_ids=yes execution_evidence_hash=yes")
            java("partial")
            assert snapshot(max_menu) == before
            java("upgrade84", expected=False, packaged=True)
            java("validate84", expected=False, packaged=True)
            emit("PARTIAL_V84_DDL_REPRODUCED existing_rows_preserved=yes retry_and_cutover_rejected=yes")
            # 损坏文件不能作为可部署备份；不删除源容器以外的任何资源。
            with (backup / "mysql.sql").open("ab") as stream:
                stream.write(b"\n-- tampered\n")
            try:
                d01_backup.validate(backup)
                raise AssertionError("tampered backup accepted")
            except (ValueError, RuntimeError):
                pass
            emit("TAMPERED_BACKUP_REJECTED")
        emit("D01_UPGRADE_REHEARSAL_PASSED")
        emit("BOUNDARY: synthetic nonempty V83; real MySQL/TDengine/Flyway/backup/restore; systemd external fencing mocked; no running test system, broker retention, actual deployment or field acceptance.")
    finally:
        for kind, key in (("container", "containers"), ("network", "networks"), ("volume", "volumes")):
            for identifier in reversed(probe.RESOURCES[key]):
                probe.owned(kind, identifier)
                probe.docker("rm", "-f", identifier) if kind == "container" else probe.docker(kind, "rm", identifier)
        emit("ONLY_OWNED_UPGRADE_RESOURCES_REMOVED")
        (ROOT / "target/d01-upgrade-rehearsal-results.txt").write_text("\n".join(LOG) + "\n")


if __name__ == "__main__":
    main()
