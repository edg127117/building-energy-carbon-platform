#!/usr/bin/env python3
"""Create and verify a D01 frozen-window backup.

This tool intentionally has no restore command. A restore must be an explicit
operation against newly created isolated containers.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import uuid
from typing import Any, Optional, Sequence


FREEZE_FORMAT = "D01_FROZEN_V1"
MANIFEST_FORMAT = "D01_BACKUP_V1"
CHECKSUM_FILE = "SHA256SUMS"
MANIFEST_FILE = "manifest.json"
FAILURE_FILE = "FAILED.json"
MAX_CONTROL_FILE_BYTES = 64 * 1024
DEFAULT_MIN_FREE_BYTES = 10 * 1024 * 1024 * 1024
METADATA_TIMEOUT_SECONDS = 30
DUMP_TIMEOUT_SECONDS = 15 * 60
MYSQL_DATABASES = ("iot_platform", "iot_platform_archive")
TARGET_KEYS = ("mysql_container", "mysql_volume", "tdengine_container", "tdengine_volume", "tdengine_database")
DOCKER_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
TD_DATABASE = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,127}")
TD_USER = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,127}")
SHA256 = re.compile(r"[0-9a-f]{64}")
TD_CLIENT = Path(__file__).resolve().with_name("taosdump-d01")
TD_LIBRARY = "/usr/local/taos/driver/libtaos.so.3.2.3.0"
TD_LIBRARY_SHA256 = "4fd407d18c1c70576b0fb0e511043080f7cef863b73759d4cd95eee686fa1b91"


class BackupError(RuntimeError):
    """Stable failure code only; never include command output or a secret."""

    def __init__(self, code, diagnostic=None):
        super().__init__(code)
        self.diagnostic = diagnostic or {"category": "unclassified"}


# Credentials cross the Docker boundary only through standard input, never in a
# Docker argument, environment value, manifest, or error message.
MYSQL_DUMP_SCRIPT = """set -eu
umask 077
config=$(mktemp)
trap 'rm -f "$config"' EXIT HUP INT TERM
cat > "$config"
mysqldump --defaults-extra-file="$config" --single-transaction --routines --events --triggers --databases iot_platform iot_platform_archive
"""

# The pinned D01 client reads a complete bounded password line; the bundled
# 3.2.3.0 client silently truncates stdin passwords to 20 bytes. No CLI secret.
TAOS_DUMP_SCRIPT = """set -eu
umask 077
IFS= read -r td_user
IFS= read -r td_password
printf '%s\\n' "$td_password" | "$1/client" -u "$td_user" -p -D "$2" -o "$1/payload" -r /dev/null -T 1
unset td_password
"""

TD_CLEANUP_SCRIPT = """set -eu
case "$1" in /tmp/d01-backup-????????????????????????????????) ;; *) exit 64 ;; esac
rm -rf -- "$1"
"""


def _fail(code: str) -> BackupError:
    return BackupError(code)


def _json_file(path: Path, label: str, *, protected: bool) -> tuple[dict[str, Any], str]:
    try:
        details = path.lstat()
    except OSError as error:
        raise _fail(label + "_UNAVAILABLE") from error
    if path.is_symlink() or not stat.S_ISREG(details.st_mode):
        raise _fail(label + "_NOT_REGULAR_FILE")
    # Windows local Docker drills lack POSIX owner/mode semantics; production
    # Linux requires an owner-only freeze authority and credential file.
    if protected and os.name == "posix":
        if details.st_mode & 0o077:
            raise _fail(label + "_PERMISSIONS_TOO_OPEN")
        if details.st_uid != os.geteuid():
            raise _fail(label + "_OWNER_MISMATCH")
    if details.st_size > MAX_CONTROL_FILE_BYTES:
        raise _fail(label + "_TOO_LARGE")
    try:
        raw = path.read_bytes()
        value = json.loads(raw.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise _fail(label + "_INVALID_JSON") from error
    if not isinstance(value, dict):
        raise _fail(label + "_INVALID_JSON")
    return value, hashlib.sha256(raw).hexdigest()


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 512 or any(char in value for char in "\0\r\n"):
        raise _fail("INVALID_" + label)
    return value


def _backup_targets(value: Any, label: str) -> dict[str, str]:
    if not isinstance(value, dict) or set(value) != set(TARGET_KEYS):
        raise _fail(label + "_INVALID")
    targets = {key: _text(value.get(key), key.upper()) for key in TARGET_KEYS}
    for key in TARGET_KEYS[:-1]:
        if not DOCKER_NAME.fullmatch(targets[key]):
            raise _fail(label + "_INVALID")
    if not TD_DATABASE.fullmatch(targets["tdengine_database"]):
        raise _fail(label + "_INVALID")
    return targets


def _freeze_state(path: Path) -> dict[str, Any]:
    value, digest = _json_file(path, "FREEZE_STATE", protected=True)
    if value.get("format") != FREEZE_FORMAT or value.get("phase") != "FROZEN":
        raise _fail("FREEZE_STATE_NOT_FROZEN")
    retained = value.get("ingress_retained") is True
    loss_accepted = value.get("ingress_loss_accepted") is True
    if value.get("writers_fenced") is not True or retained == loss_accepted:
        raise _fail("FREEZE_STATE_WRITER_PROOF_MISSING")
    # Accepting missing ingress never substitutes for stopping database writers
    # or restoring this backup; preserve the authorization separately from retention.
    loss_reference = value.get("loss_acceptance_reference")
    if loss_accepted:
        if value.get("ingress_retained") is not False:
            raise _fail("FREEZE_STATE_INGRESS_DECISION_INVALID")
        _text(loss_reference, "LOSS_ACCEPTANCE_REFERENCE")
        if not loss_reference.strip():
            raise _fail("INVALID_LOSS_ACCEPTANCE_REFERENCE")
    freeze_id = _text(value.get("freeze_id"), "FREEZE_ID")
    try:
        if str(uuid.UUID(freeze_id)) != freeze_id.lower():
            raise ValueError()
    except (ValueError, AttributeError) as error:
        raise _fail("INVALID_FREEZE_ID") from error
    plan_hash = _text(value.get("prepared_plan_hash"), "PREPARED_PLAN_HASH")
    if not SHA256.fullmatch(plan_hash):
        raise _fail("INVALID_PREPARED_PLAN_HASH")
    if not isinstance(value.get("frozen_at"), int) or isinstance(value.get("frozen_at"), bool):
        raise _fail("INVALID_FROZEN_AT")
    return {
        "freeze_id": freeze_id,
        "freeze_state_sha256": digest,
        "prepared_plan_hash": plan_hash,
        "backend_service": _text(value.get("backend_service"), "BACKEND_SERVICE"),
        "web_service": _text(value.get("web_service"), "WEB_SERVICE"),
        "backup_targets": _backup_targets(value.get("backup_targets"), "BACKUP_TARGETS"),
        "ingress_retained": retained,
        "ingress_loss_accepted": loss_accepted,
        "loss_acceptance_reference": loss_reference if loss_accepted else None,
    }


def _credentials(path: Path) -> dict[str, str]:
    value, _ = _json_file(path, "CREDENTIALS", protected=True)
    mysql, tdengine = value.get("mysql"), value.get("tdengine")
    if not isinstance(mysql, dict) or not isinstance(tdengine, dict):
        raise _fail("CREDENTIALS_INVALID_SHAPE")
    td_user = _text(tdengine.get("user"), "TDENGINE_USER")
    if not TD_USER.fullmatch(td_user):
        raise _fail("INVALID_TDENGINE_USER")
    return {
        "mysql_user": _text(mysql.get("user"), "MYSQL_USER"),
        "mysql_password": _text(mysql.get("password"), "MYSQL_PASSWORD"),
        "tdengine_user": td_user,
        "tdengine_password": _text(tdengine.get("password"), "TDENGINE_PASSWORD"),
    }


def _run(command: Sequence[str], code: str, *, runner: Any = None, input_data: Optional[bytes] = None,
         stdout: Any = subprocess.PIPE, timeout_seconds: int = METADATA_TIMEOUT_SECONDS,
         timeout_code: Optional[str] = None) -> subprocess.CompletedProcess:
    try:
        result = (runner or subprocess.run)(list(command), input=input_data, stdout=stdout,
                                            stderr=subprocess.PIPE, check=False, timeout=timeout_seconds)
    except subprocess.TimeoutExpired as error:
        raise _fail(timeout_code or code + "_TIMEOUT") from error
    except (OSError, subprocess.SubprocessError) as error:
        raise _fail(code) from error
    if result.returncode != 0:
        # Only fixed categories survive. Redacting arbitrary external text is
        # insufficient: it may contain passwords, DSNs, SQL or telemetry values.
        diagnostic = {"category": "unclassified", "returncode": result.returncode}
        if code == "TDENGINE_DUMP_FAILED":
            raw = b" ".join(value for value in (result.stdout, result.stderr) if isinstance(value, bytes)).lower()
            for category, patterns in (
                ("password_input_invalid", (b"d01_td_password_input_invalid", b"invalid password")),
                ("authentication_failed", (b"authentication failure", b"authentication failed")),
                ("connection_failed", (b"unable to establish connection", b"connection refused")),
                ("permission_denied", (b"permission denied", b"insufficient privilege")),
                ("disk_full", (b"no space left on device",)),
                ("client_missing", (b"not found",)),
            ):
                if any(pattern in raw for pattern in patterns):
                    diagnostic["category"] = category
                    break
        raise BackupError(code, diagnostic)
    return result


def _output(command: Sequence[str], code: str, runner: Any) -> str:
    value = _run(command, code, runner=runner).stdout
    return (value.decode("utf-8", errors="replace") if isinstance(value, bytes) else str(value or "")).strip()


def _container(docker: str, name: str, volume: str, destination: str, runner: Any) -> dict[str, str]:
    if not isinstance(name, str) or not DOCKER_NAME.fullmatch(name):
        raise _fail("INVALID_CONTAINER")
    if not isinstance(volume, str) or not DOCKER_NAME.fullmatch(volume):
        raise _fail("INVALID_VOLUME")
    if _output([docker, "volume", "inspect", "--format", "{{.Name}}", volume],
               "DOCKER_VOLUME_IDENTITY_FAILED", runner) != volume:
        raise _fail("DOCKER_VOLUME_IDENTITY_FAILED")
    def inspect(template: str) -> str:
        return _output([docker, "inspect", "--format", template, name], "DOCKER_CONTAINER_IDENTITY_FAILED", runner)
    identity, actual_name, image, running, mounts_raw = (
        inspect("{{.Id}}"), inspect("{{.Name}}"), inspect("{{.Config.Image}}"),
        inspect("{{.State.Running}}"), inspect("{{json .Mounts}}"),
    )
    if not identity or actual_name != "/" + name or not image or running.lower() != "true":
        raise _fail("DOCKER_CONTAINER_IDENTITY_FAILED")
    try:
        mounts = json.loads(mounts_raw)
    except json.JSONDecodeError as error:
        raise _fail("DOCKER_CONTAINER_IDENTITY_FAILED") from error
    matches = [mount for mount in mounts if isinstance(mount, dict) and mount.get("Type") == "volume"
               and mount.get("Name") == volume and mount.get("Destination") == destination]
    if len(matches) != 1:
        raise _fail("DOCKER_VOLUME_NOT_MOUNTED_BY_TARGET")
    return {"container": name, "container_id": identity, "image": image,
            "volume": volume, "volume_destination": destination}


def _require_services_inactive(freeze: dict[str, str], systemctl_bin: str, runner: Any) -> None:
    for service in (freeze["backend_service"], freeze["web_service"]):
        state = _output([systemctl_bin, "show", service, "--property=ActiveState", "--value"],
                        "SERVICE_STATE_UNAVAILABLE", runner)
        if state != "inactive":
            raise _fail("WRITER_SERVICE_NOT_INACTIVE")


def _output_root(root: Path, minimum: int) -> None:
    if not isinstance(minimum, int) or isinstance(minimum, bool) or minimum < 0:
        raise _fail("INVALID_MIN_FREE_BYTES")
    try:
        details, free = root.lstat(), shutil.disk_usage(root).free
    except OSError as error:
        raise _fail("BACKUP_OUTPUT_UNAVAILABLE") from error
    if root.is_symlink() or not stat.S_ISDIR(details.st_mode):
        raise _fail("BACKUP_OUTPUT_NOT_DIRECTORY")
    if free < minimum:
        raise _fail("BACKUP_OUTPUT_INSUFFICIENT_SPACE")


def _private(path: Path, mode: int) -> None:
    try:
        os.chmod(path, mode)
    except OSError as error:
        raise _fail("BACKUP_PERMISSION_FAILED") from error


def _relative(root: Path, path: Path) -> str:
    relative = path.relative_to(root).as_posix()
    if not relative or any(part in {"", ".", ".."} for part in relative.split("/")):
        raise _fail("BACKUP_PATH_ESCAPE")
    return relative


def _files(root: Path) -> list[Path]:
    result: list[Path] = []
    for current, directories, names in os.walk(root, topdown=True, followlinks=False):
        current_path = Path(current)
        for child in [current_path / name for name in directories + names]:
            try:
                details = child.lstat()
            except OSError as error:
                raise _fail("BACKUP_PATH_UNREADABLE") from error
            if child.is_symlink():
                raise _fail("BACKUP_SYMLINK_REJECTED")
            if child.name in directories:
                if not stat.S_ISDIR(details.st_mode):
                    raise _fail("BACKUP_NON_REGULAR_ENTRY")
            elif not stat.S_ISREG(details.st_mode):
                raise _fail("BACKUP_NON_REGULAR_ENTRY")
        for name in names:
            child = current_path / name
            _relative(root, child)
            result.append(child)
    return sorted(result, key=lambda item: _relative(root, item))


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
    except OSError as error:
        raise _fail("BACKUP_PATH_UNREADABLE") from error
    return digest.hexdigest()


def _write_json(path: Path, value: dict[str, Any], code: str) -> None:
    try:
        with path.open("x", encoding="utf-8", newline="\n") as stream:
            _private(path, 0o600)
            json.dump(value, stream, ensure_ascii=False, sort_keys=True, indent=2)
            stream.write("\n")
    except OSError as error:
        raise _fail(code) from error


def _checksum_path(value: str) -> str:
    if not value or "\\" in value or value.startswith("/") or PurePosixPath(value).is_absolute():
        raise _fail("CHECKSUM_PATH_ESCAPE")
    if any(part in {"", ".", ".."} for part in value.split("/")):
        raise _fail("CHECKSUM_PATH_ESCAPE")
    return value


def _write_sums(root: Path) -> None:
    checksum = root / CHECKSUM_FILE
    entries = [(_relative(root, item), _sha256(item)) for item in _files(root) if item != checksum]
    if not entries:
        raise _fail("BACKUP_CHECKSUM_EMPTY")
    try:
        with checksum.open("x", encoding="ascii", newline="\n") as stream:
            _private(checksum, 0o600)
            for relative, digest in entries:
                stream.write(digest + "  " + relative + "\n")
    except OSError as error:
        raise _fail("BACKUP_CHECKSUM_WRITE_FAILED") from error


def _read_sums(path: Path) -> dict[str, str]:
    try:
        content = path.read_bytes().decode("ascii")
    except (OSError, UnicodeDecodeError) as error:
        raise _fail("CHECKSUM_FILE_INVALID") from error
    if not content or not content.endswith("\n"):
        raise _fail("CHECKSUM_FILE_INVALID")
    result: dict[str, str] = {}
    for line in content.splitlines():
        if not re.fullmatch(r"[0-9a-f]{64}  .+", line):
            raise _fail("CHECKSUM_FILE_INVALID")
        digest, relative = line.split("  ", 1)
        relative = _checksum_path(relative)
        if relative == CHECKSUM_FILE or relative in result:
            raise _fail("CHECKSUM_FILE_INVALID")
        result[relative] = digest
    if not result:
        raise _fail("CHECKSUM_FILE_INVALID")
    return result


def _mysql_input(credentials: dict[str, str]) -> bytes:
    def quote(value: str) -> str:
        return value.replace("\\", "\\\\").replace('"', '\\"')
    return ("[client]\nuser=\"" + quote(credentials["mysql_user"]) + "\"\npassword=\"" +
            quote(credentials["mysql_password"]) + "\"\n").encode("utf-8")


def _dump_mysql(output: Path, credentials: dict[str, str], docker: str, container: str, runner: Any) -> None:
    try:
        with output.open("xb") as stream:
            _private(output, 0o600)
            _run([docker, "exec", "-i", container, "sh", "-ceu", MYSQL_DUMP_SCRIPT], "MYSQL_DUMP_FAILED",
                 runner=runner, input_data=_mysql_input(credentials), stdout=stream,
                 timeout_seconds=DUMP_TIMEOUT_SECONDS, timeout_code="MYSQL_DUMP_TIMEOUT")
    except OSError as error:
        raise _fail("MYSQL_DUMP_FAILED") from error


def _dump_tdengine(output: Path, credentials: dict[str, str], docker: str, container: str, database: str, runner: Any) -> None:
    temporary = "/tmp/d01-backup-" + uuid.uuid4().hex
    stdin = (credentials["tdengine_user"] + "\n" + credentials["tdengine_password"] + "\n").encode("utf-8")
    operation_error: Optional[Exception] = None
    try:
        if not TD_CLIENT.is_file() or TD_CLIENT.is_symlink():
            raise _fail("TDENGINE_PINNED_CLIENT_MISSING")
        # This native client is validated against this exact runtime library.
        # A different server image requires a new isolated export/restore drill.
        actual_library = _output([docker, "exec", container, "sha256sum", TD_LIBRARY],
                                 "TDENGINE_CLIENT_ABI_CHECK_FAILED", runner)
        if actual_library != TD_LIBRARY_SHA256 + "  " + TD_LIBRARY:
            raise _fail("TDENGINE_CLIENT_ABI_MISMATCH")
        _run([docker, "exec", container, "sh", "-ceu", 'umask 077; mkdir -- "$1"; mkdir -- "$1/payload"',
              "d01-taos-prepare", temporary], "TDENGINE_TEMP_CREATE_FAILED", runner=runner)
        _run([docker, "cp", str(TD_CLIENT), container + ":" + temporary + "/client"],
             "TDENGINE_CLIENT_COPY_FAILED", runner=runner)
        _run([docker, "exec", container, "chmod", "700", temporary + "/client"],
             "TDENGINE_CLIENT_MODE_FAILED", runner=runner)
        _run([docker, "exec", "-i", container, "sh", "-ceu", TAOS_DUMP_SCRIPT,
              "d01-taosdump", temporary, database], "TDENGINE_DUMP_FAILED", runner=runner, input_data=stdin,
             timeout_seconds=DUMP_TIMEOUT_SECONDS, timeout_code="TDENGINE_DUMP_TIMEOUT")
        output.mkdir(mode=0o700)
        _private(output, 0o700)
        _run([docker, "cp", container + ":" + temporary + "/payload/.", str(output)], "TDENGINE_COPY_FAILED",
             runner=runner, timeout_seconds=DUMP_TIMEOUT_SECONDS, timeout_code="TDENGINE_COPY_TIMEOUT")
    except OSError:
        operation_error = _fail("TDENGINE_COPY_FAILED")
    except BackupError as error:
        operation_error = error
    except Exception:
        # Unexpected library exceptions are not a safe diagnostic contract.
        operation_error = _fail("TDENGINE_EXPORT_UNEXPECTED_FAILURE")
    finally:
        try:
            _run([docker, "exec", container, "sh", "-ceu", TD_CLEANUP_SCRIPT,
                  "d01-taos-cleanup", temporary], "TDENGINE_TEMP_CLEANUP_FAILED", runner=runner)
        except BackupError as cleanup_error:
            if operation_error is not None:
                operation_error = BackupError("TDENGINE_OPERATION_AND_TEMP_CLEANUP_FAILED",
                    {**getattr(operation_error, "diagnostic", {"category": "unclassified"}),
                     "cleanup_failed": True})
            else:
                operation_error = cleanup_error
    if operation_error is not None:
        _write_json(output.parent / "tdengine-diagnostic.json", {
            "format": "D01_TDENGINE_DIAGNOSTIC_V1", "stage": str(operation_error),
            **getattr(operation_error, "diagnostic", {"category": "unclassified"}),
        }, "TDENGINE_DIAGNOSTIC_WRITE_FAILED")
        raise operation_error


def _write_manifest(root: Path, freeze: dict[str, Any], mysql: dict[str, str], tdengine: dict[str, str], database: str) -> None:
    _write_json(root / MANIFEST_FILE, {
        "format": MANIFEST_FORMAT,
        "created_at": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "freeze_id": freeze["freeze_id"],
        "freeze_state_sha256": freeze["freeze_state_sha256"],
        "prepared_plan_sha256": freeze["prepared_plan_hash"],
        "ingress_retained": freeze["ingress_retained"],
        "ingress_loss_accepted": freeze["ingress_loss_accepted"],
        "loss_acceptance_reference": freeze["loss_acceptance_reference"],
        "sources": {"mysql": {**mysql, "databases": list(MYSQL_DATABASES)},
                    "tdengine": {**tdengine, "database": database}},
        "integrity": {"algorithm": "sha256", "checksum_file": CHECKSUM_FILE,
                      "checksum_excludes": [CHECKSUM_FILE]},
        "restore_policy": "No automatic restore is provided; restore only into explicit newly created isolated containers.",
    }, "BACKUP_METADATA_WRITE_FAILED")


def _payload(root: Path) -> None:
    mysql, tdengine = root / "mysql.sql", root / "tdengine"
    try:
        mysql_empty = not mysql.is_file() or mysql.is_symlink() or mysql.stat().st_size == 0
    except OSError as error:
        raise _fail("MYSQL_DUMP_EMPTY") from error
    if mysql_empty:
        raise _fail("MYSQL_DUMP_EMPTY")
    if not tdengine.is_dir() or tdengine.is_symlink() or not _files(tdengine):
        raise _fail("TDENGINE_DUMP_EMPTY")


def _mark_failed(partial: Optional[Path], freeze_id: str, stage: str, error: Exception) -> None:
    if partial is None or not partial.is_dir():
        return
    try:
        (partial / MANIFEST_FILE).unlink(missing_ok=True)
        _write_json(partial / FAILURE_FILE, {
            "format": "D01_BACKUP_FAILED_V1",
            "failed_at": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
            "freeze_id": freeze_id, "stage": stage,
            "error_code": str(error) if isinstance(error, BackupError) else "UNEXPECTED_BACKUP_FAILURE",
        }, "BACKUP_FAILURE_MARKER_FAILED")
    except (BackupError, OSError):
        pass


def _validate(path: Path, *, permit_partial: bool) -> dict[str, Any]:
    try:
        details = path.lstat()
    except OSError as error:
        raise _fail("BACKUP_DIRECTORY_UNAVAILABLE") from error
    if path.is_symlink() or not stat.S_ISDIR(details.st_mode):
        raise _fail("BACKUP_DIRECTORY_INVALID")
    if path.name.endswith(".partial") and not permit_partial:
        raise _fail("PARTIAL_BACKUP_REJECTED")
    if (path / FAILURE_FILE).exists():
        raise _fail("FAILED_BACKUP_REJECTED")
    if os.name == "posix":
        if details.st_uid != os.geteuid():
            raise _fail("BACKUP_DIRECTORY_OWNER_MISMATCH")
        if details.st_mode & 0o077:
            raise _fail("BACKUP_DIRECTORY_PERMISSIONS_TOO_OPEN")
    manifest, _ = _json_file(path / MANIFEST_FILE, "MANIFEST", protected=False)
    if manifest.get("format") != MANIFEST_FORMAT:
        raise _fail("MANIFEST_INVALID")
    freeze_id, freeze_hash = manifest.get("freeze_id"), manifest.get("freeze_state_sha256")
    try:
        if not isinstance(freeze_id, str) or str(uuid.UUID(freeze_id)) != freeze_id.lower():
            raise ValueError()
    except (ValueError, AttributeError) as error:
        raise _fail("MANIFEST_INVALID") from error
    if not isinstance(freeze_hash, str) or not SHA256.fullmatch(freeze_hash):
        raise _fail("MANIFEST_INVALID")
    sources = manifest.get("sources")
    if not isinstance(sources, dict) or set(sources) != {"mysql", "tdengine"}:
        raise _fail("MANIFEST_INVALID")
    mysql, tdengine = sources["mysql"], sources["tdengine"]
    if not isinstance(mysql, dict) or not isinstance(tdengine, dict):
        raise _fail("MANIFEST_INVALID")
    try:
        targets = _backup_targets({
            "mysql_container": mysql["container"],
            "mysql_volume": mysql["volume"],
            "tdengine_container": tdengine["container"],
            "tdengine_volume": tdengine["volume"],
            "tdengine_database": tdengine["database"],
        }, "MANIFEST_BACKUP_TARGETS")
    except KeyError as error:
        raise _fail("MANIFEST_INVALID") from error
    if mysql.get("databases") != list(MYSQL_DATABASES) or mysql.get("volume_destination") != "/var/lib/mysql" \
            or tdengine.get("volume_destination") != "/var/lib/taos" \
            or not isinstance(mysql.get("container_id"), str) or not mysql["container_id"] \
            or not isinstance(tdengine.get("container_id"), str) or not tdengine["container_id"] \
            or not isinstance(mysql.get("image"), str) or not mysql["image"] \
            or not isinstance(tdengine.get("image"), str) or not tdengine["image"]:
        raise _fail("MANIFEST_INVALID")
    checksum = path / CHECKSUM_FILE
    try:
        checksum_details = checksum.lstat()
    except OSError as error:
        raise _fail("CHECKSUM_FILE_MISSING") from error
    if checksum.is_symlink() or not stat.S_ISREG(checksum_details.st_mode):
        raise _fail("CHECKSUM_FILE_INVALID")
    expected = _read_sums(checksum)
    actual = {_relative(path, item): item for item in _files(path) if item != checksum}
    if set(expected) != set(actual):
        raise _fail("CHECKSUM_FILE_SET_MISMATCH")
    for relative, item in actual.items():
        if _sha256(item) != expected[relative]:
            raise _fail("CHECKSUM_MISMATCH")
    _payload(path)
    return {"backup_dir": str(path), "freeze_id": freeze_id, "freeze_state_sha256": freeze_hash,
            "backup_targets": targets, "sources": sources, "files_checked": len(actual),
            "bytes_checked": sum(item.stat().st_size for item in actual.values())}


def backup(
    *,
    freeze_state: Path | str,
    output_root: Path | str,
    credentials_file: Path | str,
    mysql_container: str,
    mysql_volume: str,
    tdengine_container: str,
    tdengine_volume: str,
    tdengine_database: str,
    systemctl_bin: str = "systemctl",
    docker_bin: str = "docker",
    min_free_bytes: int = DEFAULT_MIN_FREE_BYTES,
    runner: Any = None,
) -> Path:
    """Create one frozen backup and return its atomically published directory."""
    freeze = _freeze_state(Path(freeze_state))
    if not isinstance(tdengine_database, str) or not TD_DATABASE.fullmatch(tdengine_database):
        raise _fail("INVALID_TDENGINE_DATABASE")
    requested_targets = {
        "mysql_container": mysql_container,
        "mysql_volume": mysql_volume,
        "tdengine_container": tdengine_container,
        "tdengine_volume": tdengine_volume,
        "tdengine_database": tdengine_database,
    }
    if requested_targets != freeze["backup_targets"]:
        raise _fail("BACKUP_TARGET_MISMATCH")
    credentials = _credentials(Path(credentials_file))
    root = Path(output_root)
    _output_root(root, min_free_bytes)
    _require_services_inactive(freeze, systemctl_bin, runner)
    mysql = _container(docker_bin, mysql_container, mysql_volume, "/var/lib/mysql", runner)
    tdengine = _container(docker_bin, tdengine_container, tdengine_volume, "/var/lib/taos", runner)
    name = "d01-backup-" + freeze["freeze_id"] + "-" + uuid.uuid4().hex
    partial, final, stage = root / (name + ".partial"), root / name, "create_partial"
    try:
        partial.mkdir(mode=0o700)
        _private(partial, 0o700)
        stage = "dump_mysql"
        _dump_mysql(partial / "mysql.sql", credentials, docker_bin, mysql["container"], runner)
        stage = "dump_tdengine"
        _dump_tdengine(partial / "tdengine", credentials, docker_bin, tdengine["container"], tdengine_database, runner)
        stage = "verify_payload"
        _payload(partial)
        stage = "verify_services_after_dump"
        _require_services_inactive(freeze, systemctl_bin, runner)
        stage = "write_manifest"
        _write_manifest(partial, freeze, mysql, tdengine, tdengine_database)
        stage = "write_checksums"
        _write_sums(partial)
        stage = "validate_staged_backup"
        _validate(partial, permit_partial=True)
        stage = "publish_backup"
        os.replace(partial, final)
        return final
    except Exception as error:
        _mark_failed(partial, freeze["freeze_id"], stage, error)
        if isinstance(error, BackupError):
            raise
        raise _fail("UNEXPECTED_BACKUP_FAILURE") from error


def validate(backup_dir: Path | str) -> dict[str, Any]:
    """Validate a published backup without contacting Docker or databases."""
    return _validate(Path(backup_dir), permit_partial=False)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    create = actions.add_parser("create", help="create one frozen-window backup")
    for option in ("freeze-state", "output-root", "credentials-file", "mysql-container", "mysql-volume",
                   "tdengine-container", "tdengine-volume", "tdengine-database"):
        create.add_argument("--" + option, required=True)
    create.add_argument("--systemctl-bin", default="systemctl")
    create.add_argument("--docker-bin", default="docker")
    create.add_argument("--min-free-bytes", type=int, default=DEFAULT_MIN_FREE_BYTES)
    check = actions.add_parser("validate", help="validate a published backup only")
    check.add_argument("--backup-dir", required=True)
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        if arguments.action == "create":
            result = backup(
                freeze_state=arguments.freeze_state, output_root=arguments.output_root,
                credentials_file=arguments.credentials_file, mysql_container=arguments.mysql_container,
                mysql_volume=arguments.mysql_volume, tdengine_container=arguments.tdengine_container,
                tdengine_volume=arguments.tdengine_volume, tdengine_database=arguments.tdengine_database,
                systemctl_bin=arguments.systemctl_bin, docker_bin=arguments.docker_bin,
                min_free_bytes=arguments.min_free_bytes,
            )
            print("D01_BACKUP_CREATED=" + str(result))
        else:
            print("D01_BACKUP_VALID=" + json.dumps(validate(arguments.backup_dir), ensure_ascii=False, sort_keys=True))
        return 0
    except BackupError as error:
        print("D01_BACKUP_FAILED=" + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
