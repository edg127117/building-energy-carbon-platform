#!/usr/bin/python3
"""Administrator-only installer for one hash-pinned D01 maintenance operation."""
import argparse
import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import pwd
import re
import signal
import subprocess
import sys
import tarfile
import time
import types
import uuid
import zipfile

INSTALL = Path("/opt/building-energy-d01-maintenance")
DATA = Path("/var/lib/building-energy-d01-maintenance")
BASE = Path("/home/user1/deployments/building-energy-carbon-test")
LAUNCHER = Path("/usr/local/sbin/building-energy-d01-maintenance")
SUDOERS = Path("/etc/sudoers.d/building-energy-d01-maintenance")
TIMER = "building-energy-d01-authorization"
UNITS = Path("/etc/systemd/system")
HISTORY = Path("/var/lib/building-energy-d01-maintenance-history")
FILES = ("d01_maintenance.py", "d01_release.py", "d01_backup.py", "daikin_test_deployment_config.py",
         "d01_credentials.py", "taosdump-d01", "taosdump-LICENSE", "release.tar.gz")
ACTIONS = ("status", "prepare", "online-backup", "export-online", "record-online-restore",
           "freeze", "backup", "export-backup", "record-restore", "migrate", "cutover", "revoke")
REHEARSAL_ACTIONS = ("status", "prepare", "online-backup", "export-online", "record-online-restore", "revoke")
ALIGNMENT_VERSIONS = tuple(range(94, 108)) + tuple(range(110, 122))
ALIGNMENT_PLAN = "BOOT-INF/classes/db/shared-test-alignment.json"
ALIGNMENT_DEFAULTS = {
    "room-sensor.enabled": "true",
    "mqtt.topics.room-sensor-upstream": "/MSR01/ac233fc2b716/radar,/MSR01/ac233fc2b716/status",
    "dynamic-electricity.live-enabled": "false",
    "dynamic-electricity.evidence-retention-enabled": "false",
    "carbon-display-query.enabled": "false",
    "alarm.enabled": "false",
}
ENV = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8", "HOME": "/root"}
CREATED = []
TIMER_ATTEMPTED = False
RUNTIME_REQUIRED = {
    'AUDIT_ENVIRONMENT_MODE', 'SERVER_PORT', 'MYSQL_HOST', 'MYSQL_PORT', 'MYSQL_USER', 'MYSQL_FLYWAY_ENABLED',
    'TDENGINE_INITIALIZATION_ENABLED', 'MQTT_ENABLED', 'MQTT_CLIENT_ID', 'MYSQL_CONTAINER_NAME', 'MYSQL_VOLUME_NAME',
    'TDENGINE_CONTAINER_NAME', 'TDENGINE_VOLUME_NAME', 'TDENGINE_DATABASE', 'DAIKIN_CONTROL_ENABLED',
    'DAIKIN_CONTROL_WIRE_CONFIRMED', 'DAIKIN_CONTROL_TEST_ACCESS_ENABLED', 'DAIKIN_CONTROL_TEST_OPEN_CONTROLS',
    'DAIKIN_CONTROL_TEST_EQUIPMENT_IDS', 'DAIKIN_CONTROL_TEST_WINDOW_EXPIRES_AT', 'DAIKIN_CONTROL_FAST_READBACK_TIMEOUT_MILLIS',
}
ALIGNMENT_ENV = {
    "ROOM_SENSOR_ENABLED": "true",
    "MQTT_ROOM_SENSOR_UPSTREAM_TOPICS": ALIGNMENT_DEFAULTS["mqtt.topics.room-sensor-upstream"],
    "DYNAMIC_ELECTRICITY_LIVE_ENABLED": "false",
    "DYNAMIC_ELECTRICITY_EVIDENCE_RETENTION_ENABLED": "false",
    "CARBON_DISPLAY_QUERY_ENABLED": "false",
    "ALARM_ENABLED": "false",
}
TDENGINE_TARGET = {"TDENGINE_HOST": "127.0.0.1", "TDENGINE_PORT": "16041"}


def require(ok, code):
    if not ok:
        raise RuntimeError(code)


def interrupted(signum, frame):
    raise KeyboardInterrupt("D01_INSTALL_INTERRUPTED")


def rehearsal_alignment_valid(state):
    alignment = state.get("alignment") if isinstance(state, dict) else None
    fields = {"roomSensorBuildingId", "roomSensorSpaceId", "roomSensorMysqlSha256", "roomSensorTdSha256"}
    if not isinstance(alignment, dict) or set(alignment) != fields:
        return False
    building, space = alignment["roomSensorBuildingId"], alignment["roomSensorSpaceId"]
    mysql_sha, td_sha = alignment["roomSensorMysqlSha256"], alignment["roomSensorTdSha256"]
    return bool(isinstance(building, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,32}", building)
            and isinstance(space, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,32}", space)
            and isinstance(mysql_sha, str) and re.fullmatch(r"[a-f0-9]{64}", mysql_sha)
            and isinstance(td_sha, str) and re.fullmatch(r"[a-f0-9]{64}", td_sha))


def rehearsal_prepared_fields_valid(state, expected_keys):
    if isinstance(state, dict) and (state.get("source_schema"), state.get("target_schema")) == ("121", "123"):
        return (set(state) == expected_keys | {"source_schema", "target_schema", "release_mode"}
                and state.get("release_mode") == "SCHEMA_UPGRADE")
    return (isinstance(state, dict)
            and set(state) == expected_keys | {"source_schema", "target_schema", "release_mode", "alignment"}
            and state.get("source_schema") == "93" and state.get("target_schema") == "121"
            and state.get("release_mode") == "SCHEMA_UPGRADE" and rehearsal_alignment_valid(state))


def run(*args):
    result = subprocess.run(args, env=ENV, capture_output=True, text=True, timeout=30)
    require(result.returncode == 0, "D01_INSTALL_COMMAND_FAILED")
    return result.stdout.strip()


def trusted_parent(path):
    for part in (path, *path.parents):
        value = part.lstat()
        require(not part.is_symlink() and value.st_uid == 0 and value.st_mode & 0o022 == 0,
                "D01_INSTALL_PARENT_NOT_PROTECTED")


def create(path, content, mode):
    with path.open("xb") as stream:
        CREATED.append(path)
        os.fchmod(stream.fileno(), mode)
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())


def cleanup_failed_install():
    # Only paths created by this invocation are removed, never an earlier grant
    # or any test deployment path. Directory removal is deliberately nonrecursive.
    errors = []
    if SUDOERS in CREATED:
        SUDOERS.unlink(missing_ok=True)
    if TIMER_ATTEMPTED:
        for verb in ("stop", "disable"):
            try:
                run("/usr/bin/systemctl", verb, TIMER + ".timer")
            except Exception:
                errors.append(verb)
    for path in reversed(CREATED):
        try:
            if path.is_dir() and not path.is_symlink():
                path.rmdir()
            else:
                path.unlink(missing_ok=True)
        except OSError:
            errors.append("remove")
    if any(path.parent == UNITS for path in CREATED):
        try:
            run("/usr/bin/systemctl", "daemon-reload")
        except Exception:
            errors.append("reload")
    require(not errors, "D01_AUTH_INSTALL_CLEANUP_INCOMPLETE")


def mkdir(path, mode):
    path.mkdir(mode=mode)
    CREATED.append(path)
    # The installer deliberately uses umask 077 for secrets. Public immutable
    # tools must still be traversable by the authenticated user1 worker.
    path.chmod(mode)


def retry_configuration():
    """Rebuild the prepare fingerprint set; checking only saved keys misses added drop-ins."""
    backend = 'building-energy-carbon-test-platform.service'
    def prop(service, name):
        return run('/usr/bin/systemctl', 'show', service, '-p', name, '--value')
    paths = re.findall(r'(/\S+) \(ignore_errors=(?:yes|no)\)', prop(backend, 'EnvironmentFiles'))
    require('/etc/building-energy-carbon-test/daikin-control-disabled.env' in paths,
            'D01_OLD_CONFIGURATION_CHANGED')
    paths.append(str(BASE / 'shared/nginx-user.conf'))
    for service in (backend, 'building-energy-carbon-test-nginx.service'):
        paths.append(prop(service, 'FragmentPath'))
        paths.extend(prop(service, 'DropInPaths').split())
    return {path: hashlib.sha256(Path(path).read_bytes()).hexdigest() for path in sorted(set(paths))}


def retry_baseline(state, prepared, *, allow_other_restart=False):
    runtime = state["runtime_controls"]
    runtime_keys = set(runtime) if isinstance(runtime, dict) else set()
    allowed_runtime_keys = RUNTIME_REQUIRED | set(TDENGINE_TARGET) | set(ALIGNMENT_ENV)
    legacy_runtime = runtime_keys == RUNTIME_REQUIRED
    extended_runtime = (RUNTIME_REQUIRED | set(TDENGINE_TARGET)) <= runtime_keys <= allowed_runtime_keys
    schema121 = state.get("target_schema") in ("121", "123")
    require(isinstance(runtime, dict) and (legacy_runtime or extended_runtime)
            and (not schema121 or extended_runtime)
            and (not schema121 or all(runtime.get(key) == value for key, value in TDENGINE_TARGET.items()))
            and (not schema121 or all(key not in runtime or runtime[key] == value
                                      for key, value in ALIGNMENT_ENV.items()))
            and runtime.get('AUDIT_ENVIRONMENT_MODE') == 'TEST' and runtime.get('SERVER_PORT') == '18081'
            and runtime.get('MYSQL_FLYWAY_ENABLED') == 'false' and runtime.get('TDENGINE_INITIALIZATION_ENABLED') == 'false',
            'D01_OLD_RUNTIME_CONTRACT_INVALID')
    for service in ("building-energy-carbon-test-platform.service", "building-energy-carbon-test-nginx.service"):
        require(run("/usr/bin/systemctl", "show", service, "-p", "ActiveState", "--value") == "active",
                "D01_REINSTALL_REQUIRES_RUNNING_BASELINE")
        started = int(run("/usr/bin/systemctl", "show", service, "-p", "ExecMainStartTimestampMonotonic", "--value")) / 1_000_000
        require(started > 0 and time.time() - time.monotonic() + started <= prepared + 1,
                "D01_OLD_BASELINE_PROCESS_CHANGED")
    pid = run("/usr/bin/systemctl", "show", "building-energy-carbon-test-platform.service", "-p", "MainPID", "--value")
    require(pid.isdecimal() and int(pid) > 1, "D01_OLD_BASELINE_PROCESS_CHANGED")
    entries = (Path('/proc') / pid / 'environ').read_bytes().decode().split('\0')
    actual = {key: value for entry in entries if '=' in entry
              for key, value in [entry.split('=', 1)] if key in runtime}
    require(actual == runtime and runtime.get('AUDIT_ENVIRONMENT_MODE') == 'TEST', "D01_OLD_RUNTIME_CHANGED")
    other = Path('/var/www/iot-platform/current')
    other_pid = run('/usr/bin/systemctl', 'show', 'nginx.service', '-p', 'MainPID', '--value')
    require(str(other.resolve()) == state['other_release'] and os.readlink(other) == state['other_link']
            and (other_pid == state['other_pid'] or allow_other_restart and other_pid.isdecimal() and int(other_pid) > 1)
            and run('/usr/bin/systemctl', 'show', 'nginx.service', '-p', 'ExecReload', '--value') == state['other_reload'],
            'D01_OTHER_SITE_CHANGED')


def valid_schema_plan(source, target, mode="SCHEMA_UPGRADE"):
    # 与发布状态机一致；未显式授权的同版本包不能被默认为“不需要迁移”。
    return (mode == "APPLICATION_ONLY" and (source, target) in (("88", "88"), ("93", "93"))) or (
        mode == "SCHEMA_UPGRADE" and (source, target) in
        (("83", "84"), ("84", "85"), ("85", "86"), ("85", "87"), ("86", "87"), ("87", "88"), ("88", "93"), ("93", "121"), ("121", "123")))


def failed_online_state_path(private, config, operation):
    """仅识别已发布工具的固定状态命名缺陷；保留旧文件和失败链，不修写旧状态。"""
    expected = private / ("d01-" + config["release"] + ".json")
    if expected.exists() or expected.is_symlink():
        return expected, False
    # 此摘要对应把release形参覆盖为Td的旧prepare实现，其他缺文件故障不得借此归档。
    require(config.get("rehearsal_only") is True and config.get("source_schema") == "93"
            and config.get("target_schema") == "121" and config.get("release_mode") == "SCHEMA_UPGRADE"
            and config.get("code_sha256", {}).get("d01_release.py")
                == "9312c87292d2ce0471e5c20deb7acf402e41389318a1e3bbe574e92e93017b43"
            and operation.get("failure_code") == "D01_REGULAR_STATE_REQUIRED",
            "D01_OLD_PREPARED_STATE_MISSING")
    backup = private / "online-backup"
    require(not backup.exists() and not backup.is_symlink(), "D01_OLD_PREBACKUP_STATE_NOT_PROVEN")
    misplaced = private / "d01-Td.json"
    trusted_parent(misplaced)
    require(misplaced.is_file() and not misplaced.is_symlink(), "D01_OLD_PREPARED_STATE_MISSING")
    return misplaced, True


def retire_failed_prepare(*, online_backup=False, next_release=None, source_schema="83", target_schema="84", release_mode="SCHEMA_UPGRADE"):
    """Archive only an explicitly requested, revoked pre-freeze attempt."""
    require(not SUDOERS.exists() and not SUDOERS.is_symlink(), "D01_OLD_AUTH_STILL_PRESENT")
    private = DATA / "private"
    paths = (INSTALL, DATA, LAUNCHER, UNITS / (TIMER + ".service"), UNITS / (TIMER + ".timer"))
    for path in (*paths, private / "revoked.json", private / "operation.json", INSTALL / "config.json"):
        trusted_parent(path)
    operation = json.loads((private / "operation.json").read_text())
    config = json.loads((INSTALL / "config.json").read_text())
    # 失败重授权只能重试同一个版本计划；不能先归档旧现场，再借新包扩大授权范围。
    old_target = config.get("target_schema", "84")
    require(old_target == target_schema
            and config.get("source_schema", str(int(old_target) - 1)) == source_schema
            and config.get("release_mode", "SCHEMA_UPGRADE") == release_mode,
            "D01_RETRY_SCHEMA_PLAN_MISMATCH")
    rehearsal = config.get("rehearsal_only") is True
    if rehearsal:
        require((source_schema, target_schema, release_mode) in (("93", "121", "SCHEMA_UPGRADE"), ("121", "123", "SCHEMA_UPGRADE")),
                "D01_REHEARSAL_SCHEMA_PLAN_REQUIRED")
    expected_events = ["PREPARED", "FAILED"] if online_backup else ["FAILED"]
    require(operation.get("phase") == "FAILED"
            and operation.get("failed_action") == ("online-backup" if online_backup else "prepare")
            and [event.get("phase") for event in operation.get("events", [])] == expected_events
            and not any(key in operation for key in ("stop_requested_at", "stopped_at", "services_restored_at")),
            "D01_OLD_OPERATION_NOT_FAILED_ONLINE_BACKUP" if online_backup else "D01_OLD_OPERATION_NOT_FAILED_PREPARE")
    require(config.get("format") == "D01_TEMP_AUTH_V1" and config.get("user") == "user1"
            and str((BASE / "current").resolve()) == config.get("previous_release"), "D01_OLD_BASELINE_CHANGED")
    release = config.get("release", "")
    release_pattern = ("d01-" + config.get("commit", "")[:12] + "-rehearsal") if rehearsal else r"d01-[a-f0-9]{12}"
    release_valid = (re.fullmatch(r"[a-f0-9]{40}", config.get("commit", "")) and release == release_pattern
                     if rehearsal else re.fullmatch(release_pattern, release))
    require(release_valid
            and config.get("archive_name") == release + "-authorized.tar.gz", "D01_OLD_RELEASE_ID_INVALID")
    if online_backup:
        require(next_release and next_release != release, "D01_REAUTHORIZE_NEW_RELEASE_REQUIRED")
        for artifact in (BASE / "incoming" / (next_release + "-authorized.tar.gz"), BASE / "releases" / next_release):
            require(not artifact.exists() and not artifact.is_symlink(), "D01_NEW_RELEASE_ALREADY_EXISTS")
        state_path, before_dump = failed_online_state_path(private, config, operation)
        trusted_parent(state_path)
        state = json.loads(state_path.read_text())
        expected_keys = {'format', 'phase', 'release', 'previous_release', 'previous_jar_sha256', 'other_release',
                         'other_link', 'other_pid', 'other_reload', 'config_hashes', 'runtime_controls', 'backup_targets',
                         'backend_service', 'web_service', 'candidate_started', 'jar_sha256', 'web_sha256'}
        legacy_state_keys = (expected_keys, expected_keys | {"source_schema", "target_schema"},
                             expected_keys | {"source_schema", "target_schema", "release_mode"})
        if rehearsal:
            require(rehearsal_prepared_fields_valid(state, expected_keys), "D01_OLD_REHEARSAL_ALIGNMENT_INVALID")
        else:
            require(set(state) in legacy_state_keys, "D01_OLD_PREPARED_FIELDS_INVALID")
        require(state.get("target_schema", "84") in ("84", "85", "86", "87", "88", "93", "121", "123")
                and valid_schema_plan(state.get("source_schema", "83"), state.get("target_schema", "84"),
                                      state.get("release_mode", "SCHEMA_UPGRADE"))
                and state.get("release_mode", "SCHEMA_UPGRADE") == release_mode,
                "D01_OLD_PREPARED_FIELDS_INVALID")
        require(state.get("phase") == "PREPARED" and state.get("format") == "D01_PREPARED_V1"
                and state.get("previous_release") == config["previous_release"]
                and state.get("release") == str(BASE / "releases" / release)
                and state.get("candidate_started") is False
                and not any(key in state for key in ("freeze_id", "frozen_at", "migration_completed_at", "candidate_started_at")),
                "D01_OLD_BACKUP_NOT_PREFREEZE")
        # Old user-owned artifacts stay untouched. A new commit gets distinct
        # paths; no FAILED state reset or partial backup reuse is permitted.
        prepared = operation["events"][0].get("at_utc_epoch", 0)
        failed = operation['events'][1].get('at_utc_epoch')
        authorized = operation.get('authorized_at')
        require(all(type(value) is int for value in (authorized, prepared, failed))
                and 0 < authorized <= prepared <= failed <= time.time(), 'D01_OLD_EVENT_TIMES_INVALID')
        retry_baseline(state, prepared)
        require(hashlib.sha256((Path(config["previous_release"]) / "building-energy-carbon-platform.jar").read_bytes()).hexdigest()
                == state.get("previous_jar_sha256"), "D01_OLD_BASELINE_JAR_CHANGED")
        hashes = state.get("config_hashes")
        require(isinstance(hashes, dict) and hashes and hashes == retry_configuration(), "D01_OLD_CONFIGURATION_CHANGED")
        uid = pwd.getpwnam('user1').pw_uid
        for artifact, sha in ((BASE / 'incoming' / config['archive_name'], config['archive_sha256']),
                              (BASE / 'releases' / release / 'building-energy-carbon-platform.jar', config['jar_sha256']),
                              (BASE / 'releases' / release / 'web/index.html', config['web_sha256'])):
            require(artifact.is_file() and artifact.stat().st_uid == uid
                    and not any(part.is_symlink() for part in (artifact, *artifact.parents))
                    and hashlib.sha256(artifact.read_bytes()).hexdigest() == sha, 'D01_OLD_RELEASE_ARTIFACT_CHANGED')
        if not before_dump:
            partial = private / 'online-backup/mysql.sql'
            require(partial.is_file() and not partial.is_symlink(), 'D01_OLD_PARTIAL_BACKUP_MISSING')
            trusted_parent(partial)
            require(partial.is_file() and partial.stat().st_size > 0, 'D01_OLD_PARTIAL_BACKUP_MISSING')
    else:
        for artifact in (BASE / "incoming" / config["archive_name"], BASE / "releases" / release):
            require(not artifact.exists() and not artifact.is_symlink(), "D01_OLD_PREPARE_ARTIFACTS_REQUIRE_REVIEW")
    for name in ("schema.json", "backup.json"):
        require(not (private / name).exists() and not (private / name).is_symlink(), "D01_OLD_CREDENTIALS_NOT_REVOKED")
    require(run("/usr/bin/systemctl", "show", TIMER + ".timer", "-p", "ActiveState", "--value") == "inactive"
            and run("/usr/bin/systemctl", "show", TIMER + ".timer", "-p", "UnitFileState", "--value") == "disabled",
            "D01_OLD_TIMER_NOT_REVOKED")
    require(run("/usr/bin/systemctl", "show", "building-energy-carbon-test-platform.service", "-p", "ActiveState", "--value") == "active",
            "D01_REINSTALL_REQUIRES_RUNNING_BASELINE")
    hashes = config.get("code_sha256", {})
    require(set(hashes) in ({name for name in FILES if name.endswith('.py')},
                            {name for name in FILES if name.endswith('.py')} | {"taosdump-d01"}),
            "D01_OLD_CODE_MANIFEST_INVALID")
    for name in hashes:
        if name.endswith(".py") or name == "taosdump-d01":
            trusted_parent(INSTALL / name)
            require(hashlib.sha256((INSTALL / name).read_bytes()).hexdigest() == config.get("code_sha256", {}).get(name),
                    "D01_OLD_INSTALLED_CODE_CHANGED")
    trusted_parent(HISTORY.parent)
    require(all(path.stat().st_dev == HISTORY.parent.stat().st_dev for path in paths),
            "D01_REVOKED_ARCHIVE_FILESYSTEM_MISMATCH")
    if not HISTORY.exists():
        HISTORY.mkdir(mode=0o700)
        HISTORY.chmod(0o700)
    trusted_parent(HISTORY)
    require(HISTORY.is_dir() and HISTORY.stat().st_mode & 0o077 == 0, "D01_HISTORY_NOT_PRIVATE")
    archive = HISTORY / (("failed-online-backup-" if online_backup else "failed-prepare-") + uuid.uuid4().hex)
    archive.mkdir(mode=0o700)
    archive.chmod(0o700)
    # History is intentionally outside CREATED: a later installation failure
    # must never erase the previous attempt's audit/recovery evidence.
    try:
        for source, name in zip(paths, ("tools", "data", "launcher", "authorization.service", "authorization.timer")):
            source.rename(archive / name)
        run("/usr/bin/systemctl", "daemon-reload")
    except Exception:
        raise RuntimeError("D01_REVOKED_ARCHIVE_INCOMPLETE_ADMIN_REQUIRED") from None
    print("D01_REVOKED_ATTEMPT_ARCHIVED=" + str(archive))


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


def authorization_release_id(commit, rehearsal_only=False):
    require(re.fullmatch(r"[a-f0-9]{40}", commit), "D01_COMMIT_REQUIRED")
    return "d01-" + commit[:12] + ("-rehearsal" if rehearsal_only else "")


def retire_completed(next_release, source_schema="84"):
    """只归档与新源版本一致、已撤销且仍在运行的完成记录；不重置失败现场或删除备份。"""
    require(source_schema in ("84", "85", "86", "87", "88", "93", "121", "123"), "D01_COMPLETED_SOURCE_REQUIRED")
    require(not SUDOERS.exists() and not SUDOERS.is_symlink(), "D01_OLD_AUTH_STILL_PRESENT")
    private = DATA / "private"
    paths = (INSTALL, DATA, LAUNCHER, UNITS / (TIMER + ".service"), UNITS / (TIMER + ".timer"))
    for path in (*paths, private / "revoked.json", private / "operation.json", INSTALL / "config.json"):
        trusted_parent(path)
    config = json.loads((INSTALL / "config.json").read_text())
    operation = json.loads((private / "operation.json").read_text())
    release = config.get("release", "")
    require(config.get("format") == "D01_TEMP_AUTH_V1" and config.get("user") == "user1"
            and config.get("target_schema", "84") == source_schema
            and re.fullmatch(r"d01-[a-f0-9]{12}", release) and next_release != release
            and config.get("archive_name") == release + "-authorized.tar.gz", "D01_OLD_RELEASE_ID_INVALID")
    require(operation.get("phase") == "AWAITING_BUSINESS_ACCEPTANCE"
            and operation.get("events", [])[-1].get("phase") == "AWAITING_BUSINESS_ACCEPTANCE"
            and type(operation.get("services_restored_at")) is int, "D01_OLD_OPERATION_NOT_COMPLETED")
    for name in ("schema.json", "backup.json"):
        require(not (private / name).exists() and not (private / name).is_symlink(), "D01_OLD_CREDENTIALS_NOT_REVOKED")
    for key, expected in (("ActiveState", "inactive"), ("UnitFileState", "disabled")):
        require(run("/usr/bin/systemctl", "show", TIMER + ".timer", "-p", key, "--value") == expected,
                "D01_OLD_TIMER_NOT_REVOKED")
    state_path = private / ("d01-" + release + ".json")
    trusted_parent(state_path)
    state = json.loads(state_path.read_text())
    candidate = BASE / "releases" / release
    current = (BASE / "current").resolve()
    require(state.get("phase") == "AWAITING_BUSINESS_ACCEPTANCE" and state.get("candidate_started") is True
            and state.get("target_schema", "84") == source_schema
            and state.get("release") == str(candidate) and current.is_relative_to(BASE / "releases"),
            "D01_OLD_BASELINE_CHANGED")
    # 后续前端发布可以更换目录，但不能替换已核验的后端。完成维护后允许服务正常重启。
    # 已完成且撤销的窗口不再固定其他站点的历史 PID；新 prepare 会重新固定本轮进程。
    retry_baseline(state, int(time.time()), allow_other_restart=True)
    require(state.get("config_hashes") == retry_configuration(), "D01_OLD_CONFIGURATION_CHANGED")
    require(hashlib.sha256((candidate / "building-energy-carbon-platform.jar").read_bytes()).hexdigest()
            == state.get("jar_sha256") == config.get("jar_sha256")
            == hashlib.sha256((current / "building-energy-carbon-platform.jar").read_bytes()).hexdigest(),
            "D01_OLD_BASELINE_JAR_CHANGED")
    for artifact in (BASE / "incoming" / (next_release + "-authorized.tar.gz"), BASE / "releases" / next_release):
        require(not artifact.exists() and not artifact.is_symlink(), "D01_NEW_RELEASE_ALREADY_EXISTS")
    require(set(config.get("code_sha256", {})) == {name for name in FILES if name.endswith(".py") or name == "taosdump-d01"},
            "D01_OLD_CODE_MANIFEST_INVALID")
    for name, digest in config["code_sha256"].items():
        trusted_parent(INSTALL / name)
        require(hashlib.sha256((INSTALL / name).read_bytes()).hexdigest() == digest, "D01_OLD_INSTALLED_CODE_CHANGED")
    trusted_parent(HISTORY.parent)
    require(all(path.stat().st_dev == HISTORY.parent.stat().st_dev for path in paths),
            "D01_REVOKED_ARCHIVE_FILESYSTEM_MISMATCH")
    if not HISTORY.exists():
        HISTORY.mkdir(mode=0o700)
        HISTORY.chmod(0o700)
    trusted_parent(HISTORY)
    require(HISTORY.is_dir() and HISTORY.stat().st_mode & 0o077 == 0, "D01_HISTORY_NOT_PRIVATE")
    archive = HISTORY / ("completed-v" + source_schema + "-" + uuid.uuid4().hex)
    archive.mkdir(mode=0o700)
    try:
        for source, name in zip(paths, ("tools", "data", "launcher", "authorization.service", "authorization.timer")):
            source.rename(archive / name)
        run("/usr/bin/systemctl", "daemon-reload")
    except Exception:
        raise RuntimeError("D01_REVOKED_ARCHIVE_INCOMPLETE_ADMIN_REQUIRED") from None
    print("D01_REVOKED_ATTEMPT_ARCHIVED=" + str(archive))


def retire_revoked_rehearsal(next_release, source_schema="93"):
    """Archive a revoked, verified online-only rehearsal while preserving published release files."""
    require(source_schema in ("93", "121"), "D01_REHEARSAL_SOURCE_SCHEMA_REQUIRED")
    target_schema = "121" if source_schema == "93" else "123"
    require(re.fullmatch(r"d01-[a-f0-9]{12}(?:-rehearsal)?", next_release), "D01_REHEARSAL_RELEASE_ID_REQUIRED")
    require(not SUDOERS.exists() and not SUDOERS.is_symlink(), "D01_OLD_AUTH_STILL_PRESENT")
    private = DATA / "private"
    paths = (INSTALL, DATA, LAUNCHER, UNITS / (TIMER + ".service"), UNITS / (TIMER + ".timer"))
    for path in (*paths, private / "revoked.json", private / "operation.json", INSTALL / "config.json"):
        trusted_parent(path)
    config = json.loads((INSTALL / "config.json").read_text())
    operation = json.loads((private / "operation.json").read_text())
    release = config.get("release", "")
    commit = config.get("commit", "")
    require(config.get("format") == "D01_TEMP_AUTH_V1" and config.get("user") == "user1"
            and config.get("rehearsal_only") is True and config.get("source_schema") == source_schema
            and config.get("target_schema") == target_schema and config.get("release_mode") == "SCHEMA_UPGRADE"
            and re.fullmatch(r"[a-f0-9]{40}", commit)
            and release == "d01-" + commit[:12] + "-rehearsal"
            and release != next_release and config.get("archive_name") == release + "-authorized.tar.gz",
            "D01_OLD_REHEARSAL_IDENTITY_INVALID")
    events = operation.get("events", [])
    phases = [event.get("phase") for event in events]
    require(operation.get("phase") == "ONLINE_VERIFIED"
            and phases == ["PREPARED", "ONLINE_BACKUP", "ONLINE_VERIFIED"]
            and not any(key in operation for key in ("stop_requested_at", "stopped_at", "services_restored_at",
                    "freeze_id", "frozen_at", "migration_completed_at", "candidate_started_at")),
            "D01_OLD_REHEARSAL_NOT_ONLINE_VERIFIED")
    require(not (private / "schema.json").exists() and not (private / "schema.json").is_symlink()
            and not (private / "backup.json").exists() and not (private / "backup.json").is_symlink(),
            "D01_OLD_CREDENTIALS_NOT_REVOKED")
    revoked_path = private / "revoked.json"
    require(revoked_path.is_file() and not revoked_path.is_symlink(), "D01_OLD_AUTH_NOT_REVOKED")
    revoked = json.loads(revoked_path.read_text())
    revoked_at = revoked.get("revoked_at")
    require(type(revoked_at) is int and 0 < revoked_at <= int(time.time()), "D01_OLD_AUTH_NOT_REVOKED")
    for key, expected in (("ActiveState", "inactive"), ("UnitFileState", "disabled")):
        require(run("/usr/bin/systemctl", "show", TIMER + ".timer", "-p", key, "--value") == expected,
                "D01_OLD_TIMER_NOT_REVOKED")

    state_path = private / ("d01-" + release + ".json")
    trusted_parent(state_path)
    state = json.loads(state_path.read_text())
    candidate = BASE / "releases" / release
    previous = config.get("previous_release", "")
    current = (BASE / "current").resolve()
    require(state.get("phase") == "PREPARED" and state.get("candidate_started") is False
            and state.get("release") == str(candidate) and state.get("previous_release") == previous
            and state.get("source_schema") == source_schema and state.get("target_schema") == target_schema
            and state.get("release_mode") == "SCHEMA_UPGRADE"
            and current == Path(previous).resolve() and current.is_relative_to(BASE / "releases"),
            "D01_OLD_REHEARSAL_BASELINE_CHANGED")
    prepared_at = events[0].get("at_utc_epoch")
    backup_at = events[1].get("at_utc_epoch")
    verified_at = events[2].get("at_utc_epoch")
    authorized_at = operation.get("authorized_at")
    require(all(type(value) is int for value in (authorized_at, prepared_at, backup_at, verified_at))
            and 0 < authorized_at <= prepared_at <= backup_at <= verified_at <= revoked_at,
            "D01_OLD_REHEARSAL_EVENT_TIMES_INVALID")
    retry_baseline(state, prepared_at)
    require(state.get("config_hashes") == retry_configuration(), "D01_OLD_CONFIGURATION_CHANGED")
    require(hashlib.sha256((Path(previous) / "building-energy-carbon-platform.jar").read_bytes()).hexdigest()
            == state.get("previous_jar_sha256")
            == hashlib.sha256((current / "building-energy-carbon-platform.jar").read_bytes()).hexdigest(),
            "D01_OLD_BASELINE_JAR_CHANGED")
    require(hashlib.sha256((candidate / "building-energy-carbon-platform.jar").read_bytes()).hexdigest()
            == state.get("jar_sha256") == config.get("jar_sha256")
            and hashlib.sha256((candidate / "web/index.html").read_bytes()).hexdigest()
            == state.get("web_sha256") == config.get("web_sha256"), "D01_OLD_REHEARSAL_ARTIFACT_CHANGED")
    for artifact in (BASE / "incoming" / (next_release + "-authorized.tar.gz"), BASE / "releases" / next_release):
        require(not artifact.exists() and not artifact.is_symlink(), "D01_REHEARSAL_RELEASE_COLLISION")
    uid = pwd.getpwnam("user1").pw_uid
    for artifact, sha in ((BASE / "incoming" / config["archive_name"], config["archive_sha256"]),
                          (candidate / "building-energy-carbon-platform.jar", config["jar_sha256"]),
                          (candidate / "web/index.html", config["web_sha256"])):
        require(artifact.is_file() and artifact.stat().st_uid == uid
                and not any(part.is_symlink() for part in (artifact, *artifact.parents))
                and hashlib.sha256(artifact.read_bytes()).hexdigest() == sha,
                "D01_OLD_REHEARSAL_ARTIFACT_CHANGED")
    require(set(config.get("code_sha256", {})) == {name for name in FILES if name.endswith(".py") or name == "taosdump-d01"},
            "D01_OLD_CODE_MANIFEST_INVALID")
    for name, digest in config["code_sha256"].items():
        trusted_parent(INSTALL / name)
        require(hashlib.sha256((INSTALL / name).read_bytes()).hexdigest() == digest, "D01_OLD_INSTALLED_CODE_CHANGED")
    for name in ("online-backup/manifest.json", "online-restore/report.json", "online-restore/evidence.json"):
        evidence = private / name
        trusted_parent(evidence)
        require(evidence.is_file() and not evidence.is_symlink() and evidence.stat().st_size > 0,
                "D01_OLD_REHEARSAL_EVIDENCE_MISSING")
    trusted_parent(HISTORY.parent)
    require(all(path.stat().st_dev == HISTORY.parent.stat().st_dev for path in paths),
            "D01_REVOKED_ARCHIVE_FILESYSTEM_MISMATCH")
    if not HISTORY.exists():
        HISTORY.mkdir(mode=0o700)
        HISTORY.chmod(0o700)
    trusted_parent(HISTORY)
    require(HISTORY.is_dir() and HISTORY.stat().st_mode & 0o077 == 0, "D01_HISTORY_NOT_PRIVATE")
    archive = HISTORY / ("rehearsal-v" + source_schema + "-" + uuid.uuid4().hex)
    archive.mkdir(mode=0o700)
    try:
        for source, name in zip(paths, ("tools", "data", "launcher", "authorization.service", "authorization.timer")):
            source.rename(archive / name)
        run("/usr/bin/systemctl", "daemon-reload")
    except Exception:
        raise RuntimeError("D01_REVOKED_ARCHIVE_INCOMPLETE_ADMIN_REQUIRED") from None
    print("D01_REVOKED_REHEARSAL_ARCHIVED=" + str(archive))


def bundle_files(raw):
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz") as archive:
        members = archive.getmembers()
        require(len(members) == len(FILES) and {m.name for m in members} == set(FILES)
                and all(m.isfile() and 0 < m.size <= 512 * 1024**2 for m in members), "D01_BUNDLE_MEMBERS_INVALID")
        return {m.name: archive.extractfile(m).read() for m in members}


def package_manifest(raw):
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz") as archive:
        members = archive.getmembers()
        require(len(members) <= 10000 and sum(m.size for m in members) <= 1024**3
                and len({m.name for m in members}) == len(members)
                and all(not Path(m.name).is_absolute() and ".." not in Path(m.name).parts
                        and (m.isfile() or m.isdir()) for m in members), "D01_PACKAGE_MEMBERS_INVALID")
        manifest = dict(line.split("=", 1) for line in archive.extractfile("MANIFEST.txt").read().decode().splitlines() if "=" in line)
        jar = archive.extractfile("building-energy-carbon-platform.jar").read()
        index = archive.extractfile("web/index.html").read()
        require(manifest.get("expectedMigration") in ("84", "85", "86", "87", "88", "93", "121", "123")
                and re.fullmatch("[a-f0-9]{40}", manifest.get("commit", ""))
                and hashlib.sha256(jar).hexdigest() == manifest.get("backendJarSha256")
                and hashlib.sha256(index).hexdigest() == manifest.get("webIndexSha256"), "D01_PACKAGE_MANIFEST_INVALID")
        target = manifest["expectedMigration"]
        source = manifest.get("sourceMigration", str(int(target) - 1) if int(target) <= 85 else None)
        require(valid_schema_plan(source, target, manifest.get("releaseMode", "SCHEMA_UPGRADE")),
                "D01_PACKAGE_SCHEMA_PLAN_REQUIRED")
        with zipfile.ZipFile(io.BytesIO(jar)) as packaged:
            versions = [int(match[1]) for name in packaged.namelist()
                        if (match := re.fullmatch(r"BOOT-INF/classes/db/migration/mysql/V(\d+)__[^/]+\.sql", name))]
            require(versions and max(versions) == int(manifest["expectedMigration"]), "D01_PACKAGE_SCHEMA_MISMATCH")
            if target == "93":
                require({v for v in versions if 88 < v <= 93} == {93}, "D01_V93_SINGLE_MIGRATION_REQUIRED")
            if target == "121":
                plan_bytes = packaged.read(ALIGNMENT_PLAN)
                plan = json.loads(plan_bytes)
                require(plan.get("format") == "SHARED_ALIGNMENT_93_121_V1"
                        and plan.get("source") == 93 and plan.get("target") == 121
                        and plan.get("runtimeDefaults") == ALIGNMENT_DEFAULTS,
                        "D01_ALIGNMENT_PLAN_REQUIRED")
                items = plan.get("migrations", [])
                require(tuple(i.get("version") for i in items) == ALIGNMENT_VERSIONS,
                        "D01_ALIGNMENT_MIGRATION_SEQUENCE_REQUIRED")
                expected = []
                for item in items:
                    filename = item.get("script", "")
                    require(re.fullmatch("V" + str(item["version"]) + r"__[A-Za-z0-9_]+\.sql", filename),
                            "D01_ALIGNMENT_SCRIPT_REQUIRED")
                    path = "BOOT-INF/classes/db/migration/mysql/" + filename
                    packaged.read(path)
                    expected.append(path)
                actual = [n for n in packaged.namelist()
                          if (m := re.fullmatch(r"BOOT-INF/classes/db/migration/mysql/V(\d+)__[^/]+\.sql", n)) and int(m[1]) > 93]
                require(len(actual) == len(set(actual)) == len(expected) and set(actual) == set(expected),
                        "D01_ALIGNMENT_PACKAGE_SEQUENCE_MISMATCH")
                require(hashlib.sha256(plan_bytes).hexdigest() == manifest.get("alignmentPlanSha256"),
                        "D01_ALIGNMENT_PLAN_HASH_MISMATCH")
                require(packaged.read("BOOT-INF/classes/application.properties").decode("utf-8")
                        == "".join(k + "=" + v + "\n" for k, v in ALIGNMENT_DEFAULTS.items()),
                        "D01_ALIGNMENT_RUNTIME_BOUNDARY_MISMATCH")
                require(manifest.get("preserveFrontendSha256") == manifest.get("webIndexSha256"),
                        "D01_ALIGNMENT_FRONTEND_MUST_BE_PRESERVED")
                for name in ("Building", "Space"):
                    require(re.fullmatch(r"[A-Za-z0-9_-]{1,32}", manifest.get("roomSensor" + name + "Id", "")),
                            "D01_ROOM_BINDING_REQUIRED")
                for name, resource in (("Mysql", "configure-b314.sql"), ("Td", "tdengine.sql")):
                    content = packaged.read("BOOT-INF/classes/db/room-sensor/" + resource)
                    require(hashlib.sha256(content).hexdigest() == manifest.get("roomSensor" + name + "Sha256"),
                            "D01_ROOM_RESOURCE_HASH_MISMATCH")
                packaged.read("BOOT-INF/classes/com/platform/tools/D01RoomSensorBootstrap.class")
                packaged.read("BOOT-INF/classes/com/platform/tools/SharedAlignmentSchemaContract.class")
            if target == "123":
                require(manifest.get("artifactMode") == "UNIFIED_SHARED_121_123"
                        and manifest.get("expectedDatabase") == "iot_platform"
                        and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", manifest.get("screenBuildingCode", "")),
                        "D01_UNIFIED_PACKAGE_PLAN_REQUIRED")
                require(packaged.read("BOOT-INF/classes/application.properties").decode("utf-8")
                        == "".join(k + "=" + v + "\n" for k, v in ALIGNMENT_DEFAULTS.items()),
                        "D01_ALIGNMENT_RUNTIME_BOUNDARY_MISMATCH")
                for version, suffix in ((121, "room_sensor_ingestion"), (122, "ledger_display_rules"), (123, "device_communication")):
                    content = packaged.read("BOOT-INF/classes/db/migration/mysql/V" + str(version) + "__mysql_" + suffix + ".sql")
                    require(version == 121 or not re.search(rb"(?i)\bUSE\s+", content),
                            "D01_UNIFIED_CROSS_DATABASE_SQL_FORBIDDEN")
                packaged.read("BOOT-INF/classes/com/platform/iot/roomsensor/RoomSensorIngestor.class")
                packaged.read("BOOT-INF/classes/com/platform/hvac/asset/ledger/LedgerController.class")
            packaged.read("BOOT-INF/classes/db/migration/mysql/V84__mysql_telemetry_immutable_conflicts.sql")
            for version, filename in ((85, "V85__mysql_d01_test_building_retirement.sql"),
                                      (86, "V86__mysql_energy_reporting.sql"),
                                      (87, "V87__mysql_heat_co2_factor_formula.sql"),
                                      (88, "V88__mysql_default_electricity_factor_year.sql"),
                                      (93, "V93__mysql_historical_metering_scope.sql")):
                if int(target) >= version:
                    packaged.read("BOOT-INF/classes/db/migration/mysql/" + filename)
            packaged.read("BOOT-INF/classes/com/platform/tools/D01SchemaMigration.class")
        return manifest, jar


def credentials(jar, source, target_schema="84", release_mode="SCHEMA_UPGRADE"):
    print("复用服务器既有受保护管理员配置，执行只读登录及权限核查；无需输入数据库密码。")
    # The module is compiled from the already hashed bundle bytes, never
    # imported from the administrator's cwd or a user-writable upload path.
    module = types.ModuleType("d01_credentials")
    exec(compile(source, "verified-d01-credentials", "exec"), module.__dict__)
    return module.discover(jar, target_schema, release_mode)


def install(bundle, expected_sha, *, replace_revoked_prepare=False, replace_revoked_online_backup=False,
            replace_revoked_completed=False, replace_revoked_rehearsal=False,
            authorization_reference=None, rehearsal_only=False):
    global TIMER_ATTEMPTED
    require(os.geteuid() == 0, "D01_ADMIN_REQUIRED")
    require(sys.stdin.isatty(), "D01_ADMIN_TERMINAL_REQUIRED")
    os.environ.clear()
    os.environ.update(ENV)
    os.umask(0o077)
    # Read once, hash those exact bytes, then extract from memory. A hash check
    # followed by executing a mutable pathname would not pin administrator trust.
    require(bundle.stat().st_size <= 512 * 1024**2, "D01_BUNDLE_TOO_LARGE")
    raw = bundle.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == expected_sha, "D01_BUNDLE_HASH_MISMATCH")
    files = bundle_files(raw)
    manifest, jar = package_manifest(files["release.tar.gz"])
    target_schema = manifest.get("expectedMigration", "84")
    source_schema = manifest.get("sourceMigration", str(int(target_schema) - 1))
    release_mode = manifest.get("releaseMode", "SCHEMA_UPGRADE")
    require(not rehearsal_only or (source_schema, target_schema) in (("93", "121"), ("121", "123")),
            "D01_REHEARSAL_SCHEMA_PLAN_REQUIRED")
    require(sum((replace_revoked_prepare, replace_revoked_online_backup, replace_revoked_completed,
                 replace_revoked_rehearsal)) <= 1,
            "D01_ONE_REPLACEMENT_MODE_REQUIRED")
    require(not replace_revoked_rehearsal or (source_schema, target_schema) in (("93", "121"), ("121", "123")),
            "D01_REHEARSAL_REPLACEMENT_PLAN_REQUIRED")
    require(target_schema == "84" or (isinstance(authorization_reference, str)
            and 0 < len(authorization_reference.strip()) <= 512
            and not any(c in authorization_reference for c in "\0\r\n")), "D01_NEW_MAINTENANCE_REFERENCE_REQUIRED")
    release = authorization_release_id(manifest["commit"], rehearsal_only)
    if replace_revoked_rehearsal:
        require(INSTALL.exists() or INSTALL.is_symlink(), "D01_REHEARSAL_AUTH_NOT_FOUND")
        retire_revoked_rehearsal(release, source_schema)
    if replace_revoked_completed:
        require(target_schema in ("85", "86", "87", "88", "93", "121", "123"), "D01_COMPLETED_REPLACEMENT_TARGET_REQUIRED")
        if INSTALL.exists() or INSTALL.is_symlink():
            retire_completed(release, source_schema)
    if (replace_revoked_prepare or replace_revoked_online_backup) and (INSTALL.exists() or INSTALL.is_symlink()):
        retire_failed_prepare(online_backup=replace_revoked_online_backup, next_release=release,
                              source_schema=source_schema, target_schema=target_schema, release_mode=release_mode)
    for destination in (INSTALL, DATA, LAUNCHER, SUDOERS,
                        UNITS / (TIMER + ".service"), UNITS / (TIMER + ".timer")):
        trusted_parent(destination.parent)
        require(not destination.exists() and not destination.is_symlink(), "D01_AUTH_ALREADY_EXISTS")
    require(pwd.getpwnam("user1").pw_uid != 0, "D01_RUNTIME_USER_INVALID")
    require(run("/usr/bin/systemctl", "show", "building-energy-carbon-test-platform.service", "-p", "User", "--value") == "user1",
            "D01_EXPECTED_SERVICE_USER_REQUIRED")
    previous = str((BASE / "current").resolve())
    require(Path(previous).is_relative_to(BASE / "releases"), "D01_CURRENT_OUTSIDE_TEST_RELEASES")
    # 本轮只授权在线备份/恢复核验；源结构校验只需双库SELECT，不提前要求或授予升级DDL权限。
    schema, backup, credential_report = credentials(jar, files["d01_credentials.py"],
                                                   source_schema if rehearsal_only else target_schema,
                                                   "APPLICATION_ONLY" if rehearsal_only else release_mode)
    expires = int(time.time()) + 24 * 3600
    sudoers = "# One installed D01 operation; no wildcard arguments or SETENV.\n"
    sudoers += "Defaults!" + str(LAUNCHER) + " env_reset, !setenv, secure_path=/usr/sbin:/usr/bin:/sbin:/bin\n"
    allowed_actions = REHEARSAL_ACTIONS if rehearsal_only else ACTIONS
    sudoers += "user1 ALL=(root) NOPASSWD: " + ", ".join(str(LAUNCHER) + " " + action for action in allowed_actions) + "\n"
    launcher = "#!/bin/sh\nexec /usr/bin/env -i PATH=/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8 /usr/bin/python3 -I " + str(INSTALL / "d01_maintenance.py") + ' "$@"\n'
    config = {"format": "D01_TEMP_AUTH_V1", "user": "user1", "expires_at": expires,
              "release": release, "archive_name": release + "-authorized.tar.gz", "previous_release": previous,
              "archive_sha256": hashlib.sha256(files["release.tar.gz"]).hexdigest(),
              "jar_sha256": manifest["backendJarSha256"], "web_sha256": manifest["webIndexSha256"],
              "commit": manifest["commit"],
              "source_schema": source_schema, "target_schema": target_schema, "release_mode": release_mode,
              "code_sha256": {name: hashlib.sha256(files[name]).hexdigest() for name in FILES if name.endswith(".py") or name == "taosdump-d01"},
              "sudoers_sha256": hashlib.sha256(sudoers.encode()).hexdigest(),
              "authorization_reference": authorization_reference or "D01 test maintenance approved; HTTP/MQTT unrecoverable gaps accepted"}
    if rehearsal_only:
        require((source_schema, target_schema) in (("93", "121"), ("121", "123")), "D01_REHEARSAL_SCHEMA_PLAN_REQUIRED")
        config["rehearsal_only"] = True
    mkdir(INSTALL, 0o755)
    mkdir(DATA, 0o755)
    private = DATA / "private"
    mkdir(private, 0o700)
    for name, content in files.items():
        create(INSTALL / name, content, 0o755 if name == "taosdump-d01" else 0o644)
    create(INSTALL / "building-energy-carbon-platform.jar", jar, 0o644)
    create(INSTALL / "config.json", json_bytes(config), 0o644)
    create(private / "schema.json", json_bytes(schema), 0o600)
    create(private / "backup.json", json_bytes(backup), 0o600)
    create(private / "credential-check.json", json_bytes(credential_report), 0o600)
    create(private / "operation.json", json_bytes({"phase": "AUTHORIZED", "authorized_at": int(time.time()),
                                                 "events": []}), 0o600)
    create(LAUNCHER, launcher.encode(), 0o755)
    unit = "[Unit]\nDescription=Revoke temporary D01 maintenance authorization\n[Service]\nType=oneshot\nExecStart=" + str(LAUNCHER) + " revoke\n"
    calendar = datetime.datetime.fromtimestamp(expires, datetime.timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    timer = "[Unit]\nDescription=D01 authorization expiry\n[Timer]\nOnCalendar=" + calendar + "\nPersistent=true\nAccuracySec=1s\n[Install]\nWantedBy=timers.target\n"
    service_path = UNITS / (TIMER + ".service")
    timer_path = service_path.with_suffix(".timer")
    create(service_path, unit.encode(), 0o644)
    create(timer_path, timer.encode(), 0o644)
    # No grant is published until the expiry timer and sudoers syntax work.
    candidate = private / "sudoers.candidate"
    create(candidate, sudoers.encode(), 0o440)
    run("/usr/sbin/visudo", "-cf", str(candidate))
    run("/usr/bin/systemctl", "daemon-reload")
    TIMER_ATTEMPTED = True
    run("/usr/bin/systemctl", "enable", "--now", TIMER + ".timer")
    require(run("/usr/bin/systemctl", "is-active", TIMER + ".timer") == "active", "D01_EXPIRY_TIMER_NOT_ACTIVE")
    try:
        create(SUDOERS, sudoers.encode(), 0o440)
        run("/usr/sbin/visudo", "-c")
        create(private / "installation-complete.json", json_bytes({"installed_at": int(time.time())}), 0o600)
    except BaseException:
        SUDOERS.unlink(missing_ok=True)
        raise
    print("D01_TEMP_AUTH_INSTALLED")
    print("Expires (UTC): " + calendar)
    print("Revoke: sudo " + str(LAUNCHER) + " revoke")
    print("Existing credentials and direct privileges verified. Application services have not been stopped or restarted.")
    if rehearsal_only:
        print("Rehearsal-only: online backup and isolated restore verification only; shared freeze, migration and cutover are prohibited.")
    else:
        print("Actual backup and isolated restore verification are still required before freeze.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--replace-revoked-prepare", action="store_true",
                        help="archive only a revoked attempt that failed before freeze; preserve all evidence")
    parser.add_argument("--replace-revoked-online-backup", action="store_true",
                        help="archive a revoked failed online backup; keep partial backups and old release artifacts")
    parser.add_argument("--replace-revoked-completed", action="store_true",
                        help="archive revoked completed records matching the new source version; preserve all backups")
    parser.add_argument("--replace-revoked-rehearsal", action="store_true",
                        help="archive only a revoked online-verified rehearsal that never froze or switched the old release")
    parser.add_argument("--authorization-reference", help="non-secret reference to the new approved maintenance and ingress-loss decision")
    parser.add_argument("--rehearsal-only", action="store_true", help="permit online backup/restore verification only; no freeze, schema upgrade or cutover")
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGHUP, interrupted)
    try:
        install(args.bundle, args.sha256, replace_revoked_prepare=args.replace_revoked_prepare,
                replace_revoked_online_backup=args.replace_revoked_online_backup,
                replace_revoked_completed=args.replace_revoked_completed,
                replace_revoked_rehearsal=args.replace_revoked_rehearsal, authorization_reference=args.authorization_reference,
                rehearsal_only=args.rehearsal_only)
    except BaseException as error:
        try:
            cleanup_failed_install()
        except Exception:
            print("D01_AUTH_INSTALL_CLEANUP_INCOMPLETE", file=sys.stderr)
        text = str(error)
        print(text if text.startswith("D01_") and text.replace("_", "").isalnum()
              else "D01_AUTH_INSTALL_FAILED_" + type(error).__name__, file=sys.stderr)
        sys.exit(1)
