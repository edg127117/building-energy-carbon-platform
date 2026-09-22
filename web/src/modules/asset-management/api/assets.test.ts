import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { createEquipment, getEquipmentReadings, getEquipmentTrendHistory, listBuildings, updateEquipmentPoint } from './assets'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('资产档案接口契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestApi).mockResolvedValue({} as never)
  })

  it('通过统一请求层读取建筑分页数据', async () => {
    await listBuildings({ page: 2, size: 20, keyword: '楼宇' })

    expect(requestApi).toHaveBeenCalledWith({
      method: 'get',
      url: '/v1/assets/buildings',
      params: { page: 2, size: 20, keyword: '楼宇' },
    })
  })

  it('保留设备创建和测点更新的编码标识符与正式路径', async () => {
    await createEquipment({
      buildingId: 'B/01',
      spaceId: 'S/01',
      systemGroupId: 'G/01',
      typeCode: 'AHU',
      equipmentName: '设备',
      productId: null,
      manufacturer: null,
      ratedCapacity: null,
      ratedPower: null,
      designCop: null,
      status: 'ACTIVE',
    })
    await updateEquipmentPoint('E/01', 'P/01', {
      pointName: '供水温度',
      minValue: 0,
      maxValue: 100,
      forCalculation: true,
      status: 'ACTIVE',
    })

    expect(requestApi).toHaveBeenNthCalledWith(1, {
      method: 'post',
      url: '/v1/assets/equipment',
      data: expect.objectContaining({ buildingId: 'B/01', spaceId: 'S/01', systemGroupId: 'G/01' }),
    })
    expect(requestApi).toHaveBeenNthCalledWith(2, {
      method: 'put',
      url: '/v1/assets/equipment/E%2F01/points/P%2F01',
      data: { pointName: '供水温度', minValue: 0, maxValue: 100, forCalculation: true, status: 'ACTIVE' },
    })
  })

  it('通过资产模块正式接口读取设备最近原始事件', async () => {
    await getEquipmentReadings('E/01')
    expect(requestApi).toHaveBeenCalledWith({ method: 'get', url: '/v1/assets/equipment/E%2F01/readings' })
  })

  it('通过资产模块正式接口读取设备历史时序走势', async () => {
    await getEquipmentTrendHistory('EQUIP/01', {
      startTime: '2026-09-21T10:00:00.000Z',
      endTime: '2026-09-21T11:00:00.000Z',
      intervalSeconds: 60,
    })
    expect(requestApi).toHaveBeenCalledWith({
      method: 'get',
      url: '/v1/assets/equipment/EQUIP%2F01/trend-history',
      params: {
        startTime: '2026-09-21T10:00:00.000Z',
        endTime: '2026-09-21T11:00:00.000Z',
        intervalSeconds: 60,
      },
    })
  })
})
