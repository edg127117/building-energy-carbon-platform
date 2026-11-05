import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import FinalBPage from './FinalBPage.vue'
import IndexPage from './IndexPage.vue'
import { readCarbonIntensity, type CarbonIntensityView } from '../../api/carbon-intensity'
import { intensityView } from '../../models/carbon-intensity.test-fixture'
import type { ScreenPeriods, ScreenRedesignData } from '../../models/screen-redesign'
import type { ScreenDynamicElectricityData, ScreenDynamicElectricityStatus } from '../../composables/use-screen-dynamic-electricity'
import { carbonPlanApi, type CarbonPlanResponse } from '@/modules/energy-classification/public'
import { readAnnualOverview, readDailyTrends } from '@/modules/energy-reporting/public'
import { annualFixture } from '../../models/final-b-annual.test-fixture'
import { dailyFixture } from '../../models/final-b-daily.test-fixture'

vi.mock('./IndexPage.vue', () => ({ default: {
  name: 'BScreenContent', props: ['data', 'layoutVariant'], emits: ['periodChange'], template: '<div><slot name="data-context" /></div>',
} }))
vi.mock('../../api/carbon-intensity', () => ({ readCarbonIntensity: vi.fn() }))
vi.mock('@/modules/energy-reporting/api/annual-overview', async original => ({
  ...await original<typeof import('@/modules/energy-reporting/api/annual-overview')>(), readAnnualOverview: vi.fn(),
}))
vi.mock('@/modules/energy-reporting/api/daily-trends', () => ({ readDailyTrends: vi.fn() }))
vi.mock('@/modules/energy-classification/public', async original => ({
  ...await original<typeof import('@/modules/energy-classification/public')>(), carbonPlanApi: { query: vi.fn() },
}))
vi.mock('../../composables/use-final-b-data', () => ({ useFinalBData: () => state }))
vi.mock('../../composables/use-screen-dynamic-electricity', () => ({ useScreenDynamicElectricity: () => dynamic }))
vi.mock('../../composables/use-joint-flow', () => ({ useJointFlow: () => ({
  data: ref<ScreenRedesignData>({ carbonFlow: { period: { kind: 'year', value: '2026' }, data: {
    total: 104, unit: 'tCO2e', nodes: [], links: [],
  } } }), state: ref('READY'), view: ref(null), loading: ref(false), reload: vi.fn(),
}) }))
vi.mock('../../composables/use-outdoor-temperature', () => ({ useOutdoorTemperature: () => ({
  values: ref(Array.from({ length: 12 }, () => 20)), state: ref('READY'), view: ref(null), loading: ref(false), reload: vi.fn(),
}) }))
vi.mock('../../composables/use-building-monitoring', () => ({ useBuildingMonitoring: () => ({
  devices: ref({ total: 4, on: 2, off: 1, unknown: 1, modes: [], speeds: [] }), updatedAt: ref(123),
  summaryLastSuccess: ref(123), summaryExpired: ref(false), summaryFailed: ref(false),
  summaryDiagnostics: ref({ modeExtras: [['dry', 1]], speedExtras: [['middleHigh', 1]],
    unknownReasons: [['MISSING', 1]], modeReasons: [], speedReasons: [] }),
  runtimeRanking: ref([]), rankingContext: ref(undefined), rankingFailed: ref(false),
}) }))

function initialState() {
  const period = { kind: 'year' as const, value: '2026' }
  return {
    buildingId: ref('B'), buildings: ref([{ buildingId: 'B', buildingName: '测试建筑' }]),
    periods: ref<ScreenPeriods>({ composition: period, flow: period, plan: period, mirror: period, usage: period, intensity: period }),
    data: ref<ScreenRedesignData>({ annual: { year: '2026', carbon: 104, intensity: 5.2 },
      usage: { period, data: { electricity: Array.from({ length: 12 }, () => 100), temperature: Array.from({ length: 12 }, () => null),
        electricityUnit: 'kWh', temperatureUnit: '°C' } } }),
    reports: ref(new Map()), failedMonths: ref<string[]>([]), buildingError: ref(false), loading: ref(false),
    reload: vi.fn(), loadBuildings: vi.fn(),
  }
}
let state: ReturnType<typeof initialState>
function initialDynamic() {
  return {
    data: ref<ScreenDynamicElectricityData | null>(null), status: ref<ScreenDynamicElectricityStatus>('unavailable'),
    loading: ref(false), failed: ref(false), reload: vi.fn(),
  }
}
let dynamic: ReturnType<typeof initialDynamic>

beforeEach(() => {
  dynamic = initialDynamic()
  vi.mocked(readDailyTrends).mockReset()
  vi.mocked(readDailyTrends).mockImplementation(async (building, month) => dailyFixture(building, month))
  vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-10-31T10:00:00+08:00'))
  vi.mocked(readAnnualOverview).mockReset()
  vi.mocked(readAnnualOverview).mockImplementation(async query => annualFixture(query))
})
afterEach(() => vi.useRealTimers())

describe('final B intensity integration', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-30T10:00:00+08:00'))
    state = initialState()
    vi.mocked(readCarbonIntensity).mockReset()
    vi.mocked(carbonPlanApi.query).mockReset()
    vi.mocked(carbonPlanApi.query).mockResolvedValue(planResponse())
  })
  afterEach(() => vi.useRealTimers())

  it('connects both month cards to one daily query and retains only the authorized demo series', async () => {
    vi.mocked(readCarbonIntensity).mockResolvedValue(intensityView())
    const wrapper = mount(FinalBPage), page = wrapper.findComponent(IndexPage)
    const period = { kind: 'month' as const, value: '2024-02' }
    page.vm.$emit('periodChange', { ...state.periods.value, usage: period, mirror: period })
    await flushPromises()
    const data = page.props('data') as ScreenRedesignData
    expect(readDailyTrends).toHaveBeenCalledTimes(1)
    expect(data.usage?.period).toEqual(period)
    expect(data.usage?.data.electricity.slice(0, 2)).toEqual([0, 123.456])
    expect(data.mirror?.data.staticEmissions).toEqual(Array(29).fill(0.00123456789))
    expect(data.mirror?.data.staticFactors).toEqual(Array(29).fill(0.56))
    expect(data.mirror?.data.dynamicFactors).toEqual(Array(29).fill(null))
    expect(data.devices?.on).toBe(2)
    expect(wrapper.text()).toContain('除湿模式 1')
    expect(wrapper.text()).toContain('中高档 1')
    expect(wrapper.text()).toContain('缺少观测 1')
    expect(data.deviceSummaryUpdatedAt).toBe(123)
    wrapper.unmount()
  })

  it('selects the closed-month YTD contract and keeps annual, flow, electricity and temperature data intact', async () => {
    vi.mocked(readCarbonIntensity).mockResolvedValue(intensityView({ mode: 'MONTHLY_YTD', start: '2026-01-01', endExclusive: '2026-10-01',
      comparisonStatus: 'NOT_COMPARABLE', reasons: ['YTD_NOT_ANNUAL_COMPARISON'], percentage: null,
      actual: { ...intensityView().actual, value: '5.2', batchId: null, evidenceHash: 'annual-overview-hash' } }))
    const wrapper = mount(FinalBPage)
    await flushPromises()
    expect(readCarbonIntensity).toHaveBeenCalledWith({ buildingId: 'B', mode: 'MONTHLY_YTD', year: 2026, month: 9, timezoneId: 'Asia/Shanghai' }, expect.any(AbortSignal))
    const data = wrapper.findComponent(IndexPage).props('data') as ScreenRedesignData
    expect(wrapper.findComponent(IndexPage).props('layoutVariant')).toBe('b')
    expect(data.intensity?.data).toMatchObject({ areaValue: 5.2, areaPercentage: null, personValue: null })
    expect(data.intensityBenchmarks).toMatchObject({ mode: 'MONTHLY_YTD', throughMonth: 9, sourceName: 'source' })
    expect(data.annual).toMatchObject({ year: '2026', throughMonth: 9, carbon: 129.456789, intensity: 6.472839 })
    expect(readAnnualOverview).toHaveBeenCalledWith({ buildingId: 'B', year: 2026, mode: 'MONTHLY_YTD', throughMonth: 9, timezoneId: 'Asia/Shanghai' }, expect.any(AbortSignal))
    expect(data.composition?.data).toMatchObject({ total: 129.456789, yearOverYear: -7.25, throughMonth: 9 })
    expect(data.carbonFlow?.data.total).toBe(104)
    expect(data.usage?.data.electricity).toEqual(Array.from({ length: 12 }, () => 100))
    expect(data.usage?.data.temperature).toEqual(Array.from({ length: 12 }, () => 20))
    wrapper.unmount()
  })

  it('clears the old period before a response arrives and rejects the late YTD response after selecting a month', async () => {
    const pending: Array<(view: CarbonIntensityView) => void> = []
    vi.mocked(readCarbonIntensity).mockImplementation(() => new Promise(resolve => pending.push(resolve)))
    const wrapper = mount(FinalBPage)
    wrapper.findComponent(IndexPage).vm.$emit('periodChange', { ...state.periods.value, intensity: { kind: 'month', value: '2026-02' } })
    await flushPromises()
    expect(wrapper.findComponent(IndexPage).props('data').intensity).toBeUndefined()
    expect(readCarbonIntensity).toHaveBeenLastCalledWith({ buildingId: 'B', mode: 'MONTH', year: 2026, month: 2, timezoneId: 'Asia/Shanghai' }, expect.any(AbortSignal))
    pending[1](intensityView({ mode: 'MONTH', start: '2026-02-01', endExclusive: '2026-03-01',
      comparisonStatus: 'NOT_COMPARABLE', reasons: ['MONTH_NOT_ANNUAL_COMPARISON'], percentage: null,
      actual: { ...intensityView().actual, value: '0' } }))
    await flushPromises()
    expect(wrapper.findComponent(IndexPage).props('data').intensity.data).toMatchObject({ areaValue: 0, areaPercentage: null })
    pending[0](intensityView({ mode: 'MONTHLY_YTD', start: '2026-01-01', endExclusive: '2026-10-01' }))
    await flushPromises()
    expect(wrapper.findComponent(IndexPage).props('data').intensity.period).toEqual({ kind: 'month', value: '2026-02' })
    expect(wrapper.findComponent(IndexPage).props('data').intensity.data.areaValue).toBe(0)
    wrapper.unmount()
  })

  it('shows the automatic YTD reference ratio even when the standard date and formal comparison remain unresolved', async () => {
    vi.mocked(readCarbonIntensity).mockResolvedValue(intensityView({ mode: 'MONTHLY_YTD', start: '2026-01-01', endExclusive: '2026-10-01',
      matchStatus: 'APPLICABILITY_TIME_UNCLEAR', comparisonStatus: 'NOT_COMPARABLE', reasons: ['APPLICABILITY_TIME_UNCLEAR', 'YTD_NOT_ANNUAL_COMPARISON'],
      percentage: '9.169458', percentageStatus: 'AVAILABLE', percentageBasis: 'YTD_SHARE_OF_ANNUAL_REFERENCE', percentageReasons: [],
      actual: { ...intensityView().actual, value: '5.2', gasBasis: 'CO2_ONLY' } }))
    const wrapper = mount(FinalBPage)
    await flushPromises()
    const data = wrapper.findComponent(IndexPage).props('data') as ScreenRedesignData
    expect(data.intensity?.data).toMatchObject({ areaValue: 5.2, areaPercentage: 9.169458 })
    expect(data.intensityBenchmarks).toMatchObject({ percentageBasis: 'YTD_SHARE_OF_ANNUAL_REFERENCE', comparisonStatus: 'NOT_COMPARABLE' })
    wrapper.unmount()
  })

  it('selects twelve-month history for composition while keeping the current top values and monthly composition path', async () => {
    vi.mocked(readCarbonIntensity).mockResolvedValue(intensityView())
    vi.mocked(readAnnualOverview).mockImplementation(async query => {
      const result = annualFixture(query)
      if (query.year === 2025) result.current.metrics[0].value = '222.000000'
      return result
    })
    const wrapper = mount(FinalBPage), page = wrapper.findComponent(IndexPage)
    await flushPromises()
    page.vm.$emit('periodChange', { ...state.periods.value, composition: { kind: 'year', value: '2025' } })
    await flushPromises()
    expect(readAnnualOverview).toHaveBeenLastCalledWith({ buildingId: 'B', year: 2025, mode: 'MONTHLY_YTD', throughMonth: 12, timezoneId: 'Asia/Shanghai' }, expect.any(AbortSignal))
    expect(page.props('data').composition.data).toMatchObject({ total: 222, throughMonth: 12 })
    expect(page.props('data').annual).toMatchObject({ year: '2026', carbon: 129.456789 })
    const monthly = { period: { kind: 'month' as const, value: '2026-03' }, data: { total: 3, unit: 'tCO₂e', yearOverYear: 1, monthOverMonth: 2 } }
    state.data.value = { ...state.data.value, composition: monthly }
    page.vm.$emit('periodChange', { ...state.periods.value, composition: monthly.period })
    await flushPromises()
    expect(page.props('data').composition).toEqual(monthly)
    wrapper.unmount()
  })

  it('does not fall back to pre-existing or prototype annual values when the annual API fails', async () => {
    vi.mocked(readCarbonIntensity).mockResolvedValue(intensityView())
    vi.mocked(readAnnualOverview).mockRejectedValue(new Error('unavailable'))
    const wrapper = mount(FinalBPage)
    await flushPromises()
    expect(state.data.value.annual?.carbon).toBe(104)
    expect(wrapper.findComponent(IndexPage).props('data').annual).toBeUndefined()
    expect(wrapper.findComponent(IndexPage).props('data').composition).toBeUndefined()
    wrapper.unmount()
  })

  it('仅在静态 CO2-only 单位一致时合并动态两项，静态数值保持不变', async () => {
    const period = { kind: 'year' as const, value: '2026' }
    const staticEmissions = Array.from({ length: 12 }, (_, index) => index + 1)
    const staticFactors = Array.from({ length: 12 }, () => 0.56)
    state.data.value = { ...state.data.value, mirror: { period, data: {
      dynamicEmissions: Array(12).fill(null), dynamicFactors: Array(12).fill(null),
      staticEmissions, staticFactors, emissionUnit: 'tCO₂', factorUnit: 'kgCO₂/kWh',
    } } }
    dynamic.data.value = {
      period, dynamicEmissions: Array.from({ length: 12 }, (_, index) => index / 10), dynamicFactors: Array(12).fill(0.5),
      emissionUnit: 'tCO₂', factorUnit: 'kgCO₂/kWh', sourceNatures: ['SOURCE_LABELLED'], hasSimulatedSource: false, status: 'ready',
    }
    const wrapper = mount(FinalBPage)
    await flushPromises()
    const mirror = (wrapper.findComponent(IndexPage).props('data') as ScreenRedesignData).mirror?.data
    expect(mirror?.staticEmissions).toEqual(staticEmissions)
    expect(mirror?.staticFactors).toEqual(staticFactors)
    expect(mirror?.dynamicEmissions.slice(0, 3)).toEqual([0, 0.1, 0.2])
    expect(mirror?.dynamicFactors).toEqual(Array(12).fill(0.5))
    expect(wrapper.text()).toContain('因子来源性质：SOURCE_LABELLED')
    wrapper.unmount()
  })

  it('动态失败或 CO2e 静态序列不清空静态数据，也不标记静态镜像读取失败', async () => {
    const period = { kind: 'year' as const, value: '2026' }
    const staticEmissions = Array(12).fill(7)
    state.data.value = { ...state.data.value, mirror: { period, data: {
      dynamicEmissions: Array(12).fill(null), dynamicFactors: Array(12).fill(null),
      staticEmissions, staticFactors: Array(12).fill(0.56), emissionUnit: 'tCO₂e', factorUnit: 'kgCO₂e/kWh',
    } } }
    dynamic.data.value = {
      period, dynamicEmissions: Array(12).fill(6), dynamicFactors: Array(12).fill(0.5),
      emissionUnit: 'tCO₂', factorUnit: 'kgCO₂/kWh', sourceNatures: [], hasSimulatedSource: false, status: 'ready',
    }
    dynamic.failed.value = true
    const wrapper = mount(FinalBPage)
    await flushPromises()
    const data = wrapper.findComponent(IndexPage).props('data') as ScreenRedesignData
    expect(data.mirror?.data.staticEmissions).toEqual(staticEmissions)
    expect(data.mirror?.data.dynamicEmissions).toEqual(Array(12).fill(null))
    expect(data.failed?.mirror).toBe(false)
    wrapper.unmount()
  })
})

function planResponse(overrides: Partial<CarbonPlanResponse> = {}): CarbonPlanResponse {
  return {
    buildingId: 'B', year: 2026, timezoneId: 'Asia/Shanghai', unit: 'tCO2e', status: 'COMPLETE',
    throughMonth: '2026-09', latestCompletedMonth: '2026-09', missingMonths: [],
    plan: { versionId: 'plan-v1', buildingId: 'B', year: 2026, versionNo: 1, revision: 4, status: 'ACTIVE',
      createdBy: 1, reviewedBy: 2, activatedBy: 3, activatedAt: '2026-01-01T00:00:00Z', copiedFromVersionId: null, configuration: null },
    annualBaseline: '160', reductionPercent: '15', annualQuota: '136', actualCumulative: '104', remainingQuota: '32',
    months: Array.from({ length: 12 }, (_, i) => ({ month: i + 1, baseline: i < 8 ? '10' : '20', quota: i < 8 ? '8.5' : '17',
      actual: i < 9 ? String(i) : null, actualStatus: i < 9 ? 'AVAILABLE' : 'PERIOD_NOT_ENDED',
      batchId: i < 9 ? `batch-${i}` : null, evidenceHash: i < 9 ? `evidence-${i}` : null, referenceVersionId: 'rounding-v1',
      startInclusive: '', endExclusive: '' })), ...overrides,
  }
}

describe('final B annual plan integration', () => {
  beforeEach(() => {
    state = initialState()
    vi.mocked(readCarbonIntensity).mockResolvedValue(intensityView())
    vi.mocked(carbonPlanApi.query).mockReset()
    vi.mocked(carbonPlanApi.query).mockResolvedValue(planResponse())
  })

  it('uses backend fixed baseline and remaining amount while keeping all twelve chart months', async () => {
    const wrapper = mount(FinalBPage)
    await flushPromises()
    expect(carbonPlanApi.query).toHaveBeenCalledWith('B', 2026, undefined, expect.any(AbortSignal))
    const data = wrapper.findComponent(IndexPage).props('data') as ScreenRedesignData
    expect(data.plan?.period).toEqual({ kind: 'year', value: '2026' })
    expect(data.plan?.data).toMatchObject({ annualBaseline: 160, reductionPercent: 15, remainingQuota: 32 })
    expect(data.plan?.data.actual).toHaveLength(12)
    expect(data.plan?.data.actual[0]).toBe(0)
    expect(data.plan?.data.actual[9]).toBeNull()
    expect(data.annual).toMatchObject({ year: '2026', throughMonth: 9, carbon: 129.456789, intensity: 6.472839 })
    expect(data.carbonFlow?.data.total).toBe(104)
    wrapper.unmount()
  })

  it('keeps the annual chart on month selection, preserves negative and missing amounts and rejects late responses', async () => {
    const pending: Array<(value: CarbonPlanResponse) => void> = []
    vi.mocked(carbonPlanApi.query).mockImplementation(() => new Promise(resolve => pending.push(resolve)))
    const wrapper = mount(FinalBPage)
    const page = wrapper.findComponent(IndexPage)
    page.vm.$emit('periodChange', { ...state.periods.value, plan: { kind: 'month', value: '2026-03' } })
    await flushPromises()
    expect(page.props('data').plan).toBeUndefined()
    expect(carbonPlanApi.query).toHaveBeenLastCalledWith('B', 2026, '2026-03', expect.any(AbortSignal))
    pending[1](planResponse({ throughMonth: '2026-03', actualCumulative: '140', remainingQuota: '-4' }))
    await flushPromises()
    expect(page.props('data').plan.period).toEqual({ kind: 'year', value: '2026' })
    expect(page.props('data').plan.data.remainingQuota).toBe(-4)
    expect(page.props('data').plan.data.baseline).toHaveLength(12)
    pending[0](planResponse())
    await flushPromises()
    expect(page.props('data').plan.data.remainingQuota).toBe(-4)
    page.vm.$emit('periodChange', { ...state.periods.value, plan: { kind: 'month', value: '2026-04' } })
    await flushPromises()
    pending[2](planResponse({ status: 'INCOMPLETE_ACTUAL', throughMonth: '2026-04', missingMonths: [4], actualCumulative: null, remainingQuota: null }))
    await flushPromises()
    expect(page.props('data').plan.data.remainingQuota).toBeNull()
    wrapper.unmount()
  })
})
