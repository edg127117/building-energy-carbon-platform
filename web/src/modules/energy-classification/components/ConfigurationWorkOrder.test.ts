import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ConfigurationWorkOrder from './ConfigurationWorkOrder.vue'
import { configurationWorkOrderApi, type ConfigurationWorkOrder as Order } from '../api/configuration-work-order'
vi.mock('../api/configuration-work-order', () => ({ configurationWorkOrderApi: { get: vi.fn(), execute: vi.fn() } }))
vi.mock('@/shared/ui', () => ({
  ElAlert: { props: ['title'], template: '<div>{{ title }}</div>' }, ElCard: { template: '<section><slot /></section>' },
  ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
  ElFormItem: { template: '<div><slot /></div>' }, ElInput: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' }, ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  ElSelect: { props: ['modelValue'], emits: ['update:modelValue'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>' }, ElSpace: { template: '<div><slot /></div>' }, ElTag: { template: '<span><slot /></span>' },
}))
const props = { buildingId: 'B', versionId: 'V', revision: 3, dirty: false }
const order = (): Order => ({ fingerprint: 'f', guide: { ...props, calculationStatus: 'NOT_CHECKED', steps: [{ domain: 'PROJECT', reference: 'V', revision: 3, state: 'BLOCKED', responsibility: 'ENERGY_MANAGER', action: 'RESOLVE_ISSUES', issues: [{ code: 'PENDING', reference: null, message: '倍率待确认' }] }] } })
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks() })

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
  it('HTTP 环境执行所选工作单仍携带原指纹和兼容幂等键', async () => {
    vi.stubGlobal('crypto', { getRandomValues: (bytes: Uint8Array) => { bytes.fill(7); return bytes } })
    const fixture = order()
    fixture.guide.steps[0] = { ...fixture.guide.steps[0], action: 'SUBMIT', state: 'REVIEW_REQUIRED', issues: [] }
    vi.mocked(configurationWorkOrderApi.get).mockResolvedValue(fixture)
    const wrapper = mount(ConfigurationWorkOrder, { props })
    await wrapper.find('[data-test="work-load"]').trigger('click'); await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '选择操作')!.trigger('click')
    await wrapper.find('select').setValue('SUBMIT')
    await wrapper.find('input').setValue('HTTP隔离依据')
    await wrapper.find('[data-test="work-execute"]').trigger('click'); await flushPromises()
    expect(configurationWorkOrderApi.execute).toHaveBeenCalledWith('B', 'V', {
      fingerprint: 'f', domain: 'PROJECT', action: 'SUBMIT', reason: 'HTTP隔离依据', effectiveFrom: null,
      idempotencyKey: expect.stringMatching(/^[0-9a-f-]{36}$/),
    })
    expect(wrapper.emitted('changed')).toHaveLength(1)
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
