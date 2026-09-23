<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { getApprovalPolicy, listChangeRequests } from '../api/access-control'
import {
  CopyableValue, ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElEmpty,
  ElInput,
  ElPagination,
  ElTag,
} from '@/shared/ui'
import { formatDateTime } from '@/shared/utils/format'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { useSession } from '@/modules/auth/public'
import { t } from '@/locales'
import type { SensitiveChange, SensitiveChangeList, SensitiveChangeListItem } from '../models/access-control'

const props = withDefaults(defineProps<{
  change?: SensitiveChange | null
  businessView?: boolean
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
const session = useSession()
const reviewComment = ref('')
const reviewError = ref<string | null>(null)
const selfApprovalAllowed = ref(false)
const policyUnavailable = ref(false)
const listScope = ref<'MINE' | 'REVIEW'>('MINE')
const list = ref<SensitiveChangeList>({ page: 1, size: 10, total: 0, items: [] })
const listLoading = ref(false)
const listLoaded = ref(false)
const listError = ref<string | null>(null)
let listSequence = 0

async function loadList(page = list.value.page) {
  const sequence = ++listSequence
  listLoading.value = true
  listError.value = null
  try {
    const result = await listChangeRequests(listScope.value, page)
    if (sequence === listSequence) {
      list.value = result
      listLoaded.value = true
    }
  } catch (reason) {
    if (sequence === listSequence) {
      list.value = { page, size: list.value.size, total: 0, items: [] }
      listError.value = requestErrorMessage(reason)
      listLoaded.value = true
    }
  } finally {
    if (sequence === listSequence) listLoading.value = false
  }
}

function changeListScope(scope: 'MINE' | 'REVIEW') {
  if (scope === listScope.value) return
  listScope.value = scope
  list.value = { page: 1, size: 10, total: 0, items: [] }
  listLoaded.value = false
  void loadList(1)
}

onMounted(async () => {
  void loadList()
  try {
    const policy = await getApprovalPolicy()
    selfApprovalAllowed.value = policy.selfApprovalAllowed
      && ['DEVELOPMENT', 'TEST'].includes(policy.environmentMode)
  } catch {
    policyUnavailable.value = true
  }
})

watch(() => [props.change?.requestId, props.change?.status], ([value]) => {
  if (value) {
    requestId.value = value
    reviewComment.value = ''
    void loadList()
  }
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

function operationLabel(operation?: string) {
  const keys: Record<string, string> = {
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
    PUBLISH_PROTOCOL_CONFIGURATION: 'publishProtocolConfiguration',
    GRANT_BACKEND_DUTY: 'grantBackendDuty',
    REVOKE_BACKEND_DUTY: 'revokeBackendDuty',
    SET_AUDIT_RETENTION_POLICY: 'setAuditRetentionPolicy',
    RELEASE_AUDIT_EVIDENCE_HOLD: 'releaseAuditEvidenceHold',
    DELETE_AUDIT_EVIDENCE_EXCEPTION: 'deleteAuditEvidenceException',
  }
  const key = operation ? keys[operation] : undefined
  return key ? t(`accessControl.operation.${key}`) : operation ?? '—'
}

const selectedSubmitter = computed(() => list.value.items.find(item => item.requestId === props.change?.requestId)?.submitterName
  ?? props.change?.submittedBy)
const canReview = computed(() => listScope.value === 'REVIEW' && listLoaded.value && !listError.value
  && (props.change?.submittedBy !== session.user?.id || selfApprovalAllowed.value))
const canWithdraw = computed(() => props.change?.submittedBy === session.user?.id)
function rowStatusType(status: string): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 'EXECUTED') return 'success'
  if (status === 'REJECTED' || status === 'EXECUTION_FAILED') return 'danger'
  if (status === 'APPROVED') return 'warning'
  return 'info'
}

function requestMeta(item: SensitiveChangeListItem) {
  const submitter = item.submitterName ?? `#${item.submittedBy}`
  return `${t('accessControl.change.applicant')}：${submitter} · ${formatDateTime(item.submittedAt ?? item.createTime)}`
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
      <details class="id-lookup">
        <summary>{{ t('accessControl.change.lookupById') }}</summary>
        <div class="lookup">
          <ElInput v-model="requestId" :placeholder="t('accessControl.change.requestIdPlaceholder')" :aria-label="t('accessControl.change.requestId')" />
          <ElButton :loading="busy" @click="lookup">{{ t('accessControl.change.lookup') }}</ElButton>
        </div>
      </details>
    </div>

    <ElAlert v-if="change?.oneTimeToken" :title="t('accessControl.change.tokenTitle')" :description="t('accessControl.change.tokenDescription')" type="warning" show-icon :closable="false" />
    <ElInput v-if="change?.oneTimeToken" :model-value="change.oneTimeToken" readonly :aria-label="t('accessControl.change.tokenTitle')" />

    <ElAlert v-if="selfApprovalAllowed" :title="t('accessControl.change.localSelfApproval')" type="warning" show-icon :closable="false" />
    <ElAlert v-if="policyUnavailable" :title="t('accessControl.change.policyUnavailable')" type="info" show-icon :closable="false" />

    <section class="request-inbox" :aria-label="t('accessControl.change.listTitle')">
      <div class="list-toolbar">
        <div class="list-scopes">
          <ElButton :type="listScope === 'MINE' ? 'primary' : 'default'" :aria-pressed="listScope === 'MINE'" @click="changeListScope('MINE')">{{ t('accessControl.change.mine') }}</ElButton>
          <ElButton :type="listScope === 'REVIEW' ? 'primary' : 'default'" :aria-pressed="listScope === 'REVIEW'" @click="changeListScope('REVIEW')">{{ t('accessControl.change.reviewQueue') }}</ElButton>
        </div>
        <ElButton :loading="listLoading" @click="loadList()">{{ t('accessControl.change.refresh') }}</ElButton>
      </div>
      <ElAlert v-if="listError" :title="listError" type="error" show-icon :closable="false" />
      <p v-if="listLoading && !listLoaded" role="status">{{ t('accessControl.change.loading') }}</p>
      <ElEmpty v-else-if="listLoaded && !listError && !list.items.length" :description="t(listScope === 'MINE' ? 'accessControl.change.emptyMine' : 'accessControl.change.emptyReview')" />
      <ul v-else-if="!listError && list.items.length" class="request-items">
        <li v-for="item in list.items" :key="item.requestId" class="request-item">
          <div class="request-summary"><strong>{{ operationLabel(item.operationCode) }}</strong><ElTag :type="rowStatusType(item.status)">{{ statusLabel(item.status) }}</ElTag></div>
          <p class="request-meta">{{ requestMeta(item) }}</p>
          <p v-if="item.impactSummary" class="request-impact">{{ item.impactSummary }}</p>
          <ElButton text type="primary" :loading="busy" @click="emit('lookup', item.requestId)">{{ t('accessControl.change.openRequest') }}</ElButton>
        </li>
      </ul>
      <ElPagination v-if="!listError && list.total > list.size" background layout="total, prev, pager, next" :current-page="list.page" :page-size="list.size" :total="list.total" @current-change="loadList" />
    </section>

    <template v-if="change">
      <ElDescriptions :column="1" border>
        <ElDescriptionsItem v-if="!businessView" :label="t('accessControl.change.requestId')">{{ change.requestId }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="t('accessControl.change.operation')">{{ operationLabel(change.operationCode) }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="t('accessControl.change.status')"><ElTag :type="statusType">{{ statusLabel(change.status) }}</ElTag></ElDescriptionsItem>
        <ElDescriptionsItem :label="t('accessControl.change.applicant')">{{ selectedSubmitter }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="change.submittedAt" :label="t('accessControl.change.submittedAt')">{{ formatDateTime(change.submittedAt) }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="change.reviewComment" :label="t('accessControl.change.reviewComment')">{{ change.reviewComment }}</ElDescriptionsItem>
      </ElDescriptions>

      <details v-if="businessView"><summary>{{ t('accessControl.change.requestId') }}</summary><CopyableValue :value="change.requestId" /></details>
      <ElAlert v-if="reviewError" :title="reviewError" type="error" show-icon :closable="false" />
      <div v-if="change.status === 'PENDING_REVIEW'" class="review">
        <ElInput v-if="canReview" v-model="reviewComment" type="textarea" :rows="3" :maxlength="500" show-word-limit :placeholder="t('accessControl.change.reviewCommentPlaceholder')" />
        <div class="actions">
          <ElButton v-if="canWithdraw" :loading="busy" @click="emit('withdraw', change.requestId)">{{ t('accessControl.change.withdraw') }}</ElButton>
          <ElButton v-if="canReview" type="danger" :loading="busy" @click="review('reject')">{{ t('accessControl.change.reject') }}</ElButton>
          <ElButton v-if="canReview" type="primary" :loading="busy" @click="review('approve')">{{ t('accessControl.change.approve') }}</ElButton>
        </div>
      </div>
      <div v-else class="actions">
        <ElButton v-if="change.status === 'DRAFT' && canWithdraw" type="primary" :loading="busy" @click="emit('submit', change.requestId)">{{ t('accessControl.change.submit') }}</ElButton>
        <ElButton v-if="change.status === 'APPROVED' && canReview && change.reviewerId === session.user?.id" type="primary" :loading="busy" @click="emit('execute', change.requestId)">{{ t('accessControl.change.execute') }}</ElButton>
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
.id-lookup { min-width: var(--bec-navigation-width); }
.id-lookup summary { cursor: pointer; color: var(--bec-color-text-secondary); }
.id-lookup .lookup { margin-top: var(--bec-space-tight); }
.request-inbox { display: grid; gap: var(--bec-space-group); min-width: 0; }
.list-toolbar, .list-scopes, .request-summary { display: flex; align-items: center; gap: var(--bec-space-tight); flex-wrap: wrap; }
.list-toolbar { justify-content: space-between; }
.list-scopes :deep(.el-button + .el-button) { margin-left: 0; }
.request-items { display: grid; gap: var(--bec-space-tight); list-style: none; padding: 0; margin: 0; }
.request-item { min-width: 0; padding: var(--bec-space-group); border: var(--bec-border-width) solid var(--bec-color-divider); border-radius: var(--bec-radius-control); }
.request-item p { margin: var(--bec-space-tight) 0 0; overflow-wrap: anywhere; }
.request-meta { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.request-impact { color: var(--bec-color-text-primary); }
.request-inbox :deep(.el-pagination) { justify-self: end; }
.review { display: grid; gap: var(--bec-space-group); }
.actions { display: flex; justify-content: end; gap: var(--bec-space-tight); flex-wrap: wrap; }
</style>
