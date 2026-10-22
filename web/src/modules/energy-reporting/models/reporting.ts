export type ReportingSourceChannel = 'BILL' | 'MANUAL_STATISTICS'
export type ReportingPathway = 'STATIONARY_COMBUSTION' | 'PURCHASED_ELECTRICITY' | 'PURCHASED_HEAT' | 'WATER' | 'GREEN_ENERGY'
export type ReportingPurpose = 'PRIMARY' | 'CHECK_ONLY'
export type ReportingCoverage = 'WHOLE_BUILDING'
export type ReportingMeterOverlap = 'NO_METER' | 'REPLACES_METER_TOTAL' | 'PENDING_CONFIRMATION'
export type ReportingValueStatus = 'CONFIRMED' | 'PENDING_CONFIRMATION' | 'NOT_APPLICABLE' | 'MISSING'
export type ReportingDataNature = 'NATIVE_QUANTITY' | 'AMOUNT_ESTIMATE'
export type ReportingSourceTransition = 'submit' | 'approve' | 'reject'
export type ReportingBatchTransition = 'submit' | 'approve' | 'reject' | 'seal'

export interface ReportingSourceConfiguration {
  name: string
  energyItemCode: string
  unitCode: string
  channel: ReportingSourceChannel
  pathway: ReportingPathway
  purpose: ReportingPurpose
  coverage: ReportingCoverage
  meterOverlap: ReportingMeterOverlap
  timezoneId: string
  effectiveFrom: string
  effectiveTo: string | null
  evidence: string
  reason: string
  conversionMethod: string | null
  conversionPerspective: string | null
  regionCode: string | null
  supersedesSourceId: string | null
}

export interface ReportingSource {
  sourceId: string
  revision: number
  status: string
  createdBy: string | number | null
  reviewedBy: string | number | null
  configuration: ReportingSourceConfiguration
}

export interface ReportingRow {
  sourceId: string
  sourceRevision: number
  quantity: string | null
  valueStatus: ReportingValueStatus
  dataNature: ReportingDataNature
  startDate: string
  endDate: string
  evidence: string
  note: string | null
  exclusionQuantity: string | null
  exclusionReason: string | null
  exclusionEvidence: string | null
}

export interface ReportingBatch {
  batchId: string
  buildingId: string
  month: string
  status: 'DRAFT' | 'SUBMITTED' | 'REVIEWED' | 'SEALED' | 'SUPERSEDED' | string
  revision: number
  createdBy: string | number | null
  reviewedBy: string | number | null
  supersedesBatchId: string | null
  rows: ReportingRow[]
  snapshot: ReportingSnapshot | null
}

export interface ReportingEnergyOption {
  code: string
  name: string
  usageScopes: string[]
}

export interface ReportingUnitOption {
  code: string
  symbol: string
  dimension: string
}

export interface ReportingConversionOption {
  energyItemCode: string
  method: string
  perspective: string
  regionCode: string
  label: string
}

export interface ReportingCapabilities {
  maintainSources: boolean
  reviewSources: boolean
  reviewBatches: boolean
  sealBatches: boolean
}

export interface ReportingContext {
  buildingId: string
  month: string
  sources: ReportingSource[]
  batches: ReportingBatch[]
  energyOptions: ReportingEnergyOption[]
  unitOptions: ReportingUnitOption[]
  conversionOptions: ReportingConversionOption[]
  capabilities: ReportingCapabilities
}

export interface CreateReportingSourceRequest {
  configuration: ReportingSourceConfiguration
  idempotencyKey: string
}

export interface UpdateReportingSourceRequest {
  expectedRevision: number
  configuration: ReportingSourceConfiguration
}

export interface ReportingSourceTransitionRequest {
  expectedRevision: number
  reason: string
}

export interface CreateReportingBatchRequest {
  month: string
  reason: string
  idempotencyKey: string
  supersedesBatchId?: string | null
}

export interface UpdateReportingBatchRequest {
  expectedRevision: number
  rows: ReportingRow[]
  reason: string
}

export interface ReportingBatchTransitionRequest {
  expectedRevision: number
  reason: string
  idempotencyKey: string
}

export interface ReportingPreviewIssue {
  code: string
  reference: string
  message: string
}

export interface ReportingPreview {
  ready: boolean
  issues: ReportingPreviewIssue[]
  nativeStatus: string
  tceStatus: string
}

export interface ReportingUnitEvidence {
  versionId?: string | null
  [key: string]: unknown
}

export interface ReportingConversionEvidence {
  parameterVersionId?: string | null
  formulaVersionId?: string | null
  standardCoalVersionId?: string | null
  [key: string]: unknown
}

export interface ReportingPreparedRow {
  input: ReportingRow
  source: ReportingSource
  nativeQuantity: string | null
  nativeUnit: string | null
  energyItemVersionId: string | null
  unitVersionId: string | null
  unitEvidence: ReportingUnitEvidence | null
  tce: string | null
  tceStatus: string
  conversionEvidence: ReportingConversionEvidence | null
}

export interface ReportingSnapshot {
  contractVersion: string
  nativeStatus: string
  tceStatus: string
  rows: ReportingPreparedRow[]
  evidenceHash: string | null
}

export interface ReportingWorkbookCell {
  sourceId: string
  sheetName: string
  cell: string
  originalValue: string
  originalUnit: string
  note: string
}

export interface ReportingWorkbookPreview {
  rows: ReportingRow[]
  issues: ReportingPreviewIssue[]
  cells: ReportingWorkbookCell[]
}

export interface ReportingWorkbookMapping {
  sourceId: string
  sheetName: string
  quantityCell: string
  unitCode: string
  startDate: string
  endDate: string
  dataNature: ReportingDataNature
  evidence: string
}

export interface ReportingWorkbookMappingRequest {
  format: 'CELL_MAPPING'
  mappings: ReportingWorkbookMapping[]
}
