import type { Binding, ComparisonDay, DailyWeather, Forecast, Job, Provenance, WeatherSeries } from './weather'
import w from '../locales/zh-CN'
import { reasonText, sourceText, stateText, timeText, valueText } from './presentation'
export interface DetailItem { label: string; value: string | number | null | undefined }
const item = (label: string, value: DetailItem['value']): DetailItem => ({ label, value })
export function provenanceDetails(p: Provenance | null): DetailItem[] { return !p ? [] : [
  item(w.dataKind, w.kinds[p.dataKind] ?? w.missing), item(w.publishedAt, timeText(p.sourcePublishedAt)),
  item(w.grid, `${valueText(p.gridLatitude)}, ${valueText(p.gridLongitude)}`), item(w.parser, p.parserVersion),
] }
export function bindingDetails(b: Binding | null): DetailItem[] { return !b ? [] : [
  item(w.location, b.name), item(w.locationVersion, b.version), item(w.coordinates, `${b.latitude}, ${b.longitude}`),
  item(w.coverage, w.coverages[b.coverage] ?? w.missing), item(w.effectiveDate, timeText(b.effectiveFrom)),
] }
export function seriesDetails(s: WeatherSeries | Forecast): DetailItem[] { return [
  item(w.source, sourceText('source' in s ? s.source : s.selectedSource)), item(w.status, stateText(s.status)),
  item(w.update, stateText(s.updateStatus)), item(w.fetchedAt, timeText(s.fetchedAt)), item(w.dataset, s.datasetId),
  ...provenanceDetails(s.provenance), ...('location' in s ? bindingDetails(s.location) : [item(w.reasonCode, s.fallbackReason)]),
] }
export function dailyDetails(d: DailyWeather): DetailItem[] { return [
  item(w.date, d.date), item(w.status, stateText(d.status)), item(w.temperatureHours, `${d.temperatureHours}/24`), item(w.humidityHours, `${d.humidityHours}/24`),
  item(w.hourlyDataset, d.hourlyDatasetId), item(w.dailyDataset, d.dailyDatasetId), ...bindingDetails(d.location),
  ...provenanceDetails(d.hourlyProvenance),
] }
export function comparisonDetails(d: ComparisonDay): DetailItem[] {
  const result = [item(w.date, d.date), item(w.energyStatus, stateText(d.energyStatus)), item(w.weatherStatus, stateText(d.weatherStatus)), item(w.validHours, `${d.validHours}/24`), item(w.dataset, d.datasetId)]
  const e = d.energy
  return !e ? result : [...result, item(w.startTime, timeText(e.startTime)), item(w.endTime, timeText(e.endTime)), item(w.startReading, valueText(e.startReading, ' kWh')), item(w.endReading, valueText(e.endReading, ' kWh')),
    item(w.startOffset, e.startBoundaryOffsetMillis), item(w.endOffset, e.endBoundaryOffsetMillis), item(w.relationVersion, e.relationVersion), item(w.qualityVersion, e.qualityPolicyVersion),
    item(w.calculatedAt, timeText(e.calculatedAt)), item(w.calculationVersion, e.calculationPolicyVersion), item(w.reasons, e.reasons.map(reasonText).join(' / ')), item(w.reasonCode, e.reasons.join(' / '))]
}
export function jobDetails(j: Job): DetailItem[] { return [item(w.taskId, j.id), item(w.status, stateText(j.state)), item(w.source, sourceText(j.source)), item(w.attempts, j.attempts), item(w.reasons, reasonText(j.reason)), item(w.reasonCode, j.reason)] }
