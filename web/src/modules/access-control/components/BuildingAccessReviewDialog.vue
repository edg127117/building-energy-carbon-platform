<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElButton, ElDescriptions, ElDescriptionsItem, ElDialog, ElForm, ElFormItem, ElInput, ElTag } from '@/shared/ui'
import { t } from '@/locales'
import type { BuildingAccessRequest } from '../models/access-control'

const props = withDefaults(defineProps<{
  modelValue: boolean
  request?: BuildingAccessRequest | null
  submitting?: boolean
}>(), { request: null, submitting: false })
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  approve: [comment?: string]
  reject: [comment?: string]
}>()
const comment = ref('')

watch(() => [props.modelValue, props.request] as const, () => {
  comment.value = props.request?.reviewComment ?? ''
}, { immediate: true })

function statusLabel(status?: string) {
  return t(`accessControl.buildingAccess.status.${status ?? 'PENDING'}`)
}
</script>

<template>
  <ElDialog :model-value="modelValue" :title="t('accessControl.buildingAccess.reviewTitle')" :width="'var(--bec-dialog-width)'" @update:model-value="emit('update:modelValue', $event)">
    <ElDescriptions v-if="request" :column="1" border>
      <ElDescriptionsItem :label="t('accessControl.buildingAccess.applicant')">{{ request.username ?? request.userId }}</ElDescriptionsItem>
      <ElDescriptionsItem :label="t('accessControl.buildingAccess.building')">{{ request.buildingName ?? request.buildingId }}</ElDescriptionsItem>
      <ElDescriptionsItem :label="t('accessControl.buildingAccess.reason')">{{ request.reason }}</ElDescriptionsItem>
      <ElDescriptionsItem :label="t('accessControl.buildingAccess.statusLabel')"><ElTag>{{ statusLabel(request.status) }}</ElTag></ElDescriptionsItem>
    </ElDescriptions>
    <ElForm label-position="top">
      <ElFormItem :label="t('accessControl.buildingAccess.comment')"><ElInput v-model="comment" type="textarea" :rows="3" :maxlength="500" show-word-limit :placeholder="t('accessControl.buildingAccess.commentPlaceholder')" /></ElFormItem>
    </ElForm>
    <template #footer>
      <ElButton @click="emit('update:modelValue', false)">{{ t('accessControl.action.cancel') }}</ElButton>
      <template v-if="request?.status === 'PENDING'">
        <ElButton type="danger" :loading="submitting" @click="emit('reject', comment.trim() || undefined)">{{ t('accessControl.buildingAccess.reject') }}</ElButton>
        <ElButton type="primary" :loading="submitting" @click="emit('approve', comment.trim() || undefined)">{{ t('accessControl.buildingAccess.approve') }}</ElButton>
      </template>
    </template>
  </ElDialog>
</template>
