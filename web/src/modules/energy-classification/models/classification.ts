export type ClassificationDimension = 'USAGE' | 'DEPARTMENT' | 'CIRCUIT' | 'REGION' | 'SPACE'
export type ClassificationMode = 'ADDITIVE' | 'OVERLAPPING'
export type ClassificationObjectType = 'POINT' | 'EQUIPMENT' | 'SPACE'
export type BindingSemantics = 'DESCRIPTION' | 'METERING'
export type ReviewStatus = 'CONFIRMED' | 'PENDING_EXPERT'
export type BoundaryMode = 'MAIN_METER_TOTAL' | 'SUBMETER_SUM' | 'INDEPENDENT_METER_SUM' | 'MAIN_WITH_SUBMETER_BREAKDOWN'
export type ReadingSemantics = 'PERIOD_TOTAL' | 'CUMULATIVE'
export type ReadingTimePrecision = 'EXACT' | 'DATE_ONLY'

export interface ClassificationScheme {
  code: string
  name: string
  dimension: ClassificationDimension
  mode: ClassificationMode
}

export interface ClassificationNode {
  schemeCode: string
  code: string
  name: string
  parentCode: string | null
}

export interface ClassificationBinding {
  schemeCode: string
  nodeCode: string
  objectType: ClassificationObjectType
  objectId: string
  meterPointId: string | null
  semantics: BindingSemantics
  status: ReviewStatus
  evidence: string | null
}

export interface BoundaryRule {
  boundaryId: string
  energyItemCode: string
  unit: string
  mode: BoundaryMode
  status: ReviewStatus
  evidence: string | null
  independentChannelsConfirmed: boolean
}

export interface ClassificationConfiguration {
  relationVersionId: string | null
  schemes: ClassificationScheme[]
  nodes: ClassificationNode[]
  bindings: ClassificationBinding[]
  boundaryRules: BoundaryRule[]
}

export interface ClassificationIssue {
  severity: string
  code: string
  reference: string
  message: string
}

export interface ClassificationVersion {
  versionId: string
  buildingId: string
  versionNo: number
  revision: number
  status: string
  effectiveFrom: string | null
  effectiveTo: string | null
  createdBy: number
  reviewedBy: number | null
  configuration: ClassificationConfiguration
  issues: ClassificationIssue[]
}

export interface CreateVersionRequest {
  reason: string
  copiedFromVersionId?: string | null
}

export interface SaveVersionRequest {
  expectedRevision: number
  configuration: ClassificationConfiguration
  reason: string
}

export interface TransitionRequest {
  expectedRevision: number
  reason: string
  effectiveFrom: string | null
}

export interface ClassificationFilter {
  schemeCode: string
  nodeCode: string
}

export interface ClassificationQueryRequest {
  startInclusive: string
  endExclusive: string
  periodType: string
  energyItemCode: string
  unit: string
  schemeCode: string
  versionId: string
  filters: ClassificationFilter[]
}

export interface ClassificationNodeResult {
  nodeCode: string
  nodeName: string
  quantity: string | number | null
  status: string
  pointIds: string[]
  evidenceIds: string[]
}

export interface ClassificationBalance {
  boundaryId: string
  pointId: string
  quantity: string | number | null
  childQuantity: string | number | null
  residual: string | number | null
  status: string
}

export interface ClassificationQueryView {
  buildingId: string
  versionId: string
  relationVersionId: string | null
  unit: string
  startInclusive: string
  endExclusive: string
  total: string | number | null
  assigned: string | number | null
  unallocated: string | number | null
  residual: string | number | null
  status: string
  resultNature: string
  totalKind: 'MEASURED_SUBTOTAL' | 'BOUNDARY_TOTAL' | 'UNDETERMINED'
  additive: boolean
  nodes: ClassificationNodeResult[]
  balances: ClassificationBalance[]
  issues: ClassificationIssue[]
  evidenceIds: string[]
}

export interface ManualReading {
  pointId: string
  startInclusive: string
  endExclusive: string
  energyItemCode: string
  unit: string
  semantics: ReadingSemantics
  startReading: string | number | null
  endReading: string | number | null
  quantity: string | number | null
  multiplier: string | number | null
  alreadyScaled: boolean
  status: ReviewStatus
  sourceReference: string | null
  timePrecision: ReadingTimePrecision
  supersedesFactId: string | null
}

export interface ReadingImportRequest {
  idempotencyKey: string
  readings: ManualReading[]
  reason: string
}

export interface ReadingImportView {
  batchId: string
  status: string
  accepted: number
  issues: ClassificationIssue[]
}

export interface ReadingBatchView {
  batchId: string
  buildingId: string
  status: string
  revision: number
  createdBy: number
  count: number
}

export interface ReadingView {
  factId: string
  batchId: string
  reading: ManualReading
  normalizedQuantity: string | number | null
  createdBy: number
  batchStatus: string
  unchangedReading?: UnchangedReadingDiagnostic | null
}

export interface UnchangedReadingDiagnostic {
  pointId: string
  energyItemCode: string
  unit: string
  startInclusive: string
  endExclusive: string
  intervalCount: number
  counterValue: string
  sources: { factId: string; batchId: string; sourceReference: string }[]
}

export interface RelationModelReference {
  modelId?: string
  buildingId: string
  scopeType?: string
  governanceMode?: string
  activeVersionId: string | null
  draftVersionId: string | null
  modelRevision: number
}

export interface RelationVersionReference {
  versionId: string
  modelId?: string
  buildingId: string
  versionNo: number
  baseVersionId?: string | null
  copiedFromVersionId?: string | null
  status: string
  revision: number
  submittedRevision?: number | null
  snapshotSha256?: string | null
  changeReason?: string | null
  createdBy?: number | null
  submittedBy?: number | null
  approvedBy?: number | null
  activatedBy?: number | null
  createdAt?: string | null
  effectiveAt: string | null
}

export interface RelationReviewReference {
  requestId: string
  versionId: string
  buildingId: string
  requestNo: number
  status: string
  submittedRevision: number
  snapshotSha256: string
  submittedBy: number
  reviewerId: number | null
  reviewReason: string | null
  submittedAt: string | null
  reviewedAt: string | null
}

export interface RelationQueryMetadata {
  buildingId: string
  versionId: string
  versionNo: number
  effectiveAt: string | null
  modelRevision: number
  maxPageSize?: number
}

export interface RelationMeteringBoundary {
  boundaryId: string
  boundaryCode: string
  boundaryName: string
  energyType: string | null
  confirmationStatus: string
  evidenceReference?: string | null
  status: string
}

export interface RelationMeteringAssignment {
  assignmentItemId: string
  allocationStatus: string
  reasonCode: string | null
  reasonText: string | null
  evidenceReference: string | null
  meteringBoundaryId: string | null
  meteringBoundaryCode: string | null
  meteringBoundaryName: string | null
  energyType: string | null
  boundaryConfirmationStatus: string | null
  boundaryStatus: string | null
  meterPointNodeId: string | null
  pointId: string | null
  pointCode: string | null
  pointName: string | null
  meterRole: string | null
  meterDirection: string | null
  meterConfirmationStatus: string | null
  targetNodeId: string | null
  targetNodeType: string | null
  targetObjectId: string | null
  targetObjectCode: string | null
  targetObjectName: string | null
}

export interface RelationMeterStructure {
  structureItemId: string
  meteringBoundaryId: string | null
  meterPointNodeId: string
  meterPointCode: string | null
  meterRole: string
  parentMeterPointNodeId: string | null
  parentMeterPointCode: string | null
  meterDirection: string
  confirmationStatus: string
  reasonCode: string | null
  reasonText: string | null
  evidenceReference: string | null
  description: string | null
  sourceType: string | null
}

export interface RelationPage<T> {
  metadata: RelationQueryMetadata
  page: number
  size: number
  total: number
  items: T[]
}

export interface RelationBoundaryCatalogue extends RelationPage<RelationMeteringBoundary> {
  editableCatalogue: boolean
}

export interface RelationNodeCatalogueItem {
  nodeId: string
  nodeType: string
  businessObjectId: string
  code: string | null
  name: string | null
  status: string
}

export interface RelationNodeCatalogue extends RelationPage<RelationNodeCatalogueItem> {
  labelNature: 'CURRENT_ASSET_LABELS'
}

export interface RelationVersionDetail {
  metadata: RelationQueryMetadata
  version: RelationVersionReference
  counts: Record<string, number>
  meterStructures: RelationMeterStructure[]
}

export interface RelationValidation {
  buildingId: string
  versionId: string
  revision: number
  errorCount: number
  pendingExpertCount: number
  warningCount: number
  issues: RelationValidationIssue[]
}

export interface RelationValidationIssue {
  issueId: string
  level: string
  code: string
  objectType: string
  objectId: string
  message: string
}

export interface RelationReasonRequest {
  reason: string
}

export interface RelationCreateVersionRequest extends RelationReasonRequest {
  copiedFromVersionId: string | null
  expectedModelRevision: number
}

export interface RelationRevisionReasonRequest extends RelationReasonRequest {
  expectedRevision: number
}

export interface RelationBoundaryRequest {
  boundaryCode: string
  boundaryName: string
  energyType: string | null
  confirmationStatus: ReviewStatus
  evidenceReference: string | null
  expectedRevision: number
}

export interface RelationStructureRequest {
  meteringBoundaryId: string | null
  meterPointNodeId: string
  meterRole: 'MAIN' | 'SUB' | 'INDEPENDENT' | 'UNKNOWN'
  parentMeterPointNodeId: string | null
  meterDirection: 'INBOUND' | 'OUTBOUND' | 'BIDIRECTIONAL' | 'UNKNOWN'
  confirmationStatus: ReviewStatus
  reasonCode: string | null
  reasonText: string | null
  evidenceReference: string | null
  description: string | null
  expectedRevision: number
}

export interface RelationAssignmentRequest {
  meteringBoundaryId: string | null
  meterPointNodeId: string | null
  targetNodeId: string | null
  allocationStatus: 'ASSIGNED' | 'UNASSIGNED' | 'PENDING_EXPERT' | 'INVALID'
  reasonCode: string | null
  reasonText: string | null
  evidenceReference: string | null
  expectedRevision: number
}

export type CsvRecord = Record<string, string>

export interface AccessibleBuilding {
  buildingId: string
  buildingName: string
}

export interface AccessibleBuildingPage {
  records: AccessibleBuilding[]
  total: number
}

export const readingCsvFields = [
  'pointId', 'startInclusive', 'endExclusive', 'energyItemCode', 'unit', 'semantics',
  'startReading', 'endReading', 'quantity', 'multiplier', 'alreadyScaled', 'status', 'sourceReference', 'timePrecision', 'supersedesFactId',
] as const
export type ReadingCsvField = typeof readingCsvFields[number]
export type ReadingCsvMapping = Record<ReadingCsvField, string>

export function emptyConfiguration(): ClassificationConfiguration {
  return { relationVersionId: null, schemes: [], nodes: [], bindings: [], boundaryRules: [] }
}

export function cloneConfiguration(value: ClassificationConfiguration | null | undefined): ClassificationConfiguration {
  const configuration = value ?? emptyConfiguration()
  return {
    relationVersionId: nullable(configuration.relationVersionId),
    schemes: (configuration.schemes ?? []).map(item => ({ ...item })),
    nodes: (configuration.nodes ?? []).map(item => ({ ...item, parentCode: nullable(item.parentCode) })),
    bindings: (configuration.bindings ?? []).map(item => ({ ...item, meterPointId: nullable(item.meterPointId), evidence: nullable(item.evidence) })),
    boundaryRules: (configuration.boundaryRules ?? []).map(item => ({ ...item, evidence: nullable(item.evidence), independentChannelsConfirmed: item.independentChannelsConfirmed === true })),
  }
}

export function normalizeConfiguration(value: ClassificationConfiguration): ClassificationConfiguration {
  return cloneConfiguration(value)
}

export function parseCsv(text: string): { headers: string[]; rows: CsvRecord[] } {
  const matrix: string[][] = []
  let row: string[] = []
  let cell = ''
  let quoted = false
  for (let index = 0; index < text.length; index++) {
    const character = text[index]!
    if (character === '"') {
      if (quoted && text[index + 1] === '"') {
        cell += '"'
        index++
      } else quoted = !quoted
      continue
    }
    if (!quoted && character === ',') {
      row.push(cell)
      cell = ''
      continue
    }
    if (!quoted && (character === '\n' || character === '\r')) {
      if (character === '\r' && text[index + 1] === '\n') index++
      row.push(cell)
      if (row.some(value => value.length > 0)) matrix.push(row)
      row = []
      cell = ''
      continue
    }
    cell += character
  }
  if (quoted) throw new Error('CSV_QUOTE_UNCLOSED')
  row.push(cell)
  if (row.some(value => value.length > 0)) matrix.push(row)
  const headers = matrix.shift()?.map(value => value.trim()) ?? []
  if (!headers.length) throw new Error('CSV_HEADERS_EMPTY')
  if (new Set(headers).size !== headers.length || headers.some(header => !header)) throw new Error('CSV_HEADERS_INVALID')
  return {
    headers,
    rows: matrix.map(values => Object.fromEntries(headers.map((header, index) => [header, values[index]?.trim() ?? '']))),
  }
}

export function mapCsvReadings(rows: CsvRecord[], mapping: ReadingCsvMapping): ManualReading[] {
  return rows.map(row => ({
    pointId: value(row, mapping.pointId),
    startInclusive: value(row, mapping.startInclusive),
    endExclusive: value(row, mapping.endExclusive),
    energyItemCode: value(row, mapping.energyItemCode),
    unit: value(row, mapping.unit),
    semantics: value(row, mapping.semantics) as ReadingSemantics,
    startReading: nullable(value(row, mapping.startReading)),
    endReading: nullable(value(row, mapping.endReading)),
    quantity: nullable(value(row, mapping.quantity)),
    multiplier: nullable(value(row, mapping.multiplier)),
    alreadyScaled: booleanValue(value(row, mapping.alreadyScaled)),
    status: value(row, mapping.status) as ReviewStatus,
    sourceReference: nullable(value(row, mapping.sourceReference)),
    timePrecision: value(row, mapping.timePrecision) as ReadingTimePrecision,
    supersedesFactId: nullable(value(row, mapping.supersedesFactId)),
  }))
}

export function validateManualReadings(readings: ManualReading[]): string | null {
  if (!readings.length) return 'EMPTY'
  if (readings.length > 500) return 'LIMIT'
  for (const reading of readings) {
    if (!reading.pointId || !reading.startInclusive || !reading.endExclusive || !reading.energyItemCode || !reading.unit) return 'REQUIRED'
    if (!['PERIOD_TOTAL', 'CUMULATIVE'].includes(reading.semantics)) return 'SEMANTICS'
    if (!['CONFIRMED', 'PENDING_EXPERT'].includes(reading.status)) return 'STATUS'
    if (!['EXACT', 'DATE_ONLY'].includes(reading.timePrecision)) return 'TIME_PRECISION'
    if (reading.status === 'CONFIRMED' && !reading.multiplier) return 'MULTIPLIER'
    if (reading.semantics === 'PERIOD_TOTAL' && reading.quantity == null) return 'PERIOD_TOTAL'
    if (reading.semantics === 'CUMULATIVE' && (reading.startReading == null || reading.endReading == null)) return 'CUMULATIVE'
  }
  return null
}

function nullable(value: string | null | undefined): string | null {
  const trimmed = value?.trim()
  return trimmed ? trimmed : null
}

function value(row: CsvRecord, header: string): string {
  return header ? row[header]?.trim() ?? '' : ''
}

function booleanValue(value: string): boolean {
  return value.trim().toLowerCase() === 'true' || value.trim() === '1'
}
