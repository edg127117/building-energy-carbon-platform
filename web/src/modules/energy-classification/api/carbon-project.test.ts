import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { carbonProjectApi, energyCatalogApi } from './carbon-project'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('项目能源与碳边界接口契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestApi).mockResolvedValue({} as never)
  })

  it('读取项目版本和就绪状态，并按修订号保存及流转', async () => {
    const configuration = { classificationVersionId: 'CV-1', energies: [], exclusions: [] }
    await carbonProjectApi.listVersions('B/01')
    await carbonProjectApi.getReadiness('B/01', 'P/01')
    await carbonProjectApi.createVersion('B/01', '新建草稿')
    await carbonProjectApi.saveVersion('B/01', 'P/01', { expectedRevision: 7, configuration, reason: '修改边界' })
    await carbonProjectApi.transition('B/01', 'P/01', 'activate', { expectedRevision: 8, reason: '正式启用' })

    expect(requestApi).toHaveBeenNthCalledWith(1, expect.objectContaining({ method: 'get', url: '/v1/carbon-projects/buildings/B%2F01/versions' }))
    expect(requestApi).toHaveBeenNthCalledWith(2, expect.objectContaining({ method: 'get', url: '/v1/carbon-projects/buildings/B%2F01/versions/P%2F01/readiness' }))
    expect(requestApi).toHaveBeenNthCalledWith(3, expect.objectContaining({ method: 'post', url: '/v1/carbon-projects/buildings/B%2F01/versions', data: { reason: '新建草稿', copiedFromVersionId: null } }))
    expect(requestApi).toHaveBeenNthCalledWith(4, expect.objectContaining({ method: 'put', url: '/v1/carbon-projects/buildings/B%2F01/versions/P%2F01', data: { expectedRevision: 7, configuration, reason: '修改边界' } }))
    expect(requestApi).toHaveBeenNthCalledWith(5, expect.objectContaining({ method: 'post', url: '/v1/carbon-projects/buildings/B%2F01/versions/P%2F01/activate', data: { expectedRevision: 8, reason: '正式启用' } }))
  })

  it('只通过审核目录读取能源品种选项', async () => {
    await energyCatalogApi.listItems()
    expect(requestApi).toHaveBeenCalledWith(expect.objectContaining({ method: 'get', url: '/v1/energy-catalog/items' }))
  })

  it('影响预览只传版本和期间，不传页面计算的用量', async () => {
    await carbonProjectApi.impact('B/01', 'P/01', '2026-07-31T16:00:00Z', '2026-08-31T16:00:00Z')
    expect(requestApi).toHaveBeenCalledWith(expect.objectContaining({ method: 'get', url: '/v1/carbon-projects/buildings/B%2F01/versions/P%2F01/impact', params: { startInclusive: '2026-07-31T16:00:00Z', endExclusive: '2026-08-31T16:00:00Z' } }))
  })
})
