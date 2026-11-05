import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const routeState = vi.hoisted(() => ({ path: '/configuration/rules/alarmRules', query: { buildingId: 'B1' } as Record<string, string>, replace: vi.fn() }))
const session = vi.hoisted(() => ({ user: { id: 8, roles: ['ENERGY_MANAGER'] as string[] } }))
const api = vi.hoisted(() => ({ buildings: vi.fn(), rules: vi.fn(), ruleHistory: vi.fn(), updateRule: vi.fn(), ruleOptions: vi.fn(), createRule: vi.fn(), templates:vi.fn(), dictionaries:vi.fn(), bindDictionary:vi.fn(), createDictionary:vi.fn(), updateDictionary:vi.fn(), applyDictionary:vi.fn() }))

vi.mock('vue-router', () => ({ useRoute: () => routeState, useRouter: () => ({ replace: routeState.replace }) }))
vi.mock('@/modules/auth/public', async importOriginal => ({ ...(await importOriginal<typeof import('@/modules/auth/public')>()), useSession: () => session }))
vi.mock('../api/alarms', () => ({ alarmsApi: api }))

import AlarmRulesPage from './AlarmRulesPage.vue'

const ruleRows = [
  { type: 'OFFLINE', enabled: true, parameter: 3, revision: 2, updatedAt: 1_800_000_000_000, updatedBy: 8 },
  { type: 'FAULT', enabled: true, parameter: 1, revision: 1, updatedAt: null, updatedBy: null },
  { type: 'QUOTA', enabled: true, parameter: 85, revision: 4, updatedAt: 1_800_000_000_000, updatedBy: 8 },
]

describe('AlarmRulesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routeState.query = { buildingId: 'B1' }
    session.user = { id: 8, roles: ['ENERGY_MANAGER'] }
    api.buildings.mockResolvedValue([{ buildingId: 'B1', buildingName: '研发楼' }])
    api.ruleOptions.mockResolvedValue({ points: [{ value: 'P1', label: '电表 · 有功功率', equipmentId: 'E1', unit: 'kW', dataType: 'ANALOG', spaceId: 'F1', deviceCategory: 'METER' }, { value: 'P2', label: '电表 · 开关机状态', equipmentId: 'E1', unit: '', dataType: 'DIGITAL', spaceId: 'F1', deviceCategory: 'METER' }], spaces: [{ value: 'F1', label: '一楼' }], deviceCategories: [{ value: 'METER', label: '电表' }] })
    api.rules.mockResolvedValue(ruleRows)
    api.templates.mockResolvedValue([{code:'QUOTA_PROGRESS',label:'配额进度',periods:['MONTH','YEAR'],unit:'%',requiredFields:['threshold'],professionalInputs:[]}])
    api.dictionaries.mockResolvedValue([])
    api.ruleHistory.mockResolvedValue([{ revision: 2, enabled: true, parameter_value: 3, changed_at: 1_800_000_000_000, changed_by: 8 }])
    api.updateRule.mockResolvedValue(undefined)
  })

  it('shows Chinese device conditions, edits the parameter, and reloads after save', async () => {
    const wrapper = mount(AlarmRulesPage)
    await flushPromises()
    expect(wrapper.text()).toContain('连续 3 个应报周期缺报时触发')
    expect(wrapper.text()).toContain('按设备故障状态触发')

    const edit = wrapper.findAll('button').find(button => button.text().trim() === '编辑')
    expect(edit).toBeDefined()
    await edit!.trigger('click')
    await flushPromises()
    wrapper.findAllComponents({ name: 'ElInputNumber' })[0].vm.$emit('update:modelValue', 4)
    await flushPromises()
    const save = wrapper.findAll('button').find(button => button.text().trim() === '保存并生效')
    await save!.trigger('click')
    await flushPromises()
    expect(api.updateRule).toHaveBeenCalledWith('B1', expect.objectContaining({ type: 'OFFLINE', enabled: true, parameter: 4, revision: 2 }))
    expect(api.rules).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('round trips nested trigger and independent recovery with a primary device and collection timing', async () => {
    const advanced={primaryEquipmentId:'E1',timing:{expectedIntervalSeconds:600,allowedDelaySeconds:30,alignmentSeconds:20,staleAfterSeconds:900},
      trigger:{logic:'ALL',children:[{condition:{pointId:'P1',operator:'GT',value:5}},{logic:'ANY',children:[{condition:{pointId:'P2',operator:'EQ',value:7,states:[{value:7,label:'关机'}]}}]}]},
      recovery:{logic:'ALL',children:[{condition:{pointId:'P1',operator:'LT',value:3}}]}}
    api.rules.mockResolvedValue([{...ruleRows[0],id:'advanced',type:'CONDITION',name:'关机仍耗电',parameter:1,classification:'FAULT',qualityLevel:'Q0',advanced}])
    const wrapper=mount(AlarmRulesPage)
    await flushPromises()
    await wrapper.findAll('button').find(button=>button.text()==='编辑')!.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.tree-leaf')).toHaveLength(3)
    expect(wrapper.text()).toContain('恢复条件（独立回差）')
    expect(wrapper.text()).toContain('关机')
    await wrapper.get('input[aria-label="恢复正常周期数"]').setValue('3')
    expect(wrapper.get('.condition-preview').text()).toContain('连续 3 个周期')
    await wrapper.get('footer button.el-button--primary').trigger('click')
    await flushPromises()
    expect(api.updateRule).toHaveBeenCalledWith('B1',expect.objectContaining({advanced,recoveryCycles:3}))
    wrapper.unmount()
  })

  it('uses the registered template catalog for extra numeric fields and supported periods', async () => {
    api.templates.mockResolvedValue([{code:'CUSTOM',label:'已注册模型',periods:['YEAR'],unit:'%',requiredFields:['parameters.factor'],professionalInputs:['须提供有效面积版本'],parameterLabels:{factor:'换算系数'}}])
    api.rules.mockResolvedValue([{...ruleRows[2],id:'custom',type:'EMISSION',name:'模型规则',parameter:1,advanced:{emission:{template:'CUSTOM',period:'YEAR',threshold:90,recoveryThreshold:80,parameters:{factor:2}}}}])
    const wrapper=mount(AlarmRulesPage)
    await flushPromises()
    await wrapper.findAll('[role="tab"]').find(tab=>tab.text().includes('排放预警规则'))!.trigger('click')
    await wrapper.findAll('button').find(button=>button.text()==='编辑')!.trigger('click')
    await flushPromises()
    expect(wrapper.get('.emission-form').text()).toContain('须提供有效面积版本')
    expect(wrapper.get('.emission-form').text()).toContain('换算系数')
    await wrapper.get('footer button.el-button--primary').trigger('click')
    await flushPromises()
    expect(api.updateRule).toHaveBeenCalledWith('B1',expect.objectContaining({advanced:expect.objectContaining({emission:expect.objectContaining({parameters:{factor:2}})})}))
    wrapper.unmount()
  })

  it('loads and presents read-only rule history in the selected category', async () => {
    const wrapper = mount(AlarmRulesPage)
    await flushPromises()
    const records = wrapper.findAll('button').find(button => button.text().trim() === '记录')
    await records!.trigger('click')
    await flushPromises()
    expect(api.ruleHistory).toHaveBeenCalledWith('B1', 'OFFLINE')
    expect(wrapper.text()).toContain('规则版本 2')
    expect(wrapper.text()).toContain('修改人：8')

    const quotaView = wrapper.findAll('[role="tab"]').find(tab => tab.text().includes('排放预警规则'))
    await quotaView!.trigger('click')
    expect(wrapper.text()).toContain('月度配额使用率达到 85% 时预警')
    wrapper.unmount()
  })

  it('shows rule-specific classification and provides a fault editor', async () => {
    const wrapper = mount(AlarmRulesPage)
    await flushPromises()
    expect(wrapper.find('.rule-header').text()).toContain('适用设备与场景质量要求')
    expect(wrapper.text()).toContain('研发楼')
    expect(wrapper.text()).toContain('已启用监测设备')
    expect(wrapper.text()).toContain('重要')
    const faultRow = wrapper.findAll('.rule-row').find(row => row.text().includes('设备故障'))
    expect(faultRow?.findAll('button').map(button => button.text())).toEqual(['编辑', '记录'])
    wrapper.unmount()
  })
  it('creates a named scoped rule and filters the independently returned instances', async () => {
    const wrapper = mount(AlarmRulesPage)
    await flushPromises()
    await wrapper.get('.rule-filters button.el-button').trigger('click')
    const drawer = wrapper.get('[role="dialog"]')
    await drawer.get('input[aria-label="规则名称"]').setValue('一层电表断联')
    const selects = drawer.findAllComponents({ name: 'ElSelect' })
    selects[1].vm.$emit('change', 'F1')
    selects[2].vm.$emit('change', 'METER')
    await drawer.get('input[value="Q0"]').setValue(true)
    await drawer.get('button.URGENT').trigger('click')
    const created = { ...ruleRows[0], id: 'r1', name: '一层电表断联', severity: 'URGENT', spaceId: 'F1', deviceCategory: 'METER' }
    api.rules.mockResolvedValue([created, ...ruleRows])
    await drawer.get('footer button.el-button--primary').trigger('click')
    await flushPromises()
    expect(api.createRule).toHaveBeenCalledWith('B1', expect.objectContaining({ name: created.name, qualityLevel: 'Q0', severity: 'URGENT', spaceId: 'F1', deviceCategory: 'METER' }))
    expect(wrapper.findAll('.rule-row')).toHaveLength(3)
    wrapper.get('.rule-filters').findAllComponents({ name: 'ElSelect' })[1].vm.$emit('update:modelValue', 'METER')
    await flushPromises()
    expect(wrapper.findAll('.rule-row')).toHaveLength(1)
    expect(wrapper.get('.rule-row').text()).toContain('一层电表断联')
    wrapper.unmount()
  })

  it('saves an ALL condition group with distinct points and duration settings', async () => {
    const wrapper = mount(AlarmRulesPage)
    await flushPromises()
    await wrapper.get('.rule-filters button.el-button').trigger('click')
    const drawer = wrapper.get('[role="dialog"]')
    drawer.findAllComponents({ name: 'ElSelect' })[0].vm.$emit('change', 'CONDITION')
    await drawer.get('input[aria-label="规则名称"]').setValue('关机仍耗电')
    await flushPromises()
    drawer.get('.condition-row').findAllComponents({ name: 'ElSelect' })[0].vm.$emit('update:modelValue', 'P1')
    drawer.get('.condition-row').findAllComponents({ name: 'ElInputNumber' })[0].vm.$emit('update:modelValue', 5)
    await drawer.get('.add-condition').trigger('click')
    const second = drawer.findAll('.condition-row')[1]
    second.findAllComponents({ name: 'ElSelect' })[0].vm.$emit('update:modelValue', 'P2')
    second.findAllComponents({ name: 'ElSelect' })[1].vm.$emit('update:modelValue', 'EQ')
    await flushPromises()
    await second.get('button[aria-label="设置状态选项"]').trigger('click')
    const stateRows = drawer.findAll('.state-option-row')
    stateRows[0].findComponent({ name: 'ElInputNumber' }).vm.$emit('update:modelValue', 7)
    stateRows[0].findAllComponents({ name: 'ElInput' }).at(-1)!.vm.$emit('update:modelValue', '关机')
    stateRows[1].findComponent({ name: 'ElInputNumber' }).vm.$emit('update:modelValue', 9)
    stateRows[1].findAllComponents({ name: 'ElInput' }).at(-1)!.vm.$emit('update:modelValue', '开机')
    await flushPromises()
    await drawer.get('.state-editor').findAll('button').find(button => button.text() === '应用状态选项')!.trigger('click')
    expect(drawer.get('footer button.el-button--primary').attributes('disabled')).toBeDefined()
    drawer.get('.state-value').findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 7)
    await drawer.get('input[aria-label="条件连续成立"]').setValue('15')
    await drawer.get('input[aria-label="连续恢复正常"]').setValue('10')
    await flushPromises()
    await drawer.get('footer button.el-button--primary').trigger('click')
    await flushPromises()
    expect(api.createRule).toHaveBeenCalledWith('B1', expect.objectContaining({
      type: 'CONDITION', classification: 'VALUE', conditionLogic: 'ALL', triggerMinutes: 15, recoveryMinutes: 10,
      conditions: [expect.objectContaining({ pointId: 'P1', operator: 'GT', value: 5 }), expect.objectContaining({ pointId: 'P2', operator: 'EQ', value: 7, states: [{ value: 7, label: '关机' }, { value: 9, label: '开机' }] })],
    }))
    wrapper.unmount()
  })

  it('keeps state labels with their rule revision and cancels mapping edits without mutating loaded data', async () => {
    const original = { ...ruleRows[0], type: 'CONDITION', name: '状态规则', classification: 'FAULT', recoveryCycles: 1, triggerMinutes: 0, recoveryMinutes: 0, conditionLogic: 'ALL',
      conditions: [{ pointId: 'P2', operator: 'EQ', value: 7, states: [{ value: 7, label: '关机' }, { value: 9, label: '开机' }] }] }
    api.rules.mockResolvedValue([original])
    const wrapper = mount(AlarmRulesPage)
    await flushPromises()
    expect(wrapper.get('.trigger-value').text()).toContain('关机')
    await wrapper.findAll('button').find(button => button.text().trim() === '编辑')!.trigger('click')
    await wrapper.get('button[aria-label="设置状态选项"]').trigger('click')
    const states = wrapper.findAll('.state-option-row')
    states[0].findAllComponents({ name: 'ElInput' }).at(-1)!.vm.$emit('update:modelValue', '停机')
    states[1].findComponent({ name: 'ElInputNumber' }).vm.$emit('update:modelValue', 7)
    await flushPromises()
    expect(wrapper.get('.state-editor').findAllComponents({ name: 'ElButton' }).at(-1)!.props('disabled')).toBe(true)
    await wrapper.get('.state-editor').findAll('button').find(button => button.text() === '取消')!.trigger('click')
    await wrapper.get('.drawer-heading button').trigger('click')
    expect(original.conditions[0].states[0].label).toBe('关机')
    expect(wrapper.get('.trigger-value').text()).toContain('关机')
    wrapper.unmount()
  })

})
