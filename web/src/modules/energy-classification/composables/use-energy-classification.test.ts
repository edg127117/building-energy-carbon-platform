import { beforeEach, describe, expect, it, vi } from 'vitest'
import { classificationApi } from '../api/classification'
import { emptyConfiguration, type ClassificationQueryRequest, type ClassificationQueryView, type ClassificationVersion } from '../models/classification'
import { createBindingBatch } from '../models/classification-editor'
import { useEnergyClassification } from './use-energy-classification'

vi.mock('../api/classification', () => ({
  classificationApi: {
    listVersions: vi.fn(), getVersion: vi.fn(), createVersion: vi.fn(), saveVersion: vi.fn(), submitVersion: vi.fn(), withdrawVersion: vi.fn(), rejectVersion: vi.fn(), approveVersion: vi.fn(),
    query: vi.fn(), previewReadings: vi.fn(), importReadings: vi.fn(), getReadingBatch: vi.fn(), approveReadingBatch: vi.fn(), listReadings: vi.fn(),
  },
}))
vi.mock('@/shared/utils/request-error', () => ({ requestErrorMessage: () => '请求失败', requestErrorCode: () => null }))

describe('通用计量分类异步状态', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(classificationApi.listVersions).mockResolvedValue([])
    vi.mocked(classificationApi.getVersion).mockResolvedValue(version('V-01'))
    vi.mocked(classificationApi.createVersion).mockResolvedValue(version('V-01'))
    vi.mocked(classificationApi.saveVersion).mockResolvedValue(version('V-01'))
    vi.mocked(classificationApi.submitVersion).mockResolvedValue(version('V-01'))
    vi.mocked(classificationApi.withdrawVersion).mockResolvedValue(version('V-01'))
    vi.mocked(classificationApi.rejectVersion).mockResolvedValue(version('V-01'))
    vi.mocked(classificationApi.approveVersion).mockResolvedValue(version('V-01'))
    vi.mocked(classificationApi.query).mockResolvedValue(queryView('V-01'))
    vi.mocked(classificationApi.previewReadings).mockResolvedValue({ batchId: 'batch-1', status: 'PENDING_REVIEW', accepted: 0, issues: [] })
    vi.mocked(classificationApi.importReadings).mockResolvedValue({ batchId: 'batch-1', status: 'PENDING_REVIEW', accepted: 0, issues: [] })
    vi.mocked(classificationApi.getReadingBatch).mockResolvedValue({ batchId: 'batch-1', buildingId: 'B-01', status: 'PENDING_REVIEW', revision: 7, createdBy: 1, count: 1 })
    vi.mocked(classificationApi.approveReadingBatch).mockResolvedValue({ batchId: 'batch-1', status: 'APPROVED', accepted: 0, issues: [] })
    vi.mocked(classificationApi.listReadings).mockResolvedValue([])
  })

  it('空版本列表保持为空，读取失败展示受控错误', async () => {
    const workspace = useEnergyClassification()

    await workspace.loadBuilding('B-01')
    expect(workspace.versions.value).toEqual([])
    expect(workspace.selectedVersion.value).toBeNull()

    vi.mocked(classificationApi.listVersions).mockRejectedValueOnce(new Error('transport'))
    await workspace.loadBuilding('B-02')
    expect(workspace.versionsError.value).toEqual({ message: '请求失败', code: null })
  })

  it('忽略迟到建筑版本列表，保留最新建筑选择', async () => {
    const stale = deferred<ClassificationVersion[]>()
    vi.mocked(classificationApi.listVersions)
      .mockReturnValueOnce(stale.promise)
      .mockResolvedValueOnce([version('V-new', 2, 'B-new')])
    const workspace = useEnergyClassification()

    const first = workspace.loadBuilding('B-old')
    await workspace.loadBuilding('B-new')
    stale.resolve([version('V-old', 1, 'B-old')])
    await first

    expect(workspace.buildingId.value).toBe('B-new')
    expect(workspace.selectedVersion.value?.versionId).toBe('V-new')
  })

  it('保存响应迟到时不覆盖用户已经切换的版本', async () => {
    vi.mocked(classificationApi.listVersions).mockResolvedValueOnce([version('V-01')])
    const saved = deferred<ClassificationVersion>()
    vi.mocked(classificationApi.saveVersion).mockReturnValueOnce(saved.promise)
    vi.mocked(classificationApi.getVersion).mockResolvedValueOnce(version('V-02', 2))
    const workspace = useEnergyClassification()

    await workspace.loadBuilding('B-01')
    const saving = workspace.saveSelectedVersion('保存草稿')
    await Promise.resolve()
    await workspace.selectVersion('V-02')
    saved.resolve({ ...version('V-01'), revision: 2 })
    await saving

    expect(workspace.selectedVersion.value?.versionId).toBe('V-02')
    expect(workspace.selectedVersion.value?.revision).toBe(1)
  })

  it('批量绑定随整份配置单次保存', async () => {
    vi.mocked(classificationApi.listVersions).mockResolvedValueOnce([version('V-01')])
    const workspace = useEnergyClassification()
    await workspace.loadBuilding('B-01')
    workspace.configuration.value.schemes.push({ code: 'USAGE', name: '用途', dimension: 'USAGE', mode: 'ADDITIVE' })
    workspace.configuration.value.nodes.push({ schemeCode: 'USAGE', code: 'HVAC', name: '空调', parentCode: null })
    workspace.configuration.value.bindings.push(...createBindingBatch({
      schemeCode: 'USAGE', nodeCode: 'HVAC', objectType: 'EQUIPMENT',
      objectIds: Array.from({ length: 100 }, (_, index) => `equipment-${index}`),
      evidence: '来自设备清单',
    }))

    await workspace.saveSelectedVersion('批量保存绑定')

    expect(classificationApi.saveVersion).toHaveBeenCalledTimes(1)
    expect(vi.mocked(classificationApi.saveVersion).mock.calls[0]?.[2].configuration.bindings).toHaveLength(100)
  })

  it('查询响应在版本切换后清空，不能冒充新版本结果', async () => {
    vi.mocked(classificationApi.listVersions).mockResolvedValueOnce([version('V-01')])
    const delayed = deferred<ClassificationQueryView>()
    vi.mocked(classificationApi.query).mockReturnValueOnce(delayed.promise)
    vi.mocked(classificationApi.getVersion).mockResolvedValueOnce(version('V-02', 2))
    const workspace = useEnergyClassification()

    await workspace.loadBuilding('B-01')
    const running = workspace.runQuery(queryRequest('V-01'))
    await Promise.resolve()
    await workspace.selectVersion('V-02')
    delayed.resolve(queryView('V-01'))
    await running

    expect(workspace.selectedVersion.value?.versionId).toBe('V-02')
    expect(workspace.queryResult.value).toBeNull()
    expect(workspace.queryError.value).toBeNull()
  })

  it('导入批次审核先读取当前服务端修订号，不再硬编码初始修订号', async () => {
    const workspace = useEnergyClassification()
    await workspace.loadBuilding('B-01')
    workspace.importedBatch.value = { batchId: 'batch-1', status: 'PENDING_REVIEW', accepted: 1, issues: [] }

    await workspace.approveImportedBatch('独立审核原因')

    expect(classificationApi.getReadingBatch).toHaveBeenCalledWith('B-01', 'batch-1')
    expect(classificationApi.approveReadingBatch).toHaveBeenCalledWith('B-01', 'batch-1', {
      expectedRevision: 7, reason: '独立审核原因', effectiveFrom: null,
    })
  })
})

function version(versionId: string, versionNo = 1, buildingId = 'B-01'): ClassificationVersion {
  return {
    versionId, buildingId, versionNo, revision: 1, status: 'DRAFT', effectiveFrom: null, effectiveTo: null,
    createdBy: 1, reviewedBy: null, configuration: emptyConfiguration(), issues: [],
  }
}

function queryRequest(versionId: string): ClassificationQueryRequest {
  return {
    startInclusive: '2026-10-01T00:00:00Z', endExclusive: '2026-11-01T00:00:00Z', periodType: 'MONTH',
    energyItemCode: 'ELECTRICITY', unit: 'kWh', schemeCode: 'USAGE', versionId, filters: [],
  }
}

function queryView(versionId: string): ClassificationQueryView {
  return {
    buildingId: 'B-01', versionId, relationVersionId: null, unit: 'kWh', startInclusive: '2026-10-01T00:00:00Z', endExclusive: '2026-11-01T00:00:00Z',
    total: '12.50', assigned: '12.50', unallocated: '0.00', residual: '0.00', status: 'CONFIRMED', resultNature: 'ADDITIVE', totalKind: 'BOUNDARY_TOTAL', additive: true,
    nodes: [], balances: [], issues: [], evidenceIds: [],
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
