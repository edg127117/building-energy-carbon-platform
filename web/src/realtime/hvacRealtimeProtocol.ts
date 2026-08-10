import {
  HVAC_REALTIME_INDICATOR_CODES,
  type HvacRealtimeIndicator,
  type HvacRealtimeIndicatorCode,
} from '@/types/hvac'

export type HvacRealtimeServerMessage =
  | { type: 'SUBSCRIBED'; buildingId: string; serverTime: number }
  | { type: 'PONG'; serverTime: number }
  | { type: 'HVAC_INDICATOR'; data: HvacRealtimeIndicator }
  | { type: 'ERROR'; code: string; message: string }

export type HvacRealtimeCloseKind =
  | 'normal'
  | 'protocol'
  | 'unauthorized'
  | 'forbidden'
  | 'timeout'
  | 'server_error'
  | 'network'

export type HvacRealtimeDisconnect = {
  code: number
  kind: HvacRealtimeCloseKind
  retry: boolean
}

/** 服务端信封不满足已确认协议时抛出，调用方必须降级而不能把字段补成默认值。 */
export class HvacRealtimeProtocolError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'HvacRealtimeProtocolError'
  }
}

type UrlResolutionInput = {
  wsBase?: string
  apiBase?: string
  browserOrigin?: string
}

function nonBlank(value: string | undefined): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

function record(value: unknown, description: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new HvacRealtimeProtocolError(`${description}格式非法`)
  }
  return value as Record<string, unknown>
}

function text(value: unknown, field: string): string {
  if (typeof value !== 'string' || !value.trim()) {
    throw new HvacRealtimeProtocolError(`${field}缺失或格式非法`)
  }
  return value
}

function finiteNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new HvacRealtimeProtocolError(`${field}必须是有限数值`)
  }
  return value
}

function nullableText(value: unknown, field: string): string | null {
  if (value === null) return null
  return text(value, field)
}

function nullableNumber(value: unknown, field: string): number | null {
  if (value === null) return null
  return finiteNumber(value, field)
}

function textArray(value: unknown, field: string): string[] {
  if (!Array.isArray(value) || value.some((item) => typeof item !== 'string')) {
    throw new HvacRealtimeProtocolError(`${field}必须是字符串数组`)
  }
  return [...value]
}

function indicatorCode(value: unknown): HvacRealtimeIndicatorCode {
  const code = text(value, 'indicatorCode')
  if (!(HVAC_REALTIME_INDICATOR_CODES as readonly string[]).includes(code)) {
    throw new HvacRealtimeProtocolError('indicatorCode不在实时指标范围内')
  }
  return code as HvacRealtimeIndicatorCode
}

function parseIndicator(value: unknown): HvacRealtimeIndicator {
  const data = record(value, 'HVAC_INDICATOR.data')
  return {
    indicatorId: text(data.indicatorId, 'indicatorId'),
    indicatorCode: indicatorCode(data.indicatorCode),
    buildingId: text(data.buildingId, 'buildingId'),
    equipId: nullableText(data.equipId, 'equipId'),
    minuteStart: finiteNumber(data.minuteStart, 'minuteStart'),
    status: text(data.status, 'status'),
    value: nullableNumber(data.value, 'value'),
    dataQuality: nullableNumber(data.dataQuality, 'dataQuality'),
    formulaVersion: nullableText(data.formulaVersion, 'formulaVersion'),
    reasonCode: nullableText(data.reasonCode, 'reasonCode'),
    missingInputs: textArray(data.missingInputs, 'missingInputs'),
  }
}

/**
 * 解析服务端消息而不把未知字段或缺失业务值转换为默认数据。
 *
 * 这里是浏览器与 `/ws/hvac` 的契约边界；非法帧代表版本或传输异常，应由客户端关闭并
 * 让页面回到 HTTP 权威保障，不能继续向指标卡写入部分对象。
 */
export function parseHvacRealtimeServerMessage(
  payload: string,
): HvacRealtimeServerMessage {
  let decoded: unknown
  try {
    decoded = JSON.parse(payload)
  } catch {
    throw new HvacRealtimeProtocolError('服务端消息不是有效 JSON')
  }

  const message = record(decoded, '服务端消息')
  const type = text(message.type, 'type')
  switch (type) {
    case 'SUBSCRIBED':
      return {
        type,
        buildingId: text(message.buildingId, 'buildingId'),
        serverTime: finiteNumber(message.serverTime, 'serverTime'),
      }
    case 'PONG':
      return { type, serverTime: finiteNumber(message.serverTime, 'serverTime') }
    case 'HVAC_INDICATOR':
      return { type, data: parseIndicator(message.data) }
    case 'ERROR':
      return {
        type,
        code: text(message.code, 'code'),
        message: text(message.message, 'message'),
      }
    default:
      throw new HvacRealtimeProtocolError('未知实时消息类型')
  }
}

/**
 * 将 HTTP 基址或显式 WS 基址转换为唯一的 HVAC 端点。
 *
 * 原生 WebSocket 无法安全复用 Axios 请求头，因此本函数只生成无凭据 URL；JWT 会在升级
 * 成功后的 `SUBSCRIBE` 首帧发送，避免 Token、建筑 ID 进入代理与浏览器地址记录。
 */
export function resolveHvacRealtimeUrl(
  input: UrlResolutionInput = {},
): string {
  const browserOrigin = input.browserOrigin ?? window.location.origin
  const explicitWsBase = nonBlank(input.wsBase ?? import.meta.env.VITE_WS_BASE)
  const apiBase = nonBlank(input.apiBase ?? import.meta.env.VITE_API_BASE)
  const rawBase = explicitWsBase ?? apiBase ?? `${browserOrigin}/api`
  const url = new URL(rawBase, browserOrigin)

  if (url.protocol === 'http:') url.protocol = 'ws:'
  else if (url.protocol === 'https:') url.protocol = 'wss:'
  else if (url.protocol !== 'ws:' && url.protocol !== 'wss:') {
    throw new HvacRealtimeProtocolError('实时地址必须使用 HTTP 或 WebSocket 协议')
  }

  const basePath = url.pathname.replace(/\/+$/, '')
  const contextPath = basePath && basePath !== '/' ? basePath : '/api'
  const withoutEndpoint = contextPath.replace(/(?:\/ws\/hvac)+$/i, '') || '/api'
  url.pathname = `${withoutEndpoint}/ws/hvac`
  url.search = ''
  url.hash = ''
  return url.toString()
}

/** 将浏览器 CloseEvent 转为页面重连策略；应用关闭码不能按普通网络抖动处理。 */
export function classifyHvacRealtimeClose(
  code: number,
): HvacRealtimeDisconnect {
  switch (code) {
    case 4400:
      return { code, kind: 'protocol', retry: false }
    case 4401:
      return { code, kind: 'unauthorized', retry: false }
    case 4403:
      return { code, kind: 'forbidden', retry: false }
    case 4408:
      return { code, kind: 'timeout', retry: true }
    case 1011:
      return { code, kind: 'server_error', retry: true }
    case 1000:
      return { code, kind: 'normal', retry: false }
    default:
      return { code, kind: 'network', retry: true }
  }
}
