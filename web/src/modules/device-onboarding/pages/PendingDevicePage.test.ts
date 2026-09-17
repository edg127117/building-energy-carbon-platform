import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { ElButton, ElInput } from '@/shared/ui'
import { listPendingDevices } from '../api/onboarding'
import PendingDevicePage from './PendingDevicePage.vue'
vi.mock('vue-router', () => ({ useRoute: () => ({ query: { view: 'general', profileCode: 'INDOOR', draftId: 'draft-1' } }), useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }))
vi.mock('@/modules/auth/public', async original => ({ ...(await original()), useSession: () => ({ user: { roles: ['PLATFORM_ADMIN'] } }) }))
vi.mock('../api/onboarding', async original => ({ ...(await original()), listPendingDevices: vi.fn().mockResolvedValue({ page: 1, size: 20, total: 0, items: [] }) }))
vi.mock('@/modules/access-control/api/access-control', () => ({ getApprovalPolicy: vi.fn().mockResolvedValue({ environmentMode: 'TEST', selfApprovalAllowed: false }) }))
describe('待接入范围筛选', () => {
  it('身份查询和重置保留流程协议范围，显式查看全部才移除', async () => {
    const wrapper = mount(PendingDevicePage)
    await flushPromises()
    expect(listPendingDevices).toHaveBeenLastCalledWith(expect.objectContaining({ profileCode: 'INDOOR' }))
    const input = wrapper.find('.filter-bar').findComponent(ElInput)
    input.vm.$emit('update:modelValue', '  device-1  ')
    await wrapper.vm.$nextTick()
    await input.find('input').trigger('keyup.enter')
    await flushPromises()
    expect(listPendingDevices).toHaveBeenLastCalledWith(expect.objectContaining({ identity: 'device-1', profileCode: 'INDOOR', page: 1 }))
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '清除筛选')!.trigger('click')
    await flushPromises()
    expect(listPendingDevices).toHaveBeenLastCalledWith(expect.objectContaining({ identity: undefined, profileCode: 'INDOOR' }))
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '查看全部设备')!.trigger('click')
    await flushPromises()
    expect(listPendingDevices).toHaveBeenLastCalledWith(expect.objectContaining({ profileCode: undefined }))
    wrapper.unmount()
  })
})
