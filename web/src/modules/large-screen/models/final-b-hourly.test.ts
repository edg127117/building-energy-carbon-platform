import { describe, expect, it } from 'vitest'
import type { HourlyMonitoringView } from '../api/hourly-monitoring'
import type { WeatherSeries } from '@/modules/weather/public'
import { hourlyScreenData, validateHourlyMonitoring, weatherTemperatures } from './final-b-hourly'

const building = 'B-1'
const date = '2026-09-22'
function fixture(): HourlyMonitoringView {
  const midnight = Date.parse(`${date}T00:00:00+08:00`)
  return {
    buildingId: building, date, timezoneId: 'Asia/Shanghai', asOf: new Date(midnight + 12 * 3_600_000).toISOString(),
    points: Array.from({ length: 24 }, (_, hour) => {
      const start = midnight + hour * 3_600_000
      return {
        hour, startInclusive: new Date(start).toISOString(), endExclusive: new Date(start + 3_600_000).toISOString(),
        actualStart: null, actualEnd: null,
        electricityKwh: hour === 0 ? '0' : null, staticEmissionTco2e: hour === 0 ? '0' : null,
        staticFactorKgCo2ePerKwh: null, dynamicEmissionTco2: hour === 0 ? '0.25' : null,
        dynamicFactorKgCo2PerKwh: null, electricityStatus: hour === 0 ? 'COMPLETE' : 'MISSING',
        staticStatus: hour === 0 ? 'COMPLETE' : 'MISSING', dynamicStatus: hour === 0 ? 'COMPLETE' : 'MISSING',
        approximate: false, staticGasCoverage: hour === 0 ? 'CO2_ONLY_ELECTRICITY' : null,
        sourceNatures: [], evidenceId: null, reasonCode: null,
      }
    }),
  }
}

describe('hourly monitoring projection', () => {
  it('checks building, date, timezone and exact Beijing hour boundaries', () => {
    const view = fixture()
    expect(() => validateHourlyMonitoring(view, building, date)).not.toThrow()
    expect(() => validateHourlyMonitoring(view, 'OTHER', date)).toThrow()
    view.points[2].startInclusive = view.points[2].endExclusive
    expect(() => validateHourlyMonitoring(view, building, date)).toThrow()
  })

  it('keeps zero, breaks unavailable hours and labels CO2 and CO2e series separately', () => {
    const view = fixture()
    view.points[1].electricityStatus = 'STALE'
    view.points[1].electricityKwh = null
    const result = hourlyScreenData(view)
    expect(result.usage.electricity.slice(0, 2)).toEqual([0, null])
    expect(result.mirror.dynamicEmissions[0]).toBe(0.25)
    expect(result.mirror.staticEmissions[0]).toBe(0)
    expect(result.mirror.dynamicEmissionUnit).toBe('tCO₂')
    expect(result.mirror.staticEmissionUnit).toBe('tCO₂')
    expect(result.mirror.dynamicFactorUnit).toBe('kgCO₂/kWh')
    expect(result.mirror.hourDetails?.[0].gasCoverage).toBe('CO2_ONLY_ELECTRICITY')
  })

  it('uses CO2e for mixed and other gas coverage while preserving the raw point label', () => {
    const view = fixture()
    view.points[1].staticStatus = 'COMPLETE'
    view.points[1].staticEmissionTco2e = '0.1'
    view.points[1].staticGasCoverage = 'MIXED'
    const result = hourlyScreenData(view)
    expect(result.mirror.staticEmissionUnit).toBe('tCO₂e')
    expect(result.mirror.hourDetails?.[1].gasCoverage).toBe('MIXED')
  })

  it('rejects numeric values on a stale hour', () => {
    const view = fixture()
    view.points[1].electricityStatus = 'STALE'
    view.points[1].electricityKwh = '3'
    expect(() => validateHourlyMonitoring(view, building, date)).toThrow('HOURLY_MONITORING_STATUS_VALUE_MISMATCH')
  })

  it('keeps historical and forecast temperature in separate aligned series and rejects duplicate samples', () => {
    const time = new Date(Date.parse(`${date}T03:00:00+08:00`)).toISOString()
    const create = (product: string, sampleValues: Array<number | null>): WeatherSeries => ({
      location: { id: 'L1', buildingId: building, version: 1, name: 'Location', latitude: 1, longitude: 1, cityCode: null, coverage: 'HOURLY', effectiveFrom: '2026-01-01T00:00:00+08:00', effectiveTo: null, enabled: true },
      source: 'OPEN_METEO', product, status: 'READY', updateStatus: 'READY', fetchedAt: null, datasetId: product,
      provenance: { dataKind: product, sourcePublishedAt: null, gridLatitude: 1, gridLongitude: 1, parserVersion: 'v1', timezone: 'Asia/Shanghai', units: { temperature_2m: '°C' } },
      samples: sampleValues.map(value => ({ time, values: { temperature_2m: value }, text: {} })),
    })
    const result = weatherTemperatures([create('HISTORY_HOURLY', [12, 13]), create('FORECAST_HOURLY', [15])], building, date)
    expect(result.history[3]).toBeNull()
    expect(result.forecast[3]).toBe(15)
    expect(result.history[2]).toBeNull()
  })
})
