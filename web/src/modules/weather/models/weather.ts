export interface Building { buildingId: string; buildingName: string }
export interface Page<T> { items: T[]; total: number; page: number; size: number }
export interface Binding {
  id: string; buildingId: string; version: number; name: string; latitude: number; longitude: number
  cityCode: string | null; coverage: string; effectiveFrom: string; effectiveTo: string | null; enabled: boolean
}
export interface BindingRequest {
  name: string; latitude: number; longitude: number; cityCode: string | null; coverage: string
  effectiveFrom: string; expectedVersion: number; enabled: boolean
}
export interface Provenance {
  dataKind: string; sourcePublishedAt: string | null; gridLatitude: number | null; gridLongitude: number | null
  parserVersion: string; timezone: string; units: Record<string, string>
}
export interface Sample { time: string; values: Record<string, number | null>; text: Record<string, string | null> }
export interface WeatherSeries {
  location: Binding | null; source: string | null; product: string; status: string; updateStatus: string
  fetchedAt: string | null; datasetId: string | null; provenance: Provenance | null; samples: Sample[]
}
export interface Forecast {
  preferredSource: string; selectedSource: string | null; fallbackReason: string | null; status: string
  updateStatus: string; fetchedAt: string | null; datasetId: string | null; coverage: string | null
  provenance: Provenance | null; days: { date: string; sample: Sample | null }[]
}
export interface Overview { location: Binding | null; current: WeatherSeries; forecast: Forecast }
export interface DailyWeather {
  date: string; location: Binding | null; sourceDaily: Sample | null; meanTemperature: number | null
  temperatureHours: number; meanHumidity: number | null; humidityHours: number; status: string
  hourlyDatasetId: string | null; dailyDatasetId: string | null
  hourlyProvenance: Provenance | null; dailyProvenance: Provenance | null
}
export interface Target {
  systemId: string; equipmentId: string | null; pointId: string; pointCode: string; pointName: string
  targetObjectName: string; meteringBoundaryName: string; unit: string; relationVersion: string
}
export interface EnergyResult {
  day: string; energyKwh: number | null; status: string; approximate: boolean
  startTime: number | null; endTime: number | null; startReading: number | null; endReading: number | null
  relationVersion: string | null; qualityPolicyVersion: string | null; direction: string; unit: string
  startBoundaryOffsetMillis: number | null; endBoundaryOffsetMillis: number | null
  calculatedAt: number | null; inputSummaryHash: string | null; governanceEvidenceReferences: string[] | null
  calculationPolicyVersion: string | null; reasons: string[]
}
export interface ComparisonDay {
  date: string; energy: EnergyResult | null; energyStatus: string; energyKwh: number | null
  weatherValue: number | null; weatherUnit: string; validHours: number; weatherStatus: string; datasetId: string | null
}
export interface Comparison { buildingId: string; systemId: string; pointId: string; metric: string; days: ComparisonDay[] }
export interface Management { canManageLocation: boolean; canFetchWeather: boolean; canCalculateEnergy: boolean; latestBinding: Binding | null }
export interface Job {
  id: string; kind: string; buildingId: string; source: string | null; product: string | null
  start: string; end: string; state: string; attempts: number; reason: string | null; createdAt: string
}
export interface FetchRequest { buildingId: string; source: string; product: string; start: string; end: string }
export interface EnergyRequest { systemId: string; pointId: string; start: string; end: string }
