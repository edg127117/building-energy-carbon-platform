import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { carbonFactorsApi } from './carbon-factors'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('碳因子维护接口契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestApi).mockResolvedValue({} as never)
  })

  it('读取来源、因子、有效规则和既有能源目录', async () => {
    const controller = new AbortController()
    await carbonFactorsApi.listSources(controller.signal)
    await carbonFactorsApi.listFactors(controller.signal)
    await carbonFactorsApi.getRuleOptions(controller.signal)
    await carbonFactorsApi.listEnergyItems(controller.signal)
    await carbonFactorsApi.listEnergyUnits(controller.signal)
    await carbonFactorsApi.listEnergyCompatibilities(controller.signal)

    expect(requestApi).toHaveBeenNthCalledWith(1, expect.objectContaining({ method: 'get', url: '/v1/carbon-management/factor-sources', signal: controller.signal }))
    expect(requestApi).toHaveBeenNthCalledWith(2, expect.objectContaining({ method: 'get', url: '/v1/carbon-management/factors', signal: controller.signal }))
    expect(requestApi).toHaveBeenNthCalledWith(3, expect.objectContaining({ method: 'get', url: '/v1/carbon-management/rule-options', signal: controller.signal }))
    expect(requestApi).toHaveBeenNthCalledWith(4, expect.objectContaining({ method: 'get', url: '/v1/energy-catalog/items', signal: controller.signal }))
    expect(requestApi).toHaveBeenNthCalledWith(5, expect.objectContaining({ method: 'get', url: '/v1/energy-catalog/units', signal: controller.signal }))
    expect(requestApi).toHaveBeenNthCalledWith(6, expect.objectContaining({ method: 'get', url: '/v1/energy-catalog/compatibilities', signal: controller.signal }))
  })

  it('向服务端提交来源、因子和带修订号的独立审核生命周期操作', async () => {
    const source = { sourceCode: 'SRC', sourceName: '来源', publisher: '发布方', documentReference: '文号', publicationYear: null, publishedOn: null, applicabilityNote: '适用说明', evidenceReference: '证据', usageNature: 'FORMAL' as const }
    const factor = {
      factorCode: 'FACTOR', scopeType: 'SCOPE_2' as const, energyItemCode: 'ELECTRICITY', factorCategory: 'PURCHASED_ELECTRICITY_LOCATION' as const,
      resultBasis: 'CO2E_DIRECT', gasCode: 'CO2E', gasCoverage: 'DIRECT_FACTOR_GAS_COVERAGE', sourceVersionId: 'SV/1', applicabilityLevel: 'NATIONAL' as const,
      buildingId: null, regionCode: null, inputUnitCode: 'KWH', standardConditionCode: null, usageNature: 'FORMAL' as const,
      effectiveFrom: '2026-01-01T00:00', effectiveTo: null, formulaVersionId: 'CFV/1', roundingPolicyVersionId: 'CRP/1',
      components: [{ componentType: 'DIRECT_EMISSION_FACTOR' as const, value: '0.5', unit: 'KG_CO2E/KWH', sourceVersionId: 'SV/1', evidenceReference: '证据' }],
      dataYear: null, accountingYear: null,
    }

    await carbonFactorsApi.createSource(source)
    await carbonFactorsApi.createFactor(factor)
    await carbonFactorsApi.reviewFactor('FV/1', { expectedRevision: 3, approved: false, reviewComment: '证据不足' })
    await carbonFactorsApi.activateFactor('FV/1', { expectedRevision: 4 })
    await carbonFactorsApi.disableFactor('FV/1', { expectedRevision: 5 })

    expect(requestApi).toHaveBeenNthCalledWith(1, expect.objectContaining({ method: 'post', url: '/v1/carbon-management/factor-sources', data: source }))
    expect(requestApi).toHaveBeenNthCalledWith(2, expect.objectContaining({ method: 'post', url: '/v1/carbon-management/factors', data: factor }))
    expect(requestApi).toHaveBeenNthCalledWith(3, expect.objectContaining({ method: 'post', url: '/v1/carbon-management/factors/FV%2F1/review', data: { expectedRevision: 3, approved: false, reviewComment: '证据不足' } }))
    expect(requestApi).toHaveBeenNthCalledWith(4, expect.objectContaining({ method: 'post', url: '/v1/carbon-management/factors/FV%2F1/activate', data: { expectedRevision: 4 } }))
    expect(requestApi).toHaveBeenNthCalledWith(5, expect.objectContaining({ method: 'post', url: '/v1/carbon-management/factors/FV%2F1/disable', data: { expectedRevision: 5 } }))
  })

  it('按建筑维护真实面积和人数分母，并在独立审核后激活', async () => {
    const denominator = {
      buildingId: 'B/01', denominatorType: 'BUILDING_AREA' as const, value: '1200.5', unitCode: 'M2' as const,
      sourceReference: '产权资料索引', evidenceReference: '归档凭证', usageNature: 'FORMAL' as const,
      effectiveFrom: '2026-01-01', effectiveTo: null,
    }

    await carbonFactorsApi.listDenominators('B/01')
    await carbonFactorsApi.createDenominator(denominator)
    await carbonFactorsApi.reviewDenominator('DV/01', { expectedRevision: 2, approved: true, reviewComment: '独立复核通过' })
    await carbonFactorsApi.activateDenominator('DV/01', { expectedRevision: 3 })

    expect(requestApi).toHaveBeenNthCalledWith(1, expect.objectContaining({ method: 'get', url: '/v1/carbon-management/denominators', params: { buildingId: 'B/01' } }))
    expect(requestApi).toHaveBeenNthCalledWith(2, expect.objectContaining({ method: 'post', url: '/v1/carbon-management/denominators', data: denominator }))
    expect(requestApi).toHaveBeenNthCalledWith(3, expect.objectContaining({ method: 'post', url: '/v1/carbon-management/denominators/DV%2F01/review', data: { expectedRevision: 2, approved: true, reviewComment: '独立复核通过' } }))
    expect(requestApi).toHaveBeenNthCalledWith(4, expect.objectContaining({ method: 'post', url: '/v1/carbon-management/denominators/DV%2F01/activate', data: { expectedRevision: 3 } }))
  })
})
