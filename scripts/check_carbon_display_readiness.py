"""Deployment-time poller for the default carbon display cards."""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Sequence
from typing import Any


EXPECTED_SCOPE = "DEFAULT_FLOW_AND_INTENSITY_ONLY"
EXPECTED_ITEMS = {"FLOW_YEAR", "INTENSITY_YTD", "FLOW_MONTH", "INTENSITY_MONTH"}
ALLOWED_BUSINESS_STATES = {"AVAILABLE", "AVAILABLE_WITH_WARNINGS"}
MAX_REQUEST_SECONDS = 10.0
MAX_RESPONSE_BYTES = 1024 * 1024


class ReadinessError(Exception):
    """A concise, safe-to-display readiness failure."""


def validate_api_base(value: str) -> str:
    if not value or any(char.isspace() or ord(char) < 32 for char in value):
        raise ReadinessError("api-base 不是有效网络 URL")
    try:
        parsed = urllib.parse.urlsplit(value)
        hostname = parsed.hostname
        port = parsed.port
    except ValueError:
        raise ReadinessError("api-base 不是有效网络 URL") from None

    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.netloc
        or not hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
    ):
        raise ReadinessError("api-base 必须是无凭据、query 或 fragment 的 http/https URL")
    if port is not None and not 1 <= port <= 65535:
        raise ReadinessError("api-base 端口无效")
    if parsed.scheme == "http" and not _is_loopback(hostname):
        raise ReadinessError("http 仅允许 loopback 地址；远端部署必须使用 https")
    return value.rstrip("/")


def _is_loopback(hostname: str) -> bool:
    normalized = hostname.rstrip(".").lower()
    if normalized == "localhost":
        return True
    try:
        return ipaddress.ip_address(normalized).is_loopback
    except ValueError:
        return False


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req: urllib.request.Request, fp: Any, code: int,
                         msg: str, headers: Any, newurl: str) -> None:
        return None


def http_request(url: str, token: str, timeout_seconds: float) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
        method="GET",
    )
    opener = urllib.request.build_opener(_NoRedirect())
    try:
        with opener.open(request, timeout=timeout_seconds) as response:
            status = response.getcode()
            if status != 200:
                raise ReadinessError(f"接口返回 HTTP {status}")
            body = response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as exc:
        if exc.code in {301, 302, 303, 307, 308}:
            raise ReadinessError("接口返回重定向，已拒绝") from None
        raise

    if len(body) > MAX_RESPONSE_BYTES:
        raise ReadinessError("接口响应超过安全读取上限")
    try:
        payload = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise ReadinessError("接口响应不是有效 JSON") from None
    if not isinstance(payload, dict):
        raise ReadinessError("接口响应结构无效")
    return payload


def _contract_data(payload: dict[str, Any], building_id: str) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    if payload.get("code") != 200 or isinstance(payload.get("code"), bool):
        raise ReadinessError("接口业务 code 不是 200")
    data = payload.get("data")
    if not isinstance(data, dict):
        raise ReadinessError("接口 data 结构无效")
    if data.get("buildingId") != building_id:
        raise ReadinessError("响应建筑 ID 与请求不匹配")
    if data.get("scope") != EXPECTED_SCOPE:
        raise ReadinessError("响应 scope 不匹配")
    if data.get("configured") is not True:
        if data.get("state") == "NOT_CONFIGURED":
            raise ReadinessError("该建筑未配置预热")
        if data.get("state") == "CONFIGURATION_ERROR":
            raise ReadinessError("预热配置错误 (CARBON_DISPLAY_PREWARM_CONFIGURATION)")
        if data.get("configured") is False:
            raise ReadinessError("该建筑未配置预热")
        raise ReadinessError("响应 configured 字段无效")

    items = data.get("items")
    if not isinstance(items, list) or len(items) != len(EXPECTED_ITEMS):
        raise ReadinessError("响应就绪项数量不符合契约")
    by_code: dict[str, dict[str, Any]] = {}
    for item in items:
        if not isinstance(item, dict) or not isinstance(item.get("code"), str):
            raise ReadinessError("响应就绪项结构无效")
        code = item["code"]
        if code in by_code:
            raise ReadinessError("响应就绪项 code 重复")
        by_code[code] = item
    if set(by_code) != EXPECTED_ITEMS:
        raise ReadinessError("响应就绪项 code 不符合契约")

    ordered = [by_code[code] for code in sorted(EXPECTED_ITEMS)]
    for item in ordered:
        state = item.get("state")
        if state == "NOT_APPLICABLE" and item.get("errorCode") == "NO_CLOSED_MONTH":
            raise ReadinessError("本年尚无已结束月份，无法确认 YTD 就绪")
        if state == "FAILED":
            raise ReadinessError(f"就绪项 {item['code']} 失败 ({_safe_code(item.get('errorCode'))})")
        if state not in {"READY", "PREPARING"}:
            raise ReadinessError(f"就绪项 {item['code']} 状态异常 ({_safe_code(state)})")
        if state == "READY" and item.get("ready") is not True:
            raise ReadinessError(
                f"就绪项 {item['code']} 未就绪 ({_safe_code(item.get('errorCode'))})"
            )
        if state == "READY" and item.get("businessState") not in ALLOWED_BUSINESS_STATES:
            raise ReadinessError(f"就绪项 {item['code']} 业务状态不可用 ({_safe_code(item.get('businessState'))})")
    return data, ordered


def _safe_code(value: Any) -> str:
    known_codes = {
        "READY", "PREPARING", "NOT_CONFIGURED", "CONFIGURATION_ERROR", "NOT_READY",
        "CARBON_DISPLAY_ACTOR_DISABLED", "CARBON_DISPLAY_ACTOR_FORBIDDEN",
        "NOT_APPLICABLE", "FAILED", "NO_CLOSED_MONTH", "AVAILABLE", "AVAILABLE_WITH_WARNINGS",
        "ACTUAL_UNAVAILABLE", "REFERENCE_UNAVAILABLE", "REFERENCE_PERCENTAGE_UNAVAILABLE",
        "FLOW_SOURCE_INCOMPLETE", "FLOW_GRAPH_UNAVAILABLE", "RESULT_INVALID",
        "CARBON_DISPLAY_PREWARM_CONFIGURATION", "CARBON_DISPLAY_TARGET_AMBIGUOUS",
    }
    if isinstance(value, str) and value in known_codes:
        return value
    return "UNKNOWN"


def check_readiness(
    api_base: str,
    building_id: str,
    token: str,
    timeout_seconds: int,
    *,
    request: Callable[[str, str, float], dict[str, Any]] = http_request,
    clock: Callable[[], float] = time.monotonic,
    wait: Callable[[float], None] = time.sleep,
) -> None:
    base = validate_api_base(api_base)
    if not building_id:
        raise ReadinessError("building-id 不能为空")
    if not token:
        raise ReadinessError("环境变量 SCREEN_READINESS_TOKEN 未设置")
    if not 1 <= timeout_seconds <= 600:
        raise ReadinessError("timeout-seconds 必须在 1..600 范围内")

    encoded_id = urllib.parse.quote(building_id, safe="")
    url = f"{base}/v1/carbon-display/buildings/{encoded_id}/readiness"
    deadline = clock() + timeout_seconds
    while True:
        remaining = deadline - clock()
        if remaining <= 0:
            raise ReadinessError("就绪检查超过总时间预算")
        try:
            payload = request(url, token, min(remaining, MAX_REQUEST_SECONDS))
        except urllib.error.HTTPError as exc:
            if exc.code in {401, 403}:
                raise ReadinessError(f"接口鉴权失败 (HTTP {exc.code})") from None
            if exc.code == 503:
                retry = True
            elif exc.code in {301, 302, 303, 307, 308}:
                raise ReadinessError("接口返回重定向，已拒绝") from None
            else:
                raise ReadinessError(f"接口返回 HTTP {exc.code}") from None
        except (urllib.error.URLError, TimeoutError, socket.timeout, ConnectionError):
            retry = True
        else:
            if clock() >= deadline:
                raise ReadinessError("就绪检查超过总时间预算")
            data, items = _contract_data(payload, building_id)
            preparing = data.get("state") == "PREPARING" or any(
                item.get("state") == "PREPARING" for item in items
            )
            if data.get("state") not in {"READY", "PREPARING"}:
                if data.get("state") == "NOT_READY":
                    raise ReadinessError("默认碳展示业务数据未就绪")
                raise ReadinessError(f"总就绪状态异常 ({_safe_code(data.get('state'))})")
            if preparing:
                retry = True
            else:
                if data.get("state") != "READY" or data.get("ready") is not True:
                    raise ReadinessError("响应未满足 READY 条件")
                if any(item.get("state") != "READY" or item.get("ready") is not True for item in items):
                    raise ReadinessError("四项就绪状态不完整")
                return

        remaining = deadline - clock()
        if remaining <= 0:
            raise ReadinessError("就绪检查超过总时间预算")
        wait(min(2.0, remaining))


def _positive_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError("必须是整数") from None
    if not 1 <= parsed <= 600:
        raise argparse.ArgumentTypeError("必须在 1..600 范围内")
    return parsed


def main(
    argv: Sequence[str] | None = None,
    *,
    request: Callable[[str, str, float], dict[str, Any]] = http_request,
    clock: Callable[[], float] = time.monotonic,
    wait: Callable[[float], None] = time.sleep,
) -> int:
    parser = argparse.ArgumentParser(description="检查碳展示默认卡片部署前就绪状态")
    parser.add_argument("--api-base", required=True, help="明确的 http/https API 基础 URL")
    parser.add_argument("--building-id", required=True, help="目标建筑 ID")
    parser.add_argument("--timeout-seconds", type=_positive_int, default=120)
    args = parser.parse_args(argv)

    print("阶段：检查碳展示就绪状态")
    try:
        check_readiness(
            args.api_base,
            args.building_id,
            os.environ.get("SCREEN_READINESS_TOKEN", ""),
            args.timeout_seconds,
            request=request,
            clock=clock,
            wait=wait,
        )
    except ReadinessError as exc:
        print(f"失败：{exc}")
        return 1
    except Exception as exc:
        if isinstance(exc, (urllib.error.URLError, TimeoutError, socket.timeout, ConnectionError)):
            print("失败：接口连接暂不可用")
        else:
            print("失败：就绪检查发生未分类错误")
        return 1
    print("完成：四项默认碳展示均已就绪")
    return 0


if __name__ == "__main__":
    sys.exit(main())
