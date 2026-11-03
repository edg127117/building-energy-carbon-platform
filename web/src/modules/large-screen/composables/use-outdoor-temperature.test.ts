import { defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useOutdoorTemperature } from './use-outdoor-temperature'
import type { ScreenPeriod } from '../models/screen-period'
import type { TemperatureTrend } from '@/modules/weather/public'
const api = vi.hoisted(() => vi.fn())
vi.mock('@/modules/weather/public', async original => {
  const actual = await original<typeof import('@/modules/weather/public')>()
  return { getTemperatureTrend: api, temperaturePeriodDates: actual.temperaturePeriodDates, temperatureTrendValues: actual.temperatureTrendValues }
})
function fixture(buildingId = 'B', period = '2024-02'): TemperatureTrend {
  const [year, month] = period.split('-').map(Number)
  return { buildingId, periodType: 'MONTH', period, timezoneId: 'Asia/Shanghai', unit: '°C', aggregation: 'COMPLETE_HISTORY_HOURLY_MEAN', asOfDate: '2026-10-30', status: 'READY', resultVersion: 'a'.repeat(64),
    sources: [{ datasetId: 'DATA', bindingId: 'LOCATION', locationVersion: 2, provider: 'OPEN_METEO', product: 'HISTORY_HOURLY', fetchedAt: '2026-10-29T00:00:00Z', contentHash: 'b'.repeat(64),
      provenance: { dataKind: 'MODEL_HISTORY_BEST_MATCH', sourcePublishedAt: null, gridLatitude: 31, gridLongitude: 121, parserVersion: 'v1', timezone: 'Asia/Shanghai', units: { temperature_2m: '°C' } } }],
    points: Array.from({ length: new Date(Date.UTC(year, month, 0)).getUTCDate() }, (_, index) => ({ date: `${period}-${String(index + 1).padStart(2, '0')}`, startInclusive: new Date(Date.UTC(year, month - 1, index + 1) - 28800000).toISOString(), endExclusive: new Date(Date.UTC(year, month - 1, index + 2) - 28800000).toISOString(), value: index === 0 ? '-5' : '0', status: 'READY', validHours: 24, expectedHours: 24, reasons: [], datasetIds: ['DATA'] })) }
}
const wrappers: Array<ReturnType<typeof mount>> = []
function harness() {
  const building = ref('B'), period = ref<ScreenPeriod>({ kind: 'month', value: '2024-02' })
  let state!: ReturnType<typeof useOutdoorTemperature>
  const wrapper = mount(defineComponent({ setup() { state = useOutdoorTemperature(building, period); return () => null } }))
  wrappers.push(wrapper)
  return { state, building, period, wrapper }
}
beforeEach(() => { api.mockReset(); api.mockResolvedValue(fixture()) })
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))
it('只消费后端完整均温，保留零值与负值', async () => {
  const { state } = harness(); await flushPromises()
  expect(api.mock.calls[0]!.slice(0, 3)).toEqual(['B', 'MONTH', '2024-02'])
  expect(state.values.value?.slice(0, 3)).toEqual([-5, 0, 0])
})
it('切建筑或期间会取消旧请求，迟到温度不能覆盖新上下文', async () => {
  let resolveOld!: (view: TemperatureTrend) => void
  api.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
  const { state, building, period } = harness()
  const signal = api.mock.calls[0]![3] as AbortSignal
  api.mockResolvedValue(fixture('OTHER', '2024-03'))
  building.value = 'OTHER'; period.value = { kind: 'month', value: '2024-03' }
  expect(state.values.value).toBeUndefined()
  await flushPromises(); resolveOld(fixture()); await flushPromises()
  expect(signal.aborted).toBe(true)
  expect(state.view.value?.buildingId).toBe('OTHER')
  expect(state.values.value).toHaveLength(31)
})
it('失败、错单位或错建筑会清空温度，不回退原型样例', async () => {
  const { state } = harness(); await flushPromises()
  api.mockResolvedValue({ ...fixture(), unit: '°F' })
  await state.reload(); expect(state.state.value).toBe('FAILED'); expect(state.values.value).toBeUndefined()
  api.mockResolvedValue(fixture('WRONG'))
  await state.reload(); expect(state.state.value).toBe('FAILED'); expect(state.view.value).toBeNull()
})
it('完整性不足时保留空值，不从剩余小时在前端补算', async () => {
  const view = fixture(); view.points[0] = { ...view.points[0]!, value: null, status: 'PARTIAL', validHours: 23 }; view.status = 'PARTIAL'
  api.mockResolvedValue(view)
  const { state } = harness(); await flushPromises()
  expect(state.values.value?.slice(0, 2)).toEqual([null, 0])
})
it('卸载停止请求；不支持的旧期间不发请求且不影响其他数据源', async () => {
  const { period, state, wrapper } = harness(); await flushPromises()
  const signal = api.mock.calls[0]![3] as AbortSignal
  period.value = { kind: 'year', value: '2023' }; await flushPromises()
  expect(api).toHaveBeenCalledTimes(1)
  expect(state.state.value).toBe('UNSUPPORTED_PERIOD')
  expect(signal.aborted).toBe(true)
  wrapper.unmount(); expect(state.values.value).toBeUndefined()
})
