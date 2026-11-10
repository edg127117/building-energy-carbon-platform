import { effectScope, ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { screenWeatherQueries, type WeatherSeries } from '@/modules/weather/public'
import type { ScreenPeriod } from '../models/screen-period'
import type { HourlyMonitoringView } from '../api/hourly-monitoring'
import { readHourlyMonitoring } from '../api/hourly-monitoring'
import { useFinalBHourly } from './use-final-b-hourly'

vi.mock('../api/hourly-monitoring', () => ({ readHourlyMonitoring: vi.fn() }))
vi.mock('@/modules/weather/public', async original => ({
  ...await original<typeof import('@/modules/weather/public')>(),
  screenWeatherQueries: { hourly: vi.fn(async () => []) },
}))
afterEach(() => { vi.useRealTimers() })
beforeEach(() => {
  vi.mocked(readHourlyMonitoring).mockReset()
  vi.mocked(screenWeatherQueries.hourly).mockReset().mockResolvedValue([])
})
async function settleMicrotasks() { await Promise.resolve(); await Promise.resolve(); await Promise.resolve() }

function weather(product: string, temperature: number): WeatherSeries[] {
  return [{
    location: { id: 'L1', buildingId: 'B', version: 1, name: 'Location', latitude: 1, longitude: 1, cityCode: null, coverage: 'HOURLY', effectiveFrom: '2026-01-01T00:00:00+08:00', effectiveTo: null, enabled: true },
    source: 'OPEN_METEO', product, status: 'READY', updateStatus: 'READY', fetchedAt: null, datasetId: product,
    provenance: { dataKind: product, sourcePublishedAt: null, gridLatitude: 1, gridLongitude: 1, parserVersion: 'v1', timezone: 'Asia/Shanghai', units: { temperature_2m: '°C' } },
    samples: [{ time: '2026-09-22T10:00:00+08:00', values: { temperature_2m: temperature }, text: {} }],
  }]
}

function view(): HourlyMonitoringView {
  const date = '2026-09-22', start = Date.parse(`${date}T00:00:00+08:00`)
  return {
    buildingId: 'B', date, timezoneId: 'Asia/Shanghai', asOf: new Date(start + 12 * 3_600_000).toISOString(),
    points: Array.from({ length: 24 }, (_, hour) => ({
      hour, startInclusive: new Date(start + hour * 3_600_000).toISOString(), endExclusive: new Date(start + (hour + 1) * 3_600_000).toISOString(),
      actualStart: null, actualEnd: null, electricityKwh: hour === 0 ? '0' : null, staticEmissionTco2e: null,
      staticFactorKgCo2ePerKwh: null, dynamicEmissionTco2: null, dynamicFactorKgCo2PerKwh: null,
      electricityStatus: hour === 0 ? 'COMPLETE' : 'MISSING', staticStatus: 'MISSING', dynamicStatus: 'MISSING',
      approximate: false, staticGasCoverage: null, sourceNatures: [], evidenceId: null, reasonCode: null,
    })),
  }
}

it('deduplicates same-building same-day requests and independently reads both weather products', async () => {
  vi.mocked(readHourlyMonitoring).mockResolvedValue(view())
  const scope = effectScope(), building = ref('B')
  const day: ScreenPeriod = { kind: 'day', value: '2026-09-22' }
  const state = scope.run(() => useFinalBHourly(building, ref(day), ref(day)))!
  await flushPromises()
  expect(readHourlyMonitoring).toHaveBeenCalledTimes(1)
  expect(readHourlyMonitoring).toHaveBeenCalledWith('B', day.value, expect.any(AbortSignal))
  expect(vi.mocked(screenWeatherQueries.hourly).mock.calls.map(call => call[1])).toEqual(['HISTORY_HOURLY', 'FORECAST_HOURLY'])
  expect(state.data.value.usage?.data.electricity[0]).toBe(0)
  scope.stop()
})

it('keeps a successful same-day snapshot after a refresh failure', async () => {
  vi.mocked(readHourlyMonitoring).mockResolvedValueOnce(view()).mockRejectedValueOnce(new Error('offline'))
  const scope = effectScope(), building = ref('B'), day: ScreenPeriod = { kind: 'day', value: '2026-09-22' }
  const state = scope.run(() => useFinalBHourly(building, ref(day), ref({ kind: 'month', value: '2026-09' })))!
  await flushPromises()
  expect(state.data.value.usage?.data.electricity[0]).toBe(0)
  state.reload()
  await flushPromises()
  expect(state.failed.value.usage).toBe(true)
  expect(state.data.value.usage?.data.electricity[0]).toBe(0)
  scope.stop()
})

it('does not let slow weather requests delay the two-second carbon refresh', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'Date'] })
  vi.mocked(readHourlyMonitoring).mockResolvedValue(view())
  vi.mocked(screenWeatherQueries.hourly).mockImplementation(() => new Promise<WeatherSeries[]>(() => {}))
  const scope = effectScope(), building = ref('B'), day: ScreenPeriod = { kind: 'day', value: '2026-09-22' }
  scope.run(() => useFinalBHourly(building, ref(day), ref(day)))
  await settleMicrotasks()
  const firstCount = vi.mocked(readHourlyMonitoring).mock.calls.length
  expect(firstCount).toBeGreaterThan(0)
  await vi.advanceTimersByTimeAsync(2_000)
  await settleMicrotasks()
  expect(vi.mocked(readHourlyMonitoring).mock.calls.length).toBeGreaterThan(firstCount)
  expect(screenWeatherQueries.hourly).toHaveBeenCalledTimes(2)
  scope.stop()
})

it('rechecks an empty weather response and displays data that becomes available later', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'Date'] })
  vi.setSystemTime(new Date('2026-09-22T08:00:00+08:00'))
  vi.mocked(readHourlyMonitoring).mockResolvedValue(view())
  let calls = 0
  vi.mocked(screenWeatherQueries.hourly).mockImplementation(async (_building, product) => {
    calls += 1
    if (calls <= 2) return []
    return weather(product, 18)
  })
  const scope = effectScope(), building = ref('B'), day: ScreenPeriod = { kind: 'day', value: '2026-09-22' }
  const state = scope.run(() => useFinalBHourly(building, ref(day), ref(day)))!
  await settleMicrotasks()
  expect(state.data.value.usage?.data.temperatureHistory?.[10]).toBeNull()
  await vi.advanceTimersByTimeAsync(60_000)
  await settleMicrotasks()
  expect(state.data.value.usage?.data.temperatureHistory?.[10]).toBe(18)
  scope.stop()
})
