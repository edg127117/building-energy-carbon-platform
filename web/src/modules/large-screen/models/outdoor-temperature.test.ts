import { expect, it } from 'vitest'
import { withOutdoorTemperature } from './outdoor-temperature'
import type { ScreenRedesignData } from './screen-redesign'

it('只合并同期间温度，保留后端电量和其他卡片且不修改输入', () => {
  const period = { kind: 'year', value: '2025' } as const
  const raw: ScreenRedesignData = { annual: { year: '2025', carbon: 50, intensity: 2 }, usage: { period, data: {
    electricity: Array(12).fill(15), temperature: Array(12).fill(null), electricityUnit: 'kWh', temperatureUnit: '℃',
  } } }
  const before = JSON.stringify(raw), temperatures = [-5, 0, ...Array(10).fill(null)]
  const merged = withOutdoorTemperature(raw, period, temperatures)
  expect(merged.usage?.data.electricity).toEqual(Array(12).fill(15))
  expect(merged.usage?.data.temperature.slice(0, 3)).toEqual([-5, 0, null])
  expect(merged.annual).toBe(raw.annual)
  expect(JSON.stringify(raw)).toBe(before)
})
it('月图只创建日刻度空位，不均分月电量，也不泄露其他期间温度', () => {
  const old = { kind: 'year', value: '2025' } as const
  const raw: ScreenRedesignData = { usage: { period: old, data: { electricity: Array(12).fill(999), temperature: Array(12).fill(20), electricityUnit: 'kWh', temperatureUnit: '℃' } } }
  const result = withOutdoorTemperature(raw, { kind: 'month', value: '2024-02' })
  expect(result.usage?.data.electricity).toEqual(Array(29).fill(null))
  expect(result.usage?.data.temperature).toEqual(Array(29).fill(null))
})
it('温度暂不支持的历史年份仍保留既有电量，不影响其余模块', () => {
  const period = { kind: 'year', value: '2023' } as const
  const raw: ScreenRedesignData = { usage: { period, data: { electricity: Array(12).fill(3), temperature: [], electricityUnit: 'kWh', temperatureUnit: '℃' } } }
  expect(withOutdoorTemperature(raw, period).usage?.data.electricity).toEqual(Array(12).fill(3))
})
