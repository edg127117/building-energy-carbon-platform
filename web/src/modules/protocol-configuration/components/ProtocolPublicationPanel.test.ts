import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElButton, ElCheckboxGroup, ElOption, ElSelect } from '@/shared/ui'
import {
  previewProtocolPublication, previewProtocolRollback,
  listProtocolDeploymentHistory,
  listProtocolPublicationTargets,
  listProtocolVersions,
  registerProtocolPublicationTarget,
  requestProtocolPublication,
  requestProtocolRollback,
} from '../api/protocol-configuration'
import ProtocolPublicationPanel from './ProtocolPublicationPanel.vue'
import { TransportError } from '@/infrastructure/http/public'

vi.mock('@/modules/device-onboarding/public', async importOriginal => ({ ...(await importOriginal()), listDeviceProducts: vi.fn() }))
vi.mock('../api/protocol-configuration', () => ({
  previewProtocolPublication: vi.fn(), previewProtocolRollback: vi.fn(), getProtocolDeploymentDetail: vi.fn(),
  freezeProtocolVersion: vi.fn(), importProtocolVersions: vi.fn(), listProtocolDeploymentHistory: vi.fn(),
  listProtocolPublicationTargets: vi.fn(), listProtocolVersions: vi.fn(), registerProtocolPublicationTarget: vi.fn(),
  requestProtocolPublication: vi.fn(), requestProtocolRollback: vi.fn(),
}))

vi.mock('@/modules/access-control/api/access-control', () => ({ newIdempotencyKey: () => 'test-idempotency', getApprovalPolicy: vi.fn().mockResolvedValue({ environmentMode: 'TEST', selfApprovalAllowed: false }) }))
const readyTarget = { targetId: 'READY-1', name: '隔离适配器', outputVersion: 'V1', allowedTopics: ['raw/a'], lastSeen: 1000, currentSequence: 3, status: 'READY', errorCode: null }
const unknownTarget = { ...readyTarget, targetId: 'UNKNOWN-1', name: '失联适配器', status: 'UNKNOWN' }
const version = {
  versionId: 'VERSION-1', draftId: 'DRAFT-1', draftRevision: 2, digest: 'digest-1', createdAt: 1000,
  configuration: { name: '电表协议', productId: 'P1', profileCode: 'V1', sourceTopic: 'raw/a', identityType: 'SN', identityPath: '/sn', discriminatorPath: null, discriminatorValue: null, timestampPath: null, mappings: [] },
}
const approval = { requestId: 'REQUEST-1', operationCode: 'PUBLISH_PROTOCOL_CONFIGURATION', status: 'DRAFT' }
const deferred = <T>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }

describe('协议发布面板边界', () => {
  it('展示可操作的冲突原因而非通用请求失败', async () => {
    vi.mocked(previewProtocolPublication).mockRejectedValueOnce(new TransportError('request', 400, undefined, 'PROTOCOL_SNAPSHOT_AMBIGUOUS_PROFILE_SELECTOR'))
    const wrapper = mount(ProtocolPublicationPanel, { props: { draftId: null, draftRevision: null, productEnabled: false, selectedProduct: null } })
    await flushPromises()
    wrapper.findComponent(ElSelect).vm.$emit('change', 'READY-1')
    wrapper.findComponent(ElCheckboxGroup).vm.$emit('update:modelValue', ['VERSION-1'])
    await flushPromises()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '确认发布内容')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('同一 Topic 存在重复判别条件')
    expect(wrapper.text()).toContain('自动保留目标已有的其他协议')
    wrapper.unmount()
  })
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(previewProtocolPublication).mockResolvedValue({ targetId: 'READY-1', expectedSequence: 3, digest: 'd', currentVersions: [], targetVersions: [], changes: [] })
    vi.mocked(previewProtocolRollback).mockResolvedValue({ targetId: 'READY-1', expectedSequence: 3, digest: 'd', currentVersions: [], targetVersions: [], changes: [] })
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
    const publish = wrapper.findAllComponents(ElButton).find(button => button.text() === '确认发布内容')!
    await publish.trigger('click')
    await flushPromises()

    expect(requestProtocolPublication).not.toHaveBeenCalled()
    expect(previewProtocolPublication).toHaveBeenCalled()
    const confirm = wrapper.findComponent('[data-testid="confirm-publication"]')
    expect(confirm.attributes('disabled')).toBeUndefined()
    await confirm.trigger('click')
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
    expect(wrapper.text()).toContain('适配器 B')
    oldHistory.resolve([{ targetId: 'READY-1', sequence: 1, digest: 'digest-a', approvalId: 'A1', status: 'LOADED', errorCode: null, createdAt: 1000, loadedAt: 1100 }] as never)
    await flushPromises()
    expect(wrapper.findAllComponents(ElButton).filter(button => button.text() === '查看规则清单')).toHaveLength(1)
    expect(wrapper.findAllComponents(ElButton).filter(button => button.text() === '申请回退')).toHaveLength(1)
    wrapper.unmount()
  })

  it('切换目标使迟到的发布预览失效，不能提交旧集合', async () => {
    const pending = deferred<Awaited<ReturnType<typeof previewProtocolPublication>>>()
    vi.mocked(previewProtocolPublication).mockReturnValueOnce(pending.promise)
    vi.mocked(listProtocolPublicationTargets).mockResolvedValue([readyTarget, { ...readyTarget, targetId: 'READY-2' }] as never)
    const wrapper = mount(ProtocolPublicationPanel, { props: { draftId: null, draftRevision: null, productEnabled: false, selectedProduct: null } })
    await flushPromises()
    wrapper.findComponent(ElSelect).vm.$emit('change', 'READY-1')
    wrapper.findComponent(ElCheckboxGroup).vm.$emit('update:modelValue', ['VERSION-1'])
    await flushPromises()
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '确认发布内容')!.trigger('click')
    wrapper.findComponent(ElSelect).vm.$emit('change', 'READY-2')
    await flushPromises()
    pending.resolve({ targetId: 'READY-1', expectedSequence: 3, digest: 'old', currentVersions: [], targetVersions: [], changes: [] })
    await flushPromises()
    expect(wrapper.findComponent('[data-testid="confirm-publication"]').exists()).toBe(false)
    expect(requestProtocolPublication).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('当前编辑没有有效解析结果时不能直接冻结旧修订', async () => {
    const wrapper = mount(ProtocolPublicationPanel, { props: { draftId: 'DRAFT-1', draftRevision: 2, productEnabled: true, selectedProduct: null, freezeReady: false } })
    await flushPromises()
    const freeze = wrapper.findAllComponents(ElButton).find(button => button.text() === '冻结当前草稿')!
    expect(freeze.props('disabled')).toBe(true)
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
