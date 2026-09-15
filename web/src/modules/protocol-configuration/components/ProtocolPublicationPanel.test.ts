import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElButton, ElCheckboxGroup, ElOption, ElSelect } from '@/shared/ui'
import {
  listProtocolDeploymentHistory,
  listProtocolPublicationTargets,
  listProtocolVersions,
  registerProtocolPublicationTarget,
  requestProtocolPublication,
  requestProtocolRollback,
} from '../api/protocol-configuration'
import ProtocolPublicationPanel from './ProtocolPublicationPanel.vue'

vi.mock('@/modules/device-onboarding/public', async importOriginal => ({ ...(await importOriginal()), listDeviceProducts: vi.fn() }))
vi.mock('../api/protocol-configuration', () => ({
  freezeProtocolVersion: vi.fn(), importProtocolVersions: vi.fn(), listProtocolDeploymentHistory: vi.fn(),
  listProtocolPublicationTargets: vi.fn(), listProtocolVersions: vi.fn(), registerProtocolPublicationTarget: vi.fn(),
  requestProtocolPublication: vi.fn(), requestProtocolRollback: vi.fn(),
}))

const readyTarget = { targetId: 'READY-1', name: '隔离适配器', outputVersion: 'V1', allowedTopics: ['raw/a'], lastSeen: 1000, currentSequence: 3, status: 'READY', errorCode: null }
const unknownTarget = { ...readyTarget, targetId: 'UNKNOWN-1', name: '失联适配器', status: 'UNKNOWN' }
const version = {
  versionId: 'VERSION-1', draftId: 'DRAFT-1', draftRevision: 2, digest: 'digest-1', createdAt: 1000,
  configuration: { name: '电表协议', productId: 'P1', profileCode: 'V1', sourceTopic: 'raw/a', identityType: 'SN', identityPath: '/sn', discriminatorPath: null, discriminatorValue: null, timestampPath: null, mappings: [] },
}
const approval = { requestId: 'REQUEST-1', operationCode: 'PUBLISH_PROTOCOL_CONFIGURATION', status: 'DRAFT' }
const deferred = <T>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }

describe('协议发布面板边界', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listProtocolPublicationTargets).mockResolvedValue([readyTarget, unknownTarget] as never)
    vi.mocked(listProtocolVersions).mockResolvedValue([version] as never)
    vi.mocked(listProtocolDeploymentHistory).mockResolvedValue([
      { targetId: 'READY-1', sequence: 1, digest: 'old', approvalId: 'A1', status: 'LOADED', errorCode: null, createdAt: 1000, loadedAt: 1100 },
      { targetId: 'READY-1', sequence: 2, digest: 'bad', approvalId: 'A2', status: 'FAILED', errorCode: 'INVALID', createdAt: 1200, loadedAt: 0 },
    ] as never)
    vi.mocked(requestProtocolPublication).mockResolvedValue(approval as never)
    vi.mocked(requestProtocolRollback).mockResolvedValue(approval as never)
  })

  it('禁用状态未知目标，发布完整版本集合并交接公共审批', async () => {
    const wrapper = mount(ProtocolPublicationPanel, { props: { draftId: 'DRAFT-1', draftRevision: 2, productEnabled: true, selectedProduct: null } })
    await flushPromises()
    const unknownOption = wrapper.findAllComponents(ElOption).find(item => item.props('value') === 'UNKNOWN-1')
    expect(unknownOption?.props('disabled')).toBe(true)

    wrapper.findComponent(ElSelect).vm.$emit('change', 'READY-1')
    wrapper.findComponent(ElCheckboxGroup).vm.$emit('update:modelValue', ['VERSION-1'])
    await flushPromises()
    const publish = wrapper.findAllComponents(ElButton).find(button => button.text() === '创建发布申请')!
    await publish.trigger('click')
    await flushPromises()

    expect(requestProtocolPublication).toHaveBeenCalledWith({
      targetId: 'READY-1', expectedSequence: 3, versionIds: ['VERSION-1'], idempotencyKey: expect.any(String),
    })
    expect(wrapper.text()).toContain('REQUEST-1')
    expect(wrapper.findAllComponents(ElButton).filter(button => button.text() === '申请回退')).toHaveLength(1)
    wrapper.unmount()
  })

  it('切换目标后忽略迟到历史，避免旧序号出现在新目标下', async () => {
    const oldHistory = deferred<Awaited<ReturnType<typeof listProtocolDeploymentHistory>>>()
    const targetB = { ...readyTarget, targetId: 'READY-2', name: '适配器 B', currentSequence: 8 }
    vi.mocked(listProtocolPublicationTargets).mockResolvedValue([readyTarget, targetB] as never)
    vi.mocked(listProtocolDeploymentHistory).mockImplementation(targetId => targetId === 'READY-1'
      ? oldHistory.promise
      : Promise.resolve([{ targetId: 'READY-2', sequence: 7, digest: 'digest-b', approvalId: 'B1', status: 'LOADED', errorCode: null, createdAt: 2000, loadedAt: 2100 }] as never))
    const wrapper = mount(ProtocolPublicationPanel, { props: { draftId: null, draftRevision: null, productEnabled: false, selectedProduct: null } })
    await flushPromises()
    const targetSelect = wrapper.findComponent(ElSelect)
    targetSelect.vm.$emit('change', 'READY-1')
    targetSelect.vm.$emit('change', 'READY-2')
    await flushPromises()
    expect(wrapper.text()).toContain('digest-b')
    oldHistory.resolve([{ targetId: 'READY-1', sequence: 1, digest: 'digest-a', approvalId: 'A1', status: 'LOADED', errorCode: null, createdAt: 1000, loadedAt: 1100 }] as never)
    await flushPromises()
    expect(wrapper.text()).not.toContain('digest-a')
    expect(wrapper.findAllComponents(ElButton).filter(button => button.text() === '申请回退')).toHaveLength(1)
    wrapper.unmount()
  })

  it('登记后列表刷新失败仍立即展示且只在内存保留一次性密钥', async () => {
    vi.mocked(registerProtocolPublicationTarget).mockResolvedValue({ target: readyTarget, oneTimeKey: 'ONE-TIME-KEY' } as never)
    const wrapper = mount(ProtocolPublicationPanel, {
      attachTo: document.body,
      props: { draftId: null, draftRevision: null, productEnabled: false, selectedProduct: null },
    })
    await flushPromises()
    vi.mocked(listProtocolPublicationTargets).mockRejectedValueOnce(new Error('refresh failed'))
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '登记目标')!.trigger('click')
    await flushPromises()
    const nameInput = document.body.querySelector('input[maxlength="100"]') as HTMLInputElement
    nameInput.value = '隔离适配器'
    nameInput.dispatchEvent(new Event('input', { bubbles: true }))
    const topicsInput = document.body.querySelector('textarea[rows="4"]') as HTMLTextAreaElement
    topicsInput.value = 'raw/a'
    topicsInput.dispatchEvent(new Event('input', { bubbles: true }))
    const registerButtons = [...document.body.querySelectorAll('button')].filter(button => button.textContent?.trim() === '登记目标')
    registerButtons.at(-1)!.click()
    await flushPromises()
    expect(registerProtocolPublicationTarget).toHaveBeenCalledWith({ name: '隔离适配器', outputVersion: 'V1', allowedTopics: ['raw/a'] })
    expect((document.body.querySelector('input[aria-label="一次性目标密钥"]') as HTMLInputElement).value).toBe('ONE-TIME-KEY')
    wrapper.unmount()
  })
})
