import { beforeEach, describe, expect, it, vi } from 'vitest'

const { requestApi } = vi.hoisted(() => ({
  requestApi: vi.fn(),
}))

vi.mock('@/infrastructure/http/public', () => ({
  requestApi,
}))

import {
  getIndicatorCalculationDetail,
  getHvacIndicatorTrends,
  getHvacPointHistory,
  getHvacSnapshot,
  getLatestHvacIndicators,
  listAccessibleBuildings,
} from './hvac'

describe('HVAC API client', () => {
  beforeEach(() => {
    requestApi.mockReset()
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
    requestApi.mockResolvedValue({
      records,
      total: 1,
      size: 100,
      current: 1,
      pages: 1,
    })

    await expect(listAccessibleBuildings()).resolves.toEqual(records)
    expect(requestApi).toHaveBeenCalledWith({
      method: 'GET', url: '/building/list', params: { page: 1, size: 100 },
    })
  })

  it('queries the selected building snapshot', async () => {
    const payload = {
      buildingId: 'BLD001',
      generatedAt: 1,
      points: [],
    }
    requestApi.mockResolvedValue(payload)

    await expect(getHvacSnapshot('BLD001')).resolves.toEqual(payload)
    expect(requestApi).toHaveBeenCalledWith({
      method: 'GET', url: '/hvac/buildings/BLD001/snapshot',
    })
  })

  it('queries the selected building latest indicators', async () => {
    const payload = {
      buildingId: 'BLD001',
      generatedAt: 1,
      indicators: [],
    }
    requestApi.mockResolvedValue(payload)

    await expect(getLatestHvacIndicators('BLD001')).resolves.toEqual(payload)
    expect(requestApi).toHaveBeenCalledWith({
      method: 'GET', url: '/hvac/buildings/BLD001/indicators/latest',
    })
  })

  it('queries one real indicator calculation detail', async () => {
    const payload = {
      indicatorId: 'WCR/COP 01',
      indicatorCode: 'WCR_COP',
      equipId: 'WCR001',
      minuteStart: 1_754_208_000_000,
      status: 'SUCCESS',
      value: 4.2,
      unit: null,
      dataQuality: 1,
      formulaVersion: 'WCR_COP_V1',
      inputs: [],
      steps: [],
      reasonCode: null,
      missingInputs: [],
    }
    requestApi.mockResolvedValue(payload)

    await expect(
      getIndicatorCalculationDetail('WCR/COP 01', payload.minuteStart),
    ).resolves.toEqual(payload)
    expect(requestApi).toHaveBeenCalledWith({
      method: 'GET',
      url: `/hvac/indicators/WCR%2FCOP%2001/calculations/${payload.minuteStart}`,
    })
  })

  it('queries chart-ready indicator trends in one request', async () => {
    const payload = {
      buildingId: 'BLD001',
      from: 100,
      to: 200,
      resolutionMinutes: 1,
      series: [],
    }
    requestApi.mockResolvedValue(payload)

    await expect(
      getHvacIndicatorTrends('BLD 001', ['I/1', 'I2'], 100, 200),
    ).resolves.toEqual(payload)
    expect(requestApi).toHaveBeenCalledWith({
      method: 'GET',
      url: '/hvac/buildings/BLD%20001/indicators/trends',
      params: { indicatorIds: 'I/1,I2', from: 100, to: 200 },
    })
  })

  it('queries up to eight raw point histories in one request', async () => {
    const payload = {
      buildingId: 'BLD001',
      from: 100,
      to: 200,
      resolutionMinutes: 1,
      series: [],
    }
    requestApi.mockResolvedValue(payload)

    await expect(
      getHvacPointHistory('BLD001', ['P1', 'P2'], 100, 200),
    ).resolves.toEqual(payload)
    expect(requestApi).toHaveBeenCalledWith({
      method: 'GET',
      url: '/hvac/buildings/BLD001/history',
      params: { pointIds: 'P1,P2', from: 100, to: 200 },
    })
  })
})
