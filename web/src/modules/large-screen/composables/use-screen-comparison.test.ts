import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref, type Ref } from 'vue'
import type { CalculationDetail, MonthlyCalculationResult } from '@/modules/energy-classification/public'
import { beijingMonthWindow } from '../models/screen-carbon'
import { useScreenComparison } from './use-screen-comparison'

const { getMonthlyResults, listBatches, getCalculation } = vi.hoisted(() => ({
  getMonthlyResults: vi.fn(),
  listBatches: vi.fn(),
  getCalculation: vi.fn(),
}))

vi.mock('@/modules/energy-classification/public', () => ({
  screenCarbonQueries: { getMonthlyResults, listBatches, getCalculation },
}))

function calculationDetail(
  month: string,
  batchId: string,
  buildingId = 'B-1',
  overrides: Partial<CalculationDetail> = {},
): CalculationDetail {
  const target = beijingMonthWindow(month)!
  return {
    calculationBatchId: batchId, buildingId, periodType: 'MONTH',
    startInclusive: target.startInclusive, endExclusive: target.endExclusive, timezoneId: 'Asia/Shanghai',
    resultNature: 'FORMAL', publicationStatus: 'DIRECT', status: 'COMPLETED_COMPLETE',
    idempotencyKey: `${batchId}-KEY`, supersedesCalculationBatchId: null, startedAt: null,
    completedAt: null, durationMs: null, snapshotCount: 0, detailCount: 0, slowCalculation: false,
    safeErrorCode: null, safeErrorMessage: null, items: [], failures: [], summaries: [], requestHash: 'hash',
    createdBy: 1, roundingPolicyVersionId: 'ROUND-1', ...overrides,
  }
}

function monthResult(
  month: string,
  state: MonthlyCalculationResult['state'],
  batch: CalculationDetail | null = null,
  conflictBatchIds: string[] = [],
): MonthlyCalculationResult {
  return { month, state, reason: null, conflictBatchIds, batch }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(next => { resolve = next })
  return { promise, resolve }
}

let buildingId: Ref<string>
let month: Ref<string>
let comparison!: ReturnType<typeof useScreenComparison>

function mountComparison() {
  return mount(defineComponent({
    setup() {
      comparison = useScreenComparison(buildingId, month)
      return () => h('div')
    },
  }))
}

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
  await flushPromises()
}

describe('useScreenComparison', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    buildingId = ref('B-1')
    month = ref('2024-02')
  })

  afterEach(() => vi.restoreAllMocks())

  it('以一次跨年三月精确查询读取事务内返回的冻结详情，不扫描历史批次', async () => {
    const lastYear = calculationDetail('2023-02', 'LAST-YEAR', 'B-1', { publicationStatus: 'PUBLISHED' })
    const current = calculationDetail('2024-02', 'CURRENT')
    const lastMonth = calculationDetail('2024-01', 'LAST-MONTH')
    getMonthlyResults.mockResolvedValue([
      monthResult('2023-02', 'READY', lastYear),
      monthResult('2024-02', 'READY', current),
      monthResult('2024-01', 'READY', lastMonth),
    ])

    const wrapper = mountComparison()
    await settle()

    expect(getMonthlyResults).toHaveBeenCalledTimes(1)
    expect(getMonthlyResults).toHaveBeenCalledWith('B-1', ['2023-02', '2024-02', '2024-01'], expect.anything())
    expect(listBatches).not.toHaveBeenCalled()
    expect(getCalculation).not.toHaveBeenCalled()
    expect(comparison.periods.value).toEqual(['2023-02', '2024-02', '2024-01'])
    expect(comparison.details.value.map(detail => detail?.calculationBatchId)).toEqual(['LAST-YEAR', 'CURRENT', 'LAST-MONTH'])
    expect(comparison.states.value).toEqual(['ready', 'ready', 'ready'])
    expect(comparison.loading.value).toBe(false)
    wrapper.unmount()
  })

  it('空建筑或非法月份不请求，并始终保留三个详情与状态槽', async () => {
    buildingId.value = ''
    const emptyBuilding = mountComparison()
    await settle()

    expect(getMonthlyResults).not.toHaveBeenCalled()
    expect(comparison.periods.value).toEqual(['2023-02', '2024-02', '2024-01'])
    expect(comparison.details.value).toEqual([null, null, null])
    expect(comparison.states.value).toEqual(['empty', 'empty', 'empty'])
    emptyBuilding.unmount()

    buildingId.value = 'B-1'
    month.value = '2024-02-30'
    const invalidMonth = mountComparison()
    await settle()

    expect(getMonthlyResults).not.toHaveBeenCalled()
    expect(comparison.periods.value).toEqual([])
    expect(comparison.details.value).toHaveLength(3)
    expect(comparison.states.value).toHaveLength(3)
    invalidMonth.unmount()
  })

  it('将缺失、冲突和后端失败显式映射为 empty、ambiguous、error，不任选冲突批次', async () => {
    month.value = '2024-03'
    getMonthlyResults.mockResolvedValue([
      monthResult('2023-03', 'AMBIGUOUS', null, ['DIRECT', 'PUBLISHED']),
      monthResult('2024-03', 'MISSING'),
      monthResult('2024-02', 'FAILED'),
    ])

    const wrapper = mountComparison()
    await settle()

    expect(listBatches).not.toHaveBeenCalled()
    expect(getCalculation).not.toHaveBeenCalled()
    expect(comparison.states.value).toEqual(['ambiguous', 'empty', 'error'])
    expect(comparison.details.value).toEqual([null, null, null])
    wrapper.unmount()
  })

  it('拒绝 READY 中身份或期间不符的详情，也拒绝缺失或重复的响应月份', async () => {
    const mismatched = calculationDetail('2023-02', 'WRONG-BUILDING', 'B-2')
    getMonthlyResults.mockResolvedValue([
      monthResult('2023-02', 'READY', mismatched),
      monthResult('2024-02', 'READY', calculationDetail('2024-02', 'ONE')),
      monthResult('2024-02', 'READY', calculationDetail('2024-02', 'TWO')),
    ])

    const wrapper = mountComparison()
    await settle()

    expect(comparison.states.value).toEqual(['error', 'error', 'error'])
    expect(comparison.details.value).toEqual([null, null, null])
    wrapper.unmount()
  })

  it('切换建筑或卸载会撤销旧请求，迟到响应不能写回新世代', async () => {
    const first = deferred<MonthlyCalculationResult[]>()
    const second = deferred<MonthlyCalculationResult[]>()
    getMonthlyResults.mockImplementation((currentBuildingId: string) => currentBuildingId === 'B-1'
      ? first.promise : second.promise)
    const wrapper = mountComparison()
    const firstSignal = getMonthlyResults.mock.calls[0][2] as AbortSignal

    buildingId.value = 'B-2'
    expect(firstSignal.aborted).toBe(true)
    second.resolve([
      monthResult('2023-02', 'READY', calculationDetail('2023-02', 'B-2-LAST-YEAR', 'B-2')),
      monthResult('2024-02', 'READY', calculationDetail('2024-02', 'B-2-CURRENT', 'B-2')),
      monthResult('2024-01', 'READY', calculationDetail('2024-01', 'B-2-LAST-MONTH', 'B-2')),
    ])
    await settle()
    first.resolve([monthResult('2023-02', 'READY', calculationDetail('2023-02', 'STALE'))])
    await settle()

    expect(comparison.details.value.map(detail => detail?.buildingId)).toEqual(['B-2', 'B-2', 'B-2'])
    wrapper.unmount()

    const pending = deferred<MonthlyCalculationResult[]>()
    getMonthlyResults.mockImplementation(() => pending.promise)
    const pendingWrapper = mountComparison()
    const pendingSignal = getMonthlyResults.mock.calls.at(-1)?.[2] as AbortSignal
    pendingWrapper.unmount()
    expect(pendingSignal.aborted).toBe(true)
  })
})
