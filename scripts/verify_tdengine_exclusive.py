"""只读部署核验：社区版以项目独占实例隔离；不能用 GRANT 代替实例边界。"""
import argparse
import base64
import json
import os
import posixpath
import re
import subprocess
import urllib.request


PROJECT_LABEL = "bec.security.project"
PROJECT = "building-energy-carbon-platform"
EXCLUSIVE_LABEL = "bec.security.tdengine-exclusive"
SYSTEM_DATABASES = {"information_schema", "performance_schema"}
DATA_DIRECTORY = "/var/lib/taos"


class BoundaryError(RuntimeError):
    """仅携带固定诊断，不输出凭据、Docker 环境变量或 SQL 响应。"""


def require(condition, message):
    if not condition:
        raise BoundaryError(message)


def docker(*arguments):
    try:
        return subprocess.check_output(["docker", *arguments], text=True,
                                       stderr=subprocess.PIPE, timeout=15).strip()
    except (OSError, subprocess.SubprocessError) as from_error:
        raise BoundaryError("Docker read-only inspection failed") from from_error


def inspect(kind, name):
    return json.loads(docker(kind, "inspect", name))[0]


def mount_target(path):
    require(isinstance(path, str) and path.startswith("/"), "Invalid TDengine mount target")
    # Docker 运行在 Linux；双前导斜杠不能成为绕过数据目录比较的另一种写法。
    return posixpath.normpath("/" + path.lstrip("/"))


def check_container(details, volume, network):
    require(details["State"]["Running"], "TDengine container is not running")
    require(details["Config"]["Image"] == "tdengine/tdengine:3.2.3.0", "TDengine image needs separate validation")
    labels = details["Config"].get("Labels") or {}
    require(labels.get(PROJECT_LABEL) == PROJECT and labels.get(EXCLUSIVE_LABEL) == "true",
            "TDengine project ownership or exclusivity label is missing")
    require(set(details["NetworkSettings"]["Networks"]) == {network}, "TDengine has an unexpected network")
    bindings = {key: value for key, value in details["NetworkSettings"]["Ports"].items() if value}
    require(set(bindings) == {"6041/tcp"}, "Only TDengine REST may be published")
    endpoints = bindings["6041/tcp"]
    require(len(endpoints) == 1 and endpoints[0]["HostIp"] == "127.0.0.1", "TDengine REST must bind IPv4 loopback only")
    port = endpoints[0]["HostPort"]
    require(str(port).isdigit() and 0 < int(port) < 65536, "Invalid TDengine REST port")
    host_config = details.get("HostConfig") or {}
    targets = [item["Destination"] for item in details["Mounts"]]
    declared_mounts = host_config.get("Mounts") or []
    targets.extend(item["Target"] for item in declared_mounts)
    tmpfs_targets = list((host_config.get("Tmpfs") or {}).keys())
    # 根卷存在并不保证子目录仍落在该卷；tmpfs 还可能只记录在 HostConfig 中。
    require(not any(mount_target(target).startswith(DATA_DIRECTORY + "/") for target in targets + tmpfs_targets)
            and not any(mount_target(target) == DATA_DIRECTORY for target in tmpfs_targets)
            and not any(mount_target(item["Target"]) == DATA_DIRECTORY and item["Type"] == "tmpfs"
                        for item in declared_mounts),
            "TDengine data directory has an overriding submount")
    mounts = [item for item in details["Mounts"] if mount_target(item["Destination"]) == DATA_DIRECTORY]
    require(len(mounts) == 1 and mounts[0]["Type"] == "volume" and mounts[0].get("Name") == volume,
            "TDengine must use the expected dedicated named data volume")
    return "http://127.0.0.1:" + str(port) + "/rest/sql"


def check_network(details):
    require(details["Driver"] == "bridge" and not details.get("Internal"), "TDengine needs the host-accessible data bridge")
    options = details.get("Options") or {}
    require(options.get("com.docker.network.bridge.enable_icc") == "false"
            and options.get("com.docker.network.bridge.host_binding_ipv4") == "127.0.0.1",
            "Data bridge must disable inter-container access and default to loopback")
    require((details.get("Labels") or {}).get(PROJECT_LABEL) == PROJECT, "Data network project ownership is missing")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise BoundaryError("TDengine audit must not follow redirects")


def read_catalog(url, password):
    # 必须使用管理员读取完整目录；SYSINFO 受限的应用账号可能隐藏其他库，形成假通过。
    require(len(password) >= 16, "Provide the protected TDengine audit administrator password")
    header = {"Authorization": "Basic " + base64.b64encode(("root:" + password).encode()).decode()}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(urllib.request.Request(url, data=b"SHOW DATABASES", headers=header), timeout=5) as response:
            result = json.load(response)
        require(result.get("code") == 0, "TDengine complete catalog query failed")
        columns = [column[0].lower() for column in result["column_meta"]]
        require("name" in columns, "Unexpected TDengine database catalog format")
        name_index = columns.index("name")
        return {row[name_index] for row in result["data"]}
    except BoundaryError:
        raise
    except Exception as error:
        raise BoundaryError("TDengine administrator catalog is unavailable") from error


def check_catalog(names, database):
    require(re.fullmatch(r"[a-zA-Z_][a-zA-Z0-9_]*", database) is not None
            and database not in SYSTEM_DATABASES, "Invalid project database")
    require(names == SYSTEM_DATABASES | {database}, "TDengine project database is missing or another business database is present")


def verify_instance(container, volume, network, database, password):
    details = inspect("container", container)
    url = check_container(details, volume, network)
    owners = set(docker("ps", "-aq", "--no-trunc", "--filter", "volume=" + volume).splitlines())
    # 连停止的其他容器也拒绝，避免下一次启动重新共享同一数据目录。
    require(owners == {details["Id"]}, "TDengine data volume is attached to another container")
    network_details = inspect("network", network)
    check_network(network_details)
    for member in network_details.get("Containers", {}):
        peer = inspect("container", member)
        require((peer["Config"].get("Labels") or {}).get(PROJECT_LABEL) == PROJECT,
                "Data network contains a container outside this project")
    check_catalog(read_catalog(url, password), database)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", required=True)
    parser.add_argument("--volume", required=True)
    parser.add_argument("--network", required=True)
    parser.add_argument("--database", default="iot_telemetry")
    args = parser.parse_args()
    try:
        verify_instance(args.container, args.volume, args.network, args.database,
                        os.environ.get("TDENGINE_AUDIT_PASSWORD", ""))
    except (BoundaryError, KeyError, TypeError, ValueError) as error:
        # 不输出异常链，避免来自 Docker/HTTP 的配置内容进入部署日志。
        message = str(error) if isinstance(error, BoundaryError) else "Unexpected inspection response"
        parser.exit(1, "TDENGINE_EXCLUSIVE_CHECK_FAILED: " + message + "\n")
    print("TDENGINE_EXCLUSIVE_CHECK_OK")


if __name__ == "__main__":
    main()
