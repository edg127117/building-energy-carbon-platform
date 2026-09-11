import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AssetEquipment, AssetPage } from '../models/assets'
import { createBuilding, listBuildings, listEquipment } from '../api/assets'
import { useAssetManagement } from './use-asset-management'

vi.mock('../api/assets', () => ({
  createBuilding: vi.fn(),
  createEquipment: vi.fn(),
  createSpace: vi.fn(),
  createSystemGroup: vi.fn(),
  deleteBuilding: vi.fn(),
  deleteEquipment: vi.fn(),
  deleteEquipmentPoint: vi.fn(),
  deleteSpace: vi.fn(),
  deleteSystemGroup: vi.fn(),
  getBuilding: vi.fn(),
  getEquipment: vi.fn(),
  listBuildings: vi.fn(),
  listEquipment: vi.fn(),
  listEquipmentPoints: vi.fn(),
  listSpaces: vi.fn(),
  listSystemGroups: vi.fn(),
  updateBuilding: vi.fn(),
  updateEquipment: vi.fn(),
  updateEquipmentPoint: vi.fn(),
  updateSpace: vi.fn(),
  updateSystemGroup: vi.fn(),
}))
vi.mock('@/shared/utils/request-error', () => ({ requestErrorMessage: () => '请求失败' }))

const emptyBuildings = { page: 1, size: 20, total: 0, items: [] }
const emptyEquipment: AssetPage<AssetEquipment> = { page: 1, size: 20, total: 0, items: [] }

describe('资产档案异步状态', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listBuildings).mockResolvedValue(emptyBuildings)
    vi.mocked(listEquipment).mockResolvedValue(emptyEquipment)
  })

  it('忽略迟到的设备查询响应，只保留最新筛选结果', async () => {
    const stale = deferred<AssetPage<AssetEquipment>>()
    vi.mocked(listEquipment)
      .mockReturnValueOnce(stale.promise)
      .mockResolvedValueOnce({ page: 1, size: 20, total: 1, items: [equipment('latest')] })
    const management = useAssetManagement()

    const firstLoad = management.loadEquipment()
    await management.setEquipmentQuery({ keyword: '最新' })
    stale.resolve({ page: 1, size: 20, total: 1, items: [equipment('stale')] })
    await firstLoad

    expect(management.equipment.value.items[0]?.equipmentId).toBe('latest')
  })

  it('同一建筑创建在刷新完成前只会发送一次', async () => {
    vi.mocked(createBuilding).mockResolvedValue({} as never)
    const management = useAssetManagement()
    const form = {
      buildingName: '楼宇', buildingCode: null, buildingType: null, constructionYear: null,
      totalGfa: null, climateZone: null, status: 'ACTIVE' as const,
    }

    await Promise.all([management.saveBuilding(null, form), management.saveBuilding(null, form)])

    expect(createBuilding).toHaveBeenCalledTimes(1)
    expect(listBuildings).toHaveBeenCalledTimes(1)
    expect(management.pending.value.size).toBe(0)
  })

  it('将档案写入失败保留为受控错误状态', async () => {
    vi.mocked(createBuilding).mockRejectedValueOnce(new Error('transport'))
    const management = useAssetManagement()

    await expect(management.saveBuilding(null, {
      buildingName: '楼宇', buildingCode: null, buildingType: null, constructionYear: null,
      totalGfa: null, climateZone: null, status: 'ACTIVE',
    })).rejects.toThrow('transport')

    expect(management.operationError.value).toEqual({ message: '请求失败' })
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

function equipment(equipmentId: string): AssetEquipment {
  return {
    equipmentId,
    equipmentCode: null,
    equipmentName: equipmentId,
    typeCode: 'AHU',
    category: null,
    buildingId: 'B-01',
    buildingName: null,
    spaceId: null,
    spaceName: null,
    systemGroupId: null,
    systemGroupName: null,
    productId: null,
    productName: null,
    status: 'UNBOUND',
    expectedProfileCode: null,
    lastDiscoveredTime: null,
    pointSummary: { total: 0, required: 0, configuredRequired: 0 },
    allowedActions: [],
    updateTime: 0,
  }
}
