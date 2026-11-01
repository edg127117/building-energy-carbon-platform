import { requestApi } from '@/infrastructure/http/public'

export interface IntensityRule {
  regionPrefix: string; buildingCategory: string; subcategory: string | null; climateLabel: string
  constraintValue: string; baselineValue: string; guideValue: string
  gasBasis: 'CO2_ONLY' | 'GHG_CO2E'; boundaryCode: 'WHOLE_BUILDING_ENERGY' | 'STANDARD_SPECIFIC' | 'UNKNOWN'
  areaBasis: 'BUILDING_AREA' | 'UNKNOWN'; boundaryNote: string; areaNote: string
  timeStatus: 'KNOWN' | 'NOT_STATED' | 'PENDING'; applicableFrom: string | null; applicableTo: string | null
}
export interface IntensityCatalogSave {
  catalogId: string | null; expectedVersionId: string | null; name: string; documentVersion: string
  sourceReference: string; publishedOn: string | null; entries: IntensityRule[]
}
export interface IntensityCatalogVersion extends Omit<IntensityCatalogSave, 'expectedVersionId'> {
  catalogId: string; versionId: string; revision: number; createdBy: number; createdAt: string | null; evidenceHash: string
}
export function listIntensityCatalog(signal?: AbortSignal) {
  return requestApi<IntensityCatalogVersion[]>({ method: 'get', url: '/v1/carbon-intensity/catalog', signal })
}
export function saveIntensityCatalog(data: IntensityCatalogSave) {
  return requestApi<IntensityCatalogVersion>({ method: 'post', url: '/v1/carbon-intensity/catalog', data })
}

/** 表格粘贴只转换录入格式，不推算单位、年份、气体或任何业务数值。 */
export function parseIntensityRows(text: string, defaults: IntensityRule): IntensityRule[] {
  const lines = text.trim().split(/\r?\n/).filter(line => line.trim())
  if (!lines.length || lines.length > 200) throw new Error('INTENSITY_IMPORT_INVALID')
  return lines.map(line => {
    const cells = line.split('\t').map(cell => cell.trim())
    if (cells.length !== 6 || !/^\d{2}(?:\d{2}){0,2}$/.test(cells[0]) || !cells[1]
      || cells.slice(3).some(cell => !/^\d+(?:\.\d{1,6})?$/.test(cell))) throw new Error('INTENSITY_IMPORT_INVALID')
    return { ...defaults, regionPrefix: cells[0], buildingCategory: cells[1], climateLabel: cells[2],
      constraintValue: cells[3], baselineValue: cells[4], guideValue: cells[5] }
  })
}
