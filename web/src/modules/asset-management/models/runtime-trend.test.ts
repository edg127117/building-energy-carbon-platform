import { describe, expect, it } from 'vitest'
import { formatLedgerPointValue, ledgerPointDisplay } from './point-display'
import { peopleTrendAxis, presentRuntimeTrend, runtimeTrendAxis } from './runtime-trend'

describe('台账测点显示', () => {
  it('电表功率跨过 1 kW 后仍沿用瓦，其他量纲保留档案单位', () => {
    const data: [number, number | null][] = [[0, 0.022], [60_000, 1.001]]
    const trend = presentRuntimeTrend({
      pointCode: 'NEW_POWER', pointName: '新接入功率', unit: 'kW', data,
    }, 'ELECTRIC_METER')
    expect(trend.unit).toBe('W')
    expect(trend.data[0]?.[1]).toBe(22)
    expect(formatLedgerPointValue(trend.data[1]![1]!, trend.unit)).toBe('1,001 W')
    expect(data).toEqual([[0, 0.022], [60_000, 1.001]])
    expect(ledgerPointDisplay('Pa', 'OTHER')).toEqual({ unit: 'Pa', multiplier: 1 })
    expect(formatLedgerPointValue(0.0000123, 'Pa')).toBe('0.0000123 Pa')
  })

  it('新量纲、负值、平线、全零和异常极值都按数据范围留白', () => {
    for (const [low, high, unit] of [[-10, -8, 'Pa'], [230, 230, 'V'], [0, 0, 'A'], [49.97, 51, 'Hz']] as const) {
      const axis = runtimeTrendAxis([[0, low], [60_000, null], [120_000, high]], unit)!
      expect(axis.min).toBeLessThan(low)
      expect(axis.max).toBeGreaterThan(high)
      expect(axis.interval).toBeGreaterThan(0)
    }
    expect(runtimeTrendAxis([[0, null]], 'A')).toBeNull()
  })

  it('电表频率和模板室温分别使用最小跨度，较大波动时向外扩展', () => {
    const meter = { category: 'ELECTRIC_METER', pointCode: 'MTR02_F' }
    const frequency = runtimeTrendAxis([[0, 49.95], [60_000, 50.04]], 'Hz', meter)!
    expect(frequency.max - frequency.min).toBeGreaterThanOrEqual(0.5)
    const wideFrequency = runtimeTrendAxis([[0, 48], [60_000, 51]], 'Hz', meter)!
    expect(wideFrequency.min).toBeLessThan(48)
    expect(wideFrequency.max).toBeGreaterThan(51)

    const room = { pointCode: 'IDU20_roomTemp', category: 'INDOOR_UNIT' }
    const temperature = runtimeTrendAxis([[0, 19.2], [60_000, 19.3]], '°C', room)!
    expect(temperature.max - temperature.min).toBeGreaterThanOrEqual(2)
    const wideTemperature = runtimeTrendAxis([[0, 19.2], [60_000, 25.1]], '°C', room)!
    expect(wideTemperature.min).toBeLessThan(19.2)
    expect(wideTemperature.max).toBeGreaterThan(25.1)
  })

  it('普通频率、设定温度和水温沿用各自的刻度规则', () => {
    expect(runtimeTrendAxis([[0, 49.96], [60_000, 50.04]], 'Hz', { category: 'PUMP' }))
      .toMatchObject({ min: 49.9, max: 50.1, interval: 0.05 })
    for (const pointCode of ['IDU20_setTemp', 'CH_101_WATER_TEMP']) {
      const axis = runtimeTrendAxis([[0, 19.2], [60_000, 19.3]], '°C', { pointCode })!
      expect(axis.max - axis.min).toBeLessThan(2)
    }
  })

  it('低人数共用 0 至 5 的整数刻度，零值和缺数分别计算', () => {
    for (const peak of [0, 1, 2, 4]) {
      expect(peopleTrendAxis([[0, 0], [60_000, null], [120_000, peak]])).toEqual({ min: 0, max: 5, interval: 1 })
    }
    expect(peopleTrendAxis([[0, null]])).toBeNull()
  })

  it('更多人数自动扩展到整数刻度，峰值上方有余量', () => {
    expect(peopleTrendAxis([[0, 5]])).toEqual({ min: 0, max: 6, interval: 2 })
    expect(peopleTrendAxis([[0, 18]])).toEqual({ min: 0, max: 25, interval: 5 })
    expect(peopleTrendAxis([[0, 90]])).toEqual({ min: 0, max: 125, interval: 25 })
  })
})
