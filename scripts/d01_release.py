"""D01 分阶段维护入口。prepare 只准备文件；其余动作仅供已批准的维护窗口。"""

import argparse
import base64
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import time
import urllib.request
import urllib.error
import urllib.parse
import uuid
import zipfile

from daikin_test_deployment_config import TEST_SETTINGS, runtime_settings
from d01_backup import validate as validate_backup, _container as inspect_backup_container


BASE = Path("/home/user1/deployments/building-energy-carbon-test")
BACKEND = "building-energy-carbon-test-platform.service"
WEB = "building-energy-carbon-test-nginx.service"
OTHER = Path("/var/www/iot-platform/current")
OTHER_SERVICE = "nginx.service"
JAR = "building-energy-carbon-platform.jar"
V84 = "BOOT-INF/classes/db/migration/mysql/V84__mysql_telemetry_immutable_conflicts.sql"
V85 = "BOOT-INF/classes/db/migration/mysql/V85__mysql_d01_test_building_retirement.sql"
V86 = "BOOT-INF/classes/db/migration/mysql/V86__mysql_energy_reporting.sql"
V87 = "BOOT-INF/classes/db/migration/mysql/V87__mysql_heat_co2_factor_formula.sql"
V88 = "BOOT-INF/classes/db/migration/mysql/V88__mysql_default_electricity_factor_year.sql"
V93 = "BOOT-INF/classes/db/migration/mysql/V93__mysql_historical_metering_scope.sql"
ALIGNMENT_PLAN = "BOOT-INF/classes/db/shared-test-alignment.json"
ALIGNMENT_VERSIONS = tuple(range(94, 108)) + tuple(range(110, 122))
ALIGNMENT_DEFAULTS = {
    "room-sensor.enabled": "true",
    "mqtt.topics.room-sensor-upstream": "/MSR01/ac233fc2b716/radar,/MSR01/ac233fc2b716/status",
    "dynamic-electricity.live-enabled": "false",
    "dynamic-electricity.evidence-retention-enabled": "false",
    "carbon-display-query.enabled": "false",
    "alarm.enabled": "false",
}
CONTROL = Path("/etc/building-energy-carbon-test/daikin-control-disabled.env")
RUNTIME_KEYS = ("MYSQL_HOST", "MYSQL_PORT", "MYSQL_USER", "MYSQL_FLYWAY_ENABLED", "TDENGINE_INITIALIZATION_ENABLED",
                "MQTT_ENABLED", "MQTT_CLIENT_ID", "MYSQL_CONTAINER_NAME", "MYSQL_VOLUME_NAME",
                "TDENGINE_CONTAINER_NAME", "TDENGINE_VOLUME_NAME", "TDENGINE_DATABASE", "TDENGINE_HOST", "TDENGINE_PORT", "SERVER_PORT")
ALIGNMENT_ENV = {
    "ROOM_SENSOR_ENABLED": "true",
    "MQTT_ROOM_SENSOR_UPSTREAM_TOPICS": ALIGNMENT_DEFAULTS["mqtt.topics.room-sensor-upstream"],
    "DYNAMIC_ELECTRICITY_LIVE_ENABLED": "false",
    "DYNAMIC_ELECTRICITY_EVIDENCE_RETENTION_ENABLED": "false",
    "CARBON_DISPLAY_QUERY_ENABLED": "false",
    "ALARM_ENABLED": "false",
}
RUNTIME_KEYS += tuple(ALIGNMENT_ENV)


def runtime():
    return runtime_settings(BACKEND, RUNTIME_KEYS)


def require(condition, code):
    if not condition:
        raise RuntimeError(code)


def reusable_upgrade(source, target):
    return (isinstance(source, str) and isinstance(target, str)
            and re.fullmatch(r"[1-9][0-9]{2,8}", source)
            and re.fullmatch(r"[1-9][0-9]{2,8}", target)
            and 123 <= int(source) < int(target))


def mysql_migrations(jar):
    migrations = {}
    for name in jar.namelist():
        match = re.fullmatch(r"BOOT-INF/classes/db/migration/mysql/V([0-9]{1,9})__[^/]+\.sql", name)
        if match:
            version = int(match[1])
            require(version not in migrations, "RELEASE_DUPLICATE_MIGRATION_VERSION")
            migrations[version] = name
    return migrations


def packaged_upgrade_steps(jar, source, target):
    migrations = mysql_migrations(jar)
    require(reusable_upgrade(source, target) and int(source) in migrations
            and max(migrations) == int(target), "RELEASE_MIGRATION_RANGE_MISMATCH")
    versions = tuple(sorted(v for v in migrations if v > int(source)))
    for version in versions:
        require(not re.search(rb"(?i)\bUSE\s+", jar.read(migrations[version])),
                "RELEASE_CROSS_DATABASE_SQL_FORBIDDEN")
    return versions


def verified_upgrade_steps(jar, manifest, environment):
    source, target = manifest.get("sourceMigration"), manifest.get("expectedMigration")
    require(manifest.get("artifactMode") == f"{environment.upper()}_SCHEMA_UPGRADE"
            and manifest.get("releaseMode") == "SCHEMA_UPGRADE", "RELEASE_ENVIRONMENT_OR_MODE_MISMATCH")
    versions = packaged_upgrade_steps(jar, source, target)
    require(manifest.get("migrationSteps") == ",".join(map(str, versions)), "RELEASE_MIGRATION_STEPS_MISMATCH")
    return versions


def verify_migration_history(previous, candidate, source):
    with zipfile.ZipFile(previous) as current:
        before = mysql_migrations(current)
        after = mysql_migrations(candidate)
        require(before and max(before) == int(source)
                and {v: name for v, name in after.items() if v <= int(source)} == before
                and all(current.read(name) == candidate.read(name) for name in before.values()),
                "RELEASE_EXISTING_MIGRATION_CHANGED")


def migration_versions(source, target, steps=None):
    """只展开已批准路径；版本跳号不是待补迁移，也不接受调用者任意指定 SQL。"""
    if reusable_upgrade(source, target):
        require(isinstance(steps, list) and steps and all(type(v) is int for v in steps),
                "RELEASE_MIGRATION_STEPS_REQUIRED")
        require(steps == sorted(set(steps)) and int(source) < steps[0] and steps[-1] == int(target),
                "RELEASE_MIGRATION_STEPS_INVALID")
        return tuple(steps)
    if (source, target) == ("93", "121"):
        return ALIGNMENT_VERSIONS
    if (source, target) == ("88", "93"):
        return (93,)
    return tuple(range(int(source) + 1, int(target) + 1))


def alignment_plan(jar):
    """核验包内唯一的固定迁移清单，供迁移、授权和候选构建共同使用。"""
    plan = json.loads(jar.read(ALIGNMENT_PLAN))
    require(plan.get("format") == "SHARED_ALIGNMENT_93_121_V1"
            and plan.get("source") == 93 and plan.get("target") == 121,
            "D01_ALIGNMENT_PLAN_REQUIRED")
    require(plan.get("runtimeDefaults") == ALIGNMENT_DEFAULTS, "D01_ALIGNMENT_RUNTIME_BOUNDARY_MISMATCH")
    items = plan.get("migrations", [])
    require(isinstance(items, list) and tuple(i.get("version") for i in items) == ALIGNMENT_VERSIONS,
            "D01_ALIGNMENT_MIGRATION_SEQUENCE_REQUIRED")
    expected = []
    for item in items:
        name = item.get("script", "")
        require(re.fullmatch("V" + str(item["version"]) + r"__[A-Za-z0-9_]+\.sql", name),
                "D01_ALIGNMENT_SCRIPT_REQUIRED")
        path = "BOOT-INF/classes/db/migration/mysql/" + name
        jar.read(path)
        expected.append(path)
    actual = [name for name in jar.namelist()
              if (m := re.fullmatch(r"BOOT-INF/classes/db/migration/mysql/V(\d+)__[^/]+\.sql", name))
              and int(m[1]) > 93]
    require(len(actual) == len(set(actual)) == len(expected) and set(actual) == set(expected),
            "D01_ALIGNMENT_PACKAGE_SEQUENCE_MISMATCH")
    return plan


def unified_shared_package(jar, manifest):
    """新授权只覆盖已运行 V121 到 V123；保留原房间接入边界，不重复初始化绑定。"""
    require(manifest.get("artifactMode") == "UNIFIED_SHARED_121_123"
            and manifest.get("sourceMigration") == "121" and manifest.get("expectedMigration") == "123"
            and manifest.get("expectedDatabase") == "iot_platform"
            and manifest.get("backupPolicy") == "FROZEN_SINGLE_RESTORE_V1",
            "D01_UNIFIED_PACKAGE_PLAN_REQUIRED")
    require(re.fullmatch(r"[A-Za-z0-9_-]{1,64}", manifest.get("screenBuildingCode", "")),
            "D01_UNIFIED_BUILDING_REQUIRED")
    require(jar.read("BOOT-INF/classes/application.properties").decode("utf-8")
            == "".join(k + "=" + v + "\n" for k, v in ALIGNMENT_DEFAULTS.items()),
            "D01_ALIGNMENT_RUNTIME_BOUNDARY_MISMATCH")
    for version, name in ((121, "room_sensor_ingestion"), (122, "ledger_display_rules"), (123, "device_communication")):
        content = jar.read("BOOT-INF/classes/db/migration/mysql/V" + str(version) + "__mysql_" + name + ".sql")
        require(version == 121 or not re.search(rb"(?i)\bUSE\s+", content),
                "D01_UNIFIED_CROSS_DATABASE_SQL_FORBIDDEN")
    jar.read("BOOT-INF/classes/com/platform/iot/roomsensor/RoomSensorIngestor.class")
    jar.read("BOOT-INF/classes/com/platform/hvac/asset/ledger/LedgerController.class")


def frontend_fingerprint(directory):
    require(directory.is_dir() and not directory.is_symlink(), "D01_FRONTEND_DIRECTORY_REQUIRED")
    files = list(directory.rglob("*"))
    require(not any(p.is_symlink() for p in files), "D01_FRONTEND_SYMLINK_FORBIDDEN")
    return {p.relative_to(directory).as_posix(): sha(p) for p in files if p.is_file()}


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def command(*args):
    result = subprocess.run(list(args), capture_output=True, text=True, timeout=60)
    require(result.returncode == 0, "D01_COMMAND_FAILED_" + args[0])
    return result.stdout.strip()


def service_property(service, name):
    return command("systemctl", "show", service, "--property=" + name, "--value")


def inactive():
    for service in (BACKEND, WEB):
        require(service_property(service, "ActiveState") == "inactive", "D01_WRITER_OR_WEB_NOT_STOPPED")


def protected_json(path):
    require(not path.is_symlink() and path.is_file(), "D01_REGULAR_STATE_REQUIRED")
    if os.name == "posix":
        require(path.stat().st_mode & 0o077 == 0 and path.stat().st_uid == os.geteuid(),
                "D01_PRIVATE_FILE_REQUIRED")
    return json.loads(path.read_text())


def write_state(path, state, new=False):
    temp = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        with os.fdopen(os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
            json.dump(state, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        require(not new or not path.exists(), "D01_STATE_ALREADY_EXISTS")
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)


@contextlib.contextmanager
def release_lock():
    import fcntl
    with (BASE / ".d01-release.lock").open("a") as stream:
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def environment_files():
    raw = service_property(BACKEND, "EnvironmentFiles")
    paths = re.findall(r"(/\S+) \(ignore_errors=(?:yes|no)\)", raw)
    require(paths and CONTROL.as_posix() in paths, "D01_ENVIRONMENT_FILES_REQUIRED")
    return paths


def fingerprints():
    paths = environment_files()
    paths.append(str(BASE / "shared/nginx-user.conf"))
    for service in (BACKEND, WEB):
        paths.append(service_property(service, "FragmentPath"))
        paths.extend(service_property(service, "DropInPaths").split())
    return {path: sha(Path(path)) for path in sorted(set(paths))}


def check_unchanged(state):
    require(str((BASE / "current").resolve()) == state["previous_release"], "D01_CURRENT_CHANGED")
    require(str(OTHER.resolve()) == state["other_release"], "D01_OTHER_SITE_CHANGED")
    require(os.readlink(OTHER) == state["other_link"], "D01_OTHER_LINK_CHANGED")
    require(service_property(OTHER_SERVICE, "MainPID") == state["other_pid"], "D01_OTHER_SITE_RESTARTED")
    require(service_property(OTHER_SERVICE, "ExecReload") == state["other_reload"], "D01_OTHER_SITE_RELOADED")
    require(fingerprints() == state["config_hashes"], "D01_CONFIGURATION_CHANGED")
    require(sha(Path(state["previous_release"]) / JAR) == state["previous_jar_sha256"], "D01_BASELINE_JAR_CHANGED")


def state_directory():
    return BASE / "backups"


def stage_package(archive, target):
    # The temporary privilege bridge runs this filesystem work as the runtime
    # user; root must not extract through a user-writable deployment ancestor.
    target.mkdir(mode=0o755)
    with tarfile.open(archive, "r:gz") as package:
        members = package.getmembers()
        require(len(members) <= 10000 and sum(m.size for m in members) <= 1024**3, "D01_PACKAGE_TOO_LARGE")
        names = [m.name for m in members]
        require(len(names) == len(set(names)), "D01_DUPLICATE_ARCHIVE_MEMBER")
        for member in members:
            require(not Path(member.name).is_absolute() and ".." not in Path(member.name).parts
                    and (member.isfile() or member.isdir()), "D01_UNSAFE_ARCHIVE_MEMBER")
        package.extractall(target, members=members)
    for path in [target, *target.rglob("*")]:
        path.chmod(0o755 if path.is_dir() else 0o644)


def switch_current(state):
    next_link = BASE / (".d01-next-" + uuid.uuid4().hex)
    next_link.symlink_to(state["release"])
    os.replace(next_link, BASE / "current")


def prepare(name, archive, expected_sha, credentials):
    require(re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name), "D01_INVALID_RELEASE_NAME")
    require(archive.resolve().is_relative_to((BASE / "incoming").resolve()), "D01_ARCHIVE_OUTSIDE_INCOMING")
    require(sha(archive) == expected_sha, "D01_ARCHIVE_HASH_MISMATCH")
    require(service_property(BACKEND, "ActiveState") == "active", "D01_PREPARE_REQUIRES_RUNNING_BASELINE")
    require(service_property(BACKEND, "User") not in ("", "root"), "D01_NONROOT_RUNTIME_REQUIRED")
    route = (BASE / "shared/nginx-user.conf").read_text()
    require(re.search(r"(?m)^\s*listen\s+18080;", route) and
            "root " + str(BASE / "current/web") + ";" in route and
            "proxy_pass http://127.0.0.1:18081;" in route, "D01_TEST_ROUTE_REQUIRED")
    current = (BASE / "current").resolve()
    require(current.is_relative_to((BASE / "releases").resolve()), "D01_CURRENT_OUTSIDE_RELEASES")
    current_runtime = runtime()
    require(current_runtime.get("AUDIT_ENVIRONMENT_MODE") == "TEST", "D01_TEST_ENVIRONMENT_REQUIRED")
    require(current_runtime.get("SERVER_PORT") == "18081", "D01_TEST_API_PORT_REQUIRED")
    require(current_runtime.get("MYSQL_FLYWAY_ENABLED") == "false" and
            current_runtime.get("TDENGINE_INITIALIZATION_ENABLED") == "false", "D01_RUNTIME_DDL_MUST_STAY_DISABLED")
    backup_keys = {"mysql_container": "MYSQL_CONTAINER_NAME", "mysql_volume": "MYSQL_VOLUME_NAME",
                   "tdengine_container": "TDENGINE_CONTAINER_NAME", "tdengine_volume": "TDENGINE_VOLUME_NAME",
                   "tdengine_database": "TDENGINE_DATABASE"}
    require(all(current_runtime.get(key) for key in backup_keys.values()), "D01_BACKUP_TARGETS_REQUIRED")
    # 保存原有开关，不调用通用发布入口的持续开放配置，也不持久化进程中的密码。
    state = {"format": "D01_PREPARED_V1", "phase": "PREPARED", "release": str(BASE / "releases" / name),
             "previous_release": str(current), "previous_jar_sha256": sha(current / JAR),
             "other_release": str(OTHER.resolve()), "other_link": os.readlink(OTHER),
             "other_pid": service_property(OTHER_SERVICE, "MainPID"),
             "other_reload": service_property(OTHER_SERVICE, "ExecReload"),
             "config_hashes": fingerprints(), "runtime_controls": current_runtime,
             "backup_targets": {key: current_runtime[value] for key, value in backup_keys.items()},
             "backend_service": BACKEND, "web_service": WEB, "candidate_started": False}
    target = Path(state["release"])
    require(not target.exists(), "D01_RELEASE_EXISTS")
    try:
        stage_package(archive, target)
        manifest = dict(line.split("=", 1) for line in (target / "MANIFEST.txt").read_text().splitlines() if "=" in line)
        target_schema = manifest.get("expectedMigration")
        new_upgrade = reusable_upgrade(manifest.get("sourceMigration"), target_schema)
        require(new_upgrade or target_schema in ("84", "85", "86", "87", "88", "93", "121", "123"), "D01_SUPPORTED_PACKAGE_REQUIRED")
        # 同版本换包必须显式声明并核验原结构，不能借空迁移区间跳过数据库校验。
        source_schema = manifest.get("sourceMigration", str(int(target_schema) - 1) if int(target_schema) <= 85 else None)
        state.update(source_schema=source_schema, target_schema=target_schema)
        if "releaseMode" in manifest:
            state["release_mode"] = manifest["releaseMode"]
        if not new_upgrade:
            validate_schema_plan(state)
        require(re.fullmatch(r"[a-f0-9]{40}", manifest.get("commit", "")), "D01_COMMIT_REQUIRED")
        require(sha(target / JAR) == manifest["backendJarSha256"] and
                sha(target / "web/index.html") == manifest["webIndexSha256"], "D01_PACKAGE_CONTENT_MISMATCH")
        with zipfile.ZipFile(target / JAR) as jar:
            jar.read(V84)
            versions = [int(match[1]) for name in jar.namelist()
                        if (match := re.fullmatch(r"BOOT-INF/classes/db/migration/mysql/V(\d+)__[^/]+\.sql", name))]
            require(max(versions) == int(target_schema), "D01_PACKAGE_SCHEMA_MISMATCH")
            if target_schema == "93":
                require({v for v in versions if 88 < v <= 93} == {93}, "D01_V93_SINGLE_MIGRATION_REQUIRED")
            if target_schema == "121":
                plan = alignment_plan(jar)
                require(hashlib.sha256(jar.read(ALIGNMENT_PLAN)).hexdigest() == manifest.get("alignmentPlanSha256"),
                        "D01_ALIGNMENT_PLAN_HASH_MISMATCH")
                # 旧配置文件字节不改；新增业务边界由候选内标准 Properties 资源声明。
                defaults = jar.read("BOOT-INF/classes/application.properties").decode("utf-8")
                require(defaults == "".join(k + "=" + v + "\n" for k, v in ALIGNMENT_DEFAULTS.items()),
                        "D01_ALIGNMENT_RUNTIME_BOUNDARY_MISMATCH")
                require(all(key not in current_runtime or current_runtime[key] == value
                            for key, value in ALIGNMENT_ENV.items()), "D01_ALIGNMENT_EXTERNAL_OVERRIDE_CONFLICT")
                require(sha(current / "web/index.html") == manifest.get("preserveFrontendSha256"),
                        "D01_ALIGNMENT_FRONTEND_MUST_BE_PRESERVED")
                require(frontend_fingerprint(target / "web") == frontend_fingerprint(current / "web"),
                        "D01_ALIGNMENT_FRONTEND_MUST_BE_PRESERVED")
                for id_kind in ("Building", "Space"):
                    require(re.fullmatch(r"[A-Za-z0-9_-]{1,32}", manifest.get("roomSensor" + id_kind + "Id", "")),
                            "D01_ROOM_BINDING_REQUIRED")
                for resource_kind, resource_name in (("Mysql", "configure-b314.sql"), ("Td", "tdengine.sql")):
                    require(hashlib.sha256(jar.read("BOOT-INF/classes/db/room-sensor/" + resource_name)).hexdigest()
                            == manifest.get("roomSensor" + resource_kind + "Sha256"), "D01_ROOM_RESOURCE_HASH_MISMATCH")
                jar.read("BOOT-INF/classes/com/platform/tools/D01RoomSensorBootstrap.class")
                jar.read("BOOT-INF/classes/com/platform/tools/SharedAlignmentSchemaContract.class")
                state["alignment"] = {key: manifest[key] for key in
                                      ("roomSensorBuildingId", "roomSensorSpaceId", "roomSensorMysqlSha256", "roomSensorTdSha256")}
            if new_upgrade:
                require(manifest.get("expectedDatabase") == "iot_platform"
                        and manifest.get("backupPolicy") == "FROZEN_SINGLE_RESTORE_V1"
                        and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", manifest.get("screenBuildingCode", "")),
                        "D01_UNIFIED_PACKAGE_PLAN_REQUIRED")
                current_manifest = dict(line.split("=", 1) for line in
                                        (current / "MANIFEST.txt").read_text().splitlines() if "=" in line)
                require(current_manifest.get("expectedMigration") == source_schema
                        and current_manifest.get("screenBuildingCode") == manifest["screenBuildingCode"],
                        "D01_UPGRADE_BASELINE_OR_BINDING_CHANGED")
                steps = verified_upgrade_steps(jar, manifest, "shared")
                verify_migration_history(current / JAR, jar, source_schema)
                with zipfile.ZipFile(current / JAR) as previous:
                    properties = "BOOT-INF/classes/application.properties"
                    require(jar.read(properties) == previous.read(properties), "D01_ALIGNMENT_RUNTIME_BOUNDARY_MISMATCH")
                state["migration_steps"] = list(steps)
            if target_schema == "123":
                unified_shared_package(jar, manifest)
                require(all(key not in current_runtime or current_runtime[key] == value
                            for key, value in ALIGNMENT_ENV.items()), "D01_ALIGNMENT_EXTERNAL_OVERRIDE_CONFLICT")
            for version, migration in ((85, V85), (86, V86), (87, V87), (88, V88), (93, V93)):
                if int(target_schema) >= version:
                    jar.read(migration)
            jar.read("BOOT-INF/classes/com/platform/tools/D01SchemaMigration.class")
        if new_upgrade:
            validate_schema_plan(state)
        state["jar_sha256"] = sha(target / JAR)
        state["web_sha256"] = sha(target / "web/index.html")
        schema("validate" + state["source_schema"], state, credentials)
        state_path = state_directory() / ("d01-" + name + ".json")
        write_state(state_path, state, new=True)
        return state_path
    except Exception:
        # 保留未发布产物以定位失败；current 和正在运行的进程均未更改。
        raise


def freeze(state_path, writers_fenced, ingress_retained, accept_ingress_loss=False,
           loss_acceptance_reference=None):
    state = protected_json(state_path)
    require(state["phase"] == "PREPARED", "D01_PREPARED_STATE_REQUIRED")
    target_schema(state)
    require(writers_fenced and (ingress_retained != accept_ingress_loss),
            "D01_EXTERNAL_WRITERS_AND_INGRESS_DECISION_REQUIRED")
    require(not accept_ingress_loss or (isinstance(loss_acceptance_reference, str)
            and loss_acceptance_reference.strip() and len(loss_acceptance_reference) <= 512
            and not any(c in loss_acceptance_reference for c in "\0\r\n")),
            "D01_LOSS_ACCEPTANCE_REFERENCE_REQUIRED")
    check_unchanged(state)
    require(runtime() == state["runtime_controls"], "D01_RUNTIME_CONTROLS_CHANGED")
    # 此动作只供维护窗口调用；失败不启动任何旧包，保留停写现场。
    command("systemctl", "stop", WEB, BACKEND)
    inactive()
    state.update(format="D01_FROZEN_V1", phase="FROZEN", freeze_id=str(uuid.uuid4()), frozen_at=int(time.time()),
                 prepared_plan_hash=sha(state_path),
                 writers_fenced=True, ingress_retained=ingress_retained,
                 ingress_loss_accepted=accept_ingress_loss,
                 loss_acceptance_reference=loss_acceptance_reference if accept_ingress_loss else None)
    write_state(state_path, state)


def frozen_state(state_path):
    state = protected_json(state_path)
    require(state["format"] == "D01_FROZEN_V1" and state["phase"] in ("FROZEN", "SCHEMA_READY"), "D01_FROZEN_STATE_REQUIRED")
    inactive()
    check_unchanged(state)
    require(not state["candidate_started"], "D01_CANDIDATE_ALREADY_STARTED")
    require(sha(Path(state["release"]) / JAR) == state["jar_sha256"], "D01_CANDIDATE_CHANGED")
    return state


def checked_backup(state_path, state, backup_dir):
    manifest = validate_backup(backup_dir)
    require(manifest["freeze_id"] == state["freeze_id"] and
            manifest["freeze_state_sha256"] == sha(state_path), "D01_BACKUP_FREEZE_MISMATCH")
    require(manifest["backup_targets"] == state["backup_targets"], "D01_BACKUP_TARGET_MISMATCH")
    check_backup_sources(manifest)
    return manifest


def check_backup_sources(manifest):
    # 名称和卷名相同不代表仍是备份时的实例；同名重建后不能沿用原恢复证据继续升级。
    for engine, destination in (("mysql", "/var/lib/mysql"), ("tdengine", "/var/lib/taos")):
        source = manifest["sources"][engine]
        current = inspect_backup_container("docker", source["container"], source["volume"],
                                           destination, subprocess.run)
        require(all(source.get(key) == value for key, value in current.items()),
                "D01_BACKUP_SOURCE_CHANGED")


def schema(action, state, credentials):
    target = target_schema(state)
    source = state.get("source_schema", "83")
    allowed = ("validate" + source, "validate" + target,
               *("upgrade" + str(v) for v in migration_versions(source, target, state.get("migration_steps"))))
    if target == "121":
        allowed += ("bootstrap121",)
    require(action in allowed, "D01_SCHEMA_ACTION_NOT_AUTHORIZED")
    values = protected_json(credentials)
    endpoint = urllib.parse.urlparse(values["url"].removeprefix("jdbc:"))
    actual_runtime = state["runtime_controls"]
    require(endpoint.hostname == actual_runtime.get("MYSQL_HOST", "127.0.0.1") and
            (endpoint.port or 3306) == int(actual_runtime.get("MYSQL_PORT", "3306")) and
            endpoint.path == "/iot_platform", "D01_SCHEMA_TARGET_MISMATCH")
    require(endpoint.username is None and endpoint.password is None, "D01_CREDENTIALS_MUST_NOT_BE_IN_URL")
    if action.startswith("upgrade") or action == "bootstrap121":
        require(values["user"] != actual_runtime.get("MYSQL_USER", "platform_app"), "D01_SEPARATE_MIGRATION_ACCOUNT_REQUIRED")
    env = {**os.environ, "D01_JDBC_URL": values["url"], "D01_JDBC_USER": values["user"],
           "D01_JDBC_PASSWORD": values["password"], "D01_WRITERS_FROZEN": "true"}
    if action.startswith("upgrade") and reusable_upgrade(source, target):
        versions = migration_versions(source, target, state.get("migration_steps"))
        index = versions.index(int(action.removeprefix("upgrade")))
        env["D01_EXPECTED_PREDECESSOR"] = str(versions[index - 1]) if index else source
    bootstrap = action == "bootstrap121"
    if bootstrap:
        env.update(D01_ROOM_BUILDING_ID=state["alignment"]["roomSensorBuildingId"],
                   D01_ROOM_SPACE_ID=state["alignment"]["roomSensorSpaceId"])
    loader = "D01RoomSensorBootstrap" if bootstrap else "D01SchemaMigration"
    result = subprocess.run(["java", "-Dloader.main=com.platform.tools." + loader, "-cp",
                             str(Path(state["release"]) / JAR),
                             "org.springframework.boot.loader.launch.PropertiesLauncher", *([] if bootstrap else [action])],
                            env=env, capture_output=True, text=True, timeout=300)
    # 不回显 JDBC 错误或凭据。数据库失败现场由有权限的维护者另行读取。
    version = re.search(r"[0-9]+$", action)[0]
    require(result.returncode == 0 and ("D01_SCHEMA_VALIDATED=" + version) in result.stdout.splitlines(),
            "D01_SCHEMA_CHECK_FAILED")


def checked_restore(backup_dir, restore_report):
    require(restore_report is not None, "D01_RESTORE_EVIDENCE_REQUIRED")
    report = protected_json(restore_report)
    require(report.get("format") == "D01_RESTORE_VERIFIED_V1" and
            report.get("backup_manifest_sha256") == sha(backup_dir / "manifest.json") and
            all(report.get(key) is True for key in ("mysql_verified", "tdengine_verified", "source_preserved")),
            "D01_RESTORE_EVIDENCE_MISMATCH")
    source = json.loads((backup_dir / "manifest.json").read_text())["sources"]
    targets = report.get("restored_container_ids", [])
    require(len(targets) == 2 and len(set(targets)) == 2 and
            not set(targets).intersection(value["container_id"] for value in source.values()),
            "D01_RESTORE_MUST_USE_NEW_CONTAINERS")
    evidence_name = report["evidence_file"]
    require(isinstance(evidence_name, str) and Path(evidence_name).name == evidence_name,
            "D01_RESTORE_EVIDENCE_FILENAME_REQUIRED")
    evidence = restore_report.parent / evidence_name
    require(not evidence.is_symlink() and evidence.is_file() and sha(evidence) == report["evidence_sha256"],
            "D01_RESTORE_EXECUTION_EVIDENCE_CHANGED")


def validate_schema_plan(state):
    # 旧授权缺少版本字段时只代表 83→84；新增路径不能借用旧授权或跳过中间迁移。
    target = state.get("target_schema", "84")
    source = state.get("source_schema", "83")
    mode = state.get("release_mode", "SCHEMA_UPGRADE")
    if mode == "SCHEMA_UPGRADE" and reusable_upgrade(source, target):
        migration_versions(source, target, state.get("migration_steps"))
        return target
    require((mode == "APPLICATION_ONLY" and (source, target) in (("88", "88"), ("93", "93")))
            or (mode == "SCHEMA_UPGRADE" and (source, target) in
                (("83", "84"), ("84", "85"), ("85", "86"), ("85", "87"), ("86", "87"), ("87", "88"), ("88", "93"), ("93", "121"), ("121", "123"))),
            "D01_STATE_SCHEMA_MISMATCH")
    return target


def target_schema(state):
    return validate_schema_plan(state)


def alignment_ready_path(state_path):
    return Path(state_path).with_name(Path(state_path).stem + "-alignment-ready.json")


def minimum_td_keep_minutes(value):
    """统一旧版天数与实测元数据的分钟单位；未知格式交由调用方拒绝。"""
    text = str(value)
    if not re.fullmatch(r"[0-9]{1,10}m?(?:,[0-9]{1,10}m?)*", text):
        return None
    periods = []
    for segment in text.split(","):
        if segment.endswith("m"):
            periods.append(int(segment[:-1]))
        else:
            periods.append(int(segment) * 1440)
    return min(periods)


class NoTdRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def bootstrap_room_tdengine(state, credentials, *, verify_only=False, preflight_only=False):
    """仅初始化/核验两张房间历史表；不创建数据库，不修改KEEP或既有超级表。"""
    require(credentials is not None, "D01_TD_BOOTSTRAP_CREDENTIALS_REQUIRED")
    runtime = state["runtime_controls"]
    require(runtime.get("TDENGINE_HOST") == "127.0.0.1" and runtime.get("TDENGINE_PORT") == "16041"
            and runtime.get("TDENGINE_DATABASE") == "iot_telemetry", "D01_TD_BOOTSTRAP_TARGET_MISMATCH")
    auth = protected_json(credentials)["tdengine"]
    token = base64.b64encode((auth["user"] + ":" + auth["password"]).encode()).decode()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoTdRedirect())

    def execute(sql):
        request = urllib.request.Request("http://127.0.0.1:16041/rest/sql", data=sql.encode(),
                                         headers={"Authorization": "Basic " + token})
        with opener.open(request, timeout=30) as response:
            result = json.load(response)
        require(result.get("code") == 0, "D01_TD_BOOTSTRAP_SQL_FAILED")
        return result

    meta = execute("SELECT * FROM information_schema.ins_databases WHERE name='iot_telemetry'")
    require(len(meta.get("data", [])) == 1, "D01_TD_BOOTSTRAP_DATABASE_REQUIRED")
    values = dict(zip([c[0].strip('`').lower() for c in meta["column_meta"]], meta["data"][0]))
    require(values.get("precision") == "ms", "D01_TD_BOOTSTRAP_MILLISECOND_PRECISION_REQUIRED")
    keep = values.get("keep")
    if keep is None:
        ddl = execute("SHOW CREATE DATABASE iot_telemetry")
        match = re.search(r"\bKEEP\s+([0-9,]+)(?=\s|;|$)", " ".join(str(v) for row in ddl.get("data", []) for v in row), re.I)
        keep = match[1] if match else ""
    minimum_keep_minutes = minimum_td_keep_minutes(keep)
    require(minimum_keep_minutes is not None and minimum_keep_minutes >= 10080,
            "D01_TD_BOOTSTRAP_KEEP_AT_LEAST_SEVEN_REQUIRED")
    # 在任何MySQL升级前检查凭据、目标、精度和保留期，避免前置条件失败时留下半升级现场。
    if preflight_only:
        return
    common = [("ts", "TIMESTAMP", 8), ("received_at", "BIGINT", 8), ("device_time", "VARCHAR", 64),
              ("event_id", "VARCHAR", 36), ("data_hash", "VARCHAR", 64), ("identity_id", "VARCHAR", 32),
              ("equip_id", "VARCHAR", 32), ("building_id", "VARCHAR", 32), ("space_id", "VARCHAR", 32),
              ("gateway_mac", "VARCHAR", 12), ("device_mac", "VARCHAR", 12)]
    expected = {"st_radar_observation": common + [("target_count", "INT", 4), ("device_seq", "INT", 4), ("source_id", "VARCHAR", 32)],
                "st_door_observation": common + [("door_open", "BOOL", 1), ("source_id", "VARCHAR", 32)]}
    if not verify_only:
        with zipfile.ZipFile(Path(state["release"]) / JAR) as jar:
            content = jar.read("BOOT-INF/classes/db/room-sensor/tdengine.sql")
        require(hashlib.sha256(content).hexdigest() == state["alignment"]["roomSensorTdSha256"],
                "D01_ROOM_RESOURCE_HASH_MISMATCH")
        statements = [s.strip() for s in re.sub(r"(?m)^\s*--.*$", "", content.decode()).split(";") if s.strip()]
        names = [re.match(r"CREATE STABLE IF NOT EXISTS (st_[a-z_]+)\s*\(", sql) for sql in statements]
        require(len(names) == 2 and all(names) and {m[1] for m in names} == set(expected),
                "D01_TD_BOOTSTRAP_FIXED_SCHEMA_REQUIRED")
        for sql, match in zip(statements, names):
            execute(sql[:match.start(1)] + "iot_telemetry." + sql[match.start(1):])
    for table, fields in expected.items():
        result = execute("DESCRIBE iot_telemetry." + table)
        rows = result.get("data", [])
        actual = [(r[0].lower(), "VARCHAR" if r[1].upper() == "BINARY" else r[1].upper(), int(r[2])) for r in rows]
        require(actual == fields and str(rows[-1][3]).upper() == "TAG", "D01_TD_BOOTSTRAP_SCHEMA_MISMATCH")


def migrate(state_path, backup_dir, credentials, restore_report=None, td_credentials=None):
    state = frozen_state(state_path)
    require(state["phase"] == "FROZEN", "D01_UPGRADE_NOT_REENTRANT")
    backup = checked_backup(state_path, state, backup_dir)
    checked_restore(backup_dir, restore_report)
    target = target_schema(state)
    if target == "121":
        bootstrap_room_tdengine(state, td_credentials, preflight_only=True)
    if state.get("release_mode") == "APPLICATION_ONLY":
        # 仍经过停写、完整备份和恢复证据门禁；仅换包不运行任何 upgrade/repair，也不改冻结文件。
        schema("validate" + target, state, credentials)
        return
    # 每个独立进程只执行一条迁移；任一步失败保留冻结现场，不自动重试、repair 或启动旧应用。
    versions = migration_versions(state.get("source_schema", "83"), target, state.get("migration_steps"))
    for index, version in enumerate(versions):
        if index:
            inactive()
            check_unchanged(state)
            check_backup_sources(backup)
        schema("upgrade" + str(version), state, credentials)
    if target == "121":
        schema("bootstrap121", state, credentials)
        bootstrap_room_tdengine(state, td_credentials)
        write_state(alignment_ready_path(state_path), {"format": "D01_ALIGNMENT_READY_V1",
                    "freeze_sha256": sha(state_path), "jar_sha256": state["jar_sha256"]}, new=True)
    # 不改冻结文件：备份哈希和冻结身份贯穿迁移、切换；迁移结果由 cutover 再次验证。


def cutover(state_path, backup_dir, credentials, restore_report=None, td_credentials=None):
    state = frozen_state(state_path)
    checked_backup(state_path, state, backup_dir)
    checked_restore(backup_dir, restore_report)
    if target_schema(state) == "121":
        ready = protected_json(alignment_ready_path(state_path))
        require(ready == {"format": "D01_ALIGNMENT_READY_V1", "freeze_sha256": sha(state_path),
                          "jar_sha256": state["jar_sha256"]}, "D01_ALIGNMENT_NOT_READY")
        bootstrap_room_tdengine(state, td_credentials, verify_only=True)
    schema("validate" + target_schema(state), state, credentials)
    require(sha(Path(state["release"]) / "web/index.html") == state["web_sha256"], "D01_WEB_CHANGED")
    # 在首次启动前落盘不可自动回退标记。即使健康检查失败，也不能假定尚无后台写入。
    state.update(phase="STARTING", candidate_started=True)
    write_state(state_path, state)
    try:
        switch_current(state)
        command("systemctl", "start", BACKEND)
        command("systemctl", "start", WEB)
        deadline = time.monotonic() + 180
        while True:
            try:
                with urllib.request.urlopen("http://127.0.0.1:18080/", timeout=3) as response:
                    actual = hashlib.sha256(response.read()).hexdigest()
                require(actual == state["web_sha256"], "D01_WEB_RESPONSE_MISMATCH")
                try:
                    with urllib.request.urlopen("http://127.0.0.1:18080/api/auth/me", timeout=3) as response:
                        require(response.status == 200, "D01_API_NOT_READY")
                except urllib.error.HTTPError as error:
                    require(error.code == 401, "D01_API_NOT_READY")
                require(service_property(BACKEND, "ActiveState") == "active", "D01_BACKEND_NOT_ACTIVE")
                require(service_property(WEB, "ActiveState") == "active", "D01_TEST_WEB_NOT_ACTIVE")
                require(runtime() == state["runtime_controls"], "D01_RUNTIME_CONTROLS_CHANGED")
                require(fingerprints() == state["config_hashes"], "D01_CONFIGURATION_CHANGED")
                require(str(OTHER.resolve()) == state["other_release"] and
                        os.readlink(OTHER) == state["other_link"] and
                        service_property(OTHER_SERVICE, "MainPID") == state["other_pid"] and
                        service_property(OTHER_SERVICE, "ExecReload") == state["other_reload"], "D01_OTHER_SITE_CHANGED")
                break
            except (OSError, RuntimeError):
                if time.monotonic() >= deadline:
                    raise RuntimeError("D01_STARTUP_ACCEPTANCE_FAILED") from None
                time.sleep(2)
        state.update(phase="AWAITING_BUSINESS_ACCEPTANCE")
        write_state(state_path, state)
    except BaseException:
        # 停机调用也必须有界；返回码成功后还要确认 inactive，不能把重启中的候选记为已停。
        stopped = False
        try:
            result = subprocess.run(["systemctl", "stop", WEB, BACKEND], capture_output=True, timeout=60)
            if result.returncode == 0:
                inactive()
                stopped = True
        except (OSError, subprocess.SubprocessError, RuntimeError):
            pass
        state.update(phase="FAILED_STOPPED" if stopped else "FAILED_STOP_ERROR")
        write_state(state_path, state)
        raise RuntimeError("D01_FAIL_STOP_NO_LEGACY_RESTART" if stopped else "D01_FAIL_STOP_FAILED") from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "freeze", "migrate", "cutover"))
    parser.add_argument("--release")
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--sha256")
    parser.add_argument("--state", type=Path)
    parser.add_argument("--backup-dir", type=Path)
    parser.add_argument("--schema-credentials", type=Path)
    parser.add_argument("--td-credentials", type=Path)
    parser.add_argument("--restore-report", type=Path)
    parser.add_argument("--writers-fenced", action="store_true")
    ingress = parser.add_mutually_exclusive_group()
    ingress.add_argument("--ingress-retained", action="store_true")
    ingress.add_argument("--accept-ingress-loss", action="store_true")
    parser.add_argument("--loss-acceptance-reference")
    args = parser.parse_args()
    require(os.geteuid() == 0, "D01_ROOT_REQUIRED")
    with release_lock():
        if args.action == "prepare":
            require(args.release and args.archive and args.sha256 and args.schema_credentials, "D01_PACKAGE_ARGUMENTS_REQUIRED")
            print(prepare(args.release, args.archive, args.sha256, args.schema_credentials))
        elif args.action == "freeze":
            freeze(args.state, args.writers_fenced, args.ingress_retained,
                   args.accept_ingress_loss, args.loss_acceptance_reference)
        elif args.action == "migrate":
            migrate(args.state, args.backup_dir, args.schema_credentials, args.restore_report, args.td_credentials)
        else:
            cutover(args.state, args.backup_dir, args.schema_credentials, args.restore_report, args.td_credentials)
            print("D01_INFRASTRUCTURE_READY_BUSINESS_ACCEPTANCE_REQUIRED")


if __name__ == "__main__":
    main()
