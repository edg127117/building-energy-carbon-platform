import { describe, expect, it } from 'vitest'
import { statusMessage, forecastMessage } from './status'
import type { Forecast } from './weather'
import { chinaDay } from './presentation'
describe('天气状态说明', () => {
  it('有旧数据时明确指出更新失败，时间包含日期以免误认今天', () => {
    const message = statusMessage({ status: 'READY', updateStatus: 'FAILED', fetchedAt: '2026-09-29T09:01:00Z' })
    expect(message).toContain('最近更新失败')
    expect(message).toContain('2026/9/29 17:01:00')
    expect(message).not.toContain('可用')
  })
  it('没有保存数据时不声称正在展示旧数据', () => {
    expect(statusMessage({ status: 'MISSING', updateStatus: 'FAILED', fetchedAt: null })).toContain('暂无已保存数据')
  })
  it('定位今日最高温缺失并保留更新成功状态', () => {
    const forecast: Forecast = { status: 'PARTIAL', updateStatus: 'FRESH', fetchedAt: null,
      preferredSource: 'CHINA_WEATHER', selectedSource: 'CHINA_WEATHER', fallbackReason: null,
      datasetId: null, coverage: null, provenance: null,
      days: [{ date: chinaDay(), sample: { time: '2026-09-29T00:00:00Z', values: { temperature_2m_max: null, temperature_2m_min: 0 }, text: { weather: '小雨' } } }] }
    expect(forecastMessage(forecast)).toBe('今日最高温缺失 · 更新成功')
  })
})
