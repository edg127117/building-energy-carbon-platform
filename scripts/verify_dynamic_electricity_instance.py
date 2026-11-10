"""Explicit independent-instance acceptance. Requires its D-drive ownership manifest.

Credentials stay in the instance's private local.json. The source archive and
approved V4 receipts are read-only; all writes target the selected local clone.
"""
from __future__ import annotations

import argparse
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import time
import urllib.error
import urllib.request


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--execute", action="store_true", required=True)
    parser.add_argument("--stage", choices=["all", "factors", "calculate", "verify"], default="all")
    args = parser.parse_args()
    root = args.root.resolve()
    if root.drive.upper() != "D:":
        raise ValueError("INDEPENDENT_D_DRIVE_INSTANCE_REQUIRED")
    config = json.loads((root / "private/local.json").read_text(encoding="utf-8"))
    if config["purpose"] != "DYNAMIC_CARBON_EA84_ONLY" or Path(config["runtimeRoot"]).resolve() != root:
        raise ValueError("INSTANCE_OWNERSHIP_MISMATCH")
    base = f"http://127.0.0.1:{config['ports']['api']}/api"
    building = config["buildingId"]
    fixture_file = root / "fixture/manifest.json"
    if not fixture_file.exists():
        raise ValueError("PREPARE_MIXED_GRAIN_FIXTURE_FIRST")
    fixture = json.loads(fixture_file.read_text(encoding="utf-8"))
    if fixture and (fixture["buildingId"] != building or fixture["meterCount"] != 3):
        raise ValueError("FIXTURE_IDENTITY_MISMATCH")
    api = f"/v1/carbon-management/buildings/{building}/dynamic-electricity"
    receipts = root / "acceptance"
    receipts.mkdir(exist_ok=True)
    secrets = [config.get(k, "") for k in ["mysqlPassword", "redisPassword", "appPassword"]]

    def request(path, body=None, token=None, timeout=360):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        payload = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
        command = urllib.request.Request(base + path, data=payload, headers=headers)
        try:
            with urllib.request.urlopen(command, timeout=timeout) as response:
                value = json.load(response)
        except urllib.error.HTTPError as error:
            detail = error.read().decode(errors="replace")
            for secret in secrets:
                if secret:
                    detail = detail.replace(secret, "[REDACTED]")
            raise RuntimeError(f"HTTP_{error.code}:{path}:{detail[:800]}") from None
        if value.get("code") not in (0, 200, None) or value.get("success") is False:
            raise RuntimeError(f"API_REJECTED:{path}:{value.get('errorCode')}")
        return value.get("data", value)

    tokens = {role: request("/auth/login", config["accounts"][role])["token"] for role in ["admin", "reviewer"]}
    for token in tokens.values():
        secrets.append(token)
    admin = tokens["admin"]

    manifest = json.loads((root / "factors/manifest.json").read_text(encoding="utf-8"))
    if args.stage in {"all", "factors"}:
        for name, digest in manifest["files"].items():
            content = (root / "factors" / name).read_bytes()
            if hashlib.sha256(content).hexdigest() != digest:
                raise ValueError("FACTOR_PACKAGE_HASH_MISMATCH")
            receipt = request(api + "/factors", json.loads(content), admin)
            (receipts / name.replace("factors-", "factor-import-")).write_text(json.dumps(receipt), encoding="utf-8")
    if args.stage in {"all", "calculate"}:
        for calculation in manifest["calculations"]:
            stamp = time.perf_counter()
            ids = request(api + "/calculations", {k: v for k, v in calculation.items() if k != "month"}, admin)
            (receipts / f"calculation-{calculation['month']}.json").write_text(
                json.dumps({"month": calculation["month"], "grain": calculation["grain"], "resultIds": ids,
                            "seconds": time.perf_counter() - stamp}), encoding="utf-8")
            print(json.dumps({"calculated": calculation["month"], "grain": calculation["grain"], "results": len(ids)}), flush=True)
    if args.stage not in {"all", "verify"}:
        print("INDEPENDENT_STAGE_COMPLETE:" + args.stage)
        return
    summaries = {}
    for year, expected in [(2025, 5), (2026, 10)]:
        view = request(api + f"?periodType=YEAR&period={year}", token=admin)
        complete = [p for p in view["points"] if p["status"] == "COMPLETE"]
        if len(complete) != expected:
            raise ValueError(f"ANNUAL_COVERAGE_MISMATCH:{year}:{[(p['period'],p['status']) for p in view['points']]}")
        for point in complete:
            quantity, emission, factor = (Decimal(point[k]) if point[k] is not None else None
                                          for k in ["electricityKwh", "emissionTco2", "factorKgCo2PerKwh"])
            if quantity and abs(quantity * factor - emission * 1000) > Decimal("0.000000001"):
                raise ValueError("ANNUAL_RESULT_CONSERVATION_FAILED")
            if fixture and abs(quantity - Decimal(fixture["expectedMonthlyKwh"][point["period"]])) > Decimal("0.000001"):
                raise ValueError("SOURCE_BUILDING_ELECTRICITY_NOT_CONSERVED")
        summaries[str(year)] = view
    for month in ["2026-01", "2026-09", "2026-10"]:
        view = request(api + f"?periodType=MONTH&period={month}", token=admin)
        if not all(point["status"] == "COMPLETE" for point in view["points"]):
            raise ValueError(f"DAILY_COVERAGE_MISMATCH:{month}")
        parent = next(p for p in summaries["2026"]["points"] if p["period"] == month)
        for key in ["electricityKwh", "emissionTco2"]:
            if abs(sum(Decimal(p[key]) for p in view["points"]) - Decimal(parent[key])) > Decimal("0.000000001"):
                raise ValueError("DAILY_MONTH_RESULT_MISMATCH")
        summaries[month] = view
    # A monthly/day source must never manufacture finer monitoring results.
    for date, expected in [("2026-01-01", "COMPLETE"), ("2026-09-01", "MISSING"), ("2026-10-01", "COMPLETE")]:
        view = request(api + f"/hourly?date={date}", token=admin)
        if len(view["points"]) != 24 or any(point["status"] != expected for point in view["points"]):
            raise ValueError(f"HOURLY_GRAIN_CONTRACT_FAILED:{date}")
        summaries[date] = view
    february = request(api + "?periodType=MONTH&period=2026-02", token=admin)
    if any(point["status"] != "MISSING" or point["emissionTco2"] is not None for point in february["points"]):
        raise ValueError("MONTHLY_SOURCE_MANUFACTURED_DAILY_RESULTS")
    for year, first_missing in [(2025, 5), (2026, 10)]:
        if any(point["emissionTco2"] is not None or point["factorKgCo2PerKwh"] is not None
               for point in summaries[str(year)]["points"][first_missing:]):
            raise ValueError("UNAVAILABLE_PERIOD_PRESENTED_AS_VALUE")
    first = summaries["2026"]["points"][8]
    evidence = request(api + f"/results/{first['resultIds'][0]}/evidence", token=admin)
    if evidence["building"] != building or evidence["grain"] != "DAY" or evidence["values"]["sourceNatures"] != ["SIMULATED"]:
        raise ValueError("RESULT_EVIDENCE_IDENTITY_MISMATCH")
    summaries["evidenceSample"] = evidence
    # Compare the original copied building against receipts captured before the fixture.
    # This reads existing frozen reports; it does not republish formal calculations.
    original_months = sorted((root / "fixture").glob("source-month-*.json"))
    if len(original_months) != 15:
        raise ValueError("ORIGINAL_MONTHLY_BASELINE_REQUIRED")
    for original_file in original_months:
        baseline = json.loads(original_file.read_text(encoding="utf-8"))
        month = original_file.stem.removeprefix("source-month-")
        current = request(f"/v1/energy-reporting/buildings/{fixture['sourceBuildingId']}"
                          f"/monthly-comparison?month={month}-01&timezoneId=Asia%2FShanghai", token=admin)
        def metrics(report):
            return sorted((point["metricCode"], point["unit"], point["value"], point["status"], point.get("sourceBatchId"))
                          for point in report["current"]["metrics"])
        if metrics(current) != metrics(baseline):
            raise ValueError("ORIGINAL_FROZEN_REPORT_CHANGED:" + month)
    summaries["originalFrozenMonthlyComparison"] = {"months": len(original_months), "status": "UNCHANGED"}
    (receipts / "summary.json").write_text(json.dumps(summaries, indent=2), encoding="utf-8")
    print("INDEPENDENT_DYNAMIC_CHAIN_ACCEPTANCE_OK")


if __name__ == "__main__":
    main()
