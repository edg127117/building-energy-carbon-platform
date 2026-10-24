"""New local containers only; never accepts an existing database endpoint or deployment target."""
import base64
import concurrent.futures
import json
import os
import pathlib
import subprocess
import time
import urllib.request
import uuid
import shutil

ROOT = pathlib.Path(__file__).resolve().parents[1]
HERE = ROOT / "target"
DOCKER = shutil.which("docker") or str(pathlib.Path(os.environ["LOCALAPPDATA"]) / "Programs/DockerDesktop/resources/bin/docker.exe")
JAVA = str(pathlib.Path(os.environ["JAVA_HOME"]) / "bin/java.exe")
NONCE = uuid.uuid4().hex[:16]
LABEL_KEY = "bec.d01.probe"
LABEL = LABEL_KEY + "=" + NONCE
ENV = os.environ.copy()
ENV["MYSQL_ROOT_PASSWORD"] = uuid.uuid4().hex
ENV["MYSQL_PASSWORD"] = uuid.uuid4().hex
ENV["D01_MYSQL_PASSWORD"] = ENV["MYSQL_PASSWORD"]
ENV["D01_MYSQL_USER"] = "d01_probe"
ENV["D01_DATABASE"] = "d01_" + NONCE
ENV["D01_PROBE_ISOLATED"] = "true"
ENV["D01_EVENT_TIME"] = str(int(time.time() * 1000) - 60_000)
ENV["D01_TD_PASSWORD"] = "taosdata"  # Published default of the disposable image, never deployment credentials.
RESOURCES = {"containers": [], "networks": [], "volumes": []}
LOG = []


def emit(value):
    print(value, flush=True)
    LOG.append(value)


def docker(*args, timeout=45):
    result = subprocess.run([DOCKER, "--host", "npipe:////./pipe/dockerDesktopLinuxEngine", *args], env=ENV, text=True, encoding="utf-8", errors="replace", capture_output=True, timeout=timeout)
    if result.returncode:
        # Do not echo command arguments, environment, or Docker inspect data.
        raise RuntimeError("docker operation failed: " + args[0] + " " + result.stderr[-800:])
    return result.stdout.strip()


def owned(kind, identifier):
    data = json.loads(docker(kind, "inspect", identifier))[0] if kind != "container" else json.loads(docker("inspect", identifier))[0]
    labels = data.get("Config", {}).get("Labels", {}) if kind == "container" else data.get("Labels", {})
    assert labels.get(LABEL_KEY) == NONCE, "refusing non-owned resource"
    return data


def container(image, internal_port, network, volume, extra=()):
    identifier = docker("run", "-d", "--name", "d01-" + image.split(":")[0].split("/")[-1] + "-" + NONCE,
        "--label", LABEL, "--network", network, "--memory", "768m", "--cpus", "1",
        "-p", "127.0.0.1::" + str(internal_port), "--mount", "type=volume,source=" + volume + ",target=/var/lib/" + ("mysql" if internal_port == 3306 else "taos"),
        *extra, image, timeout=180)
    RESOURCES["containers"].append(identifier)
    data = owned("container", identifier)
    ports = data["NetworkSettings"]["Ports"][str(internal_port) + "/tcp"]
    emit("PORT_BINDING_CHECK=" + json.dumps({"actual": ports, "requested": data["HostConfig"]["PortBindings"]}, sort_keys=True))
    assert len(ports) == 1 and ports[0]["HostIp"] == "127.0.0.1"
    return identifier, int(ports[0]["HostPort"])


def wait_for(probe, seconds=140, stage="engine"):
    deadline = time.monotonic() + seconds
    last = "not ready"
    while time.monotonic() < deadline:
        try:
            if probe():
                return
        except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
            last = type(error).__name__ + ": " + str(error)[:300]
        time.sleep(1)
    raise RuntimeError("isolated engine readiness timeout stage=" + stage + " last=" + last)


def taos(sql):
    auth = base64.b64encode(("root:" + ENV["D01_TD_PASSWORD"]).encode()).decode()
    request = urllib.request.Request("http://127.0.0.1:" + ENV["D01_TD_PORT"] + "/rest/sql", data=sql.encode(),
        headers={"Authorization": "Basic " + auth})
    with urllib.request.urlopen(request, timeout=5) as response:
        result = json.load(response)
    assert result.get("code", 0) == 0, "isolated TDengine SQL error: " + str(result.get("code"))
    return result


def run(*args, expected=(0,)):
    cp = os.pathsep.join([str(HERE / "test-classes"), str(HERE / "classes"), (HERE / "d01-classpath.txt").read_text(encoding="utf-8-sig").strip()])
    result = subprocess.run([JAVA, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Duser.timezone=Asia/Shanghai", "-cp", cp, "com.platform.iot.reliability.D01AcceptanceProcess", *args],
        env=ENV, text=True, encoding="utf-8", errors="replace", capture_output=True, timeout=65)
    selected = [line for line in result.stdout.splitlines() if line.startswith(("MYSQL_VERSION=", "TDENGINE_VERSION=", "REAL_", "RESULT=", "RECOVERED=", "VERIFIED", "SYNTHETIC_"))]
    for line in selected:
        emit(" ".join(args[:3]) + " -> " + line)
    if result.returncode not in expected:
        raise RuntimeError("probe failed mode=" + args[0] + " exit=" + str(result.returncode) + " " + result.stderr[-1600:])
    return result.stdout


def pair(a, b):
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(run, *args) for args in [a, b]]
        return [f.result() for f in futures]


def write(message, point, value, halt="none", arrival="100", late="false"):
    return ("write", message, point, str(value), halt, arrival, late)


def refresh_endpoints(mysql_id, td_id):
    def port(identifier, internal):
        mappings = owned("container", identifier)["NetworkSettings"]["Ports"][str(internal) + "/tcp"]
        assert len(mappings) == 1 and mappings[0]["HostIp"] == "127.0.0.1"
        return int(mappings[0]["HostPort"])
    mysql_port, td_port = port(mysql_id, 3306), port(td_id, 6041)
    ENV["D01_MYSQL_URL"] = "jdbc:mysql://127.0.0.1:" + str(mysql_port) + "/" + ENV["D01_DATABASE"] + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    ENV["D01_TD_PORT"] = str(td_port)
    ENV["D01_TD_URL"] = "jdbc:TAOS-RS://127.0.0.1:" + str(td_port) + "/?timezone=Asia/Shanghai"


def scenarios(mysql_id, td_id):
    run("init")
    outcomes = pair(write("conflict_a", "conflict", 100), write("conflict_b", "conflict", 120))
    assert sum("RESULT=PLATFORM_PERSISTED" in x for x in outcomes) == 1
    assert sum("RESULT=MESSAGE_CONFLICT" in x for x in outcomes) == 1
    winner = 100 if "RESULT=PLATFORM_PERSISTED" in outcomes[0] else 120
    chosen = 120 if winner == 100 else 100
    run("verify", "conflict", str(winner), "1")
    pair(write("equal_a", "equal", 1.12345678901234), write("equal_b", "equal", 1.12345678901234))
    run(*write("equal_a", "equal", 1.12345678901234))
    run("verify", "equal", "1.12345678901234", "2")
    pair(write("same_id", "same_id", 30), write("same_id", "same_id", 30))
    run("verify", "same_id", "30", "1")
    assert "RESULT=MESSAGE_CONFLICT" in run(*write("same_id", "same_id", 31))
    crash_stages = [("registered", 31), ("raw", 32), ("receipt", 33), ("receipt_inserted", 34), ("committed", 35)]
    for stage, code in crash_stages:
        run(*write("crash_" + stage, "crash_" + stage, 40, halt=stage), expected=(code,))
        emit("FORCED_JVM_EXIT_VERIFIED stage=" + stage + " exit=" + str(code))
    for identifier in [mysql_id, td_id]:
        owned("container", identifier)
        docker("restart", identifier, timeout=50)
    refresh_endpoints(mysql_id, td_id)
    emit("OWNED_ENGINES_RESTARTED endpoints_refreshed=yes")
    ready(mysql_id)
    pair(("recover",), ("recover",))
    for stage, _ in crash_stages:
        run("verify", "crash_" + stage, "40", "1")
    run("correct", "conflict", str(chosen))
    run("verify", "conflict", str(winner), "1")
    run("seed", "HISTORICAL", "50")
    assert "RESULT=MESSAGE_CONFLICT" in run(*write("historical_bad", "historical", 60))
    run("recover")
    run("verify", "HISTORICAL", "50", "0")
    assert "RESULT=PLATFORM_PERSISTED" in run(*write("historical_same", "HISTORICAL", 50))
    run("verify", "HISTORICAL", "50", "1")
    run(*write("retire_id", "retire", 70))
    assert "RESULT=MESSAGE_CONFLICT" in run(*write("retire_id", "retire", 80))
    run("archive_retired", "retire", "retire_id")
    emit("D01_APPLICATION_COMPONENT_CHECKS_PASSED")
    emit("BOUNDARY: real repository, ledger, message journal, V2 service, MyBatis receipt and two-metric replay; normalized validation/config dependencies are controlled fixtures. No MQTT, real devices, running test system or throughput acceptance.")


def ready(mysql_id):
    ENV["MYSQL_PWD"] = ENV["MYSQL_PASSWORD"]
    wait_for(lambda: "1" == docker("exec", "-e", "MYSQL_PWD", mysql_id, "mysql", "-ud01_probe", "--batch", "--skip-column-names", ENV["D01_DATABASE"], "-e", "SELECT 1", timeout=8), stage="mysql")
    wait_for(lambda: any("ready" in str(row).lower() for row in taos("SHOW DNODES").get("data", [])), stage="tdengine")


def main():
    try:
        assert docker("info", "--format", "{{.OSType}}", timeout=15) == "linux"
        emit("LOCAL_DOCKER_READY")
        for image in ["mysql:8.0", "tdengine/tdengine:3.2.3.0"]:
            docker("image", "inspect", image, timeout=20)
        network = "d01-net-" + NONCE
        docker("network", "create", "--label", LABEL, "--driver", "bridge",
               "--opt", "com.docker.network.bridge.host_binding_ipv4=127.0.0.1", network)
        RESOURCES["networks"].append(network)
        volumes = []
        for name in ["d01-mysql-" + NONCE, "d01-taos-" + NONCE]:
            docker("volume", "create", "--label", LABEL, name)
            RESOURCES["volumes"].append(name)
            volumes.append(name)
        mysql_id, mysql_port = container("mysql:8.0", 3306, network, volumes[0],
            ("-e", "MYSQL_ROOT_PASSWORD", "-e", "MYSQL_PASSWORD", "-e", "MYSQL_USER=d01_probe", "-e", "MYSQL_DATABASE=" + ENV["D01_DATABASE"]))
        td_id, td_port = container("tdengine/tdengine:3.2.3.0", 6041, network, volumes[1], ("-e", "TZ=Asia/Shanghai"))
        ENV["D01_MYSQL_URL"] = "jdbc:mysql://127.0.0.1:" + str(mysql_port) + "/" + ENV["D01_DATABASE"] + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
        ENV["D01_TD_PORT"] = str(td_port)
        ENV["D01_TD_URL"] = "jdbc:TAOS-RS://127.0.0.1:" + str(td_port) + "/?timezone=Asia/Shanghai"
        emit("ISOLATED_ENGINES_CREATED loopback_only=yes private_network=yes separate_volumes=yes memory_limit=768m_each")
        ready(mysql_id)
        scenarios(mysql_id, td_id)
    finally:
        for identifier in reversed(RESOURCES["containers"]):
            owned("container", identifier)
            docker("rm", "-f", identifier)
        for identifier in reversed(RESOURCES["networks"]):
            owned("network", identifier)
            docker("network", "rm", identifier)
        for identifier in reversed(RESOURCES["volumes"]):
            owned("volume", identifier)
            docker("volume", "rm", identifier)
        if RESOURCES["containers"]:
            emit("OWNED_ISOLATED_RESOURCES_REMOVED")
        (HERE / "d01-acceptance-results.txt").write_text("\n".join(LOG) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
