import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { parameterPackagesApi } from './parameter-packages'
import type { ParameterPackageRequest } from '../models/parameter-packages'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

const request: ParameterPackageRequest = {
  packageCode: 'B01',
  itemCodes: ['STANDARD_COAL_LHV'],
  source: {
    sourceCode: 'GB-T-2589', sourceName: '综合能耗计算通则', publisher: '国家标准化管理机构', documentReference: 'GB/T 2589',
    sourceDocumentVersion: '2020', publicationYear: 2020, publishedOn: '2020-01-01', applicabilityNote: '正式核算',
    evidenceReference: '档案索引', energySourceType: 'STANDARD',
  },
  effectiveFrom: '2026-01-01T00:00:00',
  effectiveTo: null,
  standardConditionCode: null,
  dependencies: { standardCoalLhvVersionId: null, naturalGasFormulaVersionId: null, rawCoalFormulaVersionId: null, rawCoalEnergyItemCode: null },
  electricity: { applicabilityLevel: '', regionCode: null, dataYear: null, accountingYear: null },
  heat: { co2eEvidenceReference: '' },
  idempotencyKey: 'parameter-import-key',
}

describe('专业参数包接口契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestApi).mockResolvedValue({} as never)
  })

  it('按建筑读取目录，不把建筑标识混入手工请求体', async () => {
    const controller = new AbortController()
    await parameterPackagesApi.catalog('B/01', controller.signal)

    expect(requestApi).toHaveBeenCalledWith({
      method: 'get', url: '/v1/energy-parameter-packages/catalog', params: { buildingId: 'B/01' }, signal: controller.signal,
    })
  })

  it('预检与导入发送同一经确认的请求内容和幂等键', async () => {
    await parameterPackagesApi.preview(request)
    await parameterPackagesApi.importPackage(request)

    expect(requestApi).toHaveBeenNthCalledWith(1, { method: 'post', url: '/v1/energy-parameter-packages/preview', data: request })
    expect(requestApi).toHaveBeenNthCalledWith(2, { method: 'post', url: '/v1/energy-parameter-packages/import', data: request })
    expect(request.idempotencyKey).toBe('parameter-import-key')
  })
})
