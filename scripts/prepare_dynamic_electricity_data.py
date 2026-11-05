"""Prepare reproducible factor uploads from the approved archive; never writes a database.

The source grid label is explicitly treated as an interval start for isolated tests.
It does not establish the original meters' sampling-boundary convention.
"""
from __future__ import annotations

import argparse
import calendar
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import io
import json
from pathlib import Path
import zipfile


ZONE = timezone(timedelta(hours=8))
QUANTUM = Decimal("0.000000000000000001")


def decimal_text(value: Decimal) -> str:
    return format(value.quantize(QUANTUM, rounding=ROUND_HALF_UP), "f")


def utc(value: datetime) -> str:
    return value.replace(tzinfo=ZONE).astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def read_source(archive: Path) -> tuple[str, dict[int, list[tuple[datetime, Decimal]]]]:
    import openpyxl

    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    months = {}
    with zipfile.ZipFile(archive) as package:
        for month in range(1, 6):
            matches = [name for name in package.namelist() if f"2025年{month:02d}月" in name and name.endswith(".xlsx")]
            if len(matches) != 1:
                raise ValueError(f"SOURCE_MONTH_NOT_UNIQUE:{month}")
            workbook = openpyxl.load_workbook(io.BytesIO(package.read(matches[0])), read_only=True, data_only=True)
            try:
                sheet = workbook["不考虑外送水电_15min明细"]
                rows = list(sheet.iter_rows(min_row=2, values_only=True))
                expected = calendar.monthrange(2025, month)[1] * 96
                if len(rows) != expected:
                    raise ValueError(f"SOURCE_MONTH_INCOMPLETE:{month}")
                values = []
                for index, row in enumerate(rows):
                    timestamp = datetime(2025, month, 1) + timedelta(minutes=index * 15)
                    if row[0] != timestamp or row[10] is None or row[8] is None or row[8] <= 0:
                        raise ValueError(f"SOURCE_INTERVAL_INVALID:{month}:{index}")
                    factor = Decimal(str(row[10]))
                    if factor < 0 or abs(Decimal(str(row[9])) / Decimal(str(row[8])) - factor) > Decimal("0.000000001"):
                        raise ValueError(f"SOURCE_FACTOR_MISMATCH:{month}:{index}")
                    values.append((timestamp, factor))
                months[month] = values
            finally:
                workbook.close()
    return digest, months


def observation(start: datetime, end: datetime, factor: Decimal) -> dict:
    return {"pointId": "*", "startInclusive": utc(start), "endExclusive": utc(end),
            "factorKgCo2PerKwh": decimal_text(factor)}


def payloads(digest: str, source: dict[int, list[tuple[datetime, Decimal]]]) -> tuple[list[dict], list[dict]]:
    imports, calculations = [], []
    for year, months in [(2025, range(1, 6)), (2026, range(1, 11))]:
        for month in months:
            grain = "HOUR" if (year, month) in {(2025, 2), (2026, 1), (2026, 10)} else "DAY" if (year, month) == (2026, 9) else "MONTH"
            first = datetime(year, month, 1)
            end = first + timedelta(days=calendar.monthrange(year, month)[1])
            key = f"{year}-{month:02d}"
            if year == 2025:
                values = [observation(t, t + timedelta(minutes=15), f) for t, f in source[month]]
                nature = "SOURCE_LABELLED"
                recipe = "SOURCE_GRID_START_TEST_V1"
            else:
                # A deterministic test curve, not a prediction of the target region/year.
                template = 1 if month == 1 else 4 if month == 9 else min(month, 5)
                grouped = defaultdict(list)
                for stamp, factor in source[template]:
                    bucket = (stamp.day, stamp.hour) if grain == "HOUR" else (stamp.day,) if grain == "DAY" else (0,)
                    grouped[bucket].append(factor)
                values = []
                cursor = first
                while cursor < end:
                    step = timedelta(hours=1) if grain == "HOUR" else timedelta(days=1) if grain == "DAY" else end - first
                    bucket = (cursor.day, cursor.hour) if grain == "HOUR" else (cursor.day,) if grain == "DAY" else (0,)
                    samples = grouped[bucket]
                    factor = sum(samples) / len(samples) * (Decimal(1) + Decimal(month - 1) / Decimal(300))
                    values.append(observation(cursor, cursor + step, factor))
                    cursor += step
                nature = "SIMULATED"
                recipe = f"FIXED_TEST_PROFILE_V1:TEMPLATE_2025_{template:02d}:SCALE_1_PLUS_MONTH_MINUS_1_DIV_300"
            imports.append({"month": key, "request": {
                "idempotencyKey": f"dynamic-{key}-v1", "sourceReference": f"ARCHIVE_SHA256:{digest};{recipe}",
                "sourceRegion": "420000", "scenario": "NO_EXTERNAL_HYDRO", "sourceNature": nature,
                "unit": "kgCO2/kWh", "observations": values}})
            calculations.append({"month": key, "grain": grain, "startInclusive": utc(first), "endExclusive": utc(end)})
    return imports, calculations


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    destination = args.output.resolve()
    if destination.drive.upper() != "D:":
        raise ValueError("TEST_DATA_OUTPUT_MUST_BE_ON_D_DRIVE")
    digest, source = read_source(args.archive)
    imports, calculations = payloads(digest, source)
    destination.mkdir(parents=True, exist_ok=False)
    for item in imports:
        (destination / f"factors-{item['month']}.json").write_text(json.dumps(item["request"], ensure_ascii=False, indent=2), encoding="utf-8")
    manifest = {"purpose": "ISOLATED_DYNAMIC_ELECTRICITY_TEST_ONLY", "archiveSha256": digest,
                "gasCoverage": "CO2_ONLY", "sourceRegion": "420000", "scenario": "NO_EXTERNAL_HYDRO",
                "sourceMonths": [f"2025-{m:02d}" for m in range(1, 6)],
                "simulatedMonths": [f"2026-{m:02d}" for m in range(1, 11)], "calculations": calculations,
                "files": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(destination.glob("factors-*.json"))}}
    (destination / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"months": len(imports), "factorIntervals": sum(len(i["request"]["observations"]) for i in imports), "output": str(destination)}))


if __name__ == "__main__":
    main()
