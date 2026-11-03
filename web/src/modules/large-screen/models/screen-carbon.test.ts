import { describe, expect, it } from 'vitest'
import type {
  CalculationDetail,
  CarbonClassificationReport,
  MonthlyCalculationResult,
} from '@/modules/energy-classification/public'
import {
  beijingMonthWindow,
  comparisonMonths,
  hasAvailableCarbonFlow,
  isCurrentCarbonFlowReport,
  isCurrentFormalCarbonDetail,
  readCarbonSummary,
  selectScreenMonthlyCarbonResult,
} from './screen-carbon'

function detail(overrides: Partial<CalculationDetail> = {}): CalculationDetail {
  return {
    calculationBatchId: 'CALC-1', buildingId: 'B-1', periodType: 'MONTH',
    startInclusive: '2024-01-31T16:00:00.000Z', endExclusive: '2024-02-29T16:00:00.000Z',
    timezoneId: 'Asia/Shanghai', resultNature: 'FORMAL', publicationStatus: 'DIRECT',
    status: 'COMPLETED_COMPLETE', idempotencyKey: 'KEY-1', supersedesCalculationBatchId: null,
    startedAt: null, completedAt: null, durationMs: null, snapshotCount: 0, detailCount: 0,
    slowCalculation: false, safeErrorCode: null, safeErrorMessage: null, items: [], failures: [],
    summaries: [], requestHash: 'hash', createdBy: 1, roundingPolicyVersionId: 'ROUND-1', ...overrides,
  }
}

function monthResult(
  state: MonthlyCalculationResult['state'],
  batch: CalculationDetail | null,
  overrides: Partial<MonthlyCalculationResult> = {},
): MonthlyCalculationResult {
  return { month: '2024-02', state, reason: null, conflictBatchIds: [], batch, ...overrides }
}

function report(overrides: Partial<CarbonClassificationReport> = {}): CarbonClassificationReport {
  return {
    buildingId: 'B-1', batchId: 'CALC-1', status: 'COMPLETED_COMPLETE', unit: 'KG_CO2E',
    totalKgCo2e: '0', classificationComplete: true, nodes: [], periods: [], excludedSources: [],
    warnings: [], flows: [], ...overrides,
  }
}

describe('大屏碳批次模型', () => {
  it('按北京时间生成半开自然月窗口，并固定比较顺序处理跨年和闰月', () => {
    expect(beijingMonthWindow('2024-02')).toEqual({
      month: '2024-02',
      startInclusive: '2024-01-31T16:00:00.000Z',
      endExclusive: '2024-02-29T16:00:00.000Z',
    })
    expect(comparisonMonths('2025-01')).toEqual(['2024-01', '2025-01', '2024-12'])
    expect(beijingMonthWindow('2024-13')).toBeNull()
    expect(comparisonMonths('2024-02-01')).toBeNull()
  })

  it('只接受 READY 中同建筑、精确期间且正式完成的冻结详情', () => {
    const target = beijingMonthWindow('2024-02')!
    expect(selectScreenMonthlyCarbonResult(monthResult('READY', detail({ publicationStatus: 'PUBLISHED' })), 'B-1', target))
      .toEqual({ detail: expect.objectContaining({ calculationBatchId: 'CALC-1' }), state: 'ready', reason: null })
    expect(selectScreenMonthlyCarbonResult(monthResult('READY', detail({ publicationStatus: 'SUPERSEDED' })), 'B-1', target))
      .toEqual({ detail: null, state: 'error', reason: 'DETAIL_SCOPE_MISMATCH' })
    expect(isCurrentFormalCarbonDetail(detail(), 'CALC-1', 'B-1', target)).toBe(true)
    expect(isCurrentFormalCarbonDetail(detail({ publicationStatus: 'SUPERSEDED' }), 'CALC-1', 'B-1', target)).toBe(false)
  })

  it('将后端裁决的缺失、冲突和失败映射为明确展示状态，不扫描历史列表', () => {
    const target = beijingMonthWindow('2024-02')!
    expect(selectScreenMonthlyCarbonResult(monthResult('MISSING', null), 'B-1', target))
      .toEqual({ detail: null, state: 'empty', reason: 'MISSING' })
    expect(selectScreenMonthlyCarbonResult(monthResult('AMBIGUOUS', null, { conflictBatchIds: ['DIRECT', 'PUBLISHED'] }), 'B-1', target))
      .toEqual({ detail: null, state: 'ambiguous', reason: 'MULTIPLE_MATCHES' })
    expect(selectScreenMonthlyCarbonResult(monthResult('FAILED', null), 'B-1', target))
      .toEqual({ detail: null, state: 'error', reason: 'MONTH_RESULT_FAILED' })
    expect(selectScreenMonthlyCarbonResult(null, 'B-1', target))
      .toEqual({ detail: null, state: 'error', reason: 'MONTH_RESULT_SCOPE_MISMATCH' })
  })

  it('只读取唯一、可用、同单位的后端汇总，保留零值且不汇总 items', () => {
    const total = {
      metricCode: 'TOTAL_EMISSION', dimensionCode: 'TOTAL', rawValue: '0', finalValue: '0',
      unitCode: 'tCO2e', denominatorVersionId: null, unavailableReason: null, evidenceHash: 'evidence',
    }
    expect(readCarbonSummary(detail({ summaries: [total] }), 'TOTAL_EMISSION', 'TOTAL', 'tCO2e')).toBe(0)
    expect(readCarbonSummary(detail({ summaries: [total, { ...total, finalValue: '1' }] }), 'TOTAL_EMISSION', 'TOTAL', 'tCO2e')).toBeNull()
    expect(readCarbonSummary(detail({ summaries: [{ ...total, unavailableReason: '缺少分母' }] }), 'TOTAL_EMISSION', 'TOTAL', 'tCO2e')).toBeNull()
    expect(readCarbonSummary(detail({ summaries: [{ ...total, finalValue: 'Infinity' }] }), 'TOTAL_EMISSION', 'TOTAL', 'tCO2e')).toBeNull()
    expect(readCarbonSummary(detail({ summaries: [total] }), 'TOTAL_EMISSION', 'TOTAL', 'KG_CO2E')).toBeNull()
  })

  it('仅把同建筑、同批次、公斤 CO2e 且完整的可用流视为可展示', () => {
    expect(isCurrentCarbonFlowReport(report(), 'CALC-1', 'B-1')).toBe(true)
    expect(isCurrentCarbonFlowReport(report({ unit: 'tCO2e' as never }), 'CALC-1', 'B-1')).toBe(false)
    expect(hasAvailableCarbonFlow(report({ flows: [{
      schemeCode: 'FORMAL', available: false, unavailableReason: '非加性', nodes: [], links: [],
    }] }))).toBe(false)
    expect(hasAvailableCarbonFlow(report({ flows: [{
      schemeCode: 'FORMAL', available: true, unavailableReason: null, nodes: [], links: [],
    }] }))).toBe(false)
    expect(hasAvailableCarbonFlow(report({ flows: [{
      schemeCode: 'FORMAL', available: true, unavailableReason: null, nodes: [], links: [{ source: 'TOTAL', target: 'SCOPE_1', value: '0' }],
    }] }))).toBe(true)
  })
})
