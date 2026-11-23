import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import { useAttrs } from 'vue'
import ChartView from '@/shared/charts/ChartView.vue'
import { alarmsApi, type AlarmEvent, type AlarmEventPage } from '@/modules/alarms/public'
import { getBuildingCurrentDayWeather, listAccessibleWeatherBuildings, type CurrentDayWeather } from '@/modules/weather/public'
import { comfortApi, type ComfortDoorStats, type ComfortHvacStatus, type ComfortOccupancy, type ComfortOverview } from '../api/comfort'
import ComfortPage from './ComfortPage.vue'

const menuState = vi.hoisted(() => ({ hvacVisible: false }))
vi.mock('@/modules/auth/public', async original => ({
  ...await original<typeof import('@/modules/auth/public')>(),
  useSession: () => ({ menus: menuState.hvacVisible
    ? [{ status: 1, visible: 1, menuType: 'C', path: '/operations/realtime/hvac' }] : [] }),
}))
vi.mock('@/modules/alarms/public', async original => ({
  ...await original<typeof import('@/modules/alarms/public')>(),
  alarmsApi: { events: vi.fn() },
}))
vi.mock('@/modules/dashboard/public', async original => ({
  ...await original<typeof import('@/modules/dashboard/public')>(),
  useHvacControlAccess: () => ({ testOpenControls: { value: false } }),
}))
vi.mock('@/modules/weather/public', async original => ({
  ...await original<typeof import('@/modules/weather/public')>(),
  listAccessibleWeatherBuildings: vi.fn(),
  getBuildingCurrentDayWeather: vi.fn(),
}))
vi.mock('../api/comfort', async original => ({
  ...await original<typeof import('../api/comfort')>(),
  comfortApi: {
    spaces: vi.fn(), overview: vi.fn(), trends: vi.fn(), occupancy: vi.fn(), doorStats: vi.fn(), hvacStatus: vi.fn(),
    parameters: vi.fn(), saveParameters: vi.fn(), electricity: vi.fn(),
  },
}))

const room = { buildingId: 'building', spaceId: 'r314', spaceName: 'B314', floorName: '三层' }
const nextRoom = { ...room, spaceId: 'r313', spaceName: 'B313' }
const sources = [
  { equipmentId: 'idu7', equipmentName: 'IDU7' },
  { equipmentId: 'idu8', equipmentName: 'IDU8' },
  { equipmentId: 'idu15', equipmentName: 'IDU15' },
]
const observedAt = '2026-11-19T00:31:00Z'
const noAlarms: AlarmEventPage = { items: [], total: 0, page: 1, size: 10 }
function weatherSnapshot(value: number | null, updateStatus = 'FRESH', hours: CurrentDayWeather['hours'] = []): CurrentDayWeather {
  return {
    date: '2026-11-19',
    current: {
      location: null, source: 'OPEN_METEO', product: 'CURRENT',
      status: value == null ? 'MISSING' : 'READY', updateStatus,
      fetchedAt: observedAt, datasetId: null, provenance: null,
      samples: value == null ? [] : [{ time: observedAt, values: { temperature_2m: value }, text: {} }],
    },
    hours,
  }
}

const sensorSnapshot = {
  date: '2026-11-19', timeZone: 'Asia/Shanghai', asOf: observedAt, status: 'UNCONFIGURED',
  communication: null, lastObservedAt: null,
} as const
const noOccupancy: ComfortOccupancy = {
  ...sensorSnapshot, currentCount: null, lastObservedCount: null, series1m: [], series15m: [], hourlyAvg: [],
}
const noDoor: ComfortDoorStats = {
  ...sensorSnapshot, doorOpen: null, lastObservedOpen: null, lastTransitionAt: null,
  currentDurationSec: null, recentEvents: [], hourlyRows: [], durationBuckets: [],
}
const noHvac: ComfortHvacStatus = {
  total: 0, running: null, unknown: 0, status: 'UNCONFIGURED', lastObservedAt: null,
  setpoint: { status: 'MISSING', value: null }, fanSpeed: { status: 'MISSING', value: null }, airflowSpeed: null,
  controlEntryAvailable: false,
}

function snapshot(space = room, value: number | null = 24.3): ComfortOverview {
  return {
    space, temperatureSources: sources, selectedSource: null,
    temperatureSourceStatus: 'ROOM_AVERAGE', pointId: null, fieldStatus: null,
    indoorTemperature: value == null ? null : { celsius: value, observedAt, stale: false },
  }
}

function mountPage() {
  return mount(ComfortPage, {
    global: { stubs: { ChartView: true, ElAlert: true, ElDialog: true, RouterLink: true,
      ElPopover: { template: '<div><slot name="reference" /><slot /></div>' },
      ElDrawer: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
      ElButton: { template: '<button><slot /></button>' },
      ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
      ElSelect: {
        props: ['modelValue'],
        emits: ['update:modelValue'],
        setup() { return { label: useAttrs()['aria-label'] } },
        template: '<select :aria-label="label" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option value=""></option><slot /></select>',
      },
    } },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  menuState.hvacVisible = false
  vi.mocked(listAccessibleWeatherBuildings).mockResolvedValue([{ buildingId: 'building', buildingName: '测试建筑' }])
  vi.mocked(comfortApi.spaces).mockResolvedValue([room, nextRoom])
  vi.mocked(comfortApi.overview).mockImplementation(async space => snapshot(space === room.spaceId ? room : nextRoom))
  vi.mocked(comfortApi.trends).mockImplementation(async space => ({
    ...snapshot(space === room.spaceId ? room : nextRoom),
    date: '2026-11-19', timeZone: 'Asia/Shanghai',
    hours: [{ startsAt: '2026-11-19T00:00:00Z', indoorTemperature: { celsius: 24.3, observedAt, stale: false } }],
  }))
  vi.mocked(comfortApi.occupancy).mockResolvedValue(noOccupancy)
  vi.mocked(comfortApi.doorStats).mockResolvedValue(noDoor)
  vi.mocked(comfortApi.hvacStatus).mockResolvedValue(noHvac)
  vi.mocked(comfortApi.electricity).mockResolvedValue({ date: '2026-11-19', power: null, carbonRateKgPerHour: null,
    carbonTodayKg: null, carbonHours: [], carbonStatus: 'NO_METER' })
  vi.mocked(comfortApi.parameters).mockResolvedValue({ comfortBand: null, manualInputs: {}, editable: true })
  vi.mocked(alarmsApi.events).mockResolvedValue(noAlarms)
  vi.mocked(getBuildingCurrentDayWeather).mockResolvedValue(weatherSnapshot(null))
})

it('uses the newest observation from the displayed sources and distinguishes stale readings from online communication', async () => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-11-19T02:00:00Z'))
  vi.mocked(comfortApi.occupancy).mockResolvedValue({ ...noOccupancy, status: 'STALE', lastObservedAt: observedAt,
    communication: { state: 'ONLINE', reason: 'LIVE_TELEMETRY', lastContactAt: observedAt } })
  vi.mocked(comfortApi.doorStats).mockResolvedValue({ ...noDoor, status: 'AVAILABLE', lastObservedAt: observedAt, doorOpen: false })
  vi.mocked(comfortApi.hvacStatus).mockResolvedValue({ ...noHvac, status: 'AVAILABLE', lastObservedAt: Date.parse('2026-11-19T01:40:00Z') })
  vi.mocked(getBuildingCurrentDayWeather).mockResolvedValue({ ...weatherSnapshot(8), current: { ...weatherSnapshot(8).current,
    samples: [{ time: '2026-11-19T01:50:00Z', values: { temperature_2m: 8 }, text: {} }] } })
  vi.mocked(comfortApi.electricity).mockResolvedValue({ date: '2026-11-19', carbonStatus: 'AVAILABLE',
    power: { watts: 40, observedAt: '2026-11-19T01:58:00Z', stale: false }, carbonRateKgPerHour: .02, carbonTodayKg: .30,
    carbonHours: [{ startsAt: '2026-11-19T01:00:00Z', observedAt: '2026-11-19T01:59:00Z', cumulativeKg: .30 }] })
  const wrapper = mountPage()
  try {
    await flushPromises()
    expect(wrapper.get('.comfort-connectivity').text()).toContain('雷达 · 读数已过期')
    expect(wrapper.get('.comfort-connectivity').text()).toContain('门磁 · 在线')
    expect(wrapper.get('.comfort-toolbar-actions time').text()).toBe('09:59:00')
  } finally {
    wrapper.unmount()
    vi.useRealTimers()
  }
})

it('shows the control entry only when the current room has explicit authorization', async () => {
  menuState.hvacVisible = true
  const wrapper = mountPage()
  try {
    await flushPromises()
    expect(wrapper.find('.comfort-ac-link').exists()).toBe(false)
    vi.mocked(comfortApi.hvacStatus).mockResolvedValueOnce({ ...noHvac, controlEntryAvailable: true })
    await wrapper.findAll('button').find(button => button.text() === '刷新')?.trigger('click')
    await flushPromises()
    expect(wrapper.find('.comfort-ac-link').exists()).toBe(true)
    await wrapper.get('select[aria-label="房间"]').setValue(nextRoom.spaceId)
    expect(wrapper.find('.comfort-ac-link').exists()).toBe(false)
    await flushPromises()
    expect(wrapper.find('.comfort-ac-link').exists()).toBe(false)
  } finally { wrapper.unmount() }
})

it('shows the highest room alarm and clears the summary and details on a room switch', async () => {
  const alarm: AlarmEvent = {
    id: 'urgent', buildingId: 'building', category: 'DEVICE', type: 'FAULT', objectId: 'IDU7', objectName: '室内机 7',
    spaceName: 'B314', period: null, status: 'ACTIVE', severity: 'URGENT', description: '设备故障',
    firstAt: Date.parse(observedAt), lastAt: Date.parse(observedAt), recoveredAt: null,
    ackBy: null, ackAt: null, silencedUntil: null, ruleRevision: 1, triggerEvidence: '{}', evidence: '{"sourceCode":"A3"}',
  }
  const result = { ...noAlarms, items: [
    alarm,
    { ...alarm, id: 'fault', description: 'U4', evidence: '{"sourceCode":"VENDOR_COMMUNICATION"}' },
    {
      ...alarm, id: 'door', ruleId: 'comfort-door-timeout', type: 'VALUE' as const, severity: 'GENERAL' as const,
      description: '门开超时', evidence: '{"observations":[{"pointName":"开门时长","value":1800,"unit":"秒"}]}',
    },
  ], total: 3 }
  let completeB314Refresh: ((page: AlarmEventPage) => void) | undefined
  vi.mocked(alarmsApi.events).mockResolvedValueOnce(result)
    .mockImplementationOnce(() => new Promise(resolve => { completeB314Refresh = resolve }))
  const wrapper = mountPage()
  try {
    await flushPromises()
    expect(wrapper.get('.room-alarm-pill').text()).toContain('B314 · 室内机 7 · A3 · 设备故障')
    expect(wrapper.get('.room-alarm-extra').text()).toBe('+2')
    const details = wrapper.findAll('.room-alarm-item')
    expect(details).toHaveLength(3)
    expect(details[1]?.text()).toContain('设备通信故障 · U4')
    expect(details[2]?.text()).toContain('开门时长 30分 · 门开超时')
    await wrapper.findAll('button').find(item => item.text() === '刷新')?.trigger('click')
    await wrapper.get('select[aria-label="房间"]').setValue(nextRoom.spaceId)
    completeB314Refresh?.(result)
    await flushPromises()
    expect(wrapper.get('.comfort-alarm-strip').text()).toContain('暂无活动报警')
    expect(wrapper.find('.room-alarm-item').exists()).toBe(false)
    expect(wrapper.find('.room-alarm-pill').exists()).toBe(false)
    expect(alarmsApi.events).toHaveBeenLastCalledWith('building', expect.objectContaining({ spaceId: 'r313' }), expect.any(AbortSignal))
  } finally { wrapper.unmount() }
})

it('displays only power when the saved factor is cleared and resets electricity on a room change', async () => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(observedAt))
  const initial = { date: '2026-11-19', power: { watts: 40, observedAt, stale: false },
    carbonRateKgPerHour: .02, carbonTodayKg: .23, carbonHours: [], carbonStatus: 'AVAILABLE' as const }
  vi.mocked(comfortApi.parameters).mockResolvedValueOnce({ comfortBand: null,
    manualInputs: { dynamicEmissionFactor: .5 }, editable: true })
  vi.mocked(comfortApi.saveParameters).mockResolvedValue({ comfortBand: null, manualInputs: {}, editable: true })
  vi.mocked(comfortApi.electricity).mockResolvedValueOnce(initial).mockResolvedValueOnce({ ...initial,
    carbonRateKgPerHour: null, carbonTodayKg: null, carbonStatus: 'NO_FACTOR' })
  const wrapper = mountPage()
  try {
    await flushPromises()
    expect(wrapper.get('[data-test="comfort-power"]').text()).toContain('40 W')
    expect(wrapper.get('[data-test="comfort-carbon-rate"]').text()).toContain('0.020')
    expect(wrapper.get('[data-test="comfort-carbon-today"]').text()).toContain('0.23')
    const button = (label: string) => wrapper.findAll('button').find(item => item.text().trim() === label)!
    await button('参数配置').trigger('click')
    await wrapper.get('#comfort-param-dynamicEmissionFactor').setValue('')
    await button('保存并应用').trigger('click')
    await flushPromises()
    expect(comfortApi.saveParameters).toHaveBeenCalledWith(room.spaceId, {
      manualInputs: { dynamicEmissionFactor: null },
    }, expect.any(String))
    expect(wrapper.get('[data-test="comfort-power"]').text()).toContain('40 W')
    expect(wrapper.find('[data-test="comfort-carbon-rate"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="comfort-carbon-today"]').exists()).toBe(false)
    await wrapper.get('select[aria-label="房间"]').setValue(nextRoom.spaceId)
    await flushPromises()
    expect(wrapper.get('[data-test="comfort-power"]').text()).not.toContain('40 W')
    expect(wrapper.text()).toContain('暂无房间计量')
  } finally {
    wrapper.unmount()
    vi.useRealTimers()
  }
})

it('plots only saved room parameters and clears the band when switching rooms', async () => {
  const saved = { tLow: 23, tHigh: 27.5 }
  vi.mocked(comfortApi.saveParameters).mockResolvedValue({ comfortBand: saved, manualInputs: {}, editable: true })
  const wrapper = mountPage()
  const button = (label: string) => wrapper.findAll('button').find(item => item.text() === label)!
  const chart = () => wrapper.get('.comfort-analysis').getComponent(ChartView).props('option').series
  try {
    await flushPromises()
    expect(chart()).not.toMatchObject([{ markArea: expect.anything() }, {}])
    await button('参数配置').trigger('click')
    await wrapper.get('#comfort-t-low').setValue('23')
    await wrapper.get('#comfort-t-high').setValue('22')
    await button('保存并应用').trigger('click')
    expect(comfortApi.saveParameters).not.toHaveBeenCalled()
    expect(chart()).not.toMatchObject([{ markArea: expect.anything() }, {}])
    await wrapper.get('#comfort-t-high').setValue('27.5')
    vi.mocked(comfortApi.parameters).mockResolvedValueOnce({ comfortBand: saved, manualInputs: {}, editable: true })
    await button('保存并应用').trigger('click')
    await flushPromises()
    expect(comfortApi.saveParameters).toHaveBeenCalledWith(room.spaceId, saved, expect.any(String))
    expect(wrapper.get('.comfort-kpi-heading .comfort-badge').text()).toBe('23–27.5℃')
    expect(chart()).toMatchObject([{ markArea: { data: [[{ yAxis: 23 }, { yAxis: 27.5 }]] } }, {}])
    await wrapper.get('select[aria-label="房间"]').setValue(nextRoom.spaceId)
    await flushPromises()
    expect(chart()).not.toMatchObject([{ markArea: expect.anything() }, {}])
    expect(wrapper.find('.comfort-kpi-heading .comfort-badge').exists()).toBe(false)
  } finally { wrapper.unmount() }
})

it('saves just edited inputs, keeps the measured temperature, and blocks invalid values', async () => {
  const before = { comfortBand: { tLow: 22, tHigh: 26 }, manualInputs: { metabolicRate: 1.3 }, editable: true }
  const saved = { ...before, manualInputs: { ...before.manualInputs, airVelocity: 0, airTemperature: 27.5 } }
  vi.mocked(comfortApi.parameters).mockResolvedValueOnce(before).mockResolvedValueOnce(saved)
  vi.mocked(comfortApi.saveParameters).mockResolvedValue(saved)
  const wrapper = mountPage()
  const button = (label: string) => wrapper.findAll('button').find(item => item.text() === label)!
  try {
    await flushPromises()
    await button('参数配置').trigger('click')
    await wrapper.get('#comfort-param-airVelocity').setValue('0.46')
    await button('保存并应用').trigger('click')
    expect(comfortApi.saveParameters).not.toHaveBeenCalled()
    await wrapper.get('#comfort-param-airVelocity').setValue('0')
    await wrapper.get('#comfort-param-airTemperature').setValue('27.5')
    await button('保存并应用').trigger('click')
    await flushPromises()
    expect(comfortApi.saveParameters).toHaveBeenCalledWith(room.spaceId, {
      manualInputs: { airVelocity: 0, airTemperature: 27.5 },
    }, expect.any(String))
    expect(wrapper.findAll('.comfort-param strong').map(value => value.text())).toEqual([
      '1.3', '—', '0.00', '—', '24.3', '—', '—',
    ])
    expect(wrapper.get('.comfort-temperature').text()).toContain('24.3')
    expect(wrapper.get('.comfort-analysis').getComponent(ChartView).props('option').series)
      .toMatchObject([{ markArea: { data: [[{ yAxis: 22 }, { yAxis: 26 }]] } }, {}])
  } finally { wrapper.unmount() }
})

it('clamps RH on blur, reuses the save key after a lost response and shows the room RH', async () => {
  const before = { comfortBand: null, manualInputs: { relativeHumidity: 52 }, editable: true }
  const saved = { ...before, manualInputs: { relativeHumidity: 80 } }
  vi.mocked(comfortApi.parameters).mockResolvedValueOnce(before).mockResolvedValueOnce(saved)
    .mockResolvedValue({ ...before, manualInputs: {} })
  vi.mocked(comfortApi.saveParameters).mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce(saved)
  const wrapper = mountPage()
  const button = (label: string) => wrapper.findAll('button').find(item => item.text() === label)!
  try {
    await flushPromises()
    expect(wrapper.get('.comfort-humidity').text()).toBe('室内相对湿度 52%')
    await button('参数配置').trigger('click')
    const input = wrapper.get<HTMLInputElement>('#comfort-param-relativeHumidity')
    await input.setValue('85')
    await input.trigger('blur')
    expect(input.element.value).toBe('80')
    await button('保存并应用').trigger('click')
    await flushPromises()
    await button('保存并应用').trigger('click')
    await flushPromises()
    const calls = vi.mocked(comfortApi.saveParameters).mock.calls
    expect(calls).toHaveLength(2)
    expect(calls[1]).toEqual(calls[0])
    expect(wrapper.get('.comfort-humidity').text()).toBe('室内相对湿度 80%')
    await wrapper.get('select[aria-label="房间"]').setValue(nextRoom.spaceId)
    await flushPromises()
    expect(wrapper.get('.comfort-humidity').text()).toBe('室内相对湿度 —')
  } finally { wrapper.unmount() }
})

it('shows measured Ta and PMV with distinct empty reasons and clears them on room change', async () => {
  const ready = { ...snapshot(), pmv: { status: 'AVAILABLE', value: -0.17, sensation: 'COMFORTABLE', missingInputs: [] } } as const
  const unavailable = { value: null, sensation: null, missingInputs: [] }
  vi.mocked(comfortApi.overview)
    .mockResolvedValueOnce({ ...ready, pmv: { ...ready.pmv, missingInputs: [] } })
    .mockResolvedValueOnce({ ...snapshot(), pmv: { ...unavailable, status: 'PARAMETERS_MISSING', missingInputs: ['relativeHumidity'] } })
    .mockResolvedValueOnce({ ...snapshot(room, null), pmv: { ...unavailable, status: 'TEMPERATURE_STALE' } })
    .mockResolvedValueOnce({ ...snapshot(nextRoom, null), pmv: { ...unavailable, status: 'TEMPERATURE_MISSING' } })
  const wrapper = mountPage()
  const pmv = () => wrapper.get('.comfort-pmv-value').text()
  const note = () => wrapper.get('.comfort-pmv-note').text()
  const ta = () => wrapper.findAll('.comfort-param').find(item => item.text().includes('室温 Ta'))?.get('strong').text()
  const refresh = async () => {
    await wrapper.findAll('button').find(item => item.text() === '刷新')?.trigger('click')
    await flushPromises()
  }
  try {
    await flushPromises()
    expect([pmv(), note(), ta()]).toEqual(['-0.17', '处于舒适区间', '24.3'])
    await refresh()
    expect([pmv(), note(), ta()]).toEqual(['—', '缺少湿度 RH', '24.3'])
    await refresh()
    expect([pmv(), note(), ta()]).toEqual(['—', '实测室温已过期', '—'])
    await wrapper.get('select[aria-label="房间"]').setValue(nextRoom.spaceId)
    await flushPromises()
    expect([pmv(), note(), ta()]).toEqual(['—', '缺少实时室温', '—'])
  } finally { wrapper.unmount() }
})

it('uses the same HVAC summary for the read-only card and airflow without turning stale observations into off', async () => {
  const lastObservedAt = Date.parse(observedAt)
  const off: ComfortHvacStatus = {
    ...noHvac, total: 3, running: 0, status: 'AVAILABLE', lastObservedAt,
    setpoint: { status: 'VALUE', value: 24 }, fanSpeed: { status: 'VALUE', value: 'middle' },
  }
  const part: ComfortHvacStatus = {
    ...off, running: 2, setpoint: { status: 'MIXED', value: null },
    fanSpeed: { status: 'MIXED', value: null }, airflowSpeed: 'high',
  }
  vi.mocked(comfortApi.hvacStatus).mockResolvedValueOnce(off).mockResolvedValueOnce(part)
    .mockResolvedValueOnce({ ...noHvac, total: 3, unknown: 3, status: 'STALE', lastObservedAt })
  const wrapper = mountPage()
  const refresh = async () => {
    await wrapper.findAll('button').find(button => button.text() === '刷新')?.trigger('click')
    await flushPromises()
  }
  const scene = () => wrapper.get('.comfort-scene .comfort-room-scene')
  try {
    await flushPromises()
    expect(comfortApi.hvacStatus).toHaveBeenCalledWith('r314', expect.any(AbortSignal))
    expect(wrapper.get('.comfort-ac-summary').text()).toBe('关停 · 0/3台')
    expect(wrapper.findAll('.comfort-ac-settings > span').map((item) => item.text())).toEqual(['设温 24.0°C', '风速 中风'])
    expect(wrapper.find('.comfort-ac-link').exists()).toBe(false)
    expect(scene().findAll('.room-airflow-stream')).toHaveLength(0)

    await refresh()
    expect(wrapper.get('.comfort-ac-summary').text()).toBe('开启 · 2/3台')
    expect(wrapper.findAll('.comfort-ac-settings > span').map((item) => item.text())).toEqual(['设温 不一致', '风速 不一致'])
    expect(scene().attributes('data-ac-running')).toBe('true')
    expect(scene().findAll('.room-airflow-stream')).toHaveLength(4)

    await refresh()
    expect(wrapper.get('.comfort-ac-summary').text()).toBe('未知 · 共3台')
    expect(wrapper.get('.comfort-ac-note').text()).toMatch(/已过期.*08:31:00/)
    expect(wrapper.get('.comfort-connectivity').text()).toContain('空调 · 读数已过期')
    expect(scene().attributes('data-ac-running')).toBe('false')
    expect(scene().findAll('.room-airflow-stream')).toHaveLength(0)

    await wrapper.get('select[aria-label="房间"]').setValue('r313')
    await flushPromises()
    expect(wrapper.get('.comfort-ac-summary').text()).toBe('—')
    expect(wrapper.find('.comfort-ac-note').exists()).toBe(false)
  } finally { wrapper.unmount() }
})

it('loads the room average without an equipment picker and keeps the prototype sections visible', async () => {
  const wrapper = mountPage()
  try {
    await flushPromises()
    expect(comfortApi.spaces).toHaveBeenCalledWith('building', expect.any(AbortSignal))
    expect(wrapper.get('select[aria-label="房间"]').text()).toContain('B314')
    expect(wrapper.findAll('select')).toHaveLength(2)
    expect(comfortApi.overview).toHaveBeenLastCalledWith('r314', expect.any(AbortSignal))
    expect(comfortApi.trends).toHaveBeenLastCalledWith('r314', expect.any(AbortSignal))
    expect(wrapper.get('.comfort-temperature').text()).toContain('24.3')
    expect(wrapper.get('time').attributes('datetime')).toBe(observedAt)
    expect(wrapper.findAll('.comfort-panel h2').map(heading => heading.text())).toEqual([
      '实时在室人数', '逐小时平均在室人数', '24h 趋势分析', '2.5D 空间',
      'MPC 24h 预测 · 室内温度与出力调度', '门窗状态 · 逐小时开门统计', '开门时长分布',
    ])
    expect(wrapper.get('.comfort-kpis').findAll('h2')).toHaveLength(4)
    expect(wrapper.get('.comfort-parameters').findAll('.comfort-param')).toHaveLength(7)
    expect(wrapper.text()).toContain('人数雷达未接入')
    expect(wrapper.text()).toContain('门磁未接入')
  } finally { wrapper.unmount() }
})

it('clears the old room selection and ignores an earlier response even if it does not honor cancellation', async () => {
  let deliver: (value: ComfortOverview) => void = () => undefined
  let deliverDoor: (value: ComfortDoorStats) => void = () => undefined
  vi.mocked(comfortApi.overview).mockImplementation(async space => {
    if (space === room.spaceId) return new Promise(resolve => { deliver = resolve })
    return snapshot(nextRoom, null)
  })
  vi.mocked(comfortApi.doorStats).mockImplementation(async space => {
    if (space === room.spaceId) return new Promise(resolve => { deliverDoor = resolve })
    return noDoor
  })
  const wrapper = mountPage()
  try {
    await flushPromises()
    await wrapper.get('select[aria-label="房间"]').setValue('r313')
    await flushPromises()
    deliver(snapshot(room, 99.9))
    deliverDoor({ ...noDoor, status: 'AVAILABLE', doorOpen: true })
    await flushPromises()
    expect(wrapper.get('.comfort-temperature').text()).toBe('—')
    expect(wrapper.text()).toContain('暂无室温读数')
    expect(comfortApi.overview).toHaveBeenLastCalledWith('r313', expect.any(AbortSignal))
    expect(comfortApi.doorStats).toHaveBeenLastCalledWith('r313', expect.any(AbortSignal))
    expect(wrapper.get('.door-hero').attributes('data-door-state')).toBe('unknown')
    expect(wrapper.get('.comfort-room-scene').attributes('data-door-state')).toBe('unknown')
  } finally { wrapper.unmount() }
})

it('shows building weather beside the indoor reading and leaves missing current weather empty', async () => {
  vi.mocked(getBuildingCurrentDayWeather).mockResolvedValue(weatherSnapshot(0, 'OVERDUE'))
  const wrapper = mountPage()
  try {
    await flushPromises()
    expect(getBuildingCurrentDayWeather).toHaveBeenCalledWith('building', expect.any(AbortSignal))
    expect(wrapper.get('.comfort-temperature').text()).toContain('24.3')
    expect(wrapper.get('.comfort-outdoor').text()).toBe('室外 0.0°C')
    expect(wrapper.get('.comfort-outdoor').classes()).toContain('comfort-outdoor-delayed')
    expect(wrapper.get('.comfort-outdoor').attributes('title')).toContain('2026')
    vi.mocked(getBuildingCurrentDayWeather).mockResolvedValue(weatherSnapshot(null))
    await wrapper.findAll('button').find(button => button.text() === '刷新')?.trigger('click')
    await flushPromises()
    expect(getBuildingCurrentDayWeather).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.comfort-outdoor').text()).toBe('室外 —')
    expect(wrapper.get('.comfort-outdoor').attributes('title')).toContain('暂无当前室外温度')
    expect(wrapper.get('.comfort-temperature').text()).toContain('24.3')
  } finally { wrapper.unmount() }
})

it('keeps the new building outdoor reading when an older building response arrives late', async () => {
  vi.mocked(listAccessibleWeatherBuildings).mockResolvedValue([
    { buildingId: 'building', buildingName: '一号楼' },
    { buildingId: 'other', buildingName: '二号楼' },
  ])
  vi.mocked(comfortApi.spaces).mockImplementation(async building =>
    building === room.buildingId ? [room] : [{ ...nextRoom, buildingId: 'other' }],
  )
  let deliver: (value: CurrentDayWeather) => void = () => undefined
  vi.mocked(getBuildingCurrentDayWeather).mockImplementation(async building =>
    building === room.buildingId ? new Promise(resolve => { deliver = resolve }) : weatherSnapshot(-4.6),
  )
  const wrapper = mountPage()
  try {
    await flushPromises()
    await wrapper.get('select[aria-label="建筑"]').setValue('other')
    await flushPromises()
    expect(getBuildingCurrentDayWeather).toHaveBeenLastCalledWith('other', expect.any(AbortSignal))
    expect(wrapper.get('.comfort-outdoor').text()).toBe('室外 -4.6°C')
    deliver(weatherSnapshot(20))
    await flushPromises()
    expect(wrapper.get('.comfort-outdoor').text()).toBe('室外 -4.6°C')
  } finally { wrapper.unmount() }
})

it('automatically refreshes the outdoor badge and hourly series with the same room refresh cycle', async () => {
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] })
  vi.setSystemTime(new Date(observedAt))
  const visibility = vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  vi.mocked(getBuildingCurrentDayWeather).mockResolvedValue(weatherSnapshot(10))
  const wrapper = mountPage()
  try {
    await flushPromises()
    expect(wrapper.get('.comfort-outdoor').text()).toBe('室外 10.0°C')
    vi.mocked(getBuildingCurrentDayWeather).mockResolvedValue(weatherSnapshot(7, 'FRESH', [
      { startsAt: '2026-11-19T00:00:00Z', observedAt, celsius: 7 },
    ]))
    await vi.advanceTimersByTimeAsync(30000)
    await flushPromises()
    expect(comfortApi.overview).toHaveBeenCalledTimes(2)
    expect(getBuildingCurrentDayWeather).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.comfort-outdoor').text()).toBe('室外 7.0°C')
    const chart = wrapper.get('.comfort-analysis').getComponent(ChartView)
    expect(chart.props('option')).toMatchObject({ series: [
      { name: '室内温度' },
      { name: '室外气象温度', data: Array.from({ length: 24 }, (_, hour) => hour === 8 ? 7 : null) },
    ] })
    visibility.mockReturnValue(true)
    await vi.advanceTimersByTimeAsync(30000)
    expect(getBuildingCurrentDayWeather).toHaveBeenCalledTimes(2)
  } finally {
    wrapper.unmount()
    visibility.mockRestore()
    vi.useRealTimers()
  }
})

it('plots a recorded zero and renders open, closed and unknown from the same door response', async () => {
  vi.mocked(comfortApi.occupancy).mockResolvedValue({
    ...noOccupancy, status: 'AVAILABLE', currentCount: 0, lastObservedCount: 0, lastObservedAt: observedAt,
    series1m: [{ startsAt: observedAt, count: 0, observedAt }], hourlyAvg: [{ startsAt: observedAt, averageCount: 0, sampleCount: 1 }],
  })
  const open: ComfortDoorStats = {
    ...noDoor, status: 'AVAILABLE', doorOpen: true, lastObservedOpen: true, lastObservedAt: observedAt,
    currentDurationSec: 60, hourlyRows: [{ startsAt: observedAt, openCount: 2, durationSec: 80, hasLongOpen: true }],
    recentEvents: [
      { observedAt, open: true, durationSec: null },
      { observedAt: '2026-11-19T00:24:00Z', open: false, durationSec: 59.9 },
      { observedAt: '2026-11-19T00:14:00Z', open: false, durationSec: 3599.9 },
      { observedAt: '2026-11-19T00:01:00Z', open: false, durationSec: 3600 },
      { observedAt: '2026-11-18T23:00:00Z', open: false, durationSec: 3663.5 },
    ],
    durationBuckets: [{ bucket: 'SHORT_LE_15S', count: 0, durationSec: 0 }, { bucket: 'LONG_GT_60S', count: 1, durationSec: 75 }],
  }
  vi.mocked(comfortApi.doorStats).mockResolvedValue(open)
  const wrapper = mountPage()
  const state = (expected: string) => {
    expect(wrapper.get('.door-hero').attributes('data-door-state')).toBe(expected)
    expect(wrapper.get('.comfort-room-scene').attributes('data-door-state')).toBe(expected)
  }
  const refresh = async () => {
    await wrapper.findAll('button').find(button => button.text() === '刷新')?.trigger('click')
    await flushPromises()
  }
  try {
    await flushPromises()
    expect(comfortApi.occupancy).toHaveBeenCalledWith('r314', expect.any(AbortSignal))
    expect(wrapper.get('.comfort-people-now').text()).toBe('当前 0 人')
    expect(wrapper.get('.comfort-room-scene').text()).toContain('当前无人')
    expect(wrapper.find('.comfort-radar-trend chart-view-stub').exists()).toBe(true)
    expect(wrapper.find('.comfort-radar-hourly chart-view-stub').exists()).toBe(true)
    state('open')
    expect(wrapper.get('.door-long-open').text()).toContain('80')
    expect(wrapper.get('.door-events').text()).toContain('开启')
    expect(wrapper.get('.door-hero-text').text()).toContain('已持续 1分')
    expect(wrapper.findAll('.door-event-duration').map(cell => cell.text()))
      .toEqual(['—', '59秒', '59分59秒', '1小时', '1小时1分3秒'])
    expect(wrapper.get('.door-legend').text()).toContain('0 次')
    vi.mocked(comfortApi.doorStats).mockResolvedValue({ ...open, doorOpen: false, lastObservedOpen: false })
    await refresh()
    state('closed')
    vi.mocked(comfortApi.doorStats).mockResolvedValue({ ...open, status: 'STALE', doorOpen: null })
    await refresh()
    state('unknown')
    expect(wrapper.get('.comfort-door-table').text()).toContain('80')
  } finally { wrapper.unmount() }
})

it('groups multiple doors and windows, checks one door at a time, and clears the selection on a room change', async () => {
  const contact = (equipmentId: string, name: string, kind: 'DOOR' | 'WINDOW', open: boolean | null) => ({
    equipmentId, name, kind, open, lastObservedOpen: open ?? false, lastObservedAt: observedAt,
  })
  const grouped: ComfortDoorStats = {
    ...noDoor, status: 'SELECTION_REQUIRED', doors: { open: 1, closed: 1, unknown: 1 },
    windows: { open: 2, closed: 4, unknown: 0 },
    contacts: [contact('east', '东门', 'DOOR', true), contact('west', '西门', 'DOOR', false),
      contact('north', '北门', 'DOOR', null), ...Array.from({ length: 6 }, (_, index) => ({
        ...contact(`window-${index}`, `南侧 ${index + 1} 号窗`, 'WINDOW', index < 2),
        lastObservedAt: '2026-11-19T00:32:00Z',
      }))],
  }
  vi.mocked(comfortApi.doorStats).mockImplementation(async (space, _signal, selected) => {
    if (space === nextRoom.spaceId) return noDoor
    if (!selected) return grouped
    return { ...grouped, status: 'AVAILABLE', doorOpen: true, selectedDoorEquipmentId: selected }
  })
  const wrapper = mountPage()
  try {
    await flushPromises()
    expect(wrapper.findAll('.contact-summary')).toHaveLength(2)
    expect(wrapper.get('.contact-summaries').text()).toMatch(/1 开.*1 关.*1 未知.*2 开.*4 关/)
    expect(wrapper.get('.comfort-toolbar-actions time').text()).toBe('08:32:00')
    expect(wrapper.find('.contact-details').exists()).toBe(false)
    await wrapper.get('.contact-summaries').trigger('click')
    expect(wrapper.get('section[aria-label="门"]').findAll('li')).toHaveLength(3)
    expect(wrapper.get('section[aria-label="窗"]').findAll('li')).toHaveLength(6)
    expect(wrapper.get('section[aria-label="门"]').text()).toContain('上次关闭 · 已过期')
    expect(wrapper.get('section[aria-label="窗"]').findAll('button')).toHaveLength(0)
    await wrapper.get('button[aria-label="东门 查看统计"]').trigger('click')
    await flushPromises()
    expect(comfortApi.doorStats).toHaveBeenLastCalledWith('r314', expect.any(AbortSignal), 'east')
    expect(wrapper.get('.door-hero-text').text()).toContain('东门')
    expect(wrapper.get('.comfort-room-scene').attributes('data-door-state')).toBe('unknown')
    await wrapper.get('select[aria-label="房间"]').setValue('r313')
    await flushPromises()
    expect(comfortApi.doorStats).toHaveBeenLastCalledWith('r313', expect.any(AbortSignal))
    expect(wrapper.find('.contact-summaries').exists()).toBe(false)
    expect(wrapper.get('.door-hero').attributes('data-door-state')).toBe('unknown')
  } finally { wrapper.unmount() }
})

it('shows stale values with observation times on first load, and clears the notices after recovery and a room change', async () => {
  vi.mocked(comfortApi.overview).mockResolvedValueOnce({
    ...snapshot(room, null),
    lastValidIndoorTemperature: { celsius: 24.3, observedAt, stale: true },
  }).mockResolvedValueOnce(snapshot()).mockResolvedValueOnce(snapshot(nextRoom, null))
  vi.mocked(comfortApi.occupancy).mockResolvedValueOnce({
    ...noOccupancy, status: 'STALE', lastObservedCount: 2, lastObservedAt: observedAt,
    communication: { state: 'OFFLINE', reason: 'TIMEOUT', lastContactAt: observedAt },
  }).mockResolvedValueOnce({ ...noOccupancy, status: 'AVAILABLE', currentCount: 0, lastObservedCount: 0, lastObservedAt: observedAt })
  vi.mocked(comfortApi.doorStats).mockResolvedValueOnce({
    ...noDoor, status: 'STALE', lastObservedOpen: false, lastObservedAt: observedAt,
  }).mockResolvedValueOnce({ ...noDoor, status: 'AVAILABLE', doorOpen: false, lastObservedOpen: false, lastObservedAt: observedAt })
  const wrapper = mountPage()
  try {
    await flushPromises()
    expect(wrapper.get('.comfort-radar-note').text()).toMatch(/离线.*已过期.*上次 2 人.*08:31:00/)
    expect(wrapper.get('.door-observation-note').text()).toMatch(/已过期.*上次 门关.*08:31:00/)
    expect(wrapper.get('.comfort-indoor-note').text()).toMatch(/已过期.*上次.*08:31:00/)
    expect(wrapper.get('.comfort-temperature').text()).toBe('24.3°C')
    expect(wrapper.get('.comfort-people-now').text()).toBe('人数未知')
    expect(wrapper.get('.door-hero').attributes('data-door-state')).toBe('unknown')
    expect(wrapper.get('.comfort-room-scene').attributes('data-door-state')).toBe('unknown')

    await wrapper.findAll('button').find(button => button.text() === '刷新')?.trigger('click')
    await flushPromises()
    expect(wrapper.find('.comfort-observation-note').exists()).toBe(false)
    expect(wrapper.get('.comfort-people-now').text()).toBe('当前 0 人')
    expect(wrapper.get('.door-hero').attributes('data-door-state')).toBe('closed')

    await wrapper.get('select[aria-label="房间"]').setValue('r313')
    await flushPromises()
    expect(wrapper.get('.comfort-temperature').text()).toBe('—')
    expect(wrapper.find('.comfort-radar-note').exists()).toBe(false)
    expect(wrapper.find('.door-observation-note').exists()).toBe(false)
    expect(wrapper.get('.door-hero').attributes('data-door-state')).toBe('unknown')
  } finally { wrapper.unmount() }
})

it('retains same-room observations with refresh failure notices until a successful reply', async () => {
  vi.mocked(comfortApi.occupancy).mockResolvedValue({
    ...noOccupancy, status: 'AVAILABLE', currentCount: 1, lastObservedCount: 1, lastObservedAt: observedAt,
  })
  vi.mocked(comfortApi.doorStats).mockResolvedValue({
    ...noDoor, status: 'AVAILABLE', doorOpen: true, lastObservedOpen: true, lastObservedAt: observedAt,
  })
  const wrapper = mountPage()
  const refresh = () => wrapper.findAll('button').find(button => button.text() === '刷新')?.trigger('click')
  try {
    await flushPromises()
    vi.mocked(comfortApi.overview).mockRejectedValueOnce(new Error('network'))
    vi.mocked(comfortApi.occupancy).mockRejectedValueOnce(new Error('network'))
    vi.mocked(comfortApi.doorStats).mockRejectedValueOnce(new Error('network'))
    await refresh()
    await flushPromises()
    expect(wrapper.get('.comfort-alarm-strip').text()).toContain('刷新失败')
    expect(wrapper.get('.comfort-radar-note').text()).toMatch(/刷新失败.*上次 1 人.*08:31:00/)
    expect(wrapper.get('.door-observation-note').text()).toMatch(/刷新失败.*上次 门开.*08:31:00/)
    expect(wrapper.get('.comfort-indoor-note').text()).toMatch(/刷新失败.*08:31:00/)
    expect(wrapper.get('.comfort-temperature').text()).toBe('24.3°C')

    let reply: (value: ComfortOverview) => void = () => undefined
    vi.mocked(comfortApi.overview).mockImplementationOnce(() => new Promise(resolve => { reply = resolve }))
    await refresh()
    await flushPromises()
    expect(wrapper.get('.comfort-alarm-strip').text()).toContain('刷新失败')
    expect(wrapper.get('.comfort-indoor-note').text()).toContain('刷新失败')
    reply(snapshot(room, 24.5))
    await flushPromises()
    expect(wrapper.get('.comfort-alarm-strip').text()).not.toContain('刷新失败')
    expect(wrapper.find('.comfort-indoor-note').exists()).toBe(false)
    expect(wrapper.get('.comfort-temperature').text()).toBe('24.5°C')
  } finally { wrapper.unmount() }
})
