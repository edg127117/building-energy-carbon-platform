import { beforeEach, describe, expect, it, vi } from 'vitest'

const { get } = vi.hoisted(() => ({
  get: vi.fn(),
}))

vi.mock('@/utils/request', () => ({
  http: { get },
}))

import {
  getHvacSnapshot,
  getLatestHvacIndicators,
  listAccessibleBuildings,
} from './hvac'

describe('HVAC API client', () => {
  beforeEach(() => {
    get.mockReset()
  })

  it('lists buildings already accessible to the current user', async () => {
    const records = [
      {
        buildingId: 'BLD001',
        buildingName: '所在大楼',
        buildingCode: 'BLD001',
        buildingType: '办公',
        climateZone: '夏热冬冷',
      },
    ]
    get.mockResolvedValue({
      data: {
        success: true,
        code: 200,
        msg: 'success',
        data: {
          records,
          total: 1,
          size: 100,
          current: 1,
          pages: 1,
        },
      },
    })

    await expect(listAccessibleBuildings()).resolves.toEqual(records)
    expect(get).toHaveBeenCalledWith('/building/list', {
      params: { page: 1, size: 100 },
    })
  })

  it('queries the selected building snapshot', async () => {
    const payload = {
      buildingId: 'BLD001',
      generatedAt: 1,
      points: [],
    }
    get.mockResolvedValue({
      data: { success: true, code: 200, msg: 'success', data: payload },
    })

    await expect(getHvacSnapshot('BLD001')).resolves.toEqual(payload)
    expect(get).toHaveBeenCalledWith('/hvac/buildings/BLD001/snapshot')
  })

  it('queries the selected building latest indicators', async () => {
    const payload = {
      buildingId: 'BLD001',
      generatedAt: 1,
      indicators: [],
    }
    get.mockResolvedValue({
      data: { success: true, code: 200, msg: 'success', data: payload },
    })

    await expect(getLatestHvacIndicators('BLD001')).resolves.toEqual(payload)
    expect(get).toHaveBeenCalledWith(
      '/hvac/buildings/BLD001/indicators/latest',
    )
  })
})
