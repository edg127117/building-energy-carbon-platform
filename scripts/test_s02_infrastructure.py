"""一次性容器验证 S02 模板；只清理带本次随机标签的自建资源，不连接部署服务。"""
import base64
import json
import os
import pathlib
import socket
import struct
import subprocess
import time
import urllib.error
import urllib.request
import uuid


ROOT = pathlib.Path(__file__).resolve().parent.parent
NONCE = uuid.uuid4().hex
LABEL = "bec.s02.test=" + NONCE
OWNED = []
NETWORKS = []
ENV = os.environ.copy()


def docker(*arguments, env=None):
    return subprocess.check_output(["docker", *arguments], text=True, stderr=subprocess.PIPE, env=env or ENV).strip()


def container(image, ports, options):
    identifier = docker("run", "-d", "--label", LABEL, *sum((["-p", "127.0.0.1::" + str(p)] for p in ports), []), *options, image)
    OWNED.append(identifier)
    return identifier


def port(identifier, internal):
    data = json.loads(docker("inspect", identifier))[0]
    assert data["Config"]["Labels"]["bec.s02.test"] == NONCE
    mapping = data["NetworkSettings"]["Ports"][str(internal) + "/tcp"][0]
    assert mapping["HostIp"] == "127.0.0.1"
    return int(mapping["HostPort"])


def request(url, payload=None, headers=None):
    data = None if payload is None else (payload if isinstance(payload, bytes) else json.dumps(payload).encode())
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers or {}), timeout=5) as result:
            return result.status, json.load(result)
    except urllib.error.HTTPError as error:
        return error.code, {}


def await_ready(probe):
    deadline = time.monotonic() + 100
    while time.monotonic() < deadline:
        try:
            if probe():
                return
        except (OSError, ValueError, subprocess.CalledProcessError):
            pass
        time.sleep(1)
    raise AssertionError("isolated container readiness timed out")


def compose_boundaries():
    configuration = ENV.copy()
    for key in ["MYSQL_ROOT_PASSWORD", "MYSQL_PASSWORD", "REDIS_PASSWORD", "EMQX_DASHBOARD_PASSWORD"]:
        configuration[key] = uuid.uuid4().hex
    result = json.loads(docker("compose", "-f", str(ROOT / "src/env/docker-compose.yml"), "config", "--format=json", env=configuration))
    for service in result["services"].values():
        assert all(p["host_ip"] == "127.0.0.1" for p in service.get("ports", [])), "unexpected non-loopback default"
    assert [p["target"] for p in result["services"]["tdengine"]["ports"]] == [6041]
    assert result["networks"]["data"]["internal"] is True
    assert "data" not in result["services"]["emqx"]["networks"]
    del configuration["REDIS_PASSWORD"]
    try:
        docker("compose", "-f", str(ROOT / "src/env/docker-compose.yml"), "config", "--quiet", env=configuration)
    except subprocess.CalledProcessError:
        pass
    else:
        raise AssertionError("Compose accepted a missing Redis secret")


def redis_template():
    ENV["REDIS_PASSWORD"] = uuid.uuid4().hex
    # 启动真实仓库脚本，而不是在测试里重新实现 ACL。
    identifier = docker("run", "-d", "--label", LABEL, "--network=none", "-e", "REDIS_PASSWORD", "-e", "REDIS_USER=platform_app",
                        "-v", str(ROOT / "src/env/redis-start.sh") + ":/usr/local/bin/platform-redis-start:ro",
                        "-v", str(ROOT / "src/env/redis-app.permissions") + ":/usr/local/etc/platform-redis-permissions:ro",
                        "--entrypoint", "/bin/sh", "redis:7.2-alpine", "/usr/local/bin/platform-redis-start")
    OWNED.append(identifier)
    ENV["REDISCLI_AUTH"] = ENV["REDIS_PASSWORD"]
    await_ready(lambda: docker("exec", "-e", "REDISCLI_AUTH", identifier, "redis-cli", "--user", "platform_app", "ping") == "PONG")
    assert docker("exec", identifier, "sh", "-c", "awk '/^Uid:/{print $2}' /proc/1/status") != "0", "Redis must drop root"
    assert "NOAUTH" in docker("exec", identifier, "redis-cli", "ping")
    assert "NOPERM" in docker("exec", "-e", "REDISCLI_AUTH", identifier, "redis-cli", "--user", "platform_app", "FLUSHDB")
    assert "NOPERM" in docker("exec", "-e", "REDISCLI_AUTH", identifier, "redis-cli", "--user", "platform_app", "GET", "other-project:key")


def mqtt_string(value):
    value = value.encode()
    return struct.pack("!H", len(value)) + value


def packet(connection):
    def read(size):
        data = b""
        while len(data) < size:
            chunk = connection.recv(size - len(data))
            if not chunk:
                raise ConnectionError("MQTT peer closed")
            data += chunk
        return data
    kind = read(1)[0]
    length, multiplier = 0, 1
    while True:
        digit = read(1)[0]
        length += (digit & 127) * multiplier
        if not digit & 128:
            break
        multiplier *= 128
    return kind, read(length)


def send(connection, kind, body):
    size, encoded = len(body), bytearray()
    while True:
        digit = size % 128
        size //= 128
        encoded.append(digit | (128 if size else 0))
        if not size:
            break
    connection.sendall(bytes([kind]) + encoded + body)


def connect_mqtt(number, user=None, password=None, accepted=True):
    connection = socket.create_connection(("127.0.0.1", number), timeout=3)
    connection.settimeout(2)
    body = mqtt_string("MQTT") + bytes([4, 0xC2 if user else 2]) + b"\x00\x1e" + mqtt_string("s02-" + uuid.uuid4().hex[:12])
    if user:
        body += mqtt_string(user) + mqtt_string(password)
    send(connection, 0x10, body)
    kind, result = packet(connection)
    assert kind == 0x20 and (result[1] == 0) == accepted, "MQTT authentication boundary"
    return connection


def subscribe(connection, topic, allowed):
    send(connection, 0x82, b"\x00\x01" + mqtt_string(topic) + b"\x00")
    kind, result = packet(connection)
    assert kind == 0x90 and (result[2] != 128) == allowed, "MQTT subscription ACL boundary"


def emqx_template():
    ENV["EMQX_DASHBOARD__DEFAULT_PASSWORD"] = uuid.uuid4().hex
    identifier = container("emqx/emqx:5.6.0", [18083, 1883], [
        "-e", "EMQX_DASHBOARD__DEFAULT_USERNAME=ops-admin", "-e", "EMQX_DASHBOARD__DEFAULT_PASSWORD",
        "-e", "EMQX_AUTHENTICATION__1__MECHANISM=password_based", "-e", "EMQX_AUTHENTICATION__1__BACKEND=built_in_database",
        "-e", "EMQX_AUTHENTICATION__1__USER_ID_TYPE=username", "-e", "EMQX_AUTHORIZATION__NO_MATCH=deny",
        "-v", str(ROOT / "src/env/emqx-acl.conf") + ":/opt/emqx/etc/acl.conf:ro"])
    api = "http://127.0.0.1:" + str(port(identifier, 18083)) + "/api/v5"
    number = port(identifier, 1883)
    credentials = {"username": "ops-admin", "password": ENV["EMQX_DASHBOARD__DEFAULT_PASSWORD"]}
    await_ready(lambda: request(api + "/login", credentials, {"Content-Type": "application/json"})[0] == 200)
    status, login = request(api + "/login", credentials, {"Content-Type": "application/json"})
    assert status == 200
    assert request(api + "/clients")[0] == 401, "Broker management must authenticate"
    header = {"Authorization": "Bearer " + login["token"], "Content-Type": "application/json"}
    passwords = {name: uuid.uuid4().hex for name in ["platform-backend", "platform-device"]}
    for user, password in passwords.items():
        assert request(api + "/authentication/password_based:built_in_database/users",
                       {"user_id": user, "password": password, "is_superuser": False}, header)[0] == 201
    with connect_mqtt(number, accepted=False):
        pass
    with connect_mqtt(number, "platform-backend", "wrong-test-password", accepted=False):
        pass
    with connect_mqtt(number, "platform-backend", credentials["password"], accepted=False):
        pass
    with connect_mqtt(number, "platform-backend", passwords["platform-backend"]) as backend:
        subscribe(backend, "device/data/up", True)
        subscribe(backend, "#", False)
        with connect_mqtt(number, "platform-device", passwords["platform-device"]) as device:
            subscribe(device, "device/data/up", False)
            subscribe(device, "platform/telemetry/v2/ack/adapter", True)
            send(device, 0x30, mqtt_string("device/data/up") + b"s02-isolated-probe")
            kind, body = packet(backend)
            assert kind == 0x30 and body.endswith(b"s02-isolated-probe"), "authorized telemetry did not arrive"


def tdengine_accounts():
    network = docker("network", "create", "--internal", "--label", LABEL, "s02-data-" + NONCE[:12])
    NETWORKS.append(network)
    identifier = container("tdengine/tdengine:3.2.3.0", [6041], ["--network", network])
    api = "http://127.0.0.1:" + str(port(identifier, 6041)) + "/rest/sql"
    def sql(statement, username="root", password="taosdata"):
        header = {"Authorization": "Basic " + base64.b64encode((username + ":" + password).encode()).decode()}
        status, result = request(api, statement.encode(), header)
        return status == 200 and result.get("code") == 0
    await_ready(lambda: sql("SELECT SERVER_VERSION()"))
    assert request(api, b"SELECT SERVER_VERSION()")[0] == 401
    assert sql("CREATE DATABASE s02_project") and sql("CREATE DATABASE s02_other")
    password = uuid.uuid4().hex[:24]
    assert sql("CREATE USER s02_app PASS '" + password + "' SYSINFO 0"), "create dedicated TDengine account"
    for permission in ["READ", "WRITE"]:
        assert sql("GRANT " + permission + " ON s02_project.* TO s02_app"), "grant project TDengine access"
    assert sql("CREATE STABLE s02_project.st(ts TIMESTAMP,v INT) TAGS(d INT)", "s02_app", password)
    assert sql("INSERT INTO s02_project.t USING s02_project.st TAGS(1) VALUES(NOW,1)", "s02_app", password)
    assert sql("SELECT COUNT(*) FROM s02_project.st", "s02_app", password)
    assert sql("CREATE STABLE s02_other.st(ts TIMESTAMP,v INT) TAGS(d INT)")
    assert not sql("SELECT COUNT(*) FROM s02_other.st", "s02_app", password), "TDengine cross-database access"
    new_root = uuid.uuid4().hex[:24]
    assert sql("ALTER USER root PASS '" + new_root + "'"), "rotate isolated default administrator password"
    assert not sql("SELECT SERVER_VERSION()"), "old TDengine default password still works"
    assert sql("SELECT SERVER_VERSION()", "root", new_root)


def main():
    assert os.environ.get("S02_INFRA_IT_ISOLATED") == "true", "explicit isolated test opt-in required"
    try:
        for check in [compose_boundaries, redis_template, emqx_template, tdengine_accounts]:
            check()
            print(check.__name__ + "=PASS", flush=True)
    finally:
        for identifier in reversed(OWNED):
            data = json.loads(docker("inspect", identifier))[0]
            assert data["Config"]["Labels"]["bec.s02.test"] == NONCE, "cleanup owner mismatch"
            docker("rm", "-f", "-v", identifier)
        for network in NETWORKS:
            data = json.loads(docker("network", "inspect", network))[0]
            assert data["Labels"]["bec.s02.test"] == NONCE, "network cleanup owner mismatch"
            docker("network", "rm", network)
        print("owned_container_cleanup=PASS", flush=True)


if __name__ == "__main__":
    main()
