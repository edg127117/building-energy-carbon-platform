import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ProfessionalScenePanels from './ProfessionalScenePanels.vue'
vi.mock('./ScreenPlot.vue', () => ({ default: { template: '<div />' } }))
describe('professional model panels', () => {
  it('separates geometry counts from unknown formal statuses and synchronizes scope events', async () => {
    const wrapper = mount(ProfessionalScenePanels, { props: { topic: 'hvac', assets: [{ id: 'E5_F5_DRAWN_001', name: '外机模型', system: 'hvac', floor: 'F5' }, { id: 'HVAC-INDOOR-DEMO', name: '内机模型', system: 'hvac', floor: 'F3' }], selected: null, floor: '全部', buildingName: 'A', playing: false } })
    expect(wrapper.get('.model-total').text()).toContain('2')
    expect(wrapper.text()).toContain('模型对象未关联正式设备')
    expect(wrapper.get('.energy-total').text()).toContain('—')
    await wrapper.get('select').setValue('F3')
    expect(wrapper.emitted('floor')?.at(-1)).toEqual(['F3'])
    await wrapper.findAll('.kind-tabs button')[1].trigger('click')
    expect(wrapper.emitted('select')?.at(-1)).toEqual(['HVAC-INDOOR-DEMO'])
    await wrapper.findAll('.professional-tabs button')[0].trigger('click')
    expect(wrapper.emitted('system')?.at(-1)).toEqual(['elevator'])
    wrapper.unmount()
  })
})
