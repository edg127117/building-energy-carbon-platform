import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ChangeRequestControl from './ChangeRequestControl.vue'
import { getApprovalPolicy } from '../api/access-control'

vi.mock('../api/access-control', () => ({ getApprovalPolicy: vi.fn() }))

describe('审批环境提示', () => {
  beforeEach(() => vi.clearAllMocks())

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
})
