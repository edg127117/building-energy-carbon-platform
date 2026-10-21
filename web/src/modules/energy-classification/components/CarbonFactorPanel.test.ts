import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, ref } from 'vue'
import CarbonFactorPanel from './CarbonFactorPanel.vue'
import { useCarbonFactors } from '../composables/use-carbon-factors'

vi.mock('../composables/use-carbon-factors', () => ({ useCarbonFactors: vi.fn() }))
vi.mock('@/shared/ui', () => ({
  ElAlert: { props: ['title'], template: '<div role="alert">{{ title }}</div>' },
  ElButton: { props: ['disabled', 'loading', 'type'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  ElCard: { template: '<section><slot name="header" /><slot /></section>' },
  ElDialog: { props: ['modelValue', 'title'], emits: ['close'], template: '<section v-if="modelValue" data-test="review-dialog"><h2>{{ title }}</h2><slot /><footer><slot name="footer" /></footer></section>' },
  ElEmpty: { props: ['description'], template: '<div>{{ description }}</div>' },
  ElFormItem: { props: ['label'], template: '<label><span>{{ label }}</span><slot /></label>' },
  ElInput: { props: ['modelValue', 'disabled', 'readonly'], emits: ['update:modelValue'], template: '<input :value="modelValue" :disabled="disabled" :readonly="readonly" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  ElRadio: { props: ['label'], template: '<span><slot /></span>' },
  ElRadioGroup: { template: '<div><slot /></div>' },
  ElSelect: { props: ['modelValue', 'disabled'], emits: ['update:modelValue', 'change'], template: '<select :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value); $emit(\'change\', $event.target.value)"><slot /></select>' },
  ElSkeleton: { template: '<div />' },
  ElTag: { template: '<span><slot /></span>' },
}))

const source = {
  sourceId: 'S-1', sourceCode: 'SRC', sourceVersionId: 'SV-1', versionNo: 1, sourceName: '来源甲', publisher: '发布方', documentReference: '文号', publicationYear: null, publishedOn: null,
  applicabilityNote: '适用说明', evidenceReference: '来源证据', usageNature: 'FORMAL' as const, createdBy: 1, createdAt: '2026-01-01T00:00:00',
}
const factor = {
  factorId: 'F-1', factorCode: 'FACTOR', factorVersionId: 'FV-1', versionNo: 1, scopeType: 'SCOPE_2' as const, energyItemCode: 'ELEC', factorCategory: 'PURCHASED_ELECTRICITY_LOCATION' as const,
  resultBasis: 'CO2E_DIRECT', gasCode: 'CO2E', gasCoverage: 'DIRECT_FACTOR_GAS_COVERAGE', sourceVersionId: 'SV-1', applicabilityLevel: 'NATIONAL' as const,
  buildingId: null, regionCode: null, inputUnitCode: 'KWH', standardConditionCode: null, usageNature: 'FORMAL' as const, status: 'PENDING_REVIEW' as const,
  effectiveFrom: '2026-01-01T00:00:00', effectiveTo: null, formulaVersionId: 'CFV-1', roundingPolicyVersionId: 'CRP-1', configRevision: 3,
  createdBy: 11, createdAt: '2026-01-01T00:00:00', reviewedBy: null, reviewedAt: null, reviewComment: null, activatedBy: null, activatedAt: null,
  components: [{ componentId: 'C-1', componentType: 'DIRECT_EMISSION_FACTOR' as const, value: '0.5', unit: 'KG_CO2E/KWH', sourceVersionId: 'SV-1', evidenceReference: '参数证据' }], dataYear: null, accountingYear: null,
}

function workspace() {
  return {
    sources: ref([source]), factors: ref([factor]), energyItems: ref([]), energyUnits: ref([]), energyCompatibilities: ref([]),
    ruleOptions: ref({
      formulas: [
        { versionId: 'CFV-1', code: 'ELECTRICITY_DIRECT', versionNo: 1, algorithmCode: 'PURCHASED_ELECTRICITY_LOCATION_CO2E_V1', resultBasis: 'CO2E_DIRECT', usageNature: 'FORMAL', effectiveFrom: '2025-01-01T00:00:00', effectiveTo: null },
        { versionId: 'CFV-2', code: 'ELECTRICITY_DEVELOPMENT', versionNo: 1, algorithmCode: 'PURCHASED_ELECTRICITY_LOCATION_CO2E_V1', resultBasis: 'CO2E_DIRECT', usageNature: 'DEVELOPMENT_REFERENCE', effectiveFrom: '2025-01-01T00:00:00', effectiveTo: null },
        { versionId: 'CFV-3', code: 'HEAT', versionNo: 1, algorithmCode: 'PURCHASED_HEAT_CO2E_V1', resultBasis: 'CO2E_DIRECT', usageNature: 'FORMAL', effectiveFrom: '2025-01-01T00:00:00', effectiveTo: null },
        { versionId: 'CFV-4', code: 'FUTURE', versionNo: 1, algorithmCode: 'PURCHASED_ELECTRICITY_LOCATION_CO2E_V1', resultBasis: 'CO2E_DIRECT', usageNature: 'FORMAL', effectiveFrom: '2027-01-01T00:00:00', effectiveTo: null },
      ],
      roundingPolicies: [{ versionId: 'CRP-1', code: 'DECIMAL', versionNo: 1, totalScale: 6, intensityScale: 6, shareScale: 4, roundingMode: 'HALF_UP' }],
    }),
    approvedEnergyItems: ref([{ itemId: 'I-1', itemCode: 'ELEC', versionId: 'EV-1', versionNo: 1, itemName: '电力', compatibleCategory: 'ELECTRICITY', usageScopes: ['PURCHASED_ELECTRICITY'], status: 'APPROVED' }]),
    approvedEnergyUnits: ref([{ unitId: 'U-1', unitCode: 'KWH', versionId: 'UV-1', versionNo: 1, symbol: 'kWh', unitName: '千瓦时', dimensionCode: 'ENERGY', precision: 6, status: 'APPROVED' }]),
    approvedEnergyCompatibilities: ref([{ compatibilityId: 'C-1', versionId: 'CV-1', versionNo: 1, energyItemCode: 'ELEC', unitCode: 'KWH', allowed: true, status: 'APPROVED' }]),
    loading: ref(false), saving: ref(false), error: ref(''), load: vi.fn(),
    createSource: vi.fn().mockResolvedValue(true), createFactor: vi.fn().mockResolvedValue(true), reviewFactor: vi.fn().mockResolvedValue(true), activateFactor: vi.fn().mockResolvedValue(true), disableFactor: vi.fn().mockResolvedValue(true),
  }
}

let current = workspace()

function setInput(wrapper: ReturnType<typeof mount>, testId: string, value: string) {
  return wrapper.find(`[data-test="${testId}"]`).setValue(value)
}

function lastDirty(wrapper: ReturnType<typeof mount>) {
  const events = wrapper.emitted('dirty-change') ?? []
  return events[events.length - 1]
}

beforeEach(() => {
  current = workspace()
  vi.mocked(useCarbonFactors).mockReturnValue(current as never)
})

describe('排放因子维护面板', () => {
  it('只在来源必填信息齐全后提交来源版本，不预填专业年份或日期', async () => {
    const wrapper = mount(CarbonFactorPanel, { props: { buildingId: 'B-1' } })
    expect(wrapper.find('[data-test="source-create"]').attributes('disabled')).toBeDefined()

    await setInput(wrapper, 'source-code', 'SRC-2')
    await setInput(wrapper, 'source-name', '来源乙')
    await setInput(wrapper, 'source-publisher', '发布机构')
    await setInput(wrapper, 'source-document', '文件号')
    await wrapper.find('[data-test="source-usage"]').setValue('FORMAL')
    await setInput(wrapper, 'source-applicability', '仅用于当前核算')
    await setInput(wrapper, 'source-evidence', '归档证据')
    await wrapper.find('[data-test="source-create"]').trigger('click')

    expect(current.createSource).toHaveBeenCalledWith(expect.objectContaining({
      sourceCode: 'SRC-2', sourceName: '来源乙', publisher: '发布机构', documentReference: '文件号',
      publicationYear: null, publishedOn: null, usageNature: 'FORMAL',
    }))
    wrapper.unmount()
  })

  it('从已批准目录选择范围二直接因子，只提供与类别、使用性质和起始日期匹配的公式', async () => {
    const wrapper = mount(CarbonFactorPanel, { props: { buildingId: 'B-1' } })
    await setInput(wrapper, 'factor-code', 'ELEC-2026')
    await wrapper.find('[data-test="factor-scope"]').setValue('SCOPE_2')
    await wrapper.find('[data-test="factor-category"]').setValue('PURCHASED_ELECTRICITY_LOCATION')
    await wrapper.find('[data-test="factor-energy"]').setValue('ELEC')
    await wrapper.find('[data-test="factor-source"]').setValue('SV-1')
    await wrapper.find('[data-test="factor-applicability"]').setValue('NATIONAL')
    await wrapper.find('[data-test="factor-unit"]').setValue('KWH')
    await wrapper.find('[data-test="factor-usage"]').setValue('FORMAL')
    await setInput(wrapper, 'factor-effective-from', '2026-01-01T00:00')
    expect(wrapper.find('[data-test="factor-formula"]').text()).toContain('ELECTRICITY_DIRECT')
    expect(wrapper.find('[data-test="factor-formula"]').text()).not.toContain('ELECTRICITY_DEVELOPMENT')
    expect(wrapper.find('[data-test="factor-formula"]').text()).not.toContain('HEAT')
    expect(wrapper.find('[data-test="factor-formula"]').text()).not.toContain('FUTURE')
    await wrapper.find('[data-test="factor-formula"]').setValue('CFV-1')
    await wrapper.find('[data-test="factor-rounding"]').setValue('CRP-1')
    await setInput(wrapper, 'component-value-DIRECT_EMISSION_FACTOR', '0.123456789')
    await setInput(wrapper, 'component-evidence-DIRECT_EMISSION_FACTOR', '参数归档')
    await wrapper.find('[data-test="factor-create"]').trigger('click')

    expect(current.createFactor).toHaveBeenCalledWith(expect.objectContaining({
      scopeType: 'SCOPE_2', factorCategory: 'PURCHASED_ELECTRICITY_LOCATION', energyItemCode: 'ELEC',
      resultBasis: 'CO2E_DIRECT', gasCode: 'CO2E', gasCoverage: 'DIRECT_FACTOR_GAS_COVERAGE',
      sourceVersionId: 'SV-1', inputUnitCode: 'KWH',
      formulaVersionId: 'CFV-1', roundingPolicyVersionId: 'CRP-1',
      components: [expect.objectContaining({ componentType: 'DIRECT_EMISSION_FACTOR', value: '0.123456789', unit: 'KG_CO2E/KWH', sourceVersionId: 'SV-1' })],
    }))
    expect(wrapper.text()).not.toContain('MOBILE_COMBUSTION')
    wrapper.unmount()
  })

  it('审核需要独立的审核意见和显式结论，前端不直接改写因子状态', async () => {
    const wrapper = mount(CarbonFactorPanel, { props: { buildingId: 'B-1' } })
    await wrapper.find('[data-test="review-FV-1"]').trigger('click')
    expect(wrapper.find('[data-test="review-submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('前端不自行判定身份')

    await setInput(wrapper, 'review-comment', '证据及适用范围已独立核验')
    await wrapper.find('[data-test="review-submit"]').trigger('click')
    await flushPromises()

    expect(current.reviewFactor).toHaveBeenCalledWith('FV-1', {
      expectedRevision: 3, approved: true, reviewComment: '证据及适用范围已独立核验',
    })
    expect(current.factors.value[0]?.status).toBe('PENDING_REVIEW')
    wrapper.unmount()
  })

  it('仅将未提交草稿、审核弹窗或保存中状态作为切楼脏状态', async () => {
    const wrapper = mount(CarbonFactorPanel, { props: { buildingId: 'B-1' } })
    expect(lastDirty(wrapper)).toEqual([false])

    await setInput(wrapper, 'source-code', 'SRC-DIRTY')
    expect(lastDirty(wrapper)).toEqual([true])
    await setInput(wrapper, 'source-code', '')
    expect(lastDirty(wrapper)).toEqual([false])

    await setInput(wrapper, 'factor-code', 'FACTOR-DIRTY')
    expect(lastDirty(wrapper)).toEqual([true])
    await setInput(wrapper, 'factor-code', '')
    expect(lastDirty(wrapper)).toEqual([false])

    await wrapper.find('[data-test="review-FV-1"]').trigger('click')
    expect(lastDirty(wrapper)).toEqual([true])
    await wrapper.find('[data-test="review-cancel"]').trigger('click')
    expect(lastDirty(wrapper)).toEqual([false])

    current.saving.value = true
    await nextTick()
    expect(lastDirty(wrapper)).toEqual([true])
    current.saving.value = false
    await nextTick()
    expect(lastDirty(wrapper)).toEqual([false])
    wrapper.unmount()
  })
})
