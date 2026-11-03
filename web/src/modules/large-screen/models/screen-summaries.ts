export interface ScreenIntensityData {
  areaValue: number | null
  personValue: number | null
  areaPercentage: number | null
  personPercentage: number | null
  areaUnit: string
  personUnit: string
}

export interface ScreenDeviceSummaryEntry {
  key: string
  label: string
  count: number | null
}

export interface ScreenDeviceSummaryData {
  total: number | null
  on: number | null
  off: number | null
  unknown: number | null
  modes: readonly ScreenDeviceSummaryEntry[]
  speeds: readonly ScreenDeviceSummaryEntry[]
}

export interface ScreenAlarmSummaryData {
  deviceAnomalies: number | null
  emissionWarnings: number | null
}

export interface ScreenRuntimeRankingEntry {
  rank: number
  room: string
  floor: string
  hours: number | null
  percentage: number | null
  coverageStatus?: 'PARTIAL' | 'COMPLETE'
  coveredMillis?: number
  onMillis?: number
  coveredHours?: number
  coveragePercentage?: number
}

export interface ScreenIntensityBenchmarks {
  percentageBasis?: string
  unit?: string
  sourceName?: string
  documentVersion?: string
  sourceReference?: string
  climateLabel?: string
  timeStatus?: string
  reasons?: string[]
  mode?: string
  throughMonth?: number
  versionId?: string
  comparisonStatus?: string
  constraint: number | null
  baseline: number | null
  guide: number | null
}
