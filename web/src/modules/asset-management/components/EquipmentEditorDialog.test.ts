import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { ElButton, ElInputNumber } from '@/shared/ui'
import EquipmentEditorDialog from './EquipmentEditorDialog.vue'

describe('read-only governed equipment parameters', () => {
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
