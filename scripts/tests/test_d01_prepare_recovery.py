"""已撤销且备份尚未开始的已知状态路径缺陷，仅归档失败证据，不修改原发布。"""
import contextlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch


@unittest.skipUnless(sys.platform == "linux", "受控归档使用POSIX路径和属主")
class PrepareStateRecoveryTest(unittest.TestCase):
    def setUp(self):
        from test_d01_maintenance import installer
        self.installer = installer
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.private = self.root / "private"
        self.private.mkdir()
        self.config = {"release": "d01-" + "a" * 12 + "-rehearsal", "rehearsal_only": True,
                       "source_schema": "93", "target_schema": "121", "release_mode": "SCHEMA_UPGRADE",
                       "code_sha256": {"d01_release.py": "9312c87292d2ce0471e5c20deb7acf402e41389318a1e3bbe574e92e93017b43"}}
        self.operation = {"failure_code": "D01_REGULAR_STATE_REQUIRED"}
        (self.private / "d01-Td.json").write_text("fixed old state")

    def test_only_known_tool_and_failure_can_select_misplaced_state(self):
        with patch.object(self.installer, "trusted_parent") as protect:
            path, before_dump = self.installer.failed_online_state_path(self.private, self.config, self.operation)
        self.assertEqual(path, self.private / "d01-Td.json")
        self.assertTrue(before_dump)
        protect.assert_called_once_with(path)
        self.assertEqual(path.read_text(), "fixed old state")
        for change in ({"rehearsal_only": False}, {"source_schema": "88"}, {"target_schema": "120"},
                       {"code_sha256": {"d01_release.py": "f" * 64}}):
            with self.subTest(change=change), self.assertRaisesRegex(RuntimeError, "STATE_MISSING"):
                self.installer.failed_online_state_path(self.private, {**self.config, **change}, self.operation)
        with self.assertRaisesRegex(RuntimeError, "STATE_MISSING"):
            self.installer.failed_online_state_path(self.private, self.config, {"failure_code": "MYSQL_DUMP_FAILED"})

    def test_started_or_ambiguous_backup_cannot_use_before_dump_recovery(self):
        (self.private / "online-backup").mkdir()
        with self.assertRaisesRegex(RuntimeError, "PREBACKUP_STATE_NOT_PROVEN"):
            self.installer.failed_online_state_path(self.private, self.config, self.operation)
        (self.private / "online-backup").rmdir()
        (self.private / "online-backup").symlink_to(self.root / "missing")
        with self.assertRaisesRegex(RuntimeError, "PREBACKUP_STATE_NOT_PROVEN"):
            self.installer.failed_online_state_path(self.private, self.config, self.operation)

    def test_normal_state_keeps_existing_partial_backup_requirements(self):
        expected = self.private / ("d01-" + self.config["release"] + ".json")
        expected.write_text("normal state")
        self.assertEqual(self.installer.failed_online_state_path(self.private, self.config, self.operation), (expected, False))

    def test_archival_preserves_misplaced_state_failure_chain_and_original_release(self):
        from test_d01_shared_alignment import rehearsal_authorization_tree
        import os
        installer = self.installer
        tree = rehearsal_authorization_tree(installer, self.root, event_phases=["PREPARED", "FAILED"])
        private = tree["private"]
        operation_path = private / "operation.json"
        operation = json.loads(operation_path.read_text())
        operation.update(failed_action="online-backup", failure_code="D01_REGULAR_STATE_REQUIRED")
        operation_path.write_text(json.dumps(operation))
        (private / "online-backup/manifest.json").unlink()
        (private / "online-backup").rmdir()
        expected = private / ("d01-" + tree["old_release"] + ".json")
        state = json.loads(expected.read_text())
        state.update(format="D01_PREPARED_V1", other_release="other", other_link="other", other_pid="100",
                     other_reload="unchanged", runtime_controls={}, backup_targets={},
                     backend_service="backend", web_service="web",
                     alignment={"roomSensorBuildingId": "building", "roomSensorSpaceId": "space",
                                "roomSensorMysqlSha256": "c" * 64, "roomSensorTdSha256": "d" * 64})
        misplaced = private / "d01-Td.json"
        misplaced.write_text(json.dumps(state))
        expected.unlink()
        # 该层验证归档和保留语义；固定旧版本识别由上面的独立测试验证。
        mapping = {"BASE": tree["base"], "INSTALL": tree["install"], "DATA": private.parent,
                   "LAUNCHER": self.root / "entry", "UNITS": tree["units"], "SUDOERS": self.root / "no-sudoers",
                   "HISTORY": self.root / "history"}
        def system(*args):
            if "UnitFileState" in args: return "disabled"
            if "building-energy-carbon-test-platform.service" in args: return "active"
            return "inactive" if "ActiveState" in args else ""
        with contextlib.ExitStack() as stack:
            for key, value in mapping.items(): stack.enter_context(patch.object(installer, key, value))
            stack.enter_context(patch.object(installer, "trusted_parent"))
            stack.enter_context(patch.object(installer, "failed_online_state_path", return_value=(misplaced, True)))
            baseline = stack.enter_context(patch.object(installer, "retry_baseline"))
            stack.enter_context(patch.object(installer, "retry_configuration", return_value={"fixture": "hash"}))
            stack.enter_context(patch.object(installer.pwd, "getpwnam", return_value=Mock(pw_uid=os.getuid())))
            commands = stack.enter_context(patch.object(installer, "run", side_effect=system))
            installer.retire_failed_prepare(online_backup=True, next_release="d01-" + "b" * 12 + "-rehearsal",
                                            source_schema="93", target_schema="121")
            baseline.assert_called_once()
        history = list((self.root / "history").iterdir())
        self.assertEqual(len(history), 1)
        archived = history[0] / "data/private"
        self.assertEqual(json.loads((archived / "d01-Td.json").read_text()), state)
        self.assertEqual(json.loads((archived / "operation.json").read_text()), operation)
        self.assertFalse((archived / "online-backup").exists())
        self.assertEqual((tree["base"] / "current").resolve(), tree["baseline"])
        self.assertEqual((tree["candidate"] / "building-energy-carbon-platform.jar").read_bytes(), b"candidate jar")
        self.assertTrue(all(not any(verb in args for verb in ("stop", "start", "restart"))
                            for args, _ in commands.call_args_list))
