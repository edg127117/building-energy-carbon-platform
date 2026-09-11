<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ElAlert,
  ElButton,
  ElCheckbox,
  ElCheckboxGroup,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElOption,
  ElPagination,
  ElPopconfirm,
  ElSelect,
  ElSpace,
  ElTable,
  ElTableColumn,
  ElTag,
  ElDialog,
} from '@/shared/ui'
import { t } from '@/locales'
import type { FormalRoleKey, UserView } from '../models/access-control'
import BuildingScopeDialog from '../components/BuildingScopeDialog.vue'
import ChangeRequestControl from '../components/ChangeRequestControl.vue'
import UserAccountDialog from '../components/UserAccountDialog.vue'
import { useUserManagement } from '../composables/use-user-management'

const management = useUserManagement()
const query = management.query
const page = management.page
const buildings = management.buildings
const loading = management.loading
const error = management.error
const changes = management.changes
const currentChange = changes.current
const changeBusy = computed(() => changes.pending.value.size > 0)
const keyword = ref('')
const selectedStatus = ref<0 | 1 | undefined>()
const includeDeleted = ref(false)
const selectedUser = ref<UserView | null>(null)
const accountDialog = ref(false)
const accountMode = ref<'open' | 'edit'>('open')
const roleDialog = ref(false)
const scopeDialog = ref(false)
const selectedRoles = ref<FormalRoleKey[]>([])
const roleOptions = computed(() => [
  { label: t('accessControl.role.BUILDING_OWNER'), value: 'BUILDING_OWNER' },
  { label: t('accessControl.role.ENERGY_MANAGER'), value: 'ENERGY_MANAGER' },
  { label: t('accessControl.role.THIRD_PARTY'), value: 'THIRD_PARTY' },
  { label: t('accessControl.role.PLATFORM_ADMIN'), value: 'PLATFORM_ADMIN' },
])
const statusOptions = computed(() => [
  { label: t('accessControl.user.active'), value: 1 },
  { label: t('accessControl.user.inactive'), value: 0 },
])

function applyFilters() {
  void management.setQuery({
    keyword: keyword.value.trim() || undefined,
    status: selectedStatus.value,
    includeDeleted: includeDeleted.value,
  }).catch(() => undefined)
}

function changePage(value: number) {
  void management.setQuery({ page: value }, false).catch(() => undefined)
}

function openAccount() {
  selectedUser.value = null
  accountMode.value = 'open'
  accountDialog.value = true
}

function openProfile(user: UserView) {
  selectedUser.value = user
  accountMode.value = 'edit'
  accountDialog.value = true
}

function openRoles(user: UserView) {
  selectedUser.value = user
  selectedRoles.value = [...user.roles]
  roleDialog.value = true
}

function openScope(user: UserView) {
  selectedUser.value = user
  scopeDialog.value = true
}

async function submitAccount(command: Parameters<typeof management.openAccount>[0]) {
  try {
    await management.openAccount(command)
    accountDialog.value = false
    ElMessage.success(t('accessControl.user.requestSubmitted'))
  } catch {
    // 失败状态由页面保留，表单不关闭。
  }
}

async function saveProfile(payload: { nickname?: string | null; phone?: string | null }) {
  if (!selectedUser.value) return
  try {
    await management.updateProfile(selectedUser.value.id, payload)
    accountDialog.value = false
    ElMessage.success(t('accessControl.user.profileSaved'))
  } catch {
    // 失败状态由页面保留，表单不关闭。
  }
}

async function submitRoles() {
  if (!selectedUser.value || selectedRoles.value.length === 0) return
  try {
    await management.replaceRoles(selectedUser.value.id, selectedRoles.value)
    roleDialog.value = false
    ElMessage.success(t('accessControl.user.requestSubmitted'))
  } catch {
    // 失败状态由页面保留，表单不关闭。
  }
}

async function submitScope(buildingIds: string[]) {
  if (!selectedUser.value) return
  try {
    await management.replaceBuildings(selectedUser.value.id, buildingIds)
    scopeDialog.value = false
    ElMessage.success(t('accessControl.user.requestSubmitted'))
  } catch {
    // 失败状态由页面保留，表单不关闭。
  }
}

async function requestPasswordReset(user: UserView) {
  try {
    await management.requestPasswordReset(user.id)
    ElMessage.success(t('accessControl.user.requestSubmitted'))
  } catch {
    // 失败状态由页面保留。
  }
}

async function requestStatus(user: UserView) {
  try {
    await management.updateStatus(user.id, user.status === 1 ? 0 : 1)
    ElMessage.success(t('accessControl.user.requestSubmitted'))
  } catch {
    // 失败状态由页面保留。
  }
}

async function requestDelete(user: UserView) {
  try {
    await management.remove(user.id)
    ElMessage.success(t('accessControl.user.requestSubmitted'))
  } catch {
    // 失败状态由页面保留。
  }
}

async function requestRestore(user: UserView) {
  try {
    await management.restore(user.id)
    ElMessage.success(t('accessControl.user.requestSubmitted'))
  } catch {
    // 失败状态由页面保留。
  }
}

async function afterExecute(requestId: string) {
  try {
    const result = await changes.execute(requestId)
    if (result?.status === 'EXECUTED') await management.load()
  } catch {
    // 失败状态由申请控件与页面错误提示保留。
  }
}

onMounted(() => {
  void management.load().catch(() => undefined)
  void management.loadBuildings().catch(() => undefined)
})
</script>

<template>
  <section class="user-page" :aria-label="t('accessControl.user.title')">
    <header class="page-heading">
      <div><h1>{{ t('accessControl.user.title') }}</h1><p>{{ t('accessControl.user.description') }}</p></div>
      <ElButton type="primary" @click="openAccount">{{ t('accessControl.user.openTitle') }}</ElButton>
    </header>

    <ElAlert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <ElForm class="filters" inline @submit.prevent="applyFilters">
      <ElFormItem><ElInput v-model="keyword" clearable :placeholder="t('accessControl.user.keywordPlaceholder')" @keyup.enter="applyFilters" /></ElFormItem>
      <ElFormItem><ElSelect v-model="selectedStatus" clearable :placeholder="t('accessControl.user.statusPlaceholder')"><ElOption v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" /></ElSelect></ElFormItem>
      <ElFormItem><ElCheckbox v-model="includeDeleted">{{ t('accessControl.user.includeDeleted') }}</ElCheckbox></ElFormItem>
      <ElFormItem><ElButton native-type="submit">{{ t('accessControl.action.query') }}</ElButton></ElFormItem>
    </ElForm>

    <ElTable v-if="page.records.length || loading" v-loading="loading" :data="page.records" row-key="id">
      <ElTableColumn prop="username" :label="t('accessControl.user.usernameColumn')" />
      <ElTableColumn prop="nickname" :label="t('accessControl.user.nicknameColumn')" />
      <ElTableColumn :label="t('accessControl.user.statusColumn')">
        <template #default="{ row }"><ElTag :type="row.delFlag ? 'info' : row.status ? 'success' : 'warning'">{{ row.delFlag ? t('accessControl.user.deleted') : row.status ? t('accessControl.user.active') : t('accessControl.user.inactive') }}</ElTag></template>
      </ElTableColumn>
      <ElTableColumn :label="t('accessControl.user.rolesColumn')">
        <template #default="{ row }"><ElSpace wrap><ElTag v-for="role in row.roles" :key="role">{{ t(`accessControl.role.${role}`) }}</ElTag></ElSpace></template>
      </ElTableColumn>
      <ElTableColumn :label="t('accessControl.user.buildingCountColumn')"><template #default="{ row }">{{ row.buildingIds.length }}</template></ElTableColumn>
      <ElTableColumn :label="t('accessControl.user.actionColumn')" min-width="var(--bec-navigation-width)">
        <template #default="{ row }">
          <ElSpace v-if="row.delFlag" wrap><ElPopconfirm :title="t('accessControl.action.restore')" @confirm="requestRestore(row as UserView)"><template #reference><ElButton text>{{ t('accessControl.action.restore') }}</ElButton></template></ElPopconfirm></ElSpace>
          <ElSpace v-else wrap>
            <ElButton text @click="openProfile(row as UserView)">{{ t('accessControl.action.edit') }}</ElButton>
            <ElButton text @click="openRoles(row as UserView)">{{ t('accessControl.action.assignRoles') }}</ElButton>
            <ElButton text @click="openScope(row as UserView)">{{ t('accessControl.action.assignBuildings') }}</ElButton>
            <ElButton text @click="requestPasswordReset(row as UserView)">{{ t('accessControl.action.resetPassword') }}</ElButton>
            <ElButton text @click="requestStatus(row as UserView)">{{ row.status ? t('accessControl.action.disable') : t('accessControl.action.enable') }}</ElButton>
            <ElPopconfirm :title="t('accessControl.action.delete')" @confirm="requestDelete(row as UserView)"><template #reference><ElButton text type="danger">{{ t('accessControl.action.delete') }}</ElButton></template></ElPopconfirm>
          </ElSpace>
        </template>
      </ElTableColumn>
    </ElTable>
    <ElEmpty v-else :description="t('accessControl.user.empty')" />
    <ElPagination v-if="page.total > page.size" :current-page="query.page" :page-size="query.size" :total="page.total" layout="prev, pager, next" @current-change="changePage" />

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

    <UserAccountDialog v-model="accountDialog" :mode="accountMode" :user="selectedUser" :buildings="buildings" :submitting="changeBusy" @open-account="submitAccount" @update-profile="saveProfile" />
    <BuildingScopeDialog v-model="scopeDialog" :user="selectedUser" :buildings="buildings" :submitting="changeBusy" @submit="submitScope" />
    <ElDialog v-model="roleDialog" :title="t('accessControl.action.assignRoles')" :width="'var(--bec-dialog-width)'">
      <ElCheckboxGroup v-model="selectedRoles"><ElCheckbox v-for="item in roleOptions" :key="item.value" :value="item.value">{{ item.label }}</ElCheckbox></ElCheckboxGroup>
      <template #footer><ElButton @click="roleDialog = false">{{ t('accessControl.action.cancel') }}</ElButton><ElButton type="primary" :loading="changeBusy" @click="submitRoles">{{ t('accessControl.action.submitRequest') }}</ElButton></template>
    </ElDialog>
  </section>
</template>

<style scoped>
.user-page { display: grid; align-content: start; gap: var(--bec-space-section); min-width: 0; }
.page-heading { display: flex; align-items: start; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
h1 { margin: 0; font-size: var(--bec-font-size-system); }
p { margin: var(--bec-space-tight) 0 0; color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }
.filters { display: flex; align-items: center; gap: var(--bec-space-tight); flex-wrap: wrap; }
</style>
