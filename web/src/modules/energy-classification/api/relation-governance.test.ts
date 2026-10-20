import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { collectRelationPages, RelationCatalogueLimitError, relationGovernanceApi } from './relation-governance'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('计量关系治理接口契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestApi).mockResolvedValue({} as never)
  })

  it('读取可引用边界和节点目录，并写入真实结构和分配', async () => {
    await relationGovernanceApi.listBoundaryCatalogue('B/01', 'V/01', 2, 50)
    await relationGovernanceApi.listNodes('B/01', 'V/01', 'POINT', 3, 25)
    await relationGovernanceApi.createStructure('V/01', { meteringBoundaryId: 'boundary-1', meterPointNodeId: 'point-1', meterRole: 'MAIN', parentMeterPointNodeId: null, meterDirection: 'INBOUND', confirmationStatus: 'CONFIRMED', reasonCode: null, reasonText: null, evidenceReference: 'evidence-1', description: null, expectedRevision: 4 }, 'key-1')
    await relationGovernanceApi.createAssignment('V/01', { meteringBoundaryId: 'boundary-1', meterPointNodeId: 'point-1', targetNodeId: 'space-1', allocationStatus: 'ASSIGNED', reasonCode: null, reasonText: null, evidenceReference: 'evidence-1', expectedRevision: 5 }, 'key-2')

    expect(requestApi).toHaveBeenNthCalledWith(1, expect.objectContaining({ method: 'get', url: '/v1/relation-models/B%2F01/versions/V%2F01/metering/boundaries', params: { page: 2, size: 50 } }))
    expect(requestApi).toHaveBeenNthCalledWith(2, expect.objectContaining({ method: 'get', url: '/v1/relation-models/B%2F01/versions/V%2F01/nodes', params: { nodeType: 'POINT', page: 3, size: 25 } }))
    expect(requestApi).toHaveBeenNthCalledWith(3, expect.objectContaining({ method: 'post', url: '/v1/relation-models/versions/V%2F01/metering/structures', headers: { 'Idempotency-Key': 'key-1' } }))
    expect(requestApi).toHaveBeenNthCalledWith(4, expect.objectContaining({ method: 'post', url: '/v1/relation-models/versions/V%2F01/metering/assignments', headers: { 'Idempotency-Key': 'key-2' } }))
  })

  it('按后端返回的页大小拉完目录，超过安全上限时拒绝截断', async () => {
    const loadPage = vi.fn(async (page: number, size: number) => {
      void size
      return { metadata: null, page, size: 2, total: 3, items: page === 1 ? ['first', 'second'] : ['third'] }
    })

    await expect(collectRelationPages(loadPage)).resolves.toMatchObject({ items: ['first', 'second', 'third'] })
    expect(loadPage).toHaveBeenNthCalledWith(1, 1, 100)
    expect(loadPage).toHaveBeenNthCalledWith(2, 2, 2)
    await expect(collectRelationPages(async () => ({ metadata: null, page: 1, size: 100, total: 5001, items: [] }))).rejects.toBeInstanceOf(RelationCatalogueLimitError)
  })
})
