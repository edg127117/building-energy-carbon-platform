<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCard, ElDialog, ElEmpty, ElFormItem, ElInput, ElOption, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag } from '@/shared/ui'
import { requestErrorMessage, requestErrorStatus } from '@/shared/utils/request-error'
import { boundarySummaryPoliciesApi, type BoundarySummaryPolicy, type CreateBoundarySummaryPolicyRequest } from '../api/boundary-summary-policies'
import { energyCatalogApi, type ApprovedEnergyItem } from '../api/carbon-project'
import { collectRelationPages, relationGovernanceApi } from '../api/relation-governance'
import type { RelationMeteringBoundary } from '../models/classification'
import copy from '../locales/boundary-summary-policies.zh-CN'

type AggregationMode = CreateBoundarySummaryPolicyRequest['aggregationMode']
type SourceType = CreateBoundarySummaryPolicyRequest['sourceType']

interface Draft {
  boundaryId: string
  energyItemCode: string
  aggregationMode: AggregationMode | ''
  sourceType: SourceType | ''
  evidenceReference: string
  effectiveFrom: string
  effectiveTo: string
}

const props = defineProps<{ buildingId: string }>()
const emit = defineEmits<{ 'dirty-change': [dirty: boolean] }>()

const policies = ref<BoundarySummaryPolicy[]>([])
const boundaries = ref<RelationMeteringBoundary[]>([])
const energyItems = ref<ApprovedEnergyItem[]>([])
const draft = ref<Draft>(emptyDraft())
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const directoryReady = ref(false)
const reviewTarget = ref<BoundarySummaryPolicy | null>(null)
const reviewComment = ref('')
let buildingGeneration = 0
let commandGeneration = 0

const aggregationModes: AggregationMode[] = ['MAIN_METER_TOTAL', 'SUBMETER_SUM', 'INDEPENDENT_METER_SUM', 'MAIN_WITH_SUBMETER_BREAKDOWN']
const sourceTypes: SourceType[] = ['FORMAL', 'SIMULATION']
const confirmedBoundaries = computed(() => boundaries.value.filter(item => item.status === 'ACTIVE' && item.confirmationStatus === 'CONFIRMED'))
const approvedEnergyItems = computed(() => energyItems.value.filter(item => item.status === 'APPROVED'))
const pendingPolicies = computed(() => policies.value.filter(item => item.status === 'PENDING_EXPERT'))
const createRequest = computed<CreateBoundarySummaryPolicyRequest | null>(() => {
  const buildingId = props.buildingId.trim()
  const current = draft.value
  if (!buildingId || !current.boundaryId || !current.energyItemCode || !current.aggregationMode || !current.sourceType
    || !current.evidenceReference.trim() || !current.effectiveFrom) return null
  if (!confirmedBoundaries.value.some(item => item.boundaryId === current.boundaryId)
    || !approvedEnergyItems.value.some(item => item.itemCode === current.energyItemCode)) return null
  return {
    buildingId,
    meteringBoundaryId: current.boundaryId,
    energyItemCode: current.energyItemCode,
    aggregationMode: current.aggregationMode,
    sourceType: current.sourceType,
    evidenceReference: current.evidenceReference.trim(),
    effectiveFrom: current.effectiveFrom,
    effectiveTo: current.effectiveTo || null,
  }
})
const dirty = computed(() => saving.value || reviewTarget.value !== null || !!draft.value.boundaryId || !!draft.value.energyItemCode
  || !!draft.value.aggregationMode || !!draft.value.sourceType || !!draft.value.evidenceReference.trim()
  || !!draft.value.effectiveFrom || !!draft.value.effectiveTo)

watch(dirty, value => emit('dirty-change', value), { immediate: true })
watch(() => props.buildingId, () => {
  buildingGeneration++
  commandGeneration++
  policies.value = []
  boundaries.value = []
  energyItems.value = []
  draft.value = emptyDraft()
  error.value = ''
  notice.value = ''
  reviewTarget.value = null
  reviewComment.value = ''
  void load()
}, { immediate: true })

function emptyDraft(): Draft {
  return { boundaryId: '', energyItemCode: '', aggregationMode: '', sourceType: '', evidenceReference: '', effectiveFrom: '', effectiveTo: '' }
}

function boundaryLabel(boundary: RelationMeteringBoundary): string {
  return [boundary.boundaryCode, boundary.boundaryName].filter(Boolean).join(' · ')
}

function energyLabel(item: ApprovedEnergyItem): string {
  return [item.itemName, item.itemCode].filter(Boolean).join(' · ')
}

function isCurrent(owner: number, buildingId: string): boolean {
  return owner === buildingGeneration && buildingId === props.buildingId.trim()
}

async function load(): Promise<void> {
  const buildingId = props.buildingId.trim()
  const owner = ++buildingGeneration
  directoryReady.value = false
  error.value = ''
  if (!buildingId) {
    loading.value = false
    return
  }
  loading.value = true
  const [policyResult, boundaryResult, energyResult] = await Promise.allSettled([
    boundarySummaryPoliciesApi.list(buildingId),
    collectRelationPages((page, size) => relationGovernanceApi.listEffectiveBoundaries(buildingId, page, size)),
    energyCatalogApi.listItems(),
  ])
  if (!isCurrent(owner, buildingId)) return
  if (policyResult.status === 'fulfilled') policies.value = policyResult.value
  if (boundaryResult.status === 'fulfilled') boundaries.value = boundaryResult.value.items
  if (energyResult.status === 'fulfilled') energyItems.value = energyResult.value
  const failed = policyResult.status === 'rejected' ? policyResult.reason
    : boundaryResult.status === 'rejected' ? boundaryResult.reason
      : energyResult.status === 'rejected' ? energyResult.reason : null
  if (failed) error.value = policyResult.status === 'rejected' && requestErrorStatus(policyResult.reason) === 409
    ? copy.listLimit
    : requestErrorMessage(failed)
  directoryReady.value = !failed
  loading.value = false
}

function upsertPolicy(value: BoundarySummaryPolicy): void {
  const index = policies.value.findIndex(item => item.versionId === value.versionId)
  if (index < 0) policies.value = [value, ...policies.value]
  else policies.value = policies.value.map(item => item.versionId === value.versionId ? value : item)
}

async function createPolicy(): Promise<void> {
  const request = createRequest.value
  const buildingId = props.buildingId.trim()
  if (!request || saving.value) {
    error.value = copy.required
    return
  }
  const owner = ++commandGeneration
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const created = await boundarySummaryPoliciesApi.create(request)
    if (owner !== commandGeneration || buildingId !== props.buildingId.trim()) return
    upsertPolicy(created)
    draft.value = emptyDraft()
    notice.value = copy.submitted.replace('{versionId}', created.versionId)
  } catch (reason) {
    if (owner === commandGeneration && buildingId === props.buildingId.trim()) error.value = requestErrorMessage(reason)
  } finally {
    if (owner === commandGeneration) saving.value = false
  }
}

function openReview(policy: BoundarySummaryPolicy): void {
  reviewTarget.value = policy
  reviewComment.value = ''
  error.value = ''
}

function closeReview(force = false): void {
  if (saving.value && !force) return
  reviewTarget.value = null
  reviewComment.value = ''
}

async function submitReview(): Promise<void> {
  const policy = reviewTarget.value
  const comment = reviewComment.value.trim()
  const buildingId = props.buildingId.trim()
  if (!policy || !comment || saving.value) {
    error.value = copy.required
    return
  }
  const owner = ++commandGeneration
  saving.value = true
  error.value = ''
  try {
    const approved = await boundarySummaryPoliciesApi.approve(policy.versionId, {
      expectedRevision: policy.configRevision,
      reviewComment: comment,
    })
    if (owner !== commandGeneration || buildingId !== props.buildingId.trim()) return
    upsertPolicy(approved)
    closeReview(true)
  } catch (reason) {
    if (owner === commandGeneration && buildingId === props.buildingId.trim()) error.value = requestErrorMessage(reason)
  } finally {
    if (owner === commandGeneration) saving.value = false
  }
}
</script>

<template>
  <section class="boundary-summary-policy-panel">
    <ElCard shadow="never">
      <template #header>
        <div class="panel-heading">
          <div><h2>{{ copy.title }}</h2><p>{{ copy.description }}</p></div>
          <ElButton data-test="policy-refresh" :disabled="loading || saving" :loading="loading" @click="load">{{ copy.refresh }}</ElButton>
        </div>
      </template>

      <ElEmpty v-if="!buildingId" :description="copy.selectBuilding" :image-size="48" />
      <template v-else>
        <ElAlert type="info" :title="copy.description" :closable="false" show-icon />
        <ElAlert v-if="error" class="panel-alert" type="error" :title="error" :closable="false" show-icon />
        <ElAlert v-if="notice" class="panel-alert" type="success" :title="notice" :closable="false" show-icon @close="notice = ''" />
        <ElSkeleton v-if="loading" animated :rows="5" />
        <template v-else>
          <ElAlert v-if="!confirmedBoundaries.length" class="panel-alert" type="warning" :title="copy.noBoundaries" :closable="false" show-icon />
          <ElAlert v-if="!approvedEnergyItems.length" class="panel-alert" type="warning" :title="copy.noEnergyItems" :closable="false" show-icon />

          <section class="editor-section" :aria-label="copy.createAriaLabel">
            <div class="section-heading"><div><h3>{{ copy.createTitle }}</h3><p>{{ copy.createHint }}</p></div></div>
            <div class="form-grid">
              <ElFormItem :label="copy.boundary"><ElSelect v-model="draft.boundaryId" data-test="boundary-select" filterable clearable :disabled="saving || !directoryReady || !confirmedBoundaries.length"><ElOption v-for="boundary in confirmedBoundaries" :key="boundary.boundaryId" :value="boundary.boundaryId" :label="boundaryLabel(boundary)" /></ElSelect></ElFormItem>
              <ElFormItem :label="copy.energyItem"><ElSelect v-model="draft.energyItemCode" data-test="energy-select" filterable clearable :disabled="saving || !directoryReady || !approvedEnergyItems.length"><ElOption v-for="item in approvedEnergyItems" :key="item.versionId" :value="item.itemCode" :label="energyLabel(item)" /></ElSelect></ElFormItem>
              <ElFormItem :label="copy.aggregationMode"><ElSelect v-model="draft.aggregationMode" data-test="aggregation-mode" clearable :disabled="saving || !directoryReady"><ElOption v-for="mode in aggregationModes" :key="mode" :value="mode" :label="copy[mode]" /></ElSelect></ElFormItem>
              <ElFormItem :label="copy.sourceType"><ElSelect v-model="draft.sourceType" data-test="source-type" clearable :disabled="saving || !directoryReady"><ElOption v-for="source in sourceTypes" :key="source" :value="source" :label="source === 'FORMAL' ? copy.sourceFormal : copy.sourceSimulation" /></ElSelect></ElFormItem>
              <ElFormItem :label="copy.effectiveFrom"><input v-model="draft.effectiveFrom" data-test="effective-from" class="native-input" type="datetime-local" :disabled="saving || !directoryReady"></ElFormItem>
              <ElFormItem :label="copy.effectiveTo"><input v-model="draft.effectiveTo" data-test="effective-to" class="native-input" type="datetime-local" :disabled="saving || !directoryReady"></ElFormItem>
              <ElFormItem class="wide-field" :label="copy.evidenceReference"><ElInput v-model="draft.evidenceReference" data-test="evidence-reference" type="textarea" :maxlength="500" show-word-limit :disabled="saving || !directoryReady" :placeholder="copy.evidencePlaceholder" /></ElFormItem>
            </div>
            <ElButton data-test="policy-create" type="primary" :disabled="!createRequest || saving" :loading="saving" @click="createPolicy">{{ copy.create }}</ElButton>
          </section>

          <section class="editor-section" :aria-label="copy.pendingAriaLabel">
            <div class="section-heading"><div><h3>{{ copy.pending }}</h3><p>{{ copy.reviewHint }}</p></div></div>
            <ElEmpty v-if="!pendingPolicies.length" :description="copy.noPending" :image-size="40" />
            <div v-else class="review-list">
              <article v-for="policy in pendingPolicies" :key="policy.versionId" class="review-row">
                <div><strong>{{ copy.version }} {{ policy.versionNo }}</strong><span>{{ [policy.meteringBoundaryId, policy.energyItemCode, policy.sourceType].join(' · ') }}</span><span>{{ policy.evidenceReference }}</span></div>
                <ElButton :data-test="`policy-review-${policy.versionId}`" :disabled="saving" @click="openReview(policy)">{{ copy.review }}</ElButton>
              </article>
            </div>
          </section>

          <section class="editor-section" :aria-label="copy.policiesAriaLabel">
            <div class="section-heading"><div><h3>{{ copy.policies }}</h3></div></div>
            <ElEmpty v-if="!policies.length" :description="copy.empty" :image-size="40" />
            <ElTable v-else :data="policies" row-key="versionId">
              <ElTableColumn prop="versionNo" :label="copy.version" min-width="100" />
              <ElTableColumn prop="meteringBoundaryId" :label="copy.boundary" min-width="180" />
              <ElTableColumn prop="energyItemCode" :label="copy.energyItem" min-width="150" />
              <ElTableColumn :label="copy.aggregationMode" min-width="180"><template #default="{ row }">{{ copy[row.aggregationMode as AggregationMode] ?? row.aggregationMode }}</template></ElTableColumn>
              <ElTableColumn prop="sourceType" :label="copy.source" min-width="120" />
              <ElTableColumn :label="copy.range" min-width="260"><template #default="{ row }">{{ [row.effectiveFrom, row.effectiveTo ?? copy.unavailable].join(' · ') }}</template></ElTableColumn>
              <ElTableColumn :label="copy.status" min-width="140"><template #default="{ row }"><ElTag>{{ row.status }}</ElTag></template></ElTableColumn>
              <ElTableColumn prop="evidenceReference" :label="copy.evidence" min-width="220" />
              <ElTableColumn prop="reviewComment" :label="copy.reviewComment" min-width="220"><template #default="{ row }">{{ row.reviewComment ?? copy.unavailable }}</template></ElTableColumn>
            </ElTable>
          </section>
        </template>
      </template>
    </ElCard>

    <ElDialog :model-value="reviewTarget !== null" data-test="policy-review-dialog" :title="copy.reviewTitle" width="min(92vw, 560px)" :close-on-click-modal="false" @close="closeReview">
      <template v-if="reviewTarget">
        <ElAlert type="warning" :title="copy.reviewHint" :closable="false" show-icon />
        <p>{{ [`${copy.version} ${reviewTarget.versionNo}`, reviewTarget.meteringBoundaryId, reviewTarget.energyItemCode].join(' · ') }}</p>
        <ElFormItem :label="copy.reviewCommentLabel"><ElInput v-model="reviewComment" data-test="policy-review-comment" type="textarea" :maxlength="500" show-word-limit :disabled="saving" :placeholder="copy.reviewCommentPlaceholder" /></ElFormItem>
      </template>
      <template #footer>
        <ElButton data-test="policy-review-cancel" :disabled="saving" @click="closeReview()">{{ copy.cancel }}</ElButton>
        <ElButton data-test="policy-review-submit" type="primary" :disabled="!reviewComment.trim() || saving" :loading="saving" @click="submitReview">{{ copy.approve }}</ElButton>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.boundary-summary-policy-panel, .editor-section, .review-list { display: grid; gap: var(--bec-space-section); min-width: 0; }
.panel-heading, .section-heading, .review-row { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
.panel-heading h2, .section-heading h3 { margin: 0; font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
.panel-heading p, .section-heading p { margin: 0; max-width: var(--bec-text-measure); color: var(--bec-color-text-secondary); }
.panel-alert { margin-top: var(--bec-space-section); }
.editor-section { padding-top: var(--bec-space-section); border-top: var(--bec-border-width) solid var(--bec-color-border); margin-top: var(--bec-space-section); }
.form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 3)), 1fr)); gap: var(--bec-space-group); margin-block: var(--bec-space-section); }
.form-grid :deep(.el-form-item), .wide-field { margin-bottom: 0; }
.wide-field { grid-column: 1 / -1; }
.native-input { box-sizing: border-box; min-block-size: var(--bec-control-height); inline-size: 100%; padding-inline: var(--bec-space-tight); color: var(--bec-color-text-primary); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-control); background: var(--bec-color-surface); }
.review-row { padding: var(--bec-space-group); border: var(--bec-border-width) solid var(--bec-color-border); background: var(--bec-color-surface); }
.review-row > div { display: grid; gap: var(--bec-space-tight); min-width: 0; overflow-wrap: anywhere; }
</style>
