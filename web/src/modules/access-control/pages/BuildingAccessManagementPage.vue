<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ElAlert,
  ElButton,
  ElEmpty,
  ElMessage,
  ElOption,
  ElSelect,
  ElSpace,
  ElTable,
  ElTableColumn,
  ElTag,
} from '@/shared/ui'
import { t } from '@/locales'
import BuildingAccessReviewDialog from '../components/BuildingAccessReviewDialog.vue'
import BuildingScopeDialog from '../components/BuildingScopeDialog.vue'
import ChangeRequestControl from '../components/ChangeRequestControl.vue'
import { useBuildingAccessManagement } from '../composables/use-building-access-management'
import type { BuildingAccessRequest, BuildingAccessStatus, UserView } from '../models/access-control'

const management = useBuildingAccessManagement()
const requests = management.requests
const users = management.users
const buildings = management.buildings
const status = management.status
const loading = management.loading
const error = management.error
const changes = management.changes
const currentChange = changes.current
const changeBusy = computed(() => changes.pending.value.size > 0)
const selectedUserId = ref<number | null>(null)
const scopeDialog = ref(false)
const reviewDialog = ref(false)
const selectedRequest = ref<BuildingAccessRequest | null>(null)
const selectedUser = computed<UserView | null>(() => users.value.find(item => item.id === selectedUserId.value) ?? null)
const reviewBusy = computed(() => selectedRequest.value
  ? management.reviewPending.value.has(`review:${selectedRequest.value.id}`)
  : false)
const statusOptions = computed(() => [
  { label: t('accessControl.buildingAccess.statusPlaceholder'), value: 'ALL' },
  { label: t('accessControl.buildingAccess.status.PENDING'), value: 'PENDING' },
  { label: t('accessControl.buildingAccess.status.APPROVED'), value: 'APPROVED' },
  { label: t('accessControl.buildingAccess.status.REJECTED'), value: 'REJECTED' },
  { label: t('accessControl.buildingAccess.status.CANCELLED'), value: 'CANCELLED' },
])
const userOptions = computed(() => users.value.map(item => ({
  label: item.nickname ? `${item.username} · ${item.nickname}` : item.username,
  value: item.id,
})))

function statusLabel(value: BuildingAccessStatus) {
  return t(`accessControl.buildingAccess.status.${value}`)
}

function openScope() {
  if (selectedUser.value) scopeDialog.value = true
}

function openReview(request: BuildingAccessRequest) {
  selectedRequest.value = request
  reviewDialog.value = true
}

async function submitScope(buildingIds: string[]) {
  if (!selectedUser.value) return
  try {
    await management.requestBuildingScope(selectedUser.value.id, buildingIds)
    scopeDialog.value = false
    ElMessage.success(t('accessControl.change.submitted'))
  } catch {
    // 失败状态由页面保留，表单不关闭。
  }
}

async function review(action: 'approve' | 'reject', comment?: string) {
  const request = selectedRequest.value
  if (!request) return
  try {
    await management.review(request.id, action, comment)
    reviewDialog.value = false
    ElMessage.success(t(action === 'approve'
      ? 'accessControl.buildingAccess.approveSuccess'
      : 'accessControl.buildingAccess.rejectSuccess'))
  } catch {
    // 失败状态由页面保留，审核窗口不关闭。
  }
}

async function afterExecute(requestId: string) {
  try {
    const result = await changes.execute(requestId)
    if (result?.status === 'EXECUTED') await Promise.all([management.load(), management.loadOptions()])
  } catch {
    // 失败状态由申请控件与页面错误提示保留。
  }
}

function reload() {
  void Promise.all([management.load(), management.loadOptions()]).catch(() => undefined)
}

onMounted(reload)
</script>

<template>
  <section class="building-access-page" :aria-label="t('accessControl.buildingAccess.title')">
    <header class="page-heading">
      <div><h1>{{ t('accessControl.buildingAccess.title') }}</h1><p>{{ t('accessControl.buildingAccess.description') }}</p></div>
      <ElButton :loading="loading" @click="reload">{{ t('accessControl.action.refresh') }}</ElButton>
    </header>

    <ElAlert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <section class="scope-panel" :aria-label="t('accessControl.buildingAccess.scopeTitle')">
      <div><h2>{{ t('accessControl.buildingAccess.scopeTitle') }}</h2><p>{{ t('accessControl.buildingAccess.scopeDescription') }}</p></div>
      <ElSpace wrap>
        <ElSelect v-model="selectedUserId" filterable clearable :placeholder="t('accessControl.buildingAccess.userPlaceholder')">
          <ElOption v-for="item in userOptions" :key="item.value" :label="item.label" :value="item.value" />
        </ElSelect>
        <ElButton type="primary" :disabled="!selectedUser" @click="openScope">{{ t('accessControl.action.assignBuildings') }}</ElButton>
      </ElSpace>
    </section>

    <ElSelect v-model="status" :aria-label="t('accessControl.buildingAccess.statusLabel')" @change="management.load">
      <ElOption v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
    </ElSelect>
    <ElTable v-if="requests.length || loading" v-loading="loading" :data="requests" row-key="id">
      <ElTableColumn prop="username" :label="t('accessControl.buildingAccess.applicant')" />
      <ElTableColumn :label="t('accessControl.buildingAccess.building')"><template #default="{ row }">{{ row.buildingName ?? row.buildingId }}</template></ElTableColumn>
      <ElTableColumn prop="reason" :label="t('accessControl.buildingAccess.reason')" min-width="var(--bec-navigation-width)" />
      <ElTableColumn :label="t('accessControl.buildingAccess.statusLabel')"><template #default="{ row }"><ElTag>{{ statusLabel(row.status) }}</ElTag></template></ElTableColumn>
      <ElTableColumn :label="t('accessControl.buildingAccess.actionColumn')"><template #default="{ row }"><ElButton text :disabled="row.status !== 'PENDING'" @click="openReview(row as BuildingAccessRequest)">{{ t('accessControl.action.review') }}</ElButton></template></ElTableColumn>
    </ElTable>
    <ElEmpty v-else :description="t('accessControl.buildingAccess.empty')" />

    <ChangeRequestControl
      :change="currentChange"
      :busy="changeBusy"
      @lookup="changes.load"
      @submit="changes.submit"
      @withdraw="changes.withdraw"
      @approve="changes.approve"
      @reject="changes.reject"
      @execute="afterExecute"
    />
    <BuildingScopeDialog v-model="scopeDialog" :user="selectedUser" :buildings="buildings" :submitting="changeBusy" @submit="submitScope" />
    <BuildingAccessReviewDialog
      v-model="reviewDialog"
      :request="selectedRequest"
      :submitting="reviewBusy"
      @approve="review('approve', $event)"
      @reject="review('reject', $event)"
    />
  </section>
</template>

<style scoped>
.building-access-page { display: grid; align-content: start; gap: var(--bec-space-section); min-width: 0; }
.page-heading, .scope-panel { display: flex; align-items: start; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
.scope-panel { padding: var(--bec-space-card); border: var(--bec-border-width) solid var(--bec-color-divider); border-radius: var(--bec-radius-card); background: var(--bec-color-surface); }
h1 { margin: 0; font-size: var(--bec-font-size-system); }
h2 { margin: 0; font-size: var(--bec-font-size-title); }
p { margin: var(--bec-space-tight) 0 0; color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }
</style>
