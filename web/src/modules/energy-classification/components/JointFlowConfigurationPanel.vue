<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import {
  ElAlert, ElButton, ElCard, ElDescriptions, ElDescriptionsItem, ElEmpty, ElForm, ElFormItem, ElInput,
  ElInputNumber, ElOption, ElSelect, ElSkeleton, ElSpace, ElTable, ElTableColumn, ElTag,
} from '@/shared/ui'
import ChartView from '@/shared/charts/ChartView.vue'
import type { ChartOption } from '@/shared/charts/echarts'
import { parameterPackagesApi, type ParameterPackageCatalog } from '@/modules/energy-reporting/public'
import { previewJointFlow, type JointFlowGraph, type JointFlowRequest, type JointFlowView } from '../api/joint-flow'
import { jointFlowConfigurationApi } from '../api/joint-flow-configurations'
import { useRelationGovernanceOptions } from '../composables/use-relation-governance-options'
import {
  cloneJointFlowConfiguration, emptyJointFlowConfiguration, validateJointFlowConfiguration,
  type JointFlowConfiguration, type JointFlowConfigurationVersion, type JointFlowDimension, type JointFlowDimensionKey,
  type JointFlowConfigurationIssue, type JointFlowDimensionNode, type JointFlowSelection,
} from '../models/joint-flow-configuration'
import type { ClassificationBinding } from '../models/classification'
import copy from '../locales/joint-flow-configuration.zh-CN'

const props = defineProps<{ buildingId: string }>()
const emit = defineEmits<{ 'dirty-change': [dirty: boolean] }>()

interface RequestScope {
  buildingId: string
  generation: number
}

const versions = ref<JointFlowConfigurationVersion[]>([])
const selected = ref<JointFlowConfigurationVersion | null>(null)
const selection = ref<JointFlowSelection | null>(null)
const selectedVersionId = ref('')
const selectionVersionId = ref('')
const copiedFromVersionId = ref<string | null>(null)
const configuration = ref<JointFlowConfiguration>(emptyJointFlowConfiguration())
const savedConfiguration = ref('')
const relationVersionIdsJson = ref('[]')
const mappingsJson = ref('[]')
const conversionsJson = ref('[]')
const floorReferencesJson = ref('[]')
const nodesJson = ref<Record<number, string>>({})
const actionReason = ref('')
const localError = ref('')
const loading = ref(false)
const saving = ref(false)
const previewLoading = ref(false)
const preview = ref<JointFlowView | null>(null)
const previewForm = ref<{ periodType: 'MONTH' | 'YEAR'; period: string; throughMonth: string }>({ periodType: 'MONTH', period: '', throughMonth: '' })
const relationWorkspace = useRelationGovernanceOptions()
const parameterCatalog = ref<ParameterPackageCatalog | null>(null)
const parameterLoading = ref(false)
let requestGeneration = 0
let requestAbort: AbortController | undefined
let versionLoadGeneration = 0
let previewGeneration = 0
let previewAbort: AbortController | undefined
let parameterGeneration = 0
let parameterAbort: AbortController | undefined

const selectedIsDraft = computed(() => selected.value?.status === 'DRAFT')
const structureIssues = computed(() => validateJointFlowConfiguration(configuration.value))
const dirty = computed(() => selectedIsDraft.value && JSON.stringify(configuration.value) !== savedConfiguration.value)
const approvedVersions = computed(() => versions.value.filter(version => version.status === 'APPROVED'))
const relationVersionOptions = computed(() => relationWorkspace.formalVersions.value)
const parameterVersionOptions = computed(() => (parameterCatalog.value?.existingVersions ?? []).filter(version => version.domain === 'CONVERSION_PARAMETER' && version.status === 'APPROVED'))
const mappingBindingRows = computed(() => configuration.value.mappings.flatMap((mapping, mappingIndex) => mapping.bindings.map((binding, bindingIndex) => ({
  mappingIndex, bindingIndex, relationVersionId: mapping.relationVersionId ?? '', binding,
}))))
const graphRows = computed(() => preview.value ? [
  { key: 'carbon', title: copy.carbon, graph: preview.value.carbon, option: previewGraphOption(preview.value.carbon, preview.value.dimensions) },
  { key: 'energy', title: copy.energy, graph: preview.value.energy, option: previewGraphOption(preview.value.energy, preview.value.dimensions) },
] : [])

watch(dirty, value => emit('dirty-change', value), { immediate: true })
watch(() => props.buildingId, () => { void loadContext() }, { immediate: true })
watch([
  () => props.buildingId,
  () => selected.value?.versionId,
  () => selected.value?.revision,
  () => previewForm.value.periodType,
  () => previewForm.value.period,
  () => previewForm.value.throughMonth,
  dirty,
], invalidatePreview)
watch(() => previewForm.value.periodType, type => {
  if (type === 'MONTH') previewForm.value.throughMonth = ''
})
onScopeDispose(() => { requestAbort?.abort(); previewAbort?.abort(); parameterAbort?.abort() })

function statusText(status: string): string {
  return copy.statuses[status as keyof typeof copy.statuses] ?? status
}

function errorMessage(error: unknown): string {
  return error instanceof Error && error.message ? error.message : String(error || copy.errors.json)
}

function issueMessage(issue: JointFlowConfigurationIssue): string {
  return copy.validation[issue.code]
}

function ensureReason(): string | null {
  const reason = actionReason.value.trim()
  if (!reason) localError.value = copy.errors.reason
  return reason || null
}

function ensureBuilding(): string | null {
  const buildingId = props.buildingId.trim()
  if (!buildingId) localError.value = copy.errors.building
  return buildingId || null
}

function isCurrentScope(scope: RequestScope): boolean {
  return props.buildingId.trim() === scope.buildingId && requestGeneration === scope.generation
}

function versionMatchesScope(version: JointFlowConfigurationVersion, scope: RequestScope, expectedVersionId?: string): boolean {
  return version.buildingId === scope.buildingId && Boolean(version.versionId)
    && (!expectedVersionId || version.versionId === expectedVersionId)
}

function selectionMatchesScope(value: JointFlowSelection, scope: RequestScope, expectedVersionId: string): boolean {
  return value.buildingId === scope.buildingId && value.versionId === expectedVersionId
}

function syncJsonEditors(): void {
  relationVersionIdsJson.value = JSON.stringify(configuration.value.relationVersionIds, null, 2)
  mappingsJson.value = JSON.stringify(configuration.value.mappings, null, 2)
  conversionsJson.value = JSON.stringify(configuration.value.conversions, null, 2)
  floorReferencesJson.value = JSON.stringify(configuration.value.floorReferences, null, 2)
  nodesJson.value = Object.fromEntries(configuration.value.dimensions.map((dimension, index) => [index, JSON.stringify(dimension.nodes, null, 2)]))
}

async function loadParameterOptions(buildingId: string): Promise<void> {
  const current = ++parameterGeneration
  parameterAbort?.abort()
  parameterCatalog.value = null
  if (!buildingId) return
  parameterAbort = new AbortController()
  parameterLoading.value = true
  try {
    const result = await parameterPackagesApi.catalog(buildingId, parameterAbort.signal)
    if (current === parameterGeneration && !parameterAbort.signal.aborted) parameterCatalog.value = result
  } catch {
    // 参数目录只提供选择辅助；配置保存仍由联合流后端核验，目录暂不可读不阻断既有草稿查看。
  } finally {
    if (current === parameterGeneration) parameterLoading.value = false
  }
}

function parameterVersionLabel(option: { domain: string; code: string; versionId: string }): string {
  return `${option.domain} · ${option.code} · ${option.versionId}`
}

function mappingSchemeOptions(mappingIndex: number) {
  return configuration.value.mappings[mappingIndex]?.schemes ?? []
}

function mappingNodeOptions(mappingIndex: number, schemeCode: string) {
  return (configuration.value.mappings[mappingIndex]?.nodes ?? []).filter(node => node.schemeCode === schemeCode)
}

function applyVersion(version: JointFlowConfigurationVersion): void {
  invalidatePreview()
  selected.value = version
  selectedVersionId.value = version.versionId
  configuration.value = cloneJointFlowConfiguration(version.configuration)
  savedConfiguration.value = JSON.stringify(configuration.value)
  syncJsonEditors()
  preview.value = null
}

function replaceVersion(version: JointFlowConfigurationVersion): void {
  const index = versions.value.findIndex(item => item.versionId === version.versionId)
  if (index >= 0) versions.value.splice(index, 1, version)
  else versions.value.push(version)
  applyVersion(version)
}

async function loadContext(): Promise<void> {
  const buildingId = props.buildingId.trim()
  const scope = { buildingId, generation: ++requestGeneration }
  versionLoadGeneration++
  requestAbort?.abort()
  invalidatePreview()
  saving.value = false
  versions.value = []
  selected.value = null
  selectedVersionId.value = ''
  selection.value = null
  selectionVersionId.value = ''
  configuration.value = emptyJointFlowConfiguration()
  savedConfiguration.value = JSON.stringify(configuration.value)
  syncJsonEditors()
  localError.value = ''
  if (!buildingId) return
  void relationWorkspace.loadBuilding(buildingId)
  void loadParameterOptions(buildingId)
  const controller = new AbortController()
  requestAbort = controller
  loading.value = true
  try {
    const [list, currentSelection] = await Promise.all([
      jointFlowConfigurationApi.list(buildingId, controller.signal),
      jointFlowConfigurationApi.getSelection(buildingId, controller.signal),
    ])
    if (!isCurrentScope(scope) || controller.signal.aborted) return
    if (list.some(version => !versionMatchesScope(version, scope)) || currentSelection.buildingId !== scope.buildingId) throw new Error(copy.errors.scope)
    versions.value = list
    selection.value = currentSelection
    selectionVersionId.value = currentSelection.versionId ?? ''
    const preferred = currentSelection.versionId ?? list.find(version => version.status === 'DRAFT')?.versionId ?? list[0]?.versionId
    if (preferred) await loadVersion(preferred, scope, controller.signal)
  } catch (error) {
    if (isCurrentScope(scope) && !controller.signal.aborted) localError.value = errorMessage(error)
  } finally {
    if (isCurrentScope(scope)) loading.value = false
  }
}

async function loadVersion(versionId: string, scope: RequestScope, signal = requestAbort?.signal): Promise<void> {
  const sequence = ++versionLoadGeneration
  if (!scope.buildingId || !versionId) return
  try {
    const version = await jointFlowConfigurationApi.get(scope.buildingId, versionId, signal)
    if (!isCurrentScope(scope) || signal?.aborted || sequence !== versionLoadGeneration) return
    if (!versionMatchesScope(version, scope, versionId)) throw new Error(copy.errors.scope)
    replaceVersion(version)
  } catch (error) {
    if (isCurrentScope(scope) && !signal?.aborted && sequence === versionLoadGeneration) localError.value = errorMessage(error)
  }
}

function chooseVersion(versionId: string): void {
  if (dirty.value) {
    localError.value = copy.errors.unsaved
    return
  }
  localError.value = ''
  const buildingId = ensureBuilding()
  if (buildingId) void loadVersion(versionId, { buildingId, generation: requestGeneration })
}

async function createVersion(): Promise<void> {
  const buildingId = ensureBuilding()
  const reason = ensureReason()
  if (!buildingId || !reason || saving.value) return
  const scope = { buildingId, generation: requestGeneration }
  saving.value = true
  localError.value = ''
  try {
    const version = await jointFlowConfigurationApi.create(buildingId, { reason, copiedFromVersionId: copiedFromVersionId.value })
    if (!isCurrentScope(scope)) return
    if (!versionMatchesScope(version, scope)) throw new Error(copy.errors.scope)
    replaceVersion(version)
  } catch (error) {
    if (isCurrentScope(scope)) localError.value = errorMessage(error)
  } finally {
    if (isCurrentScope(scope)) saving.value = false
  }
}

async function saveVersion(): Promise<void> {
  const buildingId = ensureBuilding()
  const reason = ensureReason()
  const version = selected.value
  if (!buildingId || !reason || !version || !selectedIsDraft.value || saving.value) return
  if (structureIssues.value.length) {
    localError.value = issueMessage(structureIssues.value[0]!)
    return
  }
  const scope = { buildingId, generation: requestGeneration }
  saving.value = true
  localError.value = ''
  try {
    const updated = await jointFlowConfigurationApi.save(buildingId, version.versionId, {
      expectedRevision: version.revision, configuration: cloneJointFlowConfiguration(configuration.value), reason,
    })
    if (!isCurrentScope(scope)) return
    if (!versionMatchesScope(updated, scope, version.versionId)) throw new Error(copy.errors.scope)
    replaceVersion(updated)
  } catch (error) {
    if (isCurrentScope(scope)) localError.value = errorMessage(error)
  } finally {
    if (isCurrentScope(scope)) saving.value = false
  }
}

async function transition(action: 'submit' | 'approve' | 'reject'): Promise<void> {
  const buildingId = ensureBuilding()
  const reason = ensureReason()
  const version = selected.value
  if (!buildingId || !reason || !version || dirty.value || saving.value) {
    if (version && dirty.value) localError.value = copy.errors.unsaved
    return
  }
  const scope = { buildingId, generation: requestGeneration }
  saving.value = true
  localError.value = ''
  try {
    const request = { expectedRevision: version.revision, reason }
    const updated = action === 'submit'
      ? await jointFlowConfigurationApi.submit(buildingId, version.versionId, request)
      : action === 'approve'
        ? await jointFlowConfigurationApi.approve(buildingId, version.versionId, request)
        : await jointFlowConfigurationApi.reject(buildingId, version.versionId, request)
    if (!isCurrentScope(scope)) return
    if (!versionMatchesScope(updated, scope, version.versionId)) throw new Error(copy.errors.scope)
    replaceVersion(updated)
  } catch (error) {
    if (isCurrentScope(scope)) localError.value = errorMessage(error)
  } finally {
    if (isCurrentScope(scope)) saving.value = false
  }
}

async function updateSelection(versionId: string): Promise<void> {
  const buildingId = ensureBuilding()
  const reason = ensureReason()
  const currentSelection = selection.value
  if (!buildingId || !reason || !currentSelection || saving.value) return
  const scope = { buildingId, generation: requestGeneration }
  saving.value = true
  localError.value = ''
  try {
    const updated = await jointFlowConfigurationApi.updateSelection(buildingId, {
      expectedRevision: currentSelection.revision, versionId, reason,
    })
    if (!isCurrentScope(scope)) return
    if (!selectionMatchesScope(updated, scope, versionId)) throw new Error(copy.errors.scope)
    selection.value = updated
    selectionVersionId.value = updated.versionId
  } catch (error) {
    if (isCurrentScope(scope)) localError.value = errorMessage(error)
  } finally {
    if (isCurrentScope(scope)) saving.value = false
  }
}

function applySelection(): void {
  if (selectionVersionId.value) void updateSelection(selectionVersionId.value)
}

function addDimension(): void {
  if (!selectedIsDraft.value || configuration.value.dimensions.length >= 3) return
  const used = new Set(configuration.value.dimensions.map(dimension => dimension.key))
  const key = (['ENERGY_TYPE', 'USAGE', 'FLOOR', 'DEVICE_BREAKDOWN'] as JointFlowDimensionKey[]).find(item => !used.has(item)) ?? 'USAGE'
  const dimension: JointFlowDimension = key === 'ENERGY_TYPE'
    ? { key, title: copy.dimensionsKey[key], source: 'ENERGY_ITEM', nodes: [] }
    : { key, title: copy.dimensionsKey[key], source: 'FROZEN_SCHEME', schemeCode: null, scopeLevel: key === 'FLOOR' ? 'FLOOR' : null, nodes: [] }
  configuration.value.dimensions.push(dimension)
  syncJsonEditors()
}

function addDimensionNode(dimension: JointFlowDimension): void {
  if (!selectedIsDraft.value) return
  const nextOrder = dimension.nodes.length ? Math.max(...dimension.nodes.map(node => Number.isSafeInteger(node.order) ? node.order : 0)) + 1 : 1
  dimension.nodes.push({ code: '', name: '', order: nextOrder })
  syncJsonEditors()
}

function removeDimensionNode(dimension: JointFlowDimension, index: number): void {
  if (!selectedIsDraft.value) return
  dimension.nodes.splice(index, 1)
  syncJsonEditors()
}

function addConversion(): void {
  if (!selectedIsDraft.value) return
  configuration.value.conversions.push({ energyItemCode: '', inputUnitCode: '', parameterVersionId: '' })
  syncJsonEditors()
}

function removeConversion(index: number): void {
  if (!selectedIsDraft.value) return
  configuration.value.conversions.splice(index, 1)
  syncJsonEditors()
}

function addMappingBinding(): void {
  if (!selectedIsDraft.value) return
  const mapping = configuration.value.mappings[0]
  if (!mapping) {
    localError.value = copy.errors.mapping
    return
  }
  const binding: ClassificationBinding = {
    schemeCode: '', nodeCode: '', objectType: 'POINT', objectId: '', meterPointId: null,
    semantics: 'METERING', status: 'PENDING_EXPERT', evidence: null,
  }
  mapping.bindings.push(binding)
  syncJsonEditors()
}

function removeMappingBinding(mappingIndex: number, bindingIndex: number): void {
  if (!selectedIsDraft.value) return
  configuration.value.mappings[mappingIndex]?.bindings.splice(bindingIndex, 1)
  syncJsonEditors()
}

function moveDimension(index: number, movement: -1 | 1): void {
  const target = index + movement
  if (!selectedIsDraft.value || target < 0 || target >= configuration.value.dimensions.length) return
  const [dimension] = configuration.value.dimensions.splice(index, 1)
  if (dimension) configuration.value.dimensions.splice(target, 0, dimension)
  syncJsonEditors()
}

function removeDimension(index: number): void {
  if (!selectedIsDraft.value) return
  configuration.value.dimensions.splice(index, 1)
  syncJsonEditors()
}

function changeDimensionKey(dimension: JointFlowDimension, key: JointFlowDimensionKey): void {
  dimension.key = key
  if (key === 'ENERGY_TYPE') {
    dimension.source = 'ENERGY_ITEM'
    dimension.schemeCode = null
    dimension.scopeLevel = null
  } else {
    if (dimension.source === 'ENERGY_ITEM') dimension.source = 'FROZEN_SCHEME'
    dimension.scopeLevel = key === 'FLOOR' ? 'FLOOR' : null
  }
}

function setNodesJson(index: number, value: string): void {
  nodesJson.value = { ...nodesJson.value, [index]: value }
}

function applyDimensionNodes(index: number): void {
  const raw = nodesJson.value[index] ?? '[]'
  try {
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) throw new Error(copy.errors.array)
    configuration.value.dimensions[index]!.nodes = parsed as JointFlowDimensionNode[]
    nodesJson.value = { ...nodesJson.value, [index]: JSON.stringify(parsed, null, 2) }
    localError.value = ''
  } catch (error) {
    localError.value = error instanceof Error && error.message === copy.errors.array ? error.message : copy.errors.json
  }
}

function applyArrayEditor(target: 'relationVersionIds' | 'mappings' | 'conversions' | 'floorReferences'): void {
  const source = target === 'relationVersionIds' ? relationVersionIdsJson.value : target === 'mappings' ? mappingsJson.value : target === 'conversions' ? conversionsJson.value : floorReferencesJson.value
  try {
    const parsed = JSON.parse(source) as unknown
    if (!Array.isArray(parsed)) throw new Error(copy.errors.array)
    if (target === 'relationVersionIds') configuration.value.relationVersionIds = parsed as string[]
    else if (target === 'mappings') configuration.value.mappings = parsed as JointFlowConfiguration['mappings']
    else if (target === 'conversions') configuration.value.conversions = parsed as JointFlowConfiguration['conversions']
    else configuration.value.floorReferences = parsed as JointFlowConfiguration['floorReferences']
    syncJsonEditors()
    localError.value = ''
  } catch (error) {
    localError.value = error instanceof Error && error.message === copy.errors.array ? error.message : copy.errors.json
  }
}

async function importArrayFile(event: Event, target: 'relationVersionIds' | 'mappings' | 'conversions' | 'floorReferences'): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  const text = await file.text()
  if (target === 'relationVersionIds') relationVersionIdsJson.value = text
  else if (target === 'mappings') mappingsJson.value = text
  else if (target === 'conversions') conversionsJson.value = text
  else floorReferencesJson.value = text
  applyArrayEditor(target)
  input.value = ''
}

async function importNodesFile(event: Event, index: number): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  setNodesJson(index, await file.text())
  applyDimensionNodes(index)
  input.value = ''
}

function chartValue(value: string | null): number | null {
  if (typeof value !== 'string' || !/^-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(value)) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null
}

function safeChartText(value: unknown): string {
  return typeof value === 'string'
    ? value.replace(/[\r\n{}|]/g, character => ({ '\r': ' ', '\n': ' ', '{': '（', '}': '）', '|': '｜' })[character] ?? character)
    : '—'
}

/** 预览图仅把后端节点与连线适配给 ECharts；不补节点、不重排归属，也不从连线计算任何业务数值。 */
function previewGraphOption(graph: JointFlowGraph, dimensions: JointFlowView['dimensions']): ChartOption | undefined {
  if (!['READY', 'PARTIAL'].includes(graph.state) || !graph.nodes.length || !graph.links.length) return undefined
  const nodes = new Map(graph.nodes.map(node => [node.id, node]))
  if (nodes.size !== graph.nodes.length) return undefined
  const renderedNodes = graph.nodes.map(node => ({ ...node, renderedValue: chartValue(node.value) }))
  if (renderedNodes.some(node => node.renderedValue === null || !Number.isSafeInteger(node.depth) || !Number.isSafeInteger(node.order))) return undefined
  const links = graph.links.map(link => ({ ...link, renderedValue: chartValue(link.value) }))
  if (links.some(link => link.renderedValue === null || !nodes.has(link.source) || !nodes.has(link.target))) return undefined
  const orderedNodes = [...renderedNodes].sort((left, right) => left.depth - right.depth || left.order - right.order || left.id.localeCompare(right.id))
  const names = new Map(orderedNodes.map(node => [node.id, node.name]))
  return {
    animation: false,
    tooltip: {
      trigger: 'item', appendToBody: false, renderMode: 'richText',
      formatter: (params: unknown) => {
        const item = params as { dataType?: string; data?: { source?: string; target?: string; name?: string; value?: number } }
        if (item.dataType === 'edge') return `${safeChartText(names.get(item.data?.source ?? ''))} → ${safeChartText(names.get(item.data?.target ?? ''))}\n${item.data?.value ?? '—'} ${safeChartText(graph.unit)}`
        const node = orderedNodes.find(value => value.id === item.data?.name)
        return node ? `${safeChartText(node.name)}\n${node.renderedValue} ${safeChartText(graph.unit)}` : ''
      },
    },
    series: [{
      type: 'sankey', left: 12, right: 12, top: 18, bottom: 12, nodeWidth: 10, nodeGap: 16, draggable: false, layoutIterations: 0,
      data: orderedNodes.map(node => ({ name: node.id, value: node.renderedValue ?? undefined, depth: node.depth, label: { formatter: safeChartText(node.name) } })),
      links: links.map(link => ({ source: link.source, target: link.target, value: link.renderedValue })),
      lineStyle: { opacity: 0.5, curveness: 0.5 }, emphasis: { focus: 'adjacency' },
    }],
    aria: { enabled: true, description: dimensions.map(dimension => dimension.title).join(' → ') },
  }
}

function previewRequest(): JointFlowRequest | null {
  const buildingId = ensureBuilding()
  const version = selected.value
  const form = previewForm.value
  if (!buildingId || !version || version.status !== 'DRAFT') {
    localError.value = copy.errors.draft
    return null
  }
  if (dirty.value) {
    localError.value = copy.errors.unsaved
    return null
  }
  const month = /^\d{4}-(0[1-9]|1[0-2])$/
  const year = /^\d{4}$/
  const valid = form.periodType === 'MONTH'
    ? month.test(form.period) && !form.throughMonth
    : year.test(form.period) && month.test(form.throughMonth) && form.throughMonth.startsWith(`${form.period}-`)
  if (!valid) {
    localError.value = copy.errors.period
    return null
  }
  return {
    buildingId, periodType: form.periodType, period: form.period,
    ...(form.periodType === 'YEAR' ? { throughMonth: form.throughMonth } : {}),
    configurationVersionId: version.versionId,
  }
}

function invalidatePreview(): void {
  previewGeneration++
  previewAbort?.abort()
  previewAbort = undefined
  previewLoading.value = false
  preview.value = null
}

function isCurrentPreviewRequest(request: JointFlowRequest, revision: number | undefined): boolean {
  const version = selected.value
  const form = previewForm.value
  return !dirty.value
    && props.buildingId.trim() === request.buildingId
    && version?.status === 'DRAFT'
    && version.versionId === request.configurationVersionId
    && version.revision === revision
    && form.periodType === request.periodType
    && form.period === request.period
    && (form.periodType === 'YEAR' ? form.throughMonth === request.throughMonth : !form.throughMonth && request.throughMonth === undefined)
}

async function runPreview(): Promise<void> {
  const request = previewRequest()
  if (!request || previewLoading.value) return
  const revision = selected.value?.revision
  invalidatePreview()
  const current = previewGeneration
  previewAbort = new AbortController()
  previewLoading.value = true
  localError.value = ''
  try {
    const result = await previewJointFlow(request, previewAbort.signal)
    if (current !== previewGeneration || previewAbort.signal.aborted) return
    if (result.classificationBasis !== 'DRAFT_PREVIEW' || result.configurationVersionId !== request.configurationVersionId || result.buildingId !== request.buildingId
      || result.periodType !== request.periodType || result.period !== request.period || (result.throughMonth ?? undefined) !== request.throughMonth
      || !isCurrentPreviewRequest(request, revision)) {
      throw new Error(copy.errors.preview)
    }
    preview.value = result
  } catch (error) {
    if (current === previewGeneration && !previewAbort.signal.aborted) localError.value = errorMessage(error)
  } finally {
    if (current === previewGeneration) previewLoading.value = false
  }
}
</script>

<template>
  <ElCard shadow="never" class="joint-flow-configuration-panel">
    <template #header>
      <div class="panel-heading"><div><h2>{{ copy.title }}</h2><p>{{ copy.description }}</p></div><ElButton :disabled="loading || dirty" @click="loadContext">{{ copy.refresh }}</ElButton></div>
    </template>

    <ElAlert :title="copy.description" type="info" :closable="false" show-icon />
    <ElAlert v-if="localError" :title="localError" type="error" :closable="false" show-icon />
    <ElSkeleton v-if="loading" animated :rows="4" />
    <template v-else>
      <ElForm label-position="top" class="panel-form">
        <div class="form-grid">
          <ElFormItem :label="copy.selectVersion"><ElSelect :model-value="selectedVersionId" clearable filterable :disabled="saving" data-test="joint-flow-version" @update:model-value="chooseVersion"><ElOption v-for="version in versions" :key="version.versionId" :value="version.versionId" :label="`${copy.version} ${version.versionNo} · ${statusText(version.status)}`" /></ElSelect></ElFormItem>
          <ElFormItem :label="copy.copySource"><ElSelect v-model="copiedFromVersionId" clearable filterable :disabled="saving"><ElOption v-for="version in versions" :key="version.versionId" :value="version.versionId" :label="`${copy.version} ${version.versionNo} · ${statusText(version.status)}`" /></ElSelect></ElFormItem>
          <ElFormItem :label="copy.reason"><ElInput v-model="actionReason" :placeholder="copy.reasonPlaceholder" :disabled="saving" data-test="joint-flow-reason" /></ElFormItem>
        </div>
        <ElButton :disabled="!buildingId || saving" :loading="saving" data-test="joint-flow-create" @click="createVersion">{{ copy.create }}</ElButton>
      </ElForm>

      <ElEmpty v-if="buildingId && !versions.length" :description="copy.noSelection" :image-size="48" />
      <template v-if="selected">
        <ElDescriptions :column="3" border class="version-details">
          <ElDescriptionsItem :label="copy.version">{{ selected.versionNo }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="copy.revision">{{ selected.revision }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="copy.status"><ElTag>{{ statusText(selected.status) }}</ElTag></ElDescriptionsItem>
          <ElDescriptionsItem :label="copy.hash" :span="3">{{ selected.configurationHash || '—' }}</ElDescriptionsItem>
        </ElDescriptions>
        <ElAlert v-if="!selectedIsDraft" :title="copy.draftOnly" type="warning" :closable="false" show-icon />

        <section class="editor-section">
          <div class="panel-heading"><div><h3>{{ copy.dimensions }}</h3><p>{{ copy.deviceHint }}</p></div><ElButton :disabled="!selectedIsDraft || configuration.dimensions.length >= 3" @click="addDimension">{{ copy.addDimension }}</ElButton></div>
          <ElCard v-for="(dimension, index) in configuration.dimensions" :key="`${dimension.key}-${index}`" shadow="never" class="dimension-card">
            <template #header><div class="dimension-heading"><strong>{{ `${index + 1}. ${dimension.title || copy.dimensionsKey[dimension.key]}` }}</strong><ElSpace><ElButton link :disabled="!selectedIsDraft || index === 0" @click="moveDimension(index, -1)">{{ copy.moveUp }}</ElButton><ElButton link :disabled="!selectedIsDraft || index === configuration.dimensions.length - 1" @click="moveDimension(index, 1)">{{ copy.moveDown }}</ElButton><ElButton link type="danger" :disabled="!selectedIsDraft" @click="removeDimension(index)">{{ copy.remove }}</ElButton></ElSpace></div></template>
            <div class="form-grid">
              <ElFormItem :label="copy.key"><ElSelect :model-value="dimension.key" :disabled="!selectedIsDraft" @update:model-value="changeDimensionKey(dimension, $event as JointFlowDimensionKey)"><ElOption v-for="(label, key) in copy.dimensionsKey" :key="key" :value="key" :label="label" /></ElSelect></ElFormItem>
              <ElFormItem :label="copy.titleField"><ElInput v-model="dimension.title" :disabled="!selectedIsDraft" /></ElFormItem>
              <ElFormItem :label="copy.source"><ElSelect v-model="dimension.source" :disabled="!selectedIsDraft || dimension.key === 'ENERGY_TYPE'"><ElOption v-for="(label, source) in copy.dimensionSource" :key="source" :value="source" :label="label" /></ElSelect></ElFormItem>
              <ElFormItem v-if="dimension.key !== 'ENERGY_TYPE'" :label="copy.schemeCode"><ElInput v-model="dimension.schemeCode" :disabled="!selectedIsDraft" /></ElFormItem>
              <ElFormItem v-if="dimension.key === 'FLOOR'" :label="copy.scopeLevel"><ElInput :model-value="dimension.scopeLevel" disabled /></ElFormItem>
            </div>
            <section class="node-editor"><div class="panel-heading"><div><h4>{{ copy.nodeRows }}</h4></div><ElButton :disabled="!selectedIsDraft" @click="addDimensionNode(dimension)">{{ copy.addNode }}</ElButton></div><ElTable v-if="dimension.nodes.length" :data="dimension.nodes"><ElTableColumn :label="copy.nodeCode" min-width="150"><template #default="{ row }"><ElInput v-model="row.code" :disabled="!selectedIsDraft" @change="syncJsonEditors" /></template></ElTableColumn><ElTableColumn :label="copy.nodeName" min-width="180"><template #default="{ row }"><ElInput v-model="row.name" :disabled="!selectedIsDraft" @change="syncJsonEditors" /></template></ElTableColumn><ElTableColumn :label="copy.nodeOrder" min-width="140"><template #default="{ row }"><ElInputNumber v-model="row.order" :min="0" :step="1" :precision="0" :disabled="!selectedIsDraft" @change="syncJsonEditors" /></template></ElTableColumn><ElTableColumn :label="copy.remove" width="90"><template #default="{ $index }"><ElButton link type="danger" :disabled="!selectedIsDraft" @click="removeDimensionNode(dimension, $index)">{{ copy.remove }}</ElButton></template></ElTableColumn></ElTable><ElEmpty v-else :description="copy.nodeRows" :image-size="32" /></section>
            <ElFormItem :label="copy.nodeMapping"><ElInput :model-value="nodesJson[index]" type="textarea" :rows="5" :disabled="!selectedIsDraft" @update:model-value="setNodesJson(index, $event)" /><p class="form-hint">{{ copy.nodeHint }}</p><ElSpace><ElButton :disabled="!selectedIsDraft" @click="applyDimensionNodes(index)">{{ copy.applyJson }}</ElButton><label class="json-import"><span>{{ copy.importJson }}</span><input type="file" accept="application/json,.json" :disabled="!selectedIsDraft" @change="importNodesFile($event, index)"></label></ElSpace></ElFormItem>
          </ElCard>
          <ElEmpty v-if="!configuration.dimensions.length" :description="copy.dimensions" :image-size="40" />
        </section>

        <section class="editor-section">
          <div class="panel-heading"><div><h3>{{ copy.relationVersions }}</h3><p>{{ copy.selectionHint }}</p></div></div>
          <ElSelect v-model="configuration.relationVersionIds" multiple filterable :disabled="!selectedIsDraft" :loading="relationWorkspace.loading.value" @change="syncJsonEditors"><ElOption v-for="version in relationVersionOptions" :key="version.versionId" :value="version.versionId" :label="`${version.versionNo} · ${version.effectiveAt ?? ''}`" /></ElSelect>
        </section>
        <section class="editor-section">
          <div class="panel-heading"><div><h3>{{ copy.relationVersionIds }}</h3><p>{{ copy.jsonHint }}</p></div></div>
          <ElInput v-model="relationVersionIdsJson" type="textarea" :rows="3" :disabled="!selectedIsDraft" />
          <ElSpace><ElButton :disabled="!selectedIsDraft" @click="applyArrayEditor('relationVersionIds')">{{ copy.applyJson }}</ElButton><label class="json-import"><span>{{ copy.importJson }}</span><input type="file" accept="application/json,.json" :disabled="!selectedIsDraft" @change="importArrayFile($event, 'relationVersionIds')"></label></ElSpace>
        </section>
        <section class="editor-section">
          <div class="panel-heading"><div><h3>{{ copy.mappings }}</h3><p>{{ copy.jsonHint }}</p></div></div>
          <ElInput v-model="mappingsJson" type="textarea" :rows="6" :disabled="!selectedIsDraft" />
          <ElSpace><ElButton :disabled="!selectedIsDraft" @click="applyArrayEditor('mappings')">{{ copy.applyJson }}</ElButton><label class="json-import"><span>{{ copy.importJson }}</span><input type="file" accept="application/json,.json" :disabled="!selectedIsDraft" @change="importArrayFile($event, 'mappings')"></label></ElSpace>
        </section>
        <section class="editor-section">
          <div class="panel-heading"><div><h3>{{ copy.mappingBindings }}</h3><p>{{ copy.mappingBindingsHint }}</p></div><ElButton :disabled="!selectedIsDraft" @click="addMappingBinding">{{ copy.addBinding }}</ElButton></div>
          <ElTable v-if="mappingBindingRows.length" :data="mappingBindingRows"><ElTableColumn prop="relationVersionId" :label="copy.bindingRelation" min-width="150" /><ElTableColumn :label="copy.bindingScheme" min-width="150"><template #default="{ row }"><ElSelect v-model="row.binding.schemeCode" :disabled="!selectedIsDraft" @change="syncJsonEditors"><ElOption v-for="scheme in mappingSchemeOptions(row.mappingIndex)" :key="scheme.code" :value="scheme.code" :label="scheme.name ? `${scheme.code} · ${scheme.name}` : scheme.code" /></ElSelect></template></ElTableColumn><ElTableColumn :label="copy.bindingNode" min-width="150"><template #default="{ row }"><ElSelect v-model="row.binding.nodeCode" :disabled="!selectedIsDraft" @change="syncJsonEditors"><ElOption v-for="node in mappingNodeOptions(row.mappingIndex, row.binding.schemeCode)" :key="node.code" :value="node.code" :label="node.name ? `${node.code} · ${node.name}` : node.code" /></ElSelect></template></ElTableColumn><ElTableColumn :label="copy.bindingObject" min-width="180"><template #default="{ row }"><ElInput v-model="row.binding.objectId" :disabled="!selectedIsDraft" @change="syncJsonEditors" /></template></ElTableColumn><ElTableColumn :label="copy.bindingMeter" min-width="180"><template #default="{ row }"><ElInput v-model="row.binding.meterPointId" :disabled="!selectedIsDraft" @change="syncJsonEditors" /></template></ElTableColumn><ElTableColumn :label="copy.bindingSemantics" min-width="130"><template #default="{ row }">{{ row.binding.semantics }}</template></ElTableColumn><ElTableColumn :label="copy.remove" width="90"><template #default="{ row }"><ElButton link type="danger" :disabled="!selectedIsDraft" @click="removeMappingBinding(row.mappingIndex, row.bindingIndex)">{{ copy.remove }}</ElButton></template></ElTableColumn></ElTable>
          <ElEmpty v-else :description="copy.mappingBindings" :image-size="36" />
        </section>
        <section class="editor-section">
          <div class="panel-heading"><div><h3>{{ copy.conversionRows }}</h3><p>{{ copy.conversionRowsHint }}</p></div><ElButton :disabled="!selectedIsDraft" @click="addConversion">{{ copy.addConversion }}</ElButton></div>
          <ElTable v-if="configuration.conversions.length" :data="configuration.conversions"><ElTableColumn :label="copy.conversionEnergy" min-width="150"><template #default="{ row }"><ElInput v-model="row.energyItemCode" :disabled="!selectedIsDraft" @change="syncJsonEditors" /></template></ElTableColumn><ElTableColumn :label="copy.conversionUnit" min-width="140"><template #default="{ row }"><ElInput v-model="row.inputUnitCode" :disabled="!selectedIsDraft" @change="syncJsonEditors" /></template></ElTableColumn><ElTableColumn :label="copy.conversionVersion" min-width="260"><template #default="{ row }"><ElSelect v-model="row.parameterVersionId" filterable :disabled="!selectedIsDraft" :loading="parameterLoading" @change="syncJsonEditors"><ElOption v-for="option in parameterVersionOptions" :key="option.versionId" :value="option.versionId" :label="parameterVersionLabel(option)" /></ElSelect></template></ElTableColumn><ElTableColumn :label="copy.remove" width="90"><template #default="{ $index }"><ElButton link type="danger" :disabled="!selectedIsDraft" @click="removeConversion($index)">{{ copy.remove }}</ElButton></template></ElTableColumn></ElTable>
          <ElEmpty v-else :description="copy.conversionRows" :image-size="36" />
        </section>
        <section class="editor-section">
          <div class="panel-heading"><div><h3>{{ copy.conversions }}</h3><p>{{ copy.jsonHint }}</p></div></div>
          <ElInput v-model="conversionsJson" type="textarea" :rows="4" :disabled="!selectedIsDraft" />
          <ElSpace><ElButton :disabled="!selectedIsDraft" @click="applyArrayEditor('conversions')">{{ copy.applyJson }}</ElButton><label class="json-import"><span>{{ copy.importJson }}</span><input type="file" accept="application/json,.json" :disabled="!selectedIsDraft" @change="importArrayFile($event, 'conversions')"></label></ElSpace>
        </section>
        <section class="editor-section">
          <div class="panel-heading"><div><h3>{{ copy.floorReferences }}</h3><p>{{ copy.jsonHint }}</p></div></div>
          <ElInput v-model="floorReferencesJson" type="textarea" :rows="4" :disabled="!selectedIsDraft" />
          <ElSpace><ElButton :disabled="!selectedIsDraft" @click="applyArrayEditor('floorReferences')">{{ copy.applyJson }}</ElButton><label class="json-import"><span>{{ copy.importJson }}</span><input type="file" accept="application/json,.json" :disabled="!selectedIsDraft" @change="importArrayFile($event, 'floorReferences')"></label></ElSpace>
        </section>
        <section class="editor-section"><div class="form-grid"><ElFormItem :label="copy.startInclusive"><ElInput v-model="configuration.startInclusive" :disabled="!selectedIsDraft" /></ElFormItem><ElFormItem :label="copy.endExclusive"><ElInput v-model="configuration.endExclusive" :disabled="!selectedIsDraft" /></ElFormItem><ElFormItem :label="copy.sourceReference"><ElInput v-model="configuration.sourceReference" :disabled="!selectedIsDraft" data-test="joint-flow-source-reference" /></ElFormItem></div></section>

        <section class="editor-section"><div class="panel-heading"><div><h3>{{ copy.issues }}</h3></div></div><ElAlert v-if="!structureIssues.length" :title="copy.noIssues" type="success" :closable="false" show-icon /><ul v-else class="issue-list"><li v-for="issue in structureIssues" :key="`${issue.path}-${issue.code}`"><code>{{ issue.path }}</code> {{ issueMessage(issue) }}</li></ul></section>
        <ElSpace wrap class="actions"><ElButton type="primary" :disabled="!selectedIsDraft || !dirty || saving" :loading="saving" data-test="joint-flow-save" @click="saveVersion">{{ copy.save }}</ElButton><ElButton :disabled="!selectedIsDraft || dirty || saving" :loading="saving" @click="transition('submit')">{{ copy.submit }}</ElButton><ElButton :disabled="selected.status !== 'PENDING_REVIEW' || dirty || saving" :loading="saving" @click="transition('approve')">{{ copy.approve }}</ElButton><ElButton :disabled="selected.status !== 'PENDING_REVIEW' || dirty || saving" :loading="saving" @click="transition('reject')">{{ copy.reject }}</ElButton></ElSpace>

        <section class="editor-section preview-section"><div class="panel-heading"><div><h3>{{ copy.preview }}</h3><p>{{ copy.previewHint }}</p></div></div><div class="form-grid"><ElFormItem :label="copy.periodType"><ElSelect v-model="previewForm.periodType" :disabled="!selectedIsDraft"><ElOption value="MONTH" :label="copy.month" /><ElOption value="YEAR" :label="copy.year" /></ElSelect></ElFormItem><ElFormItem :label="copy.period"><ElInput v-model="previewForm.period" :disabled="!selectedIsDraft" :placeholder="copy.periodExample" data-test="joint-flow-preview-period" /></ElFormItem><ElFormItem v-if="previewForm.periodType === 'YEAR'" :label="copy.throughMonth"><ElInput v-model="previewForm.throughMonth" :disabled="!selectedIsDraft" :placeholder="copy.throughMonthExample" /></ElFormItem></div><ElButton :disabled="!selectedIsDraft || dirty" :loading="previewLoading" data-test="joint-flow-preview" @click="runPreview">{{ copy.runPreview }}</ElButton><ElEmpty v-if="!preview && !previewLoading" :description="copy.noPreview" :image-size="40" />
          <template v-if="preview">
            <ElDescriptions :column="3" border class="preview-details">
              <ElDescriptionsItem :label="copy.previewBasis">{{ preview.classificationBasis }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="copy.version">{{ preview.configurationVersionId ?? '—' }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="copy.hash">{{ preview.configurationHash ?? '—' }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="copy.totalAtoms">{{ preview.coverage.totalAtoms }}</ElDescriptionsItem>
              <ElDescriptionsItem :label="copy.allocatedAtoms">{{ preview.coverage.allocatedAtoms }}</ElDescriptionsItem>
            </ElDescriptions>
            <h4>{{ copy.coverage }}</h4>
            <ElTable :data="preview.coverage.dimensions"><ElTableColumn prop="key" :label="copy.key" min-width="140" /><ElTableColumn prop="allocatedAtoms" :label="copy.allocatedAtoms" min-width="150" /><ElTableColumn prop="unallocatedAtoms" :label="copy.unallocatedAtoms" min-width="150" /></ElTable>
            <h4>{{ copy.graph }}</h4>
            <section v-for="row in graphRows" :key="row.key" class="preview-graph">
              <h5>{{ row.title }}</h5>
              <ChartView v-if="row.option" :option="row.option" :accessible-label="`${row.title} ${copy.graph}`" />
              <ElAlert v-else :title="copy.previewGraphUnavailable" type="warning" :closable="false" show-icon />
            </section>
            <ElTable :data="graphRows"><ElTableColumn prop="title" :label="copy.graph" min-width="120" /><ElTableColumn :label="copy.graphState" min-width="120"><template #default="{ row }">{{ row.graph.state }}</template></ElTableColumn><ElTableColumn :label="copy.graphTotal" min-width="140"><template #default="{ row }">{{ row.graph.total ?? '—' }} {{ row.graph.unit }}</template></ElTableColumn><ElTableColumn :label="copy.graphUnallocated" min-width="160"><template #default="{ row }">{{ row.graph.unallocated ?? '—' }} {{ row.graph.unit }}</template></ElTableColumn></ElTable>
            <ElAlert v-if="preview.warnings.length" :title="`${copy.warnings}：${preview.warnings.join('；')}`" type="warning" :closable="false" show-icon />
          </template>
        </section>
      </template>

      <section class="editor-section selection-section"><div class="panel-heading"><div><h3>{{ copy.selected }}</h3><p>{{ copy.selectionHint }}</p></div></div><ElDescriptions v-if="selection" :column="2" border><ElDescriptionsItem :label="copy.selected">{{ selection.versionId ?? copy.noSelection }}</ElDescriptionsItem><ElDescriptionsItem :label="copy.revision">{{ selection.revision }}</ElDescriptionsItem></ElDescriptions><ElForm label-position="top" class="selection-form"><ElFormItem :label="copy.selectApproved"><ElSelect v-model="selectionVersionId" :disabled="saving"><ElOption v-for="version in approvedVersions" :key="version.versionId" :value="version.versionId" :label="`${copy.version} ${version.versionNo}`" /></ElSelect></ElFormItem><ElButton :disabled="!selection || !selectionVersionId || saving" :loading="saving" @click="applySelection">{{ copy.selectApproved }}</ElButton></ElForm></section>
    </template>
  </ElCard>
</template>

<style scoped>
.joint-flow-configuration-panel, .panel-form, .editor-section, .selection-form { display: grid; gap: var(--bec-space-group); }
.panel-heading, .dimension-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
.panel-heading h2, .panel-heading h3, .panel-heading p, .dimension-heading strong, .form-hint, h4 { margin: 0; }
.panel-heading h2, .panel-heading h3 { font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
.panel-heading p, .form-hint { color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }
.form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 3)), 1fr)); gap: var(--bec-space-group); }
.panel-form :deep(.el-form-item), .dimension-card :deep(.el-form-item), .selection-form :deep(.el-form-item) { margin-bottom: 0; min-width: 0; }
.version-details, .editor-section, .selection-section, .preview-details { margin-top: var(--bec-space-section); }
.dimension-card { margin-top: var(--bec-space-group); }
.form-hint { margin-top: var(--bec-space-tight); font-size: var(--bec-font-size-small); }
.json-import { display: inline-flex; position: relative; overflow: hidden; cursor: pointer; color: var(--bec-color-action-primary); font-size: var(--bec-font-size-small); }
.json-import input { position: absolute; inset: 0; opacity: 0; cursor: pointer; }
.json-import input:disabled { cursor: not-allowed; }
.issue-list { display: grid; gap: var(--bec-space-tight); margin: 0; padding-left: var(--bec-space-section); color: var(--bec-color-danger); }
.issue-list code { color: inherit; }
.actions { margin-top: var(--bec-space-section); }
</style>
