import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ConfigurationWorkOrder from './ConfigurationWorkOrder.vue'
import { configurationWorkOrderApi, type ConfigurationWorkOrder as Order } from '../api/configuration-work-order'
vi.mock('../api/configuration-work-order', () => ({ configurationWorkOrderApi: { get: vi.fn(), execute: vi.fn() } }))
vi.mock('@/shared/ui', () => ({
  ElAlert: { props: ['title'], template: '<div>{{ title }}</div>' }, ElCard: { template: '<section><slot /></section>' },
  ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
  ElFormItem: { template: '<div><slot /></div>' }, ElInput: { template: '<input />' }, ElOption: { template: '<option />' },
  ElSelect: { template: '<select><slot /></select>' }, ElSpace: { template: '<div><slot /></div>' }, ElTag: { template: '<span><slot /></span>' },
}))
const props = { buildingId: 'B', versionId: 'V', revision: 3, dirty: false }
const order = (): Order => ({ fingerprint: 'f', guide: { ...props, calculationStatus: 'NOT_CHECKED', steps: [{ domain: 'PROJECT', reference: 'V', revision: 3, state: 'BLOCKED', responsibility: 'ENERGY_MANAGER', action: 'RESOLVE_ISSUES', issues: [{ code: 'PENDING', reference: null, message: '倍率待确认' }] }] } })
describe('可执行配置工作单', () => {
  it('待确认步骤保留问题，不出现提交、审核或自动发布动作', async () => {
    vi.mocked(configurationWorkOrderApi.get).mockResolvedValue(order())
    const wrapper = mount(ConfigurationWorkOrder, { props })
    await wrapper.find('[data-test="work-load"]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('倍率待确认')
    expect(wrapper.find('[data-test="work-execute"]').exists()).toBe(false)
    expect(configurationWorkOrderApi.execute).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('项目修订变化后忽略迟到的工作单，不保留旧步骤', async () => {
    let resolve!: (response: Order) => void
    vi.mocked(configurationWorkOrderApi.get).mockImplementation(() => new Promise(done => { resolve = done }))
    const wrapper = mount(ConfigurationWorkOrder, { props })
    await wrapper.find('[data-test="work-load"]').trigger('click'); await wrapper.setProps({ revision: 4 })
    resolve(order()); await flushPromises()
    expect(wrapper.text()).not.toContain('倍率待确认')
    wrapper.unmount()
  })
})
