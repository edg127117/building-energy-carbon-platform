<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElAlert, ElButton, ElCard, ElDatePicker, ElDialog, ElEmpty, ElForm, ElFormItem, ElInput, ElOption,
  ElPagination, ElSelect, ElSkeleton, ElSpace, ElTabPane, ElTable, ElTableColumn, ElTabs, ElTag, ElUpload,
} from '@/shared/ui'
import type { UploadFile } from '@/shared/ui'
import { t } from '@/locales'
import { useSession } from '@/modules/auth/public'
import { createIdempotencyKey } from '@/shared/utils/idempotency-key'
import { energyReportingApi } from '../api/energy-reporting'
import { useReportingBuildings } from '../composables/use-reporting-buildings'
import { useEnergyReporting } from '../composables/use-energy-reporting'
import type {
  ReportingBatch,
  ReportingBatchTransition,
  ReportingPreparedRow,
  ReportingPreview,
  ReportingRow,
  ReportingSource,
  ReportingSourceConfiguration,
  ReportingSourceTransition,
  ReportingWorkbookMapping,
  ReportingWorkbookMappingRequest,
  ReportingWorkbookPreview,
} from '../models/reporting'
import copy from '../locales/zh-CN'

const route = useRoute()
const router = useRouter()
const workspace = useEnergyReporting()
const buildings = useReportingBuildings()
const session = useSession()
const activeSection = ref<'source' | 'batch'>('batch')
const selectedBuildingId = ref('')
const selectedMonth = ref(currentMonth())
const localError = ref('')
const sourceActionReason = ref('')
const batchActionReason = ref('')
const sourceEditorOpen = ref(false)
const sourceEditing = ref<ReportingSource | null>(null)
const sourceDraft = ref<ReportingSourceConfiguration>(emptySourceConfiguration())
const sourceSnapshot = ref('')
const selectedBatchId = ref('')
const batchDraft = ref<ReportingBatch | null>(null)
const batchSnapshot = ref('')
const batchPreview = ref<ReportingPreview | null>(null)
const workbookFile = ref<File | null>(null)
const workbookPreview = ref<ReportingWorkbookPreview | null>(null)
const workbookMappingOpen = ref(false)
const workbookMappings = ref<ReportingWorkbookMapping[]>([])
// 与账单导入端点的单文件上限一致，避免用户在服务端拒绝前等待上传。
const maximumWorkbookFileBytes = 1024 * 1024

const sourceFormDirty = computed(() => sourceEditorOpen.value && sourceSignature(sourceDraft.value) !== sourceSnapshot.value)
const batchSignature = computed(() => batchDraft.value ? batchSignatureOf(batchDraft.value) : '')
const batchDirty = computed(() => !!batchDraft.value && batchSignature.value !== batchSnapshot.value)
const hasUnsavedChanges = computed(() => sourceFormDirty.value || batchDirty.value)
const sourceEditorTitle = computed(() => sourceEditing.value ? copy.source.editorTitleEdit : copy.source.editorTitleNew)
const currentContext = computed(() => workspace.context.value)
const currentBatchEditable = computed(() => !!batchDraft.value && batchDraft.value.status === 'DRAFT'
  && isCurrentActor(batchDraft.value.createdBy))
const hasOpenBatch = computed(() => (currentContext.value?.batches ?? []).some(batch =>
  ['DRAFT', 'SUBMITTED', 'REVIEWED'].includes(batch.status),
))
const revisionSourceBatch = computed(() => {
  if (hasOpenBatch.value) return null
  return (currentContext.value?.batches ?? []).find(batch => batch.status === 'SEALED') ?? null
})
const createBatchLabel = computed(() => revisionSourceBatch.value ? copy.actions.createRevisionBatch : copy.actions.createBatch)
const canCreateBatch = computed(() => !!currentContext.value && !hasOpenBatch.value)
const hasAmountEstimate = computed(() => batchDraft.value?.rows.some(row => row.dataNature === 'AMOUNT_ESTIMATE') ?? false)
const workbookCanAdopt = computed(() => !!workbookPreview.value?.rows.length
  && workbookPreview.value.issues.every(issue => issue.code === 'AMOUNT_ESTIMATE'))
const frozenSnapshot = computed(() => {
  const batch = batchDraft.value
  return batch && ['REVIEWED', 'SEALED', 'SUPERSEDED'].includes(batch.status) ? batch.snapshot : null
})
const sourceById = computed(() => new Map((currentContext.value?.sources ?? []).map(source => [source.sourceId, source])))
const replacementSourceOptions = computed(() => (currentContext.value?.sources ?? []).filter(source =>
  source.status === 'APPROVED'
  && source.sourceId !== sourceEditing.value?.sourceId
  && (!sourceDraft.value.energyItemCode || source.configuration.energyItemCode === sourceDraft.value.energyItemCode),
))
const sourceUnitOptions = computed(() => (currentContext.value?.unitOptions ?? []).filter(unit =>
  allowsUnitDimension(sourceDraft.value.pathway, unit.dimension),
))
const sourceConversionOptions = computed(() => (currentContext.value?.conversionOptions ?? []).filter(option =>
  option.energyItemCode === sourceDraft.value.energyItemCode,
))
// 已有来源若不在当前已审核生产参数范围内，保留原口径直到维护人员明确清空或重新选择。
const sourceConversionNeedsReview = computed(() => hasSourceConversion(sourceDraft.value)
  && !sourceConversionOptions.value.some(option => matchesSourceConversion(option, sourceDraft.value)))
const conversionReviewValue = '__REVIEW__'
const sourceConversionKey = computed({
  get: (): string => {
    const option = sourceConversionOptions.value.find(item => matchesSourceConversion(item, sourceDraft.value))
    return option ? conversionOptionKey(option) : sourceConversionNeedsReview.value ? conversionReviewValue : ''
  },
  set: (value: string) => {
    const option = sourceConversionOptions.value.find(item => conversionOptionKey(item) === value)
    sourceDraft.value.conversionMethod = option?.method ?? null
    sourceDraft.value.conversionPerspective = option?.perspective ?? null
    sourceDraft.value.regionCode = option?.regionCode ?? null
  },
})
const workbookMappingSources = computed(() => (currentContext.value?.sources ?? []).filter(source =>
  source.status === 'APPROVED' || batchDraft.value?.rows.some(row => row.sourceId === source.sourceId),
))
const workbookMappingUnitOptions = computed(() => currentContext.value?.unitOptions ?? [])

watch(batchSignature, (signature, previous) => {
  if (signature !== previous && signature !== batchSnapshot.value) {
    batchPreview.value = null
    workbookPreview.value = null
  }
})
watch(
  () => [route.query.buildingId, route.query.month, route.query.section],
  () => { void applyRouteContext() },
  { immediate: true },
)
onMounted(() => { void buildings.search() })

async function applyRouteContext(): Promise<void> {
  const nextBuildingId = routeValue(route.query.buildingId)
  const queriedMonth = routeValue(route.query.month)
  const nextMonth = isMonthStart(queriedMonth) ? queriedMonth : currentMonth()
  const section = routeValue(route.query.section)
  if (section === 'source' || section === 'batch') activeSection.value = section
  selectedBuildingId.value = nextBuildingId
  selectedMonth.value = nextMonth
  if (queriedMonth !== nextMonth) {
    await replaceRoute(nextBuildingId, nextMonth, activeSection.value)
    return
  }
  if (workspace.buildingId.value === nextBuildingId && workspace.month.value === nextMonth && workspace.context.value) return
  if (hasUnsavedChanges.value && (workspace.buildingId.value !== nextBuildingId || workspace.month.value !== nextMonth)) {
    localError.value = copy.context.changeBlocked
    selectedBuildingId.value = workspace.buildingId.value
    selectedMonth.value = workspace.month.value || nextMonth
    await replaceRoute(workspace.buildingId.value, selectedMonth.value, activeSection.value)
    return
  }
  localError.value = ''
  const context = await workspace.loadContext(nextBuildingId, nextMonth)
  if (context) setBatchFromContext(context, selectedBatchId.value)
  else if (!nextBuildingId) clearBatch()
}

async function replaceRoute(buildingId: string, month: string, section: 'source' | 'batch'): Promise<void> {
  const query = { ...route.query }
  delete query.buildingId
  delete query.month
  delete query.section
  await router.replace({
    path: route.path,
    query: { ...query, ...(buildingId ? { buildingId } : {}), month, section },
  })
}

function requestContextChange(): void {
  if (hasUnsavedChanges.value || workspace.commandLoading.value) {
    localError.value = copy.context.changeBlocked
    selectedBuildingId.value = workspace.buildingId.value
    selectedMonth.value = workspace.month.value || selectedMonth.value
    return
  }
  void replaceRoute(selectedBuildingId.value, selectedMonth.value, activeSection.value)
}

function changeSection(value: string | number): void {
  const next = value === 'source' ? 'source' : 'batch'
  activeSection.value = next
  void replaceRoute(selectedBuildingId.value, selectedMonth.value, next)
}

function openParameterPackages(): void {
  if (hasUnsavedChanges.value || workspace.commandLoading.value) {
    localError.value = copy.context.changeBlocked
    return
  }
  const query = { ...route.query }
  delete query.mode
  void router.push({ path: route.path, query: { ...query, mode: 'parameters', ...(selectedBuildingId.value ? { buildingId: selectedBuildingId.value } : {}) } })
}

function openCarbonResults(): void {
  if (hasUnsavedChanges.value || workspace.commandLoading.value) {
    localError.value = copy.context.changeBlocked
    return
  }
  if (!selectedBuildingId.value) {
    localError.value = copy.context.select
    return
  }
  void router.push({
    path: '/operations/energy/energyZones',
    query: { buildingId: selectedBuildingId.value, reportingMonth: selectedMonth.value, tab: 'carbonResults' },
  })
}

function searchBuildings(keyword: string): void {
  void buildings.search(1, keyword)
}

function refreshContext(): void {
  if (hasUnsavedChanges.value || workspace.commandLoading.value) {
    localError.value = copy.context.changeBlocked
    return
  }
  void loadAndSyncContext(selectedBatchId.value)
}

async function loadAndSyncContext(preferredBatchId = ''): Promise<void> {
  const context = await workspace.loadContext(selectedBuildingId.value, selectedMonth.value)
  if (context) setBatchFromContext(context, preferredBatchId)
}

function setBatchFromContext(context: NonNullable<typeof workspace.context.value>, preferredBatchId: string): void {
  const batch = context.batches.find(item => item.batchId === preferredBatchId) ?? context.batches[0] ?? null
  setBatch(batch)
}

function selectBatch(batchId: string): void {
  if (batchDirty.value) {
    localError.value = copy.context.changeBlocked
    selectedBatchId.value = batchDraft.value?.batchId ?? ''
    return
  }
  const batch = currentContext.value?.batches.find(item => item.batchId === batchId) ?? null
  setBatch(batch)
}

function setBatch(batch: ReportingBatch | null): void {
  batchPreview.value = null
  workbookFile.value = null
  workbookPreview.value = null
  workbookMappingOpen.value = false
  workbookMappings.value = []
  selectedBatchId.value = batch?.batchId ?? ''
  batchDraft.value = batch ? cloneBatch(batch) : null
  batchSnapshot.value = batchDraft.value ? batchSignatureOf(batchDraft.value) : ''
}

function clearBatch(): void {
  selectedBatchId.value = ''
  batchDraft.value = null
  batchSnapshot.value = ''
  batchPreview.value = null
  workbookFile.value = null
  workbookPreview.value = null
  workbookMappingOpen.value = false
  workbookMappings.value = []
}

function restoreBatch(): void {
  const batch = currentContext.value?.batches.find(item => item.batchId === selectedBatchId.value) ?? null
  setBatch(batch)
  localError.value = ''
}

function openSourceEditor(source?: ReportingSource): void {
  if (workspace.commandLoading.value) return
  sourceEditing.value = source ?? null
  sourceDraft.value = source ? cloneSourceConfiguration(source.configuration, true) : emptySourceConfiguration()
  sourceSnapshot.value = sourceSignature(sourceDraft.value)
  sourceEditorOpen.value = true
  localError.value = ''
}

function closeSourceEditor(): void {
  if (workspace.commandLoading.value) return
  sourceEditorOpen.value = false
  sourceEditing.value = null
  sourceDraft.value = emptySourceConfiguration()
  sourceSnapshot.value = ''
}

async function saveSource(): Promise<void> {
  const context = currentContext.value
  const source = sourceEditing.value
  if (!context || !context.capabilities.maintainSources || (source && !canEditSource(source))) return
  const configuration = normalizeSourceConfiguration(sourceDraft.value)
  if (!validSourceConfiguration(configuration)) { localError.value = copy.source.validation; return }
  const response = source
    ? await workspace.execute(() => energyReportingApi.updateSource(context.buildingId, source.sourceId, {
      expectedRevision: source.revision,
      configuration,
    }))
    : await workspace.execute(() => energyReportingApi.createSource(context.buildingId, {
      configuration,
      idempotencyKey: createIdempotencyKey(),
    }))
  if (!response) return
  closeSourceEditor()
  await loadAndSyncContext(selectedBatchId.value)
}

async function transitionSource(source: ReportingSource, action: ReportingSourceTransition): Promise<void> {
  const context = currentContext.value
  const reason = sourceActionReason.value.trim()
  if (!context) { localError.value = copy.error.unavailable; return }
  if ((action === 'submit' && !canSubmitSource(source))
    || ((action === 'approve' || action === 'reject') && !canReviewSource(source))) return
  if (!reason) { localError.value = copy.error.sourceActionReason; return }
  const response = await workspace.execute(() => energyReportingApi.transitionSource(context.buildingId, source.sourceId, action, {
    expectedRevision: source.revision,
    reason,
  }))
  if (!response) return
  sourceActionReason.value = ''
  await loadAndSyncContext(selectedBatchId.value)
}

async function createBatch(): Promise<void> {
  const context = currentContext.value
  const reason = batchActionReason.value.trim()
  if (!context) { localError.value = copy.error.unavailable; return }
  if (!reason) { localError.value = copy.error.batchActionReason; return }
  const response = await workspace.execute(() => energyReportingApi.createBatch(context.buildingId, {
    month: context.month,
    reason,
    idempotencyKey: createIdempotencyKey(),
    supersedesBatchId: revisionSourceBatch.value?.batchId ?? null,
  }))
  if (!response) return
  batchActionReason.value = ''
  await loadAndSyncContext(response.batchId)
}

async function saveBatch(): Promise<void> {
  const context = currentContext.value
  const batch = batchDraft.value
  const reason = batchActionReason.value.trim()
  if (!context || !batch || !currentBatchEditable.value) { localError.value = copy.error.unavailable; return }
  if (!reason) { localError.value = copy.error.batchActionReason; return }
  if (!validRows(batch.rows)) { localError.value = copy.batch.validation; return }
  const response = await workspace.execute(() => energyReportingApi.updateBatch(context.buildingId, batch.batchId, {
    expectedRevision: batch.revision,
    rows: batch.rows.map(normalizeRow),
    reason,
  }))
  if (!response) return
  batchActionReason.value = ''
  await loadAndSyncContext(response.batchId)
}

async function previewBatch(): Promise<void> {
  const context = currentContext.value
  const batch = batchDraft.value
  if (!context || !batch || !currentBatchEditable.value || batchDirty.value) return
  const signature = batchSignature.value
  const response = await workspace.execute(() => energyReportingApi.previewBatch(context.buildingId, batch.batchId))
  if (!response || selectedBatchId.value !== batch.batchId || signature !== batchSignature.value) return
  batchPreview.value = response
}

function selectWorkbook(file: UploadFile): void {
  const raw = file.raw
  if (!raw || raw.size > maximumWorkbookFileBytes || !raw.name.toLowerCase().endsWith('.xlsx')) {
    localError.value = copy.workbook.invalidFile
    return
  }
  workbookFile.value = raw
  workbookPreview.value = null
  workbookMappings.value = []
  localError.value = ''
}

function clearWorkbook(): void {
  workbookFile.value = null
  workbookPreview.value = null
  workbookMappingOpen.value = false
  workbookMappings.value = []
}

function openWorkbookMapping(): void {
  if (!workbookFile.value) { localError.value = copy.workbook.noFile; return }
  if (batchDirty.value) { localError.value = copy.workbook.saveFirst; return }
  if (!workbookMappings.value.length) addWorkbookMapping()
  workbookMappingOpen.value = true
  localError.value = ''
}

function addWorkbookMapping(): void {
  if (workbookMappings.value.length >= 100) return
  workbookMappings.value.push(emptyWorkbookMapping())
}

function removeWorkbookMapping(index: number): void {
  workbookMappings.value.splice(index, 1)
}

async function previewWorkbookMapping(): Promise<void> {
  const mappings = workbookMappings.value.map(normalizeWorkbookMapping)
  if (!validWorkbookMappings(mappings)) { localError.value = copy.workbook.mappingValidation; return }
  if (await previewWorkbook({ format: 'CELL_MAPPING', mappings })) workbookMappingOpen.value = false
}

async function downloadWorkbookTemplate(): Promise<void> {
  const context = currentContext.value
  const batch = batchDraft.value
  if (!context || !batch) { localError.value = copy.error.unavailable; return }
  const workbook = await workspace.execute(() => energyReportingApi.workbookTemplate(context.buildingId, batch.batchId))
  if (!workbook) return
  const href = URL.createObjectURL(workbook)
  const link = document.createElement('a')
  link.href = href
  link.download = `monthly-energy-bills-${context.month}.xlsx`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(href)
}

function previewStandardWorkbook(): void {
  void previewWorkbook()
}

async function previewWorkbook(mapping?: ReportingWorkbookMappingRequest): Promise<boolean> {
  const context = currentContext.value
  const batch = batchDraft.value
  const file = workbookFile.value
  if (!context || !batch || !currentBatchEditable.value) { localError.value = copy.error.unavailable; return false }
  if (!file) { localError.value = copy.workbook.noFile; return false }
  if (batchDirty.value) { localError.value = copy.workbook.saveFirst; return false }
  const signature = batchSignature.value
  const response = await workspace.execute(() => energyReportingApi.previewWorkbook(context.buildingId, batch.batchId, file, mapping))
  if (!response || selectedBatchId.value !== batch.batchId || signature !== batchSignature.value) return false
  workbookPreview.value = response
  return true
}

function adoptWorkbookRows(): void {
  const batch = batchDraft.value
  const preview = workbookPreview.value
  if (!batch || !preview) { localError.value = copy.error.unavailable; return }
  if (!workbookCanAdopt.value) { localError.value = copy.workbook.adoptBlocked; return }
  const imported = new Map(preview.rows.map(row => [row.sourceId, cloneRow(row)]))
  const existingSourceIds = new Set(batch.rows.map(row => row.sourceId))
  batchDraft.value = {
    ...batch,
    rows: [
      ...batch.rows.map(row => imported.get(row.sourceId) ?? cloneRow(row)),
      ...preview.rows.filter(row => !existingSourceIds.has(row.sourceId)).map(cloneRow),
    ],
  }
  workbookPreview.value = null
  batchPreview.value = null
  localError.value = ''
}

async function transitionBatch(action: ReportingBatchTransition): Promise<void> {
  const context = currentContext.value
  const batch = batchDraft.value
  const reason = batchActionReason.value.trim()
  if (!context || !batch) { localError.value = copy.error.unavailable; return }
  if ((action === 'refresh-sources' && (!currentBatchEditable.value || batchDirty.value))
    || (action === 'submit' && !currentBatchEditable.value)
    || (action === 'approve' && !canApproveBatch(batch))
    || (action === 'reject' && !canRejectBatch(batch))
    || (action === 'seal' && !canSealBatch(batch))) return
  if (!reason) { localError.value = copy.error.batchActionReason; return }
  if (action === 'submit' && (!batchPreview.value?.ready || batchDirty.value)) return
  const response = await workspace.execute(() => energyReportingApi.transitionBatch(context.buildingId, batch.batchId, action, {
    expectedRevision: batch.revision,
    reason,
    idempotencyKey: createIdempotencyKey(),
  }))
  if (!response) return
  batchActionReason.value = ''
  await loadAndSyncContext(response.batchId)
}

function openProfessional(): void {
  if (hasUnsavedChanges.value) { localError.value = copy.context.changeBlocked; return }
  void router.push({ path: route.path, query: { ...route.query, mode: 'professional' } })
}

// 页面提前收紧创建人与审核人分离；后端仍是最终权限边界。
function isCurrentActor(createdBy: string | number | null): boolean {
  const actorId = session.user?.id
  return actorId !== undefined && createdBy !== null && createdBy !== undefined && String(createdBy) === String(actorId)
}

function canEditSource(source: ReportingSource): boolean {
  return !!currentContext.value?.capabilities.maintainSources && source.status === 'DRAFT' && isCurrentActor(source.createdBy)
}

function canSubmitSource(source: ReportingSource): boolean {
  return !!currentContext.value?.capabilities.maintainSources && source.status === 'DRAFT' && isCurrentActor(source.createdBy)
}

function canReviewSource(source: ReportingSource): boolean {
  return !!currentContext.value?.capabilities.reviewSources && source.status === 'SUBMITTED' && !isCurrentActor(source.createdBy)
}

function canApproveBatch(batch: ReportingBatch | null): boolean {
  return !!batch && !!currentContext.value?.capabilities.reviewBatches && batch.status === 'SUBMITTED' && !isCurrentActor(batch.createdBy)
}

function canRejectBatch(batch: ReportingBatch | null): boolean {
  return !!batch && !!currentContext.value?.capabilities.reviewBatches
    && (batch.status === 'SUBMITTED' || batch.status === 'REVIEWED') && !isCurrentActor(batch.createdBy)
}

function canSealBatch(batch: ReportingBatch | null): boolean {
  return !!batch && !!currentContext.value?.capabilities.sealBatches && batch.status === 'REVIEWED' && !isCurrentActor(batch.createdBy)
}

function tagType(status: string): 'success' | 'warning' | 'info' | 'danger' {
  if (status === 'APPROVED' || status === 'REVIEWED' || status === 'SEALED') return 'success'
  if (status === 'SUBMITTED') return 'warning'
  if (status === 'SUPERSEDED') return 'info'
  return 'info'
}

function statusLabel(status: string): string {
  return ((copy.status as Record<string, string>)[status] ?? status) || t('common.missing')
}

function sourceName(sourceId: string): string {
  return sourceById.value.get(sourceId)?.configuration.name || sourceId
}

// 表格数据只绑定 currentContext.sources；Element Plus 槽位仍以通用行类型暴露。
function reportingSource(row: unknown): ReportingSource {
  return row as ReportingSource
}

function reportingPreparedRow(row: unknown): ReportingPreparedRow {
  return row as ReportingPreparedRow
}

function energyLabel(code: string): string {
  const option = currentContext.value?.energyOptions.find(item => item.code === code)
  return option ? `${option.name} (${option.code})` : code
}

function unitLabel(code: string): string {
  const option = currentContext.value?.unitOptions.find(item => item.code === code)
  return option ? `${option.symbol} (${option.dimension})` : code
}

function conversionOptionKey(option: { method: string; perspective: string; regionCode: string }): string {
  return [option.method, option.perspective, option.regionCode].join('|')
}

function hasSourceConversion(configuration: ReportingSourceConfiguration): boolean {
  return !!(configuration.conversionMethod || configuration.conversionPerspective || configuration.regionCode)
}

function matchesSourceConversion(option: { method: string; perspective: string; regionCode: string }, configuration: ReportingSourceConfiguration): boolean {
  return option.method === configuration.conversionMethod
    && option.perspective === configuration.conversionPerspective
    && option.regionCode === configuration.regionCode
}

function emptyWorkbookMapping(): ReportingWorkbookMapping {
  return {
    sourceId: '', sheetName: '', quantityCell: '', unitCode: '', startDate: '', endDate: '',
    dataNature: 'NATIVE_QUANTITY', evidence: '',
  }
}

function normalizeWorkbookMapping(mapping: ReportingWorkbookMapping): ReportingWorkbookMapping {
  return {
    ...mapping,
    sourceId: mapping.sourceId.trim(),
    sheetName: mapping.sheetName.trim(),
    quantityCell: mapping.quantityCell.trim().toUpperCase(),
    unitCode: mapping.unitCode.trim(),
    startDate: mapping.startDate.trim(),
    endDate: mapping.endDate.trim(),
    evidence: mapping.evidence.trim(),
  }
}

function validWorkbookMappings(mappings: ReportingWorkbookMapping[]): boolean {
  const usedSourceIds = new Set<string>()
  return mappings.length > 0 && mappings.length <= 100 && mappings.every(mapping => {
    const sourceValid = workbookMappingSources.value.some(source => source.sourceId === mapping.sourceId)
    const unitValid = workbookMappingUnitOptions.value.some(unit => unit.code === mapping.unitCode)
    const duplicate = usedSourceIds.has(mapping.sourceId)
    usedSourceIds.add(mapping.sourceId)
    return !!(sourceValid && !duplicate && mapping.sheetName && /^[A-Z]{1,3}[1-9][0-9]{0,3}$/.test(mapping.quantityCell)
      && unitValid && mapping.startDate && mapping.endDate && mapping.startDate < mapping.endDate && mapping.evidence)
  })
}

function snapshotStatusLabel(status: string): string {
  return (copy.batch.snapshot.status as Record<string, string>)[status] ?? status
}

function displaySnapshotTce(row: ReportingPreparedRow): string {
  if (row.tce === null && row.tceStatus === 'NOT_CALCULATED') return copy.batch.snapshot.notCalculated
  return row.tce ?? copy.batch.snapshot.noValue
}

function snapshotVersionLabels(row: ReportingPreparedRow): string[] {
  const conversion = row.conversionEvidence
  return [
    [copy.batch.snapshot.energyItemVersion, row.energyItemVersionId],
    [copy.batch.snapshot.unitVersion, row.unitVersionId],
    [copy.batch.snapshot.parameterVersion, conversion?.parameterVersionId],
    [copy.batch.snapshot.formulaVersion, conversion?.formulaVersionId],
    [copy.batch.snapshot.standardCoalVersion, conversion?.standardCoalVersionId],
  ].filter((entry): entry is [string, string] => !!entry[1]).map(([label, version]) => label + ' ' + version)
}

function sourceSignature(configuration: ReportingSourceConfiguration): string {
  return JSON.stringify(normalizeSourceConfiguration(configuration))
}

function batchSignatureOf(batch: ReportingBatch): string {
  return JSON.stringify({ revision: batch.revision, rows: batch.rows.map(normalizeRow) })
}

function emptySourceConfiguration(): ReportingSourceConfiguration {
  return {
    name: '', energyItemCode: '', unitCode: '', channel: 'BILL', pathway: 'PURCHASED_ELECTRICITY', purpose: 'PRIMARY',
    coverage: 'WHOLE_BUILDING', meterOverlap: 'PENDING_CONFIRMATION', timezoneId: '', effectiveFrom: '', effectiveTo: null,
    evidence: '', reason: '', conversionMethod: null, conversionPerspective: null, regionCode: null, supersedesSourceId: null,
  }
}

function cloneSourceConfiguration(configuration: ReportingSourceConfiguration, resetReason = false): ReportingSourceConfiguration {
  return {
    ...emptySourceConfiguration(),
    ...configuration,
    effectiveTo: trimToNull(configuration.effectiveTo),
    evidence: configuration.evidence ?? '',
    reason: resetReason ? '' : configuration.reason ?? '',
    conversionMethod: trimToNull(configuration.conversionMethod),
    conversionPerspective: trimToNull(configuration.conversionPerspective),
    regionCode: trimToNull(configuration.regionCode),
    supersedesSourceId: trimToNull(configuration.supersedesSourceId),
  }
}

function normalizeSourceConfiguration(configuration: ReportingSourceConfiguration): ReportingSourceConfiguration {
  return {
    ...configuration,
    name: configuration.name.trim(),
    energyItemCode: configuration.energyItemCode.trim(),
    unitCode: configuration.unitCode.trim(),
    timezoneId: configuration.timezoneId.trim(),
    effectiveFrom: configuration.effectiveFrom.trim(),
    effectiveTo: trimToNull(configuration.effectiveTo),
    evidence: configuration.evidence.trim(),
    reason: configuration.reason.trim(),
    conversionMethod: trimToNull(configuration.conversionMethod),
    conversionPerspective: trimToNull(configuration.conversionPerspective),
    regionCode: trimToNull(configuration.regionCode),
    supersedesSourceId: trimToNull(configuration.supersedesSourceId),
  }
}

function validSourceConfiguration(configuration: ReportingSourceConfiguration): boolean {
  return !!(configuration.name && configuration.energyItemCode && configuration.unitCode && configuration.timezoneId
    && configuration.effectiveFrom && configuration.evidence && configuration.reason
    && sourceUnitOptions.value.some(option => option.code === configuration.unitCode))
}

function allowsUnitDimension(pathway: ReportingSourceConfiguration['pathway'], dimension: string): boolean {
  if (pathway === 'PURCHASED_ELECTRICITY' || pathway === 'PURCHASED_HEAT') return dimension === 'ENERGY'
  if (pathway === 'STATIONARY_COMBUSTION') return dimension === 'MASS' || dimension === 'NORMAL_VOLUME' || dimension === 'ACTUAL_VOLUME'
  return true
}

function cloneBatch(batch: ReportingBatch): ReportingBatch {
  return { ...batch, rows: batch.rows.map(cloneRow) }
}

function cloneRow(row: ReportingRow): ReportingRow {
  return {
    ...row,
    quantity: trimToNull(row.quantity),
    dataNature: row.dataNature ?? 'NATIVE_QUANTITY',
    evidence: row.evidence ?? '',
    note: trimToNull(row.note),
    exclusionQuantity: trimToNull(row.exclusionQuantity),
    exclusionReason: trimToNull(row.exclusionReason),
    exclusionEvidence: trimToNull(row.exclusionEvidence),
  }
}

function normalizeRow(row: ReportingRow): ReportingRow {
  return {
    ...row,
    quantity: trimToNull(row.quantity),
    evidence: row.evidence.trim(),
    note: trimToNull(row.note),
    exclusionQuantity: trimToNull(row.exclusionQuantity),
    exclusionReason: trimToNull(row.exclusionReason),
    exclusionEvidence: trimToNull(row.exclusionEvidence),
  }
}

function validRows(rows: ReportingRow[]): boolean {
  return rows.every(row => {
    if (!(row.sourceId && Number.isFinite(row.sourceRevision) && row.startDate && row.endDate)) return false
    return row.valueStatus !== 'CONFIRMED' || !!(row.quantity?.trim() && row.evidence.trim())
  })
}

function trimToNull(value: string | null | undefined): string | null {
  const normalized = value?.trim()
  return normalized ? normalized : null
}

function routeValue(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function isMonthStart(value: string): boolean {
  return /^\d{4}-(0[1-9]|1[0-2])-01$/.test(value)
}

function currentMonth(): string {
  return `${new Date().toISOString().slice(0, 7)}-01`
}
</script>

<template>
  <main class="energy-reporting-page">
    <header class="page-heading">
      <div><h1>{{ copy.title }}</h1><p>{{ copy.description }}</p></div>
      <ElSpace wrap><ElButton data-test="carbon-results-entry" @click="openCarbonResults">{{ copy.actions.openCarbonResults }}</ElButton><ElButton @click="openParameterPackages">{{ copy.actions.openParameters }}</ElButton><ElButton @click="openProfessional">{{ copy.actions.openProfessional }}</ElButton></ElSpace>
    </header>

    <ElCard shadow="never" class="context-card">
      <ElForm label-position="top" class="context-form">
        <ElFormItem :label="copy.context.building">
          <ElSelect v-model="selectedBuildingId" data-test="reporting-building" filterable remote clearable :remote-method="searchBuildings" :loading="buildings.loading.value" :placeholder="copy.context.buildingPlaceholder" :disabled="workspace.commandLoading.value" @change="requestContextChange">
            <ElOption v-for="building in buildings.records.value" :key="building.buildingId" :value="building.buildingId" :label="building.buildingName" />
            <template #footer><ElPagination size="small" layout="total, prev, next" :current-page="buildings.page.value" :page-size="20" :total="buildings.total.value" @current-change="buildings.search" /></template>
          </ElSelect>
        </ElFormItem>
        <ElFormItem :label="copy.context.month">
          <ElDatePicker v-model="selectedMonth" data-test="reporting-month" type="month" value-format="YYYY-MM-01" format="YYYY-MM" :clearable="false" :disabled="workspace.commandLoading.value" @change="requestContextChange" />
        </ElFormItem>
        <ElButton :disabled="!selectedBuildingId || workspace.contextLoading.value || workspace.commandLoading.value" :loading="workspace.contextLoading.value" @click="refreshContext">{{ copy.context.refresh }}</ElButton>
      </ElForm>
      <ElAlert v-if="buildings.error.value" type="error" :title="buildings.error.value" :closable="false" show-icon />
      <ElAlert v-if="localError" type="error" :title="localError" :closable="false" show-icon />
      <ElAlert v-if="workspace.contextError.value" type="error" :title="workspace.contextError.value" :closable="false" show-icon />
      <ElAlert v-if="workspace.commandError.value" type="error" :title="workspace.commandError.value" :closable="false" show-icon />
    </ElCard>

    <ElSkeleton v-if="workspace.contextLoading.value && selectedBuildingId" animated :rows="8" />
    <ElAlert v-else-if="!selectedBuildingId" type="info" :title="copy.context.select" :closable="false" show-icon />
    <ElTabs v-else v-model="activeSection" class="reporting-tabs" @tab-change="changeSection">
      <ElTabPane :label="copy.tabs.source" name="source">
        <section class="tab-content">
          <ElCard shadow="never">
            <template #header>
              <div class="card-heading">
                <div><h2>{{ copy.source.title }}</h2><p>{{ copy.source.description }}</p></div>
                <ElButton v-if="currentContext?.capabilities.maintainSources" data-test="source-add" type="primary" :disabled="workspace.commandLoading.value" @click="openSourceEditor()">{{ copy.actions.addSource }}</ElButton>
              </div>
            </template>
            <ElAlert :title="copy.source.coverageHint" type="info" :closable="false" show-icon />
            <ElTable v-if="currentContext?.sources.length" :data="currentContext.sources" class="source-table">
              <ElTableColumn :label="copy.source.form.name" min-width="180"><template #default="{ row }">{{ row.configuration.name }}</template></ElTableColumn>
              <ElTableColumn :label="copy.source.form.energyItem" min-width="180"><template #default="{ row }">{{ energyLabel(row.configuration.energyItemCode) }}</template></ElTableColumn>
              <ElTableColumn :label="copy.source.form.unit" min-width="160"><template #default="{ row }">{{ unitLabel(row.configuration.unitCode) }}</template></ElTableColumn>
              <ElTableColumn :label="copy.source.form.pathway" min-width="150"><template #default="{ row }">{{ (copy.source.pathway as Record<string, string>)[row.configuration.pathway] ?? row.configuration.pathway }}</template></ElTableColumn>
              <ElTableColumn :label="copy.source.form.meterOverlap" min-width="150"><template #default="{ row }">{{ (copy.source.overlap as Record<string, string>)[row.configuration.meterOverlap] ?? row.configuration.meterOverlap }}</template></ElTableColumn>
              <ElTableColumn :label="copy.batch.status" min-width="110"><template #default="{ row }"><ElTag :type="tagType(row.status)">{{ statusLabel(row.status) }}</ElTag></template></ElTableColumn>
              <ElTableColumn :label="copy.actions.edit" min-width="230" fixed="right">
                <template #default="{ row }"><ElSpace wrap><ElButton v-if="canEditSource(reportingSource(row))" link :disabled="workspace.commandLoading.value" @click="openSourceEditor(reportingSource(row))">{{ copy.actions.edit }}</ElButton><ElButton v-if="canSubmitSource(reportingSource(row))" link type="primary" :disabled="workspace.commandLoading.value" @click="transitionSource(reportingSource(row), 'submit')">{{ copy.actions.submit }}</ElButton><ElButton v-if="canReviewSource(reportingSource(row))" link type="success" :disabled="workspace.commandLoading.value" @click="transitionSource(reportingSource(row), 'approve')">{{ copy.actions.approve }}</ElButton><ElButton v-if="canReviewSource(reportingSource(row))" link type="danger" :disabled="workspace.commandLoading.value" @click="transitionSource(reportingSource(row), 'reject')">{{ copy.actions.reject }}</ElButton></ElSpace></template>
              </ElTableColumn>
            </ElTable>
            <ElEmpty v-else :description="copy.source.empty" :image-size="48" />
            <ElFormItem v-if="currentContext?.sources.some(source => canSubmitSource(source) || canReviewSource(source))" :label="copy.source.approvalReason" class="reason-field"><ElInput v-model="sourceActionReason" data-test="source-action-reason" type="textarea" :maxlength="500" show-word-limit :disabled="workspace.commandLoading.value" :placeholder="copy.source.placeholder.reviewReason" /></ElFormItem>
            <ElAlert :title="copy.source.approvedHint" type="info" :closable="false" show-icon />
          </ElCard>
        </section>
      </ElTabPane>

      <ElTabPane :label="copy.tabs.batch" name="batch">
        <section class="tab-content">
          <ElCard shadow="never">
            <template #header>
              <div class="card-heading"><div><h2>{{ copy.batch.title }}</h2><p>{{ copy.batch.description }}</p></div><ElButton data-test="batch-create" type="primary" :disabled="!canCreateBatch || workspace.commandLoading.value || batchDirty" @click="createBatch">{{ createBatchLabel }}</ElButton></div>
            </template>
            <ElForm label-position="top" class="batch-controls">
              <ElFormItem :label="copy.batch.select"><ElSelect v-model="selectedBatchId" data-test="batch-select" clearable :disabled="workspace.commandLoading.value" @change="selectBatch"><ElOption v-for="batch in currentContext?.batches ?? []" :key="batch.batchId" :value="batch.batchId" :label="`${batch.month} · ${statusLabel(batch.status)}`" /></ElSelect></ElFormItem>
              <ElFormItem :label="copy.batch.reason"><ElInput v-model="batchActionReason" data-test="batch-reason" type="textarea" :maxlength="500" show-word-limit :disabled="workspace.commandLoading.value" :placeholder="copy.batch.reasonPlaceholder" /></ElFormItem>
            </ElForm>
            <ElAlert v-if="revisionSourceBatch" type="info" :title="copy.batch.revisionHint" :closable="false" show-icon />
            <div v-if="currentBatchEditable" class="workbook-controls">
              <ElButton data-test="workbook-template" :disabled="workspace.commandLoading.value" @click="downloadWorkbookTemplate">{{ copy.actions.downloadWorkbookTemplate }}</ElButton>
              <ElUpload action="#" accept=".xlsx" :auto-upload="false" :show-file-list="false" :disabled="workspace.commandLoading.value" :on-change="selectWorkbook">
                <ElButton data-test="workbook-select" :disabled="workspace.commandLoading.value">{{ copy.actions.chooseWorkbook }}</ElButton>
              </ElUpload>
              <span v-if="workbookFile" class="workbook-file">{{ copy.workbook.selected }}{{ workbookFile.name }}</span>
              <ElButton v-if="workbookFile" data-test="workbook-clear" link :disabled="workspace.commandLoading.value" @click="clearWorkbook">{{ copy.actions.clearWorkbook }}</ElButton>
              <ElButton v-if="workbookFile" data-test="workbook-mapping-open" :disabled="batchDirty || workspace.commandLoading.value" @click="openWorkbookMapping">{{ copy.actions.openWorkbookMapping }}</ElButton>
              <ElButton data-test="workbook-preview" type="primary" :disabled="!workbookFile || batchDirty || workspace.commandLoading.value" :loading="workspace.commandLoading.value" @click="previewStandardWorkbook">{{ copy.actions.previewWorkbook }}</ElButton>
            </div>
            <ElAlert v-if="currentBatchEditable" type="info" :title="copy.workbook.hint" :closable="false" show-icon />
            <ElAlert v-if="currentBatchEditable" type="info" :title="copy.batch.refreshSourcesHint" :closable="false" show-icon />
            <ElAlert v-if="batchDirty" type="warning" :title="copy.batch.saveFirst" :closable="false" show-icon />
            <ElAlert v-if="hasAmountEstimate" type="warning" :title="copy.batch.amountEstimate" :closable="false" show-icon />
            <ElAlert v-if="batchDraft?.rows.length" type="info" :title="copy.batch.unallocatedHint" :closable="false" show-icon />
            <ElEmpty v-if="!batchDraft" :description="copy.batch.empty" :image-size="48" />
            <template v-else>
              <div class="batch-summary"><ElTag :type="tagType(batchDraft.status)">{{ statusLabel(batchDraft.status) }}</ElTag><span>{{ copy.batch.revision }} {{ batchDraft.revision }}</span></div>
              <ElTable v-if="batchDraft.rows.length" :data="batchDraft.rows" class="batch-table">
                <ElTableColumn :label="copy.batch.source" min-width="180"><template #default="{ row }"><strong>{{ sourceName(row.sourceId) }}</strong><small>{{ energyLabel(sourceById.get(row.sourceId)?.configuration.energyItemCode ?? '') }}</small></template></ElTableColumn>
                <ElTableColumn :label="copy.batch.unit" min-width="130"><template #default="{ row }">{{ unitLabel(sourceById.get(row.sourceId)?.configuration.unitCode ?? '') }}</template></ElTableColumn>
                <ElTableColumn :label="copy.batch.quantity" min-width="150"><template #default="{ row }"><ElInput v-model="row.quantity" :disabled="!currentBatchEditable || workspace.commandLoading.value" /></template></ElTableColumn>
                <ElTableColumn :label="copy.batch.valueStatus" min-width="145"><template #default="{ row }"><ElSelect v-model="row.valueStatus" :disabled="!currentBatchEditable || workspace.commandLoading.value"><ElOption v-for="(label, value) in copy.batch.rowStatus" :key="value" :value="value" :label="label" /></ElSelect></template></ElTableColumn>
                <ElTableColumn :label="copy.batch.dataNature" min-width="160"><template #default="{ row }"><ElSelect v-model="row.dataNature" :disabled="!currentBatchEditable || workspace.commandLoading.value"><ElOption v-for="(label, value) in copy.batch.dataNatureOption" :key="value" :value="value" :label="label" /></ElSelect></template></ElTableColumn>
                <ElTableColumn :label="copy.batch.startDate" min-width="145"><template #default="{ row }"><ElDatePicker v-model="row.startDate" type="date" value-format="YYYY-MM-DD" :clearable="false" :disabled="!currentBatchEditable || workspace.commandLoading.value" /></template></ElTableColumn>
                <ElTableColumn :label="copy.batch.endDate" min-width="155"><template #default="{ row }"><ElDatePicker v-model="row.endDate" type="date" value-format="YYYY-MM-DD" :clearable="false" :disabled="!currentBatchEditable || workspace.commandLoading.value" /></template></ElTableColumn>
                <ElTableColumn :label="copy.batch.evidence" min-width="220"><template #default="{ row }"><ElInput v-model="row.evidence" type="textarea" :maxlength="500" :autosize="{ minRows: 1, maxRows: 3 }" :disabled="!currentBatchEditable || workspace.commandLoading.value" /></template></ElTableColumn>
                <ElTableColumn :label="copy.batch.note" min-width="180"><template #default="{ row }"><ElInput v-model="row.note" type="textarea" :maxlength="500" :autosize="{ minRows: 1, maxRows: 3 }" :disabled="!currentBatchEditable || workspace.commandLoading.value" /></template></ElTableColumn>
                <ElTableColumn :label="copy.batch.exclusionQuantity" min-width="140"><template #default="{ row }"><ElInput v-model="row.exclusionQuantity" :disabled="!currentBatchEditable || workspace.commandLoading.value" /></template></ElTableColumn>
                <ElTableColumn :label="copy.batch.exclusionReason" min-width="180"><template #default="{ row }"><ElInput v-model="row.exclusionReason" type="textarea" :maxlength="500" :autosize="{ minRows: 1, maxRows: 3 }" :disabled="!currentBatchEditable || workspace.commandLoading.value" /></template></ElTableColumn>
                <ElTableColumn :label="copy.batch.exclusionEvidence" min-width="220"><template #default="{ row }"><ElInput v-model="row.exclusionEvidence" type="textarea" :maxlength="500" :autosize="{ minRows: 1, maxRows: 3 }" :disabled="!currentBatchEditable || workspace.commandLoading.value" /></template></ElTableColumn>
              </ElTable>
              <ElEmpty v-else :description="copy.batch.noRows" :image-size="44" />
              <div class="batch-actions"><ElButton v-if="currentBatchEditable" data-test="batch-refresh-sources" :disabled="batchDirty || workspace.commandLoading.value" :loading="workspace.commandLoading.value" @click="transitionBatch('refresh-sources')">{{ copy.actions.refreshBatchSources }}</ElButton><ElButton v-if="currentBatchEditable" data-test="batch-save" :disabled="!batchDirty || workspace.commandLoading.value" :loading="workspace.commandLoading.value" @click="saveBatch">{{ copy.actions.save }}</ElButton><ElButton v-if="batchDirty" data-test="batch-restore" :disabled="workspace.commandLoading.value" @click="restoreBatch">{{ copy.actions.restore }}</ElButton><ElButton v-if="currentBatchEditable" data-test="batch-preview" type="primary" :disabled="batchDirty || workspace.commandLoading.value" :loading="workspace.commandLoading.value" @click="previewBatch">{{ copy.actions.preview }}</ElButton><ElButton v-if="currentBatchEditable" data-test="batch-submit" type="warning" :disabled="batchDirty || !batchPreview?.ready || workspace.commandLoading.value" :loading="workspace.commandLoading.value" @click="transitionBatch('submit')">{{ copy.actions.submit }}</ElButton><ElButton v-if="canApproveBatch(batchDraft)" data-test="batch-approve" type="success" :disabled="workspace.commandLoading.value" :loading="workspace.commandLoading.value" @click="transitionBatch('approve')">{{ copy.actions.approve }}</ElButton><ElButton v-if="canRejectBatch(batchDraft)" data-test="batch-reject" type="danger" :disabled="workspace.commandLoading.value" :loading="workspace.commandLoading.value" @click="transitionBatch('reject')">{{ copy.actions.reject }}</ElButton><ElButton v-if="canSealBatch(batchDraft)" data-test="batch-seal" type="danger" :disabled="workspace.commandLoading.value" :loading="workspace.commandLoading.value" @click="transitionBatch('seal')">{{ copy.actions.seal }}</ElButton></div>
              <ElAlert v-if="!batchDirty && !batchPreview" type="info" :title="copy.batch.previewRequired" :closable="false" show-icon />
              <section v-if="batchPreview" class="preview-result"><div class="preview-heading"><h3>{{ copy.batch.preview.title }}</h3><ElTag :type="batchPreview.ready ? 'success' : 'warning'">{{ batchPreview.ready ? copy.batch.preview.ready : copy.batch.preview.blocked }}</ElTag></div><div class="preview-status"><span>{{ copy.batch.preview.nativeStatus }}{{ copy.separator }}{{ batchPreview.nativeStatus }}</span><span>{{ copy.batch.preview.tceStatus }}{{ copy.separator }}{{ batchPreview.tceStatus }}</span></div><ElTable v-if="batchPreview.issues.length" :data="batchPreview.issues" size="small"><ElTableColumn prop="code" :label="t('energyClassification.issues.code')" min-width="150" /><ElTableColumn prop="reference" :label="t('energyClassification.issues.reference')" min-width="160" /><ElTableColumn prop="message" :label="t('energyClassification.issues.message')" min-width="260" /></ElTable><ElAlert v-else type="success" :title="copy.batch.preview.noIssues" :closable="false" show-icon /></section>
              <section v-if="frozenSnapshot" data-test="frozen-snapshot" class="preview-result snapshot-result">
                <div class="preview-heading"><div><h3>{{ copy.batch.snapshot.title }}</h3><p>{{ copy.batch.snapshot.description }}</p></div></div>
                <div class="preview-status"><span>{{ copy.batch.snapshot.nativeStatus }}{{ copy.separator }}{{ snapshotStatusLabel(frozenSnapshot.nativeStatus) }}</span><span>{{ copy.batch.snapshot.tceStatus }}{{ copy.separator }}{{ snapshotStatusLabel(frozenSnapshot.tceStatus) }}</span></div>
                <ElTable :data="frozenSnapshot.rows" size="small">
                  <ElTableColumn :label="copy.batch.source" min-width="170"><template #default="{ row }">{{ reportingPreparedRow(row).source.configuration.name }}</template></ElTableColumn>
                  <ElTableColumn :label="copy.batch.snapshot.nativeQuantity" min-width="160"><template #default="{ row }">{{ [reportingPreparedRow(row).nativeQuantity, reportingPreparedRow(row).nativeUnit].filter(Boolean).join(copy.separator) || copy.batch.snapshot.noValue }}</template></ElTableColumn>
                  <ElTableColumn :label="copy.batch.snapshot.tce" min-width="150"><template #default="{ row }">{{ displaySnapshotTce(reportingPreparedRow(row)) }}</template></ElTableColumn>
                  <ElTableColumn :label="copy.batch.snapshot.rowTceStatus" min-width="140"><template #default="{ row }">{{ snapshotStatusLabel(reportingPreparedRow(row).tceStatus) }}</template></ElTableColumn>
                  <ElTableColumn :label="copy.batch.snapshot.versions" min-width="260"><template #default="{ row }"><div class="snapshot-versions"><span v-for="version in snapshotVersionLabels(reportingPreparedRow(row))" :key="version">{{ version }}</span><span v-if="!snapshotVersionLabels(reportingPreparedRow(row)).length">{{ copy.batch.snapshot.noVersion }}</span></div></template></ElTableColumn>
                </ElTable>
              </section>
              <section v-if="workbookPreview" class="preview-result workbook-preview">
                <div class="preview-heading"><div><h3>{{ copy.workbook.title }}</h3><p>{{ copy.workbook.description }}</p></div><ElTag :type="workbookCanAdopt ? 'success' : 'warning'">{{ workbookCanAdopt ? copy.actions.adoptWorkbookRows : copy.workbook.adoptBlocked }}</ElTag></div>
                <ElAlert v-if="!workbookCanAdopt" type="error" :title="copy.workbook.adoptBlocked" :closable="false" show-icon />
                <ElTable v-if="workbookPreview.rows.length" :data="workbookPreview.rows" size="small"><ElTableColumn :label="copy.workbook.source" min-width="160"><template #default="{ row }">{{ sourceName(row.sourceId) }}</template></ElTableColumn><ElTableColumn prop="quantity" :label="copy.batch.quantity" min-width="120" /><ElTableColumn :label="copy.batch.dataNature" min-width="150"><template #default="{ row }">{{ copy.batch.dataNatureOption[row.dataNature] ?? row.dataNature }}</template></ElTableColumn><ElTableColumn prop="evidence" :label="copy.batch.evidence" min-width="200" /></ElTable>
                <ElEmpty v-else :description="copy.workbook.noRows" :image-size="44" />
                <ElTable v-if="workbookPreview.cells.length" :data="workbookPreview.cells" size="small"><ElTableColumn :label="copy.workbook.source" min-width="150"><template #default="{ row }">{{ sourceName(row.sourceId) }}</template></ElTableColumn><ElTableColumn prop="sheetName" :label="copy.workbook.sheet" min-width="140" /><ElTableColumn prop="cell" :label="copy.workbook.cell" min-width="100" /><ElTableColumn prop="originalValue" :label="copy.workbook.originalValue" min-width="140" /><ElTableColumn prop="originalUnit" :label="copy.workbook.originalUnit" min-width="130" /><ElTableColumn prop="note" :label="copy.workbook.note" min-width="200" /></ElTable>
                <ElTable v-if="workbookPreview.issues.length" :data="workbookPreview.issues" size="small"><ElTableColumn prop="code" :label="copy.workbook.issues" min-width="170" /><ElTableColumn prop="reference" :label="t('energyClassification.issues.reference')" min-width="150" /><ElTableColumn prop="message" :label="t('energyClassification.issues.message')" min-width="280" /></ElTable><ElAlert v-else type="success" :title="copy.workbook.noIssues" :closable="false" show-icon />
                <div class="batch-actions"><ElButton data-test="workbook-adopt" type="primary" :disabled="!workbookCanAdopt || workspace.commandLoading.value" @click="adoptWorkbookRows">{{ copy.actions.adoptWorkbookRows }}</ElButton><small>{{ copy.workbook.adoptHint }}</small></div>
              </section>
            </template>
          </ElCard>
        </section>
      </ElTabPane>
    </ElTabs>

    <ElDialog v-model="sourceEditorOpen" :title="sourceEditorTitle" width="min(94vw, 760px)" :close-on-click-modal="false" @closed="closeSourceEditor">
      <ElForm label-position="top" class="source-form">
        <div class="form-grid">
          <ElFormItem :label="copy.source.form.name"><ElInput v-model="sourceDraft.name" :maxlength="120" :disabled="workspace.commandLoading.value" :placeholder="copy.source.placeholder.name" /></ElFormItem>
          <ElFormItem :label="copy.source.form.energyItem"><ElSelect v-model="sourceDraft.energyItemCode" filterable :disabled="workspace.commandLoading.value"><ElOption v-for="option in currentContext?.energyOptions ?? []" :key="option.code" :value="option.code" :label="energyLabel(option.code)" /></ElSelect></ElFormItem>
          <ElFormItem v-if="currentContext?.capabilities.maintainSources" :label="copy.source.form.conversion"><ElSelect v-model="sourceConversionKey" data-test="source-conversion" clearable :disabled="workspace.commandLoading.value"><ElOption v-if="sourceConversionNeedsReview" :value="conversionReviewValue" :label="copy.source.conversionNeedsReview" disabled /><ElOption v-for="option in sourceConversionOptions" :key="conversionOptionKey(option)" :value="conversionOptionKey(option)" :label="option.label" /></ElSelect><small>{{ copy.source.conversionHint }}</small></ElFormItem>
          <ElFormItem :label="copy.source.form.unit"><ElSelect v-model="sourceDraft.unitCode" filterable :disabled="workspace.commandLoading.value"><ElOption v-for="option in sourceUnitOptions" :key="option.code" :value="option.code" :label="unitLabel(option.code)" /></ElSelect><small>{{ copy.source.unitHint }}</small></ElFormItem>
          <ElFormItem :label="copy.source.form.channel"><ElSelect v-model="sourceDraft.channel" :disabled="workspace.commandLoading.value"><ElOption v-for="(label, value) in copy.source.channel" :key="value" :value="value" :label="label" /></ElSelect></ElFormItem>
          <ElFormItem :label="copy.source.form.pathway"><ElSelect v-model="sourceDraft.pathway" :disabled="workspace.commandLoading.value"><ElOption v-for="(label, value) in copy.source.pathway" :key="value" :value="value" :label="label" /></ElSelect></ElFormItem>
          <ElFormItem :label="copy.source.form.purpose"><ElSelect v-model="sourceDraft.purpose" :disabled="workspace.commandLoading.value"><ElOption v-for="(label, value) in copy.source.purpose" :key="value" :value="value" :label="label" /></ElSelect></ElFormItem>
          <ElFormItem :label="copy.source.form.supersedesSource"><ElSelect v-model="sourceDraft.supersedesSourceId" data-test="source-supersedes" clearable :disabled="workspace.commandLoading.value"><ElOption v-for="source in replacementSourceOptions" :key="source.sourceId" :value="source.sourceId" :label="source.configuration.name" /></ElSelect></ElFormItem>
          <ElFormItem :label="copy.source.form.coverage"><ElInput :model-value="copy.source.coverageHint" disabled /></ElFormItem>
          <ElFormItem :label="copy.source.form.meterOverlap"><ElSelect v-model="sourceDraft.meterOverlap" :disabled="workspace.commandLoading.value"><ElOption v-for="(label, value) in copy.source.overlap" :key="value" :value="value" :label="label" /></ElSelect></ElFormItem>
          <ElFormItem :label="copy.source.form.timezone"><ElInput v-model="sourceDraft.timezoneId" :maxlength="64" :disabled="workspace.commandLoading.value" :placeholder="copy.source.placeholder.timezone" /></ElFormItem>
          <ElFormItem :label="copy.source.form.effectiveFrom"><ElDatePicker v-model="sourceDraft.effectiveFrom" type="date" value-format="YYYY-MM-DD" :clearable="false" :disabled="workspace.commandLoading.value" /></ElFormItem>
          <ElFormItem :label="copy.source.form.effectiveTo"><ElDatePicker v-model="sourceDraft.effectiveTo" type="date" value-format="YYYY-MM-DD" :disabled="workspace.commandLoading.value" /></ElFormItem>
          <ElFormItem class="wide-field" :label="copy.source.form.evidence"><ElInput v-model="sourceDraft.evidence" type="textarea" :maxlength="500" show-word-limit :disabled="workspace.commandLoading.value" :placeholder="copy.source.placeholder.evidence" /></ElFormItem>
          <ElFormItem class="wide-field" :label="copy.source.form.reason"><ElInput v-model="sourceDraft.reason" type="textarea" :maxlength="500" show-word-limit :disabled="workspace.commandLoading.value" :placeholder="copy.source.placeholder.reason" /></ElFormItem>
        </div>
        <small>{{ copy.source.evidenceHint }}{{ copy.separator }}{{ copy.source.supersedesHint }}</small>
      </ElForm>
      <template #footer><ElButton :disabled="workspace.commandLoading.value" @click="closeSourceEditor">{{ copy.actions.cancel }}</ElButton><ElButton type="primary" :loading="workspace.commandLoading.value" @click="saveSource">{{ copy.actions.save }}</ElButton></template>
    </ElDialog>

    <ElDialog v-model="workbookMappingOpen" :title="copy.workbook.mappingTitle" width="min(96vw, 1280px)" :close-on-click-modal="false">
      <ElAlert type="info" :title="copy.workbook.mappingHint" :closable="false" show-icon />
      <ElTable v-if="workbookMappings.length" :data="workbookMappings" class="mapping-table" size="small">
        <ElTableColumn :label="copy.workbook.mappingSource" min-width="180"><template #default="{ row }"><ElSelect v-model="row.sourceId" filterable :disabled="workspace.commandLoading.value"><ElOption v-for="source in workbookMappingSources" :key="source.sourceId" :value="source.sourceId" :label="source.configuration.name + copy.separator + energyLabel(source.configuration.energyItemCode)" /></ElSelect></template></ElTableColumn>
        <ElTableColumn :label="copy.workbook.sheet" min-width="150"><template #default="{ row }"><ElInput v-model="row.sheetName" :maxlength="120" :disabled="workspace.commandLoading.value" /></template></ElTableColumn>
        <ElTableColumn :label="copy.workbook.mappingQuantityCell" min-width="130"><template #default="{ row }"><ElInput v-model="row.quantityCell" :maxlength="7" :disabled="workspace.commandLoading.value" /></template></ElTableColumn>
        <ElTableColumn :label="copy.workbook.mappingUnit" min-width="150"><template #default="{ row }"><ElSelect v-model="row.unitCode" filterable :disabled="workspace.commandLoading.value"><ElOption v-for="unit in workbookMappingUnitOptions" :key="unit.code" :value="unit.code" :label="unitLabel(unit.code)" /></ElSelect></template></ElTableColumn>
        <ElTableColumn :label="copy.workbook.mappingStartDate" min-width="160"><template #default="{ row }"><ElDatePicker v-model="row.startDate" type="date" value-format="YYYY-MM-DD" :disabled="workspace.commandLoading.value" /></template></ElTableColumn>
        <ElTableColumn :label="copy.workbook.mappingEndDate" min-width="180"><template #default="{ row }"><ElDatePicker v-model="row.endDate" type="date" value-format="YYYY-MM-DD" :disabled="workspace.commandLoading.value" /></template></ElTableColumn>
        <ElTableColumn :label="copy.batch.dataNature" min-width="150"><template #default="{ row }"><ElSelect v-model="row.dataNature" :disabled="workspace.commandLoading.value"><ElOption v-for="(label, value) in copy.batch.dataNatureOption" :key="value" :value="value" :label="label" /></ElSelect></template></ElTableColumn>
        <ElTableColumn :label="copy.workbook.mappingEvidence" min-width="220"><template #default="{ row }"><ElInput v-model="row.evidence" :maxlength="500" :disabled="workspace.commandLoading.value" /></template></ElTableColumn>
        <ElTableColumn :label="copy.actions.remove" width="90" fixed="right"><template #default="{ $index }"><ElButton link type="danger" :disabled="workspace.commandLoading.value" @click="removeWorkbookMapping($index)">{{ copy.actions.remove }}</ElButton></template></ElTableColumn>
      </ElTable>
      <ElEmpty v-else :description="copy.workbook.noRows" :image-size="44" />
      <div class="batch-actions"><ElButton :disabled="workbookMappings.length >= 100 || workspace.commandLoading.value" @click="addWorkbookMapping">{{ copy.workbook.addMapping }}</ElButton></div>
      <template #footer><ElButton :disabled="workspace.commandLoading.value" @click="workbookMappingOpen = false">{{ copy.actions.cancel }}</ElButton><ElButton type="primary" :loading="workspace.commandLoading.value" :disabled="workspace.commandLoading.value" @click="previewWorkbookMapping">{{ copy.workbook.mappingPreview }}</ElButton></template>
    </ElDialog>
  </main>
</template>

<style scoped>
.energy-reporting-page, .tab-content, .source-form, .preview-result { display: grid; gap: var(--bec-space-section); min-width: 0; }
.page-heading, .card-heading, .batch-summary, .batch-actions, .preview-heading, .preview-status, .workbook-controls { display: flex; align-items: center; gap: var(--bec-space-group); flex-wrap: wrap; }
.page-heading, .card-heading, .preview-heading { justify-content: space-between; }
.page-heading h1, .card-heading h2, .preview-heading h3 { margin: 0; font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
.page-heading p, .card-heading p, small { margin: 0; color: var(--bec-color-text-secondary); }
.context-card { display: grid; gap: var(--bec-space-group); }
.context-form, .batch-controls { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 3)), 1fr)); gap: var(--bec-space-group); align-items: end; }
.context-form :deep(.el-form-item), .batch-controls :deep(.el-form-item), .source-form :deep(.el-form-item) { margin-bottom: 0; }
.reason-field { margin-top: var(--bec-space-section); }
.source-table, .batch-table, .mapping-table { margin-top: var(--bec-space-section); }
.batch-summary { margin-top: var(--bec-space-section); color: var(--bec-color-text-secondary); }
.batch-actions { margin-top: var(--bec-space-section); }
.workbook-controls { margin-top: var(--bec-space-section); }
.workbook-file { overflow-wrap: anywhere; color: var(--bec-color-text-secondary); }
.preview-result { padding-top: var(--bec-space-section); border-top: var(--bec-border-width) solid var(--bec-color-border); }
.preview-status { color: var(--bec-color-text-secondary); }
.snapshot-versions { display: grid; gap: var(--bec-space-tight); overflow-wrap: anywhere; }
.form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 3)), 1fr)); gap: var(--bec-space-group); }
.wide-field { grid-column: 1 / -1; }
</style>
