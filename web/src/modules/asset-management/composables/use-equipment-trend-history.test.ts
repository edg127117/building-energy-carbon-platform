import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  calculateQueryTimeRange,
  clearSessionTrendCache,
  convertSeriesToTrendRecords,
  MAX_REALTIME_POINTS,
  useEquipmentTrendHistory,
} from './use-equipment-trend-history'
import type { AssetEquipmentTrendHistory, AssetPointReading } from '../models/assets'
import * as api from '../api/assets'

vi.mock('../api/assets', () => ({
  getEquipmentTrendHistory: vi.fn(),
}))

describe('useEquipmentTrendHistory', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearSessionTrendCache()
  })

  describe('calculateQueryTimeRange', () => {
    const fixedNow = new Date('2026-09-21T12:00:00.000Z').getTime()

    it('calculates 1-hour interval for realtime mode', () => {
      const q = calculateQueryTimeRange('realtime', null, fixedNow)
      expect(q.intervalSeconds).toBe(10)
      expect(new Date(q.endTime).getTime()).toBe(fixedNow)
      expect(new Date(q.startTime).getTime()).toBe(fixedNow - 3600 * 1000)
    })

    it('calculates 6h range', () => {
      const q = calculateQueryTimeRange('6h', null, fixedNow)
      expect(q.intervalSeconds).toBe(60)
      expect(new Date(q.startTime).getTime()).toBe(fixedNow - 6 * 3600 * 1000)
    })

    it('calculates today range starting at 00:00:00', () => {
      const q = calculateQueryTimeRange('today', null, fixedNow)
      const startDate = new Date(q.startTime)
      expect(startDate.getHours()).toBe(0)
      expect(startDate.getMinutes()).toBe(0)
      expect(startDate.getSeconds()).toBe(0)
    })

    it('calculates 24h range', () => {
      const q = calculateQueryTimeRange('24h', null, fixedNow)
      expect(q.intervalSeconds).toBe(120)
      expect(new Date(q.startTime).getTime()).toBe(fixedNow - 24 * 3600 * 1000)
    })

    it('calculates custom range dynamically', () => {
      const start = new Date('2026-09-20T08:00:00.000Z')
      const end = new Date('2026-09-21T08:00:00.000Z')
      const q = calculateQueryTimeRange('custom', [start, end], fixedNow)
      expect(q.startTime).toBe(start.toISOString())
      expect(q.endTime).toBe(end.toISOString())
      expect(q.intervalSeconds).toBeGreaterThan(10)
    })
  })

  describe('convertSeriesToTrendRecords', () => {
    it('converts 3P series into sorted MeterTrendRecord array', () => {
      const series = [
        {
          pointCode: 'P_TOTAL',
          pointName: '总有功功率',
          unit: 'kW',
          data: [
            [1000, 30.5],
            [2000, 31.0],
          ] as [number, number | null][],
        },
        {
          pointCode: 'I_A',
          pointName: 'A相电流',
          unit: 'A',
          data: [
            [1000, 45.2],
            [2000, 45.8],
          ] as [number, number | null][],
        },
        {
          pointCode: 'U_A',
          pointName: 'A相电压',
          unit: 'V',
          data: [
            [1000, 220.1],
            [2000, 220.3],
          ] as [number, number | null][],
        },
      ]

      const records = convertSeriesToTrendRecords(series, '3P')
      expect(records).toHaveLength(2)
      expect(records[0]).toEqual({
        time: 1000,
        power: 30.5,
        currentA: 45.2,
        currentB: null,
        currentC: null,
        voltage: 220.1,
      })
      expect(records[1].power).toBe(31.0)
    })

    it('converts 1P series into sorted MeterTrendRecord array', () => {
      const series = [
        {
          pointCode: 'P',
          pointName: '用电功率',
          unit: 'kW',
          data: [
            [1000, 2.5],
          ] as [number, number | null][],
        },
        {
          pointCode: 'U',
          pointName: '工作电压',
          unit: 'V',
          data: [
            [1000, 221.0],
          ] as [number, number | null][],
        },
      ]

      const records = convertSeriesToTrendRecords(series, '1P')
      expect(records).toHaveLength(1)
      expect(records[0].power).toBe(2.5)
      expect(records[0].voltage).toBe(221.0)
    })
  })

  describe('useEquipmentTrendHistory state and lifecycle', () => {
    it('initializes and falls back gracefully when backend history API is not ready (404/pending)', async () => {
      vi.mocked(api.getEquipmentTrendHistory).mockRejectedValueOnce({
        response: { status: 404 },
      })

      const { records, apiPending, loading, init } = useEquipmentTrendHistory()
      init('eq-1', '3P')
      expect(loading.value).toBe(true)

      await vi.waitFor(() => expect(loading.value).toBe(false))
      expect(apiPending.value).toBe(true)
      expect(records.value).toHaveLength(0)
    })

    it('populates records when backend history API succeeds', async () => {
      const mockHistory: AssetEquipmentTrendHistory = {
        equipmentId: 'eq-1',
        startTime: '2026-09-21T10:00:00Z',
        endTime: '2026-09-21T11:00:00Z',
        series: [
          {
            pointCode: 'P_TOTAL',
            pointName: '总有功功率',
            unit: 'kW',
            data: [[1700000000000, 15.6]],
          },
        ],
      }
      vi.mocked(api.getEquipmentTrendHistory).mockResolvedValueOnce(mockHistory)

      const { records, apiPending, loading, init } = useEquipmentTrendHistory()
      init('eq-1', '3P')

      await vi.waitFor(() => expect(loading.value).toBe(false))
      expect(apiPending.value).toBe(false)
      expect(records.value).toHaveLength(1)
      expect(records.value[0].power).toBe(15.6)
    })

    it('appends realtime reading and maintains maximum buffer size', () => {
      const { records, appendRealtimeReading } = useEquipmentTrendHistory()
      const mockPoints: AssetPointReading[] = [
        {
          pointId: 'p1',
          pointCode: 'P_TOTAL',
          pointName: '总有功功率',
          unit: 'kW',
          value: 10.0,
          dataQuality: 0,
          status: 'NORMAL',
          usageStatus: 'NORMAL',
          eventTime: null,
          receivedTime: null,
          reason: null,
        },
      ]

      for (let i = 0; i < MAX_REALTIME_POINTS + 10; i++) {
        appendRealtimeReading(mockPoints, 1000 + i * 1000, 'eq-1', '3P')
      }

      expect(records.value.length).toBe(MAX_REALTIME_POINTS)
      expect(records.value[records.value.length - 1].time).toBe(1000 + (MAX_REALTIME_POINTS + 9) * 1000)
    })

    it('restores cached points from session cache on drawer reopen', () => {
      const instance1 = useEquipmentTrendHistory()
      const mockPoints: AssetPointReading[] = [
        {
          pointId: 'p1',
          pointCode: 'P_TOTAL',
          pointName: '总有功功率',
          unit: 'kW',
          value: 12.0,
          dataQuality: 0,
          status: 'NORMAL',
          usageStatus: 'NORMAL',
          eventTime: null,
          receivedTime: null,
          reason: null,
        },
      ]
      instance1.appendRealtimeReading(mockPoints, 1700000000000, 'eq-cache-test', '3P')
      expect(instance1.records.value).toHaveLength(1)

      // Mock getEquipmentTrendHistory to hang so we see instant cached render
      vi.mocked(api.getEquipmentTrendHistory).mockImplementationOnce(() => new Promise(() => {}))

      const instance2 = useEquipmentTrendHistory()
      instance2.init('eq-cache-test', '3P')
      // Immediately has cached records!
      expect(instance2.records.value).toHaveLength(1)
      expect(instance2.records.value[0].power).toBe(12.0)
    })

    it('processes PR 94 full three-phase backend series response accurately', async () => {
      const pr94BackendResponse: AssetEquipmentTrendHistory = {
        equipmentId: 'EQUIP_3P_B1',
        buildingId: 'BLD001',
        startTime: '2026-09-21T00:00:00.000Z',
        endTime: '2026-09-21T06:00:00.000Z',
        series: [
          {
            pointCode: 'P_TOTAL',
            pointName: '总有功功率',
            unit: 'kW',
            data: [
              [1700000000000, 35.5],
              [1700000060000, 38.2],
            ],
          },
          {
            pointCode: 'I_A',
            pointName: 'A相电流',
            unit: 'A',
            data: [
              [1700000000000, 52.1],
              [1700000060000, 55.4],
            ],
          },
          {
            pointCode: 'I_B',
            pointName: 'B相电流',
            unit: 'A',
            data: [
              [1700000000000, 51.8],
              [1700000060000, 54.9],
            ],
          },
          {
            pointCode: 'I_C',
            pointName: 'C相电流',
            unit: 'A',
            data: [
              [1700000000000, 52.3],
              [1700000060000, 55.1],
            ],
          },
          {
            pointCode: 'U_A',
            pointName: 'A相电压',
            unit: 'V',
            data: [
              [1700000000000, 220.8],
              [1700000060000, 221.2],
            ],
          },
        ],
      }
      vi.mocked(api.getEquipmentTrendHistory).mockResolvedValueOnce(pr94BackendResponse)

      const { records, apiPending, loading, isLargeDataset, init } = useEquipmentTrendHistory()
      init('EQUIP_3P_B1', '3P')

      await vi.waitFor(() => expect(loading.value).toBe(false))
      expect(apiPending.value).toBe(false)
      expect(isLargeDataset.value).toBe(false)
      expect(records.value).toHaveLength(2)

      expect(records.value[0]).toEqual({
        time: 1700000000000,
        power: 35.5,
        currentA: 52.1,
        currentB: 51.8,
        currentC: 52.3,
        voltage: 220.8,
      })
      expect(records.value[1]).toEqual({
        time: 1700000060000,
        power: 38.2,
        currentA: 55.4,
        currentB: 54.9,
        currentC: 55.1,
        voltage: 221.2,
      })
    })

    it('triggers loadHistory with valid query parameters when switching range types', async () => {
      vi.mocked(api.getEquipmentTrendHistory).mockResolvedValue({
        equipmentId: 'eq-switch',
        startTime: '2026-09-21T00:00:00.000Z',
        endTime: '2026-09-21T06:00:00.000Z',
        series: [],
      })

      const { setRangeType, setCustomRange, rangeType, customRange } = useEquipmentTrendHistory()

      setRangeType('6h', 'eq-switch', '3P')
      expect(rangeType.value).toBe('6h')
      expect(api.getEquipmentTrendHistory).toHaveBeenLastCalledWith(
        'eq-switch',
        expect.objectContaining({
          intervalSeconds: 60,
        }),
      )

      setRangeType('today', 'eq-switch', '3P')
      expect(rangeType.value).toBe('today')
      expect(api.getEquipmentTrendHistory).toHaveBeenLastCalledWith(
        'eq-switch',
        expect.objectContaining({
          intervalSeconds: 120,
        }),
      )

      const customStart = new Date('2026-09-18T00:00:00.000Z')
      const customEnd = new Date('2026-09-20T00:00:00.000Z')
      setCustomRange([customStart, customEnd], 'eq-switch', '3P')
      expect(customRange.value).toEqual([customStart, customEnd])
      expect(api.getEquipmentTrendHistory).toHaveBeenLastCalledWith(
        'eq-switch',
        expect.objectContaining({
          startTime: customStart.toISOString(),
          endTime: customEnd.toISOString(),
        }),
      )
    })
  })
})
