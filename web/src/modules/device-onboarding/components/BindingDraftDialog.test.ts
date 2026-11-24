import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElButton, ElCheckbox, ElInput, ElRadioGroup, ElSelect } from '@/shared/ui'
import BindingDraftDialog from './BindingDraftDialog.vue'

const assets = {
  buildingOptions: ref<Array<{ label: string; value: string }>>([]),
  scopeSpaces: ref<Array<{ spaceId: string; parentSpaceId: string | null; spaceName: string; children: never[] }>>([]),
  scopeSystemGroups: ref<Array<{ systemGroupId: string; systemName: string }>>([]),
  points: ref([]),
  equipment: ref({ page: 1, size: 100, total: 0, items: [] as Array<{ equipmentId: string; equipmentName: string }> }),
  scopeLoading: ref(false), buildingsError: ref(null), scopeError: ref(null), equipmentError: ref(null),
  ensureBuildingOptions: vi.fn().mockResolvedValue(undefined), loadScope: vi.fn().mockResolvedValue(undefined),
  selectEquipment: vi.fn().mockResolvedValue(undefined), setEquipmentQuery: vi.fn().mockResolvedValue(undefined),
}

vi.mock('@/modules/asset-management/public', async importOriginal => ({
  ...(await importOriginal()),
  useAssetManagement: () => assets,
}))

const product = {
  productId: 'P1', productCode: 'METER', productName: '电表', manufacturer: null, model: null,
  equipmentTypeCode: 'METER', expectedProfileCode: 'V1', identityType: 'SN', status: 'ENABLED', pointCount: 1,
  updateTime: 1, createTime: 1, allowedActions: [],
  points: [{ metricCode: 'power', pointNameTemplate: '有功功率', suffixCode: 'P', dataType: 'ANALOG' as const, unit: 'kW', minValue: null, maxValue: null, forCalc: true, required: true, sortOrder: 0, enabled: true }],
}
const pending = { pendingId: 'D1', identityType: 'SN', maskedIdentityValue: 'x', identityValue: 'SN1008', profileCode: 'V1', lastProfileVersion: 1, status: 'DISCOVERED', identityStatus: 'UNBOUND' as const, reportCount: 1, firstSeenTime: 1, lastSeenTime: 1, sampleTruncated: false, boundIdentityId: null, latestEventTime: 1, latestTimeSource: null, latestMetrics: {}, allowedActions: [] }

describe('绑定准备弹窗', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    assets.buildingOptions.value = []
    assets.scopeSpaces.value = []
    assets.scopeSystemGroups.value = []
    assets.equipment.value = { page: 1, size: 100, total: 0, items: [] }
  })

  it('去掉保守性前置说明卡片，直接保留提交错误反馈', async () => {
    const wrapper = mount(BindingDraftDialog, { props: { open: true, pending, product, products: [product], productTotal: 2, submitError: '请先启用数据源' } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('绑定前需要准备什么')
    expect(wrapper.text()).toContain('请先启用数据源')
    wrapper.unmount()
  })

  it('弹窗打开时按当前产品初始化模板，产品切换期间不保留旧模板', async () => {
    const wrapper = mount(BindingDraftDialog, { props: { open: false, pending, product, products: [product], productTotal: 2 } })
    await wrapper.setProps({ open: true })
    await flushPromises()
    expect(wrapper.emitted('close')).toBeUndefined()
    expect(wrapper.text()).toContain('自动建点 · 1')

    const productSelect = wrapper.findAllComponents(ElSelect)[0]!
    productSelect.vm.$emit('update:modelValue', 'P2')
    productSelect.vm.$emit('change', 'P2')
    await nextTick()
    expect(wrapper.text()).not.toContain('自动建点 · 1')
    expect(wrapper.emitted('product-change')?.at(-1)).toEqual(['P2'])
    wrapper.unmount()
  })

  it('唯一匹配产品、建筑和分组自动带出，选空间自动建议设备名并默认提交绑定并启用', async () => {
    assets.buildingOptions.value = [{ label: '研发楼 A 座', value: 'B1' }]
    assets.scopeSpaces.value = [{ spaceId: 'S1', parentSpaceId: null, spaceName: '3F 强电间', children: [] }]
    assets.scopeSystemGroups.value = [{ systemGroupId: 'G1', systemName: '照明与动力计量' }]

    const wrapper = mount(BindingDraftDialog, { props: { open: true, pending, product, products: [product], productTotal: 1, namingRules: [] } })
    await flushPromises()

    expect(wrapper.text()).toContain('研发楼 A 座')
    expect(wrapper.text()).toContain('照明与动力计量')
    expect(wrapper.text()).toContain('自动建点 · 1')
    expect(wrapper.text()).toContain('提交绑定并启用')

    const spaceSelect = wrapper.findAllComponents(ElSelect)[0]!
    spaceSelect.vm.$emit('update:modelValue', 'S1')
    spaceSelect.vm.$emit('change', 'S1')
    await flushPromises()

    const nameInput = wrapper.findAllComponents(ElInput)[0]!
    expect(nameInput.props('modelValue')).toBe('3F 强电间-电表')

    await wrapper.findAllComponents(ElButton).find(button => button.text() === '提交绑定并启用')!.trigger('click')
    expect(wrapper.emitted('submit')?.at(-1)?.[0]).toEqual(expect.objectContaining({
      productId: 'P1',
      buildingId: 'B1',
      spaceId: 'S1',
      systemGroupId: 'G1',
      newEquipment: { equipmentName: '3F 强电间-电表', manufacturer: null },
      autoCreatePoints: true,
      activateOnApprove: true,
      pointBindings: [],
    }))
    wrapper.unmount()
  })

  it('复用已有档案也默认支持自动建点，取消立即启用后切换为仅绑定', async () => {
    assets.buildingOptions.value = [{ label: '研发楼 A 座', value: 'B1' }]
    assets.scopeSpaces.value = [{ spaceId: 'S1', parentSpaceId: null, spaceName: '3F 强电间', children: [] }]
    assets.scopeSystemGroups.value = [{ systemGroupId: 'G1', systemName: '照明与动力计量' }]
    assets.equipment.value = { page: 1, size: 100, total: 1, items: [{ equipmentId: 'EQ-1', equipmentName: '3F 照明总表' }] }

    const wrapper = mount(BindingDraftDialog, { props: { open: true, pending, product, products: [product], productTotal: 1 } })
    await flushPromises()

    const spaceSelect = wrapper.findAllComponents(ElSelect)[0]!
    spaceSelect.vm.$emit('update:modelValue', 'S1')
    spaceSelect.vm.$emit('change', 'S1')
    await flushPromises()

    const targetMode = wrapper.findAllComponents(ElRadioGroup)[0]!
    targetMode.vm.$emit('update:modelValue', 'existing')
    targetMode.vm.$emit('change', 'existing')
    await nextTick()

    const equipmentSelect = wrapper.findAllComponents(ElSelect).at(-1)!
    equipmentSelect.vm.$emit('update:modelValue', 'EQ-1')
    equipmentSelect.vm.$emit('change', 'EQ-1')
    await nextTick()

    const activateCheckbox = wrapper.findAllComponents(ElCheckbox).at(-1)!
    activateCheckbox.vm.$emit('update:modelValue', false)
    await nextTick()
    expect(wrapper.text()).toContain('提交仅绑定')

    await wrapper.findAllComponents(ElButton).find(button => button.text() === '提交仅绑定')!.trigger('click')
    expect(wrapper.emitted('submit')?.at(-1)?.[0]).toEqual(expect.objectContaining({
      existingEquipmentId: 'EQ-1',
      newEquipment: null,
      autoCreatePoints: true,
      activateOnApprove: false,
      pointBindings: [],
    }))

    await wrapper.setProps({ automaticMappingRejected: true })
    expect(wrapper.find('.binding-points').exists()).toBe(true)
    expect(wrapper.find('.automatic-points').exists()).toBe(false)
    expect(equipmentSelect.props('modelValue')).toBe('EQ-1')
    wrapper.unmount()
  })

  it('区分零测点状态产品和需要HTTP数值来源的温度模板，且零测点状态产品可直接提交', async () => {
    const stateProduct = { ...product, pointCount: 0, points: [] }
    const bindingOptions = {
      buildingId: 'BLD001', buildingName: '创新港大楼',
      spaces: [{ spaceId: 'S1', parentSpaceId: null, spaceName: '屋顶机房' }],
      systems: [{ systemGroupId: 'G1', systemName: '空调系统' }],
      equipmentPage: 1, equipmentSize: 20, equipmentTotal: 0, equipment: [],
    }
    const stateWrapper = mount(BindingDraftDialog, {
      props: {
        open: true,
        pending: { ...pending, identityType: 'DAIKIN_UNIT', profileCode: 'DAIKIN_OUTDOOR_V2' },
        product: stateProduct,
        products: [stateProduct],
        productTotal: 1,
        bindingOptions,
        allowEmptyPoints: true,
      },
    })
    await flushPromises()
    expect(stateWrapper.text()).not.toContain('温度数值来源')
    const spaceSelect = stateWrapper.findAllComponents(ElSelect)[0]!
    spaceSelect.vm.$emit('update:modelValue', 'S1')
    spaceSelect.vm.$emit('change', 'S1')
    await nextTick()
    await stateWrapper.findAllComponents(ElButton).find(button => button.text() === '提交绑定并启用')!.trigger('click')
    expect(stateWrapper.emitted('submit')?.at(-1)?.[0]).toEqual(expect.objectContaining({
      productId: 'P1',
      buildingId: 'BLD001',
      spaceId: 'S1',
      systemGroupId: 'G1',
      numericSourceId: null,
      pointBindings: [],
    }))

    const temperatureWrapper = mount(BindingDraftDialog, {
      props: {
        open: true, pending: { ...pending, identityType: 'DAIKIN_UNIT' }, product, products: [product], allowEmptyPoints: true,
        numericSources: [{ sourceId: 'HTTP-1', sourceCode: 'DAIKIN_TEMP', sourceName: '厂家温度来源' }],
      },
    })
    await flushPromises()
    expect(temperatureWrapper.text()).toContain('温度数值来源')
    stateWrapper.unmount()
    temperatureWrapper.unmount()
  })

  it('大金设备默认新建台账且无兼容产品时给出警告', async () => {
    const wrapper = mount(BindingDraftDialog, {
      props: { open: true, pending: { ...pending, identityType: 'DAIKIN_UNIT' }, products: [], productTotal: 0, allowEmptyPoints: true },
    })
    await flushPromises()
    expect(wrapper.findAllComponents(ElRadioGroup)[0]!.props('modelValue')).toBe('new')
    expect(wrapper.text()).toContain('当前没有已启用的兼容产品')
    wrapper.unmount()
  })

  it('批量内机预览逐台房间和名称，支持同房间多台内机自动编号且不要求选择一个共享空间', async () => {
    const stateProduct = { ...product, pointCount: 0, points: [] }
    const rows = [
      { ...pending, identityType: 'DAIKIN_UNIT', profileCode: 'DAIKIN_INDOOR_V2', location: { roomSpaceId: 'S1', roomCode: 'B308', monitorAddress: '1-02', assetReferenceCode: 'F000003' } },
      { ...pending, pendingId: 'D2', identityType: 'DAIKIN_UNIT', profileCode: 'DAIKIN_INDOOR_V2', location: { roomSpaceId: 'S1', roomCode: 'B308', monitorAddress: '1-01', assetReferenceCode: 'F000005' } },
      { ...pending, pendingId: 'D3', identityType: 'DAIKIN_UNIT', profileCode: 'DAIKIN_INDOOR_V2', location: { roomSpaceId: 'S2', roomCode: 'B313', monitorAddress: '1-06', assetReferenceCode: 'F000007' } },
    ]
    const bindingOptions = { buildingId: 'BLD001', buildingName: '创新港大楼', spaces: [], systems: [], equipmentPage: 1, equipmentSize: 20, equipmentTotal: 0, equipment: [] }
    const wrapper = mount(BindingDraftDialog, {
      props: { open: true, pending: rows[0], batchRows: rows, product: stateProduct, products: [stateProduct], bindingOptions, allowEmptyPoints: true },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('逐台绑定预览')
    expect(wrapper.text()).toContain('大金内机-B308-1')
    expect(wrapper.text()).toContain('大金内机-B308-2')
    expect(wrapper.text()).toContain('大金内机-B313')
    expect(wrapper.findAllComponents(ElRadioGroup)).toHaveLength(1)
    wrapper.unmount()
  })

  it('大金室内机默认自动匹配，并且不混入旧测点映射', async () => {
    const bindingOptions = {
      buildingId: 'BLD001', buildingName: '创新港大楼',
      spaces: [{ spaceId: 'S1', parentSpaceId: null, spaceName: 'B303' }],
      systems: [{ systemGroupId: 'G1', systemName: '空调系统' }],
      equipmentPage: 1, equipmentSize: 20, equipmentTotal: 0, equipment: [],
    }
    const wrapper = mount(BindingDraftDialog, {
      props: {
        open: true,
        pending: { ...pending, identityType: 'DAIKIN_UNIT', profileCode: 'DAIKIN_INDOOR_V2' },
        product,
        products: [product],
        productTotal: 1,
        allowEmptyPoints: true,
        bindingOptions,
        temperatureOptions: { templates: [{ productId: 'T1', productName: '大金双温度模板' }], numericSources: [{ sourceId: 'S1', sourceName: '厂家温度来源' }], rules: [] },
      },
    })
    await flushPromises()
    expect(wrapper.findAllComponents(ElRadioGroup).at(-1)!.props('modelValue')).toBe('AUTO')
    const spaceSelect = wrapper.findAllComponents(ElSelect)[0]!
    spaceSelect.vm.$emit('update:modelValue', 'S1')
    spaceSelect.vm.$emit('change', 'S1')
    const name = wrapper.findAllComponents(ElInput)[0]!
    name.vm.$emit('update:modelValue', '大金内机-B303')
    await nextTick()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '预览温度测点')!.trigger('click')
    expect(wrapper.emitted('temperature-preview')?.at(-1)?.[0]).toEqual(expect.objectContaining({
      pendingId: 'D1', mode: 'AUTO', binding: expect.objectContaining({
        buildingId: 'BLD001', spaceId: 'S1', systemGroupId: 'G1', pointBindings: [],
      }),
    }))
    wrapper.unmount()
  })

  it('批量大金内机允许含测点产品走温度计划，但仍不展示旧手工逐点映射', async () => {
    const rows = [
      { ...pending, identityType: 'DAIKIN_UNIT', profileCode: 'DAIKIN_INDOOR_V2', location: { roomSpaceId: 'S1', roomCode: 'B314-3', monitorAddress: '1-10', assetReferenceCode: 'F000011' } },
      { ...pending, pendingId: 'D2', identityType: 'DAIKIN_UNIT', profileCode: 'DAIKIN_INDOOR_V2', location: { roomSpaceId: 'S2', roomCode: 'B303-2', monitorAddress: '1-08', assetReferenceCode: 'F000009' } },
    ]
    const wrapper = mount(BindingDraftDialog, {
      props: {
        open: true, pending: rows[0], batchRows: rows, product, products: [product], allowEmptyPoints: true,
        bindingOptions: { buildingId: 'BLD001', buildingName: '创新港大楼', spaces: [], systems: [], equipmentPage: 1, equipmentSize: 20, equipmentTotal: 0, equipment: [] },
        temperatureOptions: { templates: [], numericSources: [], rules: [] },
      },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('温度测点接入')
    expect(wrapper.text()).not.toContain('测点映射')
    expect(wrapper.findAllComponents(ElRadioGroup)).toHaveLength(1)
    wrapper.unmount()
  })
})
