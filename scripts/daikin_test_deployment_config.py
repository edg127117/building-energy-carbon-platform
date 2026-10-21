"""仅供固定测试发布入口使用；配置不随应用产物替换，也不发送控制。"""

import os
import json
from pathlib import Path
import subprocess
import sys
import tempfile


TEST_SETTINGS = {
    "DAIKIN_CONTROL_ENABLED": "true",
    "DAIKIN_CONTROL_WIRE_CONFIRMED": "true",
    "DAIKIN_CONTROL_TEST_ACCESS_ENABLED": "false",
    "DAIKIN_CONTROL_TEST_OPEN_CONTROLS": "true",
    "DAIKIN_CONTROL_TEST_EQUIPMENT_IDS": "",
    "DAIKIN_CONTROL_TEST_WINDOW_EXPIRES_AT": "0",
    "DAIKIN_CONTROL_FAST_READBACK_TIMEOUT_MILLIS": "120000",
}


def runtime_settings(service):
    pid = subprocess.check_output(
        ["systemctl", "show", service, "-p", "MainPID", "--value"], text=True
    ).strip()
    if not pid.isdecimal() or int(pid) <= 0:
        raise SystemExit("TEST_SERVICE_NOT_RUNNING")
    entries = Path("/proc", pid, "environ").read_bytes().decode().split("\0")
    # 仅返回环境标记和控制参数，避免诊断输出包含同进程的厂家凭据。
    keys = set(TEST_SETTINGS) | {"AUDIT_ENVIRONMENT_MODE"}
    return {
        key: value
        for entry in entries if "=" in entry
        for key, value in [entry.split("=", 1)] if key in keys
    }


def require_test(runtime):
    if runtime.get("AUDIT_ENVIRONMENT_MODE") != "TEST":
        raise SystemExit("EXPLICIT_TEST_ENVIRONMENT_REQUIRED")


def atomic_write(path, content):
    temp = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=path.name + ".deploy-", delete=False) as stream:
            temp = Path(stream.name)
            os.chmod(temp, 0o600)
            stream.write(content)
        os.replace(temp, path)
    finally:
        if temp is not None:
            temp.unlink(missing_ok=True)


def prepare(config, backup, runtime):
    require_test(runtime)
    original = config.read_bytes()
    # 发布失败须恢复原配置，而不是把已开放的测试环境强制改为关闭。
    with backup.open("xb") as stream:
        os.chmod(backup, 0o600)
        stream.write(original)
    lines = [
        line for line in original.decode().splitlines()
        if line.split("=", 1)[0].strip() not in TEST_SETTINGS
    ]
    lines.extend(key + "=" + value for key, value in TEST_SETTINGS.items())
    atomic_write(config, ("\n".join(lines) + "\n").encode())


def verify(runtime):
    require_test(runtime)
    if any(runtime.get(key) != value for key, value in TEST_SETTINGS.items()):
        raise SystemExit("TEST_CONTROL_RUNTIME_CONFIG_MISMATCH")


def preserve(config, backup, runtime):
    require_test(runtime)
    with backup.open("xb") as stream:
        os.chmod(backup, 0o600)
        stream.write(config.read_bytes())
    # 文件与实际进程分别留证，重启后不能因另一份环境文件覆盖而悄悄改变控制边界。
    with backup.with_suffix(".runtime.json").open("x", encoding="utf-8") as stream:
        os.chmod(stream.name, 0o600)
        json.dump({key: runtime.get(key) for key in TEST_SETTINGS}, stream)


def verify_preserved(config, backup, runtime):
    require_test(runtime)
    before = json.loads(backup.with_suffix(".runtime.json").read_text())
    if config.read_bytes() != backup.read_bytes() or before != {key: runtime.get(key) for key in TEST_SETTINGS}:
        raise SystemExit("TEST_CONTROL_PRESERVED_CONFIG_MISMATCH")


def restore(config, backup):
    atomic_write(config, backup.read_bytes())


if __name__ == "__main__":
    action = sys.argv[1]
    config = Path("/etc/building-energy-carbon-test/daikin-control-disabled.env")
    service = "building-energy-carbon-test-platform.service"
    if action == "prepare":
        prepare(config, Path(sys.argv[2]), runtime_settings(service))
    elif action == "preserve":
        preserve(config, Path(sys.argv[2]), runtime_settings(service))
    elif action == "verify-preserved":
        verify_preserved(config, Path(sys.argv[2]), runtime_settings(service))
        print("DAIKIN_TEST_CONFIG_PRESERVED")
    elif action == "verify":
        verify(runtime_settings(service))
        print("DAIKIN_TEST_OPEN_DEPLOY_VERIFIED")
    elif action == "restore":
        restore(config, Path(sys.argv[2]))
    else:
        raise SystemExit("INVALID_TEST_CONFIG_ACTION")
