"""Static contract checks for the isolated hourly monitoring invalidation migration."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "src/env/init/V119__mysql_hourly_monitoring.sql"


class HourlyMonitoringMigrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")

    def test_rule_sources_have_insert_update_delete_triggers(self):
        tables = (
            "biz_carbon_factor_version",
            "biz_carbon_factor_component",
            "biz_carbon_factor_source",
            "biz_carbon_factor_source_version",
            "biz_carbon_formula_version",
            "biz_carbon_gwp_version",
            "biz_carbon_rounding_policy_version",
            "building",
            "biz_energy_meter_event",
            "biz_energy_meter_event_version",
            "biz_energy_activity_correction",
            "biz_energy_activity_correction_version",
        )
        for table in tables:
            with self.subTest(table=table):
                for action in ("INSERT", "UPDATE", "DELETE"):
                    self.assertRegex(
                        self.sql,
                        rf"CREATE TRIGGER `?tr_hourly_rule_[a-z_]+`? AFTER {action} ON {table}\b",
                    )

    def test_metadata_invalidates_only_hourly_rule_domain(self):
        self.assertIsNotNone(
            re.search(
                r"CREATE PROCEDURE bump_hourly_monitor_rule\(IN p_building VARCHAR\(32\)\).*?"
                r"CALL bump_carbon_display_revision\(COALESCE\(NULLIF\(p_building,''\),'\*'\),"
                r"'HOURLY_RULE','\*'\)",
                self.sql,
                re.S,
            )
        )
        self.assertNotRegex(
            self.sql,
            r"bump_hourly_monitor_rule\([^;]*\);[^\n]*bump_carbon_display_revision\([^;]*"
            r"'(?:DAILY_INPUT|DYNAMIC_INPUT|DAILY_RESULT|DYNAMIC_RESULT)'",
        )

    def test_trigger_targets_exist_in_repository_migrations(self):
        all_migrations = "\n".join(
            path.read_text(encoding="utf-8")
            for path in (ROOT / "src/env/init").glob("V*.sql")
        )
        for table in (
            "biz_carbon_factor_version",
            "biz_carbon_factor_component",
            "biz_carbon_factor_source",
            "biz_carbon_factor_source_version",
            "biz_carbon_formula_version",
            "biz_carbon_gwp_version",
            "biz_carbon_rounding_policy_version",
            "building",
            "biz_energy_meter_event",
            "biz_energy_meter_event_version",
            "biz_energy_activity_correction",
            "biz_energy_activity_correction_version",
        ):
            with self.subTest(table=table):
                self.assertRegex(all_migrations, rf"CREATE TABLE(?: IF NOT EXISTS)? `?{table}`?\b")

    def test_trigger_row_columns_exist(self):
        all_migrations = "\n".join(
            path.read_text(encoding="utf-8")
            for path in (ROOT / "src/env/init").glob("V*.sql")
        )
        tables = {
            "biz_carbon_factor_version": {"building_id", "factor_version_id"},
            "biz_carbon_factor_component": {"factor_version_id"},
            "building": {"building_id", "region_code", "del_flag"},
            "biz_energy_meter_event": {"event_id", "building_id"},
            "biz_energy_meter_event_version": {"event_id"},
            "biz_energy_activity_correction": {"correction_id", "building_id"},
            "biz_energy_activity_correction_version": {"correction_id"},
        }
        for table, columns in tables.items():
            ddl = re.search(
                rf"CREATE TABLE(?: IF NOT EXISTS)? `?{table}`?\s*\((.*?)\)\s*ENGINE=",
                all_migrations,
                re.S,
            )
            self.assertIsNotNone(ddl, table)
            actual = set(re.findall(r"(?m)^\s*`([^`]+)`\s+[A-Z]", ddl.group(1)))
            with self.subTest(table=table):
                self.assertTrue(columns <= actual, f"missing {columns - actual}")

    def test_raw_window_keeps_hourly_boundary_and_six_minute_scope(self):
        self.assertIn("p_domain IN ('DYNAMIC_RAW','DYNAMIC_INPUT','DAILY_INPUT')", self.sql)
        self.assertIn("FLOOR((p_from_ms-IF(p_domain='DYNAMIC_RAW',1,0))/3600000)*3600000", self.sql)
        self.assertIn("FLOOR((p_to_ms+360000-1)/3600000)*3600000", self.sql)
        self.assertIn("INSERT INTO biz_hourly_monitor_revision(building_id,hour_ms,revision)", self.sql)
        self.assertNotRegex(self.sql, r"(?i)temperature|outdoor_temp|weather")

    def test_reservation_waits_until_pending_before_locking_revision(self):
        self.assertRegex(self.sql, r"(?s)CREATE TRIGGER tr_display_raw_intent_ai.*?"
                         r"IF NEW.storage_state <> 'RESERVING' THEN\s+"
                         r"CALL bump_display_raw_electricity\(NEW.building_id,NEW.point_id,NEW.event_ms\)")


if __name__ == "__main__":
    unittest.main()
