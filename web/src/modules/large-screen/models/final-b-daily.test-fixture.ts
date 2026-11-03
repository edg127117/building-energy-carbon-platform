import type { DailyTrends } from '@/modules/energy-reporting/public'

export function dailyFixture(buildingId = 'B', month = '2024-02'): DailyTrends {
  const [year, m] = month.split('-').map(Number)
  return { buildingId, month, timezoneId: 'Asia/Shanghai', state: 'COMPLETE', electricityUnit: 'kWh', emissionUnit: 'tCO2e', factorUnit: 'kgCO2e/kWh',
    resultNature: 'MONITORING', contractVersion: 'DAILY_ELECTRICITY_STATIC_CARBON_V1', capturedAt: '', evidenceHash: 'hash',
    days: Array.from({ length: new Date(Date.UTC(year, m, 0)).getUTCDate() }, (_, i) => ({
      date: `${month}-${String(i + 1).padStart(2, '0')}`, startInclusive: new Date(Date.UTC(year, m - 1, i + 1) - 8 * 3600000).toISOString(), endExclusive: new Date(Date.UTC(year, m - 1, i + 2) - 8 * 3600000).toISOString(),
      electricityKwh: i === 0 ? '0' : '123.456', electricityStatus: 'AVAILABLE', electricityReasonCode: null,
      classificationVersionId: 'cv', relationVersionId: 'rv', electricityEvidenceIds: ['reading'], electricitySourceNature: 'SOURCE_LABELLED',
      staticEmissionTco2e: '0.00123456789', staticCarbonStatus: 'AVAILABLE', staticFactorKgCo2ePerKwh: '0.56', factorStatus: 'AVAILABLE',
      hourlyBatchId: 'batch', hourlyRevision: 2, hourlySourceNature: 'SIMULATED', factorVersionIds: ['factor'], hourlyEvidenceHash: 'hash',
    })),
  }
}
