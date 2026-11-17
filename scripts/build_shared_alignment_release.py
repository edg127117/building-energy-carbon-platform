#!/usr/bin/env python3
"""构建共享端专用完整后端包，保留既有前端和外置配置；不连接或修改运行环境。"""

import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import zipfile

from d01_release import ALIGNMENT_DEFAULTS, ALIGNMENT_PLAN, JAR, alignment_plan, frontend_fingerprint, require, sha


def build(jar, frontend, destination, commit, building_id, space_id):
    require(re.fullmatch(r"[a-f0-9]{40}", commit), "D01_BUILD_COMMIT_REQUIRED")
    require(all(re.fullmatch(r"[A-Za-z0-9_-]{1,32}", v) for v in (building_id, space_id)),
            "D01_ROOM_BINDING_REQUIRED")
    require(jar.is_file() and not jar.is_symlink(), "D01_BUILD_JAR_REQUIRED")
    fingerprints = frontend_fingerprint(frontend)
    require("index.html" in fingerprints, "D01_BUILD_FRONTEND_REQUIRED")
    require(not destination.exists(), "D01_BUILD_DESTINATION_EXISTS")
    archive = destination.with_suffix(".tar.gz")
    require(not archive.exists(), "D01_BUILD_ARCHIVE_EXISTS")
    with zipfile.ZipFile(jar) as source:
        names = source.namelist()
        require(len(names) == len(set(names)) and "BOOT-INF/classes/application.properties" not in names,
                "D01_BUILD_UNEXPECTED_RESOURCE")
        plan = alignment_plan(source)
        source.read("BOOT-INF/classes/com/platform/tools/D01RoomSensorBootstrap.class")
        source.read("BOOT-INF/classes/com/platform/tools/SharedAlignmentSchemaContract.class")
        mysql_sql = source.read("BOOT-INF/classes/db/room-sensor/configure-b314.sql")
        td_sql = source.read("BOOT-INF/classes/db/room-sensor/tdengine.sql")
        plan_sha = hashlib.sha256(source.read(ALIGNMENT_PLAN)).hexdigest()
        destination.mkdir(parents=True, mode=0o700)
        # 保留嵌套依赖JAR的ZIP_STORED等属性，避免破坏Spring Boot加载布局。
        with zipfile.ZipFile(destination / JAR, "w") as candidate:
            for item in source.infolist():
                candidate.writestr(copy.copy(item), source.read(item))
            candidate.writestr("BOOT-INF/classes/application.properties",
                               "".join(k + "=" + v + "\n" for k, v in ALIGNMENT_DEFAULTS.items()))
    shutil.copytree(frontend, destination / "web")
    require(frontend_fingerprint(destination / "web") == fingerprints, "D01_BUILD_FRONTEND_CHANGED")
    manifest = {
        "commit": commit, "sourceMigration": "93", "expectedMigration": "121", "releaseMode": "SCHEMA_UPGRADE",
        "artifactMode": "FULL_BUILD_SHARED_ALIGNMENT", "backendJarSha256": sha(destination / JAR),
        "webIndexSha256": fingerprints["index.html"], "preserveFrontendSha256": fingerprints["index.html"],
        "alignmentPlanSha256": plan_sha, "roomSensorMysqlSha256": hashlib.sha256(mysql_sql).hexdigest(),
        "roomSensorTdSha256": hashlib.sha256(td_sql).hexdigest(),
        "roomSensorBuildingId": building_id, "roomSensorSpaceId": space_id,
    }
    (destination / "MANIFEST.txt").write_text("".join(k + "=" + v + "\n" for k, v in manifest.items()), encoding="utf-8")
    (destination / "FRONTEND_SHA256.json").write_text(json.dumps(fingerprints, sort_keys=True, indent=2), encoding="utf-8")
    with tarfile.open(archive, "w:gz") as package:
        for item in sorted(destination.iterdir()):
            package.add(item, arcname=item.name)
    return {"archive": str(archive), "sha256": sha(archive), "manifest": manifest, "migrations": len(plan["migrations"])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--frontend", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--building-id", required=True)
    parser.add_argument("--space-id", required=True)
    args = parser.parse_args()
    # 正式候选必须来自可追溯的干净提交，不用旧HEAD冒充尚未提交的实现。
    require(not subprocess.check_output(["git", "status", "--porcelain"], text=True).strip(), "D01_BUILD_CLEAN_COMMIT_REQUIRED")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    print(json.dumps(build(args.jar, args.frontend, args.destination, commit, args.building_id, args.space_id), ensure_ascii=False))


if __name__ == "__main__":
    main()
