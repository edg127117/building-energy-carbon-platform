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
const pending = { pendingId: 'D1', identityType: 'SN', maskedIdentityValue: 'x', identityValue: 'SN1', profileCode: 'V1', lastProfileVersion: 1, status: 'DISCOVERED', reportCount: 1, firstSeenTime: 1, lastSeenTime: 1, sampleTruncated: false, boundIdentityId: null, latestEventTime: 1, latestTimeSource: null, latestMetrics: {}, allowedActions: [] }

describe('绑定准备弹窗', () => {
  beforeEach(() => vi.clearAllMocks())

  it('弹窗打开时按当前产品初始化模板，产品切换期间不保留旧模板', async () => {
    const wrapper = mount(BindingDraftDialog, { props: { open: false, pending, product, products: [product] } })
    await wrapper.setProps({ open: true })
    await flushPromises()
    expect(wrapper.text()).toContain('有功功率')

    const productSelect = wrapper.findAllComponents(ElSelect)[0]
    productSelect.vm.$emit('update:modelValue', 'P2')
    productSelect.vm.$emit('change', 'P2')
    await nextTick()
    expect(wrapper.text()).not.toContain('有功功率')
    expect(wrapper.emitted('product-change')?.at(-1)).toEqual(['P2'])
  })

  it('新建设备默认使用ANALOG，并按前缀和模板后缀生成可编辑编码', async () => {
    const wrapper = mount(BindingDraftDialog, { props: { open: true, pending, product, products: [product], namingRules: [] } })
    await flushPromises()
    const targetMode = wrapper.findAllComponents(ElRadioGroup)[0]
    targetMode.vm.$emit('update:modelValue', 'new')
    targetMode.vm.$emit('change', 'new')
    await nextTick()

    const prefix = wrapper.find('input[placeholder="填写前缀后按产品模板后缀生成，可继续编辑。"]')
    await prefix.setValue('METER_01')
    await prefix.trigger('change')
    expect(wrapper.findAll('input').some(input => input.element.value === 'METER_01_P')).toBe(true)
    expect(wrapper.findAll('input').some(input => input.element.value === '有功功率')).toBe(true)
    expect(wrapper.findAllComponents(ElSelect).some(select => select.props('modelValue') === 'ANALOG')).toBe(true)
  })
})
