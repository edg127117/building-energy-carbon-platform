import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref, type Ref } from 'vue'
import type { WeatherSeries } from '@/modules/weather/public'
import { useScreenWeather } from './use-screen-weather'

const { hourly } = vi.hoisted(() => ({ hourly: vi.fn() }))

vi.mock('@/modules/weather/public', () => ({ screenWeatherQueries: { hourly }, messages: {} }))

const day = '2026-10-27'
const from = '2026-10-26T16:00:00.000Z'
const to = '2026-10-27T16:00:00.000Z'

function location(buildingId = 'B-1', overrides: Record<string, unknown> = {}) {
  return {
    id: 'LOCATION-1', buildingId, version: 1, name: '测试位置', latitude: 39.9, longitude: 116.4,
    cityCode: '110000000', coverage: 'CITY', effectiveFrom: '2024-01-01T00:00:00.000Z',
    effectiveTo: null, enabled: true, ...overrides,
  }
}

function weatherSeries(overrides: Partial<WeatherSeries> = {}): WeatherSeries {
  return {
    location: location(), source: 'OPEN_METEO', product: 'HISTORY_HOURLY', status: 'READY', updateStatus: 'FRESH',
    fetchedAt: '2026-10-28T00:00:00.000Z', datasetId: 'DATASET-1',
    provenance: {
      dataKind: 'MODEL_HISTORY_BEST_MATCH', sourcePublishedAt: null, gridLatitude: 39.9, gridLongitude: 116.4,
      parserVersion: 'test', timezone: 'Asia/Shanghai', units: { temperature_2m: '°C' },
    },
    samples: [], ...overrides,
  }
}

function sample(hour: number, temperature: number | null, date = day) {
  return { time: `${date}T${String(hour).padStart(2, '0')}:00:00+08:00`, values: { temperature_2m: temperature }, text: {} }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(next => { resolve = next })
  return { promise, resolve }
}

let buildingId: Ref<string>
let selectedDay: Ref<string>
let screenWeather!: ReturnType<typeof useScreenWeather>

function mountWeather() {
  return mount(defineComponent({
    setup() {
      screenWeather = useScreenWeather(buildingId, selectedDay)
      return () => h('div')
    },
  }))
}

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
}

describe('useScreenWeather', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    buildingId = ref('B-1')
    selectedDay = ref(day)
  })

  afterEach(() => vi.restoreAllMocks())

  it('按北京时间自然日读取历史小时温度，保留零值并补齐 24 个整点槽', async () => {
    hourly.mockResolvedValue([
      weatherSeries({ samples: [sample(0, 0), sample(1, 10), sample(2, 12), sample(2, 13), sample(3, Number.NaN)] }),
      weatherSeries({
        product: 'FORECAST_HOURLY', datasetId: 'FORECAST-1',
        provenance: { ...weatherSeries().provenance!, dataKind: 'MODEL_FORECAST' }, samples: [sample(4, 99)],
      }),
    ])
    const wrapper = mountWeather()
    await settle()

    expect(hourly).toHaveBeenCalledWith('B-1', 'HISTORY_HOURLY', from, to, expect.anything())
    expect(screenWeather.periods.value).toEqual(['00:00', '01:00', '02:00', '03:00', '04:00', '05:00', '06:00', '07:00', '08:00', '09:00', '10:00', '11:00', '12:00', '13:00', '14:00', '15:00', '16:00', '17:00', '18:00', '19:00', '20:00', '21:00', '22:00', '23:00'])
    expect(screenWeather.series.value).toEqual([{
      name: '室外温度', axis: 1,
      values: [0, 10, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null],
    }])
    expect(screenWeather.datasetIds.value).toEqual(['DATASET-1'])
    expect(screenWeather.status.value).toBe('partial')
    wrapper.unmount()
  })

  it('无建筑或无效日期不请求；未配置位置为 unavailable', async () => {
    buildingId.value = ''
    selectedDay.value = '2026-02-30'
    const invalid = mountWeather()
    await settle()

    expect(hourly).not.toHaveBeenCalled()
    expect(screenWeather.status.value).toBe('unavailable')
    invalid.unmount()

    buildingId.value = 'B-1'
    selectedDay.value = day
    hourly.mockResolvedValue([weatherSeries({ status: 'NOT_CONFIGURED', location: null })])
    const unconfigured = mountWeather()
    await settle()
    expect(screenWeather.status.value).toBe('unavailable')
    expect(screenWeather.series.value).toEqual([])
    unconfigured.unmount()

  })

  it('其他字段缺失不抹掉完整温度，且以 partial 明确来源不完整', async () => {
    const samples = Array.from({ length: 24 }, (_, hour) => ({
      ...sample(hour, 8), values: { temperature_2m: 8, relative_humidity_2m: null },
    }))
    hourly.mockResolvedValue([weatherSeries({ status: 'PARTIAL', samples })])
    const partial = mountWeather()
    await settle()

    expect(screenWeather.status.value).toBe('partial')
    expect(screenWeather.series.value[0]).toMatchObject({ name: '室外温度', axis: 1, values: Array.from({ length: 24 }, () => 8) })
    expect(screenWeather.datasetIds.value).toEqual(['DATASET-1'])
    partial.unmount()
  })

  it('只缺某个小时温度时保留该小时 null，不插值或平均', async () => {
    hourly.mockResolvedValue([weatherSeries({
      status: 'PARTIAL', samples: Array.from({ length: 24 }, (_, hour) => sample(hour, hour === 5 ? null : 8)),
    })])
    const partial = mountWeather()
    await settle()

    expect(screenWeather.status.value).toBe('partial')
    expect(screenWeather.series.value[0]?.values).toEqual([8, 8, 8, 8, 8, null, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8])
    partial.unmount()
  })

  it('切换建筑立即清空旧数据并撤销旧请求，迟到响应不能覆盖新世代', async () => {
    const first = deferred<WeatherSeries[]>()
    const second = deferred<WeatherSeries[]>()
    hourly.mockImplementation((building: string) => building === 'B-1' ? first.promise : second.promise)
    const wrapper = mountWeather()
    const firstSignal = hourly.mock.calls[0][4] as AbortSignal

    buildingId.value = 'B-2'
    expect(firstSignal.aborted).toBe(true)
    expect(screenWeather.series.value).toEqual([])
    expect(screenWeather.status.value).toBe('loading')

    second.resolve([weatherSeries({ location: location('B-2'), datasetId: 'B-2-DATA', samples: [sample(0, 8)] })])
    await settle()
    first.resolve([weatherSeries({ samples: [sample(0, 99)] })])
    await settle()

    expect(screenWeather.series.value[0]?.values[0]).toBe(8)
    expect(screenWeather.datasetIds.value).toEqual(['B-2-DATA'])
    wrapper.unmount()
  })

  it('请求失败清空证据并标记 error，卸载会撤销未完成请求', async () => {
    hourly.mockRejectedValue(new Error('weather unavailable'))
    const failed = mountWeather()
    await settle()
    expect(screenWeather.status.value).toBe('error')
    expect(screenWeather.series.value).toEqual([])
    failed.unmount()

    const pending = deferred<WeatherSeries[]>()
    hourly.mockImplementation(() => pending.promise)
    const active = mountWeather()
    const signal = hourly.mock.calls[1][4] as AbortSignal
    active.unmount()
    expect(signal.aborted).toBe(true)
  })
})
