"""D01 维护阶段和失败边界，所有服务、HTTP、数据库依赖均离线替换。"""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import subprocess
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
        self.sources = {engine: {"container": self.controls[engine.upper()+"_CONTAINER_NAME"],
                                  "volume": self.controls[engine.upper()+"_VOLUME_NAME"],
                                  "container_id": engine + "-original-id", "image": engine + ":fixture",
                                  "volume_destination": destination}
                        for engine, destination in (("mysql", "/var/lib/mysql"), ("tdengine", "/var/lib/taos"))}
        self.current_sources = {engine: dict(source) for engine, source in self.sources.items()}
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
        self.inspect_source = stack.enter_context(patch.object(release, "inspect_backup_container",
            side_effect=lambda docker, name, volume, destination, runner:
                next(dict(source) for source in self.current_sources.values() if source["container"] == name)))
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
                "backup_targets": state["backup_targets"], "sources": self.sources}

    def stopped(self, *args, **kwargs):
        self.assertEqual(kwargs["timeout"], 60)
        self.states = {release.BACKEND: "inactive", release.WEB: "inactive"}
        return subprocess.CompletedProcess(args, 0)

    def http(self, url, timeout):
        response = io.BytesIO(b"candidate")
        response.status = 200
        return response

    def prepare(self, migration="84", source=None, mode=None, extra_migration=None):
        package = self.base / "package"
        with zipfile.ZipFile(package / release.JAR, "w") as jar:
            jar.writestr(release.V84, "V84 SQL")
            for version, path in ((85, release.V85), (86, release.V86), (87, release.V87), (88, release.V88), (93, release.V93)):
                if int(migration) >= version:
                    jar.writestr(path, "fixture SQL")
            jar.writestr("BOOT-INF/classes/com/platform/tools/D01SchemaMigration.class", b"fixture")
            if extra_migration is not None:
                jar.writestr("BOOT-INF/classes/db/migration/mysql/V" + str(extra_migration) + "__unexpected.sql", "fixture")
        (package / "web/index.html").write_bytes(b"candidate")
        (package / "MANIFEST.txt").write_text("commit=" + "a" * 40 + "\nexpectedMigration=" + migration + ("\nsourceMigration=" + source if source else "") + ("\nreleaseMode=" + mode if mode else "") +
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
        with self.assertRaisesRegex(RuntimeError, "D01_SUPPORTED_PACKAGE_REQUIRED"):
            self.prepare("83")
        self.assertEqual(self.events, [])

    def test_v85_uses_adjacent_baseline_then_exact_target_without_changing_controls(self):
        self.prepare("85")
        self.assertEqual(self.schema.call_args.args[0], "validate84")
        self.assertEqual(self.events, [])
        state = release.protected_json(self.state)
        self.assertEqual((state["source_schema"], state["target_schema"]), ("84", "85"))
        release.freeze(self.state, True, True)
        frozen_hash = release.sha(self.state)
        release.migrate(self.state, self.base, self.base)
        self.assertEqual(self.schema.call_args.args[0], "upgrade85")
        self.assertEqual(release.sha(self.state), frozen_hash)
        release.cutover(self.state, self.base, self.base)
        self.assertEqual(self.schema.call_args.args[0], "validate85")
        self.assertEqual(release.protected_json(self.state)["phase"], "AWAITING_BUSINESS_ACCEPTANCE")
        self.assertEqual(self.config.read_bytes(), self.original)

    def test_v88_uses_only_v87_baseline_and_one_bound_upgrade(self):
        self.prepare("88", "87")
        self.assertEqual(self.schema.call_args.args[0], "validate87")
        self.assertEqual(self.events, [])
        state = release.protected_json(self.state)
        self.assertEqual((state["source_schema"], state["target_schema"]), ("87", "88"))
        release.freeze(self.state, True, True)
        self.schema.reset_mock()
        release.migrate(self.state, self.base, self.base)
        self.assertEqual([call.args[0] for call in self.schema.call_args_list], ["upgrade88"])
        release.cutover(self.state, self.base, self.base)
        self.assertEqual(self.schema.call_args.args[0], "validate88")
        self.assertEqual(self.config.read_bytes(), self.original)

    def test_v88_application_only_validates_three_times_and_preserves_freeze_and_controls(self):
        self.prepare("88", "88", "APPLICATION_ONLY")
        self.assertEqual([call.args[0] for call in self.schema.call_args_list], ["validate88"])
        self.assertEqual(self.events, [])
        release.freeze(self.state, True, True)
        frozen = self.state.read_bytes()
        release.migrate(self.state, self.base, self.base)
        self.assertEqual(self.state.read_bytes(), frozen)
        release.cutover(self.state, self.base, self.base)
        self.assertEqual([call.args[0] for call in self.schema.call_args_list], ["validate88"] * 3)
        self.assertEqual(release.protected_json(self.state)["phase"], "AWAITING_BUSINESS_ACCEPTANCE")
        self.assertEqual(self.config.read_bytes(), self.original)
        self.assertEqual(self.other.readlink(), self.base / "other-release")

    def test_v93_from_v88_runs_only_its_single_migration_and_preserves_controls(self):
        self.prepare("93", "88")
        self.assertEqual(self.schema.call_args.args[0], "validate88")
        self.assertEqual(self.events, [])
        release.freeze(self.state, True, True)
        frozen = self.state.read_bytes()
        self.schema.reset_mock()
        release.migrate(self.state, self.base, self.base)
        self.assertEqual([call.args[0] for call in self.schema.call_args_list], ["upgrade93"])
        self.assertEqual(self.state.read_bytes(), frozen)
        release.cutover(self.state, self.base, self.base)
        self.assertEqual(self.schema.call_args.args[0], "validate93")
        self.assertEqual(self.config.read_bytes(), self.original)

    def test_v93_refuses_an_unapproved_source_schema(self):
        with self.assertRaisesRegex(RuntimeError, "D01_STATE_SCHEMA_MISMATCH"):
            self.prepare("93", "87")
        self.assertEqual(self.events, [])

    def test_v93_refuses_other_pending_migrations_before_stopping_services(self):
        with self.assertRaisesRegex(RuntimeError, "D01_V93_SINGLE_MIGRATION_REQUIRED"):
            self.prepare("93", "88", extra_migration=89)
        self.assertEqual(self.events, [])

    def test_application_only_failed_baseline_cannot_stop_or_switch(self):
        self.schema.side_effect = RuntimeError("D01_SCHEMA_CHECK_FAILED")
        with self.assertRaisesRegex(RuntimeError, "SCHEMA_CHECK_FAILED"):
            self.prepare("88", "88", "APPLICATION_ONLY")
        self.assertEqual(self.events, [])
        self.assertEqual(self.current.resolve().name, "previous")
        self.assertFalse(list((self.base / "backups").iterdir()))

    def test_application_only_backup_restore_source_and_schema_gates_still_block_switch(self):
        self.prepare("88", "88", "APPLICATION_ONLY")
        release.freeze(self.state, True, True)
        self.schema.reset_mock()
        for error, dependency in (("D01_BACKUP_FREEZE_MISMATCH", self.backup),
                                  ("D01_RESTORE_EVIDENCE_REQUIRED", self.restore)):
            dependency.side_effect = RuntimeError(error)
            for action in (release.migrate, release.cutover):
                with self.assertRaisesRegex(RuntimeError, error):
                    action(self.state, self.base, self.base)
            dependency.side_effect = self.manifest if dependency is self.backup else None
        self.current_sources["mysql"]["container_id"] = "replacement"
        for action in (release.migrate, release.cutover):
            with self.assertRaisesRegex(RuntimeError, "BACKUP_SOURCE_CHANGED"):
                action(self.state, self.base, self.base)
        self.schema.assert_not_called()
        self.current_sources = {key: dict(source) for key, source in self.sources.items()}
        self.schema.side_effect = RuntimeError("D01_SCHEMA_CHECK_FAILED")
        for action in (release.migrate, release.cutover):
            with self.assertRaisesRegex(RuntimeError, "SCHEMA_CHECK_FAILED"):
                action(self.state, self.base, self.base)
        self.assertEqual(self.current.resolve().name, "previous")
        self.assertFalse(any(event[1] == "start" for event in self.events))

    def test_application_only_start_failure_keeps_candidate_stopped_and_preserves_previous(self):
        self.prepare("88", "88", "APPLICATION_ONLY")
        release.freeze(self.state, True, True)
        previous = self.base / "releases/previous" / release.JAR
        original = self.command
        def fail(*args):
            if args == ("systemctl", "start", release.BACKEND):
                raise RuntimeError("fixture failure")
            return original(*args)
        with patch.object(release, "command", side_effect=fail), patch.object(release.subprocess, "run") as stopped:
            stopped.side_effect = self.stopped
            with self.assertRaisesRegex(RuntimeError, "NO_LEGACY_RESTART"):
                release.cutover(self.state, self.base, self.base)
        self.assertEqual(self.current.resolve().name, "d01-candidate")
        self.assertEqual(release.protected_json(self.state)["phase"], "FAILED_STOPPED")
        self.assertEqual(previous.read_bytes(), b"existing backend")
        self.assertEqual(self.config.read_bytes(), self.original)

    def test_reporting_plans_preserve_controls_and_upgrade_one_version_at_a_time(self):
        self.prepare("87", "85")
        self.assertEqual(self.schema.call_args.args[0], "validate85")
        self.assertEqual(self.events, [])
        release.freeze(self.state, True, True)
        frozen = self.state.read_bytes()
        self.schema.reset_mock()
        release.migrate(self.state, self.base, self.base)
        self.assertEqual([call.args[0] for call in self.schema.call_args_list], ["upgrade86", "upgrade87"])
        self.assertEqual(self.state.read_bytes(), frozen)
        release.cutover(self.state, self.base, self.base)
        self.assertEqual(self.schema.call_args.args[0], "validate87")
        self.assertEqual(self.config.read_bytes(), self.original)
        self.assertEqual(self.other.readlink(), self.base / "other-release")

    def test_reporting_package_requires_explicit_compatible_source_before_stop(self):
        with self.assertRaisesRegex(RuntimeError, "SCHEMA_MISMATCH"):
            self.prepare("87")
        self.assertEqual(self.events, [])

    def test_reporting_second_migration_failure_blocks_cutover_and_keeps_first_evidence(self):
        self.prepare("87", "85")
        release.freeze(self.state, True, True)
        frozen = self.state.read_bytes()
        self.schema.reset_mock()
        self.schema.side_effect = [None, RuntimeError("partial V87")]
        with self.assertRaisesRegex(RuntimeError, "partial V87"):
            release.migrate(self.state, self.base, self.base)
        self.assertEqual([call.args[0] for call in self.schema.call_args_list], ["upgrade86", "upgrade87"])
        self.assertEqual(self.state.read_bytes(), frozen)
        self.schema.side_effect = RuntimeError("D01_SCHEMA_CHECK_FAILED")
        with self.assertRaisesRegex(RuntimeError, "SCHEMA_CHECK_FAILED"):
            release.cutover(self.state, self.base, self.base)
        self.assertEqual(self.current.resolve().name, "previous")
        self.assertFalse(any(event[1] == "start" for event in self.events))
        self.assertEqual(self.config.read_bytes(), self.original)

    def test_reporting_restore_gate_blocks_both_migration_and_cutover(self):
        self.prepare("87", "85")
        release.freeze(self.state, True, True)
        self.schema.reset_mock()
        self.restore.side_effect = RuntimeError("D01_RESTORE_EVIDENCE_REQUIRED")
        for action in (release.migrate, release.cutover):
            with self.assertRaisesRegex(RuntimeError, "RESTORE_EVIDENCE_REQUIRED"):
                action(self.state, self.base, self.base)
        self.schema.assert_not_called()
        self.assertEqual(self.current.resolve().name, "previous")
        self.assertFalse(any(event[1] == "start" for event in self.events))

    def test_reporting_route_drift_refuses_before_stop(self):
        (self.base / "shared/nginx-user.conf").write_text("listen 80; proxy_pass http://127.0.0.1:8080;")
        with self.assertRaisesRegex(RuntimeError, "TEST_ROUTE_REQUIRED"):
            self.prepare("87", "85")
        self.assertEqual(self.events, [])

    def test_reporting_other_site_pid_drift_refuses_before_stop(self):
        self.prepare("87", "85")
        original = self.property
        with patch.object(release, "service_property", side_effect=lambda service, key:
                          "changed" if key == "MainPID" else original(service, key)):
            with self.assertRaisesRegex(RuntimeError, "OTHER_SITE_RESTARTED"):
                release.freeze(self.state, True, True)
        self.assertEqual(self.events, [])
        self.assertEqual(self.config.read_bytes(), self.original)

    def test_reporting_start_failure_keeps_candidate_and_no_legacy_restart(self):
        self.prepare("87", "86")
        release.freeze(self.state, True, True)
        original = self.command
        def fail(*args):
            if args == ("systemctl", "start", release.BACKEND):
                raise RuntimeError("fixture failure")
            return original(*args)
        with patch.object(release, "command", side_effect=fail), patch.object(release.subprocess, "run") as stopped:
            stopped.side_effect = self.stopped
            with self.assertRaisesRegex(RuntimeError, "NO_LEGACY_RESTART"):
                release.cutover(self.state, self.base, self.base)
        self.assertEqual(self.current.resolve().name, "d01-candidate")
        self.assertEqual(release.protected_json(self.state)["phase"], "FAILED_STOPPED")
        self.assertEqual(self.config.read_bytes(), self.original)

    def test_mismatched_v85_baseline_cannot_stop_services(self):
        self.prepare("85")
        state = release.protected_json(self.state)
        state["source_schema"] = "83"
        release.write_state(self.state, state)
        with self.assertRaisesRegex(RuntimeError, "STATE_SCHEMA_MISMATCH"):
            release.freeze(self.state, True, True)
        self.assertEqual(self.events, [])

    def test_v85_missing_restore_cannot_migrate_or_start(self):
        self.prepare("85")
        release.freeze(self.state, True, True)
        self.schema.reset_mock()
        self.restore.side_effect = RuntimeError("D01_RESTORE_EVIDENCE_REQUIRED")
        for action in (release.migrate, release.cutover):
            with self.assertRaisesRegex(RuntimeError, "RESTORE_EVIDENCE"):
                action(self.state, self.base, self.base)
        self.schema.assert_not_called()
        self.assertFalse(any(event[1] == "start" for event in self.events))

    def test_v85_start_failure_preserves_candidate_and_never_restarts_old_version(self):
        self.prepare("85")
        release.freeze(self.state, True, True)
        original = self.command
        def fail(*args):
            if args == ("systemctl", "start", release.BACKEND):
                raise RuntimeError("fixture failure")
            return original(*args)
        with patch.object(release, "command", side_effect=fail), patch.object(release.subprocess, "run") as stopped:
            stopped.side_effect = self.stopped
            with self.assertRaisesRegex(RuntimeError, "NO_LEGACY_RESTART"):
                release.cutover(self.state, self.base, self.base)
        self.assertEqual(self.current.resolve().name, "d01-candidate")
        self.assertEqual(release.protected_json(self.state)["phase"], "FAILED_STOPPED")

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

    def test_same_named_replacement_blocks_migration_and_cutover(self):
        self.prepare("87", "85")
        release.freeze(self.state, True, True)
        self.schema.reset_mock()
        for engine in ("mysql", "tdengine"):
            self.current_sources = {key: dict(source) for key, source in self.sources.items()}
            self.current_sources[engine]["container_id"] = "replacement-id"
            for action in (release.migrate, release.cutover):
                with self.subTest(engine=engine, action=action.__name__), self.assertRaisesRegex(RuntimeError, "BACKUP_SOURCE_CHANGED"):
                    action(self.state, self.base, self.base)
        self.schema.assert_not_called()
        self.assertEqual(self.current.resolve().name, "previous")

    def test_source_replacement_between_v86_and_v87_keeps_v87_unexecuted(self):
        self.prepare("87", "85")
        release.freeze(self.state, True, True)
        self.schema.reset_mock()
        def replace_after_v86(*args):
            self.assertEqual(args[0], "upgrade86")
            self.current_sources["mysql"]["container_id"] = "replacement-id"
        self.schema.side_effect = replace_after_v86
        with self.assertRaisesRegex(RuntimeError, "BACKUP_SOURCE_CHANGED"):
            release.migrate(self.state, self.base, self.base)
        self.assertEqual([call.args[0] for call in self.schema.call_args_list], ["upgrade86"])
        self.assertEqual(release.protected_json(self.state)["phase"], "FROZEN")
        self.assertEqual(self.current.resolve().name, "previous")

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
            stopped.side_effect = self.stopped
            with self.assertRaisesRegex(RuntimeError, "NO_LEGACY_RESTART"):
                release.cutover(self.state, self.base, self.base)
        state = release.protected_json(self.state)
        self.assertTrue(state["candidate_started"])
        self.assertEqual(state["phase"], "FAILED_STOPPED")
        self.assertEqual(self.current.resolve().name, "d01-candidate")

    def test_stop_returning_zero_with_active_service_cannot_claim_stopped(self):
        self.freeze()
        original = self.command
        def failing(*args):
            if args == ("systemctl", "start", release.WEB):
                raise RuntimeError("fixture web start failure")
            return original(*args)
        with patch.object(release, "command", side_effect=failing), patch.object(release.subprocess, "run") as stop:
            stop.return_value.returncode = 0
            with self.assertRaisesRegex(RuntimeError, "D01_FAIL_STOP_FAILED"):
                release.cutover(self.state, self.base, self.base)
            self.assertEqual(stop.call_args.kwargs["timeout"], 60)
        self.assertEqual(release.protected_json(self.state)["phase"], "FAILED_STOP_ERROR")
        self.assertEqual(self.current.resolve().name, "d01-candidate")
        self.assertEqual(self.config.read_bytes(), self.original)

    def test_stop_timeout_or_os_failure_records_unknown_stop_result(self):
        self.prepare("87", "85")
        release.freeze(self.state, True, True)
        frozen = self.state.read_bytes()
        for error in (subprocess.TimeoutExpired("systemctl", 60), OSError("fixture unavailable")):
            self.state.write_bytes(frozen)
            self.states = {release.BACKEND: "inactive", release.WEB: "inactive"}
            original = self.command
            def failing(*args):
                if args == ("systemctl", "start", release.WEB):
                    raise RuntimeError("fixture web start failure")
                return original(*args)
            with patch.object(release, "command", side_effect=failing), patch.object(release.subprocess, "run", side_effect=error):
                with self.assertRaisesRegex(RuntimeError, "D01_FAIL_STOP_FAILED"):
                    release.cutover(self.state, self.base, self.base)
            state = release.protected_json(self.state)
            self.assertEqual(state["phase"], "FAILED_STOP_ERROR")
            self.assertTrue(state["candidate_started"])
            self.assertEqual(self.current.resolve().name, "d01-candidate")
            self.current.unlink()
            self.current.symlink_to(self.base / "releases/previous", target_is_directory=True)

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
            stopped.side_effect = self.stopped
            with self.assertRaisesRegex(RuntimeError, "NO_LEGACY_RESTART"):
                release.cutover(self.state, self.base, self.base)
        self.assertEqual(release.protected_json(self.state)["phase"], "FAILED_STOPPED")


class BackupSourceIdentityTest(unittest.TestCase):
    def test_original_container_and_volume_are_checked_for_both_engines(self):
        sources = {engine: {"container": engine, "container_id": engine + "-id", "volume": engine + "-volume",
                             "image": engine + ":fixture", "volume_destination": destination}
                   for engine, destination in (("mysql", "/var/lib/mysql"), ("tdengine", "/var/lib/taos"))}
        with patch.object(release, "inspect_backup_container", side_effect=lambda docker, name, volume, destination, runner:
                          sources[name]) as inspect:
            release.check_backup_sources({"sources": sources})
            self.assertEqual(inspect.call_count, 2)
        for engine in sources:
            changed = {key: dict(value) for key, value in sources.items()}
            changed[engine]["container_id"] = "new-instance-id"
            with patch.object(release, "inspect_backup_container", side_effect=lambda docker, name, volume, destination, runner:
                              changed[name]):
                with self.assertRaisesRegex(RuntimeError, "BACKUP_SOURCE_CHANGED"):
                    release.check_backup_sources({"sources": sources})


class SchemaPlanTest(unittest.TestCase):
    def test_only_reviewed_paths_and_legacy_default_are_accepted(self):
        self.assertEqual(release.target_schema({}), "84")
        for source, target in (("83", "84"), ("84", "85"), ("85", "86"), ("85", "87"), ("86", "87"), ("87", "88")):
            self.assertEqual(release.target_schema({"source_schema": source, "target_schema": target}), target)
        for source, target in (("84", "87"), ("83", "86"), ("87", "87"), ("87", "86"), (None, "87"),
                               ("85", "88"), ("86", "88"), (None, "88")):
            with self.subTest(source=source, target=target), self.assertRaisesRegex(RuntimeError, "SCHEMA_MISMATCH"):
                release.target_schema({"source_schema": source, "target_schema": target})

    def test_application_only_requires_exact_v88_pair_and_explicit_mode(self):
        state = {"source_schema": "88", "target_schema": "88", "release_mode": "APPLICATION_ONLY"}
        self.assertEqual(release.target_schema(state), "88")
        for changed in ({"release_mode": "SCHEMA_UPGRADE"}, {"release_mode": "unknown"},
                        {"source_schema": "87"}, {"target_schema": "87"}, {"target_schema": "89"}):
            with self.subTest(changed=changed), self.assertRaisesRegex(RuntimeError, "SCHEMA_MISMATCH"):
                release.target_schema({**state, **changed})

    def test_application_only_accepts_exact_v93_pair_but_no_cross_version_shortcut(self):
        state = {"source_schema": "93", "target_schema": "93", "release_mode": "APPLICATION_ONLY"}
        self.assertEqual(release.target_schema(state), "93")
        for source, target, mode in (("88", "93", "APPLICATION_ONLY"), ("93", "88", "APPLICATION_ONLY"),
                                     ("93", "93", "SCHEMA_UPGRADE"), ("93", "94", "APPLICATION_ONLY")):
            with self.subTest(source=source, target=target, mode=mode), self.assertRaisesRegex(RuntimeError, "SCHEMA_MISMATCH"):
                release.target_schema({"source_schema": source, "target_schema": target, "release_mode": mode})


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


class SchemaProcessTest(unittest.TestCase):
    def test_v85_requires_exact_success_marker_and_separate_account(self):
        values = {"url": "jdbc:mysql://127.0.0.1:13316/iot_platform", "user": "platform_migrator", "password": "fixture"}
        state = {"source_schema": "84", "target_schema": "85", "release": "/root/trusted-tools", "runtime_controls": {
            "MYSQL_HOST": "127.0.0.1", "MYSQL_PORT": "13316", "MYSQL_USER": "platform_app"}}
        with patch.object(release, "protected_json", return_value=values), patch.object(release.subprocess, "run") as command:
            command.return_value.returncode = 0
            command.return_value.stdout = "D01_SCHEMA_VALIDATED=84\n"
            with self.assertRaisesRegex(RuntimeError, "SCHEMA_CHECK_FAILED"):
                release.schema("upgrade85", state, Path("/protected/schema.json"))
            command.return_value.stdout = "D01_SCHEMA_VALIDATED=85\n"
            release.schema("upgrade85", state, Path("/protected/schema.json"))
            self.assertEqual(command.call_args.args[0][-1], "upgrade85")
            values["user"] = "platform_app"
            command.reset_mock()
            with self.assertRaisesRegex(RuntimeError, "SEPARATE_MIGRATION_ACCOUNT"):
                release.schema("upgrade85", state, Path("/protected/schema.json"))
            command.assert_not_called()

    def test_application_only_cannot_launch_upgrade_repair_or_unpinned_action(self):
        state = {"source_schema": "88", "target_schema": "88", "release_mode": "APPLICATION_ONLY"}
        with patch.object(release, "protected_json") as credentials, patch.object(release.subprocess, "run") as command:
            for action in ("upgrade88", "upgrade89", "validate87", "repair", "clean", "baseline"):
                with self.subTest(action=action), self.assertRaisesRegex(RuntimeError, "ACTION_NOT_AUTHORIZED"):
                    release.schema(action, state, Path("/protected/schema.json"))
            credentials.assert_not_called()
            command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
