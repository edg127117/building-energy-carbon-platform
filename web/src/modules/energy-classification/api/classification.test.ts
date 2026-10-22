import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { classificationApi } from './classification'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('通用计量分类接口契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestApi).mockResolvedValue({} as never)
  })

  it('按建筑和版本转义读取、保存及流转分类配置', async () => {
    await classificationApi.listVersions('B/01')
    await classificationApi.getVersion('B/01', 'V/01')
    await classificationApi.saveVersion('B/01', 'V/01', { expectedRevision: 3, configuration: { relationVersionId: null, schemes: [], nodes: [], bindings: [], boundaryRules: [] }, reason: '修订' })
    await classificationApi.submitVersion('B/01', 'V/01', { expectedRevision: 3, reason: '提交', effectiveFrom: null })
    await classificationApi.approveVersion('B/01', 'V/01', { expectedRevision: 4, reason: '审核', effectiveFrom: '2026-10-19T00:00:00Z' })

    expect(requestApi).toHaveBeenNthCalledWith(1, expect.objectContaining({ method: 'get', url: '/v1/energy-classification/buildings/B%2F01/versions' }))
    expect(requestApi).toHaveBeenNthCalledWith(2, expect.objectContaining({ method: 'get', url: '/v1/energy-classification/buildings/B%2F01/versions/V%2F01' }))
    expect(requestApi).toHaveBeenNthCalledWith(3, expect.objectContaining({ method: 'put', url: '/v1/energy-classification/buildings/B%2F01/versions/V%2F01', data: expect.objectContaining({ expectedRevision: 3, reason: '修订' }) }))
    expect(requestApi).toHaveBeenNthCalledWith(4, expect.objectContaining({ method: 'post', url: '/v1/energy-classification/buildings/B%2F01/versions/V%2F01/submit', data: expect.objectContaining({ effectiveFrom: null }) }))
    expect(requestApi).toHaveBeenNthCalledWith(5, expect.objectContaining({ method: 'post', url: '/v1/energy-classification/buildings/B%2F01/versions/V%2F01/approve', data: expect.objectContaining({ effectiveFrom: '2026-10-19T00:00:00Z' }) }))
  })

  it('将统计、人工读数预览、导入、批次详情和独立审批发到对应端点', async () => {
    const readingRequest = { idempotencyKey: 'key-1', readings: [], reason: '人工抄表' }
    await classificationApi.query('B-01', { startInclusive: '2026-10-01T00:00:00Z', endExclusive: '2026-11-01T00:00:00Z', periodType: 'MONTH', energyItemCode: 'ELECTRICITY', unit: 'kWh', schemeCode: 'USAGE', versionId: 'V-01', filters: [{ schemeCode: 'DEPARTMENT', nodeCode: 'D-01' }] })
    await classificationApi.previewReadings('B-01', readingRequest)
    await classificationApi.importReadings('B-01', readingRequest)
    await classificationApi.getReadingBatch('B-01', 'batch/01')
    await classificationApi.approveReadingBatch('B-01', 'batch/01', { expectedRevision: 7, reason: '独立审核', effectiveFrom: null })
    await classificationApi.listReadings('B-01', { startInclusive: '2026-10-01T00:00:00Z', endExclusive: '2026-11-01T00:00:00Z' })

    expect(requestApi).toHaveBeenNthCalledWith(1, expect.objectContaining({ method: 'post', url: '/v1/energy-classification/buildings/B-01/query', data: expect.objectContaining({ filters: [{ schemeCode: 'DEPARTMENT', nodeCode: 'D-01' }] }) }))
    expect(requestApi).toHaveBeenNthCalledWith(2, expect.objectContaining({ method: 'post', url: '/v1/energy-classification/buildings/B-01/readings/preview', data: readingRequest }))
    expect(requestApi).toHaveBeenNthCalledWith(3, expect.objectContaining({ method: 'post', url: '/v1/energy-classification/buildings/B-01/readings/import', data: readingRequest }))
    expect(requestApi).toHaveBeenNthCalledWith(4, expect.objectContaining({ method: 'get', url: '/v1/energy-classification/buildings/B-01/readings/batches/batch%2F01' }))
    expect(requestApi).toHaveBeenNthCalledWith(5, expect.objectContaining({ method: 'post', url: '/v1/energy-classification/buildings/B-01/readings/batches/batch%2F01/approve', data: expect.objectContaining({ expectedRevision: 7 }) }))
    expect(requestApi).toHaveBeenNthCalledWith(6, expect.objectContaining({ method: 'get', url: '/v1/energy-classification/buildings/B-01/readings', params: { startInclusive: '2026-10-01T00:00:00Z', endExclusive: '2026-11-01T00:00:00Z' } }))
  })
})
