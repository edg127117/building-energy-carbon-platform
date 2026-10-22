import { describe, expect, it, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import { TransportError } from '@/infrastructure/http/public'
import { carbonFactorsApi } from '../api/carbon-factors'
import { useCarbonFactors } from './use-carbon-factors'

vi.mock('../api/carbon-factors', () => ({
  carbonFactorsApi: {
    listSources: vi.fn(), listFactors: vi.fn(), getRuleOptions: vi.fn(), listEnergyItems: vi.fn(), listEnergyUnits: vi.fn(), listEnergyCompatibilities: vi.fn(),
    createSource: vi.fn(), createFactor: vi.fn(), reviewFactor: vi.fn(), activateFactor: vi.fn(), disableFactor: vi.fn(),
  },
}))

const source = (sourceVersionId: string) => ({ sourceId: 'S', sourceCode: 'SRC', sourceVersionId, versionNo: 1, sourceName: '来源', publisher: '发布方', documentReference: '文号', publicationYear: null, publishedOn: null, applicabilityNote: '说明', evidenceReference: '证据', usageNature: 'FORMAL', createdBy: 1, createdAt: '2026-01-01T00:00:00' })
const factor = (factorVersionId: string, revision = 1) => ({ factorId: 'F', factorCode: 'FACTOR', factorVersionId, versionNo: 1, scopeType: 'SCOPE_2', energyItemCode: 'ELECTRICITY', factorCategory: 'PURCHASED_ELECTRICITY_LOCATION', resultBasis: 'CO2E_DIRECT', gasCode: 'CO2E', gasCoverage: 'DIRECT_FACTOR_GAS_COVERAGE', sourceVersionId: 'SV', applicabilityLevel: 'NATIONAL', buildingId: null, regionCode: null, inputUnitCode: 'KWH', standardConditionCode: null, usageNature: 'FORMAL', status: 'PENDING_REVIEW', effectiveFrom: '2026-01-01T00:00:00', effectiveTo: null, formulaVersionId: 'CFV', roundingPolicyVersionId: 'CRP', configRevision: revision, createdBy: 1, createdAt: '2026-01-01T00:00:00', reviewedBy: null, reviewedAt: null, reviewComment: null, activatedBy: null, activatedAt: null, components: [], dataYear: null, accountingYear: null })

function readyLists() {
  vi.mocked(carbonFactorsApi.listSources).mockResolvedValue([source('SV-1')] as never)
  vi.mocked(carbonFactorsApi.listFactors).mockResolvedValue([factor('FV-1')] as never)
  vi.mocked(carbonFactorsApi.getRuleOptions).mockResolvedValue({
    formulas: [{ versionId: 'CFV-1', code: 'FORMULA', versionNo: 1, algorithmCode: 'PURCHASED_ELECTRICITY_LOCATION_CO2E_V1', resultBasis: 'CO2E_DIRECT', usageNature: 'FORMAL', effectiveFrom: '2026-01-01T00:00:00', effectiveTo: null }],
    roundingPolicies: [{ versionId: 'CRP-1', code: 'ROUNDING', versionNo: 1, totalScale: 6, intensityScale: 6, shareScale: 4, roundingMode: 'HALF_UP' }],
  } as never)
  vi.mocked(carbonFactorsApi.listEnergyItems).mockResolvedValue([
    { itemId: 'I-1', itemCode: 'ELEC', versionId: 'EV-1', versionNo: 1, itemName: '电力', compatibleCategory: 'ELECTRICITY', usageScopes: ['PURCHASED_ELECTRICITY'], status: 'APPROVED' },
    { itemId: 'I-2', itemCode: 'GAS', versionId: 'EV-2', versionNo: 1, itemName: '燃气', compatibleCategory: 'FUEL', usageScopes: ['STATIONARY_COMBUSTION'], status: 'DRAFT' },
  ] as never)
  vi.mocked(carbonFactorsApi.listEnergyUnits).mockResolvedValue([
    { unitId: 'U-1', unitCode: 'KWH', versionId: 'UV-1', versionNo: 1, symbol: 'kWh', unitName: '千瓦时', dimensionCode: 'ENERGY', precision: 6, status: 'APPROVED' },
    { unitId: 'U-2', unitCode: 'MJ', versionId: 'UV-2', versionNo: 1, symbol: 'MJ', unitName: '兆焦', dimensionCode: 'ENERGY', precision: 6, status: 'DRAFT' },
  ] as never)
  vi.mocked(carbonFactorsApi.listEnergyCompatibilities).mockResolvedValue([
    { compatibilityId: 'C-1', versionId: 'CV-1', versionNo: 1, energyItemCode: 'ELEC', unitCode: 'KWH', allowed: true, status: 'APPROVED' },
    { compatibilityId: 'C-2', versionId: 'CV-2', versionNo: 1, energyItemCode: 'ELEC', unitCode: 'MJ', allowed: false, status: 'APPROVED' },
  ] as never)
}

describe('碳因子维护状态', () => {
  it('只将已批准能源品种、单位和允许兼容关系提供给录入界面', async () => {
    readyLists()
    const workspace = useCarbonFactors(() => 'B-1')
    await vi.waitFor(() => expect(workspace.factors.value).toHaveLength(1))

    expect(workspace.approvedEnergyItems.value.map(item => item.itemCode)).toEqual(['ELEC'])
    expect(workspace.approvedEnergyUnits.value.map(item => item.unitCode)).toEqual(['KWH'])
    expect(workspace.approvedEnergyCompatibilities.value.map(item => item.unitCode)).toEqual(['KWH'])
    expect(workspace.ruleOptions.value.formulas.map(item => item.versionId)).toEqual(['CFV-1'])
  })

  it('建筑切换后忽略旧上下文的迟到读取', async () => {
    let resolveOld!: (value: ReturnType<typeof source>[]) => void
    vi.mocked(carbonFactorsApi.listSources).mockImplementationOnce(() => new Promise(done => { resolveOld = done }) as never).mockResolvedValueOnce([source('SV-2')] as never)
    vi.mocked(carbonFactorsApi.listFactors).mockResolvedValue([factor('FV-2')] as never)
    vi.mocked(carbonFactorsApi.getRuleOptions).mockResolvedValue({ formulas: [], roundingPolicies: [] } as never)
    vi.mocked(carbonFactorsApi.listEnergyItems).mockResolvedValue([])
    vi.mocked(carbonFactorsApi.listEnergyUnits).mockResolvedValue([])
    vi.mocked(carbonFactorsApi.listEnergyCompatibilities).mockResolvedValue([])
    const building = ref('B-1')
    const workspace = useCarbonFactors(() => building.value)

    building.value = 'B-2'
    await nextTick()
    await vi.waitFor(() => expect(workspace.sources.value.map(item => item.sourceVersionId)).toEqual(['SV-2']))
    resolveOld([source('SV-1')])
    await nextTick()

    expect(workspace.sources.value.map(item => item.sourceVersionId)).toEqual(['SV-2'])
  })

  it('保留生命周期版本冲突错误，不把审核简化为本地状态变更', async () => {
    readyLists()
    vi.mocked(carbonFactorsApi.reviewFactor).mockRejectedValue(new TransportError('request', 409, 409, 'VERSION_CONFLICT'))
    const workspace = useCarbonFactors(() => 'B-1')
    await vi.waitFor(() => expect(workspace.factors.value[0]?.factorVersionId).toBe('FV-1'))

    await expect(workspace.reviewFactor('FV-1', { expectedRevision: 1, approved: true, reviewComment: '独立审核通过' })).resolves.toBe(false)
    expect(carbonFactorsApi.reviewFactor).toHaveBeenCalledWith('FV-1', { expectedRevision: 1, approved: true, reviewComment: '独立审核通过' })
    expect(workspace.error.value).toContain('状态已变化')
    expect(workspace.factors.value[0]?.status).toBe('PENDING_REVIEW')
  })
})
