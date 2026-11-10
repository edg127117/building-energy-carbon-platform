import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref, type Ref } from 'vue'
import type { HourlyCarbonPoint, HourlyCarbonView } from '@/modules/energy-classification/public'
import { useScreenHourlyCarbon } from './use-screen-hourly-carbon'

const { getHourly } = vi.hoisted(() => ({ getHourly: vi.fn() }))

vi.mock('@/modules/energy-classification/public', () => ({
  screenHourlyCarbonQueries: { getHourly },
  messages: {},
}))

const day = '2026-10-27'
const start = Date.parse(`${day}T00:00:00+08:00`)

function atCompletedHours(hours: number): string {
  return new Date(start + hours * 60 * 60 * 1_000).toISOString()
}

function point(hour: number, status: HourlyCarbonPoint['status'], emissionKgCo2e: string | null = null): HourlyCarbonPoint {
  return {
    hour,
    startInclusive: atCompletedHours(hour),
    endExclusive: atCompletedHours(hour + 1),
    status,
    reasonCode: null,
    reason: null,
    electricityKwh: null,
    emissionKgCo2e,
    sourceSnapshotIds: [],
    factorVersionIds: [],
    formulaVersionIds: [],
    gwpVersionIds: [],
    roundingPolicyVersionIds: ['ROUND-HOURLY-1'],
    qualityPolicyVersions: [],
    projectVersionIds: [],
    relationVersionIds: [],
    gasCoverages: [],
    evidenceHash: null,
  }
}

function view(overrides: Partial<HourlyCarbonView> = {}): HourlyCarbonView {
  const points = Array.from({ length: 24 }, (_, hour) => point(hour, 'COMPLETE', hour === 0 ? '0' : '1'))
  return {
    buildingId: 'B-1', date: day, timezoneId: 'Asia/Shanghai', unit: 'KG_CO2E', scope: 'PURCHASED_ELECTRICITY_SCOPE_2',
    sourceKind: 'RAW_CUMULATIVE', declaredSourceNature: 'SOURCE_LABELLED', factorUsageNature: 'FORMAL', resultNature: 'MONITORING',
    state: 'COMPLETE', calculationBatchId: 'HOUR-BATCH-1', revision: 2, calculationAsOf: atCompletedHours(24), dueHours: 24,
    activityWatermark: null, activityWatermarkMeaning: null, availableHours: 24, dailyTotal: '23', points,
    capturedAt: atCompletedHours(24), evidenceHash: 'VIEW-EVIDENCE-1', ...overrides,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(next => { resolve = next })
  return { promise, resolve }
}

let buildingId: Ref<string>
let selectedDay: Ref<string>
let hourlyCarbon!: ReturnType<typeof useScreenHourlyCarbon>

function mountHourlyCarbon() {
  return mount(defineComponent({
    setup() {
      hourlyCarbon = useScreenHourlyCarbon(buildingId, selectedDay)
      return () => h('div')
    },
  }))
}

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
}

describe('useScreenHourlyCarbon', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    buildingId = ref('B-1')
    selectedDay = ref(day)
  })

  afterEach(() => vi.restoreAllMocks())

  it('逐点保留 0，缺测和未到期均留空，不插值或相加', async () => {
    const points = Array.from({ length: 24 }, (_, hour) => point(
      hour,
      hour === 0 ? 'COMPLETE' : hour === 23 ? 'NOT_DUE' : 'INCOMPLETE',
      hour === 0 ? '0' : '99',
    ))
    getHourly.mockResolvedValue(view({
      state: 'PARTIAL', declaredSourceNature: 'SIMULATED', calculationAsOf: atCompletedHours(23),
      dueHours: 23, availableHours: 1, dailyTotal: '0', points,
    }))

    const wrapper = mountHourlyCarbon()
    await settle()

    expect(getHourly).toHaveBeenCalledWith('B-1', day, expect.anything())
    expect(hourlyCarbon.periods.value).toEqual(Array.from({ length: 24 }, (_, hour) => `${String(hour).padStart(2, '0')}:00`))
    expect(hourlyCarbon.series.value).toEqual([{
      name: '碳排放',
      values: [0, ...Array.from({ length: 23 }, () => null)],
    }])
    expect(hourlyCarbon.status.value).toBe('partial')
    expect(hourlyCarbon.evidence.value).toEqual({
      calculationBatchId: 'HOUR-BATCH-1', revision: 2, declaredSourceNature: 'SIMULATED', state: 'PARTIAL',
    })
    wrapper.unmount()
  })

  it('未生成结果只接受 MISSING/NOT_DUE 点，并把整条序列保留为 null', async () => {
    const points = Array.from({ length: 24 }, (_, hour) => point(hour, hour < 10 ? 'MISSING' : 'NOT_DUE'))
    getHourly.mockResolvedValue(view({
      state: 'MISSING', calculationBatchId: null, revision: null, calculationAsOf: new Date(start + (10 * 60 + 30) * 60 * 1_000).toISOString(),
      sourceKind: null, declaredSourceNature: null, factorUsageNature: null,
      dueHours: 10, availableHours: 0, dailyTotal: null, points,
    }))

    const wrapper = mountHourlyCarbon()
    await settle()

    expect(hourlyCarbon.status.value).toBe('missing')
    expect(hourlyCarbon.series.value[0]?.values).toEqual(Array.from({ length: 24 }, () => null))
    expect(hourlyCarbon.evidence.value?.declaredSourceNature).toBeNull()
    wrapper.unmount()
  })

  it.each([
    ['越界小时窗口', () => {
      const valid = view()
      const points = [...valid.points]
      points[2] = { ...points[2], endExclusive: atCompletedHours(4) }
      return { ...valid, points }
    }],
    ['重复小时', () => {
      const valid = view()
      const points = [...valid.points]
      points[23] = { ...points[22] }
      return { ...valid, points }
    }],
    ['错建筑', () => view({ buildingId: 'B-2' })],
    ['错误单位', () => view({ unit: 'T_CO2E' as never })],
    ['错范围', () => view({ scope: 'SCOPE_1' as never })],
    ['已有结果无来源声明', () => view({ declaredSourceNature: null })],
    ['截止后的整点', () => {
      const points = Array.from({ length: 24 }, (_, hour) => point(hour, hour <= 10 ? 'COMPLETE' : 'NOT_DUE', '1'))
      return view({
        state: 'PARTIAL', calculationAsOf: new Date(start + (10 * 60 + 30) * 60 * 1_000).toISOString(),
        dueHours: 10, availableHours: 11, points,
      })
    }],
  ])('拒绝%s，不展示不可信小时数据', async (_, invalidView) => {
    getHourly.mockResolvedValue(invalidView())
    const wrapper = mountHourlyCarbon()
    await settle()

    expect(hourlyCarbon.status.value).toBe('error')
    expect(hourlyCarbon.series.value).toEqual([])
    expect(hourlyCarbon.evidence.value).toBeNull()
    wrapper.unmount()
  })

  it('切换建筑立即清空并取消旧请求，迟到响应和卸载都不能写回', async () => {
    const first = deferred<HourlyCarbonView>()
    const second = deferred<HourlyCarbonView>()
    getHourly.mockImplementation((building: string) => building === 'B-1' ? first.promise : second.promise)
    const wrapper = mountHourlyCarbon()
    const firstSignal = getHourly.mock.calls[0][2] as AbortSignal

    buildingId.value = 'B-2'
    expect(firstSignal.aborted).toBe(true)
    expect(hourlyCarbon.series.value).toEqual([])
    expect(hourlyCarbon.status.value).toBe('loading')

    second.resolve(view({ buildingId: 'B-2', calculationBatchId: 'HOUR-BATCH-2', points: Array.from({ length: 24 }, (_, hour) => point(hour, 'COMPLETE', hour === 0 ? '7' : '1')) }))
    await settle()
    first.resolve(view({ points: Array.from({ length: 24 }, (_, hour) => point(hour, 'COMPLETE', hour === 0 ? '99' : '1')) }))
    await settle()

    expect(hourlyCarbon.series.value[0]?.values[0]).toBe(7)
    expect(hourlyCarbon.evidence.value?.calculationBatchId).toBe('HOUR-BATCH-2')
    wrapper.unmount()

    const pending = deferred<HourlyCarbonView>()
    getHourly.mockImplementation(() => pending.promise)
    const active = mountHourlyCarbon()
    const activeSignal = getHourly.mock.calls[2][2] as AbortSignal
    active.unmount()
    expect(activeSignal.aborted).toBe(true)
  })
})
