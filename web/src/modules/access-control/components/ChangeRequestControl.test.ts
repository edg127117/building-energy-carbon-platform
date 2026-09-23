import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ChangeRequestControl from './ChangeRequestControl.vue'
import { getApprovalPolicy, listChangeRequests } from '../api/access-control'

vi.mock('../api/access-control', () => ({ getApprovalPolicy: vi.fn(), listChangeRequests: vi.fn() }))
vi.mock('@/modules/auth/public', async importOriginal => ({
  ...(await importOriginal<typeof import('@/modules/auth/public')>()),
  useSession: () => ({ user: { id: 1 } }),
}))

describe('审批环境提示', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listChangeRequests).mockResolvedValue({ page: 1, size: 10, total: 0, items: [] })
  })

  it.each(['DEVELOPMENT', 'TEST'])('显示显式开启的 %s 自审模式', async environmentMode => {
    vi.mocked(getApprovalPolicy).mockResolvedValue({ environmentMode, selfApprovalAllowed: true })
    const wrapper = mount(ChangeRequestControl)
    await flushPromises()
    expect(wrapper.text()).toContain('研发自审已开启')
    expect(wrapper.emitted('approve')).toBeUndefined()
    expect(wrapper.emitted('execute')).toBeUndefined()
    wrapper.unmount()
  })

  it.each([
    { environmentMode: 'TEST', selfApprovalAllowed: false },
    { environmentMode: 'PRODUCTION', selfApprovalAllowed: true },
  ])('不将关闭或生产环境显示为可自审', async policy => {
    vi.mocked(getApprovalPolicy).mockResolvedValue(policy)
    const wrapper = mount(ChangeRequestControl)
    await flushPromises()
    expect(wrapper.text()).not.toContain('研发自审已开启')
    wrapper.unmount()
  })

  it('查询失败明确提示，不推断已开启', async () => {
    vi.mocked(getApprovalPolicy).mockRejectedValue(new Error('offline'))
    const wrapper = mount(ChangeRequestControl)
    await flushPromises()
    expect(wrapper.text()).toContain('审批模式读取失败')
    expect(wrapper.text()).not.toContain('研发自审已开启')
    wrapper.unmount()
  })

  it('从我的申请和审核待办直接打开申请，无须输入编号', async () => {
    vi.mocked(getApprovalPolicy).mockResolvedValue({ environmentMode: 'TEST', selfApprovalAllowed: true })
    vi.mocked(listChangeRequests).mockImplementation(async () => ({
      page: 1, size: 10, total: 1,
      items: [{ requestId: 'request-1', operationCode: 'BIND_PENDING_DEVICE', status: 'PENDING_REVIEW',
        targetType: 'PENDING_DEVICE', targetId: 'pending-1', impactSummary: '绑定设备', submittedBy: 1,
        submitterName: 'admin', submittedAt: '2026-09-23T12:00:00', createTime: '2026-09-23T11:59:00' }],
    }))
    const wrapper = mount(ChangeRequestControl)
    await flushPromises()
    expect(listChangeRequests).toHaveBeenCalledWith('MINE', 1)
    expect(wrapper.text()).toContain('admin')
    expect(wrapper.text()).toContain('绑定待接入设备')
    await wrapper.findAll('button').find(button => button.text() === '审核待办')!.trigger('click')
    await flushPromises()
    expect(listChangeRequests).toHaveBeenCalledWith('REVIEW', 1)
    await wrapper.findAll('button').find(button => button.text() === '打开申请')!.trigger('click')
    expect(wrapper.emitted('lookup')?.[0]).toEqual(['request-1'])
    await wrapper.setProps({ change: {
      requestId: 'request-1', operationCode: 'BIND_PENDING_DEVICE', status: 'PENDING_REVIEW',
      buildingId: null, targetType: 'PENDING_DEVICE', targetId: 'pending-1', submittedBy: 2,
      submittedAt: '2026-09-23T12:00:00', reviewerId: null, reviewComment: null,
      reviewedAt: null, executedAt: null, executionErrorCode: null, environmentMode: 'TEST',
      selfApprovalDevMode: false, traceId: null, oneTimeToken: null, tokenPurpose: null,
      tokenExpiresAt: null,
    } })
    await wrapper.find('textarea').setValue('信息核对完成')
    await wrapper.findAll('button').find(button => button.text() === '审核通过')!.trigger('click')
    expect(wrapper.emitted('approve')?.[0]).toEqual(['request-1', '信息核对完成'])
    wrapper.unmount()
  })
})
