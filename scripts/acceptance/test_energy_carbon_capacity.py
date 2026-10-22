"""仅验证容量脚本的本地参数和脱敏逻辑；不启动 HTTP 服务或真实负载。"""

from __future__ import annotations

import importlib.util
import json
import os
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path
from types import SimpleNamespace


MODULE_PATH = Path(__file__).with_name("energy-carbon-capacity.py")
SPEC = importlib.util.spec_from_file_location("energy_carbon_capacity", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
capacity = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = capacity
SPEC.loader.exec_module(capacity)


class EnergyCarbonCapacityScriptTest(unittest.TestCase):
    def test_base_url_only_accepts_literal_loopback(self) -> None:
        self.assertEqual(
            "http://127.0.0.1:18081/api",
            capacity.validate_loopback_base_url("http://127.0.0.1:18081/api/"),
        )
        for value in ("http://localhost:18081/api", "http://[::1]:18081/api", "http://10.0.0.2:18081/api", "http://127.0.0.1/api"):
            with self.assertRaises(capacity.ConfigurationError):
                capacity.validate_loopback_base_url(value)

    def test_concurrency_steps_are_positive_and_unique(self) -> None:
        self.assertEqual((10, 25, 50), capacity.parse_concurrency_steps("10,25,50"))
        for value in ("", "10,10", "0,10", "201"):
            with self.assertRaises(capacity.ConfigurationError):
                capacity.parse_concurrency_steps(value)

    def test_redaction_never_returns_secret_value(self) -> None:
        original = {"token": "jwt-value", "nested": {"dbPassword": "p", "cpu": 33}}
        redacted = capacity.redact_value(original)
        self.assertEqual("[REDACTED]", redacted["token"])
        self.assertEqual("[REDACTED]", redacted["nested"]["dbPassword"])
        self.assertEqual(33, redacted["nested"]["cpu"])

    def test_monitor_extraction_keeps_only_numeric_runtime_metrics(self) -> None:
        metrics = capacity.flattened_numeric_metrics({
            "hikari": {"active": 3, "password": "do-not-write"},
            "service": {"status": "UP"},
            "process": {"cpu": "18.5", "pid": 1234},
        })
        self.assertEqual({"hikari.active": 3.0, "process.cpu": 18.5, "process.pid": 1234.0}, metrics)

    def test_percentile_uses_documented_nearest_rank(self) -> None:
        self.assertEqual(5.0, capacity.EndpointStats.percentile([1, 2, 3, 4, 5], 0.95))
        self.assertEqual(3.0, capacity.EndpointStats.percentile([1, 2, 3, 4, 5], 0.50))

    def test_local_dry_run_plan_requires_distinct_environment_credentials(self) -> None:
        params = {
            "isolated": True,
            "base_url": "http://127.0.0.1:18081/api",
            "accounts": [
                {"username_env": "CAPACITY_TEST_USER_1", "password_env": "CAPACITY_TEST_PASSWORD_1"},
                {"username_env": "CAPACITY_TEST_USER_2", "password_env": "CAPACITY_TEST_PASSWORD_2"},
            ],
            "ids": {
                "building_id": "building-a",
                "project_version_id": "project-a",
                "calculation_batch_id": "batch-a",
                "calculation_item_id": "item-a",
            },
        }
        environment = {
            "CAPACITY_TEST_USER_1": "capacity-user-1",
            "CAPACITY_TEST_PASSWORD_1": "not-recorded",
            "CAPACITY_TEST_USER_2": "capacity-user-2",
            "CAPACITY_TEST_PASSWORD_2": "not-recorded",
        }
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, environment, clear=False):
            params_file = Path(temporary) / "capacity-params.json"
            params_file.write_text(json.dumps(params), encoding="utf-8")
            args = capacity.build_parser().parse_args([
                "--params-file", str(params_file), "--confirm-isolated-local-app", "--concurrency", "1,2", "--dry-run",
            ])
            plan = capacity.load_plan(args)
        self.assertEqual((1, 2), plan.concurrency_steps)
        self.assertEqual(2, len(plan.accounts))
        self.assertFalse(plan.replay.enabled)

    def test_calculation_replay_needs_configuration_and_cli_lock(self) -> None:
        identifiers = capacity.Identifiers("building-a", "project-a", "batch-a", "item-a")
        replay = {
            "enabled": True,
            "max_requests": 2,
            "every_n_read_cycles": 3,
            "request": {
                "buildingId": "building-a", "periodType": "MONTH", "startInclusive": "2026-01-01T00:00:00Z",
                "endExclusive": "2026-02-01T00:00:00Z", "timezoneId": "Asia/Shanghai",
                "resultNature": "OFFICIAL", "idempotencyKey": "isolated-capacity-replay",
            },
        }
        with self.assertRaises(capacity.ConfigurationError):
            capacity.parse_replay(replay, False, identifiers, 1)
        self.assertTrue(capacity.parse_replay(replay, True, identifiers, 1).enabled)

    def test_jsonl_request_evidence_is_sampled_and_bounded(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            recorder = capacity.JsonlRecorder(Path(temporary), success_sample_every=10,
                                               max_success_events=2, max_error_events=1)
            success = capacity.HttpOutcome(True, 200, 2.0, None, {})
            failure = capacity.HttpOutcome(False, 500, 3.0, "HTTP_500")
            for _ in range(30):
                recorder.record("concurrency-10", "fixed_carbon_report", success)
            for _ in range(2):
                recorder.record("concurrency-10", "fixed_carbon_report", failure)
            sampling = recorder.sampling_snapshot()
            recorder.close()
            events = (Path(temporary) / "events.jsonl").read_text(encoding="utf-8").splitlines()
        self.assertEqual(3, len(events))
        self.assertEqual(2, sampling["emitted_success_request_events"])
        self.assertEqual(1, sampling["emitted_error_request_events"])
        self.assertGreater(sampling["omitted_success_request_events"], 0)
        self.assertEqual(1, sampling["omitted_error_request_events"])

    def test_error_class_cardinality_stays_bounded(self) -> None:
        stats = capacity.EndpointStats()
        for index in range(capacity.MAX_ERROR_CLASSES_PER_ENDPOINT + 10):
            stats.record(capacity.HttpOutcome(False, 500, 1.0, f"ERROR_{index}"), ())
        error_classes = stats.snapshot()["error_classes"]
        self.assertLessEqual(len(error_classes), capacity.MAX_ERROR_CLASSES_PER_ENDPOINT)
        self.assertIn("OTHER_ERROR_CLASS", error_classes)

    def test_http_and_acceptance_success_rates_are_distinct(self) -> None:
        stats = capacity.EndpointStats()
        stats.record(capacity.HttpOutcome(False, 200, 1.0, "APPLICATION_REJECTED"), ())
        snapshot = stats.snapshot()
        self.assertEqual(1.0, snapshot["http_success_rate"])
        self.assertEqual(0.0, snapshot["acceptance_success_rate"])

    def test_evidence_output_cannot_leave_workspace(self) -> None:
        local = capacity.workspace_output_path("target/capacity-script-test")
        self.assertEqual(capacity.WORKSPACE_ROOT / "target" / "capacity-script-test", local)
        for path in (str(capacity.WORKSPACE_ROOT.parent / "outside-workspace"), r"\\server\share\evidence"):
            with self.assertRaises(capacity.ConfigurationError):
                capacity.workspace_output_path(path)

    def test_work_order_baseline_is_per_account_and_still_detects_later_drift(self) -> None:
        plan = SimpleNamespace(identifiers=capacity.Identifiers("building-a", "project-a", "batch-a", "item-a"),
                               base_url="http://127.0.0.1:18081/api", request_timeout_seconds=5,
                               max_response_bytes=4096)
        baseline = capacity.Baseline("class-a", "report-hash", "10", "COMPLETE", "evidence-hash", None,
                                     "manager-fingerprint")
        sessions = [capacity.AccountSession("manager"), capacity.AccountSession("owner")]
        with tempfile.TemporaryDirectory() as temporary:
            recorder = capacity.JsonlRecorder(Path(temporary))
            with patch.object(capacity, "ApiClient") as client:
                client.return_value.request.return_value = capacity.HttpOutcome(
                    True, 200, 2, None, {"fingerprint": "owner-fingerprint"})
                baselines = capacity.collect_account_baselines(plan, sessions, baseline, recorder)
                self.assertEqual(1, client.return_value.request.call_count)
                self.assertEqual("owner", client.return_value.request.call_args.args[2])
            recorder.close()
        self.assertEqual("manager-fingerprint", baselines[0].work_order_fingerprint)
        self.assertEqual("owner-fingerprint", baselines[1].work_order_fingerprint)
        self.assertEqual(baselines[0].report_hash, baselines[1].report_hash)
        self.assertEqual((), capacity.validate_endpoint_data(
            "work_order_read", {"fingerprint": "owner-fingerprint"}, plan, baselines[1]))
        self.assertEqual(("WORK_ORDER_FINGERPRINT_DRIFT",), capacity.validate_endpoint_data(
            "work_order_read", {"fingerprint": "unexpected-change"}, plan, baselines[1]))


if __name__ == "__main__":
    unittest.main()
