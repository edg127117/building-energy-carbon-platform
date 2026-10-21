"""离线验证测试发布配置；不连接服务器、数据库或厂家。"""

import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "daikin_test_deployment_config.py"
spec = importlib.util.spec_from_file_location("test_deploy_config", MODULE_PATH)
config_tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(config_tool)


class TestDeploymentControlConfig(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.config = Path(self.temp.name) / "control.env"
        self.backup = Path(self.temp.name) / "before.env"
        self.original = b"# existing configuration\nUNRELATED=value\nDAIKIN_CONTROL_ENABLED=false\nDAIKIN_CONTROL_TEST_WINDOW_EXPIRES_AT=123456\n"
        self.config.write_bytes(self.original)

    def prepare(self):
        config_tool.prepare(self.config, self.backup, {"AUDIT_ENVIRONMENT_MODE": "TEST"})

    def test_closed_config_opens_without_device_window_and_preserves_other_settings(self):
        self.prepare()
        settings = dict(line.split("=", 1) for line in self.config.read_text().splitlines() if "=" in line)
        config_tool.verify({**settings, "AUDIT_ENVIRONMENT_MODE": "TEST"})
        self.assertEqual(settings["UNRELATED"], "value")
        self.assertEqual(self.backup.read_bytes(), self.original)
        self.assertIn("# existing configuration", self.config.read_text())

    def test_next_update_keeps_open_and_backup_captures_previous_open_values(self):
        self.prepare()
        opened = self.config.read_bytes()
        next_backup = self.backup.with_name("next.env")
        config_tool.prepare(self.config, next_backup, {"AUDIT_ENVIRONMENT_MODE": "TEST"})
        self.assertEqual(self.config.read_bytes(), opened)
        self.assertEqual(next_backup.read_bytes(), opened)

    def test_duplicate_and_spaced_old_switches_are_removed(self):
        self.config.write_text("DAIKIN_CONTROL_ENABLED=false\n DAIKIN_CONTROL_ENABLED =false\n")
        self.prepare()
        self.assertEqual(self.config.read_text().count("DAIKIN_CONTROL_ENABLED"), 1)

    def test_non_test_environment_is_rejected_before_any_write(self):
        for mode in ["PRODUCTION", "DEVELOPMENT", "", None]:
            with self.subTest(mode=mode), self.assertRaises(SystemExit):
                config_tool.prepare(self.config, self.backup, {"AUDIT_ENVIRONMENT_MODE": mode})
            self.assertEqual(self.config.read_bytes(), self.original)
            self.assertFalse(self.backup.exists())

    def test_existing_backup_cannot_be_overwritten(self):
        self.backup.write_bytes(b"preserved")
        with self.assertRaises(FileExistsError):
            self.prepare()
        self.assertEqual(self.config.read_bytes(), self.original)
        self.assertEqual(self.backup.read_bytes(), b"preserved")

    def test_failed_config_replacement_leaves_original_and_recovery_backup(self):
        with patch.object(config_tool.os, "replace", side_effect=OSError("offline failure")):
            with self.assertRaises(OSError):
                self.prepare()
        self.assertEqual(self.config.read_bytes(), self.original)
        self.assertEqual(self.backup.read_bytes(), self.original)
        self.assertFalse(list(self.config.parent.glob(self.config.name + ".deploy-*")))

    def test_failed_publish_restores_exact_previous_config(self):
        self.prepare()
        config_tool.restore(self.config, self.backup)
        self.assertEqual(self.config.read_bytes(), self.original)

    def test_failed_next_publish_restores_open_config(self):
        self.prepare()
        opened = self.config.read_bytes()
        next_backup = self.backup.with_name("next.env")
        config_tool.prepare(self.config, next_backup, {"AUDIT_ENVIRONMENT_MODE": "TEST"})
        config_tool.restore(self.config, next_backup)
        self.assertEqual(self.config.read_bytes(), opened)

    def test_runtime_verification_rejects_overridden_switch(self):
        for key in config_tool.TEST_SETTINGS:
            with self.subTest(key=key), self.assertRaises(SystemExit):
                config_tool.verify({**config_tool.TEST_SETTINGS, "AUDIT_ENVIRONMENT_MODE": "TEST", key: "unexpected"})

    def test_runtime_verification_rejects_production(self):
        with self.assertRaises(SystemExit):
            config_tool.verify({**config_tool.TEST_SETTINGS, "AUDIT_ENVIRONMENT_MODE": "PRODUCTION"})

    def test_preserved_mode_keeps_bytes_and_existing_runtime_including_missing_settings(self):
        runtime = {"AUDIT_ENVIRONMENT_MODE": "TEST", "DAIKIN_CONTROL_ENABLED": "false"}
        config_tool.preserve(self.config, self.backup, runtime)
        self.assertEqual(self.config.read_bytes(), self.original)
        config_tool.verify_preserved(self.config, self.backup, runtime)
        with self.assertRaises(SystemExit):
            config_tool.verify_preserved(self.config, self.backup, {**runtime, "DAIKIN_CONTROL_ENABLED": "true"})
        self.config.write_bytes(b"changed")
        with self.assertRaises(SystemExit):
            config_tool.verify_preserved(self.config, self.backup, runtime)

    def test_preserved_mode_rejects_production_before_creating_backup(self):
        with self.assertRaises(SystemExit):
            config_tool.preserve(self.config, self.backup, {"AUDIT_ENVIRONMENT_MODE": "PRODUCTION"})
        self.assertFalse(self.backup.exists())


if __name__ == "__main__":
    unittest.main()
