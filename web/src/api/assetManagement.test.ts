import { beforeEach, describe, expect, it, vi } from 'vitest'
import { http } from '@/utils/request'
import { requestErrorMessage } from '@/utils/request'
import {
  createAssetBuilding,
  deleteAssetEquipmentPoint,
  getAssetEquipment,
  pageAssetBuildings,
  pageAssetEquipment,
  updateAssetEquipmentPoint,
} from './assetManagement'
import { presentAssetStatus } from '@/types/assets'

vi.mock('@/utils/request', () => ({
  http: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  requestErrorMessage: (reason: unknown) => {
    const status = (reason as { response?: { status?: number } })?.response?.status
    return status === 403 ? '当前账号没有权限执行此操作' : status === 409 ? '当前状态不允许此操作，请刷新后重试' : '请求失败，请稍后重试'
  },
}))

const contractBuildingPage = {
  data: {
    success: true,
    code: 200,
    msg: 'ok',
    data: {
      page: 1,
      size: 20,
      total: 1,
      items: [{ buildingId: 'B-01', buildingName: '综合楼', buildingCode: 'HQ', buildingType: 'OFFICE', climateZone: 'HOT_SUMMER_COLD_WINTER', address: null, status: 'RETIRED_BY_SERVER', references: { equipment: 0, points: 0, authorizations: 0 } }],
    },
  },
}

describe('asset management api contract client', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(http.get).mockResolvedValue(contractBuildingPage)
    vi.mocked(http.post).mockResolvedValue({ data: { success: true, code: 200, msg: 'ok', data: contractBuildingPage.data.data.items[0] } })
    vi.mocked(http.put).mockResolvedValue({ data: { success: true, code: 200, msg: 'ok', data: {} } })
    vi.mocked(http.delete).mockResolvedValue({ data: { success: true, code: 200, msg: 'ok', data: null } })
  })

  it('consumes a V1 page sample and preserves an unknown status for the safe presentation layer', async () => {
    const page = await pageAssetBuildings({ page: 1, size: 20, keyword: '综合' })
    expect(http.get).toHaveBeenCalledWith('/v1/assets/buildings', { params: { page: 1, size: 20, keyword: '综合' } })
    expect(page).toEqual(contractBuildingPage.data.data)
    expect(presentAssetStatus(page.items[0].status)).toEqual({ label: '未知（RETIRED_BY_SERVER）', color: 'default', known: false })
  })

  it('maps empty device data and all point command paths without leaking Axios responses', async () => {
    vi.mocked(http.get).mockResolvedValueOnce({ data: { success: true, code: 200, msg: 'ok', data: { page: 1, size: 20, total: 0, items: [] } } })
    await expect(pageAssetEquipment({ page: 1, size: 20, buildingId: 'B-01' })).resolves.toEqual({ page: 1, size: 20, total: 0, items: [] })
    vi.mocked(http.get).mockResolvedValueOnce({ data: { success: true, code: 200, msg: 'ok', data: { equipmentId: 'E-01' } } })
    await getAssetEquipment('E/01')
    await createAssetBuilding({ buildingName: '综合楼', status: 'ACTIVE' })
    await updateAssetEquipmentPoint('E/01', 'P/01', { minValue: 0, maxValue: 100, forCalculation: true, status: 'ACTIVE' })
    await deleteAssetEquipmentPoint('E/01', 'P/01')
    expect(http.get).toHaveBeenLastCalledWith('/v1/assets/equipment/E%2F01')
    expect(http.post).toHaveBeenCalledWith('/v1/assets/buildings', { buildingName: '综合楼', status: 'ACTIVE' })
    expect(http.put).toHaveBeenCalledWith('/v1/assets/equipment/E%2F01/points/P%2F01', { minValue: 0, maxValue: 100, forCalculation: true, status: 'ACTIVE' })
    expect(http.delete).toHaveBeenCalledWith('/v1/assets/equipment/E%2F01/points/P%2F01')
  })

  it('does not swallow permission and concurrent-state failures from the shared HTTP client', async () => {
    const forbidden = { response: { status: 403 } }
    const conflict = { response: { status: 409 } }
    vi.mocked(http.get).mockRejectedValueOnce(forbidden).mockRejectedValueOnce(conflict)
    await expect(pageAssetEquipment({ page: 1, size: 20 })).rejects.toBe(forbidden)
    await expect(pageAssetEquipment({ page: 1, size: 20 })).rejects.toBe(conflict)
    expect(requestErrorMessage(forbidden)).toBe('当前账号没有权限执行此操作')
    expect(requestErrorMessage(conflict)).toBe('当前状态不允许此操作，请刷新后重试')
  })
})
