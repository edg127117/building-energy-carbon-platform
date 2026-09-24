import { describe, expect, it } from 'vitest'
import { daikinLabel, daikinFieldLabel, daikinCurrentValue, daikinCurrentFields, temperatureSeries, runtimePeriodTime, temperatureWindow } from './daikin-display'
import type { CurrentField } from './daikin'

describe('manufacturer display boundaries', () => {
  it('keeps zero and blocked values, breaks gaps without interpolation', () => {
    expect(temperatureSeries([
      { observedAt: 1, value: 0, dataQuality: 0, gapBefore: false, stale: false },
      { observedAt: 600001, value: 22, dataQuality: 0, gapBefore: true, stale: false },
      { observedAt: 660001, value: null, dataQuality: 2, gapBefore: false, stale: false },
    ])).toEqual([[1, 0], [600000, null], [600001, 22], [660001, null]])
  })
  it('does not invent semantics for new manufacturer values', () => {
    expect(daikinLabel('VENDOR_EQUIPMENT')).toBe('设备故障')
    expect(daikinLabel('CONTROLLER_COMMUNICATION')).toBe('控制器通信故障')
    expect(daikinLabel('on')).toBe('开')
    expect(daikinLabel('vendor-future-mode')).toBe('未确认值（原始值：vendor-future-mode）')
    expect(daikinLabel('constructor')).toBe('未确认值（原始值：constructor）')
    expect(daikinLabel('UNCONFIRMED')).toBe('原始值待核验')
    expect(daikinLabel('airFlowSeven')).toBe('风向 7')
    expect(daikinLabel('operating')).toBe('正常')
    expect(daikinFieldLabel('mc11')).toBe('未确认字段（原始字段名：mc11）')
    expect(daikinFieldLabel('onOff')).toBe('启停')
  })
  it('keeps verified fields visible and puts unknown codes in expandable details', () => {
    const field = (fieldName: string, normalizedValue: string): CurrentField => ({
      fieldName, normalizedValue, rawJson: null, status: 'PRESENT', lastValidAt: 1,
      lastAttemptAt: 1, lastAttemptRawJson: null, valueVisible: true, stale: false,
      mappingVersion: 1, lastAttemptMappingVersion: 1,
    })
    const rows = [field('onOff', 'on'), field('airflowDirection', 'airFlowSeven'), field('arth1', '22'), field('modelName', 'FSFP80AB')]
    expect(daikinCurrentFields(rows)).toEqual({ primary: [rows[0], rows[1], rows[3]], extended: [rows[2]] })
    expect(daikinCurrentValue('modelName', 'FSFP80AB')).toBe('FSFP80AB')
    expect(daikinCurrentValue('errorCode', '')).toBe('—')
    expect(daikinCurrentValue('roomTemp', '25.2')).toBe('25.2')
    expect(daikinCurrentValue('errorType', '0')).toBe('正常')
    expect(daikinCurrentValue('rcProhibitOnOff', 'stopOnly')).toBe('仅允许停止')
    expect(daikinCurrentValue('limitSettempCool', 'off')).toBe('无效')
    expect(daikinCurrentValue('coolLimitsettempU', '32')).toBe('32')
  })
  it('translates every runtime synchronization state for customer display', () => {
    expect(['QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'UNSUPPORTED', 'EXPIRED'].map(daikinLabel))
      .toEqual(['待执行', '执行中', '等待重试', '已完成', '失败', '不支持', '已过期'])
  })
  it('uses manufacturer period timezone independently of browser timezone', () => {
    expect(runtimePeriodTime(Date.parse('2026-09-16T16:00:00Z'), 'Asia/Shanghai')).toContain('2026/09/17')
    expect(runtimePeriodTime(0, 'unsupported-zone')).toBe('1970-01-01T00:00:00.000Z')
  })
  it('clips a rolling retention boundary but rejects future and oversized windows', () => {
    const now = 100 * 86400000
    expect(temperatureWindow([10 * 86400000 - 1, now - 1], now)).toEqual([10 * 86400000, now - 1])
    expect(temperatureWindow([0, now], now)).toBeNull()
    expect(temperatureWindow([now - 1000, now + 1], now)).toBeNull()
    expect(temperatureWindow(null, now)).toBeNull()
  })
})
