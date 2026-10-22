"""D01 维护阶段和失败边界，所有服务、HTTP、数据库依赖均离线替换。"""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("d01_release", SCRIPTS / "d01_release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


@unittest.skipUnless(sys.platform == "linux", "Linux 文件权限和符号链接流程在隔离 Linux 容器验证")
class D01ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        for directory in ("incoming", "backups", "shared", "releases/previous", "package/web"):
            (self.base / directory).mkdir(parents=True)
        (self.base / "releases/previous" / release.JAR).write_bytes(b"existing backend")
        self.current = self.base / "current"
        self.current.symlink_to(self.base / "releases/previous", target_is_directory=True)
        self.other = self.base / "other"
        self.other.symlink_to(self.base / "other-release", target_is_directory=True)
        self.config = self.base / "control.env"
        self.original = b"DAIKIN_CONTROL_ENABLED=false\n"
        self.config.write_bytes(self.original)
        (self.base / "shared/nginx-user.conf").write_text(
            "listen 18080;\nroot " + str(self.base / "current/web") + ";\nproxy_pass http://127.0.0.1:18081;\n")
        self.controls = {"AUDIT_ENVIRONMENT_MODE": "TEST", "DAIKIN_CONTROL_ENABLED": "false",
                         "MYSQL_FLYWAY_ENABLED": "false", "TDENGINE_INITIALIZATION_ENABLED": "false",
                         "MYSQL_CONTAINER_NAME": "isolated-mysql", "MYSQL_VOLUME_NAME": "isolated-mysql-volume",
                         "TDENGINE_CONTAINER_NAME": "isolated-td", "TDENGINE_VOLUME_NAME": "isolated-td-volume",
                         "TDENGINE_DATABASE": "iot_telemetry",
                         "SERVER_PORT": "18081",
                         "MYSQL_HOST": "127.0.0.1", "MYSQL_PORT": "13316"}
        self.states = {release.BACKEND: "active", release.WEB: "active"}
        self.events = []
        stack = contextlib.ExitStack()
        self.addCleanup(stack.close)
        for key, value in (("BASE", self.base), ("OTHER", self.other)):
            stack.enter_context(patch.object(release, key, value))
        stack.enter_context(patch.object(release, "runtime", side_effect=lambda: self.controls.copy()))
        stack.enter_context(patch.object(release, "fingerprints", side_effect=lambda: {str(self.config): release.sha(self.config)}))
        stack.enter_context(patch.object(release, "service_property", side_effect=self.property))
        stack.enter_context(patch.object(release, "command", side_effect=self.command))
        self.schema = stack.enter_context(patch.object(release, "schema"))
        self.backup = stack.enter_context(patch.object(release, "validate_backup", side_effect=self.manifest))
        self.restore = stack.enter_context(patch.object(release, "checked_restore"))
        stack.enter_context(patch.object(release.urllib.request, "urlopen", side_effect=self.http))

    def property(self, service, key):
        return self.states[service] if key == "ActiveState" else "100"

    def command(self, *args):
        self.events.append(args)
        if args[0] == "systemctl" and args[1] in ("start", "stop"):
            for service in args[2:]:
                self.states[service] = "active" if args[1] == "start" else "inactive"
        return ""

    def manifest(self, directory):
        state = release.protected_json(self.state)
        return {"freeze_id": state["freeze_id"], "freeze_state_sha256": release.sha(self.state),
                "backup_targets": state["backup_targets"]}

    def http(self, url, timeout):
        response = io.BytesIO(b"candidate")
        response.status = 200
        return response

    def prepare(self, migration="84"):
        package = self.base / "package"
        with zipfile.ZipFile(package / release.JAR, "w") as jar:
            jar.writestr(release.V84, "V84 SQL")
            jar.writestr("BOOT-INF/classes/com/platform/tools/D01SchemaMigration.class", b"fixture")
        (package / "web/index.html").write_bytes(b"candidate")
        (package / "MANIFEST.txt").write_text("commit=" + "a" * 40 + "\nexpectedMigration=" + migration +
                                             "\nbackendJarSha256=" + release.sha(package / release.JAR) +
                                             "\nwebIndexSha256=" + release.sha(package / "web/index.html") + "\n")
        archive = self.base / "incoming/candidate.tar.gz"
        with tarfile.open(archive, "w:gz") as stream:
            for path in package.iterdir():
                stream.add(path, arcname=path.name)
        self.state = release.prepare("d01-candidate", archive, release.sha(archive), self.base / "credentials.json")
        return self.state

    def freeze(self):
        self.prepare()
        self.schema.reset_mock()
        release.freeze(self.state, True, True)

    def test_prepare_preserves_running_services_and_exact_control_file(self):
        self.prepare()
        self.assertEqual(self.events, [])
        self.assertEqual(self.current.resolve().name, "previous")
        self.assertEqual(self.config.read_bytes(), self.original)

    def test_wrong_schema_package_never_switches_or_stops_services(self):
        with self.assertRaisesRegex(RuntimeError, "D01_V84_PACKAGE_REQUIRED"):
            self.prepare("83")
        self.assertEqual(self.events, [])

    def test_freeze_needs_external_writers_and_retry_confirmation(self):
        self.prepare()
        with self.assertRaisesRegex(RuntimeError, "EXTERNAL_WRITERS"):
            release.freeze(self.state, True, False)
        self.assertEqual(self.events, [])

    def test_configuration_drift_blocks_before_stop(self):
        self.prepare()
        self.config.write_bytes(b"changed\n")
        with self.assertRaisesRegex(RuntimeError, "CONFIGURATION_CHANGED"):
            release.freeze(self.state, True, True)
        self.assertEqual(self.events, [])

    def test_accepted_ingress_loss_is_recorded_without_claiming_retention(self):
        self.prepare()
        release.freeze(self.state, True, False, True, "approved test maintenance")
        state = release.protected_json(self.state)
        self.assertFalse(state["ingress_retained"])
        self.assertTrue(state["ingress_loss_accepted"])
        self.assertEqual(state["loss_acceptance_reference"], "approved test maintenance")
        self.assertEqual(self.events, [("systemctl", "stop", release.WEB, release.BACKEND)])
        self.schema.reset_mock()
        self.restore.side_effect = RuntimeError("D01_RESTORE_EVIDENCE_REQUIRED")
        for action in (release.migrate, release.cutover):
            with self.assertRaisesRegex(RuntimeError, "RESTORE_EVIDENCE_REQUIRED"):
                action(self.state, self.base, self.base)
        self.schema.assert_not_called()

    def test_loss_acceptance_cannot_replace_writer_fence_or_conflict_with_retention(self):
        self.prepare()
        for fenced, retained in ((False, False), (True, True)):
            with self.subTest(fenced=fenced, retained=retained):
                with self.assertRaisesRegex(RuntimeError, "EXTERNAL_WRITERS"):
                    release.freeze(self.state, fenced, retained, True, "approved test maintenance")
        self.assertEqual(self.events, [])

    def test_loss_acceptance_requires_nonempty_bounded_reference_before_stop(self):
        self.prepare()
        for reference in (None, "", "  ", "a" * 513, "a" + " " * 512, "bad\nreference"):
            with self.subTest(reference=reference):
                with self.assertRaisesRegex(RuntimeError, "LOSS_ACCEPTANCE_REFERENCE_REQUIRED"):
                    release.freeze(self.state, True, False, True, reference)
        self.assertEqual(self.events, [])

    def test_freeze_only_stops_test_web_and_backend(self):
        self.freeze()
        self.assertEqual(self.events, [("systemctl", "stop", release.WEB, release.BACKEND)])
        self.assertEqual(release.protected_json(self.state)["phase"], "FROZEN")

    def test_running_writer_refuses_migration(self):
        self.freeze()
        self.states[release.BACKEND] = "active"
        with self.assertRaisesRegex(RuntimeError, "NOT_STOPPED"):
            release.migrate(self.state, self.base, self.base)
        self.schema.assert_not_called()

    def test_foreign_or_modified_freeze_backup_is_refused(self):
        self.freeze()
        self.backup.side_effect = None
        self.backup.return_value = {"freeze_id": "wrong", "freeze_state_sha256": release.sha(self.state)}
        with self.assertRaisesRegex(RuntimeError, "BACKUP_FREEZE_MISMATCH"):
            release.migrate(self.state, self.base, self.base)
        self.schema.assert_not_called()

    def test_migration_never_starts_application_or_changes_frozen_identity(self):
        self.freeze()
        before = self.state.read_bytes()
        release.migrate(self.state, self.base, self.base)
        self.schema.assert_called_once()
        self.assertEqual(self.state.read_bytes(), before)
        self.assertEqual(len(self.events), 1)

    def test_backup_for_other_source_is_refused_even_with_matching_freeze(self):
        self.freeze()
        manifest = self.manifest(self.base)
        manifest["backup_targets"] = {**manifest["backup_targets"], "mysql_volume": "other-volume"}
        self.backup.side_effect = None
        self.backup.return_value = manifest
        with self.assertRaisesRegex(RuntimeError, "BACKUP_TARGET_MISMATCH"):
            release.cutover(self.state, self.base, self.base)
        self.schema.assert_not_called()

    def test_partial_migration_failure_remains_frozen(self):
        self.freeze()
        self.schema.side_effect = RuntimeError("partial DDL")
        with self.assertRaisesRegex(RuntimeError, "partial DDL"):
            release.migrate(self.state, self.base, self.base)
        self.assertEqual(release.protected_json(self.state)["phase"], "FROZEN")
        self.assertEqual(self.current.resolve().name, "previous")

    def test_restore_not_verified_prevents_migration_and_startup(self):
        self.freeze()
        self.restore.side_effect = RuntimeError("D01_RESTORE_EVIDENCE_REQUIRED")
        for action in (release.migrate, release.cutover):
            with self.assertRaisesRegex(RuntimeError, "RESTORE_EVIDENCE_REQUIRED"):
                action(self.state, self.base, self.base)
        self.schema.assert_not_called()
        self.assertEqual(len(self.events), 1)

    def test_same_master_pid_but_nginx_reload_blocks_freeze(self):
        self.prepare()
        original = self.property
        with patch.object(release, "service_property", side_effect=lambda service, key:
                          "changed" if key == "ExecReload" else original(service, key)):
            with self.assertRaisesRegex(RuntimeError, "OTHER_SITE_RELOADED"):
                release.freeze(self.state, True, True)
        self.assertEqual(self.events, [])

    def test_cutover_explicitly_starts_web_and_requires_business_acceptance(self):
        self.freeze()
        release.cutover(self.state, self.base, self.base)
        self.assertEqual(self.current.resolve().name, "d01-candidate")
        self.assertEqual(release.protected_json(self.state)["phase"], "AWAITING_BUSINESS_ACCEPTANCE")
        self.assertIn(("systemctl", "start", release.WEB), self.events)
        self.assertEqual(self.config.read_bytes(), self.original)

    def test_start_failure_keeps_candidate_stopped_without_legacy_restart(self):
        self.freeze()
        original = self.command
        def failing(*args):
            if args[:3] == ("systemctl", "start", release.BACKEND):
                raise RuntimeError("start failed")
            return original(*args)
        with patch.object(release, "command", side_effect=failing), patch.object(release.subprocess, "run") as stopped:
            stopped.return_value.returncode = 0
            with self.assertRaisesRegex(RuntimeError, "NO_LEGACY_RESTART"):
                release.cutover(self.state, self.base, self.base)
        state = release.protected_json(self.state)
        self.assertTrue(state["candidate_started"])
        self.assertEqual(state["phase"], "FAILED_STOPPED")
        self.assertEqual(self.current.resolve().name, "d01-candidate")

    def test_candidate_started_state_cannot_be_reused_as_frozen(self):
        self.freeze()
        release.cutover(self.state, self.base, self.base)
        self.states = {release.BACKEND: "inactive", release.WEB: "inactive"}
        with self.assertRaisesRegex(RuntimeError, "FROZEN_STATE_REQUIRED"):
            release.cutover(self.state, self.base, self.base)

    def test_web_inactive_rejects_http_success_and_stops_candidate(self):
        self.freeze()
        original = self.command
        def no_web(*args):
            if args == ("systemctl", "start", release.WEB):
                return ""
            return original(*args)
        with patch.object(release, "command", side_effect=no_web), \
                patch.object(release.time, "monotonic", side_effect=[0, 999]), \
                patch.object(release.subprocess, "run") as stopped:
            stopped.return_value.returncode = 0
            with self.assertRaisesRegex(RuntimeError, "NO_LEGACY_RESTART"):
                release.cutover(self.state, self.base, self.base)
        self.assertEqual(release.protected_json(self.state)["phase"], "FAILED_STOPPED")


class RestoreReportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.backup = self.root / "backup"
        self.backup.mkdir()
        (self.backup / "manifest.json").write_text(json.dumps({"sources": {
            "mysql": {"container_id": "old-mysql"}, "tdengine": {"container_id": "old-td"}}}))
        self.evidence = self.root / "executed.json"
        self.evidence.write_text("actual isolated restore evidence fixture")
        self.report = self.root / "restore-report.json"
        self.data = {"format": "D01_RESTORE_VERIFIED_V1",
                     "backup_manifest_sha256": release.sha(self.backup / "manifest.json"),
                     "mysql_verified": True, "tdengine_verified": True, "source_preserved": True,
                     "restored_container_ids": ["new-mysql", "new-td"],
                     "evidence_file": self.evidence.name, "evidence_sha256": release.sha(self.evidence)}

    def write(self):
        self.report.write_text(json.dumps(self.data))
        self.report.chmod(0o600)

    def test_matching_backup_and_executed_evidence_is_accepted(self):
        self.write()
        release.checked_restore(self.backup, self.report)

    def test_another_backup_report_is_rejected(self):
        self.data["backup_manifest_sha256"] = "f" * 64
        self.write()
        with self.assertRaisesRegex(RuntimeError, "EVIDENCE_MISMATCH"):
            release.checked_restore(self.backup, self.report)

    def test_source_container_cannot_be_the_restored_target(self):
        self.data["restored_container_ids"] = ["old-mysql", "new-td"]
        self.write()
        with self.assertRaisesRegex(RuntimeError, "NEW_CONTAINERS"):
            release.checked_restore(self.backup, self.report)

    def test_tampered_execution_evidence_is_rejected(self):
        self.write()
        self.evidence.write_text("tampered")
        with self.assertRaisesRegex(RuntimeError, "EVIDENCE_CHANGED"):
            release.checked_restore(self.backup, self.report)


if __name__ == "__main__":
    unittest.main()
