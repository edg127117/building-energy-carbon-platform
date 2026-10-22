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

INSTALL = Path("/opt/building-energy-d01-maintenance")
DATA = Path("/var/lib/building-energy-d01-maintenance")
BASE = Path("/home/user1/deployments/building-energy-carbon-test")
LAUNCHER = Path("/usr/local/sbin/building-energy-d01-maintenance")
SUDOERS = Path("/etc/sudoers.d/building-energy-d01-maintenance")
TIMER = "building-energy-d01-authorization"
UNITS = Path("/etc/systemd/system")
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


def install(bundle, expected_sha):
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
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGHUP, interrupted)
    try:
        install(args.bundle, args.sha256)
    except BaseException as error:
        try:
            cleanup_failed_install()
        except Exception:
            print("D01_AUTH_INSTALL_CLEANUP_INCOMPLETE", file=sys.stderr)
        text = str(error)
        print(text if text.startswith("D01_") and text.replace("_", "").isalnum()
              else "D01_AUTH_INSTALL_FAILED_" + type(error).__name__, file=sys.stderr)
        sys.exit(1)
