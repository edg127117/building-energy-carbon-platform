import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { TransportError } from '@/infrastructure/http/public'
import BoundarySummaryPolicyPanel from './BoundarySummaryPolicyPanel.vue'
import { boundarySummaryPoliciesApi } from '../api/boundary-summary-policies'
import { energyCatalogApi } from '../api/carbon-project'
import { collectRelationPages } from '../api/relation-governance'

vi.mock('../api/boundary-summary-policies', () => ({
  boundarySummaryPoliciesApi: { list: vi.fn(), create: vi.fn(), approve: vi.fn() },
}))
vi.mock('../api/carbon-project', () => ({ energyCatalogApi: { listItems: vi.fn() } }))
vi.mock('../api/relation-governance', () => ({
  collectRelationPages: vi.fn(), relationGovernanceApi: { listEffectiveBoundaries: vi.fn() },
}))
vi.mock('@/shared/ui', () => ({
  ElAlert: { props: ['title'], template: '<div role="alert">{{ title }}</div>' },
  ElButton: { props: ['disabled', 'loading', 'type'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  ElCard: { template: '<section><slot name="header" /><slot /></section>' },
  ElDialog: { props: ['modelValue', 'title'], emits: ['close'], template: '<section v-if="modelValue"><h2>{{ title }}</h2><slot /><footer><slot name="footer" /></footer></section>' },
  ElEmpty: { props: ['description'], template: '<div>{{ description }}</div>' },
  ElFormItem: { props: ['label'], template: '<label><span>{{ label }}</span><slot /></label>' },
  ElInput: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  ElSelect: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<select :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>' },
  ElSkeleton: { template: '<div />' },
  ElTable: { template: '<div><slot /></div>' },
  ElTableColumn: { template: '<div />' },
  ElTag: { template: '<span><slot /></span>' },
}))

const pendingPolicy = {
  policyId: 'POLICY-1', versionId: 'POLICY-V1', versionNo: 1, buildingId: 'B-1', meteringBoundaryId: 'MB-1',
  energyItemCode: 'ELECTRICITY', aggregationMode: 'MAIN_METER_TOTAL', status: 'PENDING_EXPERT', sourceType: 'FORMAL' as const,
  evidenceReference: '已提交依据', effectiveFrom: '2026-01-01T00:00', effectiveTo: null, configRevision: 4,
  createdBy: 1, createdAt: '2026-01-01T00:00', approvedBy: null, approvedAt: null, reviewComment: null,
}

function mountPanel() {
  return mount(BoundarySummaryPolicyPanel, { props: { buildingId: 'B-1' } })
}

async function settle() {
  await vi.waitFor(() => expect(boundarySummaryPoliciesApi.list).toHaveBeenCalledWith('B-1'))
  await flushPromises()
}

describe('计量边界汇总口径面板', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(boundarySummaryPoliciesApi.list).mockResolvedValue([] as never)
    vi.mocked(collectRelationPages).mockResolvedValue({
      page: 1, size: 100, total: 2,
      items: [
        { boundaryId: 'MB-1', boundaryCode: 'MAIN', boundaryName: '总表边界', energyType: 'ELECTRICITY', confirmationStatus: 'CONFIRMED', status: 'ACTIVE' },
        { boundaryId: 'MB-2', boundaryCode: 'UNCONFIRMED', boundaryName: '待确认边界', energyType: 'HEAT', confirmationStatus: 'PENDING_EXPERT', status: 'ACTIVE' },
      ],
    } as never)
    vi.mocked(energyCatalogApi.listItems).mockResolvedValue([
      { itemId: 'ITEM-1', itemCode: 'ELECTRICITY', itemName: '电力', status: 'APPROVED', versionId: 'ITEM-V1', versionNo: 1 },
      { itemId: 'ITEM-2', itemCode: 'HEAT', itemName: '热力', status: 'PENDING_EXPERT', versionId: 'ITEM-V2', versionNo: 2 },
    ] as never)
  })

  it('只从有效确认边界和已审核目录创建明确选择的正式口径', async () => {
    vi.mocked(boundarySummaryPoliciesApi.create).mockResolvedValue({ ...pendingPolicy, versionId: 'POLICY-NEW' } as never)
    const wrapper = mountPanel()
    await settle()

    expect(wrapper.find('[data-test="boundary-select"]').text()).toContain('总表边界')
    expect(wrapper.find('[data-test="boundary-select"]').text()).not.toContain('待确认边界')
    expect(wrapper.find('[data-test="energy-select"]').text()).toContain('电力')
    expect(wrapper.find('[data-test="energy-select"]').text()).not.toContain('热力')
    expect(wrapper.find('[data-test="policy-create"]').attributes('disabled')).toBeDefined()

    await wrapper.find('[data-test="boundary-select"]').setValue('MB-1')
    await wrapper.find('[data-test="energy-select"]').setValue('ELECTRICITY')
    await wrapper.find('[data-test="aggregation-mode"]').setValue('MAIN_METER_TOTAL')
    await wrapper.find('[data-test="source-type"]').setValue('FORMAL')
    await wrapper.find('[data-test="effective-from"]').setValue('2026-01-01T00:00')
    await wrapper.find('[data-test="evidence-reference"]').setValue('汇总口径专业依据')
    await wrapper.find('[data-test="policy-create"]').trigger('click')
    await flushPromises()

    expect(boundarySummaryPoliciesApi.create).toHaveBeenCalledWith({
      buildingId: 'B-1', meteringBoundaryId: 'MB-1', energyItemCode: 'ELECTRICITY',
      aggregationMode: 'MAIN_METER_TOTAL', sourceType: 'FORMAL', evidenceReference: '汇总口径专业依据',
      effectiveFrom: '2026-01-01T00:00', effectiveTo: null,
    })
    expect(wrapper.text()).toContain('页面未自动确认或选用')
    wrapper.unmount()
  })

  it('独立审核必须填写意见，并只由后端返回审核后的版本', async () => {
    vi.mocked(boundarySummaryPoliciesApi.list).mockResolvedValue([pendingPolicy] as never)
    vi.mocked(boundarySummaryPoliciesApi.approve).mockResolvedValue({
      ...pendingPolicy, status: 'APPROVED', approvedBy: 2, approvedAt: '2026-01-02T00:00', reviewComment: '独立审核通过',
    } as never)
    const wrapper = mountPanel()
    await settle()

    await wrapper.find('[data-test="policy-review-POLICY-V1"]').trigger('click')
    expect(wrapper.find('[data-test="policy-review-submit"]').attributes('disabled')).toBeDefined()
    await wrapper.find('[data-test="policy-review-comment"]').setValue('独立审核通过')
    await wrapper.find('[data-test="policy-review-submit"]').trigger('click')
    await flushPromises()

    expect(boundarySummaryPoliciesApi.approve).toHaveBeenCalledWith('POLICY-V1', {
      expectedRevision: 4, reviewComment: '独立审核通过',
    })
    expect(wrapper.text()).not.toContain('PENDING_EXPERT')
    wrapper.unmount()
  })

  it('后端拒绝超过上限的口径版本时，不把结果当作可用的截断列表', async () => {
    vi.mocked(boundarySummaryPoliciesApi.list).mockRejectedValue(new TransportError('request', 409, undefined, 'RESULT_LIMIT_EXCEEDED'))
    const wrapper = mountPanel()
    await settle()

    expect(wrapper.text()).toContain('超过 500 条查询上限')
    wrapper.unmount()
  })
})
