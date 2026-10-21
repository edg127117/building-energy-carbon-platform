#!/usr/bin/env python3
"""隔离回环环境的能碳配置 HTTP 容量与持续运行采样器。

此脚本只会访问参数文件中明确指定的 ``http(s)://127.0.0.1:<port>``
应用 API。它不会启动应用、创建测试数据、调用 Mock，也不会访问远端地址。
账号和密码只从本地参数文件引用的环境变量读取；JSONL、摘要和控制台都不会
写入账号、密码、JWT、Authorization 头、请求体或原始响应。

本地参数文件（不要提交）至少需要以下结构：

{
  "isolated": true,
  "base_url": "http://127.0.0.1:18081/api",
  "accounts": [
    {"username_env": "ENERGY_CAPACITY_USER_01", "password_env": "ENERGY_CAPACITY_PASSWORD_01"}
  ],
  "ids": {
    "building_id": "...",
    "project_version_id": "...",
    "calculation_batch_id": "...",
    "calculation_item_id": "..."
  }
}

默认是只读负载。若要有限执行带幂等键的计算重放，参数文件必须显式设置
``calculation_replay.enabled=true`` 和有界 ``max_requests``，同时命令行还必须
传入 ``--enable-calculation-replay``。双重开关避免把容量验收误变成写入任务。
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import http.client
import json
import math
import os
import random
import re
import socket
import ssl
import sys
import threading
import time
import uuid
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence
from urllib.parse import quote, urlsplit


SCRIPT_VERSION = "1.1"
DEFAULT_CONCURRENCY_STEPS = (10, 25, 50)
DEFAULT_DURATION_SECONDS = 300
DEFAULT_TIMEOUT_SECONDS = 15.0
DEFAULT_REQUEST_PAUSE_MS = 25
DEFAULT_MONITOR_INTERVAL_SECONDS = 10
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
MAX_MONITOR_BYTES = 1024 * 1024
LATENCY_RESERVOIR_SIZE = 10_000
DEFAULT_JSONL_SUCCESS_SAMPLE_EVERY = 100
MAX_JSONL_SUCCESS_EVENTS = 10_000
MAX_JSONL_ERROR_EVENTS = 2_000
MAX_MONITOR_EVENTS = 1_000
DEFAULT_MAX_JSONL_SUCCESS_EVENTS = MAX_JSONL_SUCCESS_EVENTS
DEFAULT_MAX_JSONL_ERROR_EVENTS = MAX_JSONL_ERROR_EVENTS
DEFAULT_MAX_MONITOR_EVENTS = MAX_MONITOR_EVENTS
MAX_MONITOR_METRIC_FIELDS = 32
MAX_MONITOR_METRIC_KEY_LENGTH = 96
MAX_ERROR_CLASSES_PER_ENDPOINT = 128
MAX_DRIFT_CLASSES_PER_ENDPOINT = 64
MAX_CONCURRENCY_STEPS = 10
WORKSPACE_ROOT = Path(__file__).resolve().parents[2]

# 这些数值是本次工具的临时工程目标，不是生产 SLA、容量承诺或现场验收结论。
TEMPORARY_ENGINEERING_TARGET = {
    "min_success_rate": 0.995,
    "max_p95_ms": 1500.0,
    "max_p99_ms": 3000.0,
    "max_error_count": 0,
}

SENSITIVE_KEY = re.compile(r"(?i)(password|secret|token|authorization|cookie|credential|apikey|api[_-]?key)")
METRIC_KEY = re.compile(r"(?i)(hikari|jdbc|connection|pool|process|cpu|rss|memory|thread|uptime|pid)")
ENVIRONMENT_NAME = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
SAFE_ERROR_CODE = re.compile(r"^[A-Z0-9_]{1,120}$")


class CapacityError(Exception):
    """可脱敏展示的参数、运行或验收失败。"""


class ConfigurationError(CapacityError):
    """参数文件或命令行未满足安全前置条件。"""


class RuntimeAcceptanceError(CapacityError):
    """真实应用调用无法建立可用基线，继续压测没有解释价值。"""


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def canonical_json_hash(value: Any) -> str:
    """对已解析 JSON 做确定性哈希，避免把原始证据正文落盘。"""
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def json_number(value: Any) -> float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value.strip())
        except ValueError:
            return None
    return None


def as_non_blank_text(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ConfigurationError(f"参数 {name} 必须是非空字符串")
    return value.strip()


def as_positive_int(value: Any, name: str, minimum: int, maximum: int) -> int:
    if isinstance(value, bool):
        raise ConfigurationError(f"参数 {name} 必须是整数")
    try:
        parsed = int(value)
    except (TypeError, ValueError) as error:
        raise ConfigurationError(f"参数 {name} 必须是整数") from error
    if parsed < minimum or parsed > maximum:
        raise ConfigurationError(f"参数 {name} 必须在 {minimum} 到 {maximum} 之间")
    return parsed


def as_positive_float(value: Any, name: str, minimum: float, maximum: float) -> float:
    if isinstance(value, bool):
        raise ConfigurationError(f"参数 {name} 必须是数值")
    try:
        parsed = float(value)
    except (TypeError, ValueError) as error:
        raise ConfigurationError(f"参数 {name} 必须是数值") from error
    if not math.isfinite(parsed) or parsed < minimum or parsed > maximum:
        raise ConfigurationError(f"参数 {name} 必须在 {minimum} 到 {maximum} 之间")
    return parsed


def parse_concurrency_steps(raw: str | Sequence[Any]) -> tuple[int, ...]:
    if isinstance(raw, str):
        entries: Iterable[Any] = [piece.strip() for piece in raw.split(",") if piece.strip()]
    elif isinstance(raw, Sequence):
        entries = raw
    else:
        raise ConfigurationError("并发阶梯必须是逗号分隔字符串或数组")
    steps = tuple(as_positive_int(item, "concurrency_steps", 1, 200) for item in entries)
    if not steps:
        raise ConfigurationError("至少需要一个并发阶梯")
    if len(steps) > MAX_CONCURRENCY_STEPS:
        raise ConfigurationError(f"并发阶梯最多 {MAX_CONCURRENCY_STEPS} 档")
    if len(set(steps)) != len(steps):
        raise ConfigurationError("并发阶梯不能重复")
    return steps


def validate_loopback_base_url(raw: str) -> str:
    """只接受字面 127.0.0.1，避免重定向、DNS 或代理悄然离开隔离环境。"""
    value = as_non_blank_text(raw, "base_url")
    parsed = urlsplit(value)
    if parsed.scheme not in {"http", "https"}:
        raise ConfigurationError("base_url 只允许 http 或 https")
    if parsed.hostname != "127.0.0.1":
        raise ConfigurationError("base_url 主机必须精确为 127.0.0.1")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ConfigurationError("base_url 不能含账号、查询参数或片段")
    try:
        port = parsed.port
    except ValueError as error:
        raise ConfigurationError("base_url 端口无效") from error
    if port is None or not (1 <= port <= 65535):
        raise ConfigurationError("base_url 必须显式指定 1 到 65535 的端口")
    normalized_path = parsed.path.rstrip("/")
    return f"{parsed.scheme}://127.0.0.1:{port}{normalized_path}"


def redact_value(value: Any) -> Any:
    """只用于本地监控快照等可选输入，永不让秘密键进入证据文件。"""
    if isinstance(value, Mapping):
        return {str(key): ("[REDACTED]" if SENSITIVE_KEY.search(str(key)) else redact_value(item))
                for key, item in value.items()}
    if isinstance(value, list):
        return [redact_value(item) for item in value]
    return value


@dataclass(frozen=True)
class AccountCredential:
    username: str
    password: str


@dataclass(frozen=True)
class Identifiers:
    building_id: str
    project_version_id: str
    calculation_batch_id: str
    calculation_item_id: str
    classification_version_id: str | None = None


@dataclass(frozen=True)
class ReplayPlan:
    enabled: bool
    max_requests: int = 0
    every_n_read_cycles: int = 0
    account_index: int = 0
    request: Mapping[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class Thresholds:
    min_success_rate: float
    max_p95_ms: float
    max_p99_ms: float
    max_error_count: int


@dataclass(frozen=True)
class CapacityPlan:
    base_url: str
    accounts: tuple[AccountCredential, ...]
    identifiers: Identifiers
    concurrency_steps: tuple[int, ...]
    duration_seconds: int
    request_timeout_seconds: float
    request_pause_ms: int
    max_response_bytes: int
    output_dir: Path
    monitor_file: Path | None
    monitor_interval_seconds: int
    max_monitor_events: int
    jsonl_success_sample_every: int
    max_jsonl_success_events: int
    max_jsonl_error_events: int
    replay: ReplayPlan
    thresholds: Thresholds


@dataclass(frozen=True)
class AccountSession:
    token: str


@dataclass
class HttpOutcome:
    http_success: bool
    status: int | None
    latency_ms: float
    error_class: str | None
    data: Any = None


def environment_value(name: Any, field: str) -> str:
    variable = as_non_blank_text(name, field)
    if not ENVIRONMENT_NAME.fullmatch(variable):
        raise ConfigurationError(f"环境变量名 {field} 无效")
    value = os.environ.get(variable)
    if value is None or not value:
        raise ConfigurationError(f"未设置所需环境变量 {variable}")
    return value


def parse_accounts(raw: Any) -> tuple[AccountCredential, ...]:
    if not isinstance(raw, list) or not raw:
        raise ConfigurationError("accounts 必须是非空数组")
    accounts: list[AccountCredential] = []
    names: set[str] = set()
    for index, item in enumerate(raw):
        if not isinstance(item, Mapping):
            raise ConfigurationError(f"accounts[{index}] 必须是对象")
        if "password" in item:
            raise ConfigurationError("参数文件不得直接保存 password；请使用 password_env")
        has_name = "username" in item
        has_name_env = "username_env" in item
        if has_name == has_name_env:
            raise ConfigurationError(f"accounts[{index}] 必须且只能提供 username 或 username_env")
        username = (as_non_blank_text(item["username"], f"accounts[{index}].username")
                    if has_name else environment_value(item["username_env"], f"accounts[{index}].username_env"))
        password = environment_value(item.get("password_env"), f"accounts[{index}].password_env")
        if username in names:
            raise ConfigurationError("accounts 中的用户名必须唯一；每个并发工作线程只能登录一次")
        names.add(username)
        accounts.append(AccountCredential(username=username, password=password))
    return tuple(accounts)


def parse_identifiers(raw: Any) -> Identifiers:
    if not isinstance(raw, Mapping):
        raise ConfigurationError("ids 必须是对象")
    classification = raw.get("classification_version_id")
    return Identifiers(
        building_id=as_non_blank_text(raw.get("building_id"), "ids.building_id"),
        project_version_id=as_non_blank_text(raw.get("project_version_id"), "ids.project_version_id"),
        calculation_batch_id=as_non_blank_text(raw.get("calculation_batch_id"), "ids.calculation_batch_id"),
        calculation_item_id=as_non_blank_text(raw.get("calculation_item_id"), "ids.calculation_item_id"),
        classification_version_id=(as_non_blank_text(classification, "ids.classification_version_id")
                                   if classification is not None else None),
    )


def parse_replay(raw: Any, cli_enabled: bool, identifiers: Identifiers, account_count: int) -> ReplayPlan:
    if raw is None:
        return ReplayPlan(enabled=False)
    if not isinstance(raw, Mapping):
        raise ConfigurationError("calculation_replay 必须是对象")
    enabled = raw.get("enabled", False)
    if not isinstance(enabled, bool):
        raise ConfigurationError("calculation_replay.enabled 必须是布尔值")
    if not enabled:
        return ReplayPlan(enabled=False)
    if not cli_enabled:
        raise ConfigurationError("计算重放需要同时传入 --enable-calculation-replay")
    max_requests = as_positive_int(raw.get("max_requests"), "calculation_replay.max_requests", 1, 1000)
    every_n = as_positive_int(raw.get("every_n_read_cycles", 20), "calculation_replay.every_n_read_cycles", 1, 100_000)
    account_index = as_positive_int(raw.get("account_index", 0), "calculation_replay.account_index", 0, account_count - 1)
    request = raw.get("request")
    if not isinstance(request, Mapping):
        raise ConfigurationError("calculation_replay.request 必须是对象")
    required = {"buildingId", "periodType", "startInclusive", "endExclusive", "timezoneId", "resultNature", "idempotencyKey"}
    missing = sorted(key for key in required if not request.get(key))
    if missing:
        raise ConfigurationError("calculation_replay.request 缺少必填字段")
    if request.get("buildingId") != identifiers.building_id:
        raise ConfigurationError("计算重放 buildingId 必须等于 ids.building_id")
    if any(SENSITIVE_KEY.search(str(key)) for key in request):
        raise ConfigurationError("计算重放请求不允许包含敏感字段")
    # 幂等键由操作者在隔离环境中固定提供，脚本绝不自动生成会导致新计算的键。
    return ReplayPlan(True, max_requests, every_n, account_index, dict(request))


def parse_thresholds(raw: Any) -> Thresholds:
    values = raw if isinstance(raw, Mapping) else {}
    if raw is not None and not isinstance(raw, Mapping):
        raise ConfigurationError("thresholds 必须是对象")
    return Thresholds(
        min_success_rate=as_positive_float(values.get("min_success_rate", TEMPORARY_ENGINEERING_TARGET["min_success_rate"]),
                                           "thresholds.min_success_rate", 0.0, 1.0),
        max_p95_ms=as_positive_float(values.get("max_p95_ms", TEMPORARY_ENGINEERING_TARGET["max_p95_ms"]),
                                     "thresholds.max_p95_ms", 1.0, 600_000.0),
        max_p99_ms=as_positive_float(values.get("max_p99_ms", TEMPORARY_ENGINEERING_TARGET["max_p99_ms"]),
                                     "thresholds.max_p99_ms", 1.0, 600_000.0),
        max_error_count=as_positive_int(values.get("max_error_count", TEMPORARY_ENGINEERING_TARGET["max_error_count"]),
                                        "thresholds.max_error_count", 0, 1_000_000),
    )


def local_monitor_path(raw: Any) -> Path | None:
    if raw is None or raw == "":
        return None
    value = as_non_blank_text(raw, "monitor_file")
    if value.startswith("\\\\") or value.startswith("//") or "://" in value:
        raise ConfigurationError("monitor_file 只能是本地文件路径")
    return Path(value)


def workspace_output_path(raw: Any) -> Path:
    """证据文件只写到当前工作区内，避免误写共享路径或其他环境。"""
    value = as_non_blank_text(raw, "output_dir")
    if value.startswith("\\\\") or value.startswith("//") or "://" in value:
        raise ConfigurationError("output_dir 只能是当前工作区内的本地路径")
    try:
        candidate = Path(value)
        output = (candidate if candidate.is_absolute() else WORKSPACE_ROOT / candidate).resolve()
        output.relative_to(WORKSPACE_ROOT)
    except (OSError, ValueError) as error:
        raise ConfigurationError("output_dir 必须位于当前工作区内") from error
    return output


def load_plan(args: argparse.Namespace) -> CapacityPlan:
    params_file = args.params_file
    try:
        raw = json.loads(params_file.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise ConfigurationError("未找到本地参数文件") from error
    except UnicodeDecodeError as error:
        raise ConfigurationError("参数文件必须是 UTF-8 JSON") from error
    except json.JSONDecodeError as error:
        raise ConfigurationError("参数文件不是有效 JSON") from error
    if not isinstance(raw, Mapping):
        raise ConfigurationError("参数文件根节点必须是对象")
    if raw.get("isolated") is not True:
        raise ConfigurationError("参数文件必须显式设置 isolated=true")
    if not args.confirm_isolated_local_app:
        raise ConfigurationError("必须显式传入 --confirm-isolated-local-app")

    base_url = validate_loopback_base_url(raw.get("base_url"))
    accounts = parse_accounts(raw.get("accounts"))
    identifiers = parse_identifiers(raw.get("ids"))
    concurrency_input: str | Sequence[Any] = args.concurrency if args.concurrency is not None else raw.get("concurrency_steps", DEFAULT_CONCURRENCY_STEPS)
    concurrency_steps = parse_concurrency_steps(concurrency_input)
    if len(accounts) < max(concurrency_steps):
        raise ConfigurationError("账户数量必须不少于最大并发；脚本禁止共享账户或重复登录")

    duration_value = args.duration_seconds if args.duration_seconds is not None else raw.get("duration_seconds", DEFAULT_DURATION_SECONDS)
    duration_seconds = as_positive_int(duration_value, "duration_seconds", 1, 86_400)
    timeout_value = args.request_timeout_seconds if args.request_timeout_seconds is not None else raw.get("request_timeout_seconds", DEFAULT_TIMEOUT_SECONDS)
    request_timeout_seconds = as_positive_float(timeout_value, "request_timeout_seconds", 1.0, 120.0)
    pause_value = args.request_pause_ms if args.request_pause_ms is not None else raw.get("request_pause_ms", DEFAULT_REQUEST_PAUSE_MS)
    request_pause_ms = as_positive_int(pause_value, "request_pause_ms", 0, 60_000)
    max_response = as_positive_int(raw.get("max_response_bytes", MAX_RESPONSE_BYTES), "max_response_bytes", 1024, 8 * 1024 * 1024)
    monitor_file = local_monitor_path(args.monitor_file if args.monitor_file is not None else raw.get("monitor_file"))
    monitor_interval = as_positive_int(raw.get("monitor_interval_seconds", DEFAULT_MONITOR_INTERVAL_SECONDS),
                                       "monitor_interval_seconds", 1, 3600)
    max_monitor_events = as_positive_int(raw.get("max_monitor_events", DEFAULT_MAX_MONITOR_EVENTS),
                                         "max_monitor_events", 1, MAX_MONITOR_EVENTS)
    jsonl_success_sample_every = as_positive_int(
        raw.get("jsonl_success_sample_every", DEFAULT_JSONL_SUCCESS_SAMPLE_EVERY),
        "jsonl_success_sample_every", 1, 1_000_000)
    max_jsonl_success_events = as_positive_int(
        raw.get("max_jsonl_success_events", DEFAULT_MAX_JSONL_SUCCESS_EVENTS),
        "max_jsonl_success_events", 1, MAX_JSONL_SUCCESS_EVENTS)
    max_jsonl_error_events = as_positive_int(
        raw.get("max_jsonl_error_events", DEFAULT_MAX_JSONL_ERROR_EVENTS),
        "max_jsonl_error_events", 1, MAX_JSONL_ERROR_EVENTS)
    replay = parse_replay(raw.get("calculation_replay"), args.enable_calculation_replay, identifiers, len(accounts))
    thresholds = parse_thresholds(raw.get("thresholds"))

    output_value = args.output_dir if args.output_dir is not None else raw.get("output_dir")
    if output_value:
        output_dir = workspace_output_path(output_value)
    else:
        stamp = datetime.now().strftime("%Y%m%dT%H%M%S")
        output_dir = workspace_output_path(str(WORKSPACE_ROOT / "target" / "energy-carbon-capacity" / f"{stamp}-{uuid.uuid4().hex[:8]}"))
    return CapacityPlan(
        base_url=base_url,
        accounts=accounts,
        identifiers=identifiers,
        concurrency_steps=concurrency_steps,
        duration_seconds=duration_seconds,
        request_timeout_seconds=request_timeout_seconds,
        request_pause_ms=request_pause_ms,
        max_response_bytes=max_response,
        output_dir=output_dir,
        monitor_file=monitor_file,
        monitor_interval_seconds=monitor_interval,
        max_monitor_events=max_monitor_events,
        jsonl_success_sample_every=jsonl_success_sample_every,
        max_jsonl_success_events=max_jsonl_success_events,
        max_jsonl_error_events=max_jsonl_error_events,
        replay=replay,
        thresholds=thresholds,
    )


class ApiClient:
    """每个工作线程持有一个直连回环 HTTP 连接，不读取系统代理配置。"""

    def __init__(self, base_url: str, timeout_seconds: float, max_response_bytes: int):
        parsed = urlsplit(base_url)
        self._scheme = parsed.scheme
        self._host = "127.0.0.1"
        self._port = parsed.port
        self._prefix = parsed.path.rstrip("/")
        self._timeout_seconds = timeout_seconds
        self._max_response_bytes = max_response_bytes
        self._connection: http.client.HTTPConnection | http.client.HTTPSConnection | None = None

    def close(self) -> None:
        if self._connection is not None:
            try:
                self._connection.close()
            finally:
                self._connection = None

    def _connection_for_request(self) -> http.client.HTTPConnection | http.client.HTTPSConnection:
        if self._connection is None:
            if self._scheme == "https":
                self._connection = http.client.HTTPSConnection(
                    self._host, self._port, timeout=self._timeout_seconds, context=ssl.create_default_context())
            else:
                self._connection = http.client.HTTPConnection(self._host, self._port, timeout=self._timeout_seconds)
        return self._connection

    def request(self, method: str, endpoint_path: str, token: str | None = None,
                payload: Mapping[str, Any] | None = None) -> HttpOutcome:
        if not endpoint_path.startswith("/") or "://" in endpoint_path:
            raise ConfigurationError("接口路径必须是相对业务路径")
        body = None
        headers = {"Accept": "application/json", "User-Agent": f"energy-carbon-capacity/{SCRIPT_VERSION}"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        target = f"{self._prefix}{endpoint_path}" or "/"
        started = time.perf_counter()
        try:
            connection = self._connection_for_request()
            connection.request(method, target, body=body, headers=headers)
            response = connection.getresponse()
            length = response.getheader("Content-Length")
            if length is not None:
                try:
                    if int(length) > self._max_response_bytes:
                        response.close()
                        self.close()
                        return HttpOutcome(False, response.status, (time.perf_counter() - started) * 1000,
                                           "RESPONSE_TOO_LARGE")
                except ValueError:
                    pass
            raw = response.read(self._max_response_bytes + 1)
            status = response.status
            if len(raw) > self._max_response_bytes:
                self.close()
                return HttpOutcome(False, status, (time.perf_counter() - started) * 1000, "RESPONSE_TOO_LARGE")
            if response.will_close:
                self.close()
            latency_ms = (time.perf_counter() - started) * 1000
        except (socket.timeout, TimeoutError):
            self.close()
            return HttpOutcome(False, None, (time.perf_counter() - started) * 1000, "TIMEOUT")
        except (http.client.HTTPException, OSError, ssl.SSLError):
            self.close()
            return HttpOutcome(False, None, (time.perf_counter() - started) * 1000, "NETWORK")

        try:
            payload_json = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            payload_json = None
        if not 200 <= status < 300:
            code = payload_json.get("errorCode") if isinstance(payload_json, Mapping) else None
            error_class = f"APP_{code}" if isinstance(code, str) and SAFE_ERROR_CODE.fullmatch(code) else f"HTTP_{status}"
            return HttpOutcome(False, status, latency_ms, error_class)
        if not isinstance(payload_json, Mapping):
            return HttpOutcome(False, status, latency_ms, "INVALID_JSON")
        if payload_json.get("success") is True and payload_json.get("code") == 200:
            return HttpOutcome(True, status, latency_ms, None, payload_json.get("data"))
        code = payload_json.get("errorCode")
        error_class = f"APP_{code}" if isinstance(code, str) and SAFE_ERROR_CODE.fullmatch(code) else f"HTTP_{status}"
        return HttpOutcome(False, status, latency_ms, error_class)


class EndpointStats:
    def __init__(self) -> None:
        self.requests = 0
        self.http_successes = 0
        self.acceptance_successes = 0
        self.errors: Counter[str] = Counter()
        self.drifts: Counter[str] = Counter()
        self._latencies: list[float] = []
        self._latency_observations = 0
        self._random = random.Random(0)

    def record(self, outcome: HttpOutcome, assertions: Sequence[str]) -> None:
        self.requests += 1
        self._latency_observations += 1
        if len(self._latencies) < LATENCY_RESERVOIR_SIZE:
            self._latencies.append(outcome.latency_ms)
        else:
            candidate = self._random.randrange(self._latency_observations)
            if candidate < LATENCY_RESERVOIR_SIZE:
                self._latencies[candidate] = outcome.latency_ms
        if outcome.status is not None and 200 <= outcome.status < 300:
            self.http_successes += 1
        if outcome.http_success and not assertions:
            self.acceptance_successes += 1
        if outcome.error_class:
            self._increment(self.errors, outcome.error_class, MAX_ERROR_CLASSES_PER_ENDPOINT, "OTHER_ERROR_CLASS")
        for assertion in assertions:
            self._increment(self.errors, assertion, MAX_ERROR_CLASSES_PER_ENDPOINT, "OTHER_ERROR_CLASS")
            if assertion.endswith("_DRIFT"):
                self._increment(self.drifts, assertion, MAX_DRIFT_CLASSES_PER_ENDPOINT, "OTHER_DRIFT")

    @staticmethod
    def _increment(counter: Counter[str], key: str, maximum: int, overflow_key: str) -> None:
        if key in counter:
            counter[key] += 1
        elif len(counter) < maximum - 1:
            counter[key] += 1
        else:
            counter[overflow_key] += 1

    @staticmethod
    def percentile(values: Sequence[float], fraction: float) -> float | None:
        if not values:
            return None
        ordered = sorted(values)
        index = max(0, math.ceil(len(ordered) * fraction) - 1)
        return round(ordered[index], 3)

    def snapshot(self) -> dict[str, Any]:
        sample_kind = "exact" if self._latency_observations <= LATENCY_RESERVOIR_SIZE else "uniform_reservoir"
        return {
            "requests": self.requests,
            "http_success_rate": round(self.http_successes / self.requests, 6) if self.requests else None,
            "acceptance_success_rate": round(self.acceptance_successes / self.requests, 6) if self.requests else None,
            "p50_ms": self.percentile(self._latencies, 0.50),
            "p95_ms": self.percentile(self._latencies, 0.95),
            "p99_ms": self.percentile(self._latencies, 0.99),
            "latency_observations": self._latency_observations,
            "latency_sample_count": len(self._latencies),
            "latency_method": f"{sample_kind}_nearest_rank",
            "error_classes": dict(sorted(self.errors.items())),
            "business_drift": dict(sorted(self.drifts.items())),
        }


class JsonlRecorder:
    """聚合所有请求，JSONL 仅保留有界的成功采样和错误样本。"""

    def __init__(self, output_dir: Path, success_sample_every: int = DEFAULT_JSONL_SUCCESS_SAMPLE_EVERY,
                 max_success_events: int = DEFAULT_MAX_JSONL_SUCCESS_EVENTS,
                 max_error_events: int = DEFAULT_MAX_JSONL_ERROR_EVENTS) -> None:
        self.output_dir = output_dir
        self._events = (output_dir / "events.jsonl").open("x", encoding="utf-8", buffering=64 * 1024)
        self._lock = threading.Lock()
        self._event_count = 0
        self._phase_stats: dict[str, dict[str, EndpointStats]] = defaultdict(dict)
        self._capacity_stats: dict[str, EndpointStats] = {}
        self._success_sample_every = success_sample_every
        self._max_success_events = max_success_events
        self._max_error_events = max_error_events
        self._success_request_events = 0
        self._error_request_events = 0
        self._omitted_success_request_events = 0
        self._omitted_error_request_events = 0
        self._success_seen_by_endpoint: set[tuple[str, str]] = set()

    def emit(self, event: Mapping[str, Any]) -> None:
        with self._lock:
            self._write_event(event)

    def _write_event(self, event: Mapping[str, Any]) -> None:
        self._events.write(json.dumps(redact_value(event), ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")
        self._event_count += 1
        if self._event_count % 200 == 0:
            self._events.flush()

    def record(self, phase: str, endpoint: str, outcome: HttpOutcome, assertions: Sequence[str] = ()) -> None:
        accepted = outcome.http_success and not assertions
        with self._lock:
            phase_stats = self._phase_stats[phase].setdefault(endpoint, EndpointStats())
            phase_stats.record(outcome, assertions)
            if phase.startswith("concurrency-"):
                capacity_stats = self._capacity_stats.setdefault(endpoint, EndpointStats())
                capacity_stats.record(outcome, assertions)
            request_is_error = not accepted
            if request_is_error:
                if self._error_request_events >= self._max_error_events:
                    self._omitted_error_request_events += 1
                    return
                self._error_request_events += 1
            else:
                endpoint_key = (phase, endpoint)
                first_for_endpoint = endpoint_key not in self._success_seen_by_endpoint
                self._success_seen_by_endpoint.add(endpoint_key)
                sampled = first_for_endpoint or (phase_stats.requests % self._success_sample_every == 0)
                if not sampled or self._success_request_events >= self._max_success_events:
                    self._omitted_success_request_events += 1
                    return
                self._success_request_events += 1
            self._write_event({
                "kind": "request",
                "at": utc_now(),
                "phase": phase,
                "endpoint": endpoint,
                "http_status": outcome.status,
                "latency_ms": round(outcome.latency_ms, 3),
                "http_success": outcome.http_success,
                "acceptance_success": accepted,
                "error_class": outcome.error_class,
                "assertions": list(assertions),
            })

    def phase_snapshot(self) -> dict[str, dict[str, dict[str, Any]]]:
        with self._lock:
            return {phase: {endpoint: stats.snapshot() for endpoint, stats in sorted(endpoints.items())}
                    for phase, endpoints in sorted(self._phase_stats.items())}

    def capacity_snapshot(self) -> dict[str, dict[str, Any]]:
        with self._lock:
            return {endpoint: stats.snapshot() for endpoint, stats in sorted(self._capacity_stats.items())}

    def sampling_snapshot(self) -> dict[str, Any]:
        with self._lock:
            return {
                "success_sample_every": self._success_sample_every,
                "max_success_request_events": self._max_success_events,
                "emitted_success_request_events": self._success_request_events,
                "omitted_success_request_events": self._omitted_success_request_events,
                "max_error_request_events": self._max_error_events,
                "emitted_error_request_events": self._error_request_events,
                "omitted_error_request_events": self._omitted_error_request_events,
                "latency_reservoir_size_per_endpoint": LATENCY_RESERVOIR_SIZE,
            }

    def close(self) -> None:
        with self._lock:
            self._events.flush()
            self._events.close()


def flattened_numeric_metrics(value: Any, prefix: str = "", depth: int = 0) -> dict[str, float]:
    if depth > 5:
        return {}
    result: dict[str, float] = {}
    if isinstance(value, Mapping):
        for key, child in value.items():
            key_text = str(key)
            next_prefix = f"{prefix}.{key_text}" if prefix else key_text
            if len(next_prefix) > MAX_MONITOR_METRIC_KEY_LENGTH or SENSITIVE_KEY.search(next_prefix):
                continue
            result.update(flattened_numeric_metrics(child, next_prefix, depth + 1))
    elif isinstance(value, list):
        for index, child in enumerate(value[:100]):
            result.update(flattened_numeric_metrics(child, f"{prefix}[{index}]", depth + 1))
    else:
        number = json_number(value)
        if number is not None and METRIC_KEY.search(prefix):
            result[prefix] = number
    return result


class LocalMonitor:
    """只读可选本地监控文件；不调用受独立职责保护的 Actuator 接口。"""

    def __init__(self, path: Path | None, interval_seconds: int, max_events: int, recorder: JsonlRecorder):
        self._path = path
        self._interval_seconds = interval_seconds
        self._max_events = max_events
        self._recorder = recorder
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._availability = "not_configured" if path is None else "pending"
        self._samples = 0
        self._emitted_events = 0
        self._omitted_events = 0
        self._metric_ranges: dict[str, list[float]] = {}
        self._lock = threading.Lock()

    def start(self) -> None:
        if self._path is None:
            return
        self._thread = threading.Thread(target=self._run, name="capacity-local-monitor", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=self._interval_seconds + 2)

    def _run(self) -> None:
        while not self._stop.is_set():
            self.sample()
            self._stop.wait(self._interval_seconds)

    def sample(self) -> None:
        if self._path is None:
            return
        status = "ok"
        metrics: dict[str, float] = {}
        try:
            if not self._path.is_file():
                status = "missing"
            elif self._path.stat().st_size > MAX_MONITOR_BYTES:
                status = "too_large"
            else:
                raw = self._path.read_text(encoding="utf-8")
                metrics = parse_monitor_metrics(raw, self._path.suffix.lower())
                if not metrics:
                    status = "no_supported_numeric_metrics"
        except (OSError, UnicodeDecodeError, csv.Error, json.JSONDecodeError):
            status = "unreadable"
        with self._lock:
            self._availability = status
            if status == "ok":
                self._samples += 1
                for key, value in metrics.items():
                    bounds = self._metric_ranges.setdefault(key, [value, value])
                    bounds[0] = min(bounds[0], value)
                    bounds[1] = max(bounds[1], value)
            should_emit = self._emitted_events < self._max_events
            if should_emit:
                self._emitted_events += 1
            else:
                self._omitted_events += 1
        if should_emit:
            self._recorder.emit({"kind": "local_monitor", "at": utc_now(), "status": status, "metrics": metrics})

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return {
                "status": self._availability,
                "samples": self._samples,
                "max_events": self._max_events,
                "emitted_events": self._emitted_events,
                "omitted_events": self._omitted_events,
                "metric_ranges": {key: {"min": round(bounds[0], 6), "max": round(bounds[1], 6)}
                                  for key, bounds in sorted(self._metric_ranges.items())},
            }


def parse_monitor_metrics(raw: str, suffix: str) -> dict[str, float]:
    if suffix == ".csv":
        rows = list(csv.DictReader(raw.splitlines()))
        metrics = flattened_numeric_metrics(rows[-1] if rows else {})
    elif suffix == ".jsonl":
        parsed_rows = []
        for line in raw.splitlines():
            if line.strip():
                parsed_rows.append(json.loads(line))
        metrics = flattened_numeric_metrics(parsed_rows[-1] if parsed_rows else {})
    else:
        metrics = flattened_numeric_metrics(json.loads(raw))
    return dict(sorted(metrics.items())[:MAX_MONITOR_METRIC_FIELDS])


@dataclass(frozen=True)
class Baseline:
    classification_version_id: str
    report_hash: str
    report_total: str
    report_status: str
    source_evidence_hash: str
    declared_evidence_value_hash: str | None
    work_order_fingerprint: str


def api_path(*segments: str) -> str:
    return "/" + "/".join(quote(segment, safe="") for segment in segments)


def response_data_or_error(endpoint: str, outcome: HttpOutcome, recorder: JsonlRecorder, phase: str) -> Any:
    recorder.record(phase, endpoint, outcome)
    if not outcome.http_success:
        raise RuntimeAcceptanceError(f"基线接口 {endpoint} 调用失败：{outcome.error_class or 'UNKNOWN'}")
    return outcome.data


def find_project_version(data: Any, project_version_id: str) -> Mapping[str, Any]:
    if not isinstance(data, list):
        raise RuntimeAcceptanceError("项目版本接口未返回数组")
    for item in data:
        if isinstance(item, Mapping) and item.get("versionId") == project_version_id:
            return item
    raise RuntimeAcceptanceError("目标项目版本不在授权读取结果中")


def collect_baseline(plan: CapacityPlan, session: AccountSession, recorder: JsonlRecorder) -> Baseline:
    ids = plan.identifiers
    client = ApiClient(plan.base_url, plan.request_timeout_seconds, plan.max_response_bytes)
    try:
        project_path = api_path("v1", "carbon-projects", "buildings", ids.building_id, "versions")
        project_data = response_data_or_error("project_configuration", client.request("GET", project_path, session.token), recorder, "preflight")
        project = find_project_version(project_data, ids.project_version_id)
        classification_id = ids.classification_version_id
        if classification_id is None:
            configuration = project.get("configuration") if isinstance(project, Mapping) else None
            if isinstance(configuration, Mapping):
                classification_id = configuration.get("classificationVersionId")
        if not isinstance(classification_id, str) or not classification_id:
            raise RuntimeAcceptanceError("项目版本未提供可读取的分类版本；请在 ids.classification_version_id 显式指定")

        classification_path = api_path("v1", "energy-classification", "buildings", ids.building_id, "versions", classification_id)
        classification_data = response_data_or_error("classification_version", client.request("GET", classification_path, session.token), recorder, "preflight")
        if not isinstance(classification_data, Mapping) or classification_data.get("versionId") != classification_id:
            raise RuntimeAcceptanceError("分类版本读取结果与配置 ID 不一致")

        guide_path = api_path("v1", "carbon-projects", "buildings", ids.building_id, "versions", ids.project_version_id, "guide")
        guide_data = response_data_or_error("configuration_guide", client.request("GET", guide_path, session.token), recorder, "preflight")
        if not isinstance(guide_data, Mapping) or guide_data.get("versionId") != ids.project_version_id:
            raise RuntimeAcceptanceError("配置引导读取结果与项目版本不一致")

        work_order_path = api_path("v1", "carbon-projects", "buildings", ids.building_id, "versions", ids.project_version_id, "work-order")
        work_order = response_data_or_error("work_order_read", client.request("GET", work_order_path, session.token), recorder, "preflight")
        fingerprint = work_order.get("fingerprint") if isinstance(work_order, Mapping) else None
        if not isinstance(fingerprint, str) or not fingerprint:
            raise RuntimeAcceptanceError("工作单读取结果缺少 fingerprint")

        detail_path = api_path("v1", "carbon-management", "calculations", ids.calculation_batch_id)
        detail = response_data_or_error("calculation_detail", client.request("GET", detail_path, session.token), recorder, "preflight")
        items = detail.get("items") if isinstance(detail, Mapping) else None
        matched = next((item for item in items if isinstance(item, Mapping) and item.get("calculationItemId") == ids.calculation_item_id), None) if isinstance(items, list) else None
        if matched is None:
            raise RuntimeAcceptanceError("固定碳批次中没有配置的 calculation_item_id")
        declared_evidence_value = matched.get("evidenceHash") if isinstance(matched.get("evidenceHash"), str) else None

        report_path = api_path("v1", "carbon-management", "calculations", ids.calculation_batch_id, "classification-report")
        report = response_data_or_error("fixed_carbon_report", client.request("GET", report_path, session.token), recorder, "preflight")
        if not isinstance(report, Mapping) or report.get("buildingId") != ids.building_id or report.get("batchId") != ids.calculation_batch_id:
            raise RuntimeAcceptanceError("固定分类碳报表的建筑或批次与参数不一致")
        total = report.get("totalKgCo2e")
        if total is None:
            raise RuntimeAcceptanceError("固定分类碳报表没有可核对的业务总量")

        evidence_path = api_path("v1", "carbon-management", "calculations", ids.calculation_batch_id, "items", ids.calculation_item_id, "evidence")
        evidence = response_data_or_error("source_evidence", client.request("GET", evidence_path, session.token), recorder, "preflight")
        if evidence is None:
            raise RuntimeAcceptanceError("来源证据接口返回空数据")
        return Baseline(
            classification_version_id=classification_id,
            report_hash=canonical_json_hash(report),
            report_total=str(total),
            report_status=str(report.get("status", "")),
            source_evidence_hash=canonical_json_hash(evidence),
            declared_evidence_value_hash=(sha256_text(declared_evidence_value) if declared_evidence_value else None),
            work_order_fingerprint=fingerprint,
        )
    finally:
        client.close()


def collect_account_baselines(plan: CapacityPlan, sessions: Sequence[AccountSession],
                              baseline: Baseline, recorder: JsonlRecorder) -> tuple[Baseline, ...]:
    """工作单包含按职责隐藏的关系步骤，只能与同一账号启动时的指纹比较。"""
    values = [baseline]
    path = api_path("v1", "carbon-projects", "buildings", plan.identifiers.building_id,
                    "versions", plan.identifiers.project_version_id, "work-order")
    client = ApiClient(plan.base_url, plan.request_timeout_seconds, plan.max_response_bytes)
    try:
        for session in sessions[1:]:
            order = response_data_or_error("work_order_read", client.request("GET", path, session.token),
                                           recorder, "preflight")
            fingerprint = order.get("fingerprint") if isinstance(order, Mapping) else None
            if not isinstance(fingerprint, str) or not fingerprint:
                raise RuntimeAcceptanceError("至少一个账号的工作单缺少有效指纹")
            values.append(replace(baseline, work_order_fingerprint=fingerprint))
    finally:
        client.close()
    return tuple(values)


@dataclass(frozen=True)
class EndpointSpec:
    name: str
    path: Callable[[CapacityPlan, Baseline], str]


def endpoint_specs() -> tuple[EndpointSpec, ...]:
    return (
        EndpointSpec("classification_version", lambda plan, baseline: api_path(
            "v1", "energy-classification", "buildings", plan.identifiers.building_id, "versions", baseline.classification_version_id)),
        EndpointSpec("project_configuration", lambda plan, baseline: api_path(
            "v1", "carbon-projects", "buildings", plan.identifiers.building_id, "versions")),
        EndpointSpec("configuration_guide", lambda plan, baseline: api_path(
            "v1", "carbon-projects", "buildings", plan.identifiers.building_id, "versions", plan.identifiers.project_version_id, "guide")),
        EndpointSpec("work_order_read", lambda plan, baseline: api_path(
            "v1", "carbon-projects", "buildings", plan.identifiers.building_id, "versions", plan.identifiers.project_version_id, "work-order")),
        EndpointSpec("fixed_carbon_report", lambda plan, baseline: api_path(
            "v1", "carbon-management", "calculations", plan.identifiers.calculation_batch_id, "classification-report")),
        EndpointSpec("source_evidence", lambda plan, baseline: api_path(
            "v1", "carbon-management", "calculations", plan.identifiers.calculation_batch_id,
            "items", plan.identifiers.calculation_item_id, "evidence")),
    )


def validate_endpoint_data(name: str, data: Any, plan: CapacityPlan, baseline: Baseline) -> tuple[str, ...]:
    ids = plan.identifiers
    errors: list[str] = []
    if name == "classification_version":
        if not isinstance(data, Mapping) or data.get("versionId") != baseline.classification_version_id:
            errors.append("CLASSIFICATION_VERSION_MISMATCH")
    elif name == "project_configuration":
        try:
            find_project_version(data, ids.project_version_id)
        except RuntimeAcceptanceError:
            errors.append("PROJECT_VERSION_MISSING")
    elif name == "configuration_guide":
        if not isinstance(data, Mapping) or data.get("versionId") != ids.project_version_id:
            errors.append("CONFIGURATION_GUIDE_MISMATCH")
    elif name == "work_order_read":
        fingerprint = data.get("fingerprint") if isinstance(data, Mapping) else None
        if not isinstance(fingerprint, str) or not fingerprint:
            errors.append("WORK_ORDER_INVALID")
        elif fingerprint != baseline.work_order_fingerprint:
            errors.append("WORK_ORDER_FINGERPRINT_DRIFT")
    elif name == "fixed_carbon_report":
        if not isinstance(data, Mapping):
            errors.append("REPORT_INVALID")
        else:
            if data.get("buildingId") != ids.building_id or data.get("batchId") != ids.calculation_batch_id:
                errors.append("REPORT_SCOPE_MISMATCH")
            if str(data.get("totalKgCo2e")) != baseline.report_total:
                errors.append("BUSINESS_TOTAL_DRIFT")
            if canonical_json_hash(data) != baseline.report_hash:
                errors.append("REPORT_HASH_DRIFT")
    elif name == "source_evidence":
        if data is None:
            errors.append("SOURCE_EVIDENCE_MISSING")
        elif canonical_json_hash(data) != baseline.source_evidence_hash:
            errors.append("SOURCE_EVIDENCE_HASH_DRIFT")
    return tuple(errors)


class ReplayGate:
    def __init__(self, plan: CapacityPlan, sessions: Sequence[AccountSession], recorder: JsonlRecorder):
        self._plan = plan
        self._sessions = sessions
        self._recorder = recorder
        self._lock = threading.Lock()
        self._attempts = 0

    def execute_if_available(self, phase: str) -> None:
        if not self._plan.replay.enabled:
            return
        with self._lock:
            if self._attempts >= self._plan.replay.max_requests:
                return
            self._attempts += 1
            client = ApiClient(self._plan.base_url, self._plan.request_timeout_seconds, self._plan.max_response_bytes)
            try:
                outcome = client.request("POST", api_path("v1", "carbon-management", "calculations"),
                                         self._sessions[self._plan.replay.account_index].token, self._plan.replay.request)
                assertions: tuple[str, ...] = ()
                if outcome.http_success:
                    data = outcome.data
                    if not isinstance(data, Mapping) or data.get("idempotencyKey") != self._plan.replay.request["idempotencyKey"]:
                        assertions = ("CALCULATION_REPLAY_IDEMPOTENCY_MISMATCH",)
                self._recorder.record(phase, "calculation_replay", outcome, assertions)
            finally:
                client.close()

    @property
    def attempts(self) -> int:
        with self._lock:
            return self._attempts


def login_accounts(plan: CapacityPlan, recorder: JsonlRecorder) -> tuple[AccountSession, ...]:
    sessions: list[AccountSession] = []
    needed = max(plan.concurrency_steps)
    for credential in plan.accounts[:needed]:
        client = ApiClient(plan.base_url, plan.request_timeout_seconds, plan.max_response_bytes)
        try:
            outcome = client.request("POST", "/auth/login", payload={"username": credential.username, "password": credential.password})
            recorder.record("authentication", "login", outcome)
            token = outcome.data.get("token") if outcome.http_success and isinstance(outcome.data, Mapping) else None
            if not isinstance(token, str) or not token:
                raise RuntimeAcceptanceError("至少一个隔离验收账号登录失败；不会重试或共享账户")
            sessions.append(AccountSession(token=token))
        finally:
            client.close()
    return tuple(sessions)


def run_stage(plan: CapacityPlan, sessions: Sequence[AccountSession], baselines: Sequence[Baseline],
              recorder: JsonlRecorder, concurrency: int, replay_gate: ReplayGate) -> dict[str, Any]:
    phase = f"concurrency-{concurrency}"
    specs = endpoint_specs()
    barrier = threading.Barrier(concurrency + 1)
    deadline_holder: dict[str, float] = {}
    worker_errors = 0
    worker_lock = threading.Lock()

    def worker(index: int) -> None:
        nonlocal worker_errors
        client = ApiClient(plan.base_url, plan.request_timeout_seconds, plan.max_response_bytes)
        cycle = 0
        baseline = baselines[index]
        try:
            barrier.wait(timeout=60)
            deadline = deadline_holder["deadline"]
            while time.monotonic() < deadline:
                spec = specs[cycle % len(specs)]
                outcome = client.request("GET", spec.path(plan, baseline), sessions[index].token)
                assertions = validate_endpoint_data(spec.name, outcome.data, plan, baseline) if outcome.http_success else ()
                recorder.record(phase, spec.name, outcome, assertions)
                cycle += 1
                if plan.replay.enabled and cycle % plan.replay.every_n_read_cycles == 0:
                    replay_gate.execute_if_available(phase)
                if plan.request_pause_ms:
                    remaining = deadline - time.monotonic()
                    if remaining > 0:
                        time.sleep(min(plan.request_pause_ms / 1000.0, remaining))
        except (threading.BrokenBarrierError, CapacityError, KeyError):
            with worker_lock:
                worker_errors += 1
            recorder.emit({"kind": "worker_error", "at": utc_now(), "phase": phase, "error_class": "WORKER_INTERNAL"})
        except Exception:
            # 不把异常消息写入证据，避免来自第三方库或响应内容的意外泄露。
            with worker_lock:
                worker_errors += 1
            recorder.emit({"kind": "worker_error", "at": utc_now(), "phase": phase, "error_class": "WORKER_UNEXPECTED"})
        finally:
            client.close()

    started = time.monotonic()
    recorder.emit({"kind": "phase_started", "at": utc_now(), "phase": phase, "concurrency": concurrency,
                   "duration_seconds": plan.duration_seconds})
    with ThreadPoolExecutor(max_workers=concurrency, thread_name_prefix=f"carbon-capacity-{concurrency}") as executor:
        futures = [executor.submit(worker, index) for index in range(concurrency)]
        deadline_holder["deadline"] = time.monotonic() + plan.duration_seconds
        try:
            barrier.wait(timeout=60)
        except threading.BrokenBarrierError:
            worker_errors += concurrency
        for future in as_completed(futures):
            future.result()
    elapsed_ms = round((time.monotonic() - started) * 1000, 3)
    recorder.emit({"kind": "phase_finished", "at": utc_now(), "phase": phase, "concurrency": concurrency,
                   "elapsed_ms": elapsed_ms, "worker_errors": worker_errors})
    return {"concurrency": concurrency, "elapsed_ms": elapsed_ms, "worker_errors": worker_errors}


def evaluate_targets(plan: CapacityPlan, capacity_stats: Mapping[str, Mapping[str, Any]],
                     worker_errors: int) -> list[dict[str, Any]]:
    failures: list[dict[str, Any]] = []
    expected = [spec.name for spec in endpoint_specs()]
    if plan.replay.enabled:
        expected.append("calculation_replay")
    for endpoint in expected:
        stats = capacity_stats.get(endpoint)
        if stats is None or not stats["requests"]:
            failures.append({"endpoint": endpoint, "reason": "NO_CAPACITY_SAMPLE"})
            continue
        if stats["acceptance_success_rate"] < plan.thresholds.min_success_rate:
            failures.append({"endpoint": endpoint, "reason": "SUCCESS_RATE_BELOW_TARGET"})
        if stats["p95_ms"] is not None and stats["p95_ms"] > plan.thresholds.max_p95_ms:
            failures.append({"endpoint": endpoint, "reason": "P95_ABOVE_TARGET"})
        if stats["p99_ms"] is not None and stats["p99_ms"] > plan.thresholds.max_p99_ms:
            failures.append({"endpoint": endpoint, "reason": "P99_ABOVE_TARGET"})
        error_count = sum(stats["error_classes"].values())
        if error_count > plan.thresholds.max_error_count:
            failures.append({"endpoint": endpoint, "reason": "ERROR_COUNT_ABOVE_TARGET"})
        if stats["business_drift"]:
            failures.append({"endpoint": endpoint, "reason": "BUSINESS_DRIFT_DETECTED"})
    if worker_errors:
        failures.append({"endpoint": "workers", "reason": "WORKER_ERRORS"})
    return failures


def safe_id_hashes(ids: Identifiers, classification_version_id: str) -> dict[str, str]:
    return {
        "building": sha256_text(ids.building_id),
        "project_version": sha256_text(ids.project_version_id),
        "classification_version": sha256_text(classification_version_id),
        "calculation_batch": sha256_text(ids.calculation_batch_id),
        "calculation_item": sha256_text(ids.calculation_item_id),
    }


def write_summary(output_dir: Path, summary: Mapping[str, Any]) -> None:
    (output_dir / "summary.json").write_text(
        json.dumps(redact_value(summary), ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def execute(plan: CapacityPlan) -> int:
    if plan.output_dir.exists():
        raise ConfigurationError("输出目录已存在；为避免覆盖证据，请指定新的 output_dir")
    plan.output_dir.mkdir(parents=True, exist_ok=False)
    recorder = JsonlRecorder(plan.output_dir, plan.jsonl_success_sample_every,
                             plan.max_jsonl_success_events, plan.max_jsonl_error_events)
    monitor = LocalMonitor(plan.monitor_file, plan.monitor_interval_seconds, plan.max_monitor_events, recorder)
    started_at = utc_now()
    phase_results: list[dict[str, Any]] = []
    overall_worker_errors = 0
    baseline: Baseline | None = None
    run_status = "ABORTED"
    failures: list[dict[str, Any]] = []
    try:
        recorder.emit({
            "kind": "run_started", "at": started_at, "script_version": SCRIPT_VERSION,
            "loopback_only": True, "concurrency_steps": list(plan.concurrency_steps),
            "duration_seconds_per_step": plan.duration_seconds, "account_count": len(plan.accounts),
            "calculation_replay_enabled": plan.replay.enabled,
            "jsonl_success_sample_every": plan.jsonl_success_sample_every,
        })
        sessions = login_accounts(plan, recorder)
        baseline = collect_baseline(plan, sessions[0], recorder)
        account_baselines = collect_account_baselines(plan, sessions, baseline, recorder)
        monitor.start()
        replay_gate = ReplayGate(plan, sessions, recorder)
        for concurrency in plan.concurrency_steps:
            phase_result = run_stage(plan, sessions, account_baselines, recorder, concurrency, replay_gate)
            phase_results.append(phase_result)
            overall_worker_errors += phase_result["worker_errors"]
        monitor.stop()
        capacity_stats = recorder.capacity_snapshot()
        failures = evaluate_targets(plan, capacity_stats, overall_worker_errors)
        run_status = "PASS" if not failures else "FAIL"
        summary = {
            "kind": "final_summary",
            "at": utc_now(),
            "script_version": SCRIPT_VERSION,
            "verdict": f"TEMPORARY_ENGINEERING_TARGET_{run_status}",
            "scope": "isolated_loopback_real_application_http",
            "not_a_field_or_production_sla_acceptance": True,
            "id_hashes": safe_id_hashes(plan.identifiers, baseline.classification_version_id),
            "baseline": {
                "business_total": baseline.report_total,
                "report_status": baseline.report_status,
                "report_hash": baseline.report_hash,
                "source_evidence_hash": baseline.source_evidence_hash,
                "declared_evidence_value_hash": baseline.declared_evidence_value_hash,
                "work_order_fingerprint_hash": sha256_text(baseline.work_order_fingerprint),
            },
            "temporary_engineering_target": {
                "min_success_rate": plan.thresholds.min_success_rate,
                "max_p95_ms": plan.thresholds.max_p95_ms,
                "max_p99_ms": plan.thresholds.max_p99_ms,
                "max_error_count": plan.thresholds.max_error_count,
            },
            "phases": phase_results,
            "endpoint_statistics": capacity_stats,
            "all_phase_statistics": recorder.phase_snapshot(),
            "local_monitor": monitor.snapshot(),
            "jsonl_sampling": recorder.sampling_snapshot(),
            "write_controls": {
                "authentication_logins": len(sessions),
                "calculation_replay_enabled": plan.replay.enabled,
                "calculation_replay_max_requests": plan.replay.max_requests,
                "calculation_replay_attempts": replay_gate.attempts,
            },
            "calculation_replay": {"enabled": plan.replay.enabled, "attempts": replay_gate.attempts},
            "failures": failures,
        }
        recorder.emit(summary)
        write_summary(plan.output_dir, summary)
        return 0 if run_status == "PASS" else 1
    except Exception as error:
        monitor.stop()
        safe_error = error.__class__.__name__ if not isinstance(error, CapacityError) else str(error)
        summary = {
            "kind": "final_summary",
            "at": utc_now(),
            "script_version": SCRIPT_VERSION,
            "verdict": "TEMPORARY_ENGINEERING_TARGET_ABORTED",
            "scope": "isolated_loopback_real_application_http",
            "error_class": safe_error,
            "phases": phase_results,
            "all_phase_statistics": recorder.phase_snapshot(),
            "local_monitor": monitor.snapshot(),
            "jsonl_sampling": recorder.sampling_snapshot(),
        }
        recorder.emit(summary)
        write_summary(plan.output_dir, summary)
        if isinstance(error, CapacityError):
            raise
        raise RuntimeAcceptanceError("验收运行发生未分类错误；请依据脱敏 JSONL 复核") from error
    finally:
        recorder.close()


def sanitized_plan_view(plan: CapacityPlan) -> dict[str, Any]:
    return {
        "script_version": SCRIPT_VERSION,
        "loopback_only": True,
        "account_count": len(plan.accounts),
        "concurrency_steps": list(plan.concurrency_steps),
        "duration_seconds_per_step": plan.duration_seconds,
        "request_timeout_seconds": plan.request_timeout_seconds,
        "request_pause_ms": plan.request_pause_ms,
        "local_monitor_configured": plan.monitor_file is not None,
        "jsonl_sampling": {
            "success_sample_every": plan.jsonl_success_sample_every,
            "max_success_request_events": plan.max_jsonl_success_events,
            "max_error_request_events": plan.max_jsonl_error_events,
            "max_monitor_events": plan.max_monitor_events,
            "latency_reservoir_size_per_endpoint": LATENCY_RESERVOIR_SIZE,
        },
        "calculation_replay": {
            "enabled": plan.replay.enabled,
            "max_requests": plan.replay.max_requests,
            "every_n_read_cycles": plan.replay.every_n_read_cycles,
        },
        "temporary_engineering_target": {
            "min_success_rate": plan.thresholds.min_success_rate,
            "max_p95_ms": plan.thresholds.max_p95_ms,
            "max_p99_ms": plan.thresholds.max_p99_ms,
            "max_error_count": plan.thresholds.max_error_count,
        },
        "endpoint_set": [spec.name for spec in endpoint_specs()],
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--params-file", type=Path, required=True, help="未提交的本地 UTF-8 JSON 参数文件")
    parser.add_argument("--confirm-isolated-local-app", action="store_true", help="确认目标是独立隔离的本机应用")
    parser.add_argument("--concurrency", help="覆盖参数文件的并发阶梯，例如 10,25,50")
    parser.add_argument("--duration-seconds", type=int, help="覆盖每个并发阶梯的持续时间")
    parser.add_argument("--request-timeout-seconds", type=float, help="覆盖单请求超时秒数")
    parser.add_argument("--request-pause-ms", type=int, help="覆盖每个工作线程两次读取间暂停毫秒数")
    parser.add_argument("--output-dir", help="当前工作区内新建的本地证据目录；已存在目录会被拒绝")
    parser.add_argument("--monitor-file", help="可选的本地 JSON、JSONL 或 CSV 进程/连接池监控文件")
    parser.add_argument("--enable-calculation-replay", action="store_true", help="第二道显式开关，允许有限幂等计算重放")
    parser.add_argument("--dry-run", action="store_true", help="只校验隔离、账户、参数和目标，不发送 HTTP 请求")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        plan = load_plan(args)
        if args.dry_run:
            print(json.dumps(sanitized_plan_view(plan), ensure_ascii=False, sort_keys=True))
            return 0
        result = execute(plan)
        print(f"ENERGY_CARBON_CAPACITY_{'PASS' if result == 0 else 'FAIL'} output={plan.output_dir}")
        return result
    except ConfigurationError as error:
        print(f"ENERGY_CARBON_CAPACITY_CONFIGURATION_ERROR: {error}", file=sys.stderr)
        return 2
    except RuntimeAcceptanceError as error:
        print(f"ENERGY_CARBON_CAPACITY_ABORTED: {error}", file=sys.stderr)
        return 3
    except KeyboardInterrupt:
        print("ENERGY_CARBON_CAPACITY_INTERRUPTED", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
