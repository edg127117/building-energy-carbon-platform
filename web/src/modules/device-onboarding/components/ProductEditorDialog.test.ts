import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { ElSelect } from '@/shared/ui'
import ProductEditorDialog from './ProductEditorDialog.vue'

const product = {
  productId: 'P1', productCode: 'OUTDOOR', productName: '外机电表', equipmentTypeCode: 'ODU',
  expectedProfileCode: 'OUTDOOR_V1', identityType: 'SN', status: 'DRAFT' as const,
  manufacturer: null, model: null, pointCount: 1, createTime: 1, updateTime: 1, allowedActions: [],
  points: [{ metricCode: 'POWER', pointNameTemplate: '输入功率', suffixCode: 'P', unit: 'kW',
    minValue: null, maxValue: null, forCalc: false, required: true, enabled: true, sortOrder: 1 }],
}

describe('产品设备类型选择', () => {
  it('只保存服务端提供的已启用类型，类型不可用时阻止保存', async () => {
    const wrapper = mount(ProductEditorDialog, { props: { open: true, product, equipmentTypes: [] } })
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')).toBeUndefined()
    expect(wrapper.text()).toContain('请选择已启用的设备类型')
    await wrapper.setProps({ equipmentTypes: [{ typeCode: 'ODU', typeName: '空调外机' }] })
    expect(wrapper.findComponent(ElSelect).props('allowCreate')).toBeFalsy()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')?.[0]?.[0]).toMatchObject({ equipmentTypeCode: 'ODU' })
  })
})
