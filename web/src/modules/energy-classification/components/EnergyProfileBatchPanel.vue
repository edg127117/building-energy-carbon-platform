<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCard, ElEmpty, ElFormItem, ElInput, ElOption, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag } from '@/shared/ui'
import { requestErrorMessage, requestErrorStatus } from '@/shared/utils/request-error'
import {
  energyProfileBatchApi,
  type EnergyProfileBatchRequest,
  type EnergyProfileBatchRow,
  type EnergyProfileOptions,
  type EnergyProfileView,
} from '../api/energy-profile-batch'
import copy from '../locales/energy-profile-batch.zh-CN'

interface PointOption {
  value: string
  label: string
}

interface BatchDraft {
  energyType: string
  energySubtype: string
  valueSemantics: string
  reportingPeriod: string
  annualSummary: '' | 'true' | 'false'
  confirmationStatus: string
  evidenceReference: string
}

interface SelectedPointRow {
  pointId: string
  label: string
  profile: EnergyProfileView | null
}

const MAX_BATCH_SIZE = 100
const PROFILE_PAGE_SIZE = 100
const MAX_PROFILE_PAGES = 1000

const props = defineProps<{ buildingId: string; pointOptions: PointOption[] }>()
const emit = defineEmits<{ 'dirty-change': [dirty: boolean] }>()
const draft = ref<BatchDraft>(emptyDraft())
const selectedPointIds = ref<string[]>([])
const options = ref<EnergyProfileOptions | null>(null)
const profiles = ref<EnergyProfileView[]>([])
const directoryReady = ref(false)
const directoryLoading = ref(false)
const saving = ref(false)
const error = ref('')
const writeSuccessCount = ref<number | null>(null)
const preflightSignature = ref<string | null>(null)
const preflightCount = ref<number | null>(null)
let directoryGeneration = 0
let commandGeneration = 0
let activeDirectoryRequest: AbortController | null = null

const availablePointIds = computed(() => new Set(props.pointOptions.map(option => option.value)))
const selectedRows = computed<SelectedPointRow[]>(() => selectedPointIds.value
  .map(pointId => ({ pointId, label: props.pointOptions.find(option => option.value === pointId)?.label ?? pointId, profile: profileByPointId.value.get(pointId) ?? null })))
const profileByPointId = computed(() => new Map(profiles.value.map(profile => [profile.pointId, profile])))
const selectedBatchRows = computed<EnergyProfileBatchRow[]>(() => [...new Set(selectedPointIds.value)].sort().map(pointId => {
  const profile = profileByPointId.value.get(pointId)
  return { pointId, profileId: profile?.profileId ?? null, expectedRevision: profile?.configRevision ?? null }
}))
const isElectricity = computed(() => draft.value.energyType === 'ELECTRICITY')
const selectionTooLarge = computed(() => selectedBatchRows.value.length > MAX_BATCH_SIZE)
const supportedUnits = computed(() => options.value?.supportedUnits ?? [])

const requestBase = computed<Omit<EnergyProfileBatchRequest, 'preview'> | null>(() => {
  const currentOptions = options.value
  const values = draft.value
  const rows = selectedBatchRows.value
  if (!directoryReady.value || !currentOptions || !props.buildingId.trim() || !rows.length || selectionTooLarge.value) return null
  if (!currentOptions.energyTypes.includes(values.energyType)
    || !currentOptions.valueSemantics.includes(values.valueSemantics)
    || !currentOptions.reportingPeriods.includes(values.reportingPeriod)
    || !currentOptions.confirmationStatuses.includes(values.confirmationStatus)
    || !values.evidenceReference.trim() || !values.annualSummary) return null
  const subtype = isElectricity.value ? values.energySubtype : ''
  if (subtype && !currentOptions.electricitySubtypes.includes(subtype)) return null
  if (isElectricity.value && values.confirmationStatus === 'CONFIRMED' && !subtype) return null
  return {
    buildingId: props.buildingId.trim(), rows,
    energyType: values.energyType, energySubtype: subtype || null,
    valueSemantics: values.valueSemantics, reportingPeriod: values.reportingPeriod,
    annualSummary: values.annualSummary === 'true', confirmationStatus: values.confirmationStatus,
    evidenceReference: values.evidenceReference.trim(),
  }
})
const currentSignature = computed(() => requestBase.value ? JSON.stringify(requestBase.value) : null)
const preflightReady = computed(() => Boolean(currentSignature.value && preflightSignature.value === currentSignature.value))
const canPreview = computed(() => Boolean(requestBase.value) && !directoryLoading.value && !saving.value)
const canWrite = computed(() => preflightReady.value && !directoryLoading.value && !saving.value)
const dirty = computed(() => saving.value || selectedPointIds.value.length > 0 || !!draft.value.energyType
  || !!draft.value.energySubtype || !!draft.value.valueSemantics || !!draft.value.reportingPeriod
  || !!draft.value.annualSummary || draft.value.confirmationStatus !== 'PENDING_EXPERT'
  || !!draft.value.evidenceReference.trim())

// 预检只证明当时的字段、选择和读取到的修订号；其中任一变化都必须使其失效。
function clearPreflight(): void {
  preflightSignature.value = null
  preflightCount.value = null
}

function invalidatePreflight(): void {
  commandGeneration++
  clearPreflight()
  saving.value = false
}

function emptyDraft(): BatchDraft {
  return {
    energyType: '', energySubtype: '', valueSemantics: '', reportingPeriod: '', annualSummary: '',
    confirmationStatus: 'PENDING_EXPERT', evidenceReference: '',
  }
}

function label(values: Record<string, string>, value: string): string {
  return values[value] ?? value
}

function normalizeSelection(): void {
  const normalized = [...new Set(selectedPointIds.value)].filter(pointId => availablePointIds.value.has(pointId))
  if (normalized.length !== selectedPointIds.value.length || normalized.some((pointId, index) => pointId !== selectedPointIds.value[index])) {
    selectedPointIds.value = normalized
  }
}

function buildRequest(preview: boolean): EnergyProfileBatchRequest | null {
  const base = requestBase.value
  return base ? { ...base, preview } : null
}

async function readAllProfiles(buildingId: string, signal: AbortSignal): Promise<EnergyProfileView[]> {
  const first = await energyProfileBatchApi.listProfiles(buildingId, 1, PROFILE_PAGE_SIZE, undefined, signal)
  const expectedTotal = first.total
  const values = [...first.items]
  if (values.length === expectedTotal) return values
  if (!first.items.length) throw new Error(copy.errors.directoryIncomplete)

  for (let page = 2; page <= MAX_PROFILE_PAGES; page++) {
    const response = await energyProfileBatchApi.listProfiles(buildingId, page, PROFILE_PAGE_SIZE, undefined, signal)
    if (response.total !== expectedTotal) throw new Error(copy.errors.directoryChanged)
    values.push(...response.items)
    if (values.length === expectedTotal) return values
    if (!response.items.length || values.length > expectedTotal) throw new Error(copy.errors.directoryIncomplete)
  }
  throw new Error(copy.errors.directoryLimit)
}

async function loadDirectory(): Promise<void> {
  const buildingId = props.buildingId.trim()
  const owner = ++directoryGeneration
  activeDirectoryRequest?.abort()
  invalidatePreflight()
  directoryReady.value = false
  writeSuccessCount.value = null
  if (!buildingId) {
    profiles.value = []
    options.value = null
    directoryLoading.value = false
    error.value = ''
    return
  }
  const controller = new AbortController()
  activeDirectoryRequest = controller
  directoryLoading.value = true
  error.value = ''
  try {
    const [nextOptions, nextProfiles] = await Promise.all([
      energyProfileBatchApi.options(controller.signal),
      readAllProfiles(buildingId, controller.signal),
    ])
    if (owner !== directoryGeneration || buildingId !== props.buildingId.trim()) return
    options.value = nextOptions
    profiles.value = nextProfiles
    directoryReady.value = true
  } catch (reason) {
    if (owner === directoryGeneration) error.value = reason instanceof Error && reason.message ? reason.message : requestErrorMessage(reason)
  } finally {
    if (owner === directoryGeneration) {
      directoryLoading.value = false
      activeDirectoryRequest = null
    }
  }
}

function handleBatchFailure(reason: unknown): void {
  if (requestErrorStatus(reason) === 409) {
    clearPreflight()
    error.value = copy.errors.previewConflict
    return
  }
  error.value = requestErrorMessage(reason)
}

async function previewBatch(): Promise<void> {
  const request = buildRequest(true)
  const signature = currentSignature.value
  if (!request || !signature || saving.value) return
  const owner = ++commandGeneration
  saving.value = true
  error.value = ''
  writeSuccessCount.value = null
  try {
    const response = await energyProfileBatchApi.batch(request)
    if (owner !== commandGeneration || signature !== currentSignature.value) return
    if (!response.preview || response.count !== request.rows.length) {
      clearPreflight()
      error.value = copy.errors.previewMismatch
      return
    }
    preflightSignature.value = signature
    preflightCount.value = response.count
  } catch (reason) {
    if (owner === commandGeneration) handleBatchFailure(reason)
  } finally {
    if (owner === commandGeneration) saving.value = false
  }
}

async function writeBatch(): Promise<void> {
  const request = buildRequest(false)
  const signature = currentSignature.value
  if (!request || !signature || preflightSignature.value !== signature || saving.value) return
  const owner = ++commandGeneration
  saving.value = true
  error.value = ''
  try {
    const response = await energyProfileBatchApi.batch(request)
    if (owner !== commandGeneration || signature !== currentSignature.value) return
    if (response.preview || response.count !== request.rows.length) {
      clearPreflight()
      error.value = copy.errors.previewMismatch
      return
    }
    saving.value = false
    writeSuccessCount.value = response.count
    selectedPointIds.value = []
    draft.value = emptyDraft()
    invalidatePreflight()
    void loadDirectory()
  } catch (reason) {
    if (owner === commandGeneration) handleBatchFailure(reason)
  } finally {
    if (owner === commandGeneration) saving.value = false
  }
}

watch(() => props.buildingId, () => {
  selectedPointIds.value = []
  draft.value = emptyDraft()
  error.value = ''
  writeSuccessCount.value = null
  void loadDirectory()
}, { immediate: true })
watch(() => props.pointOptions.map(option => option.value).join('\u0000'), normalizeSelection)
watch(selectedPointIds, normalizeSelection, { deep: true })
watch(() => draft.value.energyType, value => {
  if (value !== 'ELECTRICITY') draft.value.energySubtype = ''
})
watch(currentSignature, (value, previous) => {
  if (value !== previous) invalidatePreflight()
})
watch(dirty, value => emit('dirty-change', value), { immediate: true })
onBeforeUnmount(() => {
  directoryGeneration++
  commandGeneration++
  activeDirectoryRequest?.abort()
})
</script>

<template>
  <section class="energy-profile-batch-panel">
    <ElCard shadow="never">
      <template #header>
        <div class="panel-heading">
          <div><h2>{{ copy.title }}</h2><p>{{ copy.description }}</p></div>
          <ElButton data-test="refresh" :disabled="!buildingId || directoryLoading || saving" :loading="directoryLoading" @click="loadDirectory">{{ copy.refresh }}</ElButton>
        </div>
      </template>

      <ElEmpty v-if="!buildingId" :description="copy.selectBuilding" :image-size="48" />
      <template v-else>
        <ElAlert v-if="error" type="error" :title="error" :closable="false" show-icon />
        <ElAlert v-if="writeSuccessCount !== null" type="success" :title="copy.wrote.replace('{count}', String(writeSuccessCount))" :closable="false" show-icon />
        <ElSkeleton v-if="directoryLoading && !directoryReady" animated :rows="6" />
        <template v-else>
          <ElFormItem :label="copy.selectPoints">
            <ElSelect v-model="selectedPointIds" data-test="point-select" multiple collapse-tags filterable :multiple-limit="MAX_BATCH_SIZE" :disabled="!directoryReady || saving || !pointOptions.length" :placeholder="copy.pointPlaceholder">
              <ElOption v-for="point in pointOptions" :key="point.value" :value="point.value" :label="point.label" />
            </ElSelect>
            <small v-if="!pointOptions.length">{{ copy.noPoints }}</small>
          </ElFormItem>
          <ElAlert v-if="selectionTooLarge" type="warning" :title="copy.selectionLimit" :closable="false" show-icon />

          <div class="form-grid">
            <ElFormItem :label="copy.fields.energyType"><ElSelect v-model="draft.energyType" data-test="energy-type" clearable :disabled="!directoryReady || saving"><ElOption v-for="value in options?.energyTypes ?? []" :key="value" :value="value" :label="label(copy.energyTypes, value)" /></ElSelect></ElFormItem>
            <ElFormItem v-if="isElectricity" :label="copy.fields.energySubtype"><ElSelect v-model="draft.energySubtype" data-test="energy-subtype" clearable :disabled="saving"><ElOption v-for="value in options?.electricitySubtypes ?? []" :key="value" :value="value" :label="label(copy.electricitySubtypes, value)" /></ElSelect></ElFormItem>
            <ElFormItem :label="copy.fields.valueSemantics"><ElSelect v-model="draft.valueSemantics" data-test="value-semantics" clearable :disabled="!directoryReady || saving"><ElOption v-for="value in options?.valueSemantics ?? []" :key="value" :value="value" :label="label(copy.valueSemantics, value)" /></ElSelect></ElFormItem>
            <ElFormItem :label="copy.fields.reportingPeriod"><ElSelect v-model="draft.reportingPeriod" data-test="reporting-period" clearable :disabled="!directoryReady || saving"><ElOption v-for="value in options?.reportingPeriods ?? []" :key="value" :value="value" :label="label(copy.reportingPeriods, value)" /></ElSelect></ElFormItem>
            <ElFormItem :label="copy.fields.annualSummary"><ElSelect v-model="draft.annualSummary" data-test="annual-summary" clearable :disabled="!directoryReady || saving"><ElOption value="true" :label="copy.yes" /><ElOption value="false" :label="copy.no" /></ElSelect></ElFormItem>
            <ElFormItem :label="copy.fields.confirmationStatus"><ElSelect v-model="draft.confirmationStatus" data-test="confirmation-status" :disabled="!directoryReady || saving"><ElOption v-for="value in options?.confirmationStatuses ?? []" :key="value" :value="value" :label="label(copy.confirmationStatuses, value)" /></ElSelect><small>{{ copy.defaults.pendingExpert }}</small></ElFormItem>
            <ElFormItem class="evidence-field" :label="copy.fields.evidenceReference"><ElInput v-model="draft.evidenceReference" data-test="evidence" type="textarea" :maxlength="500" show-word-limit :disabled="!directoryReady || saving" /></ElFormItem>
          </div>

          <section class="unit-note">
            <strong>{{ copy.supportedUnits }}</strong><span>{{ supportedUnits.join(' · ') || '—' }}</span><small>{{ copy.unitHint }}</small>
          </section>

          <section class="selected-section">
            <div class="section-heading"><div><h3>{{ copy.selected }}</h3><p>{{ copy.writing }}</p></div></div>
            <ElEmpty v-if="!selectedRows.length" :description="copy.noSelection" :image-size="40" />
            <ElTable v-else :data="selectedRows" size="small">
              <ElTableColumn prop="label" :label="copy.selectPoints" min-width="220" />
              <ElTableColumn :label="copy.existingProfile" min-width="150"><template #default="{ row }"><ElTag :type="row.profile ? 'info' : 'success'">{{ row.profile ? copy.existingProfile : copy.newProfile }}</ElTag></template></ElTableColumn>
              <ElTableColumn :label="copy.revision" width="120"><template #default="{ row }">{{ row.profile?.configRevision ?? '—' }}</template></ElTableColumn>
              <ElTableColumn :label="copy.profileId" min-width="180"><template #default="{ row }"><code>{{ row.profile?.profileId ?? '—' }}</code></template></ElTableColumn>
            </ElTable>
          </section>

          <div class="batch-actions">
            <ElButton data-test="preview" type="primary" :disabled="!canPreview" :loading="saving" @click="previewBatch">{{ copy.preview }}</ElButton>
            <ElButton data-test="write" type="danger" :disabled="!canWrite" :loading="saving" @click="writeBatch">{{ copy.write }}</ElButton>
          </div>
          <ElAlert v-if="preflightReady" type="success" :title="copy.previewReady" :closable="false" show-icon />
          <ElAlert v-else-if="selectedRows.length" type="info" :title="copy.previewRequired" :closable="false" show-icon />
        </template>
      </template>
    </ElCard>
  </section>
</template>

<style scoped>
.energy-profile-batch-panel, .selected-section { display: grid; gap: var(--bec-space-section); min-width: 0; }
.panel-heading, .section-heading, .batch-actions { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
.panel-heading h2, .section-heading h3 { margin: 0; font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
.panel-heading p, .section-heading p, small { margin: 0; color: var(--bec-color-text-secondary); }
.form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 3)), 1fr)); gap: var(--bec-space-group); margin-top: var(--bec-space-section); }
.form-grid :deep(.el-form-item) { margin-bottom: 0; }
.evidence-field { grid-column: 1 / -1; }
.unit-note { display: grid; gap: var(--bec-space-tight); padding: var(--bec-space-group); border-left: var(--bec-border-width) solid var(--bec-color-primary); background: var(--bec-color-surface-secondary); }
.selected-section { padding-top: var(--bec-space-section); border-top: var(--bec-border-width) solid var(--bec-color-border); }
.batch-actions { justify-content: flex-start; }
code { overflow-wrap: anywhere; color: var(--bec-color-text-secondary); }
</style>
