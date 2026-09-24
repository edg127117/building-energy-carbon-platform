import { config, flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ChangeRequestControl from './ChangeRequestControl.vue'
import { approveChangeRequest, executeChangeRequest, getApprovalPolicy, listChangeRequests } from '../api/access-control'

vi.mock('../api/access-control', () => ({
  approveChangeRequest: vi.fn(), executeChangeRequest: vi.fn(),
  getApprovalPolicy: vi.fn(), listChangeRequests: vi.fn(),
}))
vi.mock('@/modules/auth/public', async importOriginal => ({
  ...(await importOriginal<typeof import('@/modules/auth/public')>()),
  useSession: () => ({ user: { id: 1 } }),
}))

describe('审批环境提示', () => {
  beforeEach(() => {
    config.global.stubs.RouterLink = { template: '<a><slot /></a>' }
    vi.clearAllMocks()
    vi.mocked(listChangeRequests).mockResolvedValue({ page: 1, size: 10, total: 0, items: [] })
  })

  it.each(['DEVELOPMENT', 'TEST'])('显示显式开启的 %s 自审模式', async environmentMode => {
    vi.mocked(getApprovalPolicy).mockResolvedValue({ environmentMode, selfApprovalAllowed: true })
    const wrapper = mount(ChangeRequestControl, { props: { inbox: true } })
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
    const wrapper = mount(ChangeRequestControl, { props: { inbox: true } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('研发自审已开启')
    wrapper.unmount()
  })

  it('查询失败明确提示，不推断已开启', async () => {
    vi.mocked(getApprovalPolicy).mockRejectedValue(new Error('offline'))
    const wrapper = mount(ChangeRequestControl, { props: { inbox: true } })
    await flushPromises()
    expect(wrapper.text()).toContain('审批模式读取失败')
    expect(wrapper.text()).not.toContain('研发自审已开启')
    wrapper.unmount()
  })

  it('从我的申请和审核待办直接打开申请，无须输入编号', async () => {
    vi.mocked(getApprovalPolicy).mockResolvedValue({ environmentMode: 'TEST', selfApprovalAllowed: true })
    vi.mocked(listChangeRequests).mockImplementation(async () => ({
      page: 1, size: 10, total: 1,
      items: [{ requestId: 'request-1', operationCode: 'BIND_TYPED_PENDING_DEVICE', status: 'PENDING_REVIEW',
        targetType: 'PENDING_DEVICE', targetId: 'pending-1', impactSummary: 'buildingId=BLD001;bindingType=TYPED_STATE;pointCount=0', submittedBy: 1,
        submitterName: 'admin', submittedAt: '2026-09-23T12:00:00', createTime: '2026-09-23T11:59:00' }],
    }))
    const wrapper = mount(ChangeRequestControl, { props: { inbox: true } })
    await flushPromises()
    expect(listChangeRequests).toHaveBeenCalledWith('MINE', 1, 10, 'ACTIVE')
    expect(wrapper.find('.request-inbox').attributes('open')).toBeUndefined()
    expect(wrapper.text()).toContain('admin')
    expect(wrapper.text()).toContain('绑定厂家待接入设备')
    expect(wrapper.text()).toContain('建筑编号：BLD001 · 绑定方式：厂家状态设备 · 测点数量：0')
    await wrapper.findAll('button').find(button => button.text() === '审核待办')!.trigger('click')
    await flushPromises()
    expect(listChangeRequests).toHaveBeenCalledWith('REVIEW', 1, 10, 'ALL')
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

  it('历史申请单独分页查询，已完成申请不占进行中列表', async () => {
    vi.mocked(getApprovalPolicy).mockResolvedValue({ environmentMode: 'TEST', selfApprovalAllowed: false })
    const wrapper = mount(ChangeRequestControl, { props: { inbox: true } })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '历史申请')!.trigger('click')
    await flushPromises()
    expect(listChangeRequests).toHaveBeenLastCalledWith('MINE', 1, 10, 'HISTORY')
    wrapper.unmount()
  })

  it('业务页面只给集中处理入口，不重复渲染申请列表', async () => {
    const wrapper = mount(ChangeRequestControl, { props: { change: {
      requestId: 'request-1', operationCode: 'BIND_PENDING_DEVICE', status: 'PENDING_REVIEW',
      buildingId: null, targetType: 'PENDING_DEVICE', targetId: 'pending-1', submittedBy: 1,
      submittedAt: '2026-09-23T12:00:00', reviewerId: null, reviewComment: null,
      reviewedAt: null, executedAt: null, executionErrorCode: null, environmentMode: 'TEST',
      selfApprovalDevMode: false, traceId: null, oneTimeToken: null, tokenPurpose: null,
      tokenExpiresAt: null,
    } } })
    await flushPromises()
    expect(wrapper.text()).toContain('前往敏感变更申请处理')
    expect(wrapper.find('.request-inbox').exists()).toBe(false)
    expect(listChangeRequests).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('批量只处理设备绑定待办，逐条审核并执行且保留失败结果', async () => {
    vi.mocked(getApprovalPolicy).mockResolvedValue({ environmentMode: 'TEST', selfApprovalAllowed: true })
    vi.mocked(listChangeRequests).mockResolvedValue({
      page: 1, size: 10, total: 3,
      items: [
        { requestId: 'bind-1', operationCode: 'BIND_TYPED_PENDING_DEVICE', status: 'PENDING_REVIEW',
          targetType: 'PENDING_DEVICE', targetId: 'pending-1', impactSummary: '内机 B303-1', submittedBy: 1,
          submitterName: 'admin', submittedAt: null, createTime: '2026-09-23T12:00:00' },
        { requestId: 'bind-2', operationCode: 'BIND_PENDING_DEVICE', status: 'APPROVED',
          targetType: 'PENDING_DEVICE', targetId: 'pending-2', impactSummary: '内机 B303-2', submittedBy: 2,
          submitterName: 'reviewer', submittedAt: null, createTime: '2026-09-23T12:00:00' },
        { requestId: 'product-1', operationCode: 'ENABLE_DEVICE_PRODUCT', status: 'PENDING_REVIEW',
          targetType: 'DEVICE_PRODUCT', targetId: 'product-1', impactSummary: '产品启用', submittedBy: 2,
          submitterName: 'reviewer', submittedAt: null, createTime: '2026-09-23T12:00:00' },
      ],
    })
    vi.mocked(approveChangeRequest).mockResolvedValue({ status: 'APPROVED' } as Awaited<ReturnType<typeof approveChangeRequest>>)
    vi.mocked(executeChangeRequest).mockImplementation(async id => {
      if (id === 'bind-2') throw new Error('执行失败')
      return { status: 'EXECUTED' } as Awaited<ReturnType<typeof executeChangeRequest>>
    })
    const wrapper = mount(ChangeRequestControl, { attachTo: document.body, props: { inbox: true } })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '审核待办')!.trigger('click')
    await flushPromises()
    const checks = wrapper.findAll('.batch-check input')
    expect(checks).toHaveLength(2)
    await wrapper.findAll('button').find(button => button.text() === '选择本页绑定申请')!.trigger('click')
    expect(wrapper.findAll('.batch-check input:checked')).toHaveLength(2)
    await wrapper.findAll('button').find(button => button.text() === '批量审核并执行')!.trigger('click')
    await flushPromises()
    const dialog = document.querySelector('.el-dialog') as HTMLElement
    expect(dialog.textContent).toContain('本次处理的设备绑定申请数：2')
    const comment = dialog.querySelector('textarea') as HTMLTextAreaElement
    comment.value = '现场信息核对完成'
    comment.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    const confirm = [...dialog.querySelectorAll('button')].find(button => button.textContent?.includes('批量审核并执行'))!
    confirm.click()
    await flushPromises()
    expect(approveChangeRequest).toHaveBeenCalledTimes(1)
    expect(approveChangeRequest).toHaveBeenCalledWith('bind-1', '现场信息核对完成')
    expect(executeChangeRequest).toHaveBeenNthCalledWith(1, 'bind-1')
    expect(executeChangeRequest).toHaveBeenNthCalledWith(2, 'bind-2')
    expect(wrapper.text()).toContain('bind-2：请求失败')
    wrapper.unmount()
  })

  it('身份启用申请可单独批量审核并执行，不混入绑定和产品申请', async () => {
    vi.mocked(getApprovalPolicy).mockResolvedValue({ environmentMode: 'TEST', selfApprovalAllowed: true })
    vi.mocked(listChangeRequests).mockResolvedValue({ page: 1, size: 10, total: 4, items: [
      { requestId: 'bind-1', operationCode: 'BIND_TYPED_PENDING_DEVICE', status: 'PENDING_REVIEW',
        targetType: 'PENDING_DEVICE', targetId: 'pending-1', impactSummary: null,
        submittedBy: 1, submitterName: 'admin', submittedAt: null, createTime: '2026-09-23T12:00:00' },
      { requestId: 'activate-1', operationCode: 'ACTIVATE_DEVICE_IDENTITY', status: 'PENDING_REVIEW',
        targetType: 'DEVICE_IDENTITY', targetId: 'identity-1', impactSummary: 'buildingId=BLD001;equipmentName=大金内机-B303-1;action=ACTIVATE',
        submittedBy: 1, submitterName: 'admin', submittedAt: null, createTime: '2026-09-23T12:00:00' },
      { requestId: 'activate-2', operationCode: 'ACTIVATE_DEVICE_IDENTITY', status: 'APPROVED',
        targetType: 'DEVICE_IDENTITY', targetId: 'identity-2', impactSummary: null,
        submittedBy: 2, submitterName: 'reviewer', submittedAt: null, createTime: '2026-09-23T12:00:00' },
      { requestId: 'product-1', operationCode: 'ENABLE_DEVICE_PRODUCT', status: 'PENDING_REVIEW',
        targetType: 'DEVICE_PRODUCT', targetId: 'product-1', impactSummary: null,
        submittedBy: 2, submitterName: 'reviewer', submittedAt: null, createTime: '2026-09-23T12:00:00' },
    ] })
    vi.mocked(approveChangeRequest).mockResolvedValue({ status: 'APPROVED' } as Awaited<ReturnType<typeof approveChangeRequest>>)
    vi.mocked(executeChangeRequest).mockResolvedValue({ status: 'EXECUTED' } as Awaited<ReturnType<typeof executeChangeRequest>>)
    const wrapper = mount(ChangeRequestControl, { attachTo: document.body, props: { inbox: true } })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '审核待办')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('设备名称：大金内机-B303-1 · 操作：启用')
    await wrapper.findAll('button').find(button => button.text() === '选择本页身份启用申请')!.trigger('click')
    expect(wrapper.findAll('.batch-check input:checked')).toHaveLength(2)
    await wrapper.findAll('button').find(button => button.text() === '批量审核并执行')!.trigger('click')
    await flushPromises()
    const dialog = document.querySelector('.el-dialog') as HTMLElement
    expect(dialog.textContent).toContain('本次处理的身份启用申请数：2')
    const comment = dialog.querySelector('textarea') as HTMLTextAreaElement
    comment.value = '设备身份核对完成'
    comment.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    ;([...dialog.querySelectorAll('button')].find(button => button.textContent?.includes('批量审核并执行')) as HTMLButtonElement).click()
    await flushPromises()
    expect(approveChangeRequest).toHaveBeenCalledTimes(1)
    expect(approveChangeRequest).toHaveBeenCalledWith('activate-1', '设备身份核对完成')
    expect(executeChangeRequest).toHaveBeenCalledTimes(2)
    expect(executeChangeRequest).toHaveBeenCalledWith('activate-1')
    expect(executeChangeRequest).toHaveBeenCalledWith('activate-2')
    wrapper.unmount()
  })
})
