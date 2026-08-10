import { describe, expect, it } from 'vitest'
import {
  FROZEN_POINT_DEFINITIONS,
  buildIndicatorViews,
  buildPointViews,
  calculatePointCoverage,
  mergeRealtimeIndicator,
} from './hvacDashboard'
import type {
  HvacLatestResponse,
  HvacRealtimeIndicator,
  HvacSnapshotResponse,
  SnapshotPoint,
} from '@/types/hvac'

function point(
  pointCode: string,
  average: number | null = 10,
  status = 'NORMAL',
): SnapshotPoint {
  return {
    pointId: `ID_${pointCode}`,
    pointCode,
    pointName: pointCode,
    equipId: null,
    equipCode: null,
    unit: 'u',
    minute: average === null ? null : 1785420000000,
    average,
    minimum: average,
    maximum: average,
    sampleCount: average === null ? 0 : 7,
    dataQuality: average === null ? null : 0,
    status,
  }
}

function snapshot(points: SnapshotPoint[]): HvacSnapshotResponse {
  return {
    buildingId: 'BLD001',
    generatedAt: 1785420001000,
    points,
  }
}

describe('HVAC dashboard mapping', () => {
  it('maps all 19 backend standard codes to frozen display slots', () => {
    const response = snapshot(
      FROZEN_POINT_DEFINITIONS.map((definition, index) =>
        point(definition.internalCode, index + 1),
      ),
    )

    const views = buildPointViews(response)

    expect(Object.keys(views)).toHaveLength(19)
    expect(views.WCR1_Flow.internalCode).toBe('WCR1_GW')
    expect(views.WCR1_Flow.value).toBe(3)
    expect(views.TOWER1_TCWin.internalCode).toBe('WCR1_CT_TWin')
    expect(views.PUMP1_Power.internalCode).toBe('WCR1_Pc_PPE')
    expect(views.DBO_TDB.internalCode).toBe('DBO')
    expect(views.DBO_RH.internalCode).toBe('RHO')
  })

  it('renders configured NO_DATA without inventing a value', () => {
    const views = buildPointViews(
      snapshot([point('WCR1_TWin', null, 'NO_DATA')]),
    )

    expect(views.WCR1_TWin.value).toBeNull()
    expect(views.WCR1_TWin.displayValue).toBe('--')
    expect(views.WCR1_TWin.status).toBe('NO_DATA')
    expect(views.WCR1_TWin.statusLabel).toBe('暂无数据')
    expect(views.WCR1_TWin.lastDisplayValue).toBeNull()
    expect(calculatePointCoverage(views)).toBe(0)
  })

  it('hides stale realtime value but keeps the last value evidence', () => {
    const views = buildPointViews(
      snapshot([point('WCR1_TWin', 7.2, 'STALE')]),
    )

    expect(views.WCR1_TWin.value).toBeNull()
    expect(views.WCR1_TWin.displayValue).toBe('--')
    expect(views.WCR1_TWin.status).toBe('STALE')
    expect(views.WCR1_TWin.statusLabel).toBe('数据过期')
    expect(views.WCR1_TWin.lastDisplayValue).toBe('7.2')
    expect(views.WCR1_TWin.minute).toBe(1785420000000)
    expect(calculatePointCoverage(views)).toBe(0)
  })

  it('keeps four indicator slots and exposes backend failure details', () => {
    const response: HvacLatestResponse = {
      buildingId: 'BLD001',
      generatedAt: 1785420001000,
      indicators: [
        {
          indicatorId: 'INDICATOR_WCR_COP_B1',
          indicatorCode: 'WCR_COP',
          equipId: 'EQUIP_WCR_B1',
          minuteStart: 1785420000000,
          status: 'SUCCESS',
          value: 6.52,
          unit: '',
          dataQuality: 0,
          formulaVersion: '1.0',
          reasonCode: null,
          missingInputs: [],
        },
        {
          indicatorId: 'INDICATOR_PUMP_EFF_B1',
          indicatorCode: 'PUMP_EFF',
          equipId: 'EQUIP_PUMP_B1',
          minuteStart: 1785420000000,
          status: 'MISSING_INPUT',
          value: null,
          unit: '%',
          dataQuality: null,
          formulaVersion: '1.0',
          reasonCode: 'INPUT_MISSING',
          missingInputs: ['PUMP_POWER'],
        },
      ],
    }

    const views = buildIndicatorViews(response)

    expect(views).toHaveLength(4)
    expect(views[0].indicatorCode).toBe('WCR_COP')
    expect(views[0].displayValue).toBe('6.52')
    expect(views[0].summaryText).toBe('最新分钟计算完成')
    expect(views[0].supportingText).toBeNull()
    expect(views[2].indicatorCode).toBe('PUMP_EFF')
    expect(views[2].displayValue).toBe('--')
    expect(views[2].summaryText).toBe('当前分钟缺少必要输入')
    expect(views[2].supportingText).toBe('缺少 1 项必要输入')
    expect(views[2].supportingText).not.toContain('PUMP_POWER')
    expect(views[2].reasonCode).toBe('INPUT_MISSING')
    expect(views[2].missingInputs).toEqual(['PUMP_POWER'])
  })

  it('ignores unknown response codes without changing the fixed slots', () => {
    const views = buildPointViews(
      snapshot([point('UNKNOWN_POINT', 999)]),
    )

    expect(Object.keys(views)).toHaveLength(19)
    expect(Object.values(views).every((view) => view.value === null)).toBe(true)
  })
})

function realtimeIndicator(
  overrides: Partial<HvacRealtimeIndicator> = {},
): HvacRealtimeIndicator {
  return {
    indicatorId: 'INDICATOR_WCR_COP_BLD001',
    indicatorCode: 'WCR_COP',
    buildingId: 'BLD001',
    equipId: 'WCR1',
    minuteStart: 1785420000000,
    status: 'SUCCESS',
    value: 6.52,
    dataQuality: 0,
    formulaVersion: 'WCR_V1',
    reasonCode: null,
    missingInputs: [],
    ...overrides,
  }
}

describe('mergeRealtimeIndicator', () => {
  const latest: HvacLatestResponse = {
    buildingId: 'BLD001',
    generatedAt: 1785420001000,
    indicators: [
      {
        indicatorId: 'INDICATOR_WCR_COP_BLD001',
        indicatorCode: 'WCR_COP',
        equipId: 'WCR1',
        minuteStart: 1785420000000,
        status: 'SUCCESS',
        value: 5.21,
        unit: '',
        dataQuality: 2,
        formulaVersion: 'WCR_V0',
        reasonCode: null,
        missingInputs: [],
      },
    ],
  }

  it('replaces only the matching current-building card while preserving its HTTP unit', () => {
    const merged = mergeRealtimeIndicator(
      latest,
      realtimeIndicator({ value: 6.52, dataQuality: 0 }),
      'BLD001',
    )

    expect(merged).not.toBe(latest)
    expect(merged?.indicators).toHaveLength(1)
    expect(merged?.indicators[0]).toMatchObject({
      value: 6.52,
      dataQuality: 0,
      unit: '',
      formulaVersion: 'WCR_V1',
    })
  })

  it('ignores another building, an unknown code, and an older source minute', () => {
    expect(
      mergeRealtimeIndicator(
        latest,
        realtimeIndicator({ buildingId: 'BLD002' }),
        'BLD001',
      ),
    ).toBe(latest)
    expect(
      mergeRealtimeIndicator(
        latest,
        realtimeIndicator({ indicatorCode: 'UNKNOWN' as HvacRealtimeIndicator['indicatorCode'] }),
        'BLD001',
      ),
    ).toBe(latest)
    expect(
      mergeRealtimeIndicator(
        latest,
        realtimeIndicator({ minuteStart: 1785419940000 }),
        'BLD001',
      ),
    ).toBe(latest)
  })

  it('accepts same-minute corrections and newer minutes with complete failure facts', () => {
    const sameMinute = mergeRealtimeIndicator(
      latest,
      realtimeIndicator({
        status: 'MISSING_INPUT',
        value: null,
        dataQuality: null,
        reasonCode: 'INPUT_MISSING',
        missingInputs: ['PUMP_POWER'],
      }),
      'BLD001',
    )
    const newer = mergeRealtimeIndicator(
      sameMinute,
      realtimeIndicator({
        minuteStart: 1785420060000,
        status: 'CALCULATION_ERROR',
        value: null,
        dataQuality: null,
        reasonCode: 'ENGINE_UNAVAILABLE',
        missingInputs: ['WCR1_PPE'],
      }),
      'BLD001',
    )

    expect(sameMinute?.indicators[0]).toMatchObject({
      status: 'MISSING_INPUT',
      reasonCode: 'INPUT_MISSING',
      missingInputs: ['PUMP_POWER'],
    })
    expect(newer?.indicators[0]).toMatchObject({
      minuteStart: 1785420060000,
      status: 'CALCULATION_ERROR',
      reasonCode: 'ENGINE_UNAVAILABLE',
      missingInputs: ['WCR1_PPE'],
    })
  })

  it('creates only the received valid indicator when HTTP state is not available yet', () => {
    const merged = mergeRealtimeIndicator(
      null,
      realtimeIndicator({
        status: 'MISSING_INPUT',
        value: null,
        dataQuality: null,
        reasonCode: 'INPUT_MISSING',
        missingInputs: ['WCR1_GW'],
      }),
      'BLD001',
    )

    expect(merged).toMatchObject({ buildingId: 'BLD001' })
    expect(merged?.indicators).toEqual([
      expect.objectContaining({
        indicatorCode: 'WCR_COP',
        reasonCode: 'INPUT_MISSING',
        missingInputs: ['WCR1_GW'],
      }),
    ])
  })
})
