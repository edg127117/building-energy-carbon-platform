import { describe, expect, it } from 'vitest'
import {
  adaptIndicatorTrends,
  adaptPointTrends,
  buildHistoryPointOptions,
  historyQualityLabel,
  presetRange,
  validateHistoryRange,
} from './hvacHistoryTrends'
import type {
  HvacIndicatorTrendResponse,
  HvacPointHistoryResponse,
  SnapshotPoint,
  TrendRecord,
} from '@/types/hvac'

function record(time: number, average: number, dataQuality = 0): TrendRecord {
  return {
    time,
    average,
    minimum: average - 0.5,
    maximum: average + 0.5,
    sampleCount: 3,
    dataQuality,
  }
}

function indicatorResponse(
  times: number[] = [0, 60_000],
): HvacIndicatorTrendResponse {
  const codes = ['WCR_COP', 'TOWER_EFF', 'PUMP_EFF', 'AHU_POW_EFF']
  return {
    buildingId: 'BLD001',
    from: 0,
    to: 240_000,
    resolutionMinutes: 1,
    series: codes.map((indicatorCode, index) => ({
      indicatorId: `I${index + 1}`,
      indicatorCode,
      equipId: `E${index + 1}`,
      records: times.map((time, recordIndex) =>
        record(time, index * 10 + recordIndex + 1, recordIndex % 3),
      ),
    })),
  }
}

function point(
  pointId: string,
  pointCode: string,
  pointName: string,
  unit: string | null,
): SnapshotPoint {
  return {
    pointId,
    pointCode,
    pointName,
    equipId: null,
    equipCode: null,
    unit,
    minute: null,
    average: null,
    minimum: null,
    maximum: null,
    sampleCount: 0,
    dataQuality: null,
    status: 'NO_DATA',
  }
}

describe('HVAC history trend domain adapter', () => {
  it('groups indicators by unit and keeps percent series together', () => {
    const groups = adaptIndicatorTrends(indicatorResponse())

    expect(groups.map((group) => group.unit)).toEqual([
      '',
      '%',
      'W/(m³·h)',
    ])
    expect(groups[1].series.map((series) => series.code)).toEqual([
      'TOWER_EFF',
      'PUMP_EFF',
    ])
    expect(groups[0].series[0]).toMatchObject({
      label: '冷水机组 COP',
      precision: 2,
    })
  })

  it('inserts one null separator when a successful bucket is missing', () => {
    const response = indicatorResponse([0, 60_000, 180_000])
    const points = adaptIndicatorTrends(response)[0].series[0].points

    expect(points.map((item) => [item.time, item.average])).toEqual([
      [0, 1],
      [60_000, 2],
      [120_000, null],
      [180_000, 3],
    ])
    expect(points[2]).toMatchObject({
      minimum: null,
      maximum: null,
      sampleCount: 0,
      dataQuality: null,
    })
  })

  it('keeps backend statistics and quality without frontend averaging', () => {
    const response = indicatorResponse([60_000])
    response.series[0].records[0] = {
      time: 60_000,
      average: 5.876,
      minimum: 5.2,
      maximum: 6.1,
      sampleCount: 4,
      dataQuality: 2,
    }

    expect(adaptIndicatorTrends(response)[0].series[0].points[0]).toEqual({
      time: 60_000,
      average: 5.876,
      minimum: 5.2,
      maximum: 6.1,
      sampleCount: 4,
      dataQuality: 2,
    })
  })

  it('builds only frozen point options in stable order and keeps backend ids', () => {
    const options = buildHistoryPointOptions([
      point('POINT003', 'WCR1_GW', '后端流量名称', 'm³/h'),
      point('EXTRA', 'EXTRA_POINT', '额外测点', 'u'),
      point('POINT001', 'WCR1_TWin', '后端温度名称', '℃'),
    ])

    expect(options).toEqual([
      {
        pointId: 'POINT001',
        pointCode: 'WCR1_TWin',
        label: '冷冻水进水温度',
        unit: '℃',
        precision: 1,
      },
      {
        pointId: 'POINT003',
        pointCode: 'WCR1_GW',
        label: '冷冻水流量',
        unit: 'm³/h',
        precision: 1,
      },
    ])
  })

  it('adapts point history in response order and prefers backend series metadata', () => {
    const options = buildHistoryPointOptions([
      point('P1', 'WCR1_TWin', '快照温度', '℃'),
      point('P2', 'WCR1_GW', '快照流量', 'm³/h'),
    ])
    const response: HvacPointHistoryResponse = {
      buildingId: 'BLD001',
      from: 0,
      to: 300_000,
      resolutionMinutes: 5,
      series: [
        {
          pointId: 'P2',
          pointCode: 'WCR1_GW',
          pointName: '服务端冷冻水流量',
          unit: 'm³/h',
          records: [record(0, 10)],
        },
        {
          pointId: 'P1',
          pointCode: 'WCR1_TWin',
          pointName: '服务端冷冻水温度',
          unit: '℃',
          records: [],
        },
      ],
    }

    const groups = adaptPointTrends(response, options)
    expect(groups.map((group) => group.unit)).toEqual(['m³/h', '℃'])
    expect(groups[0].series[0]).toMatchObject({
      id: 'P2',
      label: '服务端冷冻水流量',
      precision: 1,
    })
    expect(groups[1].series[0].points).toEqual([])
  })

  it('builds preset ranges on the current whole minute', () => {
    expect(presetRange('1h', 3_661_234)).toEqual({
      from: 60_000,
      to: 3_660_000,
    })
    expect(presetRange('7d', 604_860_999)).toEqual({
      from: 60_000,
      to: 604_860_000,
    })
  })

  it('rejects incomplete, reversed, invalid and overlong custom ranges', () => {
    expect(validateHistoryRange(null, 100))
      .toBe('请选择完整的开始和结束时间')
    expect(validateHistoryRange(200, 100))
      .toBe('开始时间必须早于结束时间')
    expect(validateHistoryRange(Number.NaN, 100))
      .toBe('时间范围无效')
    expect(validateHistoryRange(0, 31 * 86_400_000 + 1))
      .toBe('查询跨度不能超过31天')
    expect(validateHistoryRange(0, 31 * 86_400_000)).toBeNull()
  })

  it('exposes Q0 Q1 Q2 and unknown quality labels', () => {
    expect(historyQualityLabel(0)).toBe('Q0 真实数据')
    expect(historyQualityLabel(1)).toBe('Q1 线性插值')
    expect(historyQualityLabel(2)).toBe('Q2 典型值')
    expect(historyQualityLabel(7)).toBe('Q7 未知质量')
    expect(historyQualityLabel(null)).toBe('无数据')
  })
})
