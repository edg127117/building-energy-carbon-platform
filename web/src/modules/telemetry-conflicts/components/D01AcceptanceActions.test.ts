import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { TransportError } from '@/infrastructure/http/public'
import { telemetryConflictApi as api } from '../api/telemetry-conflicts'
import type { ConflictView, CorrectionVersion, RecoveryTask } from '../models/telemetry-conflicts'
import CorrectionReview from './CorrectionReview.vue'
import ConflictRecoveryPanel from './ConflictRecoveryPanel.vue'

const session = vi.hoisted(() => ({ user: { id: 9, roles: ['ENERGY_MANAGER'] } }))
vi.mock('@/modules/auth/public', async importOriginal => ({ ...await importOriginal<typeof import('@/modules/auth/public')>(), useSession: () => session }))
vi.mock('../api/telemetry-conflicts', () => ({ telemetryConflictApi: {
  correctionVersions: vi.fn(), approveCorrection: vi.fn(), recovery: vi.fn(), retry: vi.fn(),
} }))
const conflict = { buildingId: 'B1', pointId: 'P1', eventTime: 1000, correctionVersionId: 'C1', revision: 99 } as ConflictView
const version: CorrectionVersion = {
  correctionVersionId: 'C1', buildingId: 'B1', meterPointId: 'P1', originalFactIdentity: 'P1@1000',
  originalValue: 100, correctedValue: 120, correctionReason: '核实', evidenceReference: '工单',
  status: 'PENDING_REVIEW', configRevision: 3, createdBy: 7,
}
const task: RecoveryTask = { kind: 'RAW', id: 'P1@1000', pointId: 'P1', eventTime: 1000, retryCount: 8, errorCode: 'WRITE_FAILED' }
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(r => { resolve = r })
  return { promise, resolve }
}
beforeEach(() => {
  vi.resetAllMocks()
  session.user = { id: 9, roles: ['ENERGY_MANAGER'] }
  vi.mocked(api.correctionVersions).mockResolvedValue([version])
  vi.mocked(api.recovery).mockResolvedValue([task])
})
afterEach(() => vi.restoreAllMocks())

describe('D01 既有审核入口', () => {
  it('必填审核意见，提交更正修订号而非冲突修订号，重复点击不重复审核', async () => {
    const pending = deferred<CorrectionVersion>()
    vi.mocked(api.approveCorrection).mockReturnValue(pending.promise)
    const wrapper = mount(CorrectionReview, { props: { conflict } })
    await flushPromises()
    expect(api.correctionVersions).toHaveBeenCalledWith('B1', 'P1')
    expect(wrapper.get('[data-testid="approve-correction"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="correction-review-comment"]').setValue('  核实通过  ')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.approveCorrection).toHaveBeenCalledTimes(1)
    expect(api.approveCorrection).toHaveBeenCalledWith('C1', { expectedRevision: 3, reviewComment: '核实通过' })
    expect(wrapper.emitted('approved')).toBeUndefined()
    pending.resolve({ ...version, status: 'APPROVED' })
    await flushPromises()
    expect(wrapper.emitted('approved')).toHaveLength(1)
    expect(wrapper.text()).toContain('衍生计算仍需等待后台完成')
    wrapper.unmount()
  })

  it.each([403, 409])('服务端 %s 拒绝不显示审核成功，刷新版本后才可再提交', async status => {
    vi.mocked(api.approveCorrection).mockRejectedValue(new TransportError(status === 403 ? 'forbidden' : 'request', status))
    const wrapper = mount(CorrectionReview, { props: { conflict } })
    await flushPromises()
    await wrapper.get('[data-testid="correction-review-comment"]').setValue('审核')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.emitted('approved')).toBeUndefined()
    expect(wrapper.text()).not.toContain('审核已通过')
    expect(wrapper.get('[data-testid="approve-correction"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it.each([
    { correctionVersionId: 'OTHER' }, { buildingId: 'B2' }, { meterPointId: 'P2' }, { originalFactIdentity: 'P1@2000' },
  ])('不能批准其他事实或版本：%j', async mismatch => {
    vi.mocked(api.correctionVersions).mockResolvedValue([{ ...version, ...mismatch }])
    const wrapper = mount(CorrectionReview, { props: { conflict } })
    await flushPromises()
    expect(wrapper.text()).toContain('未找到与本次冲突匹配')
    expect(wrapper.get('[data-testid="approve-correction"]').attributes('disabled')).toBeDefined()
    expect(api.approveCorrection).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('切换事实后忽略旧版本迟到响应，已通过版本不可重复审批', async () => {
    const pending = deferred<CorrectionVersion[]>()
    vi.mocked(api.correctionVersions).mockReturnValueOnce(pending.promise).mockResolvedValue([{ ...version, status: 'APPROVED', correctionVersionId: 'C2' }])
    const wrapper = mount(CorrectionReview, { props: { conflict } })
    await wrapper.setProps({ conflict: { ...conflict, correctionVersionId: 'C2' } })
    await flushPromises()
    pending.resolve([version])
    await flushPromises()
    expect(wrapper.text()).toContain('C2')
    await wrapper.get('[data-testid="correction-review-comment"]').setValue('审核')
    await wrapper.get('form').trigger('submit')
    expect(api.approveCorrection).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('建筑负责人不能仅凭页面操作获得能源审核角色', async () => {
    session.user = { id: 7, roles: ['BUILDING_OWNER'] }
    const wrapper = mount(CorrectionReview, { props: { conflict } })
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    expect(api.approveCorrection).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('D01 有界失败任务入口', () => {
  it.each(['RAW', 'MESSAGE', 'DERIVED', 'MESSAGE_CONFLICT'] as const)('%s 使用返回的标识重试且只宣称入队', async kind => {
    session.user = { id: 9, roles: ['PLATFORM_ADMIN'] }
    const row = { ...task, kind, id: `${kind}/id@1000` }
    vi.mocked(api.recovery).mockResolvedValueOnce([row]).mockResolvedValue([])
    const pending = deferred<void>()
    vi.mocked(api.retry).mockReturnValue(pending.promise)
    const wrapper = mount(ConflictRecoveryPanel, { props: { buildingId: 'B1', ownerId: null } })
    await flushPromises()
    expect(wrapper.get('[data-testid="retry-recovery"]').attributes('disabled')).toBeDefined()
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.get('[data-testid="retry-recovery"]').trigger('click')
    await wrapper.get('[data-testid="retry-recovery"]').trigger('click')
    expect(api.retry).toHaveBeenCalledTimes(1)
    expect(api.retry).toHaveBeenCalledWith({ kind, id: row.id })
    pending.resolve()
    await flushPromises()
    expect(wrapper.text()).toContain('重试已入队')
    expect(wrapper.text()).toContain('不代表处理成功')
    expect(wrapper.text()).toContain('每类最多 100 条')
    expect(api.recovery).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it.each([403, 409])('重试 %s 不误报成功，需重新确认', async status => {
    vi.mocked(api.retry).mockRejectedValue(new TransportError(status === 403 ? 'forbidden' : 'request', status))
    const wrapper = mount(ConflictRecoveryPanel, { props: { buildingId: 'B1', ownerId: 9 } })
    await flushPromises()
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.get('[data-testid="retry-recovery"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('重试已入队')
    expect(wrapper.get('[data-testid="retry-recovery"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('非负责人不可重试，切换建筑忽略迟到结果', async () => {
    const pending = deferred<RecoveryTask[]>()
    vi.mocked(api.recovery).mockReturnValueOnce(pending.promise).mockResolvedValue([])
    const wrapper = mount(ConflictRecoveryPanel, { props: { buildingId: 'B1', ownerId: 7 } })
    await wrapper.setProps({ buildingId: 'B2' })
    await flushPromises()
    pending.resolve([task])
    await flushPromises()
    expect(wrapper.find('[data-testid="retry-recovery"]').exists()).toBe(false)
    expect(wrapper.get('input[type="checkbox"]').attributes('disabled')).toBeDefined()
    expect(api.retry).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('入队后查询失败仍保留回执，但不显示无失败任务', async () => {
    vi.mocked(api.retry).mockResolvedValue()
    vi.mocked(api.recovery).mockResolvedValueOnce([task]).mockRejectedValue(new TransportError('network'))
    const wrapper = mount(ConflictRecoveryPanel, { props: { buildingId: 'B1', ownerId: 9 } })
    await flushPromises()
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.get('[data-testid="retry-recovery"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('重试已入队')
    expect(wrapper.text()).toContain('不能据此判断没有积压')
    expect(wrapper.text()).not.toContain('本次查询未发现失败任务')
    wrapper.unmount()
  })
})
