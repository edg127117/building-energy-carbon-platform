<script setup lang="ts">
import { createIdempotencyKey } from '@/shared/utils/idempotency-key'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElAlert, ElButton, ElCard, ElCheckbox, ElDescriptions, ElDescriptionsItem, ElEmpty, ElForm, ElFormItem,
  ElInput, ElOption, ElPagination, ElSelect, ElSkeleton, ElSpace, ElTable, ElTableColumn, ElTabPane, ElTabs, ElTag, ElTree,
} from '@/shared/ui'
import { t } from '@/locales'
import { useEnergyClassification } from '../composables/use-energy-classification'
import { useClassificationBuildings } from '../composables/use-classification-buildings'
import { useRelationGovernanceOptions } from '../composables/use-relation-governance-options'
import IssueList from '../components/IssueList.vue'
import RelationGovernanceEditor from '../components/RelationGovernanceEditor.vue'
import BindingBatchEditor from '../components/BindingBatchEditor.vue'
import CarbonProjectPanel from '../components/CarbonProjectPanel.vue'
import CarbonResultsPanel from '../components/CarbonResultsPanel.vue'
import BoundarySummaryPolicyPanel from '../components/BoundarySummaryPolicyPanel.vue'
import CarbonFactorPanel from '../components/CarbonFactorPanel.vue'
import EnergyProfileBatchPanel from '../components/EnergyProfileBatchPanel.vue'
import ReadingWorkbookPanel from '../components/ReadingWorkbookPanel.vue'
import ReadingBatchReviewPanel from '../components/ReadingBatchReviewPanel.vue'
import ManualPeriodPanel from '../components/ManualPeriodPanel.vue'
import {
  cloneConfiguration, mapCsvReadings, parseCsv, readingCsvFields, validateManualReadings,
  type ClassificationDimension, type ClassificationMode,
  type ClassificationNode, type ClassificationObjectType, type CsvRecord, type ReadingCsvField, type ReadingCsvMapping, type ReadingImportView,
} from '../models/classification'
import { localDateTimeToUtc, mergeBindingBatch, nextClassificationNodeCode, parentOptionsForNode, updateBindingBatch } from '../models/classification-editor'

interface ClassificationTreeNode {
  key: string
  label: string
  children: ClassificationTreeNode[]
}

const route = useRoute()
const router = useRouter()
const workspace = useEnergyClassification()
const buildings = useClassificationBuildings()
const relationWorkspace = useRelationGovernanceOptions()
const activeTab = ref('configuration')
const buildingInput = ref('')
const actionReason = ref('')
const copiedFromVersionId = ref<string | null>(null)
const effectiveFrom = ref('')
const localError = ref('')
const localNotice = ref('')
const projectDirty = ref(false)
const relationDirty = ref(false)
const factorDirty = ref(false)
const profileDirty = ref(false)
const workbookDirty = ref(false)
const manualPeriodDirty = ref(false)
const boundarySummaryPolicyDirty = ref(false)
const carbonResultsDirty = ref(false)
const queryForm = ref({ startInclusive: '', endExclusive: '', periodType: '', energyItemCode: '', unit: '', schemeCode: '' })
const queryFilters = ref<Array<{ schemeCode: string; nodeCode: string }>>([])
const readingRange = ref({ startInclusive: '', endExclusive: '' })
const csvHeaders = ref<string[]>([])
const csvRows = ref<CsvRecord[]>([])
const csvError = ref('')
const csvMapping = ref<ReadingCsvMapping>(emptyMapping())
const importReason = ref('')
const importKey = ref(nextImportKey())
const previewSignature = ref('')
const readingReviewBatchId = ref('')

const dimensionOptions: ClassificationDimension[] = ['USAGE', 'DEPARTMENT', 'CIRCUIT', 'REGION', 'SPACE']
const modeOptions: ClassificationMode[] = ['ADDITIVE', 'OVERLAPPING']
const objectTypeOptions: ClassificationObjectType[] = ['POINT', 'EQUIPMENT', 'SPACE']
const bindingSemantics = ['DESCRIPTION', 'METERING'] as const
const reviewStatuses = ['CONFIRMED', 'PENDING_EXPERT'] as const
const boundaryModes = ['MAIN_METER_TOTAL', 'SUBMETER_SUM', 'INDEPENDENT_METER_SUM', 'MAIN_WITH_SUBMETER_BREAKDOWN'] as const
const knownStatuses = new Set(['CONFIRMED', 'PENDING_EXPERT', 'DRAFT', 'SUBMITTED', 'PENDING_REVIEW', 'APPROVED', 'EFFECTIVE', 'REJECTED', 'ACTIVE', 'SUPERSEDED', 'WITHDRAWN'])

const configurationEditable = computed(() => workspace.selectedVersion.value?.status === 'DRAFT')
const configurationDirty = computed(() => configurationEditable.value && JSON.stringify(workspace.configuration.value) !== JSON.stringify(workspace.selectedVersion.value?.configuration))
const schemeOptions = computed(() => workspace.configuration.value.schemes.map(scheme => ({ value: scheme.code, label: scheme.code || t('common.missing') })))
const relationVersionOptions = computed(() => relationWorkspace.formalVersions.value.map(version => ({
  value: version.versionId,
  label: `${t('energyClassification.version.number')} ${version.versionNo} · ${display(version.effectiveAt)}`,
})))
const boundaryOptions = computed(() => relationWorkspace.boundaries.value.map(boundary => ({
  value: boundary.boundaryId,
  label: `${boundary.boundaryCode} · ${boundary.boundaryName}`,
})))
const pointObjectOptions = computed(() => bindingObjectOptions('POINT'))
const carbonProjectPointOptions = computed(() => workspace.configuration.value.bindings.flatMap((binding, index) => {
  const values = [
    ...(binding.objectType === 'POINT' && binding.objectId.trim() ? [{ value: binding.objectId, label: binding.objectId }] : []),
    ...(binding.meterPointId?.trim() ? [{ value: binding.meterPointId, label: binding.meterPointId }] : []),
  ]
  return values.map((item, optionIndex) => ({ ...item, key: `${index}:${optionIndex}` }))
}))
const selectedVersionId = computed(() => workspace.selectedVersion.value?.versionId ?? '')
const mappedReadings = computed(() => mapCsvReadings(csvRows.value, csvMapping.value))
const importSignature = computed(() => JSON.stringify({ readings: mappedReadings.value, reason: importReason.value.trim(), key: importKey.value.trim() }))
const canImport = computed(() => Boolean(workspace.readingPreview.value && previewSignature.value === importSignature.value))
const querySummary = computed(() => {
  const result = workspace.queryResult.value
  if (!result) return []
  return [
    { key: 'total', label: totalKindText(result.totalKind), value: result.total }, { key: 'assigned', label: t('energyClassification.analysis.assigned'), value: result.assigned },
    { key: 'unallocated', label: t('energyClassification.analysis.unallocated'), value: result.unallocated }, { key: 'residual', label: t('energyClassification.analysis.residual'), value: result.residual },
  ]
})
const configurationProgress = computed(() => ({
  schemes: workspace.configuration.value.schemes.length,
  nodes: workspace.configuration.value.nodes.length,
  bindings: workspace.configuration.value.bindings.length,
}))
const schemeTrees = computed(() => workspace.configuration.value.schemes.map(scheme => ({
  key: scheme.code,
  label: scheme.name ? `${scheme.code} · ${scheme.name}` : scheme.code || t('common.missing'),
  nodes: treeNodes(scheme.code),
})))

watch(
  () => [route.path, route.query.tab, route.query.buildingId],
  ([path, tab, buildingId]) => {
    const routePath = typeof path === 'string' ? path : ''
    activeTab.value = tab === 'carbonResults' ? 'carbonResults' : routePath.endsWith('/energyZones') ? 'analysis' : 'configuration'
    const requestedBuildingId = typeof buildingId === 'string' ? buildingId.trim() : ''
    if (tab === 'carbonResults' && requestedBuildingId && workspace.buildingId.value !== requestedBuildingId && !hasUnsavedWorkspaceChanges()) {
      buildingInput.value = requestedBuildingId
      loadBuilding()
    }
  },
  { immediate: true },
)
watch(importSignature, () => {
  if (previewSignature.value) {
    previewSignature.value = ''
    workspace.clearReadingPreview()
  }
})
watch(
  () => [workspace.buildingId.value, workspace.configuration.value.relationVersionId, relationWorkspace.formalVersions.value.map(version => version.versionId).join(',')],
  () => { void relationWorkspace.loadSelectedVersion(workspace.buildingId.value, workspace.configuration.value.relationVersionId) },
)
onMounted(() => { void buildings.search() })

function loadBuilding() {
  if (hasUnsavedWorkspaceChanges()) {
    localError.value = t('energyClassification.validation.unsaved')
    return
  }
  if (!buildingInput.value) {
    localError.value = t('energyClassification.validation.building')
    return
  }
  localError.value = ''
  copiedFromVersionId.value = null
  readingReviewBatchId.value = ''
  void workspace.loadBuilding(buildingInput.value)
  void relationWorkspace.loadBuilding(buildingInput.value)
}

function hasUnsavedWorkspaceChanges(): boolean {
  return projectDirty.value || relationDirty.value || factorDirty.value || profileDirty.value || workbookDirty.value || manualPeriodDirty.value
    || boundarySummaryPolicyDirty.value || carbonResultsDirty.value || configurationDirty.value
}

function searchBuildings(keyword: string) {
  void buildings.search(1, keyword)
}

function openDailyWorkspace() {
  const query = { ...route.query }
  delete query.mode
  void router.push({ path: route.path, query })
}

function openParameterPackages() {
  void router.push({ path: route.path, query: { ...route.query, mode: 'parameters' } })
}

function navigateProjectGuide(domain: string) {
  if (domain === 'RELATION' || domain === 'CLASSIFICATION') activeTab.value = 'configuration'
  else if (domain === 'PROJECT') activeTab.value = 'carbonProject'
  else if (domain === 'FACTORS') activeTab.value = 'carbonFactors'
  else if (domain === 'SEALED_SOURCE') activeTab.value = 'readings'
}

function selectVersion(versionId: string) {
  if (configurationDirty.value) { localError.value = t('energyClassification.validation.unsaved'); return }
  localError.value = ''
  void workspace.selectVersion(versionId)
}

function selectRelationVersion(versionId: string | null | undefined) {
  workspace.configuration.value.relationVersionId = versionId?.trim() || null
}

function createVersion() {
  if (configurationDirty.value) { localError.value = t('energyClassification.validation.unsaved'); return }
  if (!ensureBuilding() || !ensureReason(actionReason.value)) return
  void workspace.createVersion({ reason: actionReason.value.trim(), copiedFromVersionId: copiedFromVersionId.value })
}

function saveVersion() {
  if (!ensureVersion() || !ensureReason(actionReason.value)) return
  const savedBuildingId = workspace.buildingId.value
  const savedVersionId = workspace.selectedVersion.value?.versionId
  void workspace.saveSelectedVersion(actionReason.value).then(result => {
    if (result && workspace.buildingId.value === savedBuildingId && workspace.selectedVersion.value?.versionId === savedVersionId) localNotice.value = ''
  })
}

function submitVersion() {
  if (configurationDirty.value) { localError.value = t('energyClassification.validation.unsaved'); return }
  if (!ensureVersion() || !ensureReason(actionReason.value)) return
  void workspace.submitSelectedVersion(actionReason.value)
}

function restoreDraft() {
  workspace.configuration.value = cloneConfiguration(workspace.selectedVersion.value?.configuration)
  localNotice.value = ''
}

function withdrawVersion() {
  if (!ensureVersion() || !ensureReason(actionReason.value)) return
  void workspace.withdrawSelectedVersion(actionReason.value)
}

function rejectVersion() {
  if (!ensureVersion() || !ensureReason(actionReason.value)) return
  void workspace.rejectSelectedVersion(actionReason.value)
}

function approveVersion() {
  if (!ensureVersion() || !ensureReason(actionReason.value)) return
  const effectiveFromUtc = localDateTimeToUtc(effectiveFrom.value)
  if (!effectiveFromUtc) {
    localError.value = t('energyClassification.validation.effectiveFrom')
    return
  }
  void workspace.approveSelectedVersion(actionReason.value, effectiveFromUtc)
}

function addScheme() {
  workspace.configuration.value.schemes.push({ code: '', name: '', dimension: 'USAGE', mode: 'ADDITIVE' })
}

function addNode() {
  workspace.configuration.value.nodes.push({ schemeCode: '', code: nextClassificationNodeCode(workspace.configuration.value.nodes), name: '', parentCode: null })
}

function addBinding() {
  workspace.configuration.value.bindings.push({
    schemeCode: '', nodeCode: '', objectType: 'POINT', objectId: '', meterPointId: null,
    semantics: 'DESCRIPTION', status: 'PENDING_EXPERT', evidence: null,
  })
}

function addBindingBatch(bindings: import('../models/classification').ClassificationBinding[]) {
  if (!configurationEditable.value || !bindings.length) return
  const added = mergeBindingBatch(workspace.configuration.value.bindings, bindings)
  workspace.configuration.value.bindings.push(...added)
  localNotice.value = t('energyClassification.binding.batchAdded', { count: added.length })
}

function updateExistingBindings(bindings: import('../models/classification').ClassificationBinding[]) {
  if (!configurationEditable.value || !bindings.length) return
  const result = updateBindingBatch(workspace.configuration.value.bindings, bindings)
  workspace.configuration.value.bindings = result.bindings
  localNotice.value = t('energyClassification.binding.batchUpdated', { count: result.matched })
}

function parentOptions(node: Pick<ClassificationNode, 'schemeCode' | 'code'>) {
  return parentOptionsForNode(node, workspace.configuration.value.nodes)
    .map(item => ({ value: item.code, label: item.name ? `${item.code} · ${item.name}` : item.code }))
}

function addBoundaryRule() {
  workspace.configuration.value.boundaryRules.push({
    boundaryId: '', energyItemCode: '', unit: '', mode: 'INDEPENDENT_METER_SUM', status: 'PENDING_EXPERT', evidence: null, independentChannelsConfirmed: false,
  })
}

function removeRow<T>(items: T[], index: number) {
  items.splice(index, 1)
}

function nodeOptions(schemeCode: string) {
  return workspace.configuration.value.nodes
    .filter(node => node.schemeCode === schemeCode)
    .map(node => ({ value: node.code, label: node.code || t('common.missing') }))
}

function treeNodes(schemeCode: string): ClassificationTreeNode[] {
  const source = workspace.configuration.value.nodes.filter(node => node.schemeCode === schemeCode)
  const sourceByCode = new Map(source.filter(node => node.code).map(node => [node.code, node]))
  const entries = source.map((node, index) => ({
    source: node,
    node: { key: `${schemeCode}:${node.code || 'node'}:${index}`, label: node.name ? `${node.code} · ${node.name}` : node.code || t('common.missing'), children: [] as ClassificationTreeNode[] },
  }))
  const entryByCode = new Map(entries.filter(entry => entry.source.code).map(entry => [entry.source.code, entry]))
  const attached = new Set<string>()
  for (const entry of entries) {
    const parentCode = entry.source.parentCode?.trim()
    const parent = parentCode ? entryByCode.get(parentCode) : undefined
    if (!parent || !canAttachTreeNode(entry.source, sourceByCode)) continue
    parent.node.children.push(entry.node)
    attached.add(entry.node.key)
  }
  return entries.filter(entry => !attached.has(entry.node.key)).map(entry => entry.node)
}

function canAttachTreeNode(node: ClassificationNode, sourceByCode: Map<string, ClassificationNode>): boolean {
  const ownCode = node.code.trim()
  const seen = new Set<string>()
  let parentCode = node.parentCode?.trim() ?? ''
  while (parentCode) {
    if (parentCode === ownCode || seen.has(parentCode)) return false
    seen.add(parentCode)
    parentCode = sourceByCode.get(parentCode)?.parentCode?.trim() ?? ''
  }
  return true
}

function bindingObjectOptions(objectType: ClassificationObjectType) {
  return relationWorkspace.nodes.value
    .filter(node => node.nodeType === objectType && node.status !== 'UNAVAILABLE')
    .map(node => ({
      value: node.businessObjectId,
      label: `${node.code ?? node.businessObjectId} · ${node.name ?? node.businessObjectId}`,
    }))
}

function runQuery() {
  if (!ensureBuilding() || !ensureVersion()) return
  const form = queryForm.value
  const startInclusive = localDateTimeToUtc(form.startInclusive)
  const endExclusive = localDateTimeToUtc(form.endExclusive)
  if (!startInclusive || !endExclusive || !form.periodType.trim() || !form.energyItemCode.trim() || !form.unit.trim() || !form.schemeCode.trim() || queryFilters.value.length > 5 || queryFilters.value.some(filter => !filter.schemeCode.trim() || !filter.nodeCode.trim())) {
    localError.value = t('energyClassification.validation.query')
    return
  }
  localError.value = ''
  void workspace.runQuery({
    startInclusive, endExclusive, periodType: form.periodType.trim(),
    energyItemCode: form.energyItemCode.trim(), unit: form.unit.trim(), schemeCode: form.schemeCode.trim(),
    versionId: workspace.selectedVersion.value!.versionId,
    filters: queryFilters.value.map(filter => ({ schemeCode: filter.schemeCode.trim(), nodeCode: filter.nodeCode.trim() })),
  })
}

function addQueryFilter() {
  if (queryFilters.value.length >= 5) return
  queryFilters.value.push({ schemeCode: queryForm.value.schemeCode, nodeCode: '' })
}

async function readCsvFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  csvError.value = ''
  if (!file) return
  try {
    const parsed = parseCsv(await file.text())
    csvHeaders.value = parsed.headers
    csvRows.value = parsed.rows
    csvMapping.value = mappingForHeaders(parsed.headers)
    importKey.value = nextImportKey()
  } catch (reason) {
    csvHeaders.value = []
    csvRows.value = []
    csvMapping.value = emptyMapping()
    csvError.value = csvErrorMessage(reason)
  }
}

function previewImport() {
  if (!ensureBuilding()) return
  const validation = validateManualReadings(mappedReadings.value)
  if (!csvRows.value.length) {
    localError.value = t('energyClassification.validation.csv')
    return
  }
  if (validation) {
    localError.value = t('energyClassification.validation.readings')
    return
  }
  if (!ensureReason(importReason.value)) return
  localError.value = ''
  const request = readingImportRequest()
  void workspace.previewReadingImport(request).then(result => {
    if (result) previewSignature.value = importSignature.value
  })
}

function importReadings() {
  if (!canImport.value) {
    localError.value = t('energyClassification.validation.preview')
    return
  }
  localError.value = ''
  void workspace.importReadings(readingImportRequest())
}

function receiveWorkbookImport(batch: ReadingImportView) {
  workspace.importedBatch.value = batch
}

function openReadingBatchReview(batchId: string) {
  const value = batchId.trim()
  if (!value || !ensureBuilding()) return
  readingReviewBatchId.value = value
}

function refreshReadingsAfterBatchReview() {
  const startInclusive = localDateTimeToUtc(readingRange.value.startInclusive)
  const endExclusive = localDateTimeToUtc(readingRange.value.endExclusive)
  if (!startInclusive || !endExclusive) return
  localError.value = ''
  void workspace.loadReadings({ startInclusive, endExclusive })
}

function handleReadingBatchApproved(result: ReadingImportView) {
  if (workspace.importedBatch.value?.batchId === result.batchId) workspace.importedBatch.value = result
  refreshReadingsAfterBatchReview()
}

function loadReadings() {
  if (!ensureBuilding()) return
  const startInclusive = localDateTimeToUtc(readingRange.value.startInclusive)
  const endExclusive = localDateTimeToUtc(readingRange.value.endExclusive)
  if (!startInclusive || !endExclusive) {
    localError.value = t('energyClassification.validation.query')
    return
  }
  localError.value = ''
  void workspace.loadReadings({ startInclusive, endExclusive })
}

function downloadTemplate() {
  const blob = new Blob([readingCsvFields.join(',') + '\n'], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = 'metering-readings-template.csv'
  link.click()
  URL.revokeObjectURL(url)
}

function ensureBuilding(): boolean {
  if (workspace.buildingId.value) return true
  localError.value = t('energyClassification.validation.building')
  return false
}

function ensureVersion(): boolean {
  if (workspace.selectedVersion.value) return true
  localError.value = t('energyClassification.validation.version')
  return false
}

function ensureReason(reason: string): boolean {
  if (reason.trim()) return true
  localError.value = t('energyClassification.validation.reason')
  return false
}

function display(value: string | number | null | undefined): string {
  return value == null || value === '' ? t('common.missing') : String(value)
}

function statusText(status: string | null | undefined): string {
  if (status && knownStatuses.has(status)) return t(`energyClassification.status.${status}`)
  return t('energyClassification.status.unknown', { status: display(status) })
}

function statusType(status: string | null | undefined): 'success' | 'warning' | 'info' | 'danger' {
  if (status === 'CONFIRMED' || status === 'APPROVED' || status === 'EFFECTIVE') return 'success'
  if (status === 'PENDING_EXPERT' || status === 'PENDING_REVIEW' || status === 'SUBMITTED') return 'warning'
  if (status === 'REJECTED') return 'danger'
  return 'info'
}

function totalKindText(totalKind: string): string {
  if (totalKind === 'MEASURED_SUBTOTAL' || totalKind === 'BOUNDARY_TOTAL' || totalKind === 'UNDETERMINED') return t(`energyClassification.analysis.totalKind.${totalKind}`)
  return t('energyClassification.analysis.totalKind.UNDETERMINED')
}

function readingImportRequest() {
  return { idempotencyKey: importKey.value.trim(), readings: mappedReadings.value, reason: importReason.value.trim() }
}

function emptyMapping(): ReadingCsvMapping {
  return Object.fromEntries(readingCsvFields.map(field => [field, ''])) as ReadingCsvMapping
}

function mappingForHeaders(headers: string[]): ReadingCsvMapping {
  return Object.fromEntries(readingCsvFields.map(field => [field, headers.includes(field) ? field : ''])) as ReadingCsvMapping
}

function csvFieldLabel(field: ReadingCsvField): string {
  return t(`energyClassification.csvField.${field}`)
}

function csvErrorMessage(reason: unknown): string {
  const code = reason instanceof Error ? reason.message : ''
  if (code === 'CSV_QUOTE_UNCLOSED' || code === 'CSV_HEADERS_EMPTY' || code === 'CSV_HEADERS_INVALID') return t(`energyClassification.csvError.${code}`)
  return t('energyClassification.csvError.unknown')
}

function nextImportKey(): string {
  return createIdempotencyKey()
}

function balanceValue(row: Record<string, unknown>, key: 'quantity' | 'childQuantity' | 'residual') {
  const value = row[key]
  return display(typeof value === 'string' || typeof value === 'number' ? value : null)
}
</script>

<template>
  <main class="classification-page">
    <header class="page-heading">
      <div>
        <h1>{{ t('energyClassification.title') }}</h1>
        <p>{{ t('energyClassification.description') }}</p>
      </div>
      <ElSpace v-if="route.path.endsWith('/energyItems') && route.query.mode === 'professional'" wrap><ElButton @click="openParameterPackages">{{ t('energyClassification.actions.openParameterPackages') }}</ElButton><ElButton @click="openDailyWorkspace">{{ t('energyClassification.actions.openDailyWorkspace') }}</ElButton></ElSpace>
    </header>

    <ElCard shadow="never" class="building-card">
      <ElForm class="building-form" label-position="top" @submit.prevent="loadBuilding">
        <ElFormItem :label="t('energyClassification.building.label')">
          <ElSelect v-model="buildingInput" filterable remote clearable :remote-method="searchBuildings" :loading="buildings.loading.value" :placeholder="t('energyClassification.building.placeholder')" @change="loadBuilding">
            <ElOption v-for="building in buildings.records.value" :key="building.buildingId" :value="building.buildingId" :label="building.buildingName" />
            <template #footer><ElPagination small layout="total, prev, next" :current-page="buildings.page.value" :page-size="20" :total="buildings.total.value" @current-change="buildings.search" /></template>
          </ElSelect>
        </ElFormItem>
        <ElButton type="primary" native-type="submit" :loading="workspace.versionsLoading.value">
          {{ t('energyClassification.building.load') }}
        </ElButton>
      </ElForm>
      <ElAlert v-if="localError" :title="localError" type="error" :closable="false" show-icon />
      <ElAlert v-if="localNotice" :title="localNotice" type="success" show-icon @close="localNotice = ''" />
      <ElAlert v-if="buildings.error.value" :title="buildings.error.value" type="error" :closable="false" show-icon />
      <ElAlert v-if="workspace.versionsError.value" :title="workspace.versionsError.value.message" type="error" :closable="false" show-icon />
    </ElCard>

    <ElTabs v-model="activeTab" class="classification-tabs">
      <ElTabPane :label="t('energyClassification.tabs.configuration')" name="configuration">
        <section class="tab-content">
          <ElCard shadow="never">
            <template #header>
              <div class="card-heading">
                <div>
                  <h2>{{ t('energyClassification.version.title') }}</h2>
                  <p>{{ t('energyClassification.version.createHint') }}</p>
                </div>
              </div>
            </template>
            <ElSkeleton v-if="workspace.versionsLoading.value" animated :rows="3" />
            <template v-else>
              <div class="version-controls">
                <ElFormItem :label="t('energyClassification.version.select')" class="version-selector">
                  <ElSelect :model-value="selectedVersionId" clearable filterable :placeholder="t('energyClassification.placeholders.select')" :loading="workspace.versionLoading.value" :disabled="configurationDirty || workspace.commandLoading.value" @update:model-value="selectVersion">
                    <ElOption v-for="version in workspace.versions.value" :key="version.versionId" :value="version.versionId" :label="`${t('energyClassification.version.number')} ${version.versionNo} · ${statusText(version.status)}`" />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem :label="t('energyClassification.version.copySource')" class="version-selector">
                  <ElSelect v-model="copiedFromVersionId" clearable filterable :placeholder="t('energyClassification.version.emptySource')" :disabled="workspace.commandLoading.value">
                    <ElOption v-for="version in workspace.versions.value" :key="version.versionId" :value="version.versionId" :label="`${t('energyClassification.version.number')} ${version.versionNo} · ${statusText(version.status)}`" />
                  </ElSelect>
                </ElFormItem>
                <ElButton :disabled="!workspace.buildingId.value || workspace.commandLoading.value" :loading="workspace.commandLoading.value" @click="createVersion">
                  {{ t('energyClassification.actions.create') }}
                </ElButton>
              </div>
              <ElEmpty v-if="workspace.buildingId.value && !workspace.versions.value.length" :description="t('energyClassification.building.empty')" :image-size="56" />
              <ElAlert v-if="workspace.versionError.value" :title="workspace.versionError.value.message" type="error" :closable="false" show-icon />
              <ElDescriptions v-if="workspace.selectedVersion.value" :column="2" border class="version-details">
                <ElDescriptionsItem :label="t('energyClassification.version.number')">{{ workspace.selectedVersion.value.versionNo }}</ElDescriptionsItem>
                <ElDescriptionsItem :label="t('energyClassification.version.revision')">{{ workspace.selectedVersion.value.revision }}</ElDescriptionsItem>
                <ElDescriptionsItem :label="t('energyClassification.version.status')"><ElTag :type="statusType(workspace.selectedVersion.value.status)">{{ statusText(workspace.selectedVersion.value.status) }}</ElTag></ElDescriptionsItem>
                <ElDescriptionsItem :label="t('energyClassification.version.effectiveFrom')">{{ display(workspace.selectedVersion.value.effectiveFrom) }}</ElDescriptionsItem>
                <ElDescriptionsItem :label="t('energyClassification.version.effectiveTo')">{{ display(workspace.selectedVersion.value.effectiveTo) }}</ElDescriptionsItem>
                <ElDescriptionsItem :label="t('energyClassification.version.reviewedBy')">{{ display(workspace.selectedVersion.value.reviewedBy) }}</ElDescriptionsItem>
              </ElDescriptions>
            </template>
          </ElCard>

          <ElCard shadow="never">
            <template #header><div class="card-heading"><div><h2>{{ t('energyClassification.configuration.title') }}</h2></div></div></template>
            <ElAlert :title="t('energyClassification.configuration.progressHint')" type="info" :closable="false" show-icon />
            <ElButton v-if="configurationDirty" :disabled="workspace.commandLoading.value" @click="restoreDraft">{{ t('energyClassification.actions.restoreDraft') }}</ElButton>
            <div class="configuration-progress" :aria-label="t('energyClassification.configuration.localProgress')">
              <span>{{ t('energyClassification.configuration.progressSchemes', { count: configurationProgress.schemes }) }}</span>
              <span>{{ t('energyClassification.configuration.progressNodes', { count: configurationProgress.nodes }) }}</span>
              <span>{{ t('energyClassification.configuration.progressBindings', { count: configurationProgress.bindings }) }}</span>
            </div>
            <ElAlert v-if="workspace.selectedVersion.value && !configurationEditable" :title="t('energyClassification.configuration.frozen')" type="info" :closable="false" show-icon />
            <ElForm label-position="top" class="configuration-form">
              <div class="form-grid">
                <ElFormItem :label="t('energyClassification.fields.relationVersionId')">
                  <ElSelect :model-value="workspace.configuration.value.relationVersionId ?? undefined" clearable filterable :placeholder="t('energyClassification.placeholders.relationVersion')" :disabled="!configurationEditable || !workspace.buildingId.value" :loading="relationWorkspace.loading.value" @update:model-value="selectRelationVersion">
                    <ElOption v-for="item in relationVersionOptions" :key="item.value" :value="item.value" :label="item.label" />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem :label="t('energyClassification.fields.reason')">
                  <ElInput v-model="actionReason" :placeholder="t('energyClassification.fields.reasonPlaceholder')" :disabled="!workspace.buildingId.value" clearable />
                </ElFormItem>
                <ElFormItem :label="t('energyClassification.fields.effectiveFrom')">
                  <input v-model="effectiveFrom" type="datetime-local" class="local-datetime-input" :aria-label="t('energyClassification.fields.effectiveFrom')" :disabled="!workspace.selectedVersion.value">
                </ElFormItem>
              </div>
              <ElSpace wrap class="version-actions">
                <ElButton type="primary" :disabled="!configurationEditable || workspace.commandLoading.value" :loading="workspace.commandLoading.value" @click="saveVersion">{{ t('energyClassification.actions.save') }}</ElButton>
                <ElButton :disabled="!workspace.selectedVersion.value || workspace.commandLoading.value" :loading="workspace.commandLoading.value" @click="submitVersion">{{ t('energyClassification.actions.submit') }}</ElButton>
                <ElButton :disabled="!workspace.selectedVersion.value || workspace.commandLoading.value" :loading="workspace.commandLoading.value" @click="withdrawVersion">{{ t('energyClassification.actions.withdraw') }}</ElButton>
                <ElButton :disabled="!workspace.selectedVersion.value || workspace.commandLoading.value" :loading="workspace.commandLoading.value" @click="rejectVersion">{{ t('energyClassification.actions.reject') }}</ElButton>
                <ElButton :disabled="!workspace.selectedVersion.value || workspace.commandLoading.value" :loading="workspace.commandLoading.value" @click="approveVersion">{{ t('energyClassification.actions.approve') }}</ElButton>
              </ElSpace>
            </ElForm>
            <ElAlert v-if="workspace.commandError.value" :title="workspace.commandError.value.message" type="error" :closable="false" show-icon />
          </ElCard>

          <ElCard shadow="never" class="editor-card">
            <template #header>
              <div class="card-heading"><div><h2>{{ t('energyClassification.relation.title') }}</h2><p>{{ t('energyClassification.relation.description') }}</p></div></div>
            </template>
            <ElAlert :title="t('energyClassification.relation.editHint')" type="info" :closable="false" show-icon />
            <ElSkeleton v-if="relationWorkspace.loading.value || relationWorkspace.detailLoading.value" animated :rows="4" />
            <template v-else>
              <ElAlert v-if="relationWorkspace.error.value" :title="relationWorkspace.error.value.message" type="error" :closable="false" show-icon />
              <ElEmpty v-else-if="!workspace.configuration.value.relationVersionId" :description="t('energyClassification.relation.noSelection')" :image-size="48" />
              <ElAlert v-else-if="!relationWorkspace.selectedVersion.value" :title="t('energyClassification.relation.noFormalVersion')" type="warning" :closable="false" show-icon />
              <template v-else>
                <ElDescriptions :column="3" border class="relation-details">
                  <ElDescriptionsItem :label="t('energyClassification.relation.selectVersion')">{{ relationWorkspace.selectedVersion.value.versionNo }}</ElDescriptionsItem>
                  <ElDescriptionsItem :label="t('energyClassification.fields.status')"><ElTag :type="statusType(relationWorkspace.selectedVersion.value.status)">{{ statusText(relationWorkspace.selectedVersion.value.status) }}</ElTag></ElDescriptionsItem>
                  <ElDescriptionsItem :label="t('energyClassification.fields.relationEffectiveAt')">{{ display(relationWorkspace.metadata.value?.effectiveAt ?? relationWorkspace.selectedVersion.value.effectiveAt) }}</ElDescriptionsItem>
                </ElDescriptions>
                <h3>{{ t('energyClassification.relation.boundaries') }}</h3>
                <ElTable v-if="relationWorkspace.boundaries.value.length" :data="relationWorkspace.boundaries.value">
                  <ElTableColumn prop="boundaryCode" :label="t('energyClassification.fields.boundaryCode')" min-width="150" />
                  <ElTableColumn prop="boundaryName" :label="t('energyClassification.fields.boundaryName')" min-width="180" />
                  <ElTableColumn prop="energyType" :label="t('energyClassification.fields.energyItemCode')" min-width="150" />
                  <ElTableColumn :label="t('energyClassification.fields.status')" min-width="140"><template #default="{ row }"><ElTag :type="statusType(row.status)">{{ statusText(row.status) }}</ElTag></template></ElTableColumn>
                </ElTable>
                <ElEmpty v-else :description="t('energyClassification.relation.noBoundaries')" :image-size="48" />
                <h3>{{ t('energyClassification.relation.structures') }}</h3>
                <ElTable v-if="relationWorkspace.structures.value.length" :data="relationWorkspace.structures.value">
                  <ElTableColumn prop="meterPointCode" :label="t('energyClassification.fields.meterPointCode')" min-width="160" />
                  <ElTableColumn prop="meterRole" :label="t('energyClassification.fields.meterRole')" min-width="130" />
                  <ElTableColumn prop="parentMeterPointCode" :label="t('energyClassification.fields.parentMeterPointCode')" min-width="180" />
                  <ElTableColumn prop="meterDirection" :label="t('energyClassification.fields.meterDirection')" min-width="150" />
                  <ElTableColumn :label="t('energyClassification.fields.status')" min-width="140"><template #default="{ row }"><ElTag :type="statusType(row.confirmationStatus)">{{ statusText(row.confirmationStatus) }}</ElTag></template></ElTableColumn>
                </ElTable>
                <ElEmpty v-else :description="t('energyClassification.relation.noStructures')" :image-size="48" />
              </template>
            </template>
          </ElCard>

          <RelationGovernanceEditor :building-id="workspace.buildingId.value" @dirty-change="relationDirty = $event" />
          <EnergyProfileBatchPanel :building-id="workspace.buildingId.value" :point-options="pointObjectOptions" @dirty-change="profileDirty = $event" />

          <ElCard shadow="never" class="editor-card">
            <template #header>
              <div class="card-heading"><div><h2>{{ t('energyClassification.scheme.title') }}</h2><p>{{ t('energyClassification.scheme.description') }}</p></div><ElButton :disabled="!configurationEditable" @click="addScheme">{{ t('energyClassification.actions.add') }}</ElButton></div>
            </template>
            <ElTable v-if="workspace.configuration.value.schemes.length" :data="workspace.configuration.value.schemes">
              <ElTableColumn :label="t('energyClassification.fields.schemeCode')" min-width="160"><template #default="{ row }"><ElInput v-model="row.code" :disabled="!configurationEditable" :placeholder="t('energyClassification.placeholders.code')" /></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.schemeName')" min-width="180"><template #default="{ row }"><ElInput v-model="row.name" :disabled="!configurationEditable" :placeholder="t('energyClassification.placeholders.name')" /></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.dimension')" min-width="140"><template #default="{ row }"><ElSelect v-model="row.dimension" :disabled="!configurationEditable"><ElOption v-for="item in dimensionOptions" :key="item" :value="item" :label="t(`energyClassification.dimension.${item}`)" /></ElSelect></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.mode')" min-width="140"><template #default="{ row }"><ElSelect v-model="row.mode" :disabled="!configurationEditable"><ElOption v-for="item in modeOptions" :key="item" :value="item" :label="t(`energyClassification.mode.${item}`)" /></ElSelect></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.actions.remove')" width="100" fixed="right"><template #default="{ $index }"><ElButton link type="danger" :disabled="!configurationEditable" @click="removeRow(workspace.configuration.value.schemes, $index)">{{ t('energyClassification.actions.remove') }}</ElButton></template></ElTableColumn>
            </ElTable>
            <ElEmpty v-else :description="t('energyClassification.scheme.empty')" :image-size="48" />
          </ElCard>

          <ElCard shadow="never" class="editor-card">
            <template #header>
              <div class="card-heading"><div><h2>{{ t('energyClassification.node.title') }}</h2><p>{{ t('energyClassification.node.description') }}</p></div><ElButton :disabled="!configurationEditable" @click="addNode">{{ t('energyClassification.actions.add') }}</ElButton></div>
            </template>
            <ElTable v-if="workspace.configuration.value.nodes.length" :data="workspace.configuration.value.nodes">
              <ElTableColumn :label="t('energyClassification.fields.schemeCode')" min-width="150"><template #default="{ row }"><ElSelect v-model="row.schemeCode" :disabled="!configurationEditable"><ElOption v-for="item in schemeOptions" :key="item.value" :value="item.value" :label="item.label" /></ElSelect></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.nodeCode')" min-width="150"><template #default="{ row }"><ElInput v-model="row.code" :disabled="!configurationEditable" :placeholder="t('energyClassification.placeholders.code')" /></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.nodeName')" min-width="180"><template #default="{ row }"><ElInput v-model="row.name" :disabled="!configurationEditable" :placeholder="t('energyClassification.placeholders.name')" /></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.parentCode')" min-width="180"><template #default="{ row }"><ElSelect :model-value="row.parentCode ?? undefined" clearable filterable :disabled="!configurationEditable || !parentOptions(row as ClassificationNode).length" :placeholder="t('energyClassification.node.rootNode')" @update:model-value="row.parentCode = $event || null"><ElOption v-for="item in parentOptions(row as ClassificationNode)" :key="item.value" :value="item.value" :label="item.label" /></ElSelect></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.actions.remove')" width="100" fixed="right"><template #default="{ $index }"><ElButton link type="danger" :disabled="!configurationEditable" @click="removeRow(workspace.configuration.value.nodes, $index)">{{ t('energyClassification.actions.remove') }}</ElButton></template></ElTableColumn>
            </ElTable>
            <ElEmpty v-else :description="t('energyClassification.node.empty')" :image-size="48" />
          </ElCard>

          <ElCard shadow="never" class="editor-card">
            <template #header><div class="card-heading"><div><h2>{{ t('energyClassification.node.treePreview') }}</h2><p>{{ t('energyClassification.node.treePreviewHint') }}</p></div></div></template>
            <ElEmpty v-if="!schemeTrees.length" :description="t('energyClassification.scheme.empty')" :image-size="48" />
            <div v-else class="tree-preview-list">
              <section v-for="tree in schemeTrees" :key="tree.key" class="tree-preview-section">
                <h3>{{ tree.label }}</h3>
                <ElTree v-if="tree.nodes.length" :data="tree.nodes" node-key="key" :props="{ label: 'label', children: 'children' }" default-expand-all />
                <ElEmpty v-else :description="t('energyClassification.node.treePreviewEmpty')" :image-size="40" />
              </section>
            </div>
          </ElCard>

          <ElCard shadow="never" class="editor-card">
            <template #header>
              <div class="card-heading"><div><h2>{{ t('energyClassification.binding.title') }}</h2><p>{{ t('energyClassification.binding.description') }}</p></div><ElButton :disabled="!configurationEditable" @click="addBinding">{{ t('energyClassification.actions.add') }}</ElButton></div>
            </template>
            <BindingBatchEditor :context-key="`${workspace.buildingId.value}/${workspace.selectedVersion.value?.versionId}`" :disabled="!configurationEditable" :schemes="schemeOptions" :nodes="nodeOptions" :objects="bindingObjectOptions" :object-types="objectTypeOptions" @add="addBindingBatch" @update="updateExistingBindings" />
            <ElTable v-if="workspace.configuration.value.bindings.length" :data="workspace.configuration.value.bindings">
              <ElTableColumn :label="t('energyClassification.fields.schemeCode')" min-width="140"><template #default="{ row }"><ElSelect v-model="row.schemeCode" :disabled="!configurationEditable"><ElOption v-for="item in schemeOptions" :key="item.value" :value="item.value" :label="item.label" /></ElSelect></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.nodeCode')" min-width="140"><template #default="{ row }"><ElSelect v-model="row.nodeCode" :disabled="!configurationEditable"><ElOption v-for="item in nodeOptions(row.schemeCode)" :key="item.value" :value="item.value" :label="item.label" /></ElSelect></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.objectType')" min-width="130"><template #default="{ row }"><ElSelect v-model="row.objectType" :disabled="!configurationEditable"><ElOption v-for="item in objectTypeOptions" :key="item" :value="item" :label="t(`energyClassification.objectType.${item}`)" /></ElSelect></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.objectId')" min-width="220"><template #default="{ row }"><ElSelect v-model="row.objectId" filterable :disabled="!configurationEditable || !bindingObjectOptions(row.objectType).length" :placeholder="t('energyClassification.placeholders.select')"><ElOption v-for="item in bindingObjectOptions(row.objectType)" :key="item.value" :value="item.value" :label="item.label" /></ElSelect></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.meterPointId')" min-width="220"><template #default="{ row }"><ElSelect v-model="row.meterPointId" clearable filterable :disabled="!configurationEditable || !pointObjectOptions.length" :placeholder="t('energyClassification.placeholders.optional')"><ElOption v-for="item in pointObjectOptions" :key="item.value" :value="item.value" :label="item.label" /></ElSelect></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.semantics')" min-width="140"><template #default="{ row }"><ElSelect v-model="row.semantics" :disabled="!configurationEditable"><ElOption v-for="item in bindingSemantics" :key="item" :value="item" :label="t(`energyClassification.semantics.${item}`)" /></ElSelect></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.status')" min-width="140"><template #default="{ row }"><ElSelect v-model="row.status" :disabled="!configurationEditable"><ElOption v-for="item in reviewStatuses" :key="item" :value="item" :label="t(`energyClassification.status.${item}`)" /></ElSelect></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.evidence')" min-width="180"><template #default="{ row }"><ElInput v-model="row.evidence" :disabled="!configurationEditable" :placeholder="t('energyClassification.placeholders.optional')" clearable /></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.actions.remove')" width="100" fixed="right"><template #default="{ $index }"><ElButton link type="danger" :disabled="!configurationEditable" @click="removeRow(workspace.configuration.value.bindings, $index)">{{ t('energyClassification.actions.remove') }}</ElButton></template></ElTableColumn>
            </ElTable>
            <ElEmpty v-else :description="t('energyClassification.binding.empty')" :image-size="48" />
          </ElCard>

          <ElCard shadow="never" class="editor-card">
            <template #header>
              <div class="card-heading"><div><h2>{{ t('energyClassification.boundaryRule.title') }}</h2><p>{{ t('energyClassification.boundaryRule.description') }}</p></div><ElButton :disabled="!configurationEditable" @click="addBoundaryRule">{{ t('energyClassification.actions.add') }}</ElButton></div>
            </template>
            <ElTable v-if="workspace.configuration.value.boundaryRules.length" :data="workspace.configuration.value.boundaryRules">
              <ElTableColumn :label="t('energyClassification.fields.boundaryId')" min-width="220"><template #default="{ row }"><ElSelect v-model="row.boundaryId" filterable :disabled="!configurationEditable || !boundaryOptions.length" :placeholder="t('energyClassification.placeholders.select')"><ElOption v-for="item in boundaryOptions" :key="item.value" :value="item.value" :label="item.label" /></ElSelect></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.energyItemCode')" min-width="150"><template #default="{ row }"><ElInput v-model="row.energyItemCode" :disabled="!configurationEditable" :placeholder="t('energyClassification.placeholders.code')" /></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.unit')" min-width="120"><template #default="{ row }"><ElInput v-model="row.unit" :disabled="!configurationEditable" :placeholder="t('energyClassification.placeholders.code')" /></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.mode')" min-width="180"><template #default="{ row }"><ElSelect v-model="row.mode" :disabled="!configurationEditable"><ElOption v-for="item in boundaryModes" :key="item" :value="item" :label="t(`energyClassification.boundaryMode.${item}`)" /></ElSelect></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.independentChannelsConfirmed')" min-width="220"><template #default="{ row }"><ElCheckbox v-model="row.independentChannelsConfirmed" :disabled="!configurationEditable" /></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.status')" min-width="140"><template #default="{ row }"><ElSelect v-model="row.status" :disabled="!configurationEditable"><ElOption v-for="item in reviewStatuses" :key="item" :value="item" :label="t(`energyClassification.status.${item}`)" /></ElSelect></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.fields.evidence')" min-width="180"><template #default="{ row }"><ElInput v-model="row.evidence" :disabled="!configurationEditable" :placeholder="t('energyClassification.placeholders.optional')" clearable /></template></ElTableColumn>
              <ElTableColumn :label="t('energyClassification.actions.remove')" width="100" fixed="right"><template #default="{ $index }"><ElButton link type="danger" :disabled="!configurationEditable" @click="removeRow(workspace.configuration.value.boundaryRules, $index)">{{ t('energyClassification.actions.remove') }}</ElButton></template></ElTableColumn>
            </ElTable>
            <ElEmpty v-else :description="t('energyClassification.boundaryRule.empty')" :image-size="48" />
          </ElCard>

          <ElCard v-if="workspace.selectedVersion.value" shadow="never">
            <template #header><div class="card-heading"><div><h2>{{ t('energyClassification.configuration.issueTitle') }}</h2></div></div></template>
            <IssueList :issues="workspace.selectedVersion.value.issues" />
          </ElCard>
        </section>
      </ElTabPane>

      <ElTabPane :label="t('energyClassification.tabs.carbonProject')" name="carbonProject">
        <section class="tab-content">
          <CarbonProjectPanel :building-id="workspace.buildingId.value" :classification-versions="workspace.versions.value" :point-options="carbonProjectPointOptions" @dirty-change="projectDirty = $event" @navigate="navigateProjectGuide" />
        </section>
      </ElTabPane>

      <ElTabPane :label="t('energyClassification.tabs.carbonFactors')" name="carbonFactors">
        <CarbonFactorPanel :building-id="workspace.buildingId.value" @dirty-change="factorDirty = $event" />
      </ElTabPane>

      <ElTabPane :label="t('energyClassification.carbonResults.title')" name="carbonResults">
        <section class="tab-content">
          <BoundarySummaryPolicyPanel :building-id="workspace.buildingId.value" @dirty-change="boundarySummaryPolicyDirty = $event" />
          <CarbonResultsPanel :building-id="workspace.buildingId.value" :reporting-month="typeof route.query.reportingMonth === 'string' ? route.query.reportingMonth : ''" @dirty-change="carbonResultsDirty = $event" />
        </section>
      </ElTabPane>

      <ElTabPane :label="t('energyClassification.tabs.analysis')" name="analysis">
        <section class="tab-content">
          <ElCard shadow="never">
            <template #header><div class="card-heading"><div><h2>{{ t('energyClassification.analysis.title') }}</h2><p>{{ t('energyClassification.analysis.description') }}</p></div></div></template>
            <ElForm label-position="top" class="query-form" @submit.prevent="runQuery">
              <div class="form-grid">
                <ElFormItem :label="t('energyClassification.analysis.selectedVersion')"><ElInput :model-value="workspace.selectedVersion.value ? String(workspace.selectedVersion.value.versionNo) : ''" disabled /></ElFormItem>
                <ElFormItem :label="t('energyClassification.fields.startInclusive')"><input v-model="queryForm.startInclusive" type="datetime-local" class="local-datetime-input" :aria-label="t('energyClassification.fields.startInclusive')"></ElFormItem>
                <ElFormItem :label="t('energyClassification.fields.endExclusive')"><input v-model="queryForm.endExclusive" type="datetime-local" class="local-datetime-input" :aria-label="t('energyClassification.fields.endExclusive')"></ElFormItem>
                <ElFormItem :label="t('energyClassification.fields.periodType')"><ElInput v-model="queryForm.periodType" :placeholder="t('energyClassification.placeholders.periodType')" /></ElFormItem>
                <ElFormItem :label="t('energyClassification.fields.energyItemCode')"><ElInput v-model="queryForm.energyItemCode" :placeholder="t('energyClassification.placeholders.code')" /></ElFormItem>
                <ElFormItem :label="t('energyClassification.fields.unit')"><ElInput v-model="queryForm.unit" :placeholder="t('energyClassification.placeholders.code')" /></ElFormItem>
                <ElFormItem :label="t('energyClassification.fields.schemeCode')"><ElSelect v-model="queryForm.schemeCode" filterable clearable :placeholder="t('energyClassification.placeholders.select')"><ElOption v-for="item in schemeOptions" :key="item.value" :value="item.value" :label="item.label" /></ElSelect></ElFormItem>
              </div>
              <h3>{{ t('energyClassification.analysis.filters') }}</h3>
              <ElTable v-if="queryFilters.length" :data="queryFilters"><ElTableColumn :label="t('energyClassification.fields.schemeCode')" min-width="180"><template #default="{ row }"><ElSelect v-model="row.schemeCode" filterable :placeholder="t('energyClassification.placeholders.select')"><ElOption v-for="item in schemeOptions" :key="item.value" :value="item.value" :label="item.label" /></ElSelect></template></ElTableColumn><ElTableColumn :label="t('energyClassification.fields.nodeCode')" min-width="180"><template #default="{ row }"><ElSelect v-model="row.nodeCode" filterable :placeholder="t('energyClassification.placeholders.select')"><ElOption v-for="item in nodeOptions(row.schemeCode)" :key="item.value" :value="item.value" :label="item.label" /></ElSelect></template></ElTableColumn><ElTableColumn :label="t('energyClassification.actions.remove')" width="100"><template #default="{ $index }"><ElButton link type="danger" @click="removeRow(queryFilters, $index)">{{ t('energyClassification.actions.remove') }}</ElButton></template></ElTableColumn></ElTable>
              <ElButton :disabled="queryFilters.length >= 5" @click="addQueryFilter">{{ t('energyClassification.analysis.addFilter') }}</ElButton>
              <ElButton type="primary" native-type="submit" :loading="workspace.queryLoading.value" :disabled="!workspace.selectedVersion.value">{{ t('energyClassification.actions.query') }}</ElButton>
            </ElForm>
            <ElAlert v-if="workspace.queryError.value" :title="workspace.queryError.value.message" type="error" :closable="false" show-icon />
            <ElEmpty v-if="!workspace.queryLoading.value && !workspace.queryResult.value" :description="t('energyClassification.analysis.noResult')" :image-size="56" />
          </ElCard>

          <template v-if="workspace.queryResult.value">
            <ElCard shadow="never">
              <template #header><div class="card-heading"><div><h2>{{ t('energyClassification.analysis.summary') }}</h2></div></div></template>
              <div class="summary-grid">
                <article v-for="item in querySummary" :key="item.key" class="summary-card"><span>{{ item.label }}</span><strong>{{ display(item.value) }} {{ workspace.queryResult.value.unit }}</strong></article>
                <article class="summary-card"><span>{{ t('energyClassification.analysis.resultNature') }}</span><strong>{{ workspace.queryResult.value.resultNature }}</strong></article>
                <article class="summary-card"><span>{{ t('energyClassification.analysis.additive') }}</span><strong>{{ workspace.queryResult.value.additive ? t('energyClassification.analysis.additive') : t('energyClassification.analysis.nonAdditive') }}</strong></article>
              </div>
            </ElCard>
            <ElCard shadow="never">
              <template #header><div class="card-heading"><div><h2>{{ t('energyClassification.analysis.nodeResults') }}</h2></div></div></template>
              <ElTable v-if="workspace.queryResult.value.nodes.length" :data="workspace.queryResult.value.nodes">
                <ElTableColumn prop="nodeCode" :label="t('energyClassification.fields.nodeCode')" min-width="150" />
                <ElTableColumn prop="nodeName" :label="t('energyClassification.fields.nodeName')" min-width="180" />
                <ElTableColumn :label="t('energyClassification.analysis.quantity')" min-width="130"><template #default="{ row }">{{ display(row.quantity) }} {{ workspace.queryResult.value.unit }}</template></ElTableColumn>
                <ElTableColumn :label="t('energyClassification.fields.status')" min-width="130"><template #default="{ row }"><ElTag :type="statusType(row.status)">{{ statusText(row.status) }}</ElTag></template></ElTableColumn>
                <ElTableColumn :label="t('energyClassification.analysis.points')" min-width="180"><template #default="{ row }">{{ row.pointIds.join(', ') || t('common.missing') }}</template></ElTableColumn>
                <ElTableColumn :label="t('energyClassification.analysis.evidence')" min-width="180"><template #default="{ row }">{{ row.evidenceIds.join(', ') || t('common.missing') }}</template></ElTableColumn>
              </ElTable>
              <ElEmpty v-else :description="t('energyClassification.analysis.nodeEmpty')" :image-size="48" />
            </ElCard>
            <ElCard shadow="never">
              <template #header><div class="card-heading"><div><h2>{{ t('energyClassification.analysis.balances') }}</h2></div></div></template>
              <ElTable v-if="workspace.queryResult.value.balances.length" :data="workspace.queryResult.value.balances">
                <ElTableColumn prop="boundaryId" :label="t('energyClassification.fields.boundaryId')" min-width="170" />
                <ElTableColumn prop="pointId" :label="t('energyClassification.fields.meterPointId')" min-width="170" />
                <ElTableColumn :label="t('energyClassification.analysis.quantity')" min-width="130"><template #default="{ row }">{{ balanceValue(row, 'quantity') }} {{ workspace.queryResult.value.unit }}</template></ElTableColumn>
                <ElTableColumn :label="t('energyClassification.analysis.assigned')" min-width="130"><template #default="{ row }">{{ balanceValue(row, 'childQuantity') }} {{ workspace.queryResult.value.unit }}</template></ElTableColumn>
                <ElTableColumn :label="t('energyClassification.analysis.residual')" min-width="130"><template #default="{ row }">{{ balanceValue(row, 'residual') }} {{ workspace.queryResult.value.unit }}</template></ElTableColumn>
                <ElTableColumn :label="t('energyClassification.fields.status')" min-width="130"><template #default="{ row }"><ElTag :type="statusType(row.status)">{{ statusText(row.status) }}</ElTag></template></ElTableColumn>
              </ElTable>
              <ElEmpty v-else :description="t('energyClassification.analysis.balanceEmpty')" :image-size="48" />
            </ElCard>
            <ElCard shadow="never"><template #header><div class="card-heading"><div><h2>{{ t('energyClassification.analysis.issues') }}</h2></div></div></template><IssueList :issues="workspace.queryResult.value.issues" /></ElCard>
          </template>
        </section>
      </ElTabPane>

      <ElTabPane :label="t('energyClassification.tabs.readings')" name="readings">
        <ReadingWorkbookPanel :building-id="workspace.buildingId.value" @dirty-change="workbookDirty = $event" @imported="receiveWorkbookImport" />
        <ManualPeriodPanel :building-id="workspace.buildingId.value" :point-options="pointObjectOptions" @dirty-change="manualPeriodDirty = $event" />
        <section class="tab-content">
          <ElCard shadow="never">
            <template #header><div class="card-heading"><div><h2>{{ t('energyClassification.readings.title') }}</h2><p>{{ t('energyClassification.readings.description') }}</p></div><ElButton @click="downloadTemplate">{{ t('energyClassification.actions.downloadTemplate') }}</ElButton></div></template>
            <ElAlert :title="t('energyClassification.readings.templateHint')" type="info" :closable="false" show-icon />
            <ElAlert :title="t('energyClassification.readings.approvalNotice')" type="warning" :closable="false" show-icon />
            <div class="file-row"><label class="file-label" for="reading-csv">{{ t('energyClassification.readings.csvFile') }}</label><input id="reading-csv" class="csv-file-input" type="file" accept=".csv,text/csv" @change="readCsvFile"></div>
            <ElAlert v-if="csvError" :title="csvError" type="error" :closable="false" show-icon />
            <ElAlert v-if="workspace.importError.value" :title="workspace.importError.value.message" type="error" :closable="false" show-icon />
            <ElEmpty v-if="!csvRows.length" :description="t('energyClassification.readings.noFile')" :image-size="48" />
            <template v-else>
              <p class="parsed-count">{{ t('energyClassification.readings.parsedRows', { count: csvRows.length }) }}</p>
              <h3>{{ t('energyClassification.readings.mapping') }}</h3>
              <ElTable :data="readingCsvFields.map(field => ({ field }))" class="mapping-table">
                <ElTableColumn :label="t('energyClassification.readings.mapping')" min-width="250"><template #default="{ row }">{{ csvFieldLabel(row.field) }}</template></ElTableColumn>
                <ElTableColumn :label="t('energyClassification.readings.sourceColumn')" min-width="260"><template #default="{ row }"><ElSelect v-model="csvMapping[row.field]" clearable filterable :placeholder="t('energyClassification.placeholders.optional')"><ElOption v-for="header in csvHeaders" :key="header" :value="header" :label="header" /></ElSelect></template></ElTableColumn>
              </ElTable>
              <h3>{{ t('energyClassification.readings.previewRows') }}</h3>
              <ElTable :data="csvRows.slice(0, 20)">
                <ElTableColumn v-for="field in readingCsvFields" :key="field" :prop="csvMapping[field] || field" :label="csvFieldLabel(field)" min-width="160" show-overflow-tooltip />
              </ElTable>
              <ElForm label-position="top" class="import-form">
                <div class="form-grid">
                  <ElFormItem :label="t('energyClassification.fields.reason')"><ElInput v-model="importReason" :placeholder="t('energyClassification.fields.reasonPlaceholder')" /></ElFormItem>
                  <ElFormItem :label="t('energyClassification.fields.idempotencyKey')"><ElInput v-model="importKey" :placeholder="t('energyClassification.placeholders.idempotencyKey')" /></ElFormItem>
                </div>
                <ElSpace wrap>
                  <ElButton :loading="workspace.importLoading.value" @click="previewImport">{{ t('energyClassification.actions.preview') }}</ElButton>
                  <ElButton type="primary" :disabled="!canImport || workspace.importLoading.value" :loading="workspace.importLoading.value" @click="importReadings">{{ t('energyClassification.actions.import') }}</ElButton>
                </ElSpace>
              </ElForm>
            </template>
          </ElCard>

          <ElCard v-if="workspace.readingPreview.value" shadow="never"><template #header><div class="card-heading"><div><h2>{{ t('energyClassification.readings.previewResult') }}</h2></div></div></template><ElDescriptions :column="3" border><ElDescriptionsItem :label="t('energyClassification.readings.batchId')">{{ workspace.readingPreview.value.batchId }}</ElDescriptionsItem><ElDescriptionsItem :label="t('energyClassification.fields.status')"><ElTag :type="statusType(workspace.readingPreview.value.status)">{{ statusText(workspace.readingPreview.value.status) }}</ElTag></ElDescriptionsItem><ElDescriptionsItem :label="t('energyClassification.readings.accepted')">{{ workspace.readingPreview.value.accepted }}</ElDescriptionsItem></ElDescriptions><IssueList :issues="workspace.readingPreview.value.issues" /></ElCard>

          <ElCard v-if="workspace.importedBatch.value" shadow="never"><template #header><div class="card-heading"><div><h2>{{ t('energyClassification.readings.importedBatch') }}</h2></div><ElButton v-if="workspace.importedBatch.value.status === 'PENDING_REVIEW'" type="primary" @click="openReadingBatchReview(workspace.importedBatch.value.batchId)">{{ t('energyClassification.actions.reviewReadingBatch') }}</ElButton></div></template><ElDescriptions :column="3" border><ElDescriptionsItem :label="t('energyClassification.readings.batchId')">{{ workspace.importedBatch.value.batchId }}</ElDescriptionsItem><ElDescriptionsItem :label="t('energyClassification.fields.status')"><ElTag :type="statusType(workspace.importedBatch.value.status)">{{ statusText(workspace.importedBatch.value.status) }}</ElTag></ElDescriptionsItem><ElDescriptionsItem :label="t('energyClassification.readings.accepted')">{{ workspace.importedBatch.value.accepted }}</ElDescriptionsItem></ElDescriptions><IssueList :issues="workspace.importedBatch.value.issues" /></ElCard>

          <ElCard shadow="never"><template #header><div class="card-heading"><div><h2>{{ t('energyClassification.readings.storedReadings') }}</h2></div></div></template><ElForm label-position="top" class="reading-query-form" @submit.prevent="loadReadings"><div class="form-grid"><ElFormItem :label="t('energyClassification.fields.startInclusive')"><input v-model="readingRange.startInclusive" type="datetime-local" class="local-datetime-input" :aria-label="t('energyClassification.fields.startInclusive')"></ElFormItem><ElFormItem :label="t('energyClassification.fields.endExclusive')"><input v-model="readingRange.endExclusive" type="datetime-local" class="local-datetime-input" :aria-label="t('energyClassification.fields.endExclusive')"></ElFormItem></div><ElButton native-type="submit" :loading="workspace.readingsLoading.value">{{ t('energyClassification.actions.loadReadings') }}</ElButton></ElForm><ElAlert v-if="workspace.readingsError.value" :title="workspace.readingsError.value.message" type="error" :closable="false" show-icon /><ElTable v-if="workspace.readings.value.length" :data="workspace.readings.value"><ElTableColumn prop="factId" :label="t('energyClassification.fields.factId')" min-width="160" /><ElTableColumn prop="batchId" :label="t('energyClassification.readings.batchId')" min-width="160" /><ElTableColumn :label="t('energyClassification.fields.batchStatus')" min-width="140"><template #default="{ row }"><ElTag :type="statusType(row.batchStatus)">{{ statusText(row.batchStatus) }}</ElTag></template></ElTableColumn><ElTableColumn :label="t('energyClassification.actions.reviewReadingBatch')" min-width="150" fixed="right"><template #default="{ row }"><ElButton v-if="row.batchStatus === 'PENDING_REVIEW'" :data-test="`reading-batch-review-${row.batchId}`" link @click="openReadingBatchReview(row.batchId)">{{ t('energyClassification.actions.reviewReadingBatch') }}</ElButton></template></ElTableColumn><ElTableColumn :label="t('energyClassification.fields.meterPointId')" min-width="160"><template #default="{ row }">{{ row.reading.pointId }}</template></ElTableColumn><ElTableColumn :label="t('energyClassification.fields.semantics')" min-width="130"><template #default="{ row }">{{ t(`energyClassification.semantics.${row.reading.semantics}`) }}</template></ElTableColumn><ElTableColumn :label="t('energyClassification.fields.supersedesFactId')" min-width="180"><template #default="{ row }">{{ display(row.reading.supersedesFactId) }}</template></ElTableColumn><ElTableColumn :label="t('energyClassification.readings.normalizedQuantity')" min-width="160"><template #default="{ row }">{{ display(row.normalizedQuantity) }} {{ row.reading.unit }}</template></ElTableColumn><ElTableColumn :label="t('energyClassification.fields.status')" min-width="130"><template #default="{ row }"><ElTag :type="statusType(row.reading.status)">{{ statusText(row.reading.status) }}</ElTag></template></ElTableColumn></ElTable><ElEmpty v-else-if="!workspace.readingsLoading.value" :description="t('energyClassification.readings.readingEmpty')" :image-size="48" /></ElCard>
        </section>
        <ReadingBatchReviewPanel :building-id="workspace.buildingId.value" :batch-id="readingReviewBatchId" @approved="handleReadingBatchApproved" @close="readingReviewBatchId = ''" />
      </ElTabPane>
    </ElTabs>
  </main>
</template>

<style scoped>
.classification-page, .tab-content { display: grid; gap: var(--bec-space-section); min-width: 0; }
.page-heading, .card-heading, .version-controls, .file-row { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
.page-heading h1, .page-heading p, .card-heading h2, .card-heading p, h3, .parsed-count { margin: 0; }
.page-heading h1 { font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); }
.card-heading h2, h3 { font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
.page-heading p, .card-heading p, .parsed-count { color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }
.building-form, .query-form, .configuration-form, .import-form, .reading-query-form { display: grid; gap: var(--bec-space-group); }
.building-form { grid-template-columns: minmax(min(100%, calc(var(--bec-ref-space-64) * 4)), 1fr) auto; align-items: end; }
.building-form :deep(.el-form-item), .configuration-form :deep(.el-form-item), .query-form :deep(.el-form-item), .import-form :deep(.el-form-item), .reading-query-form :deep(.el-form-item) { margin-bottom: 0; min-width: 0; }
.form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 3)), 1fr)); gap: var(--bec-space-group); }
.version-selector { min-width: min(100%, calc(var(--bec-ref-space-64) * 4)); margin-bottom: 0; }
.version-details, .configuration-form, .editor-card, .mapping-table, .import-form, .reading-query-form { margin-top: var(--bec-space-section); }
.version-actions { margin-top: var(--bec-space-group); }
.summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 3)), 1fr)); gap: var(--bec-space-group); }
.summary-card { display: grid; gap: var(--bec-space-tight); padding: var(--bec-space-group); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
.summary-card span { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.summary-card strong { font-family: var(--bec-font-family-number); font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); color: var(--bec-color-text-primary); overflow-wrap: anywhere; }
.file-row { align-items: center; margin-top: var(--bec-space-section); }
.file-label { font-weight: var(--bec-font-weight-heading); color: var(--bec-color-text-primary); }
.csv-file-input { max-width: 100%; color: var(--bec-color-text-secondary); }
.configuration-progress { display: flex; flex-wrap: wrap; gap: var(--bec-space-group); margin-block: var(--bec-space-group); color: var(--bec-color-text-secondary); }
.local-datetime-input { width: 100%; min-width: 0; min-height: var(--bec-ref-space-40); padding-inline: var(--bec-space-group); color: var(--bec-color-text-primary); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-control); font: inherit; }
.local-datetime-input:focus-visible { outline: var(--bec-focus-width) solid var(--bec-color-action-primary); outline-offset: var(--bec-focus-offset); }
.tree-preview-list { display: grid; gap: var(--bec-space-group); }
.tree-preview-section { display: grid; gap: var(--bec-space-tight); padding: var(--bec-space-group); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
</style>
