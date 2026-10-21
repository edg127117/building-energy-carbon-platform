<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCard, ElDialog, ElEmpty, ElFormItem, ElInput, ElOption, ElRadio, ElRadioGroup, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag } from '@/shared/ui'
import { t } from '@/locales'
import { requestErrorMessage, requestErrorStatus } from '@/shared/utils/request-error'
import {
  carbonResultsApi,
  type CalculationBatch,
  type CalculationDetail,
  type CalculationItem,
  type CarbonClassificationReport,
  type ManualRecalculationRequest,
  type RecalculationBatch,
  type RunCalculationRequest,
} from '../api/carbon-results'
import CarbonResultCharts from './CarbonResultCharts.vue'
import copy from '../locales/carbon-results.zh-CN'

type PeriodType = RunCalculationRequest['periodType']
type RecalculationNature = ManualRecalculationRequest['resultNature']
type ReviewDecision = 'approve' | 'reject'

interface CalculationDraft {
  periodType: PeriodType | ''
  startInclusive: string
  endExclusive: string
  timezoneId: string
  resultNature: 'FORMAL' | ''
  idempotencyKey: string
}

interface RecalculationDraft {
  accountingYear: string
  resultNature: RecalculationNature | ''
  reason: string
  organizationBoundary: string
}

const props = defineProps<{ buildingId: string }>()
const emit = defineEmits<{ 'dirty-change': [dirty: boolean] }>()

const batches = ref<CalculationBatch[]>([])
const selectedBatchId = ref('')
const calculationDetail = ref<CalculationDetail | null>(null)
const report = ref<CarbonClassificationReport | null>(null)
const recalculations = ref<RecalculationBatch[]>([])
const calculationDraft = ref<CalculationDraft>(emptyCalculationDraft())
const recalculationDraft = ref<RecalculationDraft>(emptyRecalculationDraft())
const batchesLoading = ref(false)
const recalculationsLoading = ref(false)
const detailLoading = ref(false)
const reportLoading = ref(false)
const calculationSaving = ref(false)
const recalculationSaving = ref(false)
const reviewSaving = ref(false)
const evidenceLoading = ref(false)
const batchesError = ref('')
const recalculationsError = ref('')
const detailError = ref('')
const reportError = ref('')
const commandError = ref('')
const calculationNotice = ref('')
const recalculationNotice = ref('')
const evidence = ref<unknown | null>(null)
const evidenceError = ref('')
const evidenceOpen = ref(false)
const reviewTarget = ref<RecalculationBatch | null>(null)
const reviewDecision = ref<ReviewDecision | ''>('')
const reviewComment = ref('')
let buildingGeneration = 0
let listGeneration = 0
let detailGeneration = 0
let reportGeneration = 0
let commandGeneration = 0
let evidenceGeneration = 0

const periodTypes: PeriodType[] = ['MONTH', 'QUARTER', 'YEAR']
const recalculationNatures: RecalculationNature[] = ['FORMAL', 'DEVELOPMENT_SIMULATION']
const approveDecision: ReviewDecision = 'approve'
const rejectDecision: ReviewDecision = 'reject'
const commandBusy = computed(() => calculationSaving.value || recalculationSaving.value || reviewSaving.value)
const excludedSourceCount = computed(() => report.value?.excludedSources.length ?? 0)
const pendingRecalculations = computed(() => recalculations.value.filter(batch => batch.status === 'PENDING_APPROVAL' && batch.resultNature === 'FORMAL'))
const calculationRequest = computed<RunCalculationRequest | null>(() => {
  const buildingId = props.buildingId.trim()
  const draft = calculationDraft.value
  if (!buildingId || !draft.periodType || !draft.resultNature || !draft.timezoneId.trim() || !draft.idempotencyKey.trim()
    || !isExplicitInstant(draft.startInclusive) || !isExplicitInstant(draft.endExclusive)) return null
  if (Date.parse(draft.endExclusive) <= Date.parse(draft.startInclusive)) return null
  return {
    buildingId,
    periodType: draft.periodType,
    startInclusive: draft.startInclusive.trim(),
    endExclusive: draft.endExclusive.trim(),
    timezoneId: draft.timezoneId.trim(),
    resultNature: draft.resultNature,
    idempotencyKey: draft.idempotencyKey.trim(),
  }
})
const calculationValidation = computed(() => {
  const draft = calculationDraft.value
  if (!draft.periodType || !draft.resultNature || !draft.timezoneId.trim() || !draft.idempotencyKey.trim()
    || !draft.startInclusive.trim() || !draft.endExclusive.trim()) return copy.calculate.required
  if (!isExplicitInstant(draft.startInclusive) || !isExplicitInstant(draft.endExclusive)
    || Date.parse(draft.endExclusive) <= Date.parse(draft.startInclusive)) return copy.calculate.invalidPeriod
  return ''
})
const recalculationRequest = computed<ManualRecalculationRequest | null>(() => {
  const buildingId = props.buildingId.trim()
  const draft = recalculationDraft.value
  const year = Number(draft.accountingYear)
  if (!buildingId || !draft.resultNature || !draft.reason.trim() || !draft.organizationBoundary.trim()
    || !Number.isInteger(year) || year < 2000 || year > 2200) return null
  return {
    buildingId,
    accountingYear: year,
    resultNature: draft.resultNature,
    reason: draft.reason.trim(),
    organizationBoundary: draft.organizationBoundary.trim(),
  }
})
const dirty = computed(() => commandBusy.value || reviewTarget.value !== null
  || !!calculationDraft.value.periodType || !!calculationDraft.value.startInclusive.trim()
  || !!calculationDraft.value.endExclusive.trim() || !!calculationDraft.value.timezoneId.trim()
  || !!calculationDraft.value.resultNature || !!String(recalculationDraft.value.accountingYear).trim()
  || !!recalculationDraft.value.resultNature || !!recalculationDraft.value.reason.trim()
  || !!recalculationDraft.value.organizationBoundary.trim())

watch(dirty, value => emit('dirty-change', value), { immediate: true })
watch(() => props.buildingId, value => { void loadBuilding(value) }, { immediate: true })

function nextIdempotencyKey(): string {
  return globalThis.crypto?.randomUUID?.() ?? `carbon-calculation-${Date.now()}`
}

function emptyCalculationDraft(): CalculationDraft {
  return { periodType: '', startInclusive: '', endExclusive: '', timezoneId: '', resultNature: '', idempotencyKey: nextIdempotencyKey() }
}

function emptyRecalculationDraft(): RecalculationDraft {
  return { accountingYear: '', resultNature: '', reason: '', organizationBoundary: '' }
}

function isExplicitInstant(value: string): boolean {
  const normalized = value.trim()
  return /(?:Z|[+-]\d{2}:\d{2})$/i.test(normalized) && !Number.isNaN(Date.parse(normalized))
}

function currentBuilding(owner: number, buildingId: string): boolean {
  return owner === buildingGeneration && buildingId === props.buildingId.trim()
}

async function loadBuilding(value: string): Promise<void> {
  const buildingId = value.trim()
  const owner = ++buildingGeneration
  detailGeneration++
  reportGeneration++
  commandGeneration++
  evidenceGeneration++
  batches.value = []
  selectedBatchId.value = ''
  calculationDetail.value = null
  report.value = null
  recalculations.value = []
  calculationDraft.value = emptyCalculationDraft()
  recalculationDraft.value = emptyRecalculationDraft()
  batchesError.value = ''
  recalculationsError.value = ''
  detailError.value = ''
  reportError.value = ''
  commandError.value = ''
  calculationNotice.value = ''
  recalculationNotice.value = ''
  evidence.value = null
  evidenceError.value = ''
  evidenceOpen.value = false
  reviewTarget.value = null
  reviewDecision.value = ''
  reviewComment.value = ''
  if (!buildingId) {
    batchesLoading.value = false
    recalculationsLoading.value = false
    detailLoading.value = false
    reportLoading.value = false
    return
  }
  await loadLists(buildingId, owner)
}

async function loadLists(buildingId = props.buildingId.trim(), buildingOwner = buildingGeneration): Promise<void> {
  if (!buildingId) return
  const owner = ++listGeneration
  batchesLoading.value = true
  recalculationsLoading.value = true
  batchesError.value = ''
  recalculationsError.value = ''
  const [batchesResult, recalculationResult] = await Promise.allSettled([
    carbonResultsApi.listBatches(buildingId),
    carbonResultsApi.listRecalculations(buildingId),
  ])
  if (!currentBuilding(buildingOwner, buildingId) || owner !== listGeneration) return
  if (batchesResult.status === 'fulfilled') {
    // 列表延迟时保留刚由命令端点返回的固定批次，避免页面短暂丢失这次后端结果。
    const current = calculationDetail.value
    const next = batchesResult.value
    batches.value = current?.buildingId === buildingId && !next.some(batch => batch.batchId === current.calculationBatchId)
      ? [batchFromDetail(current), ...next]
      : next
  }
  else batchesError.value = requestErrorMessage(batchesResult.reason)
  if (recalculationResult.status === 'fulfilled') recalculations.value = recalculationResult.value
  else recalculationsError.value = requestErrorStatus(recalculationResult.reason) === 409
    ? copy.recalculation.listLimit
    : requestErrorMessage(recalculationResult.reason)
  batchesLoading.value = false
  recalculationsLoading.value = false
}

function refreshLists(): void {
  void loadLists()
}

function selectBatch(batchId: string): void {
  selectedBatchId.value = batchId
  calculationDetail.value = null
  report.value = null
  detailError.value = ''
  reportError.value = ''
  const buildingId = props.buildingId.trim()
  if (buildingId && batchId) void loadBatch(buildingId, batchId, buildingGeneration)
}

async function loadBatch(buildingId: string, batchId: string, buildingOwner: number): Promise<void> {
  const owner = ++detailGeneration
  detailLoading.value = true
  detailError.value = ''
  const detailResult = await carbonResultsApi.getCalculation(batchId).then(
    value => ({ status: 'fulfilled' as const, value }),
    reason => ({ status: 'rejected' as const, reason }),
  )
  if (owner !== detailGeneration || !currentBuilding(buildingOwner, buildingId) || batchId !== selectedBatchId.value) return
  if (detailResult.status === 'fulfilled') calculationDetail.value = detailResult.value
  else detailError.value = requestErrorMessage(detailResult.reason)
  detailLoading.value = false
  void loadClassificationReport(buildingId, batchId, buildingOwner)
}

async function loadClassificationReport(buildingId: string, batchId: string, buildingOwner: number): Promise<void> {
  const owner = ++reportGeneration
  reportLoading.value = true
  reportError.value = ''
  try {
    const value = await carbonResultsApi.getClassificationReport(batchId)
    if (owner === reportGeneration && currentBuilding(buildingOwner, buildingId) && batchId === selectedBatchId.value) report.value = value
  } catch (reason) {
    if (owner === reportGeneration && currentBuilding(buildingOwner, buildingId) && batchId === selectedBatchId.value) reportError.value = requestErrorMessage(reason)
  } finally {
    if (owner === reportGeneration) reportLoading.value = false
  }
}

function batchFromDetail(value: CalculationDetail): CalculationBatch {
  return {
    batchId: value.calculationBatchId,
    buildingId: value.buildingId,
    status: value.status,
    periodType: value.periodType,
    periodStart: value.startInclusive,
    periodEnd: value.endExclusive,
    timezoneId: value.timezoneId,
    publicationStatus: value.publicationStatus,
    resultNature: value.resultNature,
    idempotencyKey: value.idempotencyKey,
    completedAt: value.completedAt,
    safeErrorCode: value.safeErrorCode,
    safeErrorMessage: value.safeErrorMessage,
    createdAt: null,
  }
}

function upsertBatchFromDetail(value: CalculationDetail): void {
  const next = batchFromDetail(value)
  const index = batches.value.findIndex(batch => batch.batchId === next.batchId)
  if (index < 0) batches.value = [next, ...batches.value]
  else batches.value = batches.value.map(batch => batch.batchId === next.batchId ? { ...batch, ...next } : batch)
}

async function runCalculation(): Promise<void> {
  const request = calculationRequest.value
  const buildingId = props.buildingId.trim()
  if (!request || calculationSaving.value) {
    commandError.value = calculationValidation.value
    return
  }
  const owner = ++commandGeneration
  calculationSaving.value = true
  commandError.value = ''
  calculationNotice.value = ''
  try {
    const result = await carbonResultsApi.runCalculation(request)
    if (owner !== commandGeneration || buildingId !== props.buildingId.trim()) return
    selectedBatchId.value = result.calculationBatchId
    calculationDetail.value = result
    report.value = null
    reportError.value = ''
    upsertBatchFromDetail(result)
    calculationNotice.value = result.status === 'CALCULATING'
      ? copy.calculate.calculating
      : result.failures.length || result.status === 'COMPLETED_INCOMPLETE'
        ? copy.calculate.incomplete
        : copy.calculate.submitted.replace('{batchId}', result.calculationBatchId)
    void loadClassificationReport(buildingId, result.calculationBatchId, buildingGeneration)
    void loadLists(buildingId, buildingGeneration)
  } catch (reason) {
    if (owner === commandGeneration && buildingId === props.buildingId.trim()) commandError.value = requestErrorMessage(reason)
  } finally {
    if (owner === commandGeneration) calculationSaving.value = false
  }
}

async function openEvidence(item: CalculationItem): Promise<void> {
  const batchId = calculationDetail.value?.calculationBatchId
  const buildingId = props.buildingId.trim()
  if (!batchId || evidenceLoading.value) return
  const owner = ++evidenceGeneration
  evidenceOpen.value = true
  evidence.value = null
  evidenceError.value = ''
  evidenceLoading.value = true
  try {
    const value = await carbonResultsApi.getEvidence(batchId, item.calculationItemId)
    if (owner === evidenceGeneration && batchId === calculationDetail.value?.calculationBatchId && buildingId === props.buildingId.trim()) evidence.value = value
  } catch (reason) {
    if (owner === evidenceGeneration && buildingId === props.buildingId.trim()) evidenceError.value = requestErrorMessage(reason)
  } finally {
    if (owner === evidenceGeneration) evidenceLoading.value = false
  }
}

function closeEvidence(): void {
  evidenceGeneration++
  evidenceOpen.value = false
  evidence.value = null
  evidenceError.value = ''
}

function formatEvidence(value: unknown): string {
  if (value == null) return ''
  if (typeof value === 'string') return value
  try { return JSON.stringify(value, null, 2) } catch { return String(value) }
}

function displayExcludedSource(source: unknown): string {
  if (typeof source === 'string') return source
  try { return JSON.stringify(source) ?? String(source) } catch { return String(source) }
}

async function submitManualRecalculation(): Promise<void> {
  const request = recalculationRequest.value
  const buildingId = props.buildingId.trim()
  if (!request || recalculationSaving.value) {
    commandError.value = copy.recalculation.required
    return
  }
  const owner = ++commandGeneration
  recalculationSaving.value = true
  commandError.value = ''
  recalculationNotice.value = ''
  try {
    const accepted = await carbonResultsApi.submitManualRecalculation(request)
    if (owner !== commandGeneration || buildingId !== props.buildingId.trim()) return
    recalculationDraft.value = emptyRecalculationDraft()
    recalculationNotice.value = copy.recalculation.accepted
      .replace('{changeId}', accepted.changeId)
      .replace('{status}', accepted.status)
    void loadLists(buildingId, buildingGeneration)
  } catch (reason) {
    if (owner === commandGeneration && buildingId === props.buildingId.trim()) commandError.value = requestErrorMessage(reason)
  } finally {
    if (owner === commandGeneration) recalculationSaving.value = false
  }
}

function openRecalculationReview(batch: RecalculationBatch): void {
  reviewTarget.value = batch
  reviewDecision.value = ''
  reviewComment.value = ''
  commandError.value = ''
}

function closeRecalculationReview(force = false): void {
  if (reviewSaving.value && !force) return
  reviewTarget.value = null
  reviewDecision.value = ''
  reviewComment.value = ''
}

function upsertRecalculation(value: RecalculationBatch): void {
  const index = recalculations.value.findIndex(batch => batch.recalculationBatchId === value.recalculationBatchId)
  if (index < 0) recalculations.value = [value, ...recalculations.value]
  else recalculations.value = recalculations.value.map(batch => batch.recalculationBatchId === value.recalculationBatchId ? value : batch)
}

async function submitRecalculationReview(): Promise<void> {
  const target = reviewTarget.value
  const decision = reviewDecision.value
  const comment = reviewComment.value.trim()
  const buildingId = props.buildingId.trim()
  if (!target || !decision || !comment || reviewSaving.value) {
    commandError.value = copy.recalculation.reviewHint
    return
  }
  const owner = ++commandGeneration
  reviewSaving.value = true
  commandError.value = ''
  try {
    const request = { reviewComment: comment }
    const result = decision === 'approve'
      ? await carbonResultsApi.approveRecalculation(target.recalculationBatchId, request)
      : await carbonResultsApi.rejectRecalculation(target.recalculationBatchId, request)
    if (owner !== commandGeneration || buildingId !== props.buildingId.trim()) return
    upsertRecalculation(result)
    closeRecalculationReview(true)
  } catch (reason) {
    if (owner === commandGeneration && buildingId === props.buildingId.trim()) commandError.value = requestErrorMessage(reason)
  } finally {
    if (owner === commandGeneration) reviewSaving.value = false
  }
}
</script>

<template>
  <section class="carbon-results-panel">
    <ElCard shadow="never">
      <template #header>
        <div class="panel-heading">
          <div><h2>{{ copy.title }}</h2><p>{{ copy.description }}</p></div>
          <ElButton data-test="carbon-results-refresh" :disabled="batchesLoading || recalculationsLoading || commandBusy" :loading="batchesLoading || recalculationsLoading" @click="refreshLists">{{ copy.refresh }}</ElButton>
        </div>
      </template>
      <ElEmpty v-if="!buildingId" :description="copy.selectBuilding" :image-size="48" />
      <template v-else>
        <ElAlert v-if="commandError" class="panel-alert" :title="commandError" type="error" :closable="false" show-icon />
        <ElAlert v-if="calculationNotice" class="panel-alert" :title="calculationNotice" type="info" :closable="false" show-icon @close="calculationNotice = ''" />
        <ElAlert v-if="recalculationNotice" class="panel-alert" :title="recalculationNotice" type="success" :closable="false" show-icon @close="recalculationNotice = ''" />

        <section class="result-section" :aria-label="copy.calculate.ariaLabel">
          <div class="section-heading"><div><h3>{{ copy.calculate.title }}</h3><p>{{ copy.calculate.description }}</p></div></div>
          <div class="form-grid">
            <ElFormItem :label="copy.calculate.periodType"><ElSelect v-model="calculationDraft.periodType" data-test="calculation-period-type" clearable :disabled="commandBusy"><ElOption v-for="periodType in periodTypes" :key="periodType" :value="periodType" :label="copy.calculate[periodType]" /></ElSelect></ElFormItem>
            <ElFormItem :label="copy.calculate.resultNature"><ElSelect v-model="calculationDraft.resultNature" data-test="calculation-result-nature" clearable :disabled="commandBusy"><ElOption value="FORMAL" :label="copy.calculate.FORMAL" /></ElSelect></ElFormItem>
            <ElFormItem :label="copy.calculate.startInclusive"><ElInput v-model="calculationDraft.startInclusive" data-test="calculation-start" :maxlength="64" :disabled="commandBusy" :placeholder="copy.calculate.startPlaceholder" /></ElFormItem>
            <ElFormItem :label="copy.calculate.endExclusive"><ElInput v-model="calculationDraft.endExclusive" data-test="calculation-end" :maxlength="64" :disabled="commandBusy" :placeholder="copy.calculate.endPlaceholder" /></ElFormItem>
            <ElFormItem :label="copy.calculate.timezoneId"><ElInput v-model="calculationDraft.timezoneId" data-test="calculation-timezone" :maxlength="64" :disabled="commandBusy" :placeholder="copy.calculate.timezonePlaceholder" /></ElFormItem>
            <ElFormItem :label="copy.calculate.idempotencyKey"><ElInput v-model="calculationDraft.idempotencyKey" data-test="calculation-idempotency-key" :maxlength="100" :disabled="commandBusy" /></ElFormItem>
          </div>
          <ElAlert v-if="calculationValidation" type="info" :title="calculationValidation" :closable="false" show-icon />
          <ElButton data-test="calculation-submit" type="primary" :disabled="!calculationRequest || commandBusy" :loading="calculationSaving" @click="runCalculation">{{ copy.calculate.submit }}</ElButton>
        </section>

        <section class="result-section" :aria-label="copy.batches.ariaLabel">
          <div class="section-heading"><div><h3>{{ copy.batches.title }}</h3><p>{{ copy.batches.selectHint }}</p></div></div>
          <ElAlert v-if="batchesError" type="error" :title="batchesError" :closable="false" show-icon />
          <ElSkeleton v-else-if="batchesLoading" animated :rows="4" />
          <ElEmpty v-else-if="!batches.length" :description="copy.batches.empty" :image-size="44" />
          <template v-else>
            <ElFormItem :label="copy.batches.select" class="batch-picker"><ElSelect :model-value="selectedBatchId" data-test="batch-select" filterable @update:model-value="selectBatch"><ElOption v-for="batch in batches" :key="batch.batchId" :value="batch.batchId" :label="`${batch.batchId} · ${batch.periodType} · ${batch.periodStart} – ${batch.periodEnd}`" /></ElSelect></ElFormItem>
            <ElEmpty v-if="!selectedBatchId" :description="copy.batches.selectHint" :image-size="40" />
            <template v-else>
              <ElAlert v-if="detailError" type="error" :title="detailError" :closable="false" show-icon />
              <ElSkeleton v-else-if="detailLoading" animated :rows="6" />
              <template v-else-if="calculationDetail">
                <div class="batch-meta">
                  <ElTag>{{ copy.batches.status }} {{ calculationDetail.status }}</ElTag>
                  <ElTag>{{ copy.batches.publicationStatus }} {{ calculationDetail.publicationStatus }}</ElTag>
                  <ElTag>{{ copy.batches.resultNature }} {{ calculationDetail.resultNature }}</ElTag>
                  <ElTag>{{ copy.batches.timezone }} {{ calculationDetail.timezoneId }}</ElTag>
                </div>
                <div class="fixed-meta">
                  <span><strong>{{ copy.batches.idempotencyKey }}</strong> <code>{{ calculationDetail.idempotencyKey }}</code></span>
                  <span><strong>{{ copy.batches.startedAt }}</strong> {{ calculationDetail.startedAt ?? copy.unavailable }}</span>
                  <span><strong>{{ copy.batches.completedAt }}</strong> {{ calculationDetail.completedAt ?? copy.unavailable }}</span>
                </div>
                <ElAlert v-if="calculationDetail.safeErrorMessage" type="warning" :title="`${copy.batches.safeError}：${calculationDetail.safeErrorCode ?? copy.unavailable} · ${calculationDetail.safeErrorMessage}`" :closable="false" show-icon />
                <section class="detail-section">
                  <h4>{{ copy.batches.detail }}</h4>
                  <ElEmpty v-if="!calculationDetail.items.length" :description="copy.batches.noItems" :image-size="38" />
                  <ElTable v-else :data="calculationDetail.items" row-key="calculationItemId">
                    <ElTableColumn prop="calculationItemId" :label="copy.batches.itemId" min-width="160" />
                    <ElTableColumn prop="snapshotId" :label="copy.batches.snapshotId" min-width="150" />
                    <ElTableColumn prop="energyItemCode" :label="copy.batches.energyItem" min-width="140" />
                    <ElTableColumn prop="scopeType" :label="copy.batches.scope" min-width="120" />
                    <ElTableColumn :label="copy.batches.activity" min-width="140"><template #default="{ row }">{{ row.activityQuantity }} {{ row.activityUnitCode }}</template></ElTableColumn>
                    <ElTableColumn prop="emissionKgCo2e" :label="copy.batches.emission" min-width="160" />
                    <ElTableColumn :label="copy.batches.evidence" width="150" fixed="right"><template #default="{ row }"><ElButton :data-test="`evidence-${row.calculationItemId}`" link :disabled="evidenceLoading" @click="openEvidence(row as CalculationItem)">{{ copy.batches.evidence }}</ElButton></template></ElTableColumn>
                  </ElTable>
                </section>
                <section class="detail-section">
                  <h4>{{ copy.batches.failures }}</h4>
                  <ElEmpty v-if="!calculationDetail.failures.length" :description="copy.batches.noFailures" :image-size="38" />
                  <ElTable v-else :data="calculationDetail.failures" row-key="snapshotId">
                    <ElTableColumn prop="snapshotId" :label="copy.batches.snapshotId" min-width="160" />
                    <ElTableColumn prop="energyItemCode" :label="copy.batches.energyItem" min-width="140" />
                    <ElTableColumn prop="errorCode" :label="copy.batches.failureCode" min-width="180" />
                    <ElTableColumn prop="errorMessage" :label="copy.batches.failureMessage" min-width="260" />
                  </ElTable>
                </section>
              </template>
              <ElEmpty v-else :description="copy.batches.noItems" :image-size="40" />

              <section class="detail-section">
                <h4>{{ copy.batches.report }}</h4>
                <ElAlert v-if="reportError" type="error" :title="reportError" :closable="false" show-icon />
                <ElSkeleton v-else-if="reportLoading" animated :rows="4" />
                <template v-else-if="report">
                  <div class="report-total"><span>{{ t('energyClassification.carbonResults.total') }}</span><strong>{{ report.totalKgCo2e ?? copy.unavailable }} {{ t('energyClassification.carbonResults.unit') }}</strong><span>{{ t('energyClassification.carbonResults.classification') }} {{ report.classificationComplete ? t('energyClassification.carbonResults.complete') : t('energyClassification.carbonResults.incomplete') }}</span></div>
                  <CarbonResultCharts :report="report" />
                  <div class="report-section"><h5>{{ t('energyClassification.carbonResults.nodes') }}</h5><ElTable v-if="report.nodes.length" :data="report.nodes"><ElTableColumn prop="schemeCode" :label="t('energyClassification.carbonResults.schemeCode')" min-width="130" /><ElTableColumn prop="classificationVersionId" :label="t('energyClassification.carbonProject.classificationVersion')" min-width="180" /><ElTableColumn prop="nodeCode" :label="t('energyClassification.carbonResults.nodeCode')" min-width="130" /><ElTableColumn prop="nodeName" :label="t('energyClassification.carbonResults.nodeName')" min-width="150" /><ElTableColumn prop="energyItemCode" :label="t('energyClassification.fields.energyItemCode')" min-width="150" /><ElTableColumn prop="activityQuantity" :label="t('energyClassification.carbonResults.activityQuantity')" min-width="150" /><ElTableColumn prop="emissionKgCo2e" :label="t('energyClassification.carbonResults.emission')" min-width="160" /></ElTable><ElEmpty v-else :description="t('energyClassification.carbonResults.noNodes')" :image-size="38" /></div>
                  <div class="report-section"><h5>{{ t('energyClassification.carbonResults.periods') }}</h5><ElTable v-if="report.periods.length" :data="report.periods"><ElTableColumn prop="startInclusive" :label="t('energyClassification.carbonResults.periodStart')" min-width="200" /><ElTableColumn prop="scopeType" :label="t('energyClassification.carbonResults.scopeType')" min-width="150" /><ElTableColumn prop="emissionKgCo2e" :label="t('energyClassification.carbonResults.emission')" min-width="170" /></ElTable><ElEmpty v-else :description="t('energyClassification.carbonResults.noPeriods')" :image-size="38" /></div>
                  <div class="report-section"><h5>{{ t('energyClassification.carbonResults.warnings') }}</h5><ul v-if="report.warnings.length" class="plain-list"><li v-for="(warning, index) in report.warnings" :key="index">{{ warning }}</li></ul><ElEmpty v-else :description="t('energyClassification.carbonResults.noWarnings')" :image-size="38" /></div>
                  <div class="report-section"><h5>{{ t('energyClassification.carbonResults.excludedSourcesCount', { count: excludedSourceCount }) }}</h5><ul v-if="report.excludedSources.length" class="plain-list"><li v-for="(source, index) in report.excludedSources" :key="index">{{ displayExcludedSource(source) }}</li></ul><ElEmpty v-else :description="t('energyClassification.carbonResults.noExcludedSources')" :image-size="38" /></div>
                </template>
                <ElEmpty v-else :description="copy.batches.reportUnavailable" :image-size="38" />
              </section>
            </template>
          </template>
        </section>

        <section class="result-section" :aria-label="copy.recalculation.ariaLabel">
          <div class="section-heading"><div><h3>{{ copy.recalculation.title }}</h3><p>{{ copy.recalculation.description }}</p></div></div>
          <div class="form-grid">
            <ElFormItem :label="copy.recalculation.accountingYear"><input v-model="recalculationDraft.accountingYear" data-test="recalculation-year" class="native-input" type="number" min="2000" max="2200" step="1" :disabled="commandBusy" :placeholder="copy.recalculation.yearPlaceholder"></ElFormItem>
            <ElFormItem :label="copy.recalculation.resultNature"><ElSelect v-model="recalculationDraft.resultNature" data-test="recalculation-result-nature" clearable :disabled="commandBusy"><ElOption v-for="nature in recalculationNatures" :key="nature" :value="nature" :label="nature" /></ElSelect></ElFormItem>
            <ElFormItem :label="copy.recalculation.organizationBoundary"><ElInput v-model="recalculationDraft.organizationBoundary" data-test="recalculation-boundary" :maxlength="100" :disabled="commandBusy" :placeholder="copy.recalculation.boundaryPlaceholder" /></ElFormItem>
            <ElFormItem class="wide-field" :label="copy.recalculation.reason"><ElInput v-model="recalculationDraft.reason" data-test="recalculation-reason" type="textarea" :maxlength="500" show-word-limit :disabled="commandBusy" :placeholder="copy.recalculation.reasonPlaceholder" /></ElFormItem>
          </div>
          <ElButton data-test="recalculation-submit" type="primary" :disabled="!recalculationRequest || commandBusy" :loading="recalculationSaving" @click="submitManualRecalculation">{{ copy.recalculation.submit }}</ElButton>

          <section class="detail-section">
            <h4>{{ copy.recalculation.queue }}</h4>
            <ElAlert v-if="recalculationsError" type="error" :title="recalculationsError" :closable="false" show-icon />
            <ElSkeleton v-else-if="recalculationsLoading" animated :rows="4" />
            <ElEmpty v-else-if="!pendingRecalculations.length" :description="copy.recalculation.queueEmpty" :image-size="38" />
            <div v-else class="recalculation-list">
              <article v-for="batch in pendingRecalculations" :key="batch.recalculationBatchId" class="recalculation-row">
                <div class="recalculation-heading"><div><strong>{{ copy.recalculation.batchId }} <code>{{ batch.recalculationBatchId }}</code></strong><span>{{ copy.recalculation.triggerReason }} {{ batch.triggerReason }}</span><span>{{ copy.recalculation.itemCounts }} {{ [batch.itemCount, batch.eligibleItemCount].join(' / ') }}</span></div><ElButton :data-test="`recalculation-review-${batch.recalculationBatchId}`" :disabled="commandBusy" @click="openRecalculationReview(batch)">{{ copy.recalculation.review }}</ElButton></div>
                <details><summary>{{ copy.recalculation.detail }}</summary><ul v-if="batch.items.length" class="plain-list"><li v-for="item in batch.items" :key="item.recalculationItemId">{{ [item.recalculationItemId, item.buildingId, item.accountingYear, item.status, `${copy.recalculation.oldBatch} ${item.oldCalculationBatchId}`, `${copy.recalculation.candidateBatch} ${item.candidateCalculationBatchId ?? copy.unavailable}`].join(' · ') }}</li></ul><p v-else>{{ copy.recalculation.noItems }}</p></details>
              </article>
            </div>
          </section>

          <section class="detail-section">
            <h4>{{ copy.recalculation.all }}</h4>
            <ElEmpty v-if="!recalculationsLoading && !recalculationsError && !recalculations.length" :description="copy.recalculation.allEmpty" :image-size="38" />
            <ElTable v-else-if="recalculations.length" :data="recalculations" row-key="recalculationBatchId">
              <ElTableColumn prop="recalculationBatchId" :label="copy.recalculation.batchId" min-width="190" />
              <ElTableColumn prop="status" :label="copy.batches.status" min-width="150"><template #default="{ row }"><ElTag>{{ row.status }}</ElTag></template></ElTableColumn>
              <ElTableColumn prop="resultNature" :label="copy.recalculation.resultNature" min-width="170" />
              <ElTableColumn prop="triggerReason" :label="copy.recalculation.triggerReason" min-width="180" />
              <ElTableColumn :label="copy.recalculation.itemCounts" min-width="160"><template #default="{ row }">{{ [row.itemCount, row.eligibleItemCount].join(' / ') }}</template></ElTableColumn>
            </ElTable>
          </section>
        </section>
      </template>
    </ElCard>

    <ElDialog :model-value="evidenceOpen" data-test="evidence-dialog" :title="copy.batches.evidenceTitle" width="min(92vw, 760px)" @close="closeEvidence">
      <ElSkeleton v-if="evidenceLoading" animated :rows="6" />
      <ElAlert v-else-if="evidenceError" type="error" :title="evidenceError" :closable="false" show-icon />
      <ElEmpty v-else-if="evidence === null" :description="copy.batches.evidenceEmpty" :image-size="40" />
      <pre v-else class="evidence-content">{{ formatEvidence(evidence) }}</pre>
      <template #footer><ElButton data-test="evidence-close" @click="closeEvidence">{{ copy.batches.close }}</ElButton></template>
    </ElDialog>

    <ElDialog :model-value="reviewTarget !== null" data-test="recalculation-review-dialog" :title="copy.recalculation.reviewTitle" width="min(92vw, 560px)" :close-on-click-modal="false" @close="closeRecalculationReview">
      <template v-if="reviewTarget">
        <ElAlert type="warning" :title="copy.recalculation.reviewHint" :closable="false" show-icon />
        <p>{{ [reviewTarget.recalculationBatchId, reviewTarget.status].join(' · ') }}</p>
        <ElRadioGroup v-model="reviewDecision" data-test="recalculation-review-decision" :disabled="reviewSaving"><ElRadio :label="approveDecision">{{ copy.recalculation.approve }}</ElRadio><ElRadio :label="rejectDecision">{{ copy.recalculation.reject }}</ElRadio></ElRadioGroup>
        <ElFormItem :label="copy.recalculation.reviewComment"><ElInput v-model="reviewComment" data-test="recalculation-review-comment" type="textarea" :maxlength="500" show-word-limit :disabled="reviewSaving" :placeholder="copy.recalculation.reviewCommentPlaceholder" /></ElFormItem>
      </template>
      <template #footer>
        <ElButton data-test="recalculation-review-cancel" :disabled="reviewSaving" @click="closeRecalculationReview()">{{ copy.recalculation.cancel }}</ElButton>
        <ElButton data-test="recalculation-review-submit" type="primary" :disabled="!reviewDecision || !reviewComment.trim() || reviewSaving" :loading="reviewSaving" @click="submitRecalculationReview">{{ copy.recalculation.submitReview }}</ElButton>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.carbon-results-panel, .result-section, .detail-section, .recalculation-list { display: grid; gap: var(--bec-space-section); min-width: 0; }
.panel-heading, .section-heading, .recalculation-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
.panel-heading h2, .section-heading h3, .detail-section h4, .report-section h5 { margin: 0; font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
.panel-heading p, .section-heading p { margin: 0; max-width: var(--bec-text-measure); color: var(--bec-color-text-secondary); }
.panel-alert { margin-top: var(--bec-space-section); }
.result-section { padding-top: var(--bec-space-section); border-top: var(--bec-border-width) solid var(--bec-color-border); margin-top: var(--bec-space-section); }
.form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 3)), 1fr)); gap: var(--bec-space-group); }
.form-grid :deep(.el-form-item), .wide-field { margin-bottom: 0; }
.wide-field { grid-column: 1 / -1; }
.native-input { box-sizing: border-box; min-block-size: var(--bec-control-height); inline-size: 100%; padding-inline: var(--bec-space-tight); color: var(--bec-color-text-primary); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-control); background: var(--bec-color-surface); }
.batch-picker { max-inline-size: calc(var(--bec-ref-space-64) * 8); }
.batch-meta, .fixed-meta { display: flex; flex-wrap: wrap; gap: var(--bec-space-group); }
.fixed-meta { padding: var(--bec-space-group); background: var(--bec-color-surface-secondary); overflow-wrap: anywhere; }
.detail-section, .report-section { padding-top: var(--bec-space-section); border-top: var(--bec-border-width) solid var(--bec-color-border); }
.report-total { display: grid; gap: var(--bec-space-tight); padding: var(--bec-space-group); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
.report-total strong { color: var(--bec-color-text-primary); font-family: var(--bec-font-family-number); font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); overflow-wrap: anywhere; }
.report-section { display: grid; gap: var(--bec-space-group); margin-top: var(--bec-space-section); min-width: 0; }
.recalculation-row { display: grid; gap: var(--bec-space-group); padding: var(--bec-space-group); border: var(--bec-border-width) solid var(--bec-color-border); background: var(--bec-color-surface); }
.recalculation-heading > div { display: grid; gap: var(--bec-space-tight); min-width: 0; overflow-wrap: anywhere; }
.plain-list { display: grid; gap: var(--bec-space-tight); padding-left: var(--bec-space-section); overflow-wrap: anywhere; }
.evidence-content { margin: 0; max-block-size: calc(var(--bec-ref-space-64) * 8); overflow: auto; overflow-wrap: anywhere; white-space: pre-wrap; }
code { overflow-wrap: anywhere; color: var(--bec-color-text-secondary); }
</style>
