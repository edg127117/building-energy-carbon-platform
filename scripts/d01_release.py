"""D01 分阶段维护入口。prepare 只准备文件；其余动作仅供已批准的维护窗口。"""

import argparse
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
CONTROL = Path("/etc/building-energy-carbon-test/daikin-control-disabled.env")
RUNTIME_KEYS = ("MYSQL_HOST", "MYSQL_PORT", "MYSQL_USER", "MYSQL_FLYWAY_ENABLED", "TDENGINE_INITIALIZATION_ENABLED",
                "MQTT_ENABLED", "MQTT_CLIENT_ID", "MYSQL_CONTAINER_NAME", "MYSQL_VOLUME_NAME",
                "TDENGINE_CONTAINER_NAME", "TDENGINE_VOLUME_NAME", "TDENGINE_DATABASE", "SERVER_PORT")


def runtime():
    return runtime_settings(BACKEND, RUNTIME_KEYS)


def require(condition, code):
    if not condition:
        raise RuntimeError(code)


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
        require(target_schema in ("84", "85", "86", "87", "88", "93"), "D01_SUPPORTED_PACKAGE_REQUIRED")
        # 同版本换包必须显式声明并核验原结构，不能借空迁移区间跳过数据库校验。
        source_schema = manifest.get("sourceMigration", str(int(target_schema) - 1) if int(target_schema) <= 85 else None)
        state.update(source_schema=source_schema, target_schema=target_schema)
        if "releaseMode" in manifest:
            state["release_mode"] = manifest["releaseMode"]
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
            for version, migration in ((85, V85), (86, V86), (87, V87), (88, V88), (93, V93)):
                if int(target_schema) >= version:
                    jar.read(migration)
            jar.read("BOOT-INF/classes/com/platform/tools/D01SchemaMigration.class")
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
               *("upgrade" + str(v) for v in range(int(source) + 1, int(target) + 1)))
    require(action in allowed, "D01_SCHEMA_ACTION_NOT_AUTHORIZED")
    values = protected_json(credentials)
    endpoint = urllib.parse.urlparse(values["url"].removeprefix("jdbc:"))
    actual_runtime = state["runtime_controls"]
    require(endpoint.hostname == actual_runtime.get("MYSQL_HOST", "127.0.0.1") and
            (endpoint.port or 3306) == int(actual_runtime.get("MYSQL_PORT", "3306")) and
            endpoint.path == "/iot_platform", "D01_SCHEMA_TARGET_MISMATCH")
    require(endpoint.username is None and endpoint.password is None, "D01_CREDENTIALS_MUST_NOT_BE_IN_URL")
    if action.startswith("upgrade"):
        require(values["user"] != actual_runtime.get("MYSQL_USER", "platform_app"), "D01_SEPARATE_MIGRATION_ACCOUNT_REQUIRED")
    env = {**os.environ, "D01_JDBC_URL": values["url"], "D01_JDBC_USER": values["user"],
           "D01_JDBC_PASSWORD": values["password"], "D01_WRITERS_FROZEN": "true"}
    result = subprocess.run(["java", "-Dloader.main=com.platform.tools.D01SchemaMigration", "-cp",
                             str(Path(state["release"]) / JAR),
                             "org.springframework.boot.loader.launch.PropertiesLauncher", action],
                            env=env, capture_output=True, text=True, timeout=300)
    # 不回显 JDBC 错误或凭据。数据库失败现场由有权限的维护者另行读取。
    require(result.returncode == 0 and ("D01_SCHEMA_VALIDATED=" + action[-2:]) in result.stdout.splitlines(),
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
    require((mode == "APPLICATION_ONLY" and (source, target) in (("88", "88"), ("93", "93")))
            or (mode == "SCHEMA_UPGRADE" and (source, target) in
                (("83", "84"), ("84", "85"), ("85", "86"), ("85", "87"), ("86", "87"), ("87", "88"), ("88", "93"))),
            "D01_STATE_SCHEMA_MISMATCH")
    return target


def target_schema(state):
    return validate_schema_plan(state)


def migrate(state_path, backup_dir, credentials, restore_report=None):
    state = frozen_state(state_path)
    require(state["phase"] == "FROZEN", "D01_UPGRADE_NOT_REENTRANT")
    backup = checked_backup(state_path, state, backup_dir)
    checked_restore(backup_dir, restore_report)
    target = target_schema(state)
    if state.get("release_mode") == "APPLICATION_ONLY":
        # 仍经过停写、完整备份和恢复证据门禁；仅换包不运行任何 upgrade/repair，也不改冻结文件。
        schema("validate" + target, state, credentials)
        return
    # 每个独立进程只执行一条迁移；任一步失败保留冻结现场，不自动重试、repair 或启动旧应用。
    versions = [93] if target == "93" else range(int(state.get("source_schema", "83")) + 1, int(target) + 1)
    for index, version in enumerate(versions):
        if index:
            inactive()
            check_unchanged(state)
            check_backup_sources(backup)
        schema("upgrade" + str(version), state, credentials)
    # 不改冻结文件：备份哈希和冻结身份贯穿迁移、切换；迁移结果由 cutover 再次验证。


def cutover(state_path, backup_dir, credentials, restore_report=None):
    state = frozen_state(state_path)
    checked_backup(state_path, state, backup_dir)
    checked_restore(backup_dir, restore_report)
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
            migrate(args.state, args.backup_dir, args.schema_credentials, args.restore_report)
        else:
            cutover(args.state, args.backup_dir, args.schema_credentials, args.restore_report)
            print("D01_INFRASTRUCTURE_READY_BUSINESS_ACCEPTANCE_REQUIRED")


if __name__ == "__main__":
    main()
