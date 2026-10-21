import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { manualPeriodsApi } from './manual-periods'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('正式人工月量接口契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestApi).mockResolvedValue({} as never)
  })

  it('按建筑、测点、月初和可选已审核口径读取后端上下文', async () => {
    const controller = new AbortController()
    await manualPeriodsApi.context('B/1', 'P-1', '2026-08-01', undefined, controller.signal)
    await manualPeriodsApi.context('B/1', 'P-1', '2026-08-01', 'POLICY/1', controller.signal)

    expect(requestApi).toHaveBeenNthCalledWith(1, expect.objectContaining({
      method: 'get', url: '/v1/energy-periods/buildings/B%2F1/manual-months/context',
      params: { pointId: 'P-1', month: '2026-08-01' }, signal: controller.signal,
    }))
    expect(requestApi).toHaveBeenNthCalledWith(2, expect.objectContaining({
      method: 'get', url: '/v1/energy-periods/buildings/B%2F1/manual-months/context',
      params: { pointId: 'P-1', month: '2026-08-01', policyVersionId: 'POLICY/1' }, signal: controller.signal,
    }))
  })

  it('分别提交预检、候选、正式口径、封账和独立审核契约', async () => {
    const request = {
      pointId: 'P-1', month: '2026-08-01', policyVersionId: 'POLICY-1', relationVersionId: 'REL-1',
      factIds: ['FACT-1'], expectedProjectionRevision: 3, reason: '人工已审核来源连续覆盖整月',
    }
    await manualPeriodsApi.preview('B-1', request)
    await manualPeriodsApi.createCandidate('B-1', request)
    await manualPeriodsApi.createFormalPolicy({
      buildingId: 'B-1', timezoneId: 'Asia/Shanghai', closingDelayHours: 24,
      lockMode: 'REVIEW_REQUIRED', sourceType: 'FORMAL', evidenceReference: '治理依据',
      effectiveFrom: '2026-08-01T00:00', effectiveTo: null,
    })
    await manualPeriodsApi.approvePolicy('POLICY/1', { expectedRevision: 0, reviewComment: '独立复核通过' })
    await manualPeriodsApi.submitLock({ projectionId: 'PROJ-1', expectedRevision: 4, reason: '申请封账', evidenceReference: '封账证据' })
    await manualPeriodsApi.approveLock('LOCK/1', { reviewComment: '独立审核通过' })

    expect(requestApi).toHaveBeenNthCalledWith(1, expect.objectContaining({ method: 'post', url: '/v1/energy-periods/buildings/B-1/manual-months/preview', data: request }))
    expect(requestApi).toHaveBeenNthCalledWith(2, expect.objectContaining({ method: 'post', url: '/v1/energy-periods/buildings/B-1/manual-months', data: request }))
    expect(requestApi).toHaveBeenNthCalledWith(3, expect.objectContaining({ method: 'post', url: '/v1/energy-periods/policies' }))
    expect(requestApi).toHaveBeenNthCalledWith(4, expect.objectContaining({ method: 'post', url: '/v1/energy-periods/policies/POLICY%2F1/approve', data: { expectedRevision: 0, reviewComment: '独立复核通过' } }))
    expect(requestApi).toHaveBeenNthCalledWith(5, expect.objectContaining({ method: 'post', url: '/v1/energy-periods/lock-requests', data: { projectionId: 'PROJ-1', expectedRevision: 4, reason: '申请封账', evidenceReference: '封账证据' } }))
    expect(requestApi).toHaveBeenNthCalledWith(6, expect.objectContaining({ method: 'post', url: '/v1/energy-periods/lock-requests/LOCK%2F1/approve', data: { reviewComment: '独立审核通过' } }))
  })
})
