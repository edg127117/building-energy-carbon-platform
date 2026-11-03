"""Mock-only tests for the carbon display readiness poller."""

from __future__ import annotations

import contextlib
import io
import os
import unittest
import urllib.error
from unittest import mock

import check_carbon_display_readiness as readiness


BUILDING = "building-42"
TOKEN = "test-token-must-not-be-printed"


def valid_payload(building_id: str = BUILDING) -> dict:
    return {
        "code": 200,
        "data": {
            "buildingId": building_id,
            "scope": readiness.EXPECTED_SCOPE,
            "configured": True,
            "state": "READY",
            "ready": True,
            "items": [
                {"code": code, "state": "READY", "ready": True,
                 "businessState": "AVAILABLE"}
                for code in readiness.EXPECTED_ITEMS
            ],
        },
    }


class FakeTime:
    def __init__(self) -> None:
        self.now = 0.0
        self.sleeps: list[float] = []

    def clock(self) -> float:
        return self.now

    def wait(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.now += seconds


class CarbonDisplayReadinessTests(unittest.TestCase):
    def run_check(self, payloads, *, timeout=120, errors=None):
        fake_time = FakeTime()
        calls = []
        next_payload = iter(payloads)
        next_error = iter(errors or [])

        def request(url, token, timeout_seconds):
            calls.append((url, token, timeout_seconds))
            error = next(next_error, None)
            if error is not None:
                raise error
            return next(next_payload)

        readiness.check_readiness(
            "https://example.test/api", BUILDING, TOKEN, timeout,
            request=request, clock=fake_time.clock, wait=fake_time.wait,
        )
        return calls, fake_time

    def test_success_requires_all_four_items(self):
        calls, _ = self.run_check([valid_payload()])
        self.assertEqual(1, len(calls))
        self.assertEqual(TOKEN, calls[0][1])
        self.assertTrue(calls[0][0].endswith(f"/buildings/{BUILDING}/readiness"))

    def test_wrong_building_and_contract_fail(self):
        for payload in (valid_payload("other"), {**valid_payload(), "code": 201}):
            with self.subTest(payload=payload):
                with self.assertRaises(readiness.ReadinessError):
                    self.run_check([payload])

    def test_top_level_ready_cannot_hide_unready_items(self):
        payload = valid_payload()
        payload["data"]["state"] = "NOT_READY"
        payload["data"]["ready"] = False
        payload["data"]["items"][0]["ready"] = False
        payload["data"]["items"][0]["errorCode"] = "ACTUAL_UNAVAILABLE"
        with self.assertRaisesRegex(readiness.ReadinessError, "ACTUAL_UNAVAILABLE"):
            self.run_check([payload])

    def test_request_that_consumes_entire_budget_cannot_succeed(self):
        fake_time = FakeTime()

        def slow_request(url, token, timeout_seconds):
            self.assertEqual(3.0, timeout_seconds)
            fake_time.now += 3.0
            return valid_payload()

        with self.assertRaisesRegex(readiness.ReadinessError, "时间预算"):
            readiness.check_readiness(
                "https://example.test", BUILDING, TOKEN, 3,
                request=slow_request, clock=fake_time.clock, wait=fake_time.wait,
            )

    def test_preparing_times_out_with_total_budget(self):
        payload = valid_payload()
        payload["data"]["state"] = "PREPARING"
        payload["data"]["ready"] = False
        payload["data"]["items"][0]["state"] = "PREPARING"
        payload["data"]["items"][0]["ready"] = False
        fake_time = FakeTime()
        calls = []

        def request(url, token, timeout_seconds):
            calls.append(timeout_seconds)
            return payload

        with self.assertRaisesRegex(readiness.ReadinessError, "时间预算"):
            readiness.check_readiness(
                "https://example.test", BUILDING, TOKEN, 3,
                request=request, clock=fake_time.clock, wait=fake_time.wait,
            )
        self.assertEqual([3.0, 1.0], calls)
        self.assertEqual([2.0, 1.0], fake_time.sleeps)

    def test_401_and_403_fail_without_retry(self):
        for status in (401, 403):
            calls = []

            def request(url, token, timeout_seconds):
                calls.append(status)
                raise urllib.error.HTTPError(url, status, "private body", {}, None)

            output = io.StringIO()
            with mock.patch.dict(os.environ, {"SCREEN_READINESS_TOKEN": TOKEN}):
                with contextlib.redirect_stdout(output):
                    result = readiness.main(
                        ["--api-base", "https://example.test", "--building-id", BUILDING],
                        request=request, clock=lambda: 0.0, wait=lambda _: None,
                    )
            self.assertEqual(1, result)
            self.assertEqual([status], calls)
            self.assertIn(f"HTTP {status}", output.getvalue())
            self.assertNotIn("private body", output.getvalue())

    def test_unconfigured_and_no_closed_month_fail_immediately(self):
        unconfigured = valid_payload()
        unconfigured["data"].update(configured=False, state="NOT_CONFIGURED", items=[])
        no_month = valid_payload()
        no_month["data"]["items"][0].update(
            state="NOT_APPLICABLE", ready=False, errorCode="NO_CLOSED_MONTH"
        )
        for payload, reason in ((unconfigured, "未配置"), (no_month, "已结束月份")):
            with self.subTest(reason=reason):
                with self.assertRaisesRegex(readiness.ReadinessError, reason):
                    self.run_check([payload])

    def test_redirects_are_disabled(self):
        handler = readiness._NoRedirect()
        self.assertIsNone(handler.redirect_request(None, None, 302, "Found", {}, "https://other.test"))

    def test_rejects_credentials_and_remote_http(self):
        for url in ("https://user:secret@example.test", "http://example.test"):
            with self.subTest(url=url):
                with self.assertRaises(readiness.ReadinessError):
                    readiness.validate_api_base(url)

    def test_remote_http_loopback_allowed_and_query_rejected(self):
        self.assertEqual("http://127.0.0.1:8080", readiness.validate_api_base("http://127.0.0.1:8080/"))
        with self.assertRaises(readiness.ReadinessError):
            readiness.validate_api_base("https://example.test?secret=value")

    def test_token_never_printed_and_only_read_from_environment(self):
        payload = valid_payload()
        output = io.StringIO()
        with mock.patch.dict(os.environ, {"SCREEN_READINESS_TOKEN": TOKEN}):
            with contextlib.redirect_stdout(output):
                result = readiness.main(
                    ["--api-base", "https://example.test", "--building-id", BUILDING],
                    request=lambda url, token, timeout: payload,
                    clock=lambda: 0.0, wait=lambda _: None,
                )
        self.assertEqual(0, result)
        self.assertNotIn(TOKEN, output.getvalue())


if __name__ == "__main__":
    unittest.main()
