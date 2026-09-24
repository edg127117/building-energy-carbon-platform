import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElRadioGroup, ElSelect } from '@/shared/ui'
import BindingDraftDialog from './BindingDraftDialog.vue'

const assets = {
  buildingOptions: ref([]), scopeSpaces: ref([]), scopeSystemGroups: ref([]), points: ref([]),
  equipment: ref({ page: 1, size: 100, total: 0, items: [] }),
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
  points: [{ metricCode: 'power', pointNameTemplate: '有功功率', suffixCode: 'P', unit: 'kW', minValue: null, maxValue: null, forCalc: true, required: true, sortOrder: 0, enabled: true }],
}
const pending = { pendingId: 'D1', identityType: 'SN', maskedIdentityValue: 'x', identityValue: 'SN1', profileCode: 'V1', lastProfileVersion: 1, status: 'DISCOVERED', identityStatus: 'UNBOUND' as const, reportCount: 1, firstSeenTime: 1, lastSeenTime: 1, sampleTruncated: false, boundIdentityId: null, latestEventTime: 1, latestTimeSource: null, latestMetrics: {}, allowedActions: [] }

describe('绑定准备弹窗', () => {
  beforeEach(() => vi.clearAllMocks())

  it('在弹窗中展示前置配置步骤与提交错误', async () => {
    const wrapper = mount(BindingDraftDialog, { props: { open: true, pending, product, products: [product], submitError: '请先启用数据源' } })
    await flushPromises()
    expect(wrapper.text()).toContain('绑定前需要准备什么')
    expect(wrapper.text()).toContain('仅保存草稿还不能绑定')
    expect(wrapper.text()).toContain('请先启用数据源')
    await wrapper.setProps({ allowEmptyPoints: true })
    expect(wrapper.text()).not.toContain('绑定前需要准备什么')
    wrapper.unmount()
  })

  it('弹窗打开时按当前产品初始化模板，产品切换期间不保留旧模板', async () => {
    const wrapper = mount(BindingDraftDialog, { props: { open: false, pending, product, products: [product] } })
    await wrapper.setProps({ open: true })
    await flushPromises()
    expect(wrapper.emitted('close')).toBeUndefined()
    expect(wrapper.text()).toContain('有功功率')

    const productSelect = wrapper.findAllComponents(ElSelect)[0]
    productSelect.vm.$emit('update:modelValue', 'P2')
    productSelect.vm.$emit('change', 'P2')
    await nextTick()
    expect(wrapper.text()).not.toContain('有功功率')
    expect(wrapper.emitted('product-change')?.at(-1)).toEqual(['P2'])
  })

  it('新建设备展示自动建点摘要，不要求逐项填写测点编码', async () => {
    const wrapper = mount(BindingDraftDialog, { props: { open: true, pending, product, products: [product], namingRules: [] } })
    await flushPromises()
    const targetMode = wrapper.findAllComponents(ElRadioGroup)[0]
    targetMode.vm.$emit('update:modelValue', 'new')
    targetMode.vm.$emit('change', 'new')
    await nextTick()

    expect(wrapper.text()).toContain('自动创建测点')
    expect(wrapper.text()).toContain('系统将按产品模板自动创建 1 个测点')
    expect(wrapper.find('input[placeholder="填写前缀后按产品模板后缀生成，可继续编辑。"]').exists()).toBe(false)
  })

  it('区分零测点状态产品和需要HTTP数值来源的温度模板', async () => {
    const stateProduct = { ...product, pointCount: 0, points: [] }
    const stateWrapper = mount(BindingDraftDialog, {
      props: { open: true, pending: { ...pending, identityType: 'DAIKIN_UNIT' }, product: stateProduct, products: [stateProduct], allowEmptyPoints: true },
    })
    await flushPromises()
    expect(stateWrapper.text()).toContain('该厂家状态型产品不创建数值测点')
    expect(stateWrapper.text()).not.toContain('温度数值来源')

    const temperatureWrapper = mount(BindingDraftDialog, {
      props: {
        open: true, pending: { ...pending, identityType: 'DAIKIN_UNIT' }, product, products: [product], allowEmptyPoints: true,
        numericSources: [{ sourceId: 'HTTP-1', sourceCode: 'DAIKIN_TEMP', sourceName: '厂家温度来源' }],
      },
    })
    await flushPromises()
    expect(temperatureWrapper.text()).toContain('温度数值来源')
  })

  it('大金设备默认新建台账且明确提示产品和系统分组前置条件', async () => {
    const wrapper = mount(BindingDraftDialog, {
      props: { open: true, pending: { ...pending, identityType: 'DAIKIN_UNIT' }, products: [], productTotal: 0, allowEmptyPoints: true },
    })
    await flushPromises()
    expect(wrapper.findAllComponents(ElRadioGroup)[0].props('modelValue')).toBe('new')
    expect(wrapper.text()).toContain('当前没有已启用的兼容产品')
    expect(wrapper.text()).toContain('系统分组来自当前建筑台账')
  })

  it('批量内机预览逐台房间和名称，不要求选择一个共享空间', async () => {
    const stateProduct = { ...product, pointCount: 0, points: [] }
    const rows = [
      { ...pending, identityType: 'DAIKIN_UNIT', profileCode: 'DAIKIN_INDOOR_V2', location: { roomSpaceId: 'S1', roomCode: 'B314-3', monitorAddress: '1-10', assetReferenceCode: 'F000011' } },
      { ...pending, pendingId: 'D2', identityType: 'DAIKIN_UNIT', profileCode: 'DAIKIN_INDOOR_V2', location: { roomSpaceId: 'S2', roomCode: 'B303-2', monitorAddress: '1-08', assetReferenceCode: 'F000009' } },
    ]
    const bindingOptions = { buildingId: 'BLD001', buildingName: '创新港大楼', spaces: [], systems: [], equipmentPage: 1, equipmentSize: 20, equipmentTotal: 0, equipment: [] }
    const wrapper = mount(BindingDraftDialog, {
      props: { open: true, pending: rows[0], batchRows: rows, product: stateProduct, products: [stateProduct], bindingOptions, allowEmptyPoints: true },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('逐台绑定预览')
    expect(wrapper.text()).toContain('大金内机-B314-3')
    expect(wrapper.text()).toContain('大金内机-B303-2')
    expect(wrapper.findAllComponents(ElRadioGroup)).toHaveLength(0)
    expect(wrapper.text()).toContain('每台内机使用已核对的房间')
  })
})
