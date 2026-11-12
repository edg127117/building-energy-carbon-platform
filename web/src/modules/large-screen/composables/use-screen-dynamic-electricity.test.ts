import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref, type Ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  type DynamicElectricityPoint,
  type DynamicElectricityPointStatus,
  type DynamicElectricityView,
  readDynamicElectricity,
} from '@/modules/energy-classification/public'
import type { ScreenPeriod } from '../models/screen-period'
import { useScreenDynamicElectricity, validateScreenDynamicElectricityView } from './use-screen-dynamic-electricity'

vi.mock('@/modules/energy-classification/public', async original => ({
  ...await original<typeof import('@/modules/energy-classification/public')>(),
  readDynamicElectricity: vi.fn(),
}))

const YEAR: ScreenPeriod = { kind: 'year', value: '2026' }

function isoAtShanghaiMidnight(value: string): string {
  return new Date(Date.parse(`${value}T00:00:00+08:00`)).toISOString()
}

function yearPoint(month: number, status: DynamicElectricityPointStatus = 'COMPLETE', overrides: Partial<DynamicElectricityPoint> = {}): DynamicElectricityPoint {
  const period = `2026-${String(month).padStart(2, '0')}`
  const next = month === 12 ? '2027-01' : `2026-${String(month + 1).padStart(2, '0')}`
  const complete = status === 'COMPLETE'
  return {
    period, startInclusive: isoAtShanghaiMidnight(`${period}-01`), endExclusive: isoAtShanghaiMidnight(`${next}-01`),
    computedThrough: complete ? isoAtShanghaiMidnight(`${next}-01`) : null,
    status, reasonCode: complete ? null : 'COMPLETE_RESULT_REQUIRED',
    electricityKwh: complete ? String(month === 1 ? 0 : 100) : null,
    emissionTco2: complete ? String(month === 1 ? 0 : month / 10) : null,
    factorKgCo2PerKwh: complete && month !== 1 ? '0.5' : null,
    grains: complete ? ['DAY'] : [], resultIds: complete ? [`R-${month}`] : [], sourceNatures: complete ? ['SOURCE_LABELLED'] : [],
    ...overrides,
  }
}

function yearView(buildingId = 'B-1', points = Array.from({ length: 12 }, (_, index) => yearPoint(index + 1))): DynamicElectricityView {
  return {
    buildingId, periodType: 'YEAR', period: '2026', timezoneId: 'Asia/Shanghai',
    emissionUnit: 'tCO2', factorUnit: 'kgCO2/kWh', resultNature: 'MONITORING', points,
  }
}

function monthView(month: string, buildingId = 'B-1'): DynamicElectricityView {
  const [year, value] = month.split('-').map(Number)
  const days = new Date(Date.UTC(year, value, 0)).getUTCDate()
  return {
    buildingId, periodType: 'MONTH', period: month, timezoneId: 'Asia/Shanghai',
    emissionUnit: 'tCO2', factorUnit: 'kgCO2/kWh', resultNature: 'MONITORING',
    points: Array.from({ length: days }, (_, index) => {
      const date = `${month}-${String(index + 1).padStart(2, '0')}`
      const complete = index === 0
      return {
        period: date,
        startInclusive: isoAtShanghaiMidnight(date),
        endExclusive: new Date(Date.parse(`${date}T00:00:00+08:00`) + 24 * 60 * 60 * 1_000).toISOString(),
        computedThrough: complete
          ? new Date(Date.parse(`${date}T00:00:00+08:00`) + 24 * 60 * 60 * 1_000).toISOString()
          : null,
        status: complete ? 'COMPLETE' : index === 1 ? 'PARTIAL' : 'NOT_ENDED',
        reasonCode: complete ? null : 'COMPLETE_RESULT_REQUIRED',
        electricityKwh: complete ? '0' : null,
        emissionTco2: complete ? '0' : null,
        factorKgCo2PerKwh: null,
        grains: complete ? ['DAY'] : [], resultIds: complete ? ['R-1'] : [], sourceNatures: complete ? ['SIMULATED'] : [],
      }
    }),
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((nextResolve, nextReject) => { resolve = nextResolve; reject = nextReject })
  return { promise, resolve, reject }
}

let buildingId: Ref<string>
let period: Ref<ScreenPeriod>
let dynamic: ReturnType<typeof useScreenDynamicElectricity>

function mountDynamic() {
  return mount(defineComponent({
    setup() {
      dynamic = useScreenDynamicElectricity(buildingId, period)
      return () => h('div')
    },
  }))
}

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
}

async function settleMicrotasks(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

describe('useScreenDynamicElectricity', () => {
  afterEach(() => vi.useRealTimers())

  beforeEach(() => {
    vi.resetAllMocks()
    buildingId = ref('B-1')
    period = ref({ ...YEAR })
  })

  it('按后端槽位顺序保留 CO2 数值、零值和来源性质，不重算或补缺', async () => {
    const points = Array.from({ length: 12 }, (_, index) => yearPoint(index + 1))
    points[1] = yearPoint(2, 'PARTIAL')
    points[2] = yearPoint(3, 'COMPLETE', { sourceNatures: ['SIMULATED'] })
    vi.mocked(readDynamicElectricity).mockResolvedValue(yearView('B-1', points))

    const wrapper = mountDynamic()
    await settle()

    expect(readDynamicElectricity).toHaveBeenCalledWith('B-1', 'YEAR', '2026', expect.any(AbortSignal), expect.any(Function))
    expect(dynamic.data.value).toMatchObject({ period: YEAR, emissionUnit: 'tCO₂', factorUnit: 'kgCO₂/kWh', sourceNatures: ['SOURCE_LABELLED', 'SIMULATED'], hasSimulatedSource: true })
    expect(dynamic.data.value?.dynamicEmissions.slice(0, 3)).toEqual([0, null, 0.3])
    expect(dynamic.data.value?.dynamicFactors.slice(0, 3)).toEqual([null, null, 0.5])
    expect(dynamic.status.value).toBe('partial')
    wrapper.unmount()
  })

  it('校验闰月日槽位，PARTIAL 和 NOT_ENDED 均不映射为数值', () => {
    const month: ScreenPeriod = { kind: 'month', value: '2024-02' }
    const validated = validateScreenDynamicElectricityView(monthView('2024-02'), 'B-1', month)

    expect(validated?.dynamicEmissions).toHaveLength(29)
    expect(validated?.dynamicEmissions.slice(0, 3)).toEqual([0, null, null])
    expect(validated?.dynamicFactors.slice(0, 3)).toEqual([null, null, null])
    expect(validated?.hasSimulatedSource).toBe(true)
  })

  it('日模式不调用旧年/月动态电量查询', async () => {
    period.value = { kind: 'day', value: '2026-09-22' }
    const wrapper = mountDynamic()
    await settle()
    expect(readDynamicElectricity).not.toHaveBeenCalled()
    expect(dynamic.status.value).toBe('unavailable')
    wrapper.unmount()
  })

  it('只显示当前开放自然桶的 ACCUMULATING 完整前缀，并校验 computedThrough 在桶内', () => {
    vi.setSystemTime(new Date('2026-09-15T04:00:00.000Z'))
    const month: ScreenPeriod = { kind: 'month', value: '2026-09' }
    const view = monthView('2026-09')
    view.points[14] = {
      ...view.points[14]!, status: 'ACCUMULATING', reasonCode: null,
      electricityKwh: '125.5', emissionTco2: '0.06275', factorKgCo2PerKwh: '0.5',
      computedThrough: '2026-09-15T03:00:00.000Z',
    }

    const validated = validateScreenDynamicElectricityView(view, 'B-1', month)
    expect(validated?.dynamicEmissions[14]).toBe(0.06275)
    expect(validated?.dynamicFactors[14]).toBe(0.5)
    expect(validated?.status).toBe('partial')

    view.points[14]!.computedThrough = '2026-09-16T03:00:00.000Z'
    expect(validateScreenDynamicElectricityView(view, 'B-1', month)).toBeNull()
    view.points[14]!.computedThrough = '2026-09-14T16:00:00.000Z'
    expect(validateScreenDynamicElectricityView(view, 'B-1', month)).toBeNull()
    view.points[14]!.computedThrough = '2026-09-15T16:00:00.000Z'
    expect(validateScreenDynamicElectricityView(view, 'B-1', month)).toBeNull()
    view.points[14]!.computedThrough = null
    expect(validateScreenDynamicElectricityView(view, 'B-1', month)?.dynamicEmissions[14]).toBe(0.06275)
    view.points[13] = { ...view.points[13]!, status: 'ACCUMULATING', electricityKwh: '1', emissionTco2: '1', factorKgCo2PerKwh: '1' }
    expect(validateScreenDynamicElectricityView(view, 'B-1', month)).toBeNull()
  })

  it('兼容后端完整槽位截止点等于 endExclusive，以及非累计状态的 null 截止点', () => {
    const points = Array.from({ length: 12 }, (_, index) => yearPoint(index + 1))
    points[0] = yearPoint(1, 'COMPLETE', { computedThrough: isoAtShanghaiMidnight('2026-02-01') })
    points[1] = yearPoint(2, 'PARTIAL', { computedThrough: null })

    const validated = validateScreenDynamicElectricityView(yearView('B-1', points), 'B-1', YEAR)

    expect(validated?.dynamicEmissions.slice(0, 2)).toEqual([0, null])
  })

  it('接受 BigDecimal 科学计数法的零量和小量，只投影为有限数值', () => {
    const points = Array.from({ length: 12 }, (_, index) => yearPoint(index + 1))
    points[0] = yearPoint(1, 'COMPLETE', { electricityKwh: '0E-34', emissionTco2: '0E-34', factorKgCo2PerKwh: null })
    points[1] = yearPoint(2, 'COMPLETE', { electricityKwh: '1000', emissionTco2: '5E-8', factorKgCo2PerKwh: '5E-8' })

    const validated = validateScreenDynamicElectricityView(yearView('B-1', points), 'B-1', YEAR)

    expect(validated?.dynamicEmissions.slice(0, 2)).toEqual([0, 5e-8])
    expect(validated?.dynamicFactors.slice(0, 2)).toEqual([null, 5e-8])
  })

  it('拒绝错单位或错槽位响应，避免展示不可信的动态数据', async () => {
    const invalid = yearView()
    invalid.factorUnit = 'kgCO2e/kWh' as never
    vi.mocked(readDynamicElectricity).mockResolvedValue(invalid)

    const wrapper = mountDynamic()
    await settle()

    expect(dynamic.data.value).toBeNull()
    expect(dynamic.status.value).toBe('error')
    wrapper.unmount()
  })

  it('切换建筑或期间会中止旧请求、清空旧值并拒绝迟到响应', async () => {
    const first = deferred<DynamicElectricityView>()
    const second = deferred<DynamicElectricityView>()
    const third = deferred<DynamicElectricityView>()
    vi.mocked(readDynamicElectricity)
      .mockImplementationOnce(() => first.promise)
      .mockImplementationOnce(() => second.promise)
      .mockImplementationOnce(() => third.promise)
    const wrapper = mountDynamic()
    const firstSignal = vi.mocked(readDynamicElectricity).mock.calls[0]?.[3] as AbortSignal

    buildingId.value = 'B-2'
    expect(firstSignal.aborted).toBe(true)
    expect(dynamic.data.value).toBeNull()
    period.value = { kind: 'month', value: '2026-09' }
    const secondSignal = vi.mocked(readDynamicElectricity).mock.calls[1]?.[3] as AbortSignal
    expect(secondSignal.aborted).toBe(true)
    expect(dynamic.data.value).toBeNull()
    third.resolve(monthView('2026-09', 'B-2'))
    await settle()
    first.resolve(yearView('B-1'))
    second.resolve(yearView('B-2'))
    await settle()
    expect(dynamic.data.value?.period).toEqual({ kind: 'month', value: '2026-09' })
    expect(dynamic.data.value?.dynamicEmissions[0]).toBe(0)
    wrapper.unmount()
  })

  it('将请求失败保留为空并允许同一建筑期间重新读取', async () => {
    vi.mocked(readDynamicElectricity).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(yearView())
    const wrapper = mountDynamic()
    await settle()
    expect(dynamic.data.value).toBeNull()
    expect(dynamic.status.value).toBe('error')
    await dynamic.reload()
    expect(dynamic.status.value).toBe('ready')
    expect(dynamic.data.value?.dynamicEmissions[0]).toBe(0)
    wrapper.unmount()
  })

  it('每轮结束五秒后才刷新，后台保留当前值且失败后清空', async () => {
    vi.useFakeTimers()
    const second = deferred<DynamicElectricityView>()
    vi.mocked(readDynamicElectricity).mockResolvedValueOnce(yearView()).mockReturnValueOnce(second.promise)
    const wrapper = mountDynamic()
    await settleMicrotasks()
    expect(dynamic.data.value?.dynamicEmissions[0]).toBe(0)

    await vi.advanceTimersByTimeAsync(4_999)
    expect(readDynamicElectricity).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(readDynamicElectricity).toHaveBeenCalledTimes(2)
    expect(dynamic.data.value?.dynamicEmissions[0]).toBe(0)
    expect(dynamic.status.value).toBe('ready')

    await vi.advanceTimersByTimeAsync(10_000)
    expect(readDynamicElectricity).toHaveBeenCalledTimes(2)
    second.reject(new Error('offline'))
    await settleMicrotasks()
    expect(dynamic.data.value).toBeNull()
    expect(dynamic.status.value).toBe('error')
    wrapper.unmount()
  })

  it('后台刷新进入服务端准备态时立即清空旧值并恢复加载态', async () => {
    const second = deferred<DynamicElectricityView>()
    vi.mocked(readDynamicElectricity)
      .mockResolvedValueOnce(yearView())
      .mockImplementationOnce(async (_building, _type, _period, _signal, onPreparing) => {
        onPreparing?.()
        return second.promise
      })
    const wrapper = mountDynamic()
    await settle()
    expect(dynamic.data.value).not.toBeNull()

    const refresh = dynamic.reload()
    await settleMicrotasks()
    expect(dynamic.data.value).toBeNull()
    expect(dynamic.status.value).toBe('loading')

    second.resolve(yearView())
    await refresh
    expect(dynamic.data.value).not.toBeNull()
    expect(dynamic.status.value).toBe('ready')
    wrapper.unmount()
  })

  it('卸载时停止待执行的自动刷新', async () => {
    vi.useFakeTimers()
    vi.mocked(readDynamicElectricity).mockImplementation(async requestedBuildingId => yearView(requestedBuildingId))
    const wrapper = mountDynamic()
    await settleMicrotasks()
    wrapper.unmount()

    await vi.advanceTimersByTimeAsync(10_000)
    expect(readDynamicElectricity).toHaveBeenCalledTimes(1)
  })

  it('切换建筑后撤销旧计时并按新请求完成时间启动刷新', async () => {
    vi.useFakeTimers()
    vi.mocked(readDynamicElectricity).mockImplementation(async requestedBuildingId => yearView(requestedBuildingId))
    const wrapper = mountDynamic()
    await settleMicrotasks()
    expect(readDynamicElectricity).toHaveBeenCalledTimes(1)

    buildingId.value = 'B-2'
    await settleMicrotasks()
    expect(readDynamicElectricity).toHaveBeenCalledTimes(2)
    expect(dynamic.data.value?.period).toEqual(YEAR)

    await vi.advanceTimersByTimeAsync(4_999)
    expect(readDynamicElectricity).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(readDynamicElectricity).toHaveBeenCalledTimes(3)
    expect(readDynamicElectricity).toHaveBeenLastCalledWith('B-2', 'YEAR', '2026', expect.any(AbortSignal), expect.any(Function))
    wrapper.unmount()
  })
})
