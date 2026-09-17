import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import SystemGroupEditorDialog from './SystemGroupEditorDialog.vue'

describe('系统分组必填校验', () => {
  it('阻止空白编码提交，填写后保存去除首尾空格的编码', async () => {
    const wrapper = mount(SystemGroupEditorDialog, {
      props: { open: true, buildingId: 'B' },
      global: { stubs: { ElDialog: { template: '<div><slot /><slot name="footer" /></div>' } } },
    })
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('空调系统')
    await inputs[1].setValue('   ')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.emitted('save')).toBeUndefined()
    await vi.waitFor(() => expect(wrapper.text()).toContain('请填写系统编码'))
    await inputs[1].setValue(' HVAC_01 ')
    await wrapper.find('form').trigger('submit')
    expect(wrapper.emitted('save')?.[0]?.[0]).toMatchObject({ systemCode: 'HVAC_01', systemName: '空调系统' })
    wrapper.unmount()
  })
})
