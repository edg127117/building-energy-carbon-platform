<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCard, ElDialog, ElEmpty, ElFormItem, ElInput, ElOption, ElSelect, ElSkeleton, ElTag } from '@/shared/ui'
import { requestErrorMessage, requestErrorStatus } from '@/shared/utils/request-error'
import {
  manualPeriodsApi,
  type CreateFormalPeriodPolicyRequest,
  type ManualMonthCandidate,
  type ManualMonthRequest,
  type ManualLockSnapshot,
  type ManualPendingLock,
  type ManualPeriodContext,
  type ManualPeriodPolicy,
} from '../api/manual-periods'
import copy from '../locales/manual-periods.zh-CN'

interface PointOption {
  value: string
  label: string
}

interface Draft {
  pointId: string
  month: string
  policyVersionId: string
  relationVersionId: string
  reason: string
  lockReason: string
  lockEvidence: string
}

interface SpecialistDraft {
  timezoneId: string
  closingDelayHours: string
  evidenceReference: string
  effectiveFrom: string
  effectiveTo: string
}

type ReviewTarget =
  | { kind: 'policy'; versionId: string; expectedRevision: number; label: string }
  | { kind: 'lock'; requestId: string; label: string }

const props = defineProps<{ buildingId: string; pointOptions: PointOption[] }>()
const emit = defineEmits<{ 'dirty-change': [dirty: boolean] }>()

const draft = ref<Draft>(emptyDraft())
const specialistDraft = ref<SpecialistDraft>(emptySpecialistDraft())
const context = ref<ManualPeriodContext | null>(null)
const selectedFactIds = ref<string[]>([])
const loadingContext = ref(false)
const saving = ref(false)
const error = ref('')
const specialistOpen = ref(false)
const specialistSubmittedVersion = ref<string | null>(null)
const preview = ref<ManualMonthCandidate | null>(null)
const candidate = ref<ManualMonthCandidate | null>(null)
const previewSignature = ref<string | null>(null)
const candidateSignature = ref<string | null>(null)
const lockSubmitted = ref(false)
const reviewOpen = ref(false)
const reviewTarget = ref<ReviewTarget | null>(null)
const reviewComment = ref('')
const approvedLockSnapshot = ref<ManualLockSnapshot | null>(null)
const approvedLockSnapshotKey = ref<string | null>(null)

let contextGeneration = 0
let commandGeneration = 0
let activeContextRequest: AbortController | null = null

const buildingId = computed(() => props.buildingId.trim())
const availablePointIds = computed(() => new Set(props.pointOptions.map(option => option.value)))
const monthIsStart = computed(() => /^\d{4}-\d{2}-01$/.test(draft.value.month))
const canReadContext = computed(() => Boolean(buildingId.value && availablePointIds.value.has(draft.value.pointId) && monthIsStart.value))
const approvedPolicies = computed(() => context.value?.policies.filter(policy => policy.status === 'APPROVED' && policy.sourceType === 'FORMAL') ?? [])
const pendingPolicies = computed(() => context.value?.policies.filter(policy => policy.status === 'PENDING_REVIEW' && policy.sourceType === 'FORMAL') ?? [])
const selectedPolicy = computed(() => approvedPolicies.value.find(policy => policy.versionId === draft.value.policyVersionId) ?? null)
const selectedRelation = computed(() => context.value?.relations.find(relation => relation.versionId === draft.value.relationVersionId) ?? null)
const readingIds = computed(() => new Set(context.value?.readings.map(reading => reading.factId) ?? []))
const selectedReadingCount = computed(() => [...new Set(selectedFactIds.value)].filter(factId => readingIds.value.has(factId)).length)

const manualRequest = computed<ManualMonthRequest | null>(() => {
  const current = context.value
  const policy = selectedPolicy.value
  const relation = selectedRelation.value
  const reason = draft.value.reason.trim()
  const factIds = [...new Set(selectedFactIds.value)].filter(factId => readingIds.value.has(factId)).sort()
  if (!current || !canReadContext.value || !policy || !relation || !reason || factIds.length < 1 || factIds.length > 500) return null
  return {
    pointId: draft.value.pointId,
    month: draft.value.month,
    policyVersionId: policy.versionId,
    relationVersionId: relation.versionId,
    factIds,
    expectedProjectionRevision: current.projection?.revision ?? 0,
    reason,
  }
})

const configurationSignature = computed(() => manualRequest.value
  ? JSON.stringify({ buildingId: buildingId.value, request: manualRequest.value })
  : null)
const previewReady = computed(() => Boolean(configurationSignature.value && previewSignature.value === configurationSignature.value && preview.value?.status === 'PREVIEW_ONLY'))
const candidateReady = computed(() => Boolean(configurationSignature.value && candidateSignature.value === configurationSignature.value
  && candidate.value?.status === 'PENDING_INDEPENDENT_LOCK' && candidate.value.projectionId))
const canPreview = computed(() => Boolean(manualRequest.value) && !loadingContext.value && !saving.value)
const canCreateCandidate = computed(() => previewReady.value && !candidateReady.value && !saving.value)
const canSubmitLock = computed(() => candidateReady.value && !!draft.value.lockReason.trim() && !!draft.value.lockEvidence.trim() && !saving.value)
const specialistRequest = computed<CreateFormalPeriodPolicyRequest | null>(() => {
  const delay = Number(specialistDraft.value.closingDelayHours)
  if (!buildingId.value || !specialistDraft.value.timezoneId.trim() || !specialistDraft.value.evidenceReference.trim()
    || !specialistDraft.value.effectiveFrom || !Number.isInteger(delay) || delay < 0 || delay > 720) return null
  return {
    buildingId: buildingId.value,
    timezoneId: specialistDraft.value.timezoneId.trim(),
    closingDelayHours: delay,
    lockMode: 'REVIEW_REQUIRED',
    sourceType: 'FORMAL',
    evidenceReference: specialistDraft.value.evidenceReference.trim(),
    effectiveFrom: specialistDraft.value.effectiveFrom,
    effectiveTo: specialistDraft.value.effectiveTo || null,
  }
})
const canCreateFormalPolicy = computed(() => Boolean(specialistRequest.value) && !saving.value)
const approvedLockSnapshotVisible = computed(() => approvedLockSnapshot.value && approvedLockSnapshotKey.value === contextKey()
  && context.value?.projection?.projectionId === approvedLockSnapshot.value.projectionId)
const dirty = computed(() => saving.value || reviewOpen.value || !!draft.value.pointId || !!draft.value.month || !!draft.value.policyVersionId
  || !!draft.value.relationVersionId || !!draft.value.reason.trim() || !!draft.value.lockReason.trim() || !!draft.value.lockEvidence.trim()
  || selectedFactIds.value.length > 0 || !!specialistDraft.value.timezoneId.trim() || !!specialistDraft.value.closingDelayHours
  || !!specialistDraft.value.evidenceReference.trim() || !!specialistDraft.value.effectiveFrom || !!specialistDraft.value.effectiveTo || !!reviewComment.value.trim())

function emptyDraft(): Draft {
  return { pointId: '', month: '', policyVersionId: '', relationVersionId: '', reason: '', lockReason: '', lockEvidence: '' }
}

function emptySpecialistDraft(): SpecialistDraft {
  return { timezoneId: '', closingDelayHours: '', evidenceReference: '', effectiveFrom: '', effectiveTo: '' }
}

function contextKey(): string {
  return [buildingId.value, draft.value.pointId, draft.value.month, draft.value.policyVersionId].join('\u0000')
}

function clearCalculationState(): void {
  commandGeneration++
  preview.value = null
  candidate.value = null
  previewSignature.value = null
  candidateSignature.value = null
  lockSubmitted.value = false
  saving.value = false
}

function normalizeFactSelection(): void {
  const normalized = [...new Set(selectedFactIds.value)].filter(factId => readingIds.value.has(factId))
  if (normalized.length !== selectedFactIds.value.length || normalized.some((factId, index) => factId !== selectedFactIds.value[index])) {
    selectedFactIds.value = normalized
  }
}

function resetDownstreamSelection(): void {
  draft.value.policyVersionId = ''
  draft.value.relationVersionId = ''
  selectedFactIds.value = []
  context.value = null
  clearCalculationState()
}

function clearDraft(): void {
  activeContextRequest?.abort()
  contextGeneration++
  draft.value = emptyDraft()
  specialistDraft.value = emptySpecialistDraft()
  context.value = null
  selectedFactIds.value = []
  error.value = ''
  specialistOpen.value = false
  specialistSubmittedVersion.value = null
  reviewOpen.value = false
  reviewTarget.value = null
  reviewComment.value = ''
  approvedLockSnapshot.value = null
  approvedLockSnapshotKey.value = null
  clearCalculationState()
}

function policyLabel(policy: ManualPeriodPolicy): string {
  return [`${copy.versionPrefix}${policy.versionNo}`, policy.timezoneId, policy.effectiveFrom].join(copy.separator)
}

function relationLabel(version: { versionNo: number; status: string; effectiveAt: string | null }): string {
  return [`${copy.versionPrefix}${version.versionNo}`, version.status, version.effectiveAt ?? copy.unavailable].join(copy.separator)
}

function nativeAmount(quantity: string, unit: string): string {
  return `${quantity} ${unit}`
}

/** 上下文归属到建筑、月初与口径；代际和取消信号避免切楼后采用旧来源或旧投影。 */
async function loadContext(): Promise<void> {
  const currentBuilding = buildingId.value
  const currentPoint = draft.value.pointId
  const currentMonth = draft.value.month
  const currentPolicy = draft.value.policyVersionId || undefined
  const key = contextKey()
  const owner = ++contextGeneration
  activeContextRequest?.abort()
  clearCalculationState()
  context.value = null
  if (!currentBuilding || !availablePointIds.value.has(currentPoint) || !/^\d{4}-\d{2}-01$/.test(currentMonth)) {
    loadingContext.value = false
    return
  }
  const controller = new AbortController()
  activeContextRequest = controller
  loadingContext.value = true
  error.value = ''
  try {
    const next = await manualPeriodsApi.context(currentBuilding, currentPoint, currentMonth, currentPolicy, controller.signal)
    if (owner !== contextGeneration || key !== contextKey() || currentBuilding !== buildingId.value) return
    context.value = next
    if (currentPolicy && !next.policies.some(policy => policy.versionId === currentPolicy && policy.status === 'APPROVED' && policy.sourceType === 'FORMAL')) {
      draft.value.policyVersionId = ''
    }
    if (draft.value.relationVersionId && !next.relations.some(relation => relation.versionId === draft.value.relationVersionId)) {
      draft.value.relationVersionId = ''
    }
    normalizeFactSelection()
  } catch (reason) {
    if (owner === contextGeneration) error.value = requestErrorMessage(reason)
  } finally {
    if (owner === contextGeneration) {
      loadingContext.value = false
      activeContextRequest = null
    }
  }
}

function handleMutationFailure(reason: unknown): void {
  if (requestErrorStatus(reason) === 409) {
    clearCalculationState()
    error.value = copy.conflict
    return
  }
  error.value = requestErrorMessage(reason)
}

function commandIsCurrent(owner: number, signature: string, commandBuilding: string): boolean {
  return owner === commandGeneration && signature === configurationSignature.value && commandBuilding === buildingId.value
}

function previewMatchesRequest(value: ManualMonthCandidate, request: ManualMonthRequest, commandBuilding: string): boolean {
  return value.status === 'PREVIEW_ONLY' && value.projectionId === null && value.revision === request.expectedProjectionRevision
    && value.buildingId === commandBuilding && value.pointId === request.pointId
}

async function previewManualMonth(): Promise<void> {
  const request = manualRequest.value
  const signature = configurationSignature.value
  const commandBuilding = buildingId.value
  if (!request || !signature || saving.value) {
    if (!manualRequest.value) error.value = selectedReadingCount.value > 500 ? copy.factLimit : copy.required
    return
  }
  const owner = ++commandGeneration
  saving.value = true
  error.value = ''
  try {
    const value = await manualPeriodsApi.preview(commandBuilding, request)
    if (!commandIsCurrent(owner, signature, commandBuilding)) return
    if (!previewMatchesRequest(value, request, commandBuilding)) {
      clearCalculationState()
      error.value = copy.requestMismatch
      return
    }
    preview.value = value
    candidate.value = null
    previewSignature.value = signature
    candidateSignature.value = null
  } catch (reason) {
    if (owner === commandGeneration) handleMutationFailure(reason)
  } finally {
    if (owner === commandGeneration) saving.value = false
  }
}

async function createCandidate(): Promise<void> {
  const request = manualRequest.value
  const signature = configurationSignature.value
  const commandBuilding = buildingId.value
  if (!request || !signature || !previewReady.value || saving.value) return
  const owner = ++commandGeneration
  saving.value = true
  error.value = ''
  try {
    const value = await manualPeriodsApi.createCandidate(commandBuilding, request)
    if (!commandIsCurrent(owner, signature, commandBuilding)) return
    if (value.status !== 'PENDING_INDEPENDENT_LOCK' || !value.projectionId || value.buildingId !== commandBuilding || value.pointId !== request.pointId) {
      clearCalculationState()
      error.value = copy.candidateMismatch
      return
    }
    candidate.value = value
    candidateSignature.value = signature
  } catch (reason) {
    if (owner === commandGeneration) handleMutationFailure(reason)
  } finally {
    if (owner === commandGeneration) saving.value = false
  }
}

async function submitLock(): Promise<void> {
  const value = candidate.value
  const signature = configurationSignature.value
  const commandBuilding = buildingId.value
  if (!value?.projectionId || !signature || !candidateReady.value || !canSubmitLock.value || saving.value) return
  const owner = ++commandGeneration
  saving.value = true
  error.value = ''
  try {
    await manualPeriodsApi.submitLock({
      projectionId: value.projectionId,
      expectedRevision: value.revision,
      reason: draft.value.lockReason.trim(),
      evidenceReference: draft.value.lockEvidence.trim(),
    })
    if (!commandIsCurrent(owner, signature, commandBuilding)) return
    draft.value.lockReason = ''
    draft.value.lockEvidence = ''
    candidate.value = null
    candidateSignature.value = null
    preview.value = null
    previewSignature.value = null
    selectedFactIds.value = []
    void loadContext()
    lockSubmitted.value = true
  } catch (reason) {
    if (owner === commandGeneration) handleMutationFailure(reason)
  } finally {
    if (owner === commandGeneration) saving.value = false
  }
}

async function createFormalPolicy(): Promise<void> {
  const request = specialistRequest.value
  const currentBuilding = buildingId.value
  if (!request || saving.value) {
    error.value = copy.required
    return
  }
  const owner = ++commandGeneration
  saving.value = true
  error.value = ''
  try {
    const value = await manualPeriodsApi.createFormalPolicy(request)
    if (owner !== commandGeneration || currentBuilding !== buildingId.value) return
    specialistSubmittedVersion.value = value.versionId
    specialistDraft.value = emptySpecialistDraft()
    specialistOpen.value = false
    void loadContext()
  } catch (reason) {
    if (owner === commandGeneration) handleMutationFailure(reason)
  } finally {
    if (owner === commandGeneration) saving.value = false
  }
}

function startPolicyReview(policy: ManualPeriodPolicy): void {
  reviewTarget.value = { kind: 'policy', versionId: policy.versionId, expectedRevision: policy.configRevision, label: policyLabel(policy) }
  reviewComment.value = ''
  reviewOpen.value = true
  error.value = ''
}

function startLockReview(lock: ManualPendingLock): void {
  reviewTarget.value = { kind: 'lock', requestId: lock.requestId, label: lock.requestId }
  reviewComment.value = ''
  reviewOpen.value = true
  error.value = ''
}

function closeReview(force = false): void {
  if (saving.value && !force) return
  reviewOpen.value = false
  reviewTarget.value = null
  reviewComment.value = ''
}

async function submitReview(): Promise<void> {
  const target = reviewTarget.value
  const comment = reviewComment.value.trim()
  const currentBuilding = buildingId.value
  if (!target || !comment || saving.value) {
    error.value = copy.required
    return
  }
  const owner = ++commandGeneration
  saving.value = true
  error.value = ''
  try {
    if (target.kind === 'policy') {
      await manualPeriodsApi.approvePolicy(target.versionId, { expectedRevision: target.expectedRevision, reviewComment: comment })
    } else {
      const snapshot = await manualPeriodsApi.approveLock(target.requestId, { reviewComment: comment })
      if (owner === commandGeneration && currentBuilding === buildingId.value) {
        approvedLockSnapshot.value = snapshot
        approvedLockSnapshotKey.value = contextKey()
      }
    }
    if (owner !== commandGeneration || currentBuilding !== buildingId.value) return
    closeReview(true)
    void loadContext()
  } catch (reason) {
    if (owner === commandGeneration) handleMutationFailure(reason)
  } finally {
    if (owner === commandGeneration) saving.value = false
  }
}

function selectAllReadings(): void {
  const readings = context.value?.readings ?? []
  if (readings.length > 500) {
    error.value = copy.factLimit
    return
  }
  selectedFactIds.value = readings.map(reading => reading.factId)
}

watch(() => props.buildingId, () => {
  clearDraft()
  void loadContext()
}, { immediate: true })

watch(() => props.pointOptions.map(option => option.value).join('\u0000'), () => {
  if (draft.value.pointId && !availablePointIds.value.has(draft.value.pointId)) {
    draft.value.pointId = ''
    resetDownstreamSelection()
  }
})

watch(() => [buildingId.value, draft.value.pointId, draft.value.month].join('\u0000'), (next, previous) => {
  if (previous !== undefined && next !== previous) {
    const hadPolicy = !!draft.value.policyVersionId
    resetDownstreamSelection()
    // 清空已选口径会由下一条观察器读取同一基础上下文，避免一次切月重复请求。
    if (hadPolicy) return
  }
  void loadContext()
})

watch(() => draft.value.policyVersionId, (next, previous) => {
  if (next === previous) return
  draft.value.relationVersionId = ''
  selectedFactIds.value = []
  clearCalculationState()
  void loadContext()
})

watch(configurationSignature, (next, previous) => {
  if (next !== previous) clearCalculationState()
})

watch(selectedFactIds, normalizeFactSelection, { deep: true })
watch(dirty, value => emit('dirty-change', value), { immediate: true })

onBeforeUnmount(() => {
  contextGeneration++
  commandGeneration++
  activeContextRequest?.abort()
})
</script>

<template>
  <section class="manual-period-panel">
    <ElCard shadow="never">
      <template #header>
        <div class="panel-heading">
          <div><h2>{{ copy.title }}</h2><p>{{ copy.description }}</p></div>
          <div class="header-actions">
            <ElButton data-test="refresh-context" :disabled="!canReadContext || loadingContext || saving" :loading="loadingContext" @click="loadContext">{{ copy.refresh }}</ElButton>
            <ElButton v-if="dirty" data-test="clear-draft" :disabled="saving" @click="clearDraft">{{ copy.cancel }}</ElButton>
          </div>
        </div>
      </template>

      <ElEmpty v-if="!buildingId" :description="copy.selectBuilding" :image-size="48" />
      <template v-else>
        <ElAlert v-if="error" type="error" :title="error" :closable="false" show-icon />
        <ElAlert v-if="specialistSubmittedVersion" type="success" :title="copy.createdPolicy.replace('{version}', specialistSubmittedVersion)" :closable="false" show-icon />
        <ElAlert v-if="lockSubmitted" type="success" :title="copy.lockSubmitted" :closable="false" show-icon />

        <div class="form-grid">
          <ElFormItem :label="copy.fields.point">
            <ElSelect v-model="draft.pointId" data-test="point-select" clearable filterable :disabled="loadingContext || saving || !pointOptions.length" :placeholder="copy.placeholders.point">
              <ElOption v-for="point in pointOptions" :key="point.value" :value="point.value" :label="point.label" />
            </ElSelect>
            <small v-if="!pointOptions.length">{{ copy.noPoints }}</small>
          </ElFormItem>
          <ElFormItem :label="copy.fields.month">
            <input v-model="draft.month" data-test="month-input" class="native-input" type="date" :disabled="saving" />
            <small v-if="draft.month && !monthIsStart">{{ copy.monthStartRequired }}</small>
          </ElFormItem>
          <ElFormItem :label="copy.fields.policy">
            <ElSelect v-model="draft.policyVersionId" data-test="policy-select" clearable filterable :disabled="!context || loadingContext || saving || !approvedPolicies.length" :placeholder="copy.placeholders.policy">
              <ElOption v-for="policy in approvedPolicies" :key="policy.versionId" :value="policy.versionId" :label="policyLabel(policy)" />
            </ElSelect>
            <small>{{ copy.policyHint }}</small>
          </ElFormItem>
          <ElFormItem v-if="selectedPolicy" :label="copy.fields.relation">
            <ElSelect v-model="draft.relationVersionId" data-test="relation-select" clearable filterable :disabled="loadingContext || saving || !context?.relations.length" :placeholder="copy.placeholders.relation">
              <ElOption v-for="relation in context?.relations ?? []" :key="relation.versionId" :value="relation.versionId" :label="relationLabel(relation)" />
            </ElSelect>
            <small>{{ copy.relationHint }}</small>
          </ElFormItem>
        </div>

        <ElAlert v-if="draft.month && !monthIsStart" type="warning" :title="copy.monthStartRequired" :closable="false" show-icon />
        <ElAlert v-else-if="!canReadContext" type="info" :title="copy.contextHint" :closable="false" show-icon />
        <ElSkeleton v-else-if="loadingContext" animated :rows="6" />

        <template v-else-if="context">
          <ElAlert v-if="!approvedPolicies.length" type="warning" :title="copy.noApprovedPolicies" :closable="false" show-icon />
          <ElAlert v-if="selectedPolicy && !context.relations.length" type="warning" :title="copy.noRelations" :closable="false" show-icon />

          <section class="policy-review-section">
            <div class="section-heading"><div><h3>{{ copy.pendingPolicies }}</h3><p>{{ copy.reviewHint }}</p></div></div>
            <ElEmpty v-if="!pendingPolicies.length" :description="copy.noPendingPolicies" :image-size="38" />
            <div v-else class="review-list">
              <article v-for="policy in pendingPolicies" :key="policy.versionId" class="review-row">
                <div><strong>{{ policyLabel(policy) }}</strong><span>{{ policy.sourceType }}{{ copy.separator }}{{ policy.lockMode }}{{ copy.separator }}{{ policy.evidenceReference }}</span></div>
                <ElButton :data-test="`policy-review-${policy.versionId}`" :disabled="saving" @click="startPolicyReview(policy)">{{ copy.approvePolicy }}</ElButton>
              </article>
            </div>
          </section>

          <section v-if="!context.policies.length" class="specialist-section">
            <ElButton data-test="specialist-toggle" text @click="specialistOpen = !specialistOpen">{{ copy.specialistTitle }}</ElButton>
            <div v-if="specialistOpen" class="specialist-body">
              <ElAlert type="info" :title="copy.specialistHint" :closable="false" show-icon />
              <div class="form-grid">
                <ElFormItem :label="copy.fields.timezoneId"><ElInput v-model="specialistDraft.timezoneId" data-test="specialist-timezone" :maxlength="64" :disabled="saving" :placeholder="copy.placeholders.timezoneId" /></ElFormItem>
                <ElFormItem :label="copy.fields.closingDelayHours"><input v-model="specialistDraft.closingDelayHours" data-test="specialist-delay" class="native-input" type="number" min="0" max="720" step="1" :disabled="saving" :placeholder="copy.placeholders.closingDelayHours" /></ElFormItem>
                <ElFormItem :label="copy.fields.effectiveFrom"><input v-model="specialistDraft.effectiveFrom" data-test="specialist-effective-from" class="native-input" type="datetime-local" :disabled="saving" /></ElFormItem>
                <ElFormItem :label="copy.fields.effectiveTo"><input v-model="specialistDraft.effectiveTo" data-test="specialist-effective-to" class="native-input" type="datetime-local" :disabled="saving" /></ElFormItem>
                <ElFormItem class="wide-field" :label="copy.fields.policyEvidence"><ElInput v-model="specialistDraft.evidenceReference" data-test="specialist-evidence" type="textarea" :maxlength="500" show-word-limit :disabled="saving" :placeholder="copy.placeholders.policyEvidence" /></ElFormItem>
              </div>
              <p class="fixed-policy-values"><span>{{ copy.sourceType }}</span><span>{{ copy.lockMode }}</span></p>
              <ElButton data-test="specialist-create" type="primary" :disabled="!canCreateFormalPolicy" :loading="saving" @click="createFormalPolicy">{{ copy.specialistCreate }}</ElButton>
            </div>
          </section>

          <template v-if="selectedPolicy">
            <section class="projection-section">
              <h3>{{ copy.currentProjection }}</h3>
              <ElAlert v-if="!context.projection" type="info" :title="copy.noProjection" :closable="false" show-icon />
              <div v-else class="projection-row">
                <span><strong>{{ copy.projectionId }}</strong> <code>{{ context.projection.projectionId }}</code></span>
                <span><strong>{{ copy.nativeQuantity }}</strong> {{ nativeAmount(context.projection.quantity, context.projection.unit) }}</span>
                <span><strong>{{ copy.projectionRevision }}</strong> {{ context.projection.revision }}</span>
                <ElTag>{{ context.projection.status }}</ElTag>
              </div>
            </section>

            <section class="readings-section">
              <div class="section-heading"><div><h3>{{ copy.approvedReadings }}</h3><p>{{ copy.readingsHint }}</p></div><ElButton data-test="select-all-facts" :disabled="saving || !context.readings.length" @click="selectAllReadings">{{ copy.selectAllReadings }}</ElButton></div>
              <ElEmpty v-if="!context.readings.length" :description="copy.noReadings" :image-size="40" />
              <div v-else class="reading-list" data-test="reading-list">
                <label v-for="reading in context.readings" :key="reading.factId" class="reading-row">
                  <input v-model="selectedFactIds" :data-test="`fact-${reading.factId}`" type="checkbox" :value="reading.factId" :disabled="saving" />
                  <span><strong>{{ copy.factId }}</strong> <code>{{ reading.factId }}</code></span>
                  <span><strong>{{ copy.nativeQuantity }}</strong> {{ nativeAmount(reading.normalizedQuantity, reading.reading.unit) }}</span>
                  <span><strong>{{ copy.sourceInterval }}</strong> {{ reading.reading.startInclusive }}{{ copy.rangeSeparator }}{{ reading.reading.endExclusive }}</span>
                  <span><strong>{{ copy.semantics }}</strong> {{ reading.reading.semantics }}{{ copy.separator }}<strong>{{ copy.status }}</strong> {{ reading.reading.status }}</span>
                </label>
              </div>
              <ElAlert v-if="selectedReadingCount > 500" type="warning" :title="copy.factLimit" :closable="false" show-icon />
            </section>

            <ElFormItem :label="copy.fields.reason" class="wide-field"><ElInput v-model="draft.reason" data-test="candidate-reason" type="textarea" :maxlength="500" show-word-limit :disabled="saving" :placeholder="copy.placeholders.reason" /></ElFormItem>
            <div class="command-actions">
              <ElButton data-test="preview" type="primary" :disabled="!canPreview" :loading="saving" @click="previewManualMonth">{{ copy.preview }}</ElButton>
              <ElButton data-test="create-candidate" type="warning" :disabled="!canCreateCandidate" :loading="saving" @click="createCandidate">{{ copy.createCandidate }}</ElButton>
            </div>
            <ElAlert v-if="previewReady" type="success" :title="copy.previewReady" :closable="false" show-icon />
            <ElAlert v-else-if="manualRequest" type="info" :title="copy.previewRequired" :closable="false" show-icon />
            <div v-if="preview" class="amount-result" data-test="preview-amount"><strong>{{ copy.previewOnly }}</strong><span>{{ nativeAmount(preview.quantity, preview.unit) }}</span></div>

            <section v-if="candidateReady && candidate" class="candidate-section">
              <div class="section-heading"><div><h3>{{ copy.candidatePending }}</h3><p>{{ copy.candidateReady }}</p></div><ElTag type="warning">{{ candidate.status }}</ElTag></div>
              <div class="amount-result" data-test="candidate-amount"><strong>{{ copy.nativeQuantity }}</strong><span>{{ nativeAmount(candidate.quantity, candidate.unit) }}</span></div>
              <div class="form-grid">
                <ElFormItem :label="copy.fields.lockReason"><ElInput v-model="draft.lockReason" data-test="lock-reason" type="textarea" :maxlength="500" show-word-limit :disabled="saving" :placeholder="copy.placeholders.lockReason" /></ElFormItem>
                <ElFormItem :label="copy.fields.lockEvidence"><ElInput v-model="draft.lockEvidence" data-test="lock-evidence" type="textarea" :maxlength="500" show-word-limit :disabled="saving" :placeholder="copy.placeholders.lockEvidence" /></ElFormItem>
              </div>
              <ElButton data-test="submit-lock" type="danger" :disabled="!canSubmitLock" :loading="saving" @click="submitLock">{{ copy.lock }}</ElButton>
            </section>

            <section class="lock-review-section">
              <div class="section-heading"><div><h3>{{ copy.pendingLocks }}</h3><p>{{ copy.reviewHint }}</p></div></div>
              <ElEmpty v-if="!context.pendingLocks.length" :description="copy.noPendingLocks" :image-size="38" />
              <div v-else class="review-list">
                <article v-for="lock in context.pendingLocks" :key="lock.requestId" class="review-row">
                  <div><strong>{{ copy.lockRequestId }}</strong> <code>{{ lock.requestId }}</code><span>{{ lock.status }}{{ copy.separator }}{{ copy.projectionRevision }} {{ lock.projectionRevision }}</span></div>
                  <ElButton :data-test="`lock-review-${lock.requestId}`" :disabled="saving" @click="startLockReview(lock)">{{ copy.approveLock }}</ElButton>
                </article>
              </div>
            </section>
            <section v-if="approvedLockSnapshotVisible" class="snapshot-status" data-test="lock-snapshot-status">
              <h3>{{ copy.lockReviewResult }}</h3>
              <p v-if="approvedLockSnapshot?.nativeQuantityStatus === 'COMPLETE' && approvedLockSnapshot?.tceStatus === 'NOT_CALCULATED'">{{ copy.nativeCompleteTceNotCalculated }}</p>
              <p v-else><strong>{{ copy.nativeQuantityStatus }}</strong> {{ approvedLockSnapshot?.nativeQuantityStatus }}{{ copy.separator }}<strong>{{ copy.tceStatus }}</strong> {{ approvedLockSnapshot?.tceStatus }}</p>
            </section>
          </template>
        </template>

        <ElAlert class="scope-note" type="info" :title="copy.noTceOrCarbon" :closable="false" show-icon />
      </template>
    </ElCard>

    <ElDialog v-model="reviewOpen" data-test="review-dialog" :title="copy.reviewTitle" width="min(92vw, 560px)" :close-on-click-modal="false" @close="closeReview">
      <p>{{ copy.reviewHint }}</p>
      <p v-if="reviewTarget"><strong>{{ reviewTarget.label }}</strong></p>
      <ElFormItem :label="copy.fields.reviewComment"><ElInput v-model="reviewComment" data-test="review-comment" type="textarea" :maxlength="500" show-word-limit :disabled="saving" :placeholder="copy.placeholders.reviewComment" /></ElFormItem>
      <template #footer>
        <ElButton data-test="review-cancel" :disabled="saving" @click="closeReview()">{{ copy.cancel }}</ElButton>
        <ElButton data-test="review-submit" type="primary" :disabled="!reviewComment.trim() || saving" :loading="saving" @click="submitReview">{{ copy.submitReview }}</ElButton>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.manual-period-panel, .policy-review-section, .readings-section, .projection-section, .candidate-section, .lock-review-section, .snapshot-status { display: grid; gap: var(--bec-space-section); min-width: 0; }
.panel-heading, .header-actions, .section-heading, .command-actions, .projection-row, .review-row, .fixed-policy-values { display: flex; align-items: center; gap: var(--bec-space-group); flex-wrap: wrap; }
.panel-heading, .section-heading { justify-content: space-between; }
.panel-heading h2, .section-heading h3, .projection-section h3 { margin: 0; font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
.panel-heading p, .section-heading p, .specialist-body > p, small { margin: 0; color: var(--bec-color-text-secondary); }
.form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 3)), 1fr)); gap: var(--bec-space-group); margin-top: var(--bec-space-section); }
.form-grid :deep(.el-form-item), .wide-field { margin-bottom: 0; }
.wide-field { margin-top: var(--bec-space-section); }
.native-input { box-sizing: border-box; min-block-size: var(--bec-control-height); inline-size: 100%; padding-inline: var(--bec-space-tight); color: var(--bec-color-text-primary); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-control); background: var(--bec-color-surface); }
.policy-review-section, .readings-section, .projection-section, .candidate-section, .lock-review-section, .snapshot-status, .specialist-section { padding-top: var(--bec-space-section); border-top: var(--bec-border-width) solid var(--bec-color-border); margin-top: var(--bec-space-section); }
.specialist-body { display: grid; gap: var(--bec-space-section); margin-top: var(--bec-space-group); padding: var(--bec-space-group); border-left: var(--bec-border-width) solid var(--bec-color-primary); background: var(--bec-color-surface-secondary); }
.reading-list, .review-list { display: grid; gap: var(--bec-space-tight); }
.reading-row, .review-row { padding: var(--bec-space-group); border: var(--bec-border-width) solid var(--bec-color-border); background: var(--bec-color-surface); }
.reading-row { display: grid; grid-template-columns: auto repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 2 + var(--bec-ref-space-32))), 1fr)); gap: var(--bec-space-tight) var(--bec-space-group); align-items: start; cursor: pointer; }
.review-row { justify-content: space-between; }
.review-row > div { display: grid; gap: var(--bec-space-tight); min-width: 0; }
.amount-result { display: grid; gap: var(--bec-space-tight); padding: var(--bec-space-group); border-left: var(--bec-border-width) solid var(--bec-color-primary); background: var(--bec-color-surface-secondary); }
.scope-note { margin-top: var(--bec-space-section); }
code { overflow-wrap: anywhere; color: var(--bec-color-text-secondary); }
</style>
