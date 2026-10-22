import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { TransportError } from '@/infrastructure/http/public'
import CarbonResultsPanel from './CarbonResultsPanel.vue'
import { carbonResultsApi } from '../api/carbon-results'

vi.mock('../api/carbon-results', () => ({
  carbonResultsApi: {
    listBatches: vi.fn(), listRecalculations: vi.fn(), runCalculation: vi.fn(), getCalculation: vi.fn(),
    getClassificationReport: vi.fn(), getEvidence: vi.fn(), submitManualRecalculation: vi.fn(),
    approveRecalculation: vi.fn(), rejectRecalculation: vi.fn(),
  },
}))
vi.mock('./CarbonResultCharts.vue', () => ({ default: { template: '<div data-test="result-charts" />' } }))
vi.mock('@/shared/ui', () => ({
  ElAlert: { props: ['title'], template: '<div role="alert">{{ title }}</div>' },
  ElButton: { props: ['disabled', 'loading', 'type'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  ElCard: { template: '<section><slot name="header" /><slot /></section>' },
  ElDialog: { props: ['modelValue', 'title'], emits: ['close'], template: '<section v-if="modelValue"><h2>{{ title }}</h2><slot /><footer><slot name="footer" /></footer></section>' },
  ElEmpty: { props: ['description'], template: '<div>{{ description }}</div>' },
  ElFormItem: { props: ['label'], template: '<label><span>{{ label }}</span><slot /></label>' },
  ElInput: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  ElRadio: { props: ['label'], template: '<option :value="label"><slot /></option>' },
  ElRadioGroup: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<select :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>' },
  ElSelect: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<select :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>' },
  ElSkeleton: { template: '<div />' },
  ElTable: { template: '<div><slot /></div>' },
  ElTableColumn: { template: '<div />' },
  ElTag: { template: '<span><slot /></span>' },
}))

function calculationDetail(status = 'COMPLETED_COMPLETE') {
  return {
    calculationBatchId: 'CALC-1', buildingId: 'B-1', periodType: 'MONTH',
    startInclusive: '2026-01-01T00:00:00Z', endExclusive: '2026-02-01T00:00:00Z', timezoneId: 'Asia/Shanghai',
    resultNature: 'FORMAL', publicationStatus: 'DIRECT', status, idempotencyKey: 'CALC-KEY-1',
    supersedesCalculationBatchId: null, startedAt: '2026-02-01T00:00:00', completedAt: null, durationMs: null,
    snapshotCount: 0, detailCount: 0, slowCalculation: false, safeErrorCode: null, safeErrorMessage: null,
    items: [], failures: [], summaries: [], requestHash: 'hash', createdBy: 1, roundingPolicyVersionId: 'ROUND-1',
  }
}

const recalculation = {
  recalculationBatchId: 'RECALC-1', triggerReason: 'MANUAL', organizationBoundary: 'ORG-1', resultNature: 'FORMAL',
  status: 'PENDING_APPROVAL', scopeFrozen: true, itemCount: 1, eligibleItemCount: 1, initiatedBy: 1,
  approvedBy: null, approvedAt: null, reviewComment: null, safeErrorCode: null,
  items: [{ recalculationItemId: 'ITEM-1', buildingId: 'B-1', accountingYear: 2026, oldCalculationBatchId: 'OLD-1', candidateCalculationBatchId: 'NEW-1', status: 'SUCCEEDED', approvalEligible: true, retryCount: 0, nextAttemptAt: null, safeErrorCode: null, safeErrorMessage: null }],
}

function mountPanel(props: Record<string, string> = {}) {
  return mount(CarbonResultsPanel, { props: { buildingId: 'B-1', ...props } })
}

async function settleLists() {
  await vi.waitFor(() => expect(carbonResultsApi.listBatches).toHaveBeenCalledWith('B-1'))
  await vi.waitFor(() => expect(carbonResultsApi.listRecalculations).toHaveBeenCalledWith('B-1'))
  await flushPromises()
}

describe('碳核算结果与重算面板', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(carbonResultsApi.listBatches).mockResolvedValue([] as never)
    vi.mocked(carbonResultsApi.listRecalculations).mockResolvedValue([] as never)
    vi.mocked(carbonResultsApi.getCalculation).mockResolvedValue(calculationDetail() as never)
    vi.mocked(carbonResultsApi.getClassificationReport).mockResolvedValue({
      buildingId: 'B-1', batchId: 'CALC-1', status: 'COMPLETED_COMPLETE', unit: 'KG_CO2E', totalKgCo2e: '0', classificationComplete: true,
      nodes: [], periods: [], excludedSources: [], warnings: [], flows: [],
    } as never)
  })

  it('只有明确填写期间、时区和 FORMAL 后才调用后端，且保留幂等键与计算中状态', async () => {
    vi.mocked(carbonResultsApi.runCalculation).mockResolvedValue(calculationDetail('CALCULATING') as never)
    const wrapper = mountPanel()
    await settleLists()

    expect(wrapper.find('[data-test="calculation-submit"]').attributes('disabled')).toBeDefined()
    await wrapper.find('[data-test="calculation-period-type"]').setValue('MONTH')
    await wrapper.find('[data-test="calculation-result-nature"]').setValue('FORMAL')
    await wrapper.find('[data-test="calculation-start"]').setValue('2026-01-01T00:00:00+08:00')
    await wrapper.find('[data-test="calculation-end"]').setValue('2026-02-01T00:00:00+08:00')
    await wrapper.find('[data-test="calculation-timezone"]').setValue('Asia/Shanghai')
    await wrapper.find('[data-test="calculation-idempotency-key"]').setValue('CALC-KEY-1')
    await wrapper.find('[data-test="calculation-submit"]').trigger('click')
    await flushPromises()

    expect(carbonResultsApi.runCalculation).toHaveBeenCalledWith({
      buildingId: 'B-1', periodType: 'MONTH', resultNature: 'FORMAL', timezoneId: 'Asia/Shanghai', idempotencyKey: 'CALC-KEY-1',
      startInclusive: '2026-01-01T00:00:00+08:00', endExclusive: '2026-02-01T00:00:00+08:00',
    })
    expect((wrapper.find('[data-test="calculation-idempotency-key"]').element as HTMLTextAreaElement).value).toBe('CALC-KEY-1')
    expect(wrapper.text()).toContain('仍在计算中')
    wrapper.unmount()
  })

  it('手动重算只提交后端触发条件，候选审批必须显式选择结论和审核意见', async () => {
    vi.mocked(carbonResultsApi.listRecalculations).mockResolvedValue([recalculation] as never)
    vi.mocked(carbonResultsApi.submitManualRecalculation).mockResolvedValue({ changeId: 'CHANGE-1', status: 'PENDING', buildingId: 'B-1', accountingYear: 2026 } as never)
    vi.mocked(carbonResultsApi.approveRecalculation).mockResolvedValue({ ...recalculation, status: 'COMPLETED', approvedBy: 2, approvedAt: '2026-02-01T00:00', reviewComment: '独立复核通过' } as never)
    const wrapper = mountPanel()
    await settleLists()

    await wrapper.find('[data-test="recalculation-year"]').setValue('2026')
    await wrapper.find('[data-test="recalculation-result-nature"]').setValue('FORMAL')
    await wrapper.find('[data-test="recalculation-boundary"]').setValue('ORG-1')
    await wrapper.find('[data-test="recalculation-reason"]').setValue('来源更正后需要重新核算')
    await wrapper.find('[data-test="recalculation-submit"]').trigger('click')
    await flushPromises()
    expect(carbonResultsApi.submitManualRecalculation).toHaveBeenCalledWith({
      buildingId: 'B-1', accountingYear: 2026, resultNature: 'FORMAL', reason: '来源更正后需要重新核算', organizationBoundary: 'ORG-1',
    })

    await wrapper.find('[data-test="recalculation-review-RECALC-1"]').trigger('click')
    expect(wrapper.find('[data-test="recalculation-review-submit"]').attributes('disabled')).toBeDefined()
    await wrapper.find('[data-test="recalculation-review-decision"]').setValue('approve')
    await wrapper.find('[data-test="recalculation-review-comment"]').setValue('独立复核通过')
    await wrapper.find('[data-test="recalculation-review-submit"]').trigger('click')
    await flushPromises()

    expect(carbonResultsApi.approveRecalculation).toHaveBeenCalledWith('RECALC-1', { reviewComment: '独立复核通过' })
    expect(carbonResultsApi.rejectRecalculation).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('后端拒绝超过上限的重算队列时，不把结果当作可用的截断列表', async () => {
    vi.mocked(carbonResultsApi.listRecalculations).mockRejectedValue(new TransportError('request', 409, undefined, 'RECALCULATION_CONFLICT'))
    const wrapper = mountPanel()
    await settleLists()

    expect(wrapper.text()).toContain('超过 100 条查询上限')
    wrapper.unmount()
  })

  it('月度填报上下文只提示目标月份，仍要求核算人员明确期间和时区', async () => {
    const wrapper = mount(CarbonResultsPanel, { props: { buildingId: 'B-1', reportingMonth: '2026-10-01' } })
    await settleLists()

    expect(wrapper.text()).toContain('已从月度填报带入月份 2026-10-01')
    expect(wrapper.find('[data-test="calculation-submit"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('携带月份时自动选择同月的后端固定批次读取报告，不在页面汇总结果', async () => {
    vi.mocked(carbonResultsApi.listBatches).mockResolvedValue([{
      batchId: 'CALC-1', buildingId: 'B-1', status: 'COMPLETED_COMPLETE', periodType: 'MONTH',
      periodStart: '2026-10-01T00:00:00+08:00', periodEnd: '2026-11-01T00:00:00+08:00', timezoneId: 'Asia/Shanghai',
      publicationStatus: 'DIRECT', resultNature: 'FORMAL', idempotencyKey: 'CALC-KEY-1', completedAt: null,
      safeErrorCode: null, safeErrorMessage: null, createdAt: null,
    }] as never)
    const wrapper = mountPanel({ reportingMonth: '2026-10-01' })
    await settleLists()
    await vi.waitFor(() => expect(carbonResultsApi.getCalculation).toHaveBeenCalledWith('CALC-1'))
    expect(carbonResultsApi.getClassificationReport).toHaveBeenCalledWith('CALC-1')
    wrapper.unmount()
  })
})
