import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import RelationStagingPanel from './RelationStagingPanel.vue'
import { relationStagingApi, type StagedMutation } from '../api/relation-staging'
vi.mock('../api/relation-staging', () => ({ relationStagingApi: { options: vi.fn(), list: vi.fn(), equipment: vi.fn(), point: vi.fn(), remove: vi.fn(), assignment: vi.fn() } }))
vi.mock('@/shared/ui', () => ({
  ElAlert: { props: ['title'], template: '<div role="alert">{{ title }}</div>' },
  ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
  ElCard: { template: '<section><slot /></section>' }, ElEmpty: { template: '<div />' },
  ElForm: { template: '<form><slot /></form>' }, ElFormItem: { props: ['label'], template: '<label>{{ label }}<slot /></label>' },
  ElInput: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  ElSelect: { props: ['modelValue'], emits: ['update:modelValue'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option value="" /><slot /></select>' },
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  ElSpace: { template: '<div><slot /></div>' }, ElTable: { template: '<div />' }, ElTableColumn: { template: '<div />' },
}))
const props = { buildingId: 'B', versionId: 'V', revision: 3, editable: true, nodes: [] }
const catalogue = { equipmentTypes: [{ id: 'TYPE', name: '通用类型', code: 'M' }], products: [], namingRules: [] }
describe('治理后新档案', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.mocked(relationStagingApi.options).mockResolvedValue(catalogue); vi.mocked(relationStagingApi.list).mockResolvedValue({ versionId: 'V', revision: 3, assets: [] }) })
  it('未知空间和系统按空值提交，不猜归属；服务端失败保留输入', async () => {
    vi.mocked(relationStagingApi.equipment).mockRejectedValue(new Error('修订冲突'))
    const wrapper = mount(RelationStagingPanel, { props }); await flushPromises()
    await wrapper.findAll('input')[0]!.setValue('新表计')
    await wrapper.findAll('select')[0]!.setValue('TYPE')
    await wrapper.findAll('input').at(-1)!.setValue('档案依据')
    await wrapper.findAll('button').find(b => b.text() === '加入关系草稿')!.trigger('click'); await flushPromises()
    expect(relationStagingApi.equipment).toHaveBeenCalledWith('B', 'V', expect.objectContaining({ expectedRevision: 3, name: '新表计', spaceId: null, systemGroupId: null }))
    expect(wrapper.findAll('input')[0]!.element.value).toBe('新表计')
    expect(wrapper.emitted('dirty')?.at(-1)).toEqual([true])
    wrapper.unmount()
  })
  it('切换建筑后忽略旧版本迟到来源，不显示旧楼新档案', async () => {
    let resolve!: (value: StagedMutation) => void
    vi.mocked(relationStagingApi.list).mockImplementationOnce(() => new Promise(done => { resolve = done }))
    const wrapper = mount(RelationStagingPanel, { props })
    await wrapper.setProps({ buildingId: 'B2', versionId: 'V2' }); await flushPromises()
    resolve({ versionId: 'V', revision: 4, assets: [{ stagedId: 'S', objectType: 'EQUIPMENT', objectId: 'E', nodeId: 'N', code: 'OLD', name: '旧楼设备', status: 'STAGED', definitionJson: '{}', spaceId: null, systemGroupId: null, equipmentId: null }] })
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧楼设备')
    expect(relationStagingApi.list).toHaveBeenLastCalledWith('B2', 'V2')
    wrapper.unmount()
  })
  it('保留未保存输入原来的修订，不因父页面刷新而采用较新的修订', async () => {
    vi.mocked(relationStagingApi.equipment).mockRejectedValue(new Error('修订冲突'))
    const wrapper = mount(RelationStagingPanel, { props }); await flushPromises()
    await wrapper.findAll('input')[0]!.setValue('待补档案')
    await wrapper.findAll('select')[0]!.setValue('TYPE')
    await wrapper.findAll('input').at(-1)!.setValue('档案依据')
    await wrapper.setProps({ revision: 4 }); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '加入关系草稿')!.trigger('click'); await flushPromises()
    expect(relationStagingApi.equipment).toHaveBeenCalledWith('B', 'V', expect.objectContaining({ expectedRevision: 3 }))
    expect(relationStagingApi.list).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})
