import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElButton, ElInput, ElInputNumber, ElSelect } from '@/shared/ui'
import { listDeviceProducts, listEquipmentTypes } from '@/modules/device-onboarding/public'
import EquipmentEditorDialog from './EquipmentEditorDialog.vue'

vi.mock('@/modules/device-onboarding/public', async original => ({ ...(await original<typeof import('@/modules/device-onboarding/public')>()), listDeviceProducts: vi.fn(), listEquipmentTypes: vi.fn() }))

describe('read-only governed equipment parameters', () => {
  it('keeps unassigned relations read-only while allowing archive attributes to be saved', async () => {
    const wrapper = mount(EquipmentEditorDialog, {
      props: { open: true, equipment: { buildingId: 'B', spaceId: null, systemGroupId: null, typeCode: 'WCR', equipmentName: '设备' } as never, buildings: [], spaces: [], systemGroups: [] },
      global: { stubs: { ElDialog: { template: '<div><slot /><slot name="footer" /></div>' } } },
    })
    expect(wrapper.findAllComponents(ElSelect).every(select => select.props('disabled'))).toBe(true)
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '保存')!.trigger('click')
    expect(wrapper.emitted('save')?.[0]?.[0]).toMatchObject({ spaceId: null, systemGroupId: null })
    wrapper.unmount()
  })
  it('disables parameter editing and preserves the existing projection when saving the archive', async () => {
    const wrapper = mount(EquipmentEditorDialog, {
      props: { open: true, equipment: { buildingId: 'B', spaceId: 'S', systemGroupId: 'G', typeCode: 'WCR', equipmentName: '测试设备', ratedCapacity: 100, ratedPower: 20, designCop: 5 } as never,
        buildings: [], spaces: [], systemGroups: [] },
      global: { stubs: { ElDialog: { template: '<div><slot /><slot name="footer" /></div>' } } },
    })
    expect(wrapper.findAllComponents(ElInputNumber).every(input => input.props('disabled'))).toBe(true)
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '保存')!.trigger('click')
    expect(wrapper.emitted('save')?.[0]?.[0]).toMatchObject({ ratedCapacity: 100, ratedPower: 20, designCop: 5 })
    wrapper.unmount()
  })
})

describe('classification from product templates', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(listEquipmentTypes).mockResolvedValue([{ typeCode: 'ELECTRIC_METER_1P', typeName: '单相电表' }, { typeCode: 'IDU', typeName: '空调内机' }])
    vi.mocked(listDeviceProducts).mockResolvedValue({ page: 1, size: 100, total: 1, items: [{ productId: 'P1', productName: '电表模板', equipmentTypeCode: 'ELECTRIC_METER_1P', manufacturer: null } as never] })
  })
  const create = () => mount(EquipmentEditorDialog, {
    props: { open: true, buildings: [], spaces: [], systemGroups: [] },
    global: { stubs: { ElDialog: { template: '<div><slot /><slot name="footer" /></div>' } } },
  })
  it('selecting a template fixes the type without requiring a duplicate selection', async () => {
    const wrapper = create()
    await flushPromises()
    const fields = wrapper.findAllComponents(ElSelect)
    fields[0].vm.$emit('update:modelValue', 'B1')
    fields[1].vm.$emit('update:modelValue', 'P1')
    fields[1].vm.$emit('change', 'P1')
    await flushPromises()
    expect(fields[2].props('modelValue')).toBe('ELECTRIC_METER_1P')
    expect(fields[2].props('disabled')).toBe(true)
    fields[3].vm.$emit('update:modelValue', 'S1')
    fields[4].vm.$emit('update:modelValue', 'G1')
    await wrapper.findAllComponents(ElInput)[0].setValue('新电表')
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '保存')!.trigger('click')
    expect(wrapper.emitted('save')?.[0]?.[0]).toMatchObject({ productId: 'P1', typeCode: 'ELECTRIC_METER_1P' })
    fields[1].vm.$emit('update:modelValue', undefined)
    fields[1].vm.$emit('change', undefined)
    await flushPromises()
    expect(fields[2].props('modelValue')).toBe('')
    expect(fields[2].props('disabled')).toBe(false)
    wrapper.unmount()
  })
  it('blocks create when classification options cannot be loaded', async () => {
    vi.mocked(listEquipmentTypes).mockRejectedValue(new Error('unavailable'))
    const wrapper = create()
    await flushPromises()
    expect(wrapper.text()).toContain('产品模板或设备类型加载失败')
    expect(wrapper.findAllComponents(ElButton).find(button => button.text() === '保存')!.props('disabled')).toBe(true)
    expect(wrapper.emitted('save')).toBeUndefined()
    wrapper.unmount()
  })
})
