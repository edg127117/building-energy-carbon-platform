import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import JointFlowConfigurationPanel from './JointFlowConfigurationPanel.vue'

const api = vi.hoisted(() => ({
  list: vi.fn(), get: vi.fn(), create: vi.fn(), save: vi.fn(), submit: vi.fn(), approve: vi.fn(), reject: vi.fn(), getSelection: vi.fn(), updateSelection: vi.fn(),
}))
const previewApi = vi.hoisted(() => vi.fn())
const parameterPackagesApi = vi.hoisted(() => ({ catalog: vi.fn() }))
const relationOptions = vi.hoisted(() => ({
  formalVersions: { value: [] }, loading: { value: false }, loadBuilding: vi.fn(),
}))

vi.mock('../api/joint-flow-configurations', () => ({ jointFlowConfigurationApi: api }))
vi.mock('../api/joint-flow', () => ({ previewJointFlow: previewApi }))
vi.mock('@/modules/energy-reporting/public', () => ({ parameterPackagesApi }))
vi.mock('../composables/use-relation-governance-options', () => ({ useRelationGovernanceOptions: () => relationOptions }))
vi.mock('@/shared/charts/ChartView.vue', () => ({ default: { props: ['option', 'accessibleLabel'], template: '<div data-test="preview-chart" />' } }))
vi.mock('@/shared/ui', () => ({
  ElAlert: { props: ['title'], template: '<div role="alert">{{ title }}</div>' },
  ElButton: { props: ['disabled', 'loading', 'type'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  ElCard: { template: '<section><slot name="header" /><slot /></section>' },
  ElDescriptions: { template: '<div><slot /></div>' }, ElDescriptionsItem: { template: '<div><slot /></div>' },
  ElEmpty: { props: ['description'], template: '<div>{{ description }}</div>' }, ElForm: { template: '<form><slot /></form>' },
  ElFormItem: { props: ['label'], template: '<label><span>{{ label }}</span><slot /></label>' },
  ElInput: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  ElInputNumber: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<input type="number" :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', Number($event.target.value))" />' },
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  ElSelect: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<select :value="modelValue ?? \'\'" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value || null)"><slot /></select>' },
  ElSkeleton: { template: '<div />' }, ElSpace: { template: '<div><slot /></div>' }, ElTable: { props: ['data'], template: '<div>{{ JSON.stringify(data) }}<slot /></div>' }, ElTableColumn: { template: '<div />' }, ElTag: { template: '<span><slot /></span>' },
}))

const configuration = {
  schemaVersion: 1 as const, startInclusive: '2026-01-01T00:00:00+08:00', endExclusive: '2027-01-01T00:00:00+08:00',
  relationVersionIds: [], mappings: [], conversions: [], floorReferences: [], sourceReference: '配置归档',
  dimensions: [{ key: 'ENERGY_TYPE' as const, title: '能源类型', source: 'ENERGY_ITEM' as const, nodes: [{ code: 'ELEC', name: '电力', order: 1 }] }],
}
const draft = { versionId: 'DRAFT-1', buildingId: 'B-1', versionNo: 1, revision: 3, status: 'DRAFT' as const, configuration, configurationHash: 'hash-1', createdBy: 'operator' }

beforeEach(() => {
  Object.values(api).forEach(mock => mock.mockReset())
  previewApi.mockReset()
  api.list.mockResolvedValue([draft])
  api.get.mockResolvedValue(draft)
  api.getSelection.mockResolvedValue({ buildingId: 'B-1', versionId: null, revision: 0 })
  parameterPackagesApi.catalog.mockResolvedValue({ existingVersions: [] })
  relationOptions.loadBuilding.mockReset()
})

describe('联合流展示配置面板', () => {
  it('以草稿版本调用预览，并只展示后端返回的冻结叶项计数', async () => {
    previewApi.mockResolvedValue({
      buildingId: 'B-1', periodType: 'MONTH', period: '2026-09', throughMonth: null,
      classificationBasis: 'DRAFT_PREVIEW', configurationVersionId: 'DRAFT-1', configurationHash: 'preview-hash',
      coverage: { totalAtoms: 8, allocatedAtoms: 6, dimensions: [{ key: 'ENERGY_TYPE', allocatedAtoms: 6, unallocatedAtoms: 2 }] },
      dimensions: [{ key: 'ENERGY_TYPE', title: '能源类型', source: 'ENERGY_ITEM', nodes: [{ code: 'ELEC', name: '电力', order: 1 }] }],
      carbon: {
        state: 'READY', unit: 'tCO2e', total: '10.0', unallocated: '2.0',
        nodes: [{ id: 'ELEC', code: 'ELEC', name: '电力', depth: 0, order: 1, value: '10.0' }, { id: 'B-1', code: 'B-1', name: '建筑', depth: 1, order: 1, value: '10.0' }],
        links: [{ source: 'ELEC', target: 'B-1', value: '10.0' }],
      },
      energy: {
        state: 'READY', unit: 'tce', total: '4.0', unallocated: '1.0',
        nodes: [{ id: 'ELEC', code: 'ELEC', name: '电力', depth: 0, order: 1, value: '4.0' }, { id: 'B-1', code: 'B-1', name: '建筑', depth: 1, order: 1, value: '4.0' }],
        links: [{ source: 'ELEC', target: 'B-1', value: '4.0' }],
      }, warnings: [],
    })
    const wrapper = mount(JointFlowConfigurationPanel, { props: { buildingId: 'B-1' } })
    await flushPromises()

    await wrapper.find('[data-test="joint-flow-preview-period"]').setValue('2026-09')
    await wrapper.find('[data-test="joint-flow-preview"]').trigger('click')
    await flushPromises()

    expect(previewApi).toHaveBeenCalledWith({ buildingId: 'B-1', periodType: 'MONTH', period: '2026-09', configurationVersionId: 'DRAFT-1' }, expect.any(AbortSignal))
    expect(wrapper.text()).toContain('8')
    expect(wrapper.text()).toContain('6')
    expect(wrapper.text()).toContain('2')
    expect(wrapper.findAll('[data-test="preview-chart"]')).toHaveLength(2)
    expect(wrapper.emitted('dirty-change')?.at(-1)).toEqual([false])
    wrapper.unmount()
  })

  it('切换预览期间后作废迟到的旧响应，不能把旧草稿图落到新表单', async () => {
    let resolvePreview: (result: unknown) => void = () => undefined
    previewApi.mockImplementation(() => new Promise(resolve => { resolvePreview = resolve }))
    const wrapper = mount(JointFlowConfigurationPanel, { props: { buildingId: 'B-1' } })
    await flushPromises()

    await wrapper.find('[data-test="joint-flow-preview-period"]').setValue('2026-09')
    await wrapper.find('[data-test="joint-flow-preview"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="joint-flow-preview-period"]').setValue('2026-10')
    await flushPromises()
    resolvePreview({
      buildingId: 'B-1', periodType: 'MONTH', period: '2026-09', throughMonth: null,
      classificationBasis: 'DRAFT_PREVIEW', configurationVersionId: 'DRAFT-1', configurationHash: 'preview-hash',
    })
    await flushPromises()

    expect(wrapper.findAll('[data-test="preview-chart"]')).toHaveLength(0)
    expect(wrapper.find('[data-test="joint-flow-preview"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('拒绝期间不匹配的预览响应', async () => {
    previewApi.mockResolvedValue({
      buildingId: 'B-1', periodType: 'MONTH', period: '2026-10', throughMonth: null,
      classificationBasis: 'DRAFT_PREVIEW', configurationVersionId: 'DRAFT-1', configurationHash: 'preview-hash',
    })
    const wrapper = mount(JointFlowConfigurationPanel, { props: { buildingId: 'B-1' } })
    await flushPromises()

    await wrapper.find('[data-test="joint-flow-preview-period"]').setValue('2026-09')
    await wrapper.find('[data-test="joint-flow-preview"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('预览响应不是当前草稿的 DRAFT_PREVIEW')
    expect(wrapper.findAll('[data-test="preview-chart"]')).toHaveLength(0)
    wrapper.unmount()
  })

  it('切换建筑后忽略迟到的旧建筑创建响应', async () => {
    const draftB2 = { ...draft, versionId: 'DRAFT-B2', buildingId: 'B-2', versionNo: 2 }
    let resolveCreate: (version: typeof draft) => void = () => undefined
    api.list.mockImplementation((buildingId: string) => Promise.resolve(buildingId === 'B-2' ? [draftB2] : [draft]))
    api.get.mockImplementation((buildingId: string) => Promise.resolve(buildingId === 'B-2' ? draftB2 : draft))
    api.getSelection.mockImplementation((buildingId: string) => Promise.resolve({ buildingId, versionId: null, revision: 0 }))
    api.create.mockImplementation(() => new Promise<typeof draft>(resolve => { resolveCreate = resolve }))
    const wrapper = mount(JointFlowConfigurationPanel, { props: { buildingId: 'B-1' } })
    await flushPromises()

    await wrapper.find('[data-test="joint-flow-reason"]').setValue('建立旧建筑草稿')
    await wrapper.find('[data-test="joint-flow-create"]').trigger('click')
    await flushPromises()
    await wrapper.setProps({ buildingId: 'B-2' })
    await flushPromises()
    resolveCreate({ ...draft, versionId: 'DRAFT-OLD', versionNo: 9 })
    await flushPromises()

    const state = (wrapper.vm as unknown as {
      $: { setupState: { selectedVersionId: string; versions: Array<{ versionId: string }> } }
    }).$.setupState
    expect(state.selectedVersionId).toBe('DRAFT-B2')
    expect(state.versions.map(version => version.versionId)).toEqual(['DRAFT-B2'])
    wrapper.unmount()
  })

  it('草稿有本地修改时拒绝直接预览，避免把未保存内容当成后端结果', async () => {
    const wrapper = mount(JointFlowConfigurationPanel, { props: { buildingId: 'B-1' } })
    await flushPromises()

    await wrapper.find('[data-test="joint-flow-source-reference"]').setValue('未保存修改')
    await wrapper.find('[data-test="joint-flow-preview-period"]').setValue('2026-09')
    await flushPromises()

    expect(wrapper.find('[data-test="joint-flow-preview"]').attributes('disabled')).toBeDefined()
    expect(previewApi).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('折标参数只提供已批准的 CONVERSION_PARAMETER，排除公式和碳因子版本', async () => {
    parameterPackagesApi.catalog.mockResolvedValue({
      existingVersions: [
        { domain: 'CONVERSION_PARAMETER', code: 'STD_COAL', versionId: 'CONV-OK', status: 'APPROVED' },
        { domain: 'FORMULA', code: 'COAL_FORMULA', versionId: 'FORMULA-OK', status: 'APPROVED' },
        { domain: 'CARBON_FACTOR', code: 'GRID', versionId: 'CARBON-OK', status: 'APPROVED' },
        { domain: 'CONVERSION_PARAMETER', code: 'DRAFT', versionId: 'CONV-DRAFT', status: 'DRAFT' },
      ],
    })
    const wrapper = mount(JointFlowConfigurationPanel, { props: { buildingId: 'B-1' } })
    await flushPromises()

    const options = (wrapper.vm as unknown as {
      $: { setupState: { parameterVersionOptions: Array<{ versionId: string }> } }
    }).$.setupState.parameterVersionOptions
    expect(options.map(option => option.versionId)).toEqual(['CONV-OK'])
    wrapper.unmount()
  })
})
