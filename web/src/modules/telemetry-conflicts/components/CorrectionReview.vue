<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useSession } from '@/modules/auth/public'
import { ElAlert, ElButton, ElDescriptions, ElDescriptionsItem, ElForm, ElFormItem, ElInput, ElSkeleton } from '@/shared/ui'
import { t } from '@/locales'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { telemetryConflictApi } from '../api/telemetry-conflicts'
import type { ConflictView, CorrectionVersion } from '../models/telemetry-conflicts'

const props = defineProps<{ conflict: ConflictView }>()
const emit = defineEmits<{ approved: [] }>()
const session = useSession()
const version = ref<CorrectionVersion | null>(null)
const comment = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)
const notice = ref<string | null>(null)
let generation = 0
// 角色仅控制入口展示；审核职责、建筑范围及职责分离继续由既有审核服务判定。
const reviewerRole = computed(() => session.user?.roles.some(role => ['ENERGY_MANAGER', 'PLATFORM_ADMIN'].includes(role)) === true)
const canApprove = computed(() => reviewerRole.value && version.value?.status === 'PENDING_REVIEW' && !loading.value && !saving.value && comment.value.trim().length > 0)

async function load() {
  const ticket = ++generation
  const conflict = props.conflict
  version.value = null
  error.value = null
  comment.value = ''
  loading.value = true
  try {
    const versions = await telemetryConflictApi.correctionVersions(conflict.buildingId, conflict.pointId)
    if (ticket !== generation) return
    // 不用测点的“最新版本”替代冲突引用；否则可能批准另一条事实的更正。
    version.value = versions.find(item => item.correctionVersionId === conflict.correctionVersionId
      && item.buildingId === conflict.buildingId && item.meterPointId === conflict.pointId
      && item.originalFactIdentity === `${conflict.pointId}@${conflict.eventTime}`) ?? null
    if (!version.value) error.value = t('telemetryConflicts.review.notFound')
  } catch (reason) {
    if (ticket === generation) error.value = requestErrorMessage(reason)
  } finally {
    if (ticket === generation) loading.value = false
  }
}

async function approve() {
  const current = version.value
  if (!canApprove.value || !current) return
  const ticket = generation
  saving.value = true
  error.value = null
  notice.value = null
  try {
    await telemetryConflictApi.approveCorrection(current.correctionVersionId, {
      expectedRevision: current.configRevision, reviewComment: comment.value.trim(),
    })
    if (ticket !== generation) return
    version.value = null
    notice.value = t('telemetryConflicts.review.approved')
    // 审核返回不用于乐观改写有效值；父页面重新读取事实，计算结果由后台任务更新。
    emit('approved')
  } catch (reason) {
    if (ticket !== generation) return
    error.value = requestErrorMessage(reason)
    version.value = null
  } finally {
    if (ticket === generation) saving.value = false
  }
}

watch(() => [props.conflict.buildingId, props.conflict.pointId, props.conflict.eventTime, props.conflict.correctionVersionId], () => {
  saving.value = false
  notice.value = null
  void load()
}, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <section class="correction-review" :aria-label="t('telemetryConflicts.review.title')">
    <h2>{{ t('telemetryConflicts.review.title') }}</h2>
    <ElAlert :title="t('telemetryConflicts.review.boundary')" type="info" :closable="false" />
    <ElAlert v-if="error" :title="error" type="error" :closable="false" />
    <ElAlert v-if="notice" :title="notice" type="success" :closable="false" />
    <ElSkeleton v-if="loading" :rows="3" animated />
    <ElDescriptions v-else-if="version" :column="2" border>
      <ElDescriptionsItem :label="t('telemetryConflicts.fields.correctionVersionId')">{{ version.correctionVersionId }}</ElDescriptionsItem>
      <ElDescriptionsItem :label="t('telemetryConflicts.review.revision')">{{ version.configRevision }}</ElDescriptionsItem>
      <ElDescriptionsItem :label="t('telemetryConflicts.fields.originalValue')">{{ version.originalValue }}</ElDescriptionsItem>
      <ElDescriptionsItem :label="t('telemetryConflicts.fields.candidateValue')">{{ version.correctedValue }}</ElDescriptionsItem>
      <ElDescriptionsItem :label="t('telemetryConflicts.review.creator')">{{ version.createdBy }}</ElDescriptionsItem>
      <ElDescriptionsItem :label="t('telemetryConflicts.fields.state')">{{ version.status }}</ElDescriptionsItem>
      <ElDescriptionsItem :label="t('telemetryConflicts.fields.reason')">{{ version.correctionReason }}</ElDescriptionsItem>
      <ElDescriptionsItem :label="t('telemetryConflicts.fields.evidenceReference')">{{ version.evidenceReference }}</ElDescriptionsItem>
    </ElDescriptions>
    <ElForm label-position="top" @submit.prevent="approve">
      <ElFormItem :label="t('telemetryConflicts.review.comment')" required>
        <ElInput v-model="comment" data-testid="correction-review-comment" type="textarea" :maxlength="500" :disabled="saving || !reviewerRole" />
      </ElFormItem>
      <ElButton data-testid="approve-correction" type="primary" native-type="submit" :disabled="!canApprove" :loading="saving">{{ t('telemetryConflicts.review.approve') }}</ElButton>
      <ElButton :disabled="saving || loading" @click="load">{{ t('telemetryConflicts.actions.refresh') }}</ElButton>
    </ElForm>
  </section>
</template>

<style scoped>
.correction-review { display: grid; gap: var(--bec-space-group); }
h2 { margin: 0; font-size: var(--bec-font-size-title); }
</style>
