"""The approved coverage and deterministic simulation must not silently grow."""
import importlib.util
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("dynamic_data", Path(__file__).parents[1] / "prepare_dynamic_electricity_data.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DynamicFactorDataTest(unittest.TestCase):
    def test_coverage_reproducibility_and_no_september_hours(self):
        source = {}
        for month in range(1, 6):
            first = datetime(2025, month, 1)
            following = datetime(2025, month + 1, 1)
            source[month] = [(first + timedelta(minutes=i * 15), Decimal("0.2") + Decimal(i % 96) / 1000)
                             for i in range((following - first).days * 96)]
        imports, calculations = MODULE.payloads("a" * 64, source)
        self.assertEqual((imports, calculations), MODULE.payloads("a" * 64, source))
        self.assertEqual(len(imports), 15)
        self.assertEqual({c["month"] for c in calculations if c["grain"] == "HOUR"}, {"2025-02", "2026-01", "2026-10"})
        september = next(i["request"] for i in imports if i["month"] == "2026-09")
        self.assertEqual(len(september["observations"]), 30)
        self.assertEqual(september["sourceNature"], "SIMULATED")
        self.assertTrue(all(len(i["request"]["observations"]) == 1 for i in imports if "2026-02" <= i["month"] <= "2026-08"))
        self.assertNotIn("2025-06", {i["month"] for i in imports})


if __name__ == "__main__":
    unittest.main()
