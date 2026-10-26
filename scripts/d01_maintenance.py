#!/usr/bin/python3
"""One installed D01 authorization; no caller-selected paths, programs or payloads."""
from __future__ import annotations

import fcntl
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import shutil
import socket
import stat
import struct
import subprocess
import sys
import tarfile
import time

INSTALL = Path("/opt/building-energy-d01-maintenance")
DATA = Path("/var/lib/building-energy-d01-maintenance")
SUDOERS = Path("/etc/sudoers.d/building-energy-d01-maintenance")
LAUNCHER = Path("/usr/local/sbin/building-energy-d01-maintenance")
TIMER = "building-energy-d01-authorization"
SAFE_ENV = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8", "HOME": "/root"}
ACTIONS = ("status", "prepare", "online-backup", "export-online", "record-online-restore",
           "freeze", "backup", "export-backup", "record-restore", "migrate", "cutover", "revoke")
CODE_FILES = ("d01_maintenance.py", "d01_release.py", "d01_backup.py", "daikin_test_deployment_config.py", "d01_credentials.py", "taosdump-d01")


def require(ok, code):
    if not ok:
        raise RuntimeError(code)


def failure_code(error):
    value = str(error)
    return value if re.fullmatch(r"[A-Z][A-Z0-9_]{3,160}", value) else "D01_FAILURE_" + type(error).__name__


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def protected(path, *, directory=False):
    """Check ancestors too: root ownership of a leaf alone does not protect it."""
    for part in (path, *path.parents):
        item = part.lstat()
        require(not stat.S_ISLNK(item.st_mode) and item.st_uid == 0
                and item.st_mode & 0o022 == 0, "D01_UNPROTECTED_INSTALLED_PATH")
    require(path.is_dir() if directory else path.is_file(), "D01_INSTALLED_PATH_TYPE")


def write_json(path, value):
    temporary = path.with_suffix(".new")
    with temporary.open("x", encoding="utf-8") as stream:
        os.chmod(temporary, 0o600)
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def call(args, timeout=60):
    result = subprocess.run(args, env=SAFE_ENV, stdin=subprocess.DEVNULL,
                            capture_output=True, timeout=timeout)
    require(result.returncode == 0, "D01_FIXED_COMMAND_FAILED")
    return result.stdout.decode().strip()


def load_config(*, verify_code=True):
    protected(INSTALL, directory=True)
    protected(INSTALL / "config.json")
    config = json.loads((INSTALL / "config.json").read_text())
    require(config["user"] == "user1" and config["format"] == "D01_TEMP_AUTH_V1", "D01_AUTH_CONFIG_INVALID")
    if verify_code:
        for name in CODE_FILES:
            protected(INSTALL / name)
            require(digest(INSTALL / name) == config["code_sha256"][name], "D01_INSTALLED_CODE_CHANGED")
    return config


def bounded_input():
    raw = sys.stdin.buffer.read(1024 * 1024 + 1)
    require(len(raw) <= 1024 * 1024, "D01_RECORD_TOO_LARGE")
    value = json.loads(raw)
    require(isinstance(value, dict), "D01_RECORD_OBJECT_REQUIRED")
    return value


def imported_modules():
    # -I excludes cwd/PYTHONPATH; only the checked, administrator-installed code
    # directory is introduced. No module is imported from the deployment tree.
    sys.dont_write_bytecode = True
    sys.path.insert(0, str(INSTALL))
    import d01_release
    import d01_backup
    return d01_release, d01_backup


def authorized_worker(descriptor, config):
    # A socketpair created by the root parent carries kernel peer credentials.
    # User-created pipes/sockets and direct CLI calls cannot forge this channel.
    with socket.socket(fileno=int(descriptor)) as channel:
        peer_pid, peer_uid, _ = struct.unpack("3i", channel.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
        require(peer_uid == 0 and peer_pid == os.getppid(), "D01_ROOT_PARENT_CHANNEL_REQUIRED")
        with channel.makefile("rb") as stream:
            raw = stream.read(4097)
        require(len(raw) <= 4096, "D01_WORKER_RECORD_TOO_LARGE")
    record = json.loads(raw)
    expected = {"stage-user": "AUTHORIZED", "publish-user": "AUTHORIZED", "switch-user": "STARTING"}
    require(record.get("action") in expected and record.get("phase") == expected[record["action"]]
            and record.get("expires_at") == config["expires_at"] and time.time() < config["expires_at"],
            "D01_WORKER_PHASE_OR_EXPIRY_INVALID")
    require(os.getuid() == pwd.getpwnam("user1").pw_uid and os.getuid() != 0,
            "D01_UNPRIVILEGED_WORKER_REQUIRED")
    release, _ = imported_modules()
    target = release.BASE / "releases" / config["release"]
    if record["action"] == "stage-user":
        release.stage_package(INSTALL / "release.tar.gz", target)
    elif record["action"] == "publish-user":
        destination = release.BASE / "incoming" / config["archive_name"]
        # The authenticated child still creates exclusively, never following
        # an existing user-controlled destination symlink.
        with destination.open("xb") as output, (INSTALL / "release.tar.gz").open("rb") as source:
            shutil.copyfileobj(source, output)
    else:
        require(str((release.BASE / "current").resolve()) == config["previous_release"], "D01_CURRENT_CHANGED")
        release.switch_current({"release": str(target)})


class Maintenance:
    def __init__(self, config):
        self.config = config
        self.release, self.backup_tool = imported_modules()
        self.private = DATA / "private"
        self.ledger = self.private / "operation.json"
        self.state = self.private / ("d01-" + config["release"] + ".json")
        self.schema_credentials = self.private / "schema.json"
        self.backup_credentials = self.private / "backup.json"
        self.operation = json.loads(self.ledger.read_text())
        self.original_schema = self.release.schema
        # Existing workflow gates stay in use. Only privileged execution and
        # filesystem mutation boundaries are bound to this installed operation.
        self.release.state_directory = lambda: self.private
        self.release.stage_package = self.stage
        self.release.switch_current = self.switch
        self.release.schema = self.schema

    def phase(self, expected):
        require(self.operation["phase"] in expected, "D01_AUTH_PHASE_MISMATCH")

    def record(self, phase, **details):
        self.operation.update(phase=phase, **details)
        self.operation.setdefault("events", []).append({"phase": phase, "at_utc_epoch": int(time.time())})
        write_json(self.ledger, self.operation)

    def user_action(self, action):
        account = pwd.getpwnam("user1")
        parent, child = socket.socketpair()
        with parent, child:
            parent.sendall(json.dumps({"action": action, "phase": self.operation["phase"],
                                      "expires_at": self.config["expires_at"]}).encode())
            parent.shutdown(socket.SHUT_WR)
            result = subprocess.run(["/usr/bin/python3", "-I", str(INSTALL / "d01_maintenance.py"),
                                     "--authorized-worker", str(child.fileno())],
                                    user=account.pw_uid, group=account.pw_gid, extra_groups=[],
                                    pass_fds=(child.fileno(),), env=SAFE_ENV, stdin=subprocess.DEVNULL,
                                    capture_output=True, timeout=180)
            require(result.returncode == 0, "D01_UNPRIVILEGED_STAGE_FAILED")

    def stage(self, archive, target):
        require(target == self.release.BASE / "releases" / self.config["release"], "D01_STAGE_TARGET_CHANGED")
        self.user_action("stage-user")

    def switch(self, state):
        require(state["release"] == str(self.release.BASE / "releases" / self.config["release"]),
                "D01_SWITCH_TARGET_CHANGED")
        self.user_action("switch-user")

    def schema(self, action, state, credentials):
        jar = INSTALL / self.release.JAR
        protected(jar)
        require(credentials == self.schema_credentials and digest(jar) == self.config["jar_sha256"]
                and state["jar_sha256"] == self.config["jar_sha256"]
                and state["web_sha256"] == self.config["web_sha256"], "D01_TRUSTED_JAR_REQUIRED")
        target = self.config.get("target_schema", "84")
        source = self.config.get("source_schema", str(int(target) - 1))
        actions = ("validate" + source, "validate" + target,
                   *("upgrade" + str(v) for v in range(int(source) + 1, int(target) + 1)))
        require(self.release.target_schema(state) == target
                and state.get("source_schema", "83") == source and action in actions,
                "D01_AUTH_SCHEMA_MISMATCH")
        # Never run Java as root using the mutable application release directory.
        self.original_schema(action, {**state, "release": str(INSTALL)}, credentials)

    def prepare(self):
        self.phase(("AUTHORIZED",))
        require(str((self.release.BASE / "current").resolve()) == self.config["previous_release"], "D01_CURRENT_CHANGED")
        require(digest(INSTALL / "release.tar.gz") == self.config["archive_sha256"], "D01_INSTALLED_PACKAGE_CHANGED")
        self.user_action("publish-user")
        self.release.prepare(self.config["release"], self.release.BASE / "incoming" / self.config["archive_name"],
                             self.config["archive_sha256"], self.schema_credentials)
        self.record("PREPARED")

    def targets(self):
        return self.release.protected_json(self.state)["backup_targets"]

    def online_backup(self):
        self.phase(("PREPARED",))
        self.release.check_unchanged(self.release.protected_json(self.state))
        target = self.private / "online-backup"
        tool, sources = self.backup_tool, self.targets()
        tool._output_root(self.private, tool.DEFAULT_MIN_FREE_BYTES)
        credentials = tool._credentials(self.backup_credentials)
        mysql = tool._container("/usr/bin/docker", sources["mysql_container"], sources["mysql_volume"],
                                "/var/lib/mysql", subprocess.run)
        td = tool._container("/usr/bin/docker", sources["tdengine_container"], sources["tdengine_volume"],
                             "/var/lib/taos", subprocess.run)
        target.mkdir(mode=0o700)
        tool._dump_mysql(target / "mysql.sql", credentials, "/usr/bin/docker", mysql["container"], subprocess.run)
        tool._dump_tdengine(target / "tdengine", credentials, "/usr/bin/docker", td["container"],
                            sources["tdengine_database"], subprocess.run)
        tool._payload(target)
        # A live snapshot demonstrates recoverability, not cross-engine freeze
        # consistency. Its distinct format cannot satisfy the final backup gate.
        write_json(target / "manifest.json", {"format": "D01_ONLINE_BACKUP_V1",
                   "created_at_utc_epoch": int(time.time()), "sources": {"mysql": mysql, "tdengine": td},
                   "consistency": "online-independent-engine-snapshots-not-frozen"})
        tool._write_sums(target)
        self.record("ONLINE_BACKUP")

    def backup_directory(self, online):
        return self.private / "online-backup" if online else Path(self.operation["backup_directory"])

    def export(self, online):
        self.phase(("ONLINE_BACKUP", "ONLINE_VERIFIED") if online else ("BACKED_UP", "RESTORE_VERIFIED"))
        source = self.backup_directory(online)
        require(source.is_relative_to(self.private), "D01_BACKUP_OUTSIDE_OPERATION")
        protected(source, directory=True)
        # No user-selected archive member or source path is accepted.
        files = [source, *source.rglob("*")]
        require(all(not p.is_symlink() and (p.is_file() or p.is_dir()) for p in files), "D01_EXPORT_SYMLINK")
        with tarfile.open(fileobj=sys.stdout.buffer, mode="w|gz") as archive:
            for item in files[1:]:
                archive.add(item, arcname=item.relative_to(source).as_posix(), recursive=False)

    def restore_record(self, online):
        self.phase(("ONLINE_BACKUP",) if online else ("BACKED_UP",))
        payload = bounded_input()
        require(set(payload) == {"report", "evidence"} and isinstance(payload["report"], dict)
                and isinstance(payload["evidence"], dict), "D01_RESTORE_RECORD_INVALID")
        folder = self.private / ("online-restore" if online else "final-restore")
        folder.mkdir(mode=0o700)
        evidence = folder / "evidence.json"
        write_json(evidence, payload["evidence"])
        report = {**payload["report"], "evidence_file": "evidence.json", "evidence_sha256": digest(evidence)}
        write_json(folder / "report.json", report)
        self.release.checked_restore(self.backup_directory(online), folder / "report.json")
        self.record("ONLINE_VERIFIED" if online else "RESTORE_VERIFIED")

    def freeze(self):
        self.phase(("ONLINE_VERIFIED",))
        check = bounded_input()
        require(set(check) == {"checked_at", "no_other_deployment", "external_writers_fenced", "reference"}
                and check["no_other_deployment"] is True and check["external_writers_fenced"] is True
                and type(check["checked_at"]) is int and 0 <= time.time() - check["checked_at"] <= 120
                and isinstance(check["reference"], str) and 0 < len(check["reference"]) <= 512,
                "D01_RECENT_WINDOW_CHECK_REQUIRED")
        self.release.checked_restore(self.private / "online-backup", self.private / "online-restore/report.json")
        self.record("FREEZING", window_check=check, stop_requested_at=int(time.time()))
        self.release.freeze(self.state, True, False, True, self.config["authorization_reference"])
        self.record("FROZEN", stopped_at=int(time.time()))

    def backup(self):
        self.phase(("FROZEN",))
        self.release.frozen_state(self.state)
        folder = self.backup_tool.backup(freeze_state=self.state, output_root=self.private,
                 credentials_file=self.backup_credentials, systemctl_bin="/usr/bin/systemctl",
                 docker_bin="/usr/bin/docker", **self.targets())
        self.record("BACKED_UP", backup_directory=str(folder))

    def migrate(self):
        self.phase(("RESTORE_VERIFIED",))
        self.record("MIGRATING")
        self.release.migrate(self.state, self.backup_directory(False), self.schema_credentials,
                             self.private / "final-restore/report.json")
        self.record("MIGRATED")

    def cutover(self):
        self.phase(("MIGRATED",))
        self.record("STARTING")
        self.release.cutover(self.state, self.backup_directory(False), self.schema_credentials,
                             self.private / "final-restore/report.json")
        self.record("AWAITING_BUSINESS_ACCEPTANCE", services_restored_at=int(time.time()))

    def status(self):
        print(json.dumps({"operation": self.operation, "expires_at": self.config["expires_at"],
              "services": {name: self.release.service_property(name, "ActiveState")
                           for name in (self.release.BACKEND, self.release.WEB, self.release.OTHER_SERVICE)}}, ensure_ascii=False))


def revoke(config):
    # Revocation removes only this grant and its copied credentials; recovery
    # evidence survives, and no application service is started or stopped.
    if SUDOERS.exists():
        protected(SUDOERS)
        require(digest(SUDOERS) == config["sudoers_sha256"], "D01_SUDOERS_CHANGED_ADMIN_REQUIRED")
        SUDOERS.unlink()
    for name in ("schema.json", "backup.json"):
        (DATA / "private" / name).unlink(missing_ok=True)
    call(["/usr/bin/systemctl", "stop", TIMER + ".timer"])
    call(["/usr/bin/systemctl", "disable", TIMER + ".timer"])
    write_json(DATA / "private/revoked.json", {"revoked_at": int(time.time())})
    print("D01_TEMP_AUTH_REVOKED")


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--authorized-worker":
        authorized_worker(sys.argv[2], load_config())
        return
    require(len(sys.argv) == 2, "D01_EXACT_ACTION_REQUIRED")
    action = sys.argv[1]
    require(action in ACTIONS, "D01_UNKNOWN_ACTION")
    # Cleanup must still work if an unused backup/migration module is damaged;
    # revoke imports none of them and checks its own fixed grant before removal.
    config = load_config(verify_code=action != "revoke")
    require(os.geteuid() == 0, "D01_ROOT_REQUIRED")
    os.environ.clear()
    os.environ.update(SAFE_ENV)
    os.umask(0o077)
    os.chdir(INSTALL)
    protected(DATA / "private", directory=True)
    with (DATA / "private/operation.lock").open("a") as lock:
        # Expiry must still revoke after an already-running bounded operation;
        # a nonblocking timer invocation could otherwise miss cleanup forever.
        fcntl.flock(lock, fcntl.LOCK_EX if action == "revoke" else fcntl.LOCK_EX | fcntl.LOCK_NB)
        if action == "revoke":
            revoke(config)
            return
        protected(DATA / "private/installation-complete.json")
        require(not (DATA / "private/revoked.json").exists() and time.time() < config["expires_at"], "D01_AUTH_EXPIRED_OR_REVOKED")
        maintenance = Maintenance(config)
        try:
            if action.startswith("export-"):
                maintenance.export(action == "export-online")
            elif action.startswith("record-"):
                maintenance.restore_record(action == "record-online-restore")
            else:
                getattr(maintenance, action.replace("-", "_"))()
        except Exception as error:
            if action not in ("status", "export-online", "export-backup"):
                details = {}
                if isinstance(error, maintenance.backup_tool.BackupError):
                    details["diagnostic"] = error.diagnostic
                maintenance.record("FAILED", failed_action=action, failure_code=failure_code(error), **details)
            raise


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # External database/OS errors may contain credentials or record values.
        print(failure_code(error), file=sys.stderr)
        sys.exit(1)
