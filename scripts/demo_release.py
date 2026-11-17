#!/usr/bin/env python3
"""独立19080的分阶段发布；只克隆本实例容器，数据库迁移失败后保留停机现场。"""
import argparse
import copy
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from d01_release import JAR, protected_json, require, sha, stage_package, write_state

BASE = Path("/home/user1/deployments/building-energy-carbon-demo")
BACKUP_FORMAT = "UNIFIED_DEMO_BACKUP_V1"


class DockerConnection(http.client.HTTPConnection):
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect("/var/run/docker.sock")


def api(method, path, data=None):
    connection = DockerConnection("localhost", timeout=90)
    try:
        connection.request(method, "/v1.41" + path,
                           json.dumps(data).encode() if data is not None else None,
                           {"Content-Type": "application/json"})
        response = connection.getresponse()
        body = response.read()
        # Docker错误可能含环境变量，不向普通日志输出响应正文。
        require(response.status in (200, 201, 204, 304), "DEMO_DOCKER_HTTP_" + str(response.status))
        return json.loads(body) if body else None
    finally:
        connection.close()


def inspect(identifier):
    require(re.fullmatch(r"[a-f0-9]{64}", identifier), "DEMO_CONTAINER_ID_REQUIRED")
    return api("GET", "/containers/" + identifier + "/json")


def contained(path, parent):
    require(path.is_absolute() and path.resolve().is_relative_to(parent.resolve()), "DEMO_PATH_OUTSIDE_INSTANCE")
    return path.resolve()


def signature(container):
    # Stop/start changes State, but must not change image, commands, credentials, limits or mounts.
    value = {key: container[key] for key in ("Id", "Image", "Config", "HostConfig", "Mounts")}
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


def mount(container, destination):
    found = [m for m in container["Mounts"] if m["Destination"] == destination]
    require(len(found) == 1 and found[0]["Type"] == "bind", "DEMO_BIND_MOUNT_REQUIRED")
    return contained(Path(found[0]["Source"]), BASE)


def configuration(container):
    # PyYAML is already part of this host's deployment environment; no runtime service dependency is added.
    import yaml
    value = yaml.safe_load(mount(container, "/config/backend.yml").read_text())
    urls = []

    def visit(node):
        if isinstance(node, dict):
            for item in node.values():
                visit(item)
        elif isinstance(node, list):
            for item in node:
                visit(item)
        elif isinstance(node, str) and node.startswith("jdbc:mysql://"):
            urls.append(urllib.parse.urlparse(node[5:]))
    visit(value)
    bindings = {(u.hostname, u.port or 3306, u.path.lstrip("/")) for u in urls}
    require(len(bindings) == 1 and all(u.username is None and u.password is None for u in urls),
            "DEMO_ONE_ACTIVE_DATABASE_REQUIRED")
    host, port, database = bindings.pop()
    require(port == 3306 and re.fullmatch(r"[A-Za-z0-9_]{1,64}", database)
            and database.lower() not in ("iot_platform", "iot_platform_archive", "mysql", "sys",
                                         "information_schema", "performance_schema"),
            "DEMO_ACTIVE_DATABASE_REQUIRED")
    return {"host": host, "database": database}


def snapshot():
    release = contained(BASE / "current", BASE / "releases")
    require((BASE / "current").is_symlink(), "DEMO_CURRENT_SYMLINK_REQUIRED")
    runtime = json.loads((release / "runtime.json").read_text())
    app, web = inspect(runtime["appId"]), inspect(runtime["webId"])
    require(app["State"]["Running"] and web["State"]["Running"], "DEMO_RUNNING_BASELINE_REQUIRED")
    require(web["HostConfig"].get("PortBindings") == {"8080/tcp": [{"HostIp": "", "HostPort": "19080"}]}
            or {x["HostPort"] for v in (web["HostConfig"].get("PortBindings") or {}).values() for x in v} == {"19080"},
            "DEMO_PORT_19080_REQUIRED")
    require(not app["HostConfig"].get("PortBindings"), "DEMO_BACKEND_HOST_PORT_FORBIDDEN")
    require(not app["HostConfig"].get("Privileged") and not web["HostConfig"].get("Privileged"),
            "DEMO_PRIVILEGED_CONTAINER_FORBIDDEN")
    old_jar = mount(app, "/app/application.jar")
    root = mount(web, "/usr/share/nginx/html")
    config = mount(web, "/etc/nginx/conf.d/default.conf")
    hold = mount(web, "/cutover") / "hold"
    require(not hold.exists(), "DEMO_MAINTENANCE_ALREADY_ACTIVE")
    binding = configuration(app)
    require(binding["database"] == runtime["database"], "DEMO_RUNTIME_DATABASE_MISMATCH")
    mysql = inspect(runtime["mysql"])
    data_networks = set(app["NetworkSettings"]["Networks"]) & set(mysql["NetworkSettings"]["Networks"])
    require(len(data_networks) == 1, "DEMO_DATABASE_NETWORK_MISMATCH")
    aliases = [a for n, v in mysql["NetworkSettings"]["Networks"].items() if n in data_networks
               for a in (v.get("Aliases") or [])]
    require(binding["host"] in aliases or binding["host"] == mysql["Name"].lstrip("/"),
            "DEMO_DATABASE_CONTAINER_MISMATCH")
    networks = set(app["NetworkSettings"]["Networks"]) & set(web["NetworkSettings"]["Networks"])
    require(len(networks) == 1, "DEMO_APPLICATION_NETWORK_REQUIRED")
    return {"previousRelease": str(release), "previousJarSha256": sha(old_jar),
            "appId": app["Id"], "webId": web["Id"], "mysqlId": mysql["Id"],
            "tdengineId": runtime["tdengine"], "database": binding["database"],
            "appSignature": signature(app), "webSignature": signature(web),
            "appNetwork": networks.pop(), "holdPath": str(hold), "oldWebRoot": str(root),
            "oldNginx": str(config), "oldNginxSha256": sha(config),
            "backendConfig": str(mount(app, "/config/backend.yml")),
            "backendConfigSha256": sha(mount(app, "/config/backend.yml")),
            "preserved": {x["Id"]: inspect(x["Id"])["State"]["StartedAt"]
                          for x in api("GET", "/containers/json") if x["Id"] not in (app["Id"], web["Id"])}}


def unchanged(state, *, current=True):
    if current:
        require(str((BASE / "current").resolve()) == state["previousRelease"], "DEMO_CURRENT_CHANGED")
    require(signature(inspect(state["appId"])) == state["appSignature"]
            and signature(inspect(state["webId"])) == state["webSignature"], "DEMO_BASELINE_CONTAINER_CHANGED")
    require(sha(mount(inspect(state["appId"]), "/app/application.jar")) == state["previousJarSha256"],
            "DEMO_BASELINE_JAR_CHANGED")
    require(sha(Path(state["backendConfig"])) == state["backendConfigSha256"]
            and sha(Path(state["oldNginx"])) == state["oldNginxSha256"], "DEMO_CONFIGURATION_CHANGED")
    for identifier, started in state["preserved"].items():
        info = inspect(identifier)
        require(info["State"]["Running"] and info["State"]["StartedAt"] == started, "DEMO_UNRELATED_CONTAINER_CHANGED")
    require(sha(Path(state["release"]) / JAR) == state["jarSha256"], "DEMO_CANDIDATE_JAR_CHANGED")


def prepare(archive, expected_sha):
    contained(archive, BASE / "incoming")
    require(archive.is_file() and not archive.is_symlink() and archive.stat().st_size <= 512 * 1024**2
            and sha(archive) == expected_sha, "DEMO_ARCHIVE_HASH_MISMATCH")
    baseline = snapshot()
    # Read the manifest through the bounded safe extractor before using a commit as a path.
    import tarfile
    with tarfile.open(archive) as package:
        member = package.getmember("MANIFEST.txt")
        require(member.isfile() and member.size <= 64 * 1024, "DEMO_MANIFEST_SIZE_INVALID")
        raw = package.extractfile(member).read()
    manifest = dict(line.split("=", 1) for line in raw.decode().splitlines() if "=" in line)
    require(manifest.get("artifactMode") == "UNIFIED_DEMO_120_123"
            and manifest.get("sourceMigration") == "120" and manifest.get("expectedMigration") == "123"
            and manifest.get("expectedDatabase") == baseline["database"]
            and re.fullmatch(r"[a-f0-9]{40}", manifest.get("commit", "")), "DEMO_PACKAGE_PLAN_MISMATCH")
    release = BASE / "releases" / ("unified-" + manifest["commit"][:12] + "-19080")
    stage_package(archive, release)
    require(sha(release / JAR) == manifest["backendJarSha256"]
            and sha(release / "web/index.html") == manifest["webIndexSha256"], "DEMO_PACKAGE_HASH_MISMATCH")
    # Keep old lazy chunks and the separately maintained public entry byte-for-byte.
    preserved_web = {}
    oldroot = Path(baseline["oldWebRoot"])
    for directory in ("assets", "public-screen"):
        old = oldroot / directory
        if not old.exists():
            continue
        for path in old.rglob("*"):
            require(not path.is_symlink(), "DEMO_OLD_WEB_SYMLINK_FORBIDDEN")
            if not path.is_file():
                continue
            relative = path.relative_to(oldroot)
            target = release / "web" / relative
            if target.exists():
                require(sha(target) == sha(path), "DEMO_EXISTING_RESOURCE_HASH_COLLISION")
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                import shutil
                shutil.copy2(path, target)
            preserved_web[str(relative)] = sha(path)
    import shutil
    shutil.copyfile(baseline["oldNginx"], release / "nginx.conf")
    operation = uuid.uuid4().hex
    state = {**baseline, "phase": "PREPARED", "operation": operation, "release": str(release),
             "commit": manifest["commit"], "jarSha256": manifest["backendJarSha256"],
             "webSha256": manifest["webIndexSha256"], "buildingCode": manifest["screenBuildingCode"],
             "preservedWeb": preserved_web, "candidateStarted": False}
    unchanged(state)
    path = release / "operation.json"
    write_state(path, state, new=True)
    return path


def state_file(path):
    contained(path, BASE / "releases")
    state = protected_json(path)
    require(path == Path(state["release"]) / "operation.json", "DEMO_STATE_PATH_MISMATCH")
    return state


def schema_job(state, action, *, read_only=False):
    require(action in ("validate120", "upgrade121", "upgrade122", "upgrade123", "validate123"),
            "DEMO_SCHEMA_ACTION_REQUIRED")
    if action.startswith("upgrade"):
        require(state["phase"] == "FROZEN" and not inspect(state["appId"])["State"]["Running"],
                "DEMO_WRITERS_NOT_FROZEN")
    mysql = inspect(state["mysqlId"])
    secret = next((x.partition("=")[2] for x in mysql["Config"].get("Env", [])
                   if x.startswith("MYSQL_ROOT_PASSWORD=")), None)
    require(secret and not any(x in secret for x in "\0\r\n"), "DEMO_MAINTENANCE_CREDENTIAL_UNAVAILABLE")
    # Password only crosses stdin into a short-lived process, never Docker Config.Env, an argument or a report.
    url = "jdbc:mysql://127.0.0.1:3306/" + state["database"] + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    if read_only:
        url += "&sessionVariables=transaction_read_only=1"
    environment = {**os.environ, "D01_JDBC_URL": url, "D01_JDBC_USER": "root",
                   "DEMO_EXPECTED_DATABASE": state["database"],
                   "D01_WRITERS_FROZEN": "false" if read_only else "true"}
    script = ('IFS= read -r D01_JDBC_PASSWORD; export D01_JDBC_PASSWORD; '
              'exec java -Dloader.main=com.platform.tools.DemoSchemaMigration -cp /candidate.jar '
              'org.springframework.boot.loader.launch.PropertiesLauncher "$1"')
    app = inspect(state["appId"])
    command = ["docker", "run", "--rm", "-i", "--pull=never", "--network", "container:" + state["mysqlId"],
               "--memory=256m", "--cpus=0.5", "--mount",
               "type=bind,src=" + str(Path(state["release"]) / JAR) + ",dst=/candidate.jar,readonly",
               "--entrypoint", "sh"]
    for key in ("D01_JDBC_URL", "D01_JDBC_USER", "DEMO_EXPECTED_DATABASE", "D01_WRITERS_FROZEN"):
        command += ["--env", key]
    command += [app["Image"], "-ceu", script, "release-schema", action]
    result = subprocess.run(command, input=(secret + "\n").encode(), env=environment, capture_output=True, timeout=300)
    require(result.returncode == 0 and ("DEMO_SCHEMA_VALIDATED=" + re.search(r"[0-9]+$", action)[0]).encode()
            in result.stdout.splitlines(), "DEMO_SCHEMA_CHECK_FAILED")


def freeze(path, approval_reference, writers_fenced, ingress_retained, accept_ingress_loss):
    state = state_file(path)
    require(state["phase"] == "PREPARED" and approval_reference and writers_fenced
            and ingress_retained != accept_ingress_loss, "DEMO_MAINTENANCE_APPROVAL_REQUIRED")
    unchanged(state)
    hold = Path(state["holdPath"])
    require(not hold.exists(), "DEMO_HOLD_ALREADY_EXISTS")
    hold.write_text(state["operation"] + "\n")
    state.update(phase="STOPPING", approvalReference=approval_reference, acceptIngressLoss=accept_ingress_loss)
    write_state(path, state)
    for identifier in (state["webId"], state["appId"]):
        api("POST", "/containers/" + identifier + "/stop?t=60")
    require(all(not inspect(state[k])["State"]["Running"] for k in ("appId", "webId")), "DEMO_STOP_FAILED")
    state.update(phase="FROZEN", frozenAt=int(time.time()))
    write_state(path, state)


def checked_restore(state, path, backup_file, report_file):
    backup = protected_json(backup_file)
    report = protected_json(report_file)
    require(backup.get("format") == BACKUP_FORMAT and backup.get("freezeSha256") == sha(path)
            and backup.get("database") == state["database"]
            and backup.get("mysqlSourceId") == state["mysqlId"] and backup.get("tdengineSourceId") == state["tdengineId"],
            "DEMO_BACKUP_SOURCE_MISMATCH")
    files = backup.get("files", {})
    require(files and any(n.startswith("mysql") for n in files) and any(n.startswith("tdengine/") for n in files),
            "DEMO_COMPLETE_BACKUP_REQUIRED")
    for relative, digest in files.items():
        raw = backup_file.parent / relative
        item = contained(raw, backup_file.parent)
        require(not Path(relative).is_absolute() and ".." not in Path(relative).parts
                and item.is_file() and not any(p.is_symlink() for p in (raw, *raw.parents))
                and sha(item) == digest, "DEMO_BACKUP_CONTENT_MISMATCH")
    require(report.get("format") == "D01_RESTORE_VERIFIED_V1"
            and report.get("backup_manifest_sha256") == sha(backup_file)
            and all(report.get(k) is True for k in ("mysql_verified", "tdengine_verified", "source_preserved")),
            "DEMO_RESTORE_PROOF_REQUIRED")
    targets = report.get("restored_container_ids", [])
    require(len(targets) == len(set(targets)) == 2
            and not set(targets).intersection((state["mysqlId"], state["tdengineId"])),
            "DEMO_RESTORE_MUST_USE_ISOLATED_CONTAINERS")
    source_volumes = {m["Name"] for cid in (state["mysqlId"], state["tdengineId"])
                      for m in inspect(cid)["Mounts"] if m["Type"] == "volume"}
    for cid, source_id, destination in zip(targets, (state["mysqlId"], state["tdengineId"]),
                                           ("/var/lib/mysql", "/var/lib/taos")):
        candidate = inspect(cid)
        volumes = {m["Name"] for m in candidate["Mounts"] if m["Type"] == "volume"}
        data_mounts = [m for m in candidate["Mounts"] if m["Destination"] == destination]
        require(candidate["Config"].get("Labels", {}).get("bec.operation") == state["operation"]
                and candidate["Image"] == inspect(source_id)["Image"]
                and len(data_mounts) == 1 and data_mounts[0]["Type"] == "volume"
                and not candidate["HostConfig"].get("PortBindings")
                and not candidate["HostConfig"].get("Privileged")
                and volumes and not volumes.intersection(source_volumes), "DEMO_RESTORE_VOLUME_NOT_ISOLATED")
    evidence = report_file.parent / report.get("evidence_file", "")
    require(evidence.parent == report_file.parent and evidence.is_file() and not evidence.is_symlink()
            and sha(evidence) == report.get("evidence_sha256"), "DEMO_RESTORE_EVIDENCE_CHANGED")
    content = json.loads(evidence.read_text())
    fingerprints = content.get("source")
    require(content.get("database") == state["database"] and isinstance(fingerprints, dict)
            and set(fingerprints) == {"mysql", "tdengine"} and all(fingerprints.values())
            and fingerprints == content.get("restored"), "DEMO_RESTORED_DATA_MISMATCH")


def migrate(path, backup_file, report_file):
    state = state_file(path)
    require(state["phase"] == "FROZEN", "DEMO_FROZEN_STATE_REQUIRED")
    unchanged(state)
    checked_restore(state, path, backup_file, report_file)
    started = Path(state["release"]) / "migration-started.json"
    require(not started.exists(), "DEMO_MIGRATION_NOT_REENTRANT")
    write_state(started, {"freezeSha256": sha(path), "startedAt": int(time.time())}, new=True)
    for action in ("upgrade121", "upgrade122", "upgrade123"):
        schema_job(state, action)
    # Keep the frozen state immutable: final backup and restore evidence remain bound to these exact bytes.
    write_state(Path(state["release"]) / "schema-ready.json",
                {"freezeSha256": sha(path), "jarSha256": state["jarSha256"]}, new=True)


def clone(old, name, replacements):
    config, host = copy.deepcopy(old["Config"]), copy.deepcopy(old["HostConfig"])
    host["Binds"] = [replacements.get(x.split(":")[1], x.split(":")[0]) + ":" + ":".join(x.split(":")[1:])
                     for x in (host.get("Binds") or [])]
    for item in host.get("Mounts") or []:
        if item.get("Target") in replacements:
            item["Source"] = replacements[item["Target"]]
    networks = {n: {"Aliases": list(dict.fromkeys([name, old["Name"].lstrip("/")] + (v.get("Aliases") or [])))}
                for n, v in old["NetworkSettings"]["Networks"].items()}
    primary = host["NetworkMode"] if host["NetworkMode"] in networks else next(iter(networks))
    host["NetworkMode"] = primary
    config.update(Hostname="", Image=old["Image"], HostConfig=host,
                  NetworkingConfig={"EndpointsConfig": {primary: networks[primary]}})
    created = api("POST", "/containers/create?name=" + name, config)["Id"]
    for network, endpoint in networks.items():
        if network != primary:
            api("POST", "/networks/" + network + "/connect", {"Container": created, "EndpointConfig": endpoint})
    actual = {m["Destination"]: m["Source"] for m in inspect(created)["Mounts"]}
    require(all(actual.get(k) == v for k, v in replacements.items()), "DEMO_CANDIDATE_MOUNT_MISMATCH")
    return created


def healthy(identifier, network):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        info = inspect(identifier)
        require(info["State"]["Running"], "DEMO_CANDIDATE_STOPPED")
        address = info["NetworkSettings"]["Networks"][network]["IPAddress"]
        try:
            with urllib.request.urlopen("http://" + address + ":18081/api/auth/me", timeout=3) as response:
                if response.status == 200:
                    return
        except urllib.error.HTTPError as error:
            if error.code == 401:
                return
        except (urllib.error.URLError, TimeoutError):
            pass
        time.sleep(1)
    raise RuntimeError("DEMO_STARTUP_TIMEOUT")


def cutover(path, backup_file, report_file):
    state = state_file(path)
    require(state["phase"] == "FROZEN", "DEMO_FROZEN_STATE_REQUIRED")
    unchanged(state)
    checked_restore(state, path, backup_file, report_file)
    ready = protected_json(Path(state["release"]) / "schema-ready.json")
    require(ready == {"freezeSha256": sha(path), "jarSha256": state["jarSha256"]}, "DEMO_SCHEMA_NOT_READY")
    schema_job(state, "validate123")
    app = web = None
    try:
        app = clone(inspect(state["appId"]), "unified-" + state["commit"][:12] + "-app",
                    {"/app/application.jar": str(Path(state["release"]) / JAR)})
        state.update(phase="STARTING", candidateStarted=True, candidateApp=app)
        write_state(path, state)
        api("POST", "/containers/" + app + "/start")
        healthy(app, state["appNetwork"])
        web = clone(inspect(state["webId"]), "unified-" + state["commit"][:12] + "-web",
                    {"/usr/share/nginx/html": str(Path(state["release"]) / "web"),
                     "/etc/nginx/conf.d/default.conf": str(Path(state["release"]) / "nginx.conf")})
        api("POST", "/containers/" + web + "/start")
        with urllib.request.urlopen("http://127.0.0.1:19080/", timeout=10) as response:
            require(hashlib.sha256(response.read()).hexdigest() == state["webSha256"], "DEMO_WEB_RESPONSE_MISMATCH")
        for relative, digest in state["preservedWeb"].items():
            require(sha(Path(state["release"]) / "web" / relative) == digest, "DEMO_EXISTING_WEB_CHANGED")
        unchanged(state)
        runtime = json.loads((Path(state["previousRelease"]) / "runtime.json").read_text())
        runtime.update(appId=app, webId=web, commit=state["commit"], jarSha256=state["jarSha256"], release=state["release"])
        write_state(Path(state["release"]) / "runtime.json", runtime, new=True)
        next_link = BASE / (".current-unified-" + state["operation"])
        require(not next_link.exists() and not next_link.is_symlink(), "DEMO_NEXT_LINK_EXISTS")
        next_link.symlink_to(state["release"])
        next_link.replace(BASE / "current")
        hold = Path(state["holdPath"])
        require(hold.read_text() == state["operation"] + "\n", "DEMO_HOLD_OWNER_CHANGED")
        hold.unlink()
        state.update(phase="AWAITING_BUSINESS_ACCEPTANCE", candidateWeb=web)
        write_state(path, state)
    except BaseException:
        # Once DDL or candidate writes have started, an automatic old-app restart can corrupt recovery assumptions.
        stop_errors = []
        for identifier in (web, app):
            if identifier:
                try:
                    api("POST", "/containers/" + identifier + "/stop?t=60")
                    require(not inspect(identifier)["State"]["Running"], "DEMO_CANDIDATE_STILL_RUNNING")
                except Exception:
                    stop_errors.append(identifier)
        state.update(phase="FAILED_STOP_ERROR" if stop_errors else "FAILED_STOPPED",
                     candidateApp=app, candidateWeb=web, stopErrors=stop_errors)
        write_state(path, state)
        if stop_errors:
            raise RuntimeError("DEMO_FAILED_CANDIDATE_STOP_REQUIRES_OPERATOR") from None
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("inspect", "prepare", "status", "validate-source", "freeze", "migrate", "cutover"))
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--sha256")
    parser.add_argument("--state", type=Path)
    parser.add_argument("--backup-manifest", type=Path)
    parser.add_argument("--restore-report", type=Path)
    parser.add_argument("--approval-reference")
    parser.add_argument("--writers-fenced", action="store_true")
    parser.add_argument("--ingress-retained", action="store_true")
    parser.add_argument("--accept-ingress-loss", action="store_true")
    args = parser.parse_args()
    require(os.name == "posix", "DEMO_LINUX_HOST_REQUIRED")
    import fcntl
    with (BASE / ".unified-release.lock").open("a") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.action == "inspect":
            result = snapshot()
        elif args.action == "prepare":
            require(args.archive is not None and re.fullmatch(r"[a-f0-9]{64}", args.sha256 or ""),
                    "DEMO_ARCHIVE_ARGUMENTS_REQUIRED")
            result = {"stateFile": str(prepare(args.archive, args.sha256))}
        else:
            require(args.state is not None, "DEMO_STATE_ARGUMENT_REQUIRED")
            if args.action == "status":
                result = state_file(args.state)
            elif args.action == "validate-source":
                state = state_file(args.state)
                require(state["phase"] == "PREPARED", "DEMO_PREPARED_STATE_REQUIRED")
                unchanged(state)
                schema_job(state, "validate120", read_only=True)
                result = {"validated": "120", "database": state["database"]}
            elif args.action == "freeze":
                freeze(args.state, args.approval_reference, args.writers_fenced, args.ingress_retained, args.accept_ingress_loss)
                result = {"phase": "FROZEN"}
            else:
                require(args.backup_manifest is not None and args.restore_report is not None, "DEMO_BACKUP_EVIDENCE_REQUIRED")
                (migrate if args.action == "migrate" else cutover)(args.state, args.backup_manifest, args.restore_report)
                result = {"action": args.action, "complete": True}
        print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
