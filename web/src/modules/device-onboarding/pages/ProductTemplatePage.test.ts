import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElInput, ElSelect, ElButton } from '@/shared/ui'
import { listDeviceProducts, listEquipmentTypes } from '../api/onboarding'
import ProductTemplatePage from './ProductTemplatePage.vue'

vi.mock('../api/onboarding', async original => ({ ...(await original()), listDeviceProducts: vi.fn(), listEquipmentTypes: vi.fn() }))
vi.mock('@/modules/access-control/api/access-control', () => ({ newIdempotencyKey: () => 'test-idempotency', getApprovalPolicy: vi.fn().mockResolvedValue({ environmentMode: 'TEST', selfApprovalAllowed: false }), listChangeRequests: vi.fn().mockResolvedValue({ page: 1, size: 10, total: 0, items: [] }) }))
vi.mock('@/modules/auth/public', async importOriginal => ({ ...(await importOriginal<typeof import('@/modules/auth/public')>()), useSession: () => ({ user: { id: 1 } }) }))
describe('产品筛选与客户名称', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listEquipmentTypes).mockResolvedValue([{ typeCode: 'ODU', typeName: '空调外机' }])
    vi.mocked(listDeviceProducts).mockResolvedValue({ page: 1, size: 20, total: 1, items: [{ productId: 'p', productName: '外机三相电表', productCode: 'OUTDOOR_CODE', equipmentTypeCode: 'ODU', pointCount: 11, status: 'DRAFT', updateTime: 0 }] } as never)
  })
  it('输入回车、状态改变和重置发送真实查询参数，类型使用字典', async () => {
    const wrapper = mount(ProductTemplatePage)
    await flushPromises()
    expect(wrapper.text()).toContain('空调外机')
    expect(wrapper.text()).not.toContain('OUTDOOR_CODE')
    const search = wrapper.find('.filter-bar').findComponent(ElInput)
    search.vm.$emit('update:modelValue', '  外机  ')
    await wrapper.vm.$nextTick()
    await search.find('input').trigger('keyup.enter')
    await flushPromises()
    expect(listDeviceProducts).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: '外机', page: 1 }))
    const status = wrapper.find('.filter-bar').findComponent(ElSelect)
    status.vm.$emit('update:modelValue', 'ENABLED')
    status.vm.$emit('change', 'ENABLED')
    await flushPromises()
    expect(listDeviceProducts).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: '外机', status: 'ENABLED', page: 1 }))
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '清除筛选')!.trigger('click')
    await flushPromises()
    expect(listDeviceProducts).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: undefined, status: undefined, page: 1 }))
    wrapper.unmount()
  })
})
