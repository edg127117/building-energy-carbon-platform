import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import { useResource } from './composables/useResource'
import { useCommand } from './composables/useCommand'
import { chinaDay, addDays, historyRangeValid, midnight, valueText } from './models/presentation'
import { hourlyOption, comparisonOption } from './models/charts'
import type { ComparisonDay, WeatherSeries } from './models/weather'
import { TransportError } from '@/infrastructure/http/public'

const wrappers: { unmount: () => void }[] = []
function harness<T>(setup: () => T): T {
  let result!: T
  wrappers.push(mount(defineComponent({ setup() { result = setup(); return () => null } })))
  return result
}
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.useRealTimers(); vi.unstubAllGlobals() })
describe('weather date and missing-value contracts', () => {
  it('uses Shanghai day boundaries even when UTC is still yesterday', () => {
    expect(chinaDay(new Date('2026-09-28T16:00:00Z'))).toBe('2026-09-29')
    expect(midnight('2026-09-29')).toBe('2026-09-28T16:00:00.000Z')
    expect(addDays('2024-02-28', 1)).toBe('2024-02-29')
  })
  it('accepts at most 366 ended days and preserves zero separately from missing', () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date('2026-09-29T08:00:00Z'))
    expect(historyRangeValid(['2024-01-01', '2024-12-31'])).toBe(true)
    expect(historyRangeValid(['2024-01-01', '2025-01-01'])).toBe(false)
    expect(historyRangeValid(['2026-09-29', '2026-09-29'])).toBe(false)
    expect(historyRangeValid(null)).toBe(false)
    expect(valueText(0, ' kWh')).toBe('0 kWh'); expect(valueText(null)).toBe('—')
  })
  it('creates null gaps for omitted hours without changing measured values', () => {
    const series: WeatherSeries[] = [{ location: null, source: 'OPEN_METEO', product: 'HISTORY_HOURLY', status: 'PARTIAL', updateStatus: 'FRESH', fetchedAt: null, datasetId: null, provenance: null, samples: [{ time: '2026-09-28T16:00:00Z', values: { temperature_2m: 0 }, text: {} }, { time: '2026-09-28T18:00:00Z', values: { temperature_2m: 12 }, text: {} }] }]
    const option = hourlyOption(series, midnight('2026-09-29'), '2026-09-28T19:00:00Z', 'temperature_2m')
    expect(option.series).toMatchObject([{ connectNulls: false, data: [0, null, 12] }])
  })
  it('keeps electricity and weather gaps independent with approximate bars distinct', () => {
    const days = [{ date: '2026-09-01', energyKwh: 0, weatherValue: null, energy: { approximate: false } }, { date: '2026-09-02', energyKwh: 4, weatherValue: 23, energy: { approximate: true } }] as ComparisonDay[]
    expect(comparisonOption(days, 'temperature').series).toMatchObject([{ data: [0, null] }, { data: [null, 4], itemStyle: { decal: {} } }, { connectNulls: false, data: [null, 23] }])
  })
})
describe('weather query isolation', () => {
  it('clears old buildings immediately and ignores late responses even if cancellation is ignored', async () => {
    const resource = harness(() => useResource<string>())
    let resolveOld!: (v: string) => void
    await resource.load('first', async () => 'first-value')
    const old = resource.load('second', () => new Promise(resolve => { resolveOld = resolve }))
    expect(resource.data.value).toBeNull()
    await resource.load('third', async () => 'third-value')
    resolveOld('second-value'); await old
    expect(resource.data.value).toBe('third-value')
  })
  it('retains same-query data on network failure but clears it on revoked access', async () => {
    const resource = harness(() => useResource<number>())
    await resource.load('a', async () => 0)
    await resource.load('a', async () => { throw new Error('offline') })
    expect(resource.data.value).toBe(0)
    await resource.load('a', async () => { throw new TransportError('forbidden', 403) })
    expect(resource.data.value).toBeNull(); expect(resource.error.value).toContain('无权')
  })
})
describe('weather command idempotency', () => {
  it('reuses the key after an uncertain result but allocates a new key for a new successful action', async () => {
    const command = useCommand()
    const keys: string[] = []
    await command.run('fetch:a', { start: '2026-09-01' }, async key => { keys.push(key); throw new Error('timeout') })
    await command.run('fetch:a', { start: '2026-09-01' }, async key => { keys.push(key); return [] })
    await command.run('fetch:a', { start: '2026-09-01' }, async key => { keys.push(key); return [] })
    expect(keys[0]).toBe(keys[1]); expect(keys[2]).not.toBe(keys[1])
  })
  it('prevents double submission while the first request is pending', async () => {
    const command = useCommand(); let finish!: () => void
    const send = vi.fn(() => new Promise<void>(resolve => { finish = resolve }))
    const first = command.run('a', {}, send)
    await command.run('a', {}, send)
    expect(send).toHaveBeenCalledTimes(1)
    finish(); await first
  })
})

describe('weather command HTTP compatibility', () => {
  it('keeps the same key for an uncertain retry and consumes it only after success without randomUUID', async () => {
    let sequence = 0
    vi.stubGlobal('crypto', { getRandomValues: (bytes: Uint8Array) => { bytes.fill(++sequence); return bytes } })
    const command = useCommand(), keys: string[] = []
    await command.run('http-fixture', { value: 1 }, async key => { keys.push(key); throw new Error('timeout') })
    await command.run('http-fixture', { value: 1 }, async key => { keys.push(key); return true })
    await command.run('http-fixture', { value: 1 }, async key => { keys.push(key); return true })
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/)
    expect(keys[1]).toBe(keys[0])
    expect(keys[2]).not.toBe(keys[1])
  })
})
