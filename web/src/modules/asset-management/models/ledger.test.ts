import { describe, expect, it } from 'vitest'
import {
  isLedgerCommunicationDelayed,
  ledgerCommunicationDisplayValue,
  ledgerCommunicationReasons,
  ledgerDisplayPointValue,
  type LedgerCommunicationState,
  type LedgerDisplayPoint,
} from './ledger'

describe('台账默认读数门禁', () => {
  const point: LedgerDisplayPoint = { status: 'AVAILABLE', usageStatus: 'AVAILABLE', value: 0,
    reason: null, pointId: 'P1', pointCode: 'P', pointName: '有功功率', unit: 'kW', eventTime: 1,
    receivedTime: 2, dataQuality: 0 }

  it('保留有效零值，阻断或异常载荷不泄露数值', () => {
    expect(ledgerDisplayPointValue(point)).toBe(0)
    expect(ledgerDisplayPointValue({ ...point, usageStatus: 'QUALITY_BLOCKED', value: 99 })).toBeNull()
    expect(ledgerDisplayPointValue({ ...point, status: 'UNAVAILABLE', value: 99 })).toBeNull()
    expect(ledgerDisplayPointValue({ ...point, value: Infinity })).toBeNull()
  })
})

describe('台账通讯展示契约', () => {
  const state = (overrides: Partial<LedgerCommunicationState> = {}): LedgerCommunicationState => ({
    applicable: true,
    value: 'ONLINE',
    reason: 'LIVE_TELEMETRY',
    source: 'TELEMETRY',
    observedAt: 1_735_689_600_000,
    scope: 'DEVICE',
    ...overrides,
  })

  it('只使用后端状态字段区分未监测和上报延迟，不按观测时间推断在线离线', () => {
    expect(ledgerCommunicationDisplayValue(state({ value: 'ONLINE', reason: 'REPORT_DELAYED', observedAt: null }))).toBe('ONLINE')
    expect(isLedgerCommunicationDelayed(state({ value: 'ONLINE', reason: 'REPORT_DELAYED', observedAt: null }))).toBe(true)
    expect(ledgerCommunicationDisplayValue(state({ applicable: false, value: 'UNKNOWN', reason: 'NOT_MONITORED' }))).toBe('NOT_MONITORED')
    expect(isLedgerCommunicationDelayed(state({ value: 'OFFLINE', reason: 'REPORT_DELAYED' }))).toBe(false)
  })

  it('保留服务端通讯原因集合，前端仅将其映射为展示语义', () => {
    expect(ledgerCommunicationReasons).toEqual([
      'LIVE_TELEMETRY', 'LIVE_POLL', 'REPORT_DELAYED', 'DEVICE_TIMEOUT', 'DEVICE_REPORTED_OFFLINE',
      'UPSTREAM_GATEWAY_OFFLINE', 'PLATFORM_PIPELINE_UNAVAILABLE', 'WAITING_INITIAL_CONTACT',
      'NOT_MONITORED', 'IDENTITY_CONFLICT', 'STALE_SCOPE',
    ])
  })
})
