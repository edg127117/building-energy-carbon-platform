"""Offline MySQL trigger-creation privilege and binlog metadata boundaries."""
import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "d01_credentials.py"
SPEC = importlib.util.spec_from_file_location("d01_credentials_trigger_test", SCRIPT)
CREDENTIALS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CREDENTIALS)

IDENTITY = "12345678-1234-1234-1234-123456789abc"
PRINCIPAL = "cGxhdGZvcm1fbWlncmF0b3JAbG9jYWxob3N0"  # platform_migrator@localhost
META = f"META\t{IDENTITY}\tOFF\t0\t0\t{PRINCIPAL}"
SCHEMA_GRANTS = (
    "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE ON `iot_platform`.* TO 'platform_migrator'@'localhost'\n"
    "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE ON `iot_platform_archive`.* TO 'platform_migrator'@'localhost'\n"
    "GRANT ALTER, DROP, INDEX, REFERENCES, CREATE VIEW, SHOW VIEW, TRIGGER, CREATE ROUTINE, ALTER ROUTINE, EXECUTE "
    "ON `iot_platform`.* TO 'platform_migrator'@'localhost'\n"
)


def migration_output(binlog=None, grants=SCHEMA_GRANTS):
    lines = [META]
    if binlog is not None:
        lines.extend(binlog if isinstance(binlog, list) else [binlog])
    return "\n".join(lines) + "\n" + grants


class TriggerPrivilegeTest(unittest.TestCase):
    def test_java_probe_keeps_meta_and_adds_read_only_binlog_status(self):
        probe = CREDENTIALS.JAVA_PROBE
        self.assertIn("SELECT @@server_uuid,@@gtid_mode,@@read_only,@@super_read_only,CURRENT_USER()", probe)
        self.assertIn('System.out.println("META\\t"', probe)
        self.assertIn("SELECT @@log_bin,@@log_bin_trust_function_creators", probe)
        self.assertIn('System.out.println("BINLOG\\t"', probe)

    def test_121_requires_super_only_when_binlog_is_on_and_trust_is_off(self):
        for enabled, trust in (("0", "0"), ("0", "1"), ("1", "1")):
            with self.subTest(log_bin=enabled, trust_creators=trust):
                self.assertEqual(CREDENTIALS.mysql_rights(
                    migration_output(f"BINLOG\t{enabled}\t{trust}"), "migration", "121"), IDENTITY)
        with self.assertRaisesRegex(RuntimeError, "BINLOG_SUPER_PRIVILEGE_MISSING"):
            CREDENTIALS.mysql_rights(migration_output("BINLOG\t1\t0"), "migration", "121")

    def test_global_super_or_global_all_satisfies_trigger_creation_precondition(self):
        for global_grant in ("GRANT SUPER ON *.* TO 'platform_migrator'@'localhost'\n",
                             "GRANT ALL PRIVILEGES ON *.* TO 'platform_migrator'@'localhost'\n"):
            with self.subTest(grant=global_grant):
                self.assertEqual(CREDENTIALS.mysql_rights(
                    migration_output("BINLOG\t1\t0", SCHEMA_GRANTS + global_grant), "migration", "121"), IDENTITY)

    def test_schema_scoped_super_does_not_satisfy_global_requirement(self):
        scoped = "GRANT SUPER ON `iot_platform`.* TO 'platform_migrator'@'localhost'\n"
        with self.assertRaisesRegex(RuntimeError, "BINLOG_SUPER_PRIVILEGE_MISSING"):
            CREDENTIALS.mysql_rights(migration_output("BINLOG\t1\t0", SCHEMA_GRANTS + scoped),
                                     "migration", "121")

    def test_missing_unknown_or_ambiguous_binlog_metadata_is_rejected_for_121(self):
        for metadata in (None, "BINLOG\t2\t0", "BINLOG\t1\tunknown",
                         ["BINLOG\t1\t0", "BINLOG\t1\t0"]):
            with self.subTest(metadata=metadata), self.assertRaises(RuntimeError):
                CREDENTIALS.mysql_rights(migration_output(metadata), "migration", "121")

    def test_older_targets_and_online_rehearsal_do_not_require_binlog_metadata(self):
        self.assertEqual(CREDENTIALS.mysql_rights(META + "\n" + SCHEMA_GRANTS,
                                                   "migration", "88"), IDENTITY)
        read_only_grants = ("GRANT SELECT ON `iot_platform`.* TO 'platform_migrator'@'localhost'\n"
                            "GRANT SELECT ON `iot_platform_archive`.* TO 'platform_migrator'@'localhost'\n")
        self.assertEqual(CREDENTIALS.mysql_rights(META + "\n" + read_only_grants,
                                                   "migration", "93", "APPLICATION_ONLY"), IDENTITY)


if __name__ == "__main__":
    unittest.main()
