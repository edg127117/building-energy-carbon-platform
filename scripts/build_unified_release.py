#!/usr/bin/env python3
"""为共享端和独立大屏分别组包；只保留各自部署边界，不连接服务器或修改运行环境。"""

import argparse
import copy
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import zipfile

from d01_release import ALIGNMENT_DEFAULTS, JAR, frontend_fingerprint, require, sha, unified_shared_package

SCRIPTS = {121: "V121__mysql_room_sensor_ingestion.sql", 122: "V122__mysql_ledger_display_rules.sql",
           123: "V123__mysql_device_communication.sql"}


def build(environment, jar, frontend, destination, commit, database):
    require(environment in ("shared", "demo"), "UNIFIED_ENVIRONMENT_REQUIRED")
    require(re.fullmatch(r"[a-f0-9]{40}", commit), "UNIFIED_COMMIT_REQUIRED")
    require(re.fullmatch(r"[A-Za-z0-9_]{1,64}", database), "UNIFIED_DATABASE_REQUIRED")
    require((environment == "shared" and database == "iot_platform") or
            (environment == "demo" and database.lower() not in
             ("iot_platform", "iot_platform_archive", "mysql", "sys", "information_schema", "performance_schema")),
            "UNIFIED_DATABASE_ENVIRONMENT_MISMATCH")
    info = json.loads((frontend / "release-build.json").read_text(encoding="utf-8"))
    require(info.get("environment") == environment and
            re.fullmatch(r"[A-Za-z0-9_-]{1,64}", info.get("buildingCode", "")), "UNIFIED_FRONTEND_BUILD_REQUIRED")
    pages = list((frontend / "assets").glob("FinalBPage-*.js"))
    require(len(pages) == 1, "UNIFIED_FRONTEND_PAGE_REQUIRED")
    binding = re.search(r'__name:"FinalBPage",setup\(\w+\)\{const \w+="([^"]*)"', pages[0].read_text(encoding="utf-8"))
    require(binding is not None and binding[1] == info["buildingCode"], "UNIFIED_FRONTEND_BINDING_MISMATCH")
    fingerprints = frontend_fingerprint(frontend)
    require("index.html" in fingerprints, "UNIFIED_FRONTEND_INDEX_REQUIRED")
    archive = destination.with_suffix(".tar.gz")
    require(not destination.exists() and not archive.exists(), "UNIFIED_DESTINATION_EXISTS")
    require(jar.is_file() and not jar.is_symlink(), "UNIFIED_JAR_REQUIRED")
    with zipfile.ZipFile(jar) as source:
        names = source.namelist()
        require(len(names) == len(set(names)) and "BOOT-INF/classes/application.properties" not in names,
                "UNIFIED_SOURCE_JAR_REQUIRED")
        versions = [int(m[1]) for name in names if
                    (m := re.fullmatch(r"BOOT-INF/classes/db/migration/mysql/V(\d+)__[^/]+\.sql", name))]
        require(versions and max(versions) == 123 and len(set(versions)) == len(versions),
                "UNIFIED_MIGRATION_SEQUENCE_INVALID")
        for version, filename in SCRIPTS.items():
            sql = source.read("BOOT-INF/classes/db/migration/mysql/" + filename)
            require(version == 121 or not re.search(rb"(?i)\bUSE\s+", sql), "UNIFIED_CROSS_DATABASE_SQL_FORBIDDEN")
        source.read("BOOT-INF/classes/com/platform/tools/DemoSchemaMigration.class")
        source.read("BOOT-INF/classes/com/platform/hvac/asset/ledger/LedgerController.class")
        source.read("BOOT-INF/classes/com/platform/iot/roomsensor/RoomSensorIngestor.class")
        destination.mkdir(parents=True, mode=0o700)
        with zipfile.ZipFile(destination / JAR, "w") as candidate:
            for item in source.infolist():
                candidate.writestr(copy.copy(item), source.read(item))
            if environment == "shared":
                # 当前共享端的启用/停用边界来自原受控发布；不继承独立演示实例的后台任务设置。
                candidate.writestr("BOOT-INF/classes/application.properties",
                                   "".join(k + "=" + v + "\n" for k, v in ALIGNMENT_DEFAULTS.items()))
    shutil.copytree(frontend, destination / "web")
    require(frontend_fingerprint(destination / "web") == fingerprints, "UNIFIED_FRONTEND_COPY_CHANGED")
    manifest = {"commit": commit, "sourceMigration": "121" if environment == "shared" else "120",
                "expectedMigration": "123", "releaseMode": "SCHEMA_UPGRADE",
                "artifactMode": "UNIFIED_SHARED_121_123" if environment == "shared" else "UNIFIED_DEMO_120_123",
                "expectedDatabase": database, "screenBuildingCode": info["buildingCode"],
                "sourceJarSha256": sha(jar), "backendJarSha256": sha(destination / JAR),
                "webIndexSha256": fingerprints["index.html"]}
    if environment == "shared":
        with zipfile.ZipFile(destination / JAR) as candidate:
            unified_shared_package(candidate, manifest)
    (destination / "MANIFEST.txt").write_text("".join(k + "=" + v + "\n" for k, v in manifest.items()), encoding="utf-8")
    (destination / "FRONTEND_SHA256.json").write_text(json.dumps(fingerprints, sort_keys=True, indent=2), encoding="utf-8")
    with tarfile.open(archive, "w:gz") as package:
        for item in sorted(destination.iterdir()):
            package.add(item, arcname=item.name)
    return {"archive": str(archive), "sha256": sha(archive), "manifest": manifest}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("environment", choices=("shared", "demo"))
    for name in ("jar", "frontend", "destination"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--database", required=True)
    args = parser.parse_args()
    require(not subprocess.check_output(["git", "status", "--porcelain"], text=True).strip(), "UNIFIED_CLEAN_COMMIT_REQUIRED")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    print(json.dumps(build(args.environment, args.jar, args.frontend, args.destination, commit, args.database)))


if __name__ == "__main__":
    main()
