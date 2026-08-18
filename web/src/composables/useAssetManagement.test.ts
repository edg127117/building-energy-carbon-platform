import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createAssetBuilding,
  pageAssetBuildings,
  pageAssetEquipment,
} from '@/api/assetManagement'
import type { AssetEquipmentView, AssetPageResponse } from '@/types/assets'
import { useAssetManagement } from './useAssetManagement'

vi.mock('@/api/assetManagement', () => ({
  createAssetBuilding: vi.fn(), createAssetEquipment: vi.fn(), createAssetSpace: vi.fn(), createAssetSystemGroup: vi.fn(),
  deleteAssetBuilding: vi.fn(), deleteAssetEquipment: vi.fn(), deleteAssetEquipmentPoint: vi.fn(), deleteAssetSpace: vi.fn(), deleteAssetSystemGroup: vi.fn(),
  getAssetBuilding: vi.fn(), getAssetEquipment: vi.fn(), listAssetEquipmentPoints: vi.fn(), listAssetSpaces: vi.fn(),
  pageAssetBuildings: vi.fn(), pageAssetEquipment: vi.fn(), pageAssetSystemGroups: vi.fn(),
  updateAssetBuilding: vi.fn(), updateAssetEquipment: vi.fn(), updateAssetEquipmentPoint: vi.fn(), updateAssetSpace: vi.fn(), updateAssetSystemGroup: vi.fn(),
}))

vi.mock('@/utils/request', () => ({
  requestErrorMessage: (reason: unknown) => (reason as { response?: { status?: number } })?.response?.status === 403
    ? '当前账号没有权限执行此操作'
    : '请求失败，请稍后重试',
}))

const emptyBuildingPage = { page: 1, size: 20, total: 0, items: [] }
const emptyEquipmentPage: AssetPageResponse<AssetEquipmentView> = { page: 1, size: 20, total: 0, items: [] }

describe('asset management asynchronous ownership', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(pageAssetBuildings).mockResolvedValue(emptyBuildingPage)
    vi.mocked(pageAssetEquipment).mockResolvedValue(emptyEquipmentPage)
  })

  it('keeps the latest equipment query result when an earlier request completes late', async () => {
    const first = deferred<typeof emptyEquipmentPage>()
    const latest: AssetPageResponse<AssetEquipmentView> = { page: 1, size: 20, total: 1, items: [equipment('latest', '新设备')] }
    vi.mocked(pageAssetEquipment).mockReturnValueOnce(first.promise).mockResolvedValueOnce(latest)
    const assets = useAssetManagement()
    const staleLoad = assets.loadEquipment()
    const latestLoad = assets.setEquipmentQuery({ keyword: '新设备' })
    await latestLoad
    first.resolve({ page: 1, size: 20, total: 1, items: [equipment('stale', '旧设备')] })
    await staleLoad
    expect(assets.equipmentPage.value.items[0]).toMatchObject({ equipmentId: 'latest' })
  })

  it('locks the same create command until its refresh completes', async () => {
    vi.mocked(createAssetBuilding).mockResolvedValue({ buildingId: 'B-01', buildingName: '综合楼', status: 'ACTIVE' } as never)
    const assets = useAssetManagement()
    await Promise.all([
      assets.saveBuilding(null, { buildingName: '综合楼', status: 'ACTIVE' }),
      assets.saveBuilding(null, { buildingName: '综合楼', status: 'ACTIVE' }),
    ])
    expect(createAssetBuilding).toHaveBeenCalledTimes(1)
    expect(pageAssetBuildings).toHaveBeenCalledTimes(1)
    expect(assets.pending.value.size).toBe(0)
  })

  it('marks a 403 list failure as a permission state instead of treating it as empty data', async () => {
    vi.mocked(pageAssetBuildings).mockRejectedValueOnce({ response: { status: 403 } })
    const assets = useAssetManagement()
    await expect(assets.loadBuildings()).rejects.toMatchObject({ response: { status: 403 } })
    expect(assets.buildingPage.value.items).toEqual([])
    expect(assets.buildingsError.value).toMatchObject({ forbidden: true })
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function equipment(equipmentId: string, equipmentName: string): AssetEquipmentView {
  return {
    equipmentId, equipmentName, equipmentCode: null, buildingId: 'B-01', buildingName: '综合楼', spaceId: null, spaceName: null,
    systemGroupId: null, systemGroupName: null, typeCode: null, productId: null, productName: null, status: 'ACTIVE', lastDiscoveredTime: null,
    category: null, expectedProfileCode: null, pointSummary: { total: 0, required: 0, configuredRequired: 0 },
    allowedActions: ['UPDATE', 'DELETE'], updateTime: 0,
  }
}
