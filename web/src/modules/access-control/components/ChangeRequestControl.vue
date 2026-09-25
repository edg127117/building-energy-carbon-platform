<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { approveChangeRequest, executeChangeRequest, getApprovalPolicy, listChangeRequests } from '../api/access-control'
import {
  CopyableValue, ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
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
  inbox?: boolean
  businessView?: boolean
  busy?: boolean
}>(), { change: null, inbox: false, busy: false })
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
const mineView = ref<'ACTIVE' | 'HISTORY'>('ACTIVE')
const list = ref<SensitiveChangeList>({ page: 1, size: 10, total: 0, items: [] })
const listLoading = ref(false)
const listLoaded = ref(false)
const listError = ref<string | null>(null)
let listSequence = 0
const batchSelected = ref<string[]>([])
const batchOpen = ref(false)
const batchComment = ref('')
const batchBusy = ref(false)
const batchError = ref<string | null>(null)
const batchResults = ref<Array<{ requestId: string; success: boolean; message: string }>>([])

async function loadList(page = list.value.page) {
  const sequence = ++listSequence
  listLoading.value = true
  listError.value = null
  try {
    const result = await listChangeRequests(listScope.value, page, list.value.size,
      listScope.value === 'MINE' ? mineView.value : 'ALL')
    if (sequence === listSequence) {
      list.value = result
      listLoaded.value = true
      batchSelected.value = []
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
  batchResults.value = []
  void loadList(1)
}

function changeMineView(view: 'ACTIVE' | 'HISTORY') {
  if (view === mineView.value) return
  mineView.value = view
  list.value = { page: 1, size: list.value.size, total: 0, items: [] }
  listLoaded.value = false
  batchResults.value = []
  void loadList(1)
}

function changePageSize(size: number) {
  list.value = { page: 1, size, total: 0, items: [] }
  listLoaded.value = false
  void loadList(1)
}

type BatchGroup = 'BIND' | 'ACTIVATE'
function batchGroup(item: SensitiveChangeListItem): BatchGroup | null {
  if (['BIND_PENDING_DEVICE', 'BIND_TYPED_PENDING_DEVICE', 'BIND_HVAC_TEMPERATURE'].includes(item.operationCode)) return 'BIND'
  if (item.operationCode === 'ACTIVATE_DEVICE_IDENTITY') return 'ACTIVATE'
  return null
}

function batchEligible(item: SensitiveChangeListItem): boolean {
  return listScope.value === 'REVIEW'
    && batchGroup(item) !== null
    && ['PENDING_REVIEW', 'APPROVED'].includes(item.status)
    && (item.submittedBy !== session.user?.id || selfApprovalAllowed.value)
}

function toggleBatch(requestId: string, checked: boolean) {
  if (!checked) {
    batchSelected.value = batchSelected.value.filter(id => id !== requestId)
    return
  }
  const item = list.value.items.find(row => row.requestId === requestId)
  if (!item || !batchEligible(item)) return
  const group = batchGroup(item)
  batchSelected.value = [...new Set([...batchSelected.value.filter(id => {
    const existing = list.value.items.find(row => row.requestId === id)
    return existing && batchGroup(existing) === group
  }), requestId])]
}

const selectedBatchItems = computed(() => list.value.items.filter(item =>
  batchSelected.value.includes(item.requestId) && batchEligible(item)))
const eligibleBatchItems = computed(() => list.value.items.filter(batchEligible))
const selectedBatchGroup = computed(() => selectedBatchItems.value.length ? batchGroup(selectedBatchItems.value[0]!) : null)
function selectBatchGroup(group: BatchGroup) {
  const ids = eligibleBatchItems.value.filter(item => batchGroup(item) === group).map(item => item.requestId)
  batchSelected.value = ids.every(id => batchSelected.value.includes(id)) ? [] : ids
}

/** 沿用逐条审核和执行接口及其职责校验；失败只影响该申请，逐项展示结果。 */
async function processBatch() {
  const items = selectedBatchItems.value
  if (!items.length || batchBusy.value) return
  const comment = batchComment.value.trim()
  if (items.some(item => item.status === 'PENDING_REVIEW') && !comment) {
    batchError.value = t('accessControl.change.reviewCommentRequired')
    return
  }
  batchBusy.value = true
  batchError.value = null
  const results: typeof batchResults.value = []
  for (const item of items) {
    try {
      if (item.status === 'PENDING_REVIEW') await approveChangeRequest(item.requestId, comment)
      const executed = await executeChangeRequest(item.requestId)
      results.push({ requestId: item.requestId, success: executed.status === 'EXECUTED',
        message: executed.status === 'EXECUTED' ? t('accessControl.change.batchSucceeded') : statusLabel(executed.status) })
    } catch (reason) {
      results.push({ requestId: item.requestId, success: false, message: requestErrorMessage(reason) })
    }
  }
  batchResults.value = results
  batchOpen.value = false
  batchSelected.value = []
  batchBusy.value = false
  await loadList(list.value.page)
  if (props.change && results.some(result => result.requestId === props.change?.requestId)) {
    emit('lookup', props.change.requestId)
  }
}

onMounted(async () => {
  if (!props.inbox) return
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
  if (props.inbox && value) {
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
    BIND_TYPED_PENDING_DEVICE: 'bindTypedPendingDevice',
    BIND_HVAC_TEMPERATURE: 'bindHvacTemperature',
    CONFIGURE_TEMPERATURE_RULE: 'configureTemperatureRule',
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
  return key ? t(`accessControl.operation.${key}`) : t('accessControl.change.otherOperation')
}

function impactLabel(summary?: string | null) {
  if (!summary) return ''
  if (!summary.includes('=')) return summary
  const fields = new Set(['buildingId', 'bindingType', 'pointCount', 'pointMode', 'productId', 'equipmentName', 'action', 'adapter',
    'userId', 'roleId', 'roleCount', 'buildingCount', 'menuCount', 'menuId', 'menuName', 'menuType', 'status'])
  const values = new Set(['TYPED_STATE', 'AUTO', 'MANUAL', 'ENABLE', 'DISABLE', 'ACTIVATE', 'DEACTIVATE', 'ACTIVE', 'INACTIVE', 'M', 'C', 'F', 'CONFIGURE', 'DAIKIN_INDOOR_V2'])
  const parts = summary.split(';').map(part => {
    const separator = part.indexOf('=')
    if (separator < 1) return null
    const key = part.slice(0, separator)
    const value = part.slice(separator + 1)
    const display = key === 'status' && ['0', '1'].includes(value)
      ? t(`accessControl.change.summaryValues.${value}`)
      : values.has(value) ? t(`accessControl.change.summaryValues.${value}`) : value
    return fields.has(key) ? `${t(`accessControl.change.summaryFields.${key}`)}：${display}` : null
  })
  return parts.every(Boolean) ? parts.join(' · ') : t('accessControl.change.summaryUnavailable')
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
  <ElCard v-if="inbox || change" shadow="never" class="change-request">
    <div class="heading">
      <div>
        <h2>{{ t('accessControl.change.title') }}</h2>
        <p>{{ t(inbox ? 'accessControl.change.description' : 'accessControl.change.businessDescription') }}</p>
      </div>
      <details v-if="inbox" class="id-lookup">
        <summary>{{ t('accessControl.change.lookupById') }}</summary>
        <div class="lookup">
          <ElInput v-model="requestId" :placeholder="t('accessControl.change.requestIdPlaceholder')" :aria-label="t('accessControl.change.requestId')" />
          <ElButton :loading="busy" @click="lookup">{{ t('accessControl.change.lookup') }}</ElButton>
        </div>
      </details>
    </div>

    <ElAlert v-if="change?.oneTimeToken" :title="t('accessControl.change.tokenTitle')" :description="t('accessControl.change.tokenDescription')" type="warning" show-icon :closable="false" />
    <ElInput v-if="change?.oneTimeToken" :model-value="change.oneTimeToken" readonly :aria-label="t('accessControl.change.tokenTitle')" />

    <ElAlert v-if="inbox && selfApprovalAllowed" :title="t('accessControl.change.localSelfApproval')" type="warning" show-icon :closable="false" />
    <ElAlert v-if="inbox && policyUnavailable" :title="t('accessControl.change.policyUnavailable')" type="info" show-icon :closable="false" />
    <RouterLink v-if="!inbox" :to="{ path: '/configuration/access/changeRequests', query: change ? { requestId: change.requestId } : {} }" class="manage-link">{{ t('accessControl.change.manageInInbox') }}</RouterLink>

    <details v-if="inbox" class="request-inbox">
      <summary>{{ t('accessControl.change.listTitle') }}</summary>
      <div class="list-toolbar">
        <div class="list-scopes">
          <ElButton :type="listScope === 'MINE' ? 'primary' : 'default'" :aria-pressed="listScope === 'MINE'" @click="changeListScope('MINE')">{{ t('accessControl.change.mine') }}</ElButton>
          <ElButton :type="listScope === 'REVIEW' ? 'primary' : 'default'" :aria-pressed="listScope === 'REVIEW'" @click="changeListScope('REVIEW')">{{ t('accessControl.change.reviewQueue') }}</ElButton>
        </div>
        <ElButton :loading="listLoading" @click="loadList()">{{ t('accessControl.change.refresh') }}</ElButton>
      </div>
      <div v-if="listScope === 'MINE'" class="list-scopes">
        <ElButton :type="mineView === 'ACTIVE' ? 'primary' : 'default'" :aria-pressed="mineView === 'ACTIVE'" @click="changeMineView('ACTIVE')">{{ t('accessControl.change.activeRequests') }}</ElButton>
        <ElButton :type="mineView === 'HISTORY' ? 'primary' : 'default'" :aria-pressed="mineView === 'HISTORY'" @click="changeMineView('HISTORY')">{{ t('accessControl.change.historyRequests') }}</ElButton>
      </div>
      <div v-if="listScope === 'REVIEW' && eligibleBatchItems.length && !listLoading" class="batch-toolbar">
        <ElButton v-if="eligibleBatchItems.some(item => batchGroup(item) === 'BIND')" @click="selectBatchGroup('BIND')">{{ t('accessControl.change.selectPageBindings') }}</ElButton>
        <ElButton v-if="eligibleBatchItems.some(item => batchGroup(item) === 'ACTIVATE')" @click="selectBatchGroup('ACTIVATE')">{{ t('accessControl.change.selectPageActivations') }}</ElButton>
        <ElButton v-if="selectedBatchItems.length" @click="batchSelected = []">{{ t('accessControl.change.clearPageSelection') }}</ElButton>
        <span v-if="selectedBatchItems.length">{{ t('accessControl.change.batchSelected') }}{{ selectedBatchItems.length }}</span>
        <ElButton v-if="selectedBatchItems.length" type="primary" @click="batchOpen = true">{{ t('accessControl.change.batchApproveExecute') }}</ElButton>
      </div>
      <ElAlert v-if="batchResults.length" :title="t('accessControl.change.batchResultTitle')" type="info" show-icon :closable="false" />
      <ul v-if="batchResults.length" class="batch-results">
        <li v-for="result in batchResults" :key="result.requestId">
          <strong>{{ result.requestId }}</strong>{{ t('accessControl.change.separator') }}{{ result.message }}
        </li>
      </ul>
      <ElAlert v-if="listError" :title="listError" type="error" show-icon :closable="false" />
      <p v-if="listLoading && !listLoaded" role="status">{{ t('accessControl.change.loading') }}</p>
      <ElEmpty v-else-if="listLoaded && !listError && !list.items.length" :description="t(listScope === 'MINE' ? 'accessControl.change.emptyMine' : 'accessControl.change.emptyReview')" />
      <ul v-else-if="!listError && list.items.length" class="request-items">
        <li v-for="item in list.items" :key="item.requestId" class="request-item">
          <label v-if="batchEligible(item)" class="batch-check">
            <input type="checkbox" :checked="batchSelected.includes(item.requestId)" :disabled="listLoading || batchBusy" :aria-label="`${t('accessControl.change.batchSelect')} ${item.requestId}`" @change="toggleBatch(item.requestId, ($event.target as HTMLInputElement).checked)" />
          </label>
          <div class="request-content">
            <div class="request-summary"><strong>{{ operationLabel(item.operationCode) }}</strong><ElTag :type="rowStatusType(item.status)">{{ statusLabel(item.status) }}</ElTag></div>
            <p class="request-meta">{{ requestMeta(item) }}</p>
            <p v-if="item.impactSummary" class="request-impact">{{ impactLabel(item.impactSummary) }}</p>
          </div>
          <ElButton text type="primary" :loading="busy" @click="emit('lookup', item.requestId)">{{ t('accessControl.change.openRequest') }}</ElButton>
        </li>
      </ul>
      <ElPagination v-if="!listError && list.total > list.size" background layout="total, sizes, prev, pager, next" :page-sizes="[10, 20, 50]" :current-page="list.page" :page-size="list.size" :total="list.total" @current-change="loadList" @size-change="changePageSize" />
    </details>

    <ElDialog v-if="inbox" v-model="batchOpen" :title="t('accessControl.change.batchApproveExecute')" :close-on-click-modal="!batchBusy" :close-on-press-escape="!batchBusy" :show-close="!batchBusy" width="min(36rem, 92vw)">
      <p>{{ t(selectedBatchGroup === 'ACTIVATE' ? 'accessControl.change.batchActivationConfirm' : 'accessControl.change.batchConfirm') }}{{ selectedBatchItems.length }}</p>
      <ul class="batch-preview"><li v-for="item in selectedBatchItems" :key="item.requestId">{{ operationLabel(item.operationCode) }}{{ t('accessControl.change.listSeparator') }}{{ impactLabel(item.impactSummary) || item.requestId }}</li></ul>
      <ElInput v-model="batchComment" type="textarea" :rows="3" :maxlength="500" show-word-limit :placeholder="t('accessControl.change.batchCommentPlaceholder')" :aria-label="t('accessControl.change.reviewComment')" />
      <ElAlert v-if="batchError" :title="batchError" type="error" show-icon :closable="false" />
      <template #footer>
        <ElButton :disabled="batchBusy" @click="batchOpen = false">{{ t('accessControl.change.cancel') }}</ElButton>
        <ElButton type="primary" :loading="batchBusy" @click="processBatch">{{ t('accessControl.change.batchApproveExecute') }}</ElButton>
      </template>
    </ElDialog>

    <template v-if="change">
      <ElDescriptions :column="1" border>
        <ElDescriptionsItem v-if="!businessView" :label="t('accessControl.change.requestId')">{{ change.requestId }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="t('accessControl.change.operation')">{{ operationLabel(change.operationCode) }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="change.impactSummary" :label="t('accessControl.change.impact')">{{ impactLabel(change.impactSummary) }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="t('accessControl.change.status')"><ElTag :type="statusType">{{ statusLabel(change.status) }}</ElTag></ElDescriptionsItem>
        <ElDescriptionsItem :label="t('accessControl.change.applicant')">{{ selectedSubmitter }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="change.submittedAt" :label="t('accessControl.change.submittedAt')">{{ formatDateTime(change.submittedAt) }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="change.reviewComment" :label="t('accessControl.change.reviewComment')">{{ change.reviewComment }}</ElDescriptionsItem>
      </ElDescriptions>

      <details v-if="businessView"><summary>{{ t('accessControl.change.requestId') }}</summary><CopyableValue :value="change.requestId" /></details>
      <ElAlert v-if="reviewError" :title="reviewError" type="error" show-icon :closable="false" />
      <div v-if="inbox && change.status === 'PENDING_REVIEW'" class="review">
        <ElInput v-if="canReview" v-model="reviewComment" type="textarea" :rows="3" :maxlength="500" show-word-limit :placeholder="t('accessControl.change.reviewCommentPlaceholder')" />
        <div class="actions">
          <ElButton v-if="canWithdraw" :loading="busy" @click="emit('withdraw', change.requestId)">{{ t('accessControl.change.withdraw') }}</ElButton>
          <ElButton v-if="canReview" type="danger" :loading="busy" @click="review('reject')">{{ t('accessControl.change.reject') }}</ElButton>
          <ElButton v-if="canReview" type="primary" :loading="busy" @click="review('approve')">{{ t('accessControl.change.approve') }}</ElButton>
        </div>
      </div>
      <div v-else-if="inbox" class="actions">
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
.manage-link { color: var(--bec-color-action-primary); text-decoration: none; }
.manage-link:hover { text-decoration: underline; }
.request-inbox { min-width: 0; border-top: var(--bec-border-width) solid var(--bec-color-divider); padding-top: var(--bec-space-tight); }
.request-inbox > summary { cursor: pointer; font-weight: var(--bec-font-weight-heading); padding: var(--bec-space-tight) 0; }
.request-inbox[open] > :not(summary) { margin-top: var(--bec-space-group); }
.list-toolbar, .list-scopes, .request-summary { display: flex; align-items: center; gap: var(--bec-space-tight); flex-wrap: wrap; }
.list-toolbar { justify-content: space-between; }
.list-scopes :deep(.el-button + .el-button) { margin-left: 0; }
.batch-toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-tight); flex-wrap: wrap; }
.request-items { display: grid; gap: var(--bec-space-tight); list-style: none; padding: 0; margin: 0; }
.request-item { display: flex; align-items: center; gap: var(--bec-space-group); min-width: 0; padding: var(--bec-space-tight) var(--bec-space-group); border: var(--bec-border-width) solid var(--bec-color-divider); border-radius: var(--bec-radius-control); }
.request-content { flex: 1; min-width: 0; }
.batch-check { display: flex; align-items: center; }
.batch-check input { width: var(--bec-icon-small); height: var(--bec-icon-small); cursor: pointer; }
.request-item p { margin: var(--bec-space-tight) 0 0; overflow-wrap: anywhere; }
.request-meta { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.request-impact { color: var(--bec-color-text-primary); }
.request-inbox :deep(.el-pagination) { justify-self: end; }
.batch-results, .batch-preview { margin: 0; padding-left: var(--bec-space-group); max-height: calc(var(--bec-ref-space-64) * 3); overflow: auto; }
.batch-results li, .batch-preview li { overflow-wrap: anywhere; }
.review { display: grid; gap: var(--bec-space-group); }
.actions { display: flex; justify-content: end; gap: var(--bec-space-tight); flex-wrap: wrap; }
</style>
