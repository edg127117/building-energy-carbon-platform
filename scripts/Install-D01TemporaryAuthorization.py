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

INSTALL = Path("/opt/building-energy-d01-maintenance")
DATA = Path("/var/lib/building-energy-d01-maintenance")
BASE = Path("/home/user1/deployments/building-energy-carbon-test")
LAUNCHER = Path("/usr/local/sbin/building-energy-d01-maintenance")
SUDOERS = Path("/etc/sudoers.d/building-energy-d01-maintenance")
TIMER = "building-energy-d01-authorization"
UNITS = Path("/etc/systemd/system")
HISTORY = Path("/var/lib/building-energy-d01-maintenance-history")
FILES = ("d01_maintenance.py", "d01_release.py", "d01_backup.py", "daikin_test_deployment_config.py",
         "d01_credentials.py", "release.tar.gz")
ACTIONS = ("status", "prepare", "online-backup", "export-online", "record-online-restore",
           "freeze", "backup", "export-backup", "record-restore", "migrate", "cutover", "revoke")
ENV = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8", "HOME": "/root"}
CREATED = []
TIMER_ATTEMPTED = False


def require(ok, code):
    if not ok:
        raise RuntimeError(code)


def interrupted(signum, frame):
    raise KeyboardInterrupt("D01_INSTALL_INTERRUPTED")


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


def retire_failed_prepare():
    """Archive only an explicitly requested, revoked pre-freeze attempt."""
    require(not SUDOERS.exists() and not SUDOERS.is_symlink(), "D01_OLD_AUTH_STILL_PRESENT")
    private = DATA / "private"
    paths = (INSTALL, DATA, LAUNCHER, UNITS / (TIMER + ".service"), UNITS / (TIMER + ".timer"))
    for path in (*paths, private / "revoked.json", private / "operation.json", INSTALL / "config.json"):
        trusted_parent(path)
    operation = json.loads((private / "operation.json").read_text())
    config = json.loads((INSTALL / "config.json").read_text())
    require(operation.get("phase") == "FAILED" and operation.get("failed_action") == "prepare"
            and all(event.get("phase") == "FAILED" for event in operation.get("events", []))
            and not any(key in operation for key in ("stop_requested_at", "stopped_at", "services_restored_at")),
            "D01_OLD_OPERATION_NOT_FAILED_PREPARE")
    require(config.get("format") == "D01_TEMP_AUTH_V1" and config.get("user") == "user1"
            and str((BASE / "current").resolve()) == config.get("previous_release"), "D01_OLD_BASELINE_CHANGED")
    release = config.get("release", "")
    require(re.fullmatch(r"d01-[a-f0-9]{12}", release)
            and config.get("archive_name") == release + "-authorized.tar.gz", "D01_OLD_RELEASE_ID_INVALID")
    # This recovery is only for failure before publication. Do not remove or
    # reuse user-owned partial releases from a later prepare failure.
    for artifact in (BASE / "incoming" / config["archive_name"], BASE / "releases" / release):
        require(not artifact.exists() and not artifact.is_symlink(), "D01_OLD_PREPARE_ARTIFACTS_REQUIRE_REVIEW")
    for name in ("schema.json", "backup.json"):
        require(not (private / name).exists() and not (private / name).is_symlink(), "D01_OLD_CREDENTIALS_NOT_REVOKED")
    require(run("/usr/bin/systemctl", "show", TIMER + ".timer", "-p", "ActiveState", "--value") == "inactive"
            and run("/usr/bin/systemctl", "show", TIMER + ".timer", "-p", "UnitFileState", "--value") == "disabled",
            "D01_OLD_TIMER_NOT_REVOKED")
    require(run("/usr/bin/systemctl", "show", "building-energy-carbon-test-platform.service", "-p", "ActiveState", "--value") == "active",
            "D01_REINSTALL_REQUIRES_RUNNING_BASELINE")
    for name in FILES:
        if name.endswith(".py"):
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
    archive = HISTORY / ("failed-prepare-" + uuid.uuid4().hex)
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
    print("D01_REVOKED_PREPARE_ARCHIVED=" + str(archive))


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


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
        require(manifest.get("expectedMigration") == "84"
                and re.fullmatch("[a-f0-9]{40}", manifest.get("commit", ""))
                and hashlib.sha256(jar).hexdigest() == manifest.get("backendJarSha256")
                and hashlib.sha256(index).hexdigest() == manifest.get("webIndexSha256"), "D01_PACKAGE_MANIFEST_INVALID")
        return manifest, jar


def credentials(jar, source):
    print("复用服务器既有受保护管理员配置，执行只读登录及权限核查；无需输入数据库密码。")
    # The module is compiled from the already hashed bundle bytes, never
    # imported from the administrator's cwd or a user-writable upload path.
    module = types.ModuleType("d01_credentials")
    exec(compile(source, "verified-d01-credentials", "exec"), module.__dict__)
    return module.discover(jar)


def install(bundle, expected_sha, *, replace_revoked_prepare=False):
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
    if replace_revoked_prepare and (INSTALL.exists() or INSTALL.is_symlink()):
        retire_failed_prepare()
    for destination in (INSTALL, DATA, LAUNCHER, SUDOERS,
                        UNITS / (TIMER + ".service"), UNITS / (TIMER + ".timer")):
        trusted_parent(destination.parent)
        require(not destination.exists() and not destination.is_symlink(), "D01_AUTH_ALREADY_EXISTS")
    require(pwd.getpwnam("user1").pw_uid != 0, "D01_RUNTIME_USER_INVALID")
    require(run("/usr/bin/systemctl", "show", "building-energy-carbon-test-platform.service", "-p", "User", "--value") == "user1",
            "D01_EXPECTED_SERVICE_USER_REQUIRED")
    previous = str((BASE / "current").resolve())
    require(Path(previous).is_relative_to(BASE / "releases"), "D01_CURRENT_OUTSIDE_TEST_RELEASES")
    schema, backup, credential_report = credentials(jar, files["d01_credentials.py"])
    expires = int(time.time()) + 24 * 3600
    release = "d01-" + manifest["commit"][:12]
    sudoers = "# One installed D01 operation; no wildcard arguments or SETENV.\n"
    sudoers += "Defaults!" + str(LAUNCHER) + " env_reset, !setenv, secure_path=/usr/sbin:/usr/bin:/sbin:/bin\n"
    sudoers += "user1 ALL=(root) NOPASSWD: " + ", ".join(str(LAUNCHER) + " " + action for action in ACTIONS) + "\n"
    launcher = "#!/bin/sh\nexec /usr/bin/env -i PATH=/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8 /usr/bin/python3 -I " + str(INSTALL / "d01_maintenance.py") + ' "$@"\n'
    config = {"format": "D01_TEMP_AUTH_V1", "user": "user1", "expires_at": expires,
              "release": release, "archive_name": release + "-authorized.tar.gz", "previous_release": previous,
              "archive_sha256": hashlib.sha256(files["release.tar.gz"]).hexdigest(),
              "jar_sha256": manifest["backendJarSha256"], "web_sha256": manifest["webIndexSha256"],
              "commit": manifest["commit"],
              "code_sha256": {name: hashlib.sha256(files[name]).hexdigest() for name in FILES if name.endswith(".py")},
              "sudoers_sha256": hashlib.sha256(sudoers.encode()).hexdigest(),
              "authorization_reference": "D01 test maintenance approved; HTTP/MQTT unrecoverable gaps accepted"}
    mkdir(INSTALL, 0o755)
    mkdir(DATA, 0o755)
    private = DATA / "private"
    mkdir(private, 0o700)
    for name, content in files.items():
        create(INSTALL / name, content, 0o644)
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
    print("Actual backup and isolated restore verification are still required before freeze.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--replace-revoked-prepare", action="store_true",
                        help="archive only a revoked attempt that failed before freeze; preserve all evidence")
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGHUP, interrupted)
    try:
        install(args.bundle, args.sha256, replace_revoked_prepare=args.replace_revoked_prepare)
    except BaseException as error:
        try:
            cleanup_failed_install()
        except Exception:
            print("D01_AUTH_INSTALL_CLEANUP_INCOMPLETE", file=sys.stderr)
        text = str(error)
        print(text if text.startswith("D01_") and text.replace("_", "").isalnum()
              else "D01_AUTH_INSTALL_FAILED_" + type(error).__name__, file=sys.stderr)
        sys.exit(1)
