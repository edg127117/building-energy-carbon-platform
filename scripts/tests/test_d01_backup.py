"""D01 backup unit tests: temporary files and a mocked command runner only."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "d01_backup.py"
spec = importlib.util.spec_from_file_location("d01_backup", MODULE_PATH)
backup_tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup_tool)


class FakeRunner:
    """Models only command results; it never invokes Docker, systemctl, or a database."""

    def __init__(self, *, active_after_dump=False, wrong_tdengine_mount=False, timeout_stage=None,
                 copy_fails=False, cleanup_fails=False):
        self.active_after_dump = active_after_dump
        self.wrong_tdengine_mount = wrong_tdengine_mount
        self.timeout_stage = timeout_stage
        self.copy_fails = copy_fails
        self.cleanup_fails = cleanup_fails
        self.calls = []
        self.timeouts = []
        self.service_calls = 0

    @staticmethod
    def result(args, output=b""):
        return subprocess.CompletedProcess(args, 0, stdout=output, stderr=b"")

    def __call__(self, args, **kwargs):
        args = list(args)
        self.calls.append((args, kwargs.get("input")))
        self.timeouts.append((args, kwargs.get("timeout")))
        if args[0] == "fake-systemctl":
            self.service_calls += 1
            active = self.active_after_dump and self.service_calls > 2
            return self.result(args, b"active\n" if active else b"inactive\n")
        if args[0] != "fake-docker":
            raise AssertionError("unexpected executable: " + args[0])
        if args[1:3] == ["volume", "inspect"]:
            return self.result(args, (args[-1] + "\n").encode())
        if args[1] == "inspect":
            template, name = args[3], args[4]
            if template == "{{.Id}}":
                return self.result(args, (name + "-id\n").encode())
            if template == "{{.Name}}":
                return self.result(args, ("/" + name + "\n").encode())
            if template == "{{.Config.Image}}":
                image = "mysql:8.0" if name == "mysql-db" else "tdengine/tdengine:3.2.3.0"
                return self.result(args, (image + "\n").encode())
            if template == "{{.State.Running}}":
                return self.result(args, b"true\n")
            if template == "{{json .Mounts}}":
                if name == "mysql-db":
                    mount = {"Type": "volume", "Name": "mysql-volume", "Destination": "/var/lib/mysql"}
                else:
                    mount = {"Type": "volume", "Name": "tdengine-volume",
                             "Destination": "/wrong" if self.wrong_tdengine_mount else "/var/lib/taos"}
                return self.result(args, json.dumps([mount]).encode())
            raise AssertionError("unexpected inspect template: " + template)
        if args[1:4] == ["exec", "-i", "mysql-db"]:
            if self.timeout_stage == "mysql":
                raise subprocess.TimeoutExpired(args, kwargs["timeout"])
            kwargs["stdout"].write(b"-- MySQL logical dump\nCREATE DATABASE iot_platform;\n")
            return self.result(args)
        if args[1:4] == ["exec", "-i", "td-db"]:
            if self.timeout_stage == "tdengine":
                raise subprocess.TimeoutExpired(args, kwargs["timeout"])
            return self.result(args)
        if args[1] == "cp":
            if args[-1].endswith('/client'):
                return self.result(args)
            if self.timeout_stage == "copy":
                raise subprocess.TimeoutExpired(args, kwargs["timeout"])
            if self.copy_fails:
                return subprocess.CompletedProcess(args, 1, stdout=b"", stderr=b"copy failed")
            (Path(args[-1]) / "metadata.avro").write_bytes(b"tdengine export")
            return self.result(args)
        if args[1:3] == ["exec", "td-db"]:
            if args[3] == 'sha256sum':
                return self.result(args, (backup_tool.TD_LIBRARY_SHA256 + '  ' + backup_tool.TD_LIBRARY).encode())
            if self.cleanup_fails and 'd01-taos-cleanup' in args:
                return subprocess.CompletedProcess(args, 1, stdout=b"", stderr=b"cleanup failed")
            return self.result(args)
        raise AssertionError("unexpected Docker call: " + repr(args))


class TestD01Backup(unittest.TestCase):
    def test_unexpected_export_error_never_persists_arbitrary_exception_text(self):
        runner = FakeRunner()
        def unexpected(args, **kwargs):
            if 'sha256sum' in args:
                raise RuntimeError('sensitive-unexpected-output')
            return runner(args, **kwargs)
        with self.assertRaisesRegex(backup_tool.BackupError, '^TDENGINE_EXPORT_UNEXPECTED_FAILURE$'):
            backup_tool._dump_tdengine(self.root / 'tdengine', {'tdengine_user': 'root',
                'tdengine_password': 'private-fixture'}, 'fake-docker', 'td-db', 'iot_telemetry', unexpected)
        self.assertNotIn('sensitive-unexpected-output', (self.root / 'tdengine-diagnostic.json').read_text())

    def test_native_library_mismatch_rejects_before_copying_client_or_sending_credentials(self):
        runner = FakeRunner()
        with patch.object(backup_tool, '_output', return_value='different library'):
            with self.assertRaisesRegex(backup_tool.BackupError, 'TDENGINE_CLIENT_ABI_MISMATCH'):
                backup_tool._dump_tdengine(self.root / 'tdengine', {'tdengine_user': 'root',
                    'tdengine_password': 'private-fixture'}, 'fake-docker', 'td-db', 'iot_telemetry', runner)
        self.assertFalse(any(args[1] == 'cp' or payload for args, payload in runner.calls))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "backups"
        self.root.mkdir()
        client = Path(self.temp.name) / 'taosdump-d01'
        client.write_bytes(b'isolated fixture client')
        patched = patch.object(backup_tool, 'TD_CLIENT', client)
        patched.start()
        self.addCleanup(patched.stop)
        self.freeze = Path(self.temp.name) / "freeze.json"
        self.credentials = Path(self.temp.name) / "credentials.json"
        self.freeze_id = "123e4567-e89b-12d3-a456-426614174000"
        self.write_json(self.freeze, {
            "format": "D01_FROZEN_V1",
            "phase": "FROZEN",
            "freeze_id": self.freeze_id,
            "backend_service": "building-energy-carbon-test-platform.service",
            "web_service": "building-energy-carbon-test-nginx.service",
            "previous_release": "/releases/previous",
            "current_path": "/current",
            "prepared_plan_hash": "a" * 64,
            "frozen_at": 1_791_000_000,
            "writers_fenced": True,
            "ingress_retained": True,
            "backup_targets": {
                "mysql_container": "mysql-db",
                "mysql_volume": "mysql-volume",
                "tdengine_container": "td-db",
                "tdengine_volume": "tdengine-volume",
                "tdengine_database": "iot_telemetry",
            },
        })
        self.write_json(self.credentials, {
            "mysql": {"user": "backup_user", "password": "mysql-secret"},
            "tdengine": {"user": "tdbackup", "password": "td-secret"},
        })

    @staticmethod
    def write_json(path, value):
        path.write_text(json.dumps(value), encoding="utf-8")
        if os.name == "posix":
            path.chmod(0o600)

    def create(self, runner=None, **overrides):
        arguments = {
            "freeze_state": self.freeze,
            "output_root": self.root,
            "credentials_file": self.credentials,
            "mysql_container": "mysql-db",
            "mysql_volume": "mysql-volume",
            "tdengine_container": "td-db",
            "tdengine_volume": "tdengine-volume",
            "tdengine_database": "iot_telemetry",
            "systemctl_bin": "fake-systemctl",
            "docker_bin": "fake-docker",
            "min_free_bytes": 0,
            "runner": runner or FakeRunner(),
        }
        arguments.update(overrides)
        return backup_tool.backup(**arguments)

    def test_backup_binds_frozen_state_and_keeps_secrets_off_command_lines_and_manifest(self):
        runner = FakeRunner()
        backup = self.create(runner)
        manifest = json.loads((backup / "manifest.json").read_text(encoding="utf-8"))
        report = backup_tool.validate(backup)
        self.assertEqual(self.freeze_id, manifest["freeze_id"])
        self.assertEqual(report["freeze_state_sha256"], manifest["freeze_state_sha256"])
        self.assertEqual(["iot_platform", "iot_platform_archive"], manifest["sources"]["mysql"]["databases"])
        self.assertEqual("iot_telemetry", manifest["sources"]["tdengine"]["database"])
        self.assertEqual({
            "mysql_container": "mysql-db",
            "mysql_volume": "mysql-volume",
            "tdengine_container": "td-db",
            "tdengine_volume": "tdengine-volume",
            "tdengine_database": "iot_telemetry",
        }, report["backup_targets"])
        self.assertGreaterEqual(report["files_checked"], 3)
        sums = (backup / "SHA256SUMS").read_text(encoding="ascii")
        self.assertIn("  manifest.json\n", sums)
        self.assertNotIn("  SHA256SUMS\n", sums)
        command_text = "\n".join(" ".join(call[0]) for call in runner.calls)
        self.assertNotIn("mysql-secret", command_text)
        self.assertNotIn("td-secret", command_text)
        self.assertNotIn("mysql-secret", json.dumps(manifest))
        self.assertNotIn("td-secret", json.dumps(manifest))
        mysql_stdin = next(data for args, data in runner.calls if args[1:4] == ["exec", "-i", "mysql-db"])
        td_stdin = next(data for args, data in runner.calls if args[1:4] == ["exec", "-i", "td-db"])
        td_command = next(args for args, _ in runner.calls if args[1:4] == ["exec", "-i", "td-db"])
        self.assertIn(b"mysql-secret", mysql_stdin)
        self.assertEqual(b"tdbackup\ntd-secret\n", td_stdin)
        self.assertIn('"$1/client" -u "$td_user" -p', td_command[6])
        self.assertIn('-r /dev/null -T 1', td_command[6])
        calls_before_validate = len(runner.calls)
        backup_tool.validate(backup)
        self.assertEqual(calls_before_validate, len(runner.calls))

    def test_td_failure_diagnostic_contains_only_allowlisted_categories(self):
        for phrase, category in ((b'Authentication failure', 'authentication_failed'),
                                 (b'Unable to establish connection', 'connection_failed'),
                                 (b'No space left on device', 'disk_full'),
                                 (b'unrecognized private value', 'unclassified')):
            with self.subTest(category=category):
                def runner(args, **kwargs):
                    return subprocess.CompletedProcess(args, 1, stdout=phrase + b' password=secret-value SQL: private-row',
                                                       stderr=b'td-secret mysql-secret')
                with self.assertRaises(backup_tool.BackupError) as raised:
                    backup_tool._run(['fixture'], 'TDENGINE_DUMP_FAILED', runner=runner)
                self.assertEqual(raised.exception.diagnostic, {'category': category, 'returncode': 1})
                self.assertEqual(str(raised.exception), 'TDENGINE_DUMP_FAILED')

    def test_td_failure_keeps_private_diagnostic_and_still_cleans_temporary_client(self):
        base = FakeRunner()
        def runner(args, **kwargs):
            if list(args)[1:4] == ['exec', '-i', 'td-db']:
                return subprocess.CompletedProcess(args, 1, stdout=b'Authentication failure td-secret', stderr=b'private data')
            return base(args, **kwargs)
        with self.assertRaisesRegex(backup_tool.BackupError, 'TDENGINE_DUMP_FAILED'):
            self.create(runner)
        partial = next(self.root.glob('*.partial'))
        diagnostic = partial / 'tdengine-diagnostic.json'
        self.assertEqual(json.loads(diagnostic.read_text())['category'], 'authentication_failed')
        self.assertNotIn('td-secret', diagnostic.read_text())
        if os.name == 'posix':
            self.assertEqual(diagnostic.stat().st_mode & 0o777, 0o600)
        self.assertTrue(any('d01-taos-cleanup' in args for args, _ in base.calls))

    def test_nonfrozen_state_is_rejected_before_any_external_command_or_partial_directory(self):
        state = json.loads(self.freeze.read_text(encoding="utf-8"))
        state["phase"] = "STARTING"
        self.write_json(self.freeze, state)
        runner = FakeRunner()
        with self.assertRaisesRegex(backup_tool.BackupError, "FREEZE_STATE_NOT_FROZEN"):
            self.create(runner)
        self.assertEqual([], runner.calls)
        self.assertEqual([], list(self.root.iterdir()))

    def test_post_dump_writer_reactivation_keeps_partial_without_success_manifest(self):
        with self.assertRaisesRegex(backup_tool.BackupError, "WRITER_SERVICE_NOT_INACTIVE"):
            self.create(FakeRunner(active_after_dump=True))
        partials = list(self.root.glob("*.partial"))
        self.assertEqual(1, len(partials))
        self.assertTrue((partials[0] / "FAILED.json").is_file())
        self.assertFalse((partials[0] / "manifest.json").exists())
        with self.assertRaisesRegex(backup_tool.BackupError, "PARTIAL_BACKUP_REJECTED"):
            backup_tool.validate(partials[0])

    def test_accepted_loss_backup_preserves_authorization_and_still_checks_writers(self):
        state = json.loads(self.freeze.read_text(encoding="utf-8"))
        state.update(ingress_retained=False, ingress_loss_accepted=True,
                     loss_acceptance_reference="approved test maintenance")
        self.write_json(self.freeze, state)
        folder = self.create()
        manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
        self.assertFalse(manifest["ingress_retained"])
        self.assertTrue(manifest["ingress_loss_accepted"])
        self.assertEqual(manifest["loss_acceptance_reference"], "approved test maintenance")
        backup_tool.validate(folder)
        with self.assertRaisesRegex(backup_tool.BackupError, "WRITER_SERVICE_NOT_INACTIVE"):
            self.create(FakeRunner(active_after_dump=True))

    def test_incomplete_or_contradictory_ingress_decision_never_runs_backup(self):
        original = json.loads(self.freeze.read_text(encoding="utf-8"))
        for changes in (
            {"ingress_retained": False},
            {"ingress_retained": True, "ingress_loss_accepted": True},
            {"ingress_retained": False, "ingress_loss_accepted": True},
            {"ingress_retained": False, "ingress_loss_accepted": True, "loss_acceptance_reference": " "},
            {"ingress_retained": False, "ingress_loss_accepted": True,
             "loss_acceptance_reference": "approved", "writers_fenced": False},
            {"ingress_retained": None, "ingress_loss_accepted": True, "loss_acceptance_reference": "approved"},
        ):
            with self.subTest(changes=changes):
                self.write_json(self.freeze, {**original, **changes})
                runner = FakeRunner()
                with self.assertRaises(backup_tool.BackupError):
                    self.create(runner)
                self.assertEqual([], runner.calls)
                self.assertEqual([], list(self.root.iterdir()))

    def test_all_external_commands_receive_metadata_or_dump_deadlines(self):
        runner = FakeRunner()
        self.create(runner)
        for args, timeout in runner.timeouts:
            is_dump_or_copy = (args[1] == "cp" and not args[-1].endswith('/client')) or args[1:3] == ["exec", "-i"]
            self.assertEqual(backup_tool.DUMP_TIMEOUT_SECONDS if is_dump_or_copy
                             else backup_tool.METADATA_TIMEOUT_SECONDS, timeout)
        mysql_timeout = next(timeout for args, timeout in runner.timeouts if args[1:4] == ["exec", "-i", "mysql-db"])
        td_timeout = next(timeout for args, timeout in runner.timeouts if args[1:4] == ["exec", "-i", "td-db"])
        copy_timeout = next(timeout for args, timeout in runner.timeouts if args[1] == "cp" and not args[-1].endswith('/client'))
        cleanup_timeout = next(timeout for args, timeout in runner.timeouts if args[1:3] == ["exec", "td-db"])
        self.assertEqual(backup_tool.DUMP_TIMEOUT_SECONDS, mysql_timeout)
        self.assertEqual(backup_tool.DUMP_TIMEOUT_SECONDS, td_timeout)
        self.assertEqual(backup_tool.DUMP_TIMEOUT_SECONDS, copy_timeout)
        self.assertEqual(backup_tool.METADATA_TIMEOUT_SECONDS, cleanup_timeout)

    def test_dump_and_copy_timeouts_are_stable_and_tdengine_temp_is_cleaned(self):
        for stage, code, cleanup_expected in (
            ("mysql", "MYSQL_DUMP_TIMEOUT", False),
            ("tdengine", "TDENGINE_DUMP_TIMEOUT", True),
            ("copy", "TDENGINE_COPY_TIMEOUT", True),
        ):
            with self.subTest(stage=stage):
                runner = FakeRunner(timeout_stage=stage)
                with self.assertRaisesRegex(backup_tool.BackupError, code):
                    self.create(runner)
                cleanup_calls = [args for args, _ in runner.calls if args[1:3] == ["exec", "td-db"]]
                self.assertEqual(cleanup_expected, bool(cleanup_calls))

    def test_copy_failure_still_cleans_tdengine_temp_and_keeps_host_partial(self):
        runner = FakeRunner(copy_fails=True)
        with self.assertRaisesRegex(backup_tool.BackupError, "TDENGINE_COPY_FAILED"):
            self.create(runner)
        self.assertTrue(any(args[1:3] == ["exec", "td-db"] for args, _ in runner.calls))
        partial = next(self.root.glob("*.partial"))
        self.assertTrue((partial / "tdengine").is_dir())
        self.assertEqual("TDENGINE_COPY_FAILED", json.loads((partial / "FAILED.json").read_text())["error_code"])

    def test_cleanup_failure_is_reported_and_combined_with_copy_failure(self):
        with self.assertRaisesRegex(backup_tool.BackupError, "TDENGINE_TEMP_CLEANUP_FAILED"):
            self.create(FakeRunner(cleanup_fails=True))
        with self.assertRaisesRegex(backup_tool.BackupError, "TDENGINE_OPERATION_AND_TEMP_CLEANUP_FAILED"):
            self.create(FakeRunner(copy_fails=True, cleanup_fails=True))

    def test_wrong_data_volume_mount_is_rejected_before_dump_directory_is_created(self):
        with self.assertRaisesRegex(backup_tool.BackupError, "DOCKER_VOLUME_NOT_MOUNTED_BY_TARGET"):
            self.create(FakeRunner(wrong_tdengine_mount=True))
        self.assertEqual([], list(self.root.glob("*.partial")))

    def test_requested_target_must_match_the_frozen_target_before_external_commands(self):
        for override in ({"mysql_container": "other-mysql"}, {"tdengine_volume": "other-volume"}):
            with self.subTest(override=override):
                runner = FakeRunner()
                with self.assertRaisesRegex(backup_tool.BackupError, "BACKUP_TARGET_MISMATCH"):
                    self.create(runner, **override)
                self.assertEqual([], runner.calls)

    def test_validate_rejects_tampering_and_extra_files(self):
        backup = self.create()
        (backup / "mysql.sql").write_bytes(b"tampered")
        with self.assertRaisesRegex(backup_tool.BackupError, "CHECKSUM_MISMATCH"):
            backup_tool.validate(backup)
        backup = self.create()
        (backup / "unexpected.txt").write_text("unexpected", encoding="utf-8")
        with self.assertRaisesRegex(backup_tool.BackupError, "CHECKSUM_FILE_SET_MISMATCH"):
            backup_tool.validate(backup)

    def test_validate_rejects_missing_checksum_path_escape_and_partial(self):
        backup = self.create()
        (backup / "tdengine" / "metadata.avro").unlink()
        with self.assertRaisesRegex(backup_tool.BackupError, "CHECKSUM_FILE_SET_MISMATCH"):
            backup_tool.validate(backup)
        backup = self.create()
        (backup / "SHA256SUMS").write_text("{}  ../outside\n".format("0" * 64), encoding="ascii")
        with self.assertRaisesRegex(backup_tool.BackupError, "CHECKSUM_PATH_ESCAPE"):
            backup_tool.validate(backup)
        partial = self.root / "unfinished.partial"
        partial.mkdir()
        with self.assertRaisesRegex(backup_tool.BackupError, "PARTIAL_BACKUP_REJECTED"):
            backup_tool.validate(partial)

    def test_validate_rejects_missing_payload_even_if_checksums_are_regenerated(self):
        backup = self.create()
        (backup / "mysql.sql").unlink()
        (backup / "SHA256SUMS").unlink()
        backup_tool._write_sums(backup)
        with self.assertRaisesRegex(backup_tool.BackupError, "MYSQL_DUMP_EMPTY"):
            backup_tool.validate(backup)

    def test_validate_rejects_wrong_manifest_source_even_if_checksums_are_regenerated(self):
        backup = self.create()
        manifest_path = backup / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["sources"]["mysql"]["databases"] = ["iot_platform"]
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        (backup / "SHA256SUMS").unlink()
        backup_tool._write_sums(backup)
        with self.assertRaisesRegex(backup_tool.BackupError, "MANIFEST_INVALID"):
            backup_tool.validate(backup)

    @unittest.skipUnless(os.name == "posix", "symbolic-link test requires POSIX permissions")
    def test_validate_rejects_symbolic_link_even_when_checksum_lists_it(self):
        backup = self.create()
        os.symlink(backup / "mysql.sql", backup / "tdengine" / "escape")
        with self.assertRaisesRegex(backup_tool.BackupError, "BACKUP_SYMLINK_REJECTED"):
            backup_tool.validate(backup)

    @unittest.skipUnless(os.name == "posix", "protected file mode is a Linux deployment rule")
    def test_credentials_with_group_or_other_access_are_rejected(self):
        self.credentials.chmod(0o644)
        runner = FakeRunner()
        with self.assertRaisesRegex(backup_tool.BackupError, "CREDENTIALS_PERMISSIONS_TOO_OPEN"):
            self.create(runner)
        self.assertEqual([], runner.calls)

    @unittest.skipUnless(os.name == "posix", "backup protection is a Linux deployment rule")
    def test_validate_rejects_backup_directory_with_group_or_other_access(self):
        backup = self.create()
        backup.chmod(0o755)
        with self.assertRaisesRegex(backup_tool.BackupError, "BACKUP_DIRECTORY_PERMISSIONS_TOO_OPEN"):
            backup_tool.validate(backup)


if __name__ == "__main__":
    unittest.main()
