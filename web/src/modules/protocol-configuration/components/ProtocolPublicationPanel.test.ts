import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElButton, ElOption, ElSelect } from '@/shared/ui'
import { getChangeRequest } from '@/modules/access-control/api/access-control'
import {
  listProtocolDeploymentHistory,
  listProtocolPublicationTargets,
  listProtocolVersions,
  previewDraftProtocolPublication,
  previewProtocolPublication,
  previewProtocolRollback,
  registerProtocolPublicationTarget,
  requestDraftProtocolPublication,
  requestProtocolPublication,
  requestProtocolRollback,
} from '../api/protocol-configuration'
import ProtocolPublicationPanel from './ProtocolPublicationPanel.vue'
import { TransportError } from '@/infrastructure/http/public'

vi.mock('@/modules/device-onboarding/public', async importOriginal => ({ ...(await importOriginal()), listDeviceProducts: vi.fn() }))
vi.mock('../api/protocol-configuration', () => ({
  previewDraftProtocolPublication: vi.fn(),
  requestDraftProtocolPublication: vi.fn(),
  previewProtocolPublication: vi.fn(),
  previewProtocolRollback: vi.fn(),
  getProtocolDeploymentDetail: vi.fn(),
  freezeProtocolVersion: vi.fn(),
  importProtocolVersions: vi.fn(),
  listProtocolDeploymentHistory: vi.fn(),
  listProtocolPublicationTargets: vi.fn(),
  listProtocolVersions: vi.fn(),
  registerProtocolPublicationTarget: vi.fn(),
  requestProtocolPublication: vi.fn(),
  requestProtocolRollback: vi.fn(),
}))

let keyCounter = 0
vi.mock('@/modules/access-control/api/access-control', () => ({
  newIdempotencyKey: () => `test-idempotency-${++keyCounter}`,
  getChangeRequest: vi.fn(),
  getApprovalPolicy: vi.fn().mockResolvedValue({ environmentMode: 'TEST', selfApprovalAllowed: false }),
  listChangeRequests: vi.fn().mockResolvedValue({ page: 1, size: 10, total: 0, items: [] }),
}))
vi.mock('@/modules/auth/public', async importOriginal => ({
  ...(await importOriginal<typeof import('@/modules/auth/public')>()),
  useSession: () => ({ user: { id: 1 } }),
}))

const readyTarget = { targetId: 'READY-1', name: '隔离适配器', outputVersion: 'V1', allowedTopics: ['raw/a'], lastSeen: 1000, currentSequence: 3, status: 'READY', errorCode: null }
const unknownTarget = { ...readyTarget, targetId: 'UNKNOWN-1', name: '失联适配器', status: 'UNKNOWN' }
const version = {
  versionId: 'VERSION-1', draftId: 'DRAFT-1', draftRevision: 2, digest: 'digest-1', createdAt: 1000,
  configuration: { name: '电表协议', productId: 'P1', profileCode: 'V1', sourceTopic: 'raw/a', identityType: 'SN', identityPath: '/sn', discriminatorPath: null, discriminatorValue: null, timestampPath: null, mappings: [] },
}
const pendingApproval = { requestId: 'REQUEST-1', operationCode: 'PUBLISH_PROTOCOL_CONFIGURATION', status: 'PENDING_REVIEW' }
const executedApproval = { requestId: 'REQUEST-1', operationCode: 'PUBLISH_PROTOCOL_CONFIGURATION', status: 'EXECUTED' }
const draftApproval = { requestId: 'ROLLBACK-1', operationCode: 'PUBLISH_PROTOCOL_CONFIGURATION', status: 'DRAFT' }
const draftPreviewResponse = {
  targetId: 'READY-1',
  expectedSequence: 3,
  digest: 'sha-preview',
  currentVersions: [],
  targetVersions: [{ versionId: 'DRAFT_PREVIEW', name: '电表协议', profileCode: 'V1', revision: 2, mappingCount: 3 }],
  changes: [
    {
      profileCode: 'V1',
      changeType: 'ADDED' as const,
      beforeVersion: null,
      afterVersion: { versionId: 'DRAFT_PREVIEW', name: '电表协议', profileCode: 'V1', revision: 2, mappingCount: 3 },
    },
  ],
}
const deferred = <T>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }

describe('协议发布面板边界', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    keyCounter = 0
    vi.mocked(getChangeRequest).mockResolvedValue(pendingApproval as never)
    vi.mocked(previewDraftProtocolPublication).mockResolvedValue(draftPreviewResponse)
    vi.mocked(requestDraftProtocolPublication).mockResolvedValue(pendingApproval as never)
    vi.mocked(previewProtocolPublication).mockResolvedValue({ targetId: 'READY-1', expectedSequence: 3, digest: 'd', currentVersions: [], targetVersions: [], changes: [] })
    vi.mocked(previewProtocolRollback).mockResolvedValue({ targetId: 'READY-1', expectedSequence: 3, digest: 'd', currentVersions: [], targetVersions: [], changes: [] })
    vi.mocked(listProtocolPublicationTargets).mockResolvedValue([readyTarget, unknownTarget] as never)
    vi.mocked(listProtocolVersions).mockResolvedValue([version] as never)
    vi.mocked(listProtocolDeploymentHistory).mockResolvedValue([
      { targetId: 'READY-1', sequence: 1, digest: 'old', approvalId: 'A1', status: 'LOADED', errorCode: null, createdAt: 1000, loadedAt: 1100 },
      { targetId: 'READY-1', sequence: 2, digest: 'bad', approvalId: 'A2', status: 'FAILED', errorCode: 'INVALID', createdAt: 1200, loadedAt: 0 },
    ] as never)
    vi.mocked(requestProtocolPublication).mockResolvedValue(draftApproval as never)
    vi.mocked(requestProtocolRollback).mockResolvedValue(draftApproval as never)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('未送审时不提示加载成功；送审后按申请编号自动轮询独立审核状态与加载回执并复用幂等键', async () => {
    vi.useFakeTimers()
    const wrapper = mount(ProtocolPublicationPanel, {
      props: { active: true, draftId: 'DRAFT-1', draftRevision: 2, draftDirty: false, productEnabled: true, selectedProduct: null, profileCode: 'V1' },
    })
    await flushPromises()
    expect(wrapper.text()).not.toContain('目标适配器已确认加载最新配置')
    expect(wrapper.findAllComponents(ElButton).find(button => button.text() === '前往待接入设备')).toBeUndefined()

    const unknownOption = wrapper.findAllComponents(ElOption).find(item => item.props('value') === 'UNKNOWN-1')
    expect(unknownOption?.props('disabled')).toBe(true)

    wrapper.findComponent(ElSelect).vm.$emit('change', 'READY-1')
    await flushPromises()

    expect(previewDraftProtocolPublication).toHaveBeenCalledWith({
      draftId: 'DRAFT-1',
      revision: 2,
      targetId: 'READY-1',
      expectedSequence: 3,
    })
    expect(wrapper.text()).toContain('本次草稿（待冻结）')
    expect(wrapper.text()).toContain('新增')

    const submit = wrapper.findComponent('[data-testid="submit-draft-publish"]')
    expect(submit.attributes('disabled')).toBeUndefined()
    await submit.trigger('click')
    await flushPromises()
    await submit.trigger('click')
    await flushPromises()

    expect(requestDraftProtocolPublication).toHaveBeenCalledTimes(2)
    expect(requestDraftProtocolPublication).toHaveBeenNthCalledWith(1, {
      draftId: 'DRAFT-1', revision: 2, targetId: 'READY-1', expectedSequence: 3, idempotencyKey: 'test-idempotency-1',
    })
    expect(requestDraftProtocolPublication).toHaveBeenNthCalledWith(2, {
      draftId: 'DRAFT-1', revision: 2, targetId: 'READY-1', expectedSequence: 3, idempotencyKey: 'test-idempotency-1',
    })
    expect(wrapper.text()).toContain('REQUEST-1')
    // 仍处于待审核时，不应误显示加载成功提示
    expect(wrapper.text()).not.toContain('目标适配器已确认加载最新配置')

    // 模拟独立账号审核执行完毕且适配器返回 LOADED 回执
    vi.mocked(getChangeRequest).mockResolvedValueOnce(executedApproval as never)
    vi.mocked(listProtocolPublicationTargets).mockResolvedValueOnce([
      { ...readyTarget, currentSequence: 4, status: 'LOADED' },
      unknownTarget,
    ] as never)
    vi.mocked(listProtocolDeploymentHistory).mockResolvedValueOnce([
      { targetId: 'READY-1', sequence: 4, digest: 'sha-new', approvalId: 'REQUEST-1', status: 'LOADED', errorCode: null, createdAt: 2000, loadedAt: 2100 },
    ] as never)

    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()

    expect(getChangeRequest).toHaveBeenCalledWith('REQUEST-1')
    expect(wrapper.text()).toContain('目标适配器已确认加载最新配置')
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '前往待接入设备')!.trigger('click')
    expect(wrapper.emitted('open-pending-devices')).toHaveLength(1)
    wrapper.unmount()
  })

  it('超过两分钟仍追踪待审核申请，切换稿件修订后丢弃迟到状态', async () => {
    vi.useFakeTimers()
    const wrapper = mount(ProtocolPublicationPanel, {
      props: { active: true, draftId: 'DRAFT-1', draftRevision: 2, productEnabled: true, selectedProduct: null },
    })
    await flushPromises()
    wrapper.findComponent(ElSelect).vm.$emit('change', 'READY-1')
    await flushPromises()
    await wrapper.findComponent('[data-testid="submit-draft-publish"]').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(125000)
    expect(getChangeRequest).toHaveBeenCalledTimes(25)

    const delayed = deferred<Awaited<ReturnType<typeof getChangeRequest>>>()
    vi.mocked(getChangeRequest).mockReturnValueOnce(delayed.promise)
    await vi.advanceTimersByTimeAsync(5000)
    expect(getChangeRequest).toHaveBeenCalledTimes(26)
    await wrapper.setProps({ draftRevision: 3 })
    delayed.resolve(executedApproval as never)
    await flushPromises()
    expect(wrapper.text()).not.toContain('REQUEST-1')
    expect(wrapper.text()).not.toContain('目标适配器已确认加载最新配置')
    wrapper.unmount()
  })

  it('草稿预览遇到判别条件冲突时展示可操作原因', async () => {
    vi.mocked(previewDraftProtocolPublication).mockRejectedValueOnce(
      new TransportError('request', 400, undefined, 'PROTOCOL_SNAPSHOT_AMBIGUOUS_PROFILE_SELECTOR'),
    )
    const wrapper = mount(ProtocolPublicationPanel, {
      props: { active: true, draftId: 'DRAFT-1', draftRevision: 2, draftDirty: false, productEnabled: true, selectedProduct: null },
    })
    await flushPromises()
    wrapper.findComponent(ElSelect).vm.$emit('change', 'READY-1')
    await flushPromises()
    expect(wrapper.text()).toContain('同一主题存在重复判别条件')
    wrapper.unmount()
  })

  it('切换目标使迟到的草稿差异预览失效，不能提交旧目标', async () => {
    const pending = deferred<typeof draftPreviewResponse>()
    vi.mocked(previewDraftProtocolPublication)
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce({ ...draftPreviewResponse, targetId: 'READY-2', expectedSequence: 8 })
    vi.mocked(listProtocolPublicationTargets).mockResolvedValue([readyTarget, { ...readyTarget, targetId: 'READY-2', currentSequence: 8 }] as never)
    const wrapper = mount(ProtocolPublicationPanel, {
      props: { active: true, draftId: 'DRAFT-1', draftRevision: 2, draftDirty: false, productEnabled: true, selectedProduct: null },
    })
    await flushPromises()
    wrapper.findComponent(ElSelect).vm.$emit('change', 'READY-1')
    await wrapper.vm.$nextTick()
    wrapper.findComponent(ElSelect).vm.$emit('change', 'READY-2')
    await flushPromises()
    pending.resolve(draftPreviewResponse)
    await flushPromises()

    await wrapper.findComponent('[data-testid="submit-draft-publish"]').trigger('click')
    await flushPromises()
    expect(requestDraftProtocolPublication).toHaveBeenCalledWith(expect.objectContaining({ targetId: 'READY-2', expectedSequence: 8 }))
    wrapper.unmount()
  })

  it('在适配器与历史抽屉中保留回退预览、回退提交与历史竞态保护', async () => {
    const oldHistory = deferred<Awaited<ReturnType<typeof listProtocolDeploymentHistory>>>()
    const targetB = { ...readyTarget, targetId: 'READY-2', name: '适配器 B', currentSequence: 8 }
    vi.mocked(listProtocolPublicationTargets).mockResolvedValue([readyTarget, targetB] as never)
    vi.mocked(listProtocolDeploymentHistory).mockImplementation(targetId => targetId === 'READY-1'
      ? oldHistory.promise
      : Promise.resolve([{ targetId: 'READY-2', sequence: 7, digest: 'digest-b', approvalId: 'B1', status: 'LOADED', errorCode: null, createdAt: 2000, loadedAt: 2100 }] as never))
    vi.mocked(previewProtocolRollback).mockResolvedValueOnce({ targetId: 'READY-2', expectedSequence: 8, digest: 'rb', currentVersions: [], targetVersions: [], changes: [] })

    const wrapper = mount(ProtocolPublicationPanel, {
      attachTo: document.body,
      props: { active: true, draftId: null, draftRevision: null, productEnabled: false, selectedProduct: null },
    })
    await flushPromises()
    wrapper.vm.openOperationsDrawer('history')
    await flushPromises()

    wrapper.findComponent(ElSelect).vm.$emit('change', 'READY-1')
    wrapper.findComponent(ElSelect).vm.$emit('change', 'READY-2')
    await flushPromises()
    oldHistory.resolve([{ targetId: 'READY-1', sequence: 1, digest: 'digest-a', approvalId: 'A1', status: 'LOADED', errorCode: null, createdAt: 1000, loadedAt: 1100 }] as never)
    await flushPromises()

    const rollbackButtons = [...document.body.querySelectorAll('button')].filter(button => button.textContent?.trim() === '申请回退')
    expect(rollbackButtons).toHaveLength(1)
    rollbackButtons[0].click()
    await flushPromises()
    expect(previewProtocolRollback).toHaveBeenCalledWith({ targetId: 'READY-2', expectedSequence: 8, historicalSequence: 7 })
    const confirm = document.body.querySelector('[data-testid="confirm-publication"]') as HTMLButtonElement
    confirm.click()
    await flushPromises()
    expect(requestProtocolRollback).toHaveBeenCalledWith({
      targetId: 'READY-2', historicalSequence: 7, expectedSequence: 8, idempotencyKey: expect.any(String),
    })
    wrapper.unmount()
  })

  it('登记目标后列表刷新失败仍立即展示一次性密钥', async () => {
    vi.mocked(registerProtocolPublicationTarget).mockResolvedValue({ target: readyTarget, oneTimeKey: 'ONE-TIME-KEY' } as never)
    const wrapper = mount(ProtocolPublicationPanel, {
      attachTo: document.body,
      props: { active: true, draftId: null, draftRevision: null, productEnabled: false, selectedProduct: null },
    })
    await flushPromises()
    vi.mocked(listProtocolPublicationTargets).mockRejectedValueOnce(new Error('refresh failed'))
    await wrapper.findAllComponents(ElButton).find(button => button.text() === '登记适配器目标')!.trigger('click')
    await flushPromises()
    const nameInput = document.body.querySelector('input[maxlength="100"]') as HTMLInputElement
    nameInput.value = '隔离适配器'
    nameInput.dispatchEvent(new Event('input', { bubbles: true }))
    const topicsInput = document.body.querySelector('textarea[rows="4"]') as HTMLTextAreaElement
    topicsInput.value = 'raw/a'
    topicsInput.dispatchEvent(new Event('input', { bubbles: true }))
    const registerButtons = [...document.body.querySelectorAll('button')].filter(button => button.textContent?.trim() === '登记适配器目标')
    registerButtons.at(-1)!.click()
    await flushPromises()
    expect(registerProtocolPublicationTarget).toHaveBeenCalledWith({ name: '隔离适配器', outputVersion: 'V1', allowedTopics: ['raw/a'] })
    expect((document.body.querySelector('input[aria-label="一次性目标密钥"]') as HTMLInputElement).value).toBe('ONE-TIME-KEY')
    wrapper.unmount()
  })
})
