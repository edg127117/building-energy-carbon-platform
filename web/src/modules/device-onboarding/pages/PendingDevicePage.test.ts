import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { ElButton, ElInput } from '@/shared/ui'
import { listPendingDevices } from '../api/onboarding'
import { createChangeRequest } from '@/modules/access-control/api/access-control'
import { TransportError } from '@/infrastructure/http/public'
import BindingDraftDialog from '../components/BindingDraftDialog.vue'
import PendingDevicePage from './PendingDevicePage.vue'
vi.mock('vue-router', () => ({ useRoute: () => ({ query: { view: 'general', profileCode: 'INDOOR', draftId: 'draft-1' } }), useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }))
vi.mock('@/modules/auth/public', async original => ({ ...(await original()), useSession: () => ({ user: { roles: ['PLATFORM_ADMIN'] } }) }))
vi.mock('../api/onboarding', async original => ({ ...(await original()), listPendingDevices: vi.fn().mockResolvedValue({ page: 1, size: 20, total: 0, items: [] }), getPendingDevice: vi.fn().mockResolvedValue({ pendingId: 'D1', status: 'DISCOVERED', identityType: 'SN', allowedActions: [] }), getPendingDeviceConnection: vi.fn().mockResolvedValue(null) }))
vi.mock('@/modules/access-control/api/access-control', () => ({ getApprovalPolicy: vi.fn().mockResolvedValue({ environmentMode: 'TEST', selfApprovalAllowed: false }), createChangeRequest: vi.fn(), newIdempotencyKey: () => 'test-key' }))
describe('待接入范围筛选', () => {
  it('一般绑定排除厂家专用字段，并将数据源前置错误保留在表单', async () => {
    vi.mocked(listPendingDevices).mockResolvedValueOnce({ page: 1, size: 20, total: 1, items: [{ pendingId: 'D1', status: 'DISCOVERED', identityType: 'SN', allowedActions: [] }] } as never)
    vi.mocked(createChangeRequest).mockRejectedValueOnce(new TransportError('request', 409, undefined, 'ONBOARDING_SOURCE_REQUIRED'))
    const wrapper = mount(PendingDevicePage, { global: { stubs: { BindingDraftDialog: true } } })
    await flushPromises()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '查看详情')!.trigger('click')
    await flushPromises()
    const form = wrapper.findComponent(BindingDraftDialog)
    form.vm.$emit('submit', { productId: 'P1', buildingId: 'B1', spaceId: 'S1', systemGroupId: 'G1', existingEquipmentId: 'E1', newEquipment: null, pointBindings: [{ metricCode: 'power', existingPointId: 'POINT1' }], numericSourceId: null })
    await flushPromises()
    expect(createChangeRequest).toHaveBeenCalled()
    expect(JSON.stringify(vi.mocked(createChangeRequest).mock.calls.at(-1))).not.toContain('numericSourceId')
    expect(form.props('submitError')).toContain('还没有已启用的接入数据源')
    wrapper.unmount()
  })
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
