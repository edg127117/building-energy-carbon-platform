import type { MeterCoverageView } from './meter-coverage'

export type MeterElectricityDay = {
  date: string
  kwh: number | null
  status: 'AVAILABLE' | 'MISSING' | 'QUALITY_BLOCKED' | 'UNRESOLVED'
  reason: string
  startSampleTime: number | null
  endSampleTime: number | null
  changeKwh: number | null
  changePercent: number | null
}

export type MeterElectricityView = {
  equipmentId: string
  equipmentCode: string
  equipmentName: string
  buildingId: string
  pointCode: string
  unit: 'kWh'
  timeZone: string
  boundaryWindowMinutes: number
  currentCoverage: MeterCoverageView
  days: MeterElectricityDay[]
  periodSummary: { measuredKwh: number | null; availableDays: number; requestedDays: number }
}
