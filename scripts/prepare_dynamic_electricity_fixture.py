"""Create a small mixed-grain test building through existing governance APIs.

The copied 219-point building remains intact. A main meter and two child meters
reuse its building-level energy totals, so monthly and daily source facts do not
overlap. All new assets and sources are explicitly simulated test objects.
"""
import argparse
import calendar
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import gzip
import json
from pathlib import Path
import urllib.request
import urllib.error

ZONE = timezone(timedelta(hours=8))
WHY = "DYNAMIC_EA84_TEST_ONLY;SIMULATED;MAIN_PLUS_TWO_SUBMETERS;NOT_AS_BUILT"
FIRST = "2024-12-31T16:00:00Z"
LAST = "2026-10-31T16:00:00Z"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--v4-source", type=Path)
    parser.add_argument("--live-meters", type=int, choices=[100], help="Prepare a separate 100-meter upload fixture without historical readings")
    parser.add_argument("--execute", action="store_true", required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    config = json.loads((root / "private/local.json").read_text(encoding="utf-8"))
    if root.drive.upper() != "D:" or config["purpose"] != "DYNAMIC_CARBON_EA84_ONLY" or Path(config["runtimeRoot"]).resolve() != root:
        raise ValueError("INDEPENDENT_INSTANCE_REQUIRED")
    global WHY
    meter_count = args.live_meters or 3
    if args.live_meters:
        WHY = "DYNAMIC_100_TEST_ONLY;SIMULATED;MAIN_PLUS_99_SUBMETERS;NOT_AS_BUILT"
    out = root / ("live-fixture" if args.live_meters else "fixture")
    out.mkdir(exist_ok=True)
    base = f"http://127.0.0.1:{config['ports']['api']}/api"
    tokens = {}

    def call(path, body=None, role="admin", method=None, key=None):
        headers = {"Content-Type": "application/json"}
        if role in tokens:
            headers["Authorization"] = "Bearer " + tokens[role]
        if key:
            headers["Idempotency-Key"] = ("DYN_LIVE_" if args.live_meters else "DYN_FIXTURE_") + key
        data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
        try:
            with urllib.request.urlopen(urllib.request.Request(base + path, data=data, headers=headers, method=method), timeout=180) as response:
                value = json.load(response)
        except urllib.error.HTTPError as error:
            value = json.loads(error.read())
            raise RuntimeError(f"HTTP_{error.code}:{path}:{value.get('errorCode')}:{value.get('msg')}") from None
        if value.get("success") is False or value.get("code") not in (None, 0, 200):
            raise RuntimeError(f"API_REJECTED:{path}:{value.get('errorCode')}")
        return value.get("data", value)

    for role in ["admin", "reviewer"]:
        tokens[role] = call("/auth/login", config["accounts"][role], role)["token"]

    def keep(name, path, body=None, role="admin", method=None, key=None):
        file = out / (name + ".json")
        if file.exists():
            return json.loads(file.read_text(encoding="utf-8"))
        value = call(path, body, role, method, key)
        file.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        return value

    if args.live_meters:
        original_fixture = root / "fixture"
        original_building = json.loads((original_fixture / "building.json").read_text(encoding="utf-8"))
        original_building["buildingId"] = config["sourceBuildingId"]
        bindings = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(original_fixture.glob("binding-approved-0-*.json"))]
        assets = {"building": original_building, "bindings": {"template": bindings}}
    else:
        if args.v4_source is None:
            raise ValueError("V4_SOURCE_REQUIRED_FOR_HISTORICAL_FIXTURE")
        assets = json.loads((args.v4_source / "assets.json").read_text(encoding="utf-8-sig"))
    original = assets["building"]
    source_building = original["buildingId"]
    if config.get("sourceBuildingId", config["buildingId"]) != source_building:
        raise ValueError("SOURCE_BUILDING_MISMATCH")
    body = {k: original.get(k) for k in ["buildingType", "constructionYear", "climateZone", "regionCode", "carbonEvaluationCategory", "carbonEvaluationSubcategory"]}
    body.update(buildingName="动态碳百表独立测试" if args.live_meters else "创新港动态碳链路独立测试", buildingCode="CXG_DYNAMIC_100_TEST" if args.live_meters else "CXG_DYNAMIC_EA84_TEST", totalGfa=original["totalGfa"], status="ACTIVE")
    building = keep("building", "/v1/assets/buildings", body)
    b = building["buildingId"]
    group = keep("group", "/v1/assets/system-groups", {"buildingId": b, "systemCode": "DYNAMIC_TEST_POWER", "systemName": "动态碳测试电力", "systemType": "ELECTRICITY", "status": "ACTIVE"})
    template = next(iter(assets["bindings"].values()))
    spaces, meters = [], []
    for index in range(meter_count):
        space = keep(f"space-{index}", "/v1/assets/spaces", {"buildingId": b, "spaceCode": f"DYN_{index}", "spaceName": f"测试计量范围{index}", "spaceType": "FLOOR" if index == 0 else "ZONE", "parentSpaceId": spaces[0]["spaceId"] if index else None, "status": "ACTIVE"})
        equipment = keep(f"equipment-{index}", "/v1/assets/equipment", {"buildingId": b, "spaceId": space["spaceId"], "systemGroupId": group["systemGroupId"], "typeCode": "ELECTRIC_METER_3P", "equipmentName": f"动态测试表{index}", "status": "ACTIVE"})
        point = keep(f"point-{index}", "/datapoint/add", {"buildingId": b, "pointCode": f"EM3P{(992000 if args.live_meters else 991000) + index}_EPP", "pointName": f"动态测试表{index}", "equipId": equipment.get("equipId") or equipment["equipmentId"], "systemGroupId": group["systemGroupId"], "namingRuleId": "RULE_EM3P_MAIN", "familyCode": "ELECTRIC_METER_3P", "componentCode": "MAIN", "suffixCode": "EPP", "dataType": "ACCUMULATE", "unit": "kWh", "isForCalc": 1, "status": "ONLINE"})
        pid = point["pointId"]
        keep(f"profile-{index}", "/v1/energy-point-profiles", {"buildingId": b, "pointId": pid, "energyType": "ELECTRICITY", "energySubtype": "GRID_PURCHASED", "valueSemantics": "CUMULATIVE", "reportingPeriod": "MONTH", "annualSummary": False, "confirmationStatus": "CONFIRMED", "evidenceReference": WHY})
        for ordinal, existing in enumerate(template):
            draft = keep(f"binding-{index}-{ordinal}", "/v1/energy-catalog/point-binding-versions", {"buildingId": b, "pointId": pid, "energyItemCode": "ELECTRICITY", "effectiveFrom": existing["effectiveFrom"], "effectiveTo": existing["effectiveTo"], "evidenceReference": WHY})
            keep(f"binding-approved-{index}-{ordinal}", f"/v1/energy-catalog/point-binding-versions/{draft['bindingVersionId']}/approve-historical", {"expectedRevision": draft["configRevision"], "reviewComment": WHY}, "reviewer")
        spaces.append(space)
        meters.append(point)

    relation = keep("relation-created", f"/v1/relation-models/{b}/initialize", {"reason": WHY}, key="INIT")
    vid = relation["versionId"]
    rows = []
    for page in range(1, 20):
        items = call(f"/v1/relation-models/{b}/versions/{vid}/nodes?page={page}&size=100")["items"]
        rows.extend(items)
        if len(items) < 100:
            break
    points = {n["businessObjectId"]: n["nodeId"] for n in rows if n["nodeType"] == "POINT"}
    space_nodes = {n["businessObjectId"]: n["nodeId"] for n in rows if n["nodeType"] == "SPACE"}
    revision = call(f"/v1/relation-models/{b}/versions/{vid}")["version"]["revision"]

    def mutation(name, path, body):
        nonlocal revision
        present = (out / (name + ".json")).exists()
        value = keep(name, path, {**body, "expectedRevision": revision}, key=name)
        if not present:
            revision += 1
        return value

    boundary = mutation("boundary", f"/v1/relation-models/versions/{vid}/metering/boundaries", {"boundaryCode": "DYN_TEST_ELECTRICITY", "boundaryName": "动态碳独立测试范围", "energyType": "ELECTRICITY", "confirmationStatus": "CONFIRMED", "evidenceReference": WHY})
    for index, point in enumerate(meters):
        mutation(f"structure-{index}", f"/v1/relation-models/versions/{vid}/metering/structures", {"meteringBoundaryId": boundary["boundaryId"], "meterPointNodeId": points[point["pointId"]], "meterRole": "MAIN" if index == 0 else "SUB", "parentMeterPointNodeId": None if index == 0 else points[meters[0]["pointId"]], "meterDirection": "INBOUND", "confirmationStatus": "CONFIRMED", "evidenceReference": WHY, "description": WHY})
        mutation(f"assignment-{index}", f"/v1/relation-models/versions/{vid}/metering/assignments", {"meteringBoundaryId": boundary["boundaryId"], "meterPointNodeId": points[point["pointId"]], "targetNodeId": space_nodes[spaces[index]["spaceId"]], "allocationStatus": "ASSIGNED", "evidenceReference": WHY})
    current = call(f"/v1/relation-models/{b}/versions/{vid}")["version"]
    review = keep("relation-submitted", f"/v1/relation-models/versions/{vid}/submit", {"expectedRevision": current["revision"], "reason": WHY}, key="SUBMIT")
    keep("relation-reviewed", f"/v1/relation-models/review-requests/{review['requestId']}/approve", {"reason": WHY}, "reviewer", key="REVIEW")
    model = call(f"/v1/relation-models?buildingId={b}")
    keep("relation-active", f"/v1/relation-models/versions/{vid}/activate", {"expectedModelRevision": model["modelRevision"], "reason": WHY}, key="ACTIVATE")
    scope = keep("history-scope", f"/v1/relation-models/{b}/versions/{vid}/historical-metering-scopes", {"startInclusive": FIRST, "endExclusive": LAST, "evidenceReference": WHY, "reason": WHY}, key="HISTORY")
    keep("history-reviewed", f"/v1/relation-models/{b}/historical-metering-scopes/{scope['scopeId']}/review", {"approved": True, "reason": WHY}, "reviewer", key="HISTORY_REVIEW")
    classification = {"relationVersionId": vid, "schemes": [{"code": "DYN_USE", "name": "测试用途", "dimension": "USAGE", "mode": "ADDITIVE"}], "nodes": [{"schemeCode": "DYN_USE", "code": f"SUB_{i}", "name": f"测试用电{i}", "parentCode": None} for i in range(1, meter_count)], "bindings": [{"schemeCode": "DYN_USE", "nodeCode": f"SUB_{i}", "objectType": "POINT", "objectId": meters[i]["pointId"], "meterPointId": meters[i]["pointId"], "semantics": "METERING", "status": "CONFIRMED", "evidence": WHY} for i in range(1, meter_count)], "boundaryRules": [{"boundaryId": boundary["boundaryId"], "mode": "MAIN_WITH_SUBMETER_BREAKDOWN", "status": "CONFIRMED", "evidence": WHY, "energyItemCode": "ELECTRICITY", "unit": "kWh", "independentChannelsConfirmed": False}]}
    route = f"/v1/energy-classification/buildings/{b}/versions"
    c = keep("class-created", route, {"reason": WHY})
    c = keep("class-saved", route + "/" + c["versionId"], {"expectedRevision": c["revision"], "configuration": classification, "reason": WHY}, method="PUT")
    c = keep("class-submitted", route + "/" + c["versionId"] + "/submit", {"expectedRevision": c["revision"], "reason": WHY})
    c = keep("class-approved", route + "/" + c["versionId"] + "/approve-historical", {"expectedRevision": c["revision"], "reason": WHY, "effectiveFrom": FIRST, "effectiveTo": LAST}, "reviewer")
    route = f"/v1/carbon-projects/buildings/{b}/versions"
    project = keep("project-created", route, {"reason": WHY})
    project = keep("project-saved", route + "/" + project["versionId"], {"expectedRevision": project["revision"], "configuration": {"classificationVersionId": c["versionId"], "energies": [{"energyItemCode": "ELECTRICITY", "pathway": "PURCHASED_ELECTRICITY", "capability": "ENABLED"}], "exclusions": []}, "reason": WHY}, method="PUT")
    for action, role in [("submit", "admin"), ("approve", "reviewer")]:
        project = keep("project-" + action, route + "/" + project["versionId"] + "/" + action, {"expectedRevision": project["revision"], "reason": WHY}, role)
    keep("project-active", route + "/" + project["versionId"] + "/activate-historical", {"expectedRevision": project["revision"], "reason": WHY, "effectiveFrom": FIRST, "effectiveTo": LAST})

    if args.live_meters:
        (out / "manifest.json").write_text(json.dumps({"purpose": "DYNAMIC_100_SIMULATED_ONLY", "buildingId": b,
            "meterCount": meter_count, "effectiveTo": LAST, "points": meters}), encoding="utf-8")
        print("LIVE_100_METER_FIXTURE_READY " + b)
        return

    manifest = json.loads((root / "factors/manifest.json").read_text(encoding="utf-8"))
    expected_months = {}
    for command in manifest["calculations"]:
        month, grain = command["month"], command["grain"]
        original_month = keep("source-month-" + month, f"/v1/energy-reporting/buildings/{source_building}/monthly-comparison?month={month}-01&timezoneId=Asia%2FShanghai")
        metric = next(m for m in original_month["current"]["metrics"] if m["metricCode"] == "ELECTRICITY_KWH")
        expected_months[month] = metric["value"]
        quantities = []
        if grain == "HOUR":
            for day in range(1, calendar.monthrange(int(month[:4]), int(month[5:]))[1] + 1):
                receipt = json.loads((args.v4_source / f"hourly-{month}-{day:02d}.json").read_text(encoding="utf-8-sig"))
                if receipt["buildingId"] != source_building or receipt["state"] != "COMPLETE":
                    raise ValueError("SOURCE_HOURLY_RECEIPT_REQUIRED")
                quantities.extend((p["startInclusive"], p["endExclusive"], Decimal(p["electricityKwh"])) for p in receipt["points"])
        elif grain == "DAY":
            source = json.loads(gzip.decompress((args.v4_source / f"profile-{month}.json.gz").read_bytes()))
            totals = {}
            for days in source["dailyProvenance"].values():
                for day in days:
                    totals[day["date"]] = totals.get(day["date"], 0) + int(day["dayMills"])
            for date, mills in sorted(totals.items()):
                stamp = datetime.fromisoformat(date).replace(tzinfo=ZONE)
                quantities.append((stamp.astimezone(timezone.utc).isoformat(), (stamp + timedelta(days=1)).astimezone(timezone.utc).isoformat(), Decimal(mills) / 1000))
        else:
            quantities = [(command["startInclusive"], command["endExclusive"], Decimal(metric["value"]))]
        if abs(sum(q[2] for q in quantities) - Decimal(metric["value"])) > Decimal("0.000001"):
            raise ValueError("SOURCE_QUANTITY_CONSERVATION_FAILED:" + month)
        readings = []
        for start, end, amount in quantities:
            mills = int(amount * 1000)
            if amount * 1000 != mills:
                raise ValueError("SOURCE_PRECISION_EXCEEDS_TEST_ALLOCATION")
            allocations = [mills, mills // 2, mills - mills // 2]
            for index, value in enumerate(allocations):
                readings.append({"pointId": meters[index]["pointId"], "startInclusive": start, "endExclusive": end,
                                 "energyItemCode": "ELECTRICITY", "unit": "kWh", "semantics": "PERIOD_TOTAL", "startReading": None,
                                 "endReading": None, "quantity": str(Decimal(value) / 1000), "multiplier": "1", "alreadyScaled": True,
                                 "status": "CONFIRMED", "sourceReference": WHY + ";SOURCE_BUILDING=" + source_building + ";MONTH=" + month + (";DERIVED_FROM_V4_DAILY_PROVENANCE" if grain == "DAY" else ""),
                                 "timePrecision": "EXACT", "supersedesFactId": None})
        for offset in range(0, len(readings), 498):
            key = month + "-" + str(offset)
            route = f"/v1/energy-classification/buildings/{b}/readings"
            batch = keep("import-" + key, route + "/import", {"idempotencyKey": "dynamic-fixture-" + key, "readings": readings[offset:offset + 498], "reason": WHY})
            if not batch.get("batchId"):
                raise ValueError("FIXTURE_IMPORT_REJECTED:" + key)
            current = call(route + "/batches/" + batch["batchId"])
            if current["status"] != "APPROVED":
                keep("review-" + key, route + "/batches/" + batch["batchId"] + "/approve", {"expectedRevision": current["revision"], "reason": WHY}, "reviewer")
        print("FIXTURE_MONTH_READY " + month + " " + grain, flush=True)
    fixture = {"purpose": WHY, "buildingId": b, "buildingCode": building["buildingCode"], "sourceBuildingId": source_building,
               "meterCount": 3, "expectedMonthlyKwh": expected_months, "calculations": manifest["calculations"]}
    (out / "manifest.json").write_text(json.dumps(fixture, ensure_ascii=False, indent=2), encoding="utf-8")
    config["sourceBuildingId"] = source_building
    config["buildingId"] = b
    config["buildingCode"] = building["buildingCode"]
    (root / "private/local.json").write_text(json.dumps(config), encoding="utf-8")
    print("MIXED_GRAIN_FIXTURE_READY " + b)


if __name__ == "__main__":
    main()
