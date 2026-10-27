<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from '@/modules/auth/public'
import {
  ElAlert, ElButton, ElCard, ElCheckbox, ElCheckboxGroup, ElDatePicker, ElEmpty, ElForm, ElFormItem, ElInput,
  ElInputNumber, ElOption, ElPagination, ElSelect, ElSkeleton, ElSpace, ElTable, ElTableColumn, ElTag,
} from '@/shared/ui'
import { carbonFactorsApi } from '@/modules/energy-classification/public'
import type { CarbonDenominatorVersion, CreateCarbonDenominatorRequest } from '@/modules/energy-classification/public'
import { createIdempotencyKey } from '@/shared/utils/idempotency-key'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { useReportingBuildings } from '../composables/use-reporting-buildings'
import { useParameterPackages } from '../composables/use-parameter-packages'
import type {
  ParameterPackageCatalogRequirement,
  ParameterPackageImport,
  ParameterPackageIssue,
  ParameterPackageRequest,
  ParameterPackageVersionContext,
  ParameterPackagePreview,
} from '../models/parameter-packages'
import copy from '../locales/zh-CN'

type DenominatorDraft = Omit<CreateCarbonDenominatorRequest, 'buildingId' | 'unitCode'>

const route = useRoute()
const router = useRouter()
const session = useSession()
const buildings = useReportingBuildings()
const packages = useParameterPackages()
const selectedBuildingId = ref('')
const packageDraft = ref<ParameterPackageRequest>(emptyPackageRequest())
const preview = ref<ParameterPackagePreview | null>(null)
const previewSignature = ref('')
const importResult = ref<ParameterPackageImport | null>(null)
const denominators = ref<CarbonDenominatorVersion[]>([])
const denominatorDraft = ref<DenominatorDraft>(emptyDenominatorDraft())
const denominatorReviewComment = ref('')
const denominatorsLoading = ref(false)
const denominatorsSaving = ref(false)
const denominatorError = ref('')
const localError = ref('')
let denominatorGeneration = 0
let activeDenominatorRequest: AbortController | null = null

const catalog = computed(() => packages.catalog.value)
const selectedPackage = computed(() => catalog.value?.packages.find(item => item.packageCode === packageDraft.value.packageCode) ?? null)
const parameterSignature = computed(() => JSON.stringify(normalizePackageRequest(packageDraft.value)))
const previewIsCurrent = computed(() => !!preview.value && previewSignature.value === parameterSignature.value)
// 提前收紧业主的专业参数写入口；后端仍按当前授权独立裁决。
const canManageParameters = computed(() => session.user?.roles.some(role => role === 'ENERGY_MANAGER' || role === 'PLATFORM_ADMIN') === true)
const canImport = computed(() => canManageParameters.value && !!preview.value?.readyForImport && previewIsCurrent.value && !packages.saving.value)
const denominatorUnit = computed(() => denominatorDraft.value.denominatorType === 'BUILDING_AREA' ? 'M2' : 'PERSON')
const denominatorUnitLabel = computed(() => unitLabel(denominatorUnit.value))
const approvedVersionOptions = computed(() => (catalog.value?.existingVersions ?? []).filter(version => version.status === 'APPROVED'))
const standardCoalOptions = computed(() => approvedVersionOptions.value.filter(version => version.domain === 'STANDARD_COAL'))
const formulaOptions = computed(() => approvedVersionOptions.value.filter(version => version.domain === 'CONVERSION_FORMULA'))
const energyItemOptions = computed(() => approvedVersionOptions.value.filter(version => version.domain === 'ENERGY_ITEM'))
const catalogRequirementRows = computed(() => catalog.value?.catalogRequirements ?? [])

watch(
  () => route.query.buildingId,
  value => { void applyRouteBuilding(routeValue(value)) },
  { immediate: true },
)
watch(parameterSignature, signature => {
  if (previewSignature.value && previewSignature.value !== signature) preview.value = null
})
watch(
  () => packageDraft.value.packageCode,
  () => {
    const available = new Set(selectedPackage.value?.items.map(item => item.itemCode) ?? [])
    packageDraft.value.itemCodes = packageDraft.value.itemCodes.filter(code => available.has(code))
  },
)
onMounted(() => { void buildings.search() })

async function applyRouteBuilding(buildingId: string): Promise<void> {
  const changed = selectedBuildingId.value !== buildingId
  selectedBuildingId.value = buildingId
  if (changed) resetForBuilding()
  if (!buildingId) {
    packages.catalog.value = null
    denominators.value = []
    return
  }
  await loadAll(buildingId)
}

async function replaceBuilding(buildingId: string): Promise<void> {
  const query = { ...route.query }
  delete query.buildingId
  await router.replace({ path: route.path, query: { ...query, ...(buildingId ? { buildingId } : {}) } })
}

function requestBuildingChange(): void {
  if (packages.saving.value || denominatorsSaving.value) return
  void replaceBuilding(selectedBuildingId.value)
}

function searchBuildings(keyword: string): void {
  void buildings.search(1, keyword)
}

function refresh(): void {
  if (selectedBuildingId.value && !packages.saving.value && !denominatorsSaving.value) void loadAll(selectedBuildingId.value)
}

async function loadAll(buildingId: string): Promise<void> {
  localError.value = ''
  await Promise.all([packages.load(buildingId), loadDenominators(buildingId)])
}

async function loadDenominators(buildingId: string): Promise<void> {
  const target = buildingId.trim()
  const owner = ++denominatorGeneration
  activeDenominatorRequest?.abort()
  activeDenominatorRequest = null
  denominatorError.value = ''
  if (!target) {
    denominators.value = []
    denominatorsLoading.value = false
    return
  }
  const controller = new AbortController()
  activeDenominatorRequest = controller
  denominatorsLoading.value = true
  try {
    const response = await carbonFactorsApi.listDenominators(target, controller.signal)
    if (owner === denominatorGeneration && selectedBuildingId.value === target) denominators.value = response
  } catch (reason) {
    if (owner === denominatorGeneration && selectedBuildingId.value === target) denominatorError.value = requestErrorMessage(reason)
  } finally {
    if (owner === denominatorGeneration) {
      denominatorsLoading.value = false
      if (activeDenominatorRequest === controller) activeDenominatorRequest = null
    }
  }
}

async function previewPackage(): Promise<void> {
  if (!canManageParameters.value || !validPackageSelection()) return
  const request = normalizePackageRequest(packageDraft.value)
  const response = await packages.preview(request)
  if (response) {
    preview.value = response
    previewSignature.value = JSON.stringify(request)
    importResult.value = null
  }
}

async function importPackage(): Promise<void> {
  if (!canManageParameters.value) return
  if (!canImport.value) {
    localError.value = copy.parameters.previewStale
    return
  }
  const response = await packages.importPackage(normalizePackageRequest(packageDraft.value))
  if (!response) return
  importResult.value = response
  preview.value = null
  previewSignature.value = ''
  packageDraft.value.idempotencyKey = createIdempotencyKey()
  await loadAll(selectedBuildingId.value)
}

async function createDenominator(): Promise<void> {
  if (!canManageParameters.value) return
  const request = normalizedDenominatorRequest()
  if (!request) {
    denominatorError.value = copy.parameters.denominatorValidation
    return
  }
  await executeDenominator(async () => {
    await carbonFactorsApi.createDenominator(request)
    denominatorDraft.value = emptyDenominatorDraft()
    await refreshDenominatorsAndCatalog()
  })
}

async function reviewDenominator(value: CarbonDenominatorVersion, approved: boolean): Promise<void> {
  if (!canManageParameters.value) return
  const comment = denominatorReviewComment.value.trim()
  if (!comment) {
    denominatorError.value = copy.parameters.reviewCommentRequired
    return
  }
  await executeDenominator(async () => {
    await carbonFactorsApi.reviewDenominator(value.denominatorVersionId, {
      expectedRevision: value.configRevision,
      approved,
      reviewComment: comment,
    })
    denominatorReviewComment.value = ''
    await refreshDenominatorsAndCatalog()
  })
}

async function activateDenominator(value: CarbonDenominatorVersion): Promise<void> {
  if (!canManageParameters.value) return
  await executeDenominator(async () => {
    await carbonFactorsApi.activateDenominator(value.denominatorVersionId, { expectedRevision: value.configRevision })
    await refreshDenominatorsAndCatalog()
  })
}

async function executeDenominator(action: () => Promise<void>): Promise<void> {
  if (denominatorsSaving.value) return
  denominatorsSaving.value = true
  denominatorError.value = ''
  try {
    await action()
  } catch (reason) {
    denominatorError.value = requestErrorMessage(reason)
  } finally {
    denominatorsSaving.value = false
  }
}

async function refreshDenominatorsAndCatalog(): Promise<void> {
  if (!selectedBuildingId.value) return
  await Promise.all([loadDenominators(selectedBuildingId.value), packages.load(selectedBuildingId.value)])
}

function validPackageSelection(): boolean {
  if (packageDraft.value.packageCode && packageDraft.value.itemCodes.length) {
    localError.value = ''
    return true
  }
  localError.value = copy.parameters.selectionRequired
  return false
}

function resetForBuilding(): void {
  packageDraft.value = emptyPackageRequest()
  preview.value = null
  previewSignature.value = ''
  importResult.value = null
  denominatorDraft.value = emptyDenominatorDraft()
  denominatorReviewComment.value = ''
  denominatorError.value = ''
  localError.value = ''
}

function emptyPackageRequest(): ParameterPackageRequest {
  return {
    packageCode: '',
    itemCodes: [],
    source: {
      sourceCode: '', sourceName: '', publisher: '', documentReference: '', sourceDocumentVersion: '', publicationYear: null,
      publishedOn: null, applicabilityNote: '', evidenceReference: '', energySourceType: '',
    },
    effectiveFrom: null,
    effectiveTo: null,
    standardConditionCode: null,
    dependencies: {
      standardCoalLhvVersionId: null, naturalGasFormulaVersionId: null, rawCoalFormulaVersionId: null, rawCoalEnergyItemCode: null,
    },
    electricity: { applicabilityLevel: '', regionCode: null, dataYear: null, accountingYear: null },
    heat: { co2eEvidenceReference: '' },
    idempotencyKey: createIdempotencyKey(),
  }
}

function emptyDenominatorDraft(): DenominatorDraft {
  return {
    denominatorType: 'BUILDING_AREA', value: '', sourceReference: '', evidenceReference: '', usageNature: 'FORMAL', effectiveFrom: '', effectiveTo: null,
  }
}

function normalizePackageRequest(value: ParameterPackageRequest): ParameterPackageRequest {
  return {
    packageCode: value.packageCode,
    itemCodes: [...value.itemCodes],
    source: {
      sourceCode: clean(value.source.sourceCode), sourceName: clean(value.source.sourceName), publisher: clean(value.source.publisher),
      documentReference: clean(value.source.documentReference), sourceDocumentVersion: clean(value.source.sourceDocumentVersion),
      publicationYear: value.source.publicationYear, publishedOn: nullable(value.source.publishedOn), applicabilityNote: clean(value.source.applicabilityNote),
      evidenceReference: clean(value.source.evidenceReference), energySourceType: clean(value.source.energySourceType),
    },
    effectiveFrom: nullable(value.effectiveFrom),
    effectiveTo: nullable(value.effectiveTo),
    standardConditionCode: nullable(value.standardConditionCode),
    dependencies: {
      standardCoalLhvVersionId: nullable(value.dependencies.standardCoalLhvVersionId),
      naturalGasFormulaVersionId: nullable(value.dependencies.naturalGasFormulaVersionId),
      rawCoalFormulaVersionId: nullable(value.dependencies.rawCoalFormulaVersionId),
      rawCoalEnergyItemCode: nullable(value.dependencies.rawCoalEnergyItemCode),
    },
    electricity: {
      applicabilityLevel: clean(value.electricity.applicabilityLevel), regionCode: nullable(value.electricity.regionCode),
      dataYear: value.electricity.dataYear, accountingYear: value.electricity.accountingYear,
    },
    heat: { co2eEvidenceReference: clean(value.heat.co2eEvidenceReference) },
    idempotencyKey: clean(value.idempotencyKey),
  }
}

function normalizedDenominatorRequest(): CreateCarbonDenominatorRequest | null {
  const value = denominatorDraft.value
  const number = Number(value.value)
  if (!selectedBuildingId.value || !Number.isFinite(number) || number <= 0 || !clean(value.sourceReference)
    || !clean(value.evidenceReference) || !value.effectiveFrom) return null
  return {
    buildingId: selectedBuildingId.value,
    denominatorType: value.denominatorType,
    value: number.toString(),
    unitCode: denominatorUnit.value,
    sourceReference: clean(value.sourceReference),
    evidenceReference: clean(value.evidenceReference),
    usageNature: value.usageNature,
    effectiveFrom: value.effectiveFrom,
    effectiveTo: nullable(value.effectiveTo),
  }
}

// Element Plus 槽位不会保留表格 data 的泛型；这些断言只连接本页已绑定的受控数组。
function catalogRequirement(row: unknown): ParameterPackageCatalogRequirement {
  return row as ParameterPackageCatalogRequirement
}

function parameterPackageIssue(row: unknown): ParameterPackageIssue {
  return row as ParameterPackageIssue
}

function denominatorVersion(row: unknown): CarbonDenominatorVersion {
  return row as CarbonDenominatorVersion
}

function catalogState(requirement: ParameterPackageCatalogRequirement): string {
  if (!requirement.versions.length) return copy.parameters.catalogNoVersion
  return requirement.versions.some(version => version.status === 'APPROVED')
    ? copy.parameters.catalogApproved
    : copy.parameters.catalogUnapproved
}

function dependencyText(value: ParameterPackageVersionContext): string {
  return `${parameterContextName(value.code)} ${copy.separator} ${statusLabel(value.status)}`
}

function dependencySummary(values: ParameterPackageVersionContext[]): string {
  return values.map(dependencyText).join('；')
}

function previewDependencySummary(values: Array<{ dependencyType: string, status: string, message: string }>): string {
  return values.map(value => `${dependencyName(value.dependencyType)} ${copy.separator} ${statusLabel(value.status)} ${dependencyAction(value.status)}`).join('；')
}

function mappedLabel(values: Readonly<Record<string, string>>, value: string, fallback: string): string {
  return values[value] ?? fallback
}

function packageName(packageCode: string): string {
  return mappedLabel(copy.parameters.packageNames, packageCode, copy.parameters.unknownItem)
}

function parameterItemName(itemCode: string): string {
  return mappedLabel(copy.parameters.itemNames, itemCode, copy.parameters.unknownItem)
}

function parameterItemHint(itemCode: string): string {
  return mappedLabel(copy.parameters.itemHints, itemCode, copy.parameters.unknownIssueAction)
}

function parameterContextName(code: string): string {
  return copy.parameters.itemNames[code]
    ?? copy.parameters.energyItemNames[code]
    ?? copy.parameters.unitNames[code]
    ?? copy.parameters.packageNames[code]
    ?? copy.parameters.catalogTypeNames[code]
    ?? copy.parameters.objectTypeNames[code]
    ?? copy.parameters.unknownCatalogVersion
}

function catalogTypeName(value: string): string {
  return mappedLabel(copy.parameters.catalogTypeNames, value, copy.parameters.unknownCatalogVersion)
}

function objectTypeName(value: string): string {
  return mappedLabel(copy.parameters.objectTypeNames, value, copy.parameters.unknownCatalogVersion)
}

function statusLabel(value: string): string {
  return mappedLabel(copy.parameters.statusNames, value, copy.parameters.unknownStatus)
}

function unitLabel(value: string): string {
  return value.split(';').map(unit => mappedLabel(copy.parameters.unitNames, unit.trim(), copy.parameters.unknownUnit)).join('；')
}

function quantityText(value: string | number | null | undefined, unit: string): string {
  if (value === null || value === undefined || value === '') return copy.parameters.unavailable
  return `${value} ${unitLabel(unit)}`
}

function dependencyName(value: string): string {
  return mappedLabel(copy.parameters.dependencyTypeNames, value, parameterContextName(value))
}

function dependencyAction(value: string): string {
  return mappedLabel(copy.parameters.dependencyActions, value, copy.parameters.dependencyActions.default)
}

function issueActionText(value: ParameterPackageIssue): string {
  return mappedLabel(copy.parameters.issueActions, value.code, copy.parameters.unknownIssueAction)
}

function denominatorTypeName(value: CarbonDenominatorVersion['denominatorType']): string {
  return value === 'BUILDING_AREA' ? copy.parameters.buildingArea : copy.parameters.residentPopulation
}

function tagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'APPROVED' || status === 'ACTIVE' || status === 'READY' || status === 'CREATED_PENDING_REVIEW') return 'success'
  if (status === 'PENDING_REVIEW' || status === 'PARTIAL') return 'warning'
  if (status === 'REJECTED' || status === 'BLOCKER') return 'danger'
  return 'info'
}

function issueTagType(issue: ParameterPackageIssue): 'success' | 'warning' | 'danger' | 'info' {
  return tagType(issue.severity)
}

function routeValue(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

function clean(value: string): string {
  return value.trim()
}

function nullable(value: string | null): string | null {
  const cleaned = value?.trim() ?? ''
  return cleaned || null
}
</script>

<template>
  <main class="parameter-packages-page">
    <header class="page-heading">
      <div>
        <h1>{{ copy.parameters.title }}</h1>
        <p>{{ copy.parameters.description }}</p>
      </div>
      <ElButton @click="router.push({ path: route.path, query: { ...route.query, mode: undefined } })">{{ copy.parameters.returnDaily }}</ElButton>
    </header>

    <ElCard shadow="never" class="building-card">
      <ElForm label-position="top" class="building-form">
        <ElFormItem :label="copy.parameters.building">
          <ElSelect v-model="selectedBuildingId" filterable remote clearable :remote-method="searchBuildings" :loading="buildings.loading.value" :placeholder="copy.parameters.buildingPlaceholder" :disabled="packages.saving.value || denominatorsSaving" @change="requestBuildingChange">
            <ElOption v-for="building in buildings.records.value" :key="building.buildingId" :value="building.buildingId" :label="building.buildingName" />
            <template #footer><ElPagination size="small" layout="total, prev, next" :current-page="buildings.page.value" :page-size="20" :total="buildings.total.value" @current-change="buildings.search" /></template>
          </ElSelect>
        </ElFormItem>
        <ElButton :disabled="!selectedBuildingId || packages.loading.value || denominatorsLoading || packages.saving.value || denominatorsSaving" :loading="packages.loading.value || denominatorsLoading" @click="refresh">{{ copy.parameters.refresh }}</ElButton>
      </ElForm>
      <ElAlert v-if="buildings.error.value" type="error" :title="buildings.error.value" :closable="false" show-icon />
      <ElAlert v-if="localError" type="error" :title="localError" :closable="false" show-icon />
      <ElAlert v-if="packages.error.value" type="error" :title="packages.error.value" :closable="false" show-icon />
      <ElAlert v-if="denominatorError" type="error" :title="denominatorError" :closable="false" show-icon />
    </ElCard>

    <ElAlert v-if="!selectedBuildingId" type="info" :title="copy.parameters.noBuilding" :closable="false" show-icon />
    <ElSkeleton v-else-if="packages.loading.value && !catalog" animated :rows="10" />
    <template v-else-if="catalog">
      <ElCard shadow="never">
        <template #header><div class="card-heading"><div><h2>{{ copy.parameters.catalogTitle }}</h2><p>{{ copy.parameters.catalogHint }}</p></div></div></template>
        <ElTable v-if="catalogRequirementRows.length" :data="catalogRequirementRows" size="small">
          <ElTableColumn :label="copy.parameters.catalogType" min-width="150"><template #default="{ row }">{{ catalogTypeName(catalogRequirement(row).catalogType) }}</template></ElTableColumn>
          <ElTableColumn :label="copy.parameters.requiredCode" min-width="190"><template #default="{ row }">{{ parameterContextName(catalogRequirement(row).requiredCode) }}</template></ElTableColumn>
          <ElTableColumn :label="copy.parameters.catalogState" min-width="260"><template #default="{ row }"><ElTag :type="catalogRequirement(row).versions.some((item: ParameterPackageVersionContext) => item.status === 'APPROVED') ? 'success' : 'warning'">{{ catalogState(catalogRequirement(row)) }}</ElTag><small v-if="catalogRequirement(row).versions.length">{{ dependencySummary(catalogRequirement(row).versions) }}</small></template></ElTableColumn>
          <ElTableColumn :label="copy.parameters.technicalDetails" min-width="150"><template #default="{ row }"><details class="technical-details"><summary>{{ copy.parameters.technicalDetails }}</summary><div>{{ copy.parameters.catalogCode }}{{ copy.fieldSeparator }}<code>{{ catalogRequirement(row).catalogType }}</code></div><div>{{ copy.parameters.itemCode }}{{ copy.fieldSeparator }}<code>{{ catalogRequirement(row).requiredCode }}</code></div><div v-for="version in catalogRequirement(row).versions" :key="version.versionId"><code>{{ version.code }} {{ copy.separator }} {{ version.versionId }} {{ copy.separator }} {{ version.status }}</code></div></details></template></ElTableColumn>
        </ElTable>
        <ElEmpty v-else :description="copy.parameters.catalogNoVersion" :image-size="44" />
      </ElCard>

      <ElAlert v-if="!canManageParameters" data-test="parameter-read-only" type="info" :title="copy.parameters.readOnlyHint" :closable="false" show-icon />
      <ElCard v-if="canManageParameters" data-test="parameter-write-form" shadow="never">
        <template #header><div class="card-heading"><div><h2>{{ copy.parameters.packageTitle }}</h2><p>{{ copy.parameters.packageHint }}</p></div></div></template>
        <ElForm label-position="top" class="parameter-form">
          <div class="form-grid">
            <ElFormItem :label="copy.parameters.package">
              <ElSelect v-model="packageDraft.packageCode" data-test="parameter-package" clearable :placeholder="copy.parameters.packagePlaceholder" :disabled="packages.saving.value">
                <ElOption v-for="definition in catalog.packages" :key="definition.packageCode" :value="definition.packageCode" :label="packageName(definition.packageCode)" />
              </ElSelect>
              <details v-if="packageDraft.packageCode" class="technical-details"><summary>{{ copy.parameters.technicalDetails }}</summary><code>{{ packageDraft.packageCode }}</code></details>
            </ElFormItem>
            <ElFormItem :label="copy.parameters.items" class="wide-field">
              <ElCheckboxGroup v-model="packageDraft.itemCodes" :disabled="!selectedPackage || packages.saving.value">
                <ElCheckbox v-for="item in selectedPackage?.items ?? []" :key="item.itemCode" :label="item.itemCode">{{ parameterItemName(item.itemCode) }}</ElCheckbox>
              </ElCheckboxGroup>
              <small>{{ copy.parameters.itemsHint }}</small>
            </ElFormItem>
          </div>

          <section class="section-block">
            <h3>{{ copy.parameters.sourceTitle }}</h3>
            <div class="form-grid">
              <ElFormItem :label="copy.parameters.sourceCode"><ElInput v-model="packageDraft.source.sourceCode" :maxlength="64" :disabled="packages.saving.value" /></ElFormItem>
              <ElFormItem :label="copy.parameters.sourceName"><ElInput v-model="packageDraft.source.sourceName" :maxlength="200" :disabled="packages.saving.value" /></ElFormItem>
              <ElFormItem :label="copy.parameters.publisher"><ElInput v-model="packageDraft.source.publisher" :maxlength="200" :disabled="packages.saving.value" /></ElFormItem>
              <ElFormItem :label="copy.parameters.documentReference"><ElInput v-model="packageDraft.source.documentReference" :maxlength="500" :disabled="packages.saving.value" /></ElFormItem>
              <ElFormItem :label="copy.parameters.sourceDocumentVersion"><ElInput v-model="packageDraft.source.sourceDocumentVersion" :maxlength="100" :disabled="packages.saving.value" /></ElFormItem>
              <ElFormItem :label="copy.parameters.publicationYear"><ElInputNumber v-model="packageDraft.source.publicationYear" :min="1900" :max="2200" :controls="false" :disabled="packages.saving.value" /></ElFormItem>
              <ElFormItem :label="copy.parameters.publishedOn"><ElDatePicker v-model="packageDraft.source.publishedOn" type="date" value-format="YYYY-MM-DD" :disabled="packages.saving.value" /></ElFormItem>
              <ElFormItem :label="copy.parameters.energySourceType"><ElSelect v-model="packageDraft.source.energySourceType" clearable :disabled="packages.saving.value"><ElOption v-for="(label, value) in copy.parameters.sourceType" :key="value" :value="value" :label="label" /></ElSelect><small>{{ copy.parameters.sourceTypeHint }}</small></ElFormItem>
              <ElFormItem :label="copy.parameters.effectiveFrom"><ElDatePicker v-model="packageDraft.effectiveFrom" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" :disabled="packages.saving.value" /></ElFormItem>
              <ElFormItem :label="copy.parameters.effectiveTo"><ElDatePicker v-model="packageDraft.effectiveTo" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" :disabled="packages.saving.value" /></ElFormItem>
              <ElFormItem class="wide-field" :label="copy.parameters.applicabilityNote"><ElInput v-model="packageDraft.source.applicabilityNote" type="textarea" :maxlength="1000" show-word-limit :disabled="packages.saving.value" /></ElFormItem>
              <ElFormItem class="wide-field" :label="copy.parameters.evidenceReference"><ElInput v-model="packageDraft.source.evidenceReference" type="textarea" :maxlength="1000" show-word-limit :disabled="packages.saving.value" /></ElFormItem>
            </div>
          </section>

          <section class="section-block">
            <h3>{{ copy.parameters.dependenciesTitle }}</h3>
            <div class="form-grid">
              <ElFormItem :label="copy.parameters.standardCoalLhv"><ElSelect v-model="packageDraft.dependencies.standardCoalLhvVersionId" clearable filterable :disabled="packages.saving.value"><ElOption v-for="option in standardCoalOptions" :key="option.versionId" :value="option.versionId" :label="dependencyText(option)" /></ElSelect></ElFormItem>
              <ElFormItem :label="copy.parameters.naturalGasFormula"><ElSelect v-model="packageDraft.dependencies.naturalGasFormulaVersionId" clearable filterable :disabled="packages.saving.value"><ElOption v-for="option in formulaOptions" :key="option.versionId" :value="option.versionId" :label="dependencyText(option)" /></ElSelect></ElFormItem>
              <ElFormItem :label="copy.parameters.rawCoalFormula"><ElSelect v-model="packageDraft.dependencies.rawCoalFormulaVersionId" clearable filterable :disabled="packages.saving.value"><ElOption v-for="option in formulaOptions" :key="option.versionId" :value="option.versionId" :label="dependencyText(option)" /></ElSelect></ElFormItem>
              <ElFormItem :label="copy.parameters.rawCoalEnergyItem"><ElSelect v-model="packageDraft.dependencies.rawCoalEnergyItemCode" clearable filterable :disabled="packages.saving.value"><ElOption v-for="option in energyItemOptions" :key="option.versionId" :value="option.code" :label="dependencyText(option)" /></ElSelect></ElFormItem>
              <ElFormItem class="wide-field"><small>{{ copy.parameters.dependencyHint }}</small></ElFormItem>
            </div>
          </section>

          <section class="section-block">
            <h3>{{ copy.parameters.electricityTitle }}</h3>
            <div class="form-grid">
              <ElFormItem :label="copy.parameters.applicabilityLevel"><ElSelect v-model="packageDraft.electricity.applicabilityLevel" clearable :disabled="packages.saving.value"><ElOption v-for="(label, value) in copy.parameters.applicability" :key="value" :value="value" :label="label" /></ElSelect></ElFormItem>
              <ElFormItem :label="copy.parameters.regionCode"><ElInput v-model="packageDraft.electricity.regionCode" :maxlength="32" :disabled="packages.saving.value" /></ElFormItem>
              <ElFormItem :label="copy.parameters.dataYear"><ElInputNumber v-model="packageDraft.electricity.dataYear" :min="1900" :max="2200" :controls="false" :disabled="packages.saving.value" /></ElFormItem>
              <ElFormItem :label="copy.parameters.accountingYear"><ElInputNumber v-model="packageDraft.electricity.accountingYear" :min="2000" :max="2200" :controls="false" :disabled="packages.saving.value" /></ElFormItem>
              <ElFormItem :label="copy.parameters.standardConditionCode"><ElInput v-model="packageDraft.standardConditionCode" :maxlength="100" :disabled="packages.saving.value" /></ElFormItem>
              <ElFormItem :label="copy.parameters.heatEvidence"><ElInput v-model="packageDraft.heat.co2eEvidenceReference" :maxlength="1000" :disabled="packages.saving.value" /></ElFormItem>
            </div>
            <p class="field-help">{{ copy.parameters.dataYearHint }}</p>
          </section>

          <section class="section-block">
            <h3>{{ copy.parameters.items }}</h3>
            <ElTable v-if="selectedPackage?.items.length" :data="selectedPackage.items" size="small">
              <ElTableColumn :label="copy.parameters.items" min-width="200"><template #default="{ row }">{{ parameterItemName(row.itemCode) }}</template></ElTableColumn>
              <ElTableColumn :label="copy.parameters.sourceValue" min-width="220"><template #default="{ row }">{{ quantityText(row.sourceValue, row.sourceUnit) }}</template></ElTableColumn>
              <ElTableColumn :label="copy.parameters.normalizedValue" min-width="220"><template #default="{ row }">{{ quantityText(row.normalizedValue, row.normalizedUnit) }}</template></ElTableColumn>
              <ElTableColumn :label="copy.parameters.processingHint" min-width="300"><template #default="{ row }">{{ parameterItemHint(row.itemCode) }}</template></ElTableColumn>
              <ElTableColumn :label="copy.parameters.technicalDetails" min-width="180"><template #default="{ row }"><details class="technical-details"><summary>{{ copy.parameters.technicalDetails }}</summary><div>{{ copy.parameters.itemCode }}{{ copy.fieldSeparator }}<code>{{ row.itemCode }}</code></div><div>{{ copy.parameters.unitCode }}{{ copy.fieldSeparator }}<code>{{ row.sourceUnit }} {{ copy.conversionArrow }} {{ row.normalizedUnit }}</code></div><div>{{ copy.parameters.requiredInputs }}{{ copy.fieldSeparator }}<code>{{ row.requiredInputs.join('；') }}</code></div></details></template></ElTableColumn>
            </ElTable>
          </section>
        </ElForm>
        <div class="actions"><ElButton data-test="parameter-preview" type="primary" :disabled="packages.saving.value" :loading="packages.saving.value" @click="previewPackage">{{ copy.parameters.preview }}</ElButton><ElButton data-test="parameter-import" type="success" :disabled="!canImport" :loading="packages.saving.value" @click="importPackage">{{ copy.parameters.import }}</ElButton></div>
        <ElAlert v-if="preview && previewIsCurrent" :type="preview.readyForImport ? 'success' : 'warning'" :title="preview.readyForImport ? copy.parameters.previewReady : copy.parameters.previewBlocked" :closable="false" show-icon />
        <ElAlert v-else-if="preview" type="info" :title="copy.parameters.previewRequired" :closable="false" show-icon />
      </ElCard>

      <ElCard v-if="canManageParameters && preview && previewIsCurrent" shadow="never">
        <template #header><div class="card-heading"><div><h2>{{ copy.parameters.previewTitle }}</h2></div><ElTag :type="preview.readyForImport ? 'success' : 'warning'">{{ preview.readyForImport ? copy.parameters.previewReady : copy.parameters.previewBlocked }}</ElTag></div></template>
        <ElTable v-if="preview.items.length" :data="preview.items" size="small">
          <ElTableColumn :label="copy.parameters.items" min-width="200"><template #default="{ row }">{{ parameterItemName(row.itemCode) }}</template></ElTableColumn>
          <ElTableColumn :label="copy.parameters.normalizedValue" min-width="220"><template #default="{ row }">{{ quantityText(row.normalizedValue, row.normalizedUnit) }}</template></ElTableColumn>
          <ElTableColumn :label="copy.parameters.dependencies" min-width="300"><template #default="{ row }"><span data-test="preview-dependencies">{{ previewDependencySummary(row.dependencies) }}</span></template></ElTableColumn>
          <ElTableColumn :label="copy.parameters.technicalDetails" min-width="180"><template #default="{ row }"><details data-test="preview-dependency-technical" class="technical-details"><summary>{{ copy.parameters.technicalDetails }}</summary><div>{{ copy.parameters.itemCode }}{{ copy.fieldSeparator }}<code>{{ row.itemCode }}</code></div><div>{{ copy.parameters.unitCode }}{{ copy.fieldSeparator }}<code>{{ row.normalizedUnit }}</code></div><div v-for="dependency in row.dependencies" :key="dependency.dependencyType"><code>{{ dependency.dependencyType }} {{ copy.separator }} {{ dependency.status }} {{ copy.separator }} {{ dependency.versionId ?? copy.parameters.unavailable }}</code><div>{{ copy.parameters.serverMessage }}{{ copy.fieldSeparator }}<code>{{ dependency.message || copy.parameters.unavailable }}</code></div></div></details></template></ElTableColumn>
        </ElTable>
        <ElTable v-if="preview.issues.length" :data="preview.issues" size="small"><ElTableColumn :label="copy.parameters.status" min-width="120"><template #default="{ row }"><ElTag :type="issueTagType(parameterPackageIssue(row))">{{ statusLabel(parameterPackageIssue(row).severity) }}</ElTag></template></ElTableColumn><ElTableColumn :label="copy.parameters.items" min-width="190"><template #default="{ row }">{{ parameterPackageIssue(row).itemCode ? parameterItemName(parameterPackageIssue(row).itemCode ?? '') : copy.parameters.generalIssue }}</template></ElTableColumn><ElTableColumn :label="copy.parameters.issues" min-width="340"><template #default="{ row }"><span data-test="preview-issue-action">{{ issueActionText(parameterPackageIssue(row)) }}</span></template></ElTableColumn><ElTableColumn :label="copy.parameters.technicalDetails" min-width="160"><template #default="{ row }"><details data-test="preview-issue-technical" class="technical-details"><summary>{{ copy.parameters.technicalDetails }}</summary><div>{{ copy.parameters.itemCode }}{{ copy.fieldSeparator }}<code>{{ parameterPackageIssue(row).itemCode ?? copy.parameters.unavailable }}</code></div><div><code>{{ parameterPackageIssue(row).code }}</code></div><div>{{ copy.parameters.serverMessage }}{{ copy.fieldSeparator }}<code>{{ parameterPackageIssue(row).message || copy.parameters.unavailable }}</code></div></details></template></ElTableColumn></ElTable>
        <ElAlert v-else type="success" :title="copy.parameters.noIssues" :closable="false" show-icon />
      </ElCard>

      <ElCard v-if="canManageParameters && importResult" shadow="never">
        <template #header><div class="card-heading"><div><h2>{{ copy.parameters.importTitle }}</h2></div><ElTag :type="tagType(importResult.completionStatus)">{{ statusLabel(importResult.completionStatus) }}</ElTag></div></template>
        <ElAlert v-if="importResult.awaitingIndependentReview" type="warning" :title="copy.parameters.awaitingReview" :closable="false" show-icon />
        <ElTable v-if="importResult.created.length" :data="importResult.created" size="small"><ElTableColumn :label="copy.parameters.items" min-width="200"><template #default="{ row }">{{ parameterItemName(row.itemCode) }}</template></ElTableColumn><ElTableColumn :label="copy.parameters.objectType" min-width="180"><template #default="{ row }">{{ objectTypeName(row.objectType) }}</template></ElTableColumn><ElTableColumn :label="copy.parameters.status" min-width="150"><template #default="{ row }"><ElTag :type="tagType(row.status)">{{ statusLabel(row.status) }}</ElTag></template></ElTableColumn><ElTableColumn :label="copy.parameters.technicalDetails" min-width="180"><template #default="{ row }"><details class="technical-details"><summary>{{ copy.parameters.technicalDetails }}</summary><div>{{ copy.parameters.itemCode }}{{ copy.fieldSeparator }}<code>{{ row.itemCode }}</code></div><div><code>{{ row.objectType }} {{ copy.separator }} {{ row.versionId }} {{ copy.separator }} {{ row.status }}</code></div><div v-if="row.dependencyVersionIds.length"><code>{{ row.dependencyVersionIds.join('；') }}</code></div></details></template></ElTableColumn></ElTable>
        <ElTable v-if="importResult.notices.length" :data="importResult.notices" size="small"><ElTableColumn :label="copy.parameters.notices" min-width="340"><template #default="{ row }">{{ issueActionText(parameterPackageIssue(row)) }}</template></ElTableColumn><ElTableColumn :label="copy.parameters.technicalDetails" min-width="160"><template #default="{ row }"><details class="technical-details"><summary>{{ copy.parameters.technicalDetails }}</summary><div><code>{{ row.code }}</code></div><div>{{ copy.parameters.serverMessage }}{{ copy.fieldSeparator }}<code>{{ row.message || copy.parameters.unavailable }}</code></div></details></template></ElTableColumn></ElTable>
      </ElCard>

      <ElCard shadow="never">
        <template #header><div class="card-heading"><div><h2>{{ copy.parameters.denominatorTitle }}</h2><p>{{ copy.parameters.denominatorHint }}</p></div></div></template>
        <ElForm v-if="canManageParameters" data-test="denominator-write-form" label-position="top" class="parameter-form">
          <div class="form-grid">
            <ElFormItem :label="copy.parameters.denominatorType"><ElSelect v-model="denominatorDraft.denominatorType" :disabled="denominatorsSaving"><ElOption value="BUILDING_AREA" :label="copy.parameters.buildingArea" /><ElOption value="RESIDENT_POPULATION" :label="copy.parameters.residentPopulation" /></ElSelect></ElFormItem>
            <ElFormItem :label="copy.parameters.value"><ElInput v-model="denominatorDraft.value" inputmode="decimal" :disabled="denominatorsSaving" /></ElFormItem>
            <ElFormItem :label="copy.parameters.unit"><ElInput :model-value="denominatorUnitLabel" disabled /><details class="technical-details"><summary>{{ copy.parameters.technicalDetails }}</summary><code>{{ denominatorUnit }}</code></details></ElFormItem>
            <ElFormItem :label="copy.parameters.usageNature"><ElInput :model-value="copy.parameters.usage.FORMAL" disabled /></ElFormItem>
            <ElFormItem :label="copy.parameters.effectiveFrom"><ElDatePicker v-model="denominatorDraft.effectiveFrom" type="date" value-format="YYYY-MM-DD" :disabled="denominatorsSaving" /></ElFormItem>
            <ElFormItem :label="copy.parameters.effectiveTo"><ElDatePicker v-model="denominatorDraft.effectiveTo" type="date" value-format="YYYY-MM-DD" :disabled="denominatorsSaving" /></ElFormItem>
            <ElFormItem class="wide-field" :label="copy.parameters.sourceReference"><ElInput v-model="denominatorDraft.sourceReference" type="textarea" :maxlength="1000" show-word-limit :disabled="denominatorsSaving" /></ElFormItem>
            <ElFormItem class="wide-field" :label="copy.parameters.evidenceReference"><ElInput v-model="denominatorDraft.evidenceReference" type="textarea" :maxlength="1000" show-word-limit :disabled="denominatorsSaving" /></ElFormItem>
          </div>
        </ElForm>
        <div v-if="canManageParameters" class="actions"><ElButton data-test="denominator-create" type="primary" :loading="denominatorsSaving" :disabled="denominatorsSaving" @click="createDenominator">{{ copy.parameters.createDenominator }}</ElButton></div>
        <ElAlert type="info" :title="copy.parameters.roleHint" :closable="false" show-icon />
        <ElFormItem v-if="canManageParameters && denominators.some(item => item.status === 'PENDING_REVIEW')" :label="copy.parameters.reviewComment" class="review-comment"><ElInput v-model="denominatorReviewComment" type="textarea" :maxlength="500" show-word-limit :disabled="denominatorsSaving" /></ElFormItem>
        <ElTable v-if="denominators.length" v-loading="denominatorsLoading" data-test="denominator-list" :data="denominators" size="small">
          <ElTableColumn :label="copy.parameters.denominatorType" min-width="150"><template #default="{ row }">{{ denominatorTypeName(denominatorVersion(row).denominatorType) }}</template></ElTableColumn>
          <ElTableColumn prop="value" :label="copy.parameters.value" min-width="120" />
          <ElTableColumn :label="copy.parameters.unit" min-width="180"><template #default="{ row }">{{ unitLabel(denominatorVersion(row).unitCode) }}<details class="technical-details"><summary>{{ copy.parameters.technicalDetails }}</summary><code>{{ denominatorVersion(row).unitCode }} {{ copy.separator }} {{ denominatorVersion(row).denominatorVersionId }}</code></details></template></ElTableColumn>
          <ElTableColumn prop="effectiveFrom" :label="copy.parameters.effectiveFrom" min-width="130" />
          <ElTableColumn :label="copy.parameters.status" min-width="130"><template #default="{ row }"><ElTag :type="tagType(row.status)">{{ statusLabel(row.status) }}</ElTag></template></ElTableColumn>
          <ElTableColumn v-if="canManageParameters" min-width="230" fixed="right"><template #default="{ row }"><ElSpace wrap><ElButton v-if="denominatorVersion(row).status === 'PENDING_REVIEW'" data-test="denominator-review" link type="success" :disabled="denominatorsSaving" @click="reviewDenominator(denominatorVersion(row), true)">{{ copy.parameters.review }}</ElButton><ElButton v-if="denominatorVersion(row).status === 'PENDING_REVIEW'" data-test="denominator-reject" link type="danger" :disabled="denominatorsSaving" @click="reviewDenominator(denominatorVersion(row), false)">{{ copy.parameters.reject }}</ElButton><ElButton v-if="denominatorVersion(row).status === 'APPROVED'" data-test="denominator-activate" link type="primary" :disabled="denominatorsSaving" @click="activateDenominator(denominatorVersion(row))">{{ copy.parameters.activate }}</ElButton></ElSpace></template></ElTableColumn>
        </ElTable>
        <ElEmpty v-else :description="copy.parameters.denominatorEmpty" :image-size="44" />
      </ElCard>
    </template>
  </main>
</template>

<style scoped>
.parameter-packages-page, .section-block { display: grid; gap: var(--bec-space-section); min-width: 0; }
.page-heading, .card-heading, .actions { display: flex; align-items: center; gap: var(--bec-space-group); flex-wrap: wrap; }
.page-heading, .card-heading { justify-content: space-between; }
.page-heading h1, .card-heading h2, .section-block h3 { margin: 0; font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
.page-heading p, .card-heading p, small { margin: 0; color: var(--bec-color-text-secondary); }
.building-card, .parameter-form { display: grid; gap: var(--bec-space-group); }
.building-form, .form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 3)), 1fr)); gap: var(--bec-space-group); align-items: end; }
.building-form :deep(.el-form-item), .parameter-form :deep(.el-form-item) { margin-bottom: 0; }
.wide-field { grid-column: 1 / -1; }
.section-block { padding-top: var(--bec-space-section); border-top: var(--bec-border-width) solid var(--bec-color-border); }
.actions, .review-comment { margin-top: var(--bec-space-section); }
.technical-details { margin-top: var(--bec-space-tight); color: var(--bec-color-text-secondary); }
.technical-details summary { cursor: pointer; }
.technical-details div { margin-top: var(--bec-space-tight); overflow-wrap: anywhere; }
</style>
