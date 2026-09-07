<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElInput,
  ElTag,
} from '@/shared/ui'
import { formatDateTime } from '@/shared/utils/format'
import { t } from '@/locales'
import type { SensitiveChange, SensitiveChangeOperation } from '../models/access-control'

const props = withDefaults(defineProps<{
  change?: SensitiveChange | null
  busy?: boolean
}>(), { change: null, busy: false })
const emit = defineEmits<{
  lookup: [requestId: string]
  submit: [requestId: string]
  withdraw: [requestId: string]
  approve: [requestId: string, comment: string]
  reject: [requestId: string, comment: string]
  execute: [requestId: string]
}>()

const requestId = ref('')
const reviewComment = ref('')
const reviewError = ref<string | null>(null)

watch(() => props.change?.requestId, value => {
  if (value) requestId.value = value
})

const statusType = computed(() => {
  const status = props.change?.status
  if (status === 'EXECUTED') return 'success'
  if (status === 'REJECTED' || status === 'EXECUTION_FAILED') return 'danger'
  if (status === 'APPROVED') return 'warning'
  return 'info'
})

function statusLabel(status?: string) {
  return t(`accessControl.changeStatus.${status ?? 'DRAFT'}`)
}

function operationLabel(operation?: SensitiveChangeOperation) {
  const keys: Record<SensitiveChangeOperation, string> = {
    OPEN_USER_ACCOUNT: 'openAccount',
    ISSUE_PASSWORD_RESET_TOKEN: 'resetPassword',
    DELETE_USER_ACCOUNT: 'deleteUser',
    RESTORE_USER_ACCOUNT: 'restoreUser',
    UPDATE_USER_STATUS: 'updateStatus',
    REPLACE_USER_FORMAL_ROLES: 'replaceRoles',
    REPLACE_USER_BUILDINGS: 'replaceBuildings',
    REVOKE_USER_BUILDING: 'revokeBuilding',
    REPLACE_ROLE_MENUS: 'replaceRoleMenus',
    CREATE_MENU: 'createMenu',
    UPDATE_MENU: 'updateMenu',
    DELETE_MENU: 'deleteMenu',
    ENABLE_DEVICE_PRODUCT: 'enableDeviceProduct',
    DISABLE_DEVICE_PRODUCT: 'disableDeviceProduct',
    BIND_PENDING_DEVICE: 'bindPendingDevice',
    ACTIVATE_DEVICE_IDENTITY: 'activateDeviceIdentity',
    DEACTIVATE_DEVICE_IDENTITY: 'deactivateDeviceIdentity',
  }
  return t(`accessControl.operation.${keys[operation ?? 'OPEN_USER_ACCOUNT']}`)
}

function lookup() {
  const value = requestId.value.trim()
  if (value) emit('lookup', value)
}

function review(action: 'approve' | 'reject') {
  const change = props.change
  const comment = reviewComment.value.trim()
  if (!change || !comment) {
    reviewError.value = t('accessControl.change.reviewCommentRequired')
    return
  }
  reviewError.value = null
  if (action === 'approve') emit('approve', change.requestId, comment)
  else emit('reject', change.requestId, comment)
}
</script>

<template>
  <ElCard shadow="never" class="change-request">
    <div class="heading">
      <div>
        <h2>{{ t('accessControl.change.title') }}</h2>
        <p>{{ t('accessControl.change.description') }}</p>
      </div>
      <div class="lookup">
        <ElInput v-model="requestId" :placeholder="t('accessControl.change.requestIdPlaceholder')" :aria-label="t('accessControl.change.requestId')" />
        <ElButton :loading="busy" @click="lookup">{{ t('accessControl.change.lookup') }}</ElButton>
      </div>
    </div>

    <ElAlert v-if="change?.oneTimeToken" :title="t('accessControl.change.tokenTitle')" :description="t('accessControl.change.tokenDescription')" type="warning" show-icon :closable="false" />
    <ElInput v-if="change?.oneTimeToken" :model-value="change.oneTimeToken" readonly :aria-label="t('accessControl.change.tokenTitle')" />

    <template v-if="change">
      <ElDescriptions :column="1" border>
        <ElDescriptionsItem :label="t('accessControl.change.requestId')">{{ change.requestId }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="t('accessControl.change.operation')">{{ operationLabel(change.operationCode) }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="t('accessControl.change.status')"><ElTag :type="statusType">{{ statusLabel(change.status) }}</ElTag></ElDescriptionsItem>
        <ElDescriptionsItem v-if="change.submittedAt" :label="t('accessControl.change.submittedAt')">{{ formatDateTime(change.submittedAt) }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="change.reviewComment" :label="t('accessControl.change.reviewComment')">{{ change.reviewComment }}</ElDescriptionsItem>
      </ElDescriptions>

      <ElAlert v-if="reviewError" :title="reviewError" type="error" show-icon :closable="false" />
      <div v-if="change.status === 'PENDING_REVIEW'" class="review">
        <ElInput v-model="reviewComment" type="textarea" :rows="3" :maxlength="500" show-word-limit :placeholder="t('accessControl.change.reviewCommentPlaceholder')" />
        <div class="actions">
          <ElButton :loading="busy" @click="emit('withdraw', change.requestId)">{{ t('accessControl.change.withdraw') }}</ElButton>
          <ElButton type="danger" :loading="busy" @click="review('reject')">{{ t('accessControl.change.reject') }}</ElButton>
          <ElButton type="primary" :loading="busy" @click="review('approve')">{{ t('accessControl.change.approve') }}</ElButton>
        </div>
      </div>
      <div v-else class="actions">
        <ElButton v-if="change.status === 'DRAFT'" type="primary" :loading="busy" @click="emit('submit', change.requestId)">{{ t('accessControl.change.submit') }}</ElButton>
        <ElButton v-if="change.status === 'APPROVED'" type="primary" :loading="busy" @click="emit('execute', change.requestId)">{{ t('accessControl.change.execute') }}</ElButton>
      </div>
    </template>
  </ElCard>
</template>

<style scoped>
.change-request { display: grid; gap: var(--bec-space-group); }
.heading { display: flex; align-items: start; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
h2 { margin: 0; font-size: var(--bec-font-size-title); }
p { margin: var(--bec-space-tight) 0 0; color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }
.lookup { display: flex; gap: var(--bec-space-tight); flex: 1 1 var(--bec-navigation-width); }
.review { display: grid; gap: var(--bec-space-group); }
.actions { display: flex; justify-content: end; gap: var(--bec-space-tight); flex-wrap: wrap; }
</style>
