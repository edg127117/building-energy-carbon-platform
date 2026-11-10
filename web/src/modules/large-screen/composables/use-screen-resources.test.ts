import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import type { CalculationDetail, MonthlyCalculationResult } from '@/modules/energy-classification/public'
import { beijingMonthWindow } from '../models/screen-carbon'
import { useScreenResources } from './use-screen-resources'

const {
  listAccessibleBuildings,
  listAssetLedger,
  getMonthlyResults,
  listBatches,
  getCalculation,
  getClassificationReport,
  session,
} = vi.hoisted(() => ({
  listAccessibleBuildings: vi.fn(),
  listAssetLedger: vi.fn(),
  getMonthlyResults: vi.fn(),
  listBatches: vi.fn(),
  getCalculation: vi.fn(),
  getClassificationReport: vi.fn(),
  session: { user: null as { roles: string[] } | null },
}))

vi.mock('@/modules/dashboard/public', () => ({ listAccessibleBuildings }))
vi.mock('@/modules/asset-management/public', () => ({ listAssetLedger }))
vi.mock('@/modules/energy-classification/public', () => ({
  screenCarbonQueries: { getMonthlyResults, listBatches, getCalculation, getClassificationReport },
}))
vi.mock('@/modules/auth/public', () => ({ useSession: () => session }))

const building = {
  buildingId: 'B-1',
  buildingName: '一号楼',
  buildingCode: 'B-1',
  buildingType: null,
  climateZone: null,
}

function calculationDetail(
  month = '2026-09',
  batchId = 'CALC-1',
  buildingId = 'B-1',
  overrides: Partial<CalculationDetail> = {},
): CalculationDetail {
  const target = beijingMonthWindow(month)!
  return {
    calculationBatchId: batchId, buildingId, periodType: 'MONTH',
    startInclusive: target.startInclusive, endExclusive: target.endExclusive,
    timezoneId: 'Asia/Shanghai', resultNature: 'FORMAL', publicationStatus: 'PUBLISHED',
    status: 'COMPLETED_COMPLETE', idempotencyKey: 'CALC-KEY-1', supersedesCalculationBatchId: null,
    startedAt: null, completedAt: null, durationMs: null, snapshotCount: 0, detailCount: 0,
    slowCalculation: false, safeErrorCode: null, safeErrorMessage: null, items: [], failures: [],
    summaries: [], requestHash: 'hash', createdBy: 1, roundingPolicyVersionId: 'ROUND-1', ...overrides,
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

function classificationReport(overrides: Record<string, unknown> = {}) {
  return {
    buildingId: 'B-1', batchId: 'CALC-1', status: 'COMPLETED_COMPLETE', unit: 'KG_CO2E',
    totalKgCo2e: '0', classificationComplete: true, nodes: [], periods: [], excludedSources: [],
    warnings: [], flows: [{ schemeCode: 'FORMAL', available: true, unavailableReason: null, nodes: [], links: [{ source: 'TOTAL', target: 'SCOPE_1', value: '0' }] }], ...overrides,
  }
}

function assetLedger(overrides: Record<string, unknown> = {}) {
  return {
    page: 1, size: 8, total: 0, items: [],
    groupCounts: { ALL: 0, POWER: 0, HVAC: 0, RENEWABLE: 0, WATER: 0, ENVIRONMENT: 0, UNCLASSIFIED: 0 },
    summary: { assets: 0, points: 0, online: 0, offline: 0, communicationError: 0, communicationUnknown: 0, notMonitored: 0, delayed: 0 },
    generatedAt: 1, ...overrides,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(next => { resolve = next })
  return { promise, resolve }
}

let resources!: ReturnType<typeof useScreenResources>

function mountResources() {
  return mount(defineComponent({
    setup() {
      resources = useScreenResources()
      return () => h('div')
    },
  }))
}

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
  await flushPromises()
}

describe('useScreenResources', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-26T12:00:00.000Z'))
    vi.resetAllMocks()
    session.user = { roles: ['PLATFORM_ADMIN'] }
    listAccessibleBuildings.mockResolvedValue([building])
    listAssetLedger.mockResolvedValue(assetLedger())
    getMonthlyResults.mockResolvedValue([monthResult('2026-09', 'READY', calculationDetail())])
    getClassificationReport.mockResolvedValue(classificationReport())
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('默认读取北京时间上一个完整月的正式完成 DIRECT 固定批次，并保留零值', async () => {
    const wrapper = mountResources()
    await settle()

    expect(resources.month.value).toBe('2026-09')
    expect(resources.buildingId.value).toBe('B-1')
    expect(listAssetLedger).toHaveBeenCalledWith({ buildingId: 'B-1', page: 1, size: 8, professionalGroup: 'ALL' })
    expect(getMonthlyResults).toHaveBeenCalledWith('B-1', ['2026-09'], expect.anything())
    expect(listBatches).not.toHaveBeenCalled()
    expect(getCalculation).not.toHaveBeenCalled()
    expect(getClassificationReport).toHaveBeenCalledWith('CALC-1', expect.anything())
    expect(resources.ledger.value?.summary.assets).toBe(0)
    expect(resources.carbonReport.value?.totalKgCo2e).toBe('0')
    expect(resources.ledgerError.value).toBe(false)
    expect(resources.carbonError.value).toBe(false)
    expect(resources.carbonState.value).toBe('ready')
    expect(resources.carbonReason.value).toBeNull()
    wrapper.unmount()
  })

  it('非平台管理员不读取仅管理员台账，但仍可读取已授权建筑的能碳结果', async () => {
    session.user = { roles: ['BUILDING_OWNER'] }
    const wrapper = mountResources()
    await settle()

    expect(listAssetLedger).not.toHaveBeenCalled()
    expect(resources.ledger.value).toBeNull()
    expect(resources.ledgerLoading.value).toBe(false)
    expect(getMonthlyResults).toHaveBeenCalledWith('B-1', ['2026-09'], expect.anything())
    expect(listBatches).not.toHaveBeenCalled()
    expect(getCalculation).not.toHaveBeenCalled()
    expect(resources.carbonDetail.value?.calculationBatchId).toBe('CALC-1')
    wrapper.unmount()
  })

  it('直接使用 READY 的事务内详情读取既有分类报告，不再追加详情请求', async () => {
    getMonthlyResults.mockResolvedValue([
      monthResult('2026-09', 'READY', calculationDetail('2026-09', 'PUBLISHED-FORMAL', 'B-1', { publicationStatus: 'PUBLISHED' })),
    ])
    getClassificationReport.mockResolvedValue(classificationReport({ batchId: 'PUBLISHED-FORMAL' }))
    const wrapper = mountResources()
    await settle()

    expect(listBatches).not.toHaveBeenCalled()
    expect(getCalculation).not.toHaveBeenCalled()
    expect(getClassificationReport).toHaveBeenCalledWith('PUBLISHED-FORMAL', expect.anything())
    expect(resources.carbonDetail.value?.calculationBatchId).toBe('PUBLISHED-FORMAL')
    wrapper.unmount()
  })

  it('将后端 MISSING、AMBIGUOUS、FAILED 显式展示，不伪造汇总或任选冲突批次', async () => {
    getMonthlyResults.mockResolvedValue([monthResult('2026-09', 'MISSING')])
    const missing = mountResources()
    await settle()

    expect(getClassificationReport).not.toHaveBeenCalled()
    expect(resources.carbonDetail.value).toBeNull()
    expect(resources.carbonReport.value).toBeNull()
    expect(resources.carbonError.value).toBe(false)
    expect(resources.carbonState.value).toBe('empty')
    expect(resources.carbonReason.value).toBe('MISSING')
    missing.unmount()

    getMonthlyResults.mockResolvedValue([monthResult('2026-09', 'AMBIGUOUS', null, ['DIRECT', 'PUBLISHED'])])
    const ambiguous = mountResources()
    await settle()

    expect(resources.carbonState.value).toBe('ambiguous')
    expect(resources.carbonReason.value).toBe('MULTIPLE_MATCHES')
    ambiguous.unmount()

    getMonthlyResults.mockResolvedValue([monthResult('2026-09', 'FAILED')])
    const failed = mountResources()
    await settle()

    expect(resources.carbonState.value).toBe('error')
    expect(resources.carbonReason.value).toBe('MONTH_RESULT_FAILED')
    expect(resources.carbonError.value).toBe(true)
    failed.unmount()
  })

  it('空建筑不发送资源查询，建筑目录失败与资源失败分别清空旧数据并标记错误', async () => {
    listAccessibleBuildings.mockResolvedValue([])
    const empty = mountResources()
    await settle()

    expect(resources.buildingId.value).toBe('')
    expect(listAssetLedger).not.toHaveBeenCalled()
    expect(getMonthlyResults).not.toHaveBeenCalled()
    expect(resources.ledger.value).toBeNull()
    expect(resources.carbonReport.value).toBeNull()
    empty.unmount()

    listAccessibleBuildings.mockRejectedValue(new Error('building unavailable'))
    const failedBuildings = mountResources()
    await settle()

    expect(resources.buildingsError.value).toBe(true)
    expect(resources.buildings.value).toEqual([])
    expect(resources.buildingId.value).toBe('')
    failedBuildings.unmount()

    listAccessibleBuildings.mockResolvedValue([building])
    listAssetLedger.mockRejectedValue(new Error('ledger unavailable'))
    getMonthlyResults.mockRejectedValue(new Error('carbon unavailable'))
    const failedResources = mountResources()
    await settle()

    expect(resources.ledger.value).toBeNull()
    expect(resources.ledgerError.value).toBe(true)
    expect(resources.carbonDetail.value).toBeNull()
    expect(resources.carbonReport.value).toBeNull()
    expect(resources.carbonError.value).toBe(true)
    failedResources.unmount()
  })

  it('切换建筑立即清空旧数据，旧世代迟到响应不能复活', async () => {
    const oldLedger = deferred<ReturnType<typeof assetLedger>>()
    const oldMonthlyResults = deferred<MonthlyCalculationResult[]>()
    const secondBuilding = { ...building, buildingId: 'B-2', buildingName: '二号楼', buildingCode: 'B-2' }
    listAccessibleBuildings.mockResolvedValue([building, secondBuilding])
    listAssetLedger.mockImplementation(({ buildingId }: { buildingId: string }) => buildingId === 'B-1'
      ? oldLedger.promise : Promise.resolve(assetLedger({ generatedAt: 2 })))
    getMonthlyResults.mockImplementation((buildingId: string) => buildingId === 'B-1'
      ? oldMonthlyResults.promise
      : Promise.resolve([monthResult('2026-09', 'READY', calculationDetail('2026-09', 'CALC-2', 'B-2'))]))
    getClassificationReport.mockImplementation((batchId: string) => Promise.resolve(classificationReport({
      batchId,
      buildingId: batchId === 'CALC-2' ? 'B-2' : 'B-1',
    })))
    const wrapper = mountResources()
    await settle()
    const oldSignal = getMonthlyResults.mock.calls[0][2] as AbortSignal

    resources.buildingId.value = 'B-2'
    expect(oldSignal.aborted).toBe(true)
    expect(resources.ledger.value).toBeNull()
    expect(resources.carbonDetail.value).toBeNull()
    expect(resources.carbonReport.value).toBeNull()
    await settle()

    oldLedger.resolve(assetLedger({ generatedAt: 1 }))
    oldMonthlyResults.resolve([monthResult('2026-09', 'READY', calculationDetail())])
    await settle()

    expect(resources.buildingId.value).toBe('B-2')
    expect(resources.ledger.value?.generatedAt).toBe(2)
    expect(resources.carbonDetail.value?.buildingId).toBe('B-2')
    expect(resources.carbonReport.value?.buildingId).toBe('B-2')
    wrapper.unmount()
  })

  it('详情或流报告元数据不符、非加性流不会被标记为 ready', async () => {
    getMonthlyResults.mockResolvedValue([monthResult('2026-09', 'READY', calculationDetail(
      '2026-09', 'CALC-1', 'B-1', { publicationStatus: 'SUPERSEDED' },
    ))])
    const mismatchedDetail = mountResources()
    await settle()

    expect(getClassificationReport).not.toHaveBeenCalled()
    expect(resources.carbonState.value).toBe('error')
    expect(resources.carbonReason.value).toBe('DETAIL_SCOPE_MISMATCH')
    expect(resources.carbonError.value).toBe(true)
    mismatchedDetail.unmount()

    getMonthlyResults.mockResolvedValue([monthResult('2026-09', 'READY', calculationDetail())])
    getClassificationReport.mockResolvedValue(classificationReport({ unit: 'T_CO2E' }))
    const mismatchedReport = mountResources()
    await settle()

    expect(resources.carbonReport.value).toBeNull()
    expect(resources.carbonState.value).toBe('error')
    expect(resources.carbonReason.value).toBe('REPORT_SCOPE_MISMATCH')
    mismatchedReport.unmount()

    getClassificationReport.mockResolvedValue(classificationReport({ flows: [{
      schemeCode: 'NON_ADDITIVE', available: false, unavailableReason: '非加性分类', nodes: [], links: [],
    }] }))
    const unavailableFlow = mountResources()
    await settle()

    expect(resources.carbonDetail.value?.calculationBatchId).toBe('CALC-1')
    expect(resources.carbonReport.value?.flows[0]?.unavailableReason).toBe('非加性分类')
    expect(resources.carbonState.value).toBe('empty')
    expect(resources.carbonReason.value).toBe('FLOW_UNAVAILABLE')
    unavailableFlow.unmount()
  })
})
