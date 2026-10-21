<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElDescriptions, ElDescriptionsItem, ElDialog, ElEmpty, ElFormItem, ElInput, ElSkeleton, ElTag } from '@/shared/ui'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { classificationApi } from '../api/classification'
import type { ReadingBatchView, ReadingImportView } from '../models/classification'
import copy from '../locales/reading-batch-review.zh-CN'

const props = defineProps<{ buildingId: string; batchId: string }>()
const emit = defineEmits<{ close: []; approved: [result: ReadingImportView] }>()

const detail = ref<ReadingBatchView | null>(null)
const reason = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
let generation = 0

const canApprove = computed(() => detail.value?.status === 'PENDING_REVIEW' && !!reason.value.trim() && !loading.value && !saving.value)

watch(() => [props.buildingId, props.batchId], () => { void load() }, { immediate: true })

function isCurrent(owner: number, buildingId: string, batchId: string): boolean {
  return owner === generation && buildingId === props.buildingId.trim() && batchId === props.batchId.trim()
}

async function load(): Promise<void> {
  const buildingId = props.buildingId.trim()
  const batchId = props.batchId.trim()
  const owner = ++generation
  detail.value = null
  reason.value = ''
  error.value = ''
  notice.value = ''
  saving.value = false
  if (!buildingId || !batchId) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const result = await classificationApi.getReadingBatch(buildingId, batchId)
    if (isCurrent(owner, buildingId, batchId)) detail.value = result
  } catch (failure) {
    if (isCurrent(owner, buildingId, batchId)) error.value = requestErrorMessage(failure)
  } finally {
    if (isCurrent(owner, buildingId, batchId)) loading.value = false
  }
}

function close(): void {
  generation++
  loading.value = false
  saving.value = false
  emit('close')
}

async function approve(): Promise<void> {
  const batch = detail.value
  const buildingId = props.buildingId.trim()
  const batchId = props.batchId.trim()
  const reviewReason = reason.value.trim()
  if (!batch || batch.status !== 'PENDING_REVIEW' || !buildingId || !batchId || !reviewReason || saving.value) return
  const owner = ++generation
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await classificationApi.approveReadingBatch(buildingId, batchId, {
      expectedRevision: batch.revision,
      reason: reviewReason,
      effectiveFrom: null,
    })
    if (!isCurrent(owner, buildingId, batchId)) return
    detail.value = { ...batch, status: result.status }
    reason.value = ''
    notice.value = copy.approved
    emit('approved', result)
  } catch (failure) {
    if (isCurrent(owner, buildingId, batchId)) error.value = requestErrorMessage(failure)
  } finally {
    if (isCurrent(owner, buildingId, batchId)) saving.value = false
  }
}
</script>

<template>
  <ElDialog :model-value="Boolean(batchId)" data-test="reading-batch-review-dialog" :title="copy.title" width="min(92vw, 560px)" :close-on-click-modal="false" @close="close">
    <ElAlert v-if="error" type="error" :title="error" :closable="false" show-icon />
    <ElAlert v-if="notice" type="success" :title="notice" :closable="false" show-icon />
    <ElSkeleton v-if="loading" animated :rows="4" />
    <ElEmpty v-else-if="!detail" :description="copy.empty" :image-size="48" />
    <template v-else>
      <ElDescriptions :column="2" border>
        <ElDescriptionsItem :label="copy.status"><ElTag>{{ detail.status }}</ElTag></ElDescriptionsItem>
        <ElDescriptionsItem :label="copy.author">{{ detail.createdBy }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="copy.revision">{{ detail.revision }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="copy.count">{{ detail.count }}</ElDescriptionsItem>
      </ElDescriptions>
      <ElAlert v-if="detail.status !== 'PENDING_REVIEW'" class="review-alert" type="info" :title="copy.notPending" :closable="false" show-icon />
      <template v-else>
        <ElAlert class="review-alert" type="warning" :title="copy.independence" :closable="false" show-icon />
        <ElFormItem :label="copy.reason"><ElInput v-model="reason" data-test="reading-batch-review-reason" type="textarea" :maxlength="500" show-word-limit :disabled="saving" :placeholder="copy.reasonPlaceholder" /></ElFormItem>
      </template>
    </template>
    <template #footer>
      <ElButton data-test="reading-batch-review-refresh" :disabled="loading || saving" :loading="loading" @click="load">{{ copy.refresh }}</ElButton>
      <ElButton data-test="reading-batch-review-cancel" :disabled="saving" @click="close">{{ copy.cancel }}</ElButton>
      <ElButton v-if="detail?.status === 'PENDING_REVIEW'" data-test="reading-batch-review-submit" type="primary" :disabled="!canApprove" :loading="saving" @click="approve">{{ copy.submit }}</ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.review-alert { margin-block: var(--bec-space-section); }
</style>
