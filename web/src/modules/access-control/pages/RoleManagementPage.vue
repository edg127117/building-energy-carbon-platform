<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { ElAlert, ElButton, ElCard, ElEmpty, ElMessage, ElTable, ElTableColumn, ElTag } from '@/shared/ui'
import { t } from '@/locales'
import ChangeRequestControl from '../components/ChangeRequestControl.vue'
import RoleMenuTree from '../components/RoleMenuTree.vue'
import { useRoleManagement } from '../composables/use-role-management'

const management = useRoleManagement()
const roles = management.roles
const tree = management.tree
const selectedRole = management.selectedRole
const checkedIds = management.checkedIds
const loading = management.loading
const error = management.error
const changes = management.changes
const currentChange = changes.current
const changeBusy = computed(() => changes.pending.value.size > 0)
const isThirdParty = computed(() => selectedRole.value?.roleKey === 'THIRD_PARTY')

function scopeLabel(scope: string, roleKey: string) {
  if (roleKey === 'THIRD_PARTY' || scope === 'BUILDING') return t('accessControl.role.scopeBuilding')
  if (scope === 'ALL') return t('accessControl.role.scopeAll')
  return t('accessControl.role.scopeSelf')
}

async function requestAssignment() {
  try {
    await management.requestMenuAssignment()
    ElMessage.success(t('accessControl.change.submitted'))
  } catch {
    // 失败状态由页面保留。
  }
}

function updateCheckedIds(value: number[]) {
  checkedIds.value = value
}

async function afterExecute(requestId: string) {
  try {
    const result = await changes.execute(requestId)
    if (result?.status === 'EXECUTED') await management.load()
  } catch {
    // 失败状态由申请控件与页面错误提示保留。
  }
}

onMounted(() => { void management.load().catch(() => undefined) })
</script>

<template>
  <section class="role-page" :aria-label="t('accessControl.role.title')">
    <header class="page-heading"><div><h1>{{ t('accessControl.role.title') }}</h1><p>{{ t('accessControl.role.description') }}</p></div><ElButton :loading="loading" @click="management.load">{{ t('accessControl.action.refresh') }}</ElButton></header>
    <ElAlert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <ElTable v-if="roles.length || loading" v-loading="loading" :data="roles" highlight-current-row @row-click="management.select">
      <ElTableColumn :label="t('accessControl.role.title')"><template #default="{ row }">{{ t(`accessControl.role.${row.roleKey}`) }}</template></ElTableColumn>
      <ElTableColumn :label="t('accessControl.user.statusColumn')"><template #default="{ row }"><ElTag :type="row.status ? 'success' : 'warning'">{{ row.status ? t('accessControl.role.statusActive') : t('accessControl.role.statusInactive') }}</ElTag></template></ElTableColumn>
      <ElTableColumn :label="t('accessControl.buildingAccess.statusLabel')"><template #default="{ row }">{{ scopeLabel(row.dataScope, row.roleKey) }}</template></ElTableColumn>
    </ElTable>
    <ElEmpty v-else :description="t('accessControl.role.empty')" />

    <ElCard v-if="selectedRole" shadow="never" class="menu-assignment">
      <template v-if="isThirdParty"><ElAlert :title="t('accessControl.role.thirdParty')" type="info" show-icon :closable="false" /></template>
      <template v-else>
        <div class="assignment-heading"><div><h2>{{ t('accessControl.role.menuTitle') }}</h2><p>{{ t('accessControl.role.menuDescription') }}</p></div><ElButton type="primary" :loading="changeBusy" @click="requestAssignment">{{ t('accessControl.role.requestAssignment') }}</ElButton></div>
        <RoleMenuTree :key="selectedRole.id" :tree="tree" :checked-ids="checkedIds" @update:checked-ids="updateCheckedIds" />
      </template>
    </ElCard>
    <ElAlert v-else :title="t('accessControl.role.selectPrompt')" type="info" show-icon :closable="false" />

    <ChangeRequestControl :change="currentChange" :busy="changeBusy" @lookup="changes.load" @submit="changes.submit" @withdraw="changes.withdraw" @approve="changes.approve" @reject="changes.reject" @execute="afterExecute" />
  </section>
</template>

<style scoped>
.role-page { display: grid; align-content: start; gap: var(--bec-space-section); min-width: 0; }
.page-heading, .assignment-heading { display: flex; align-items: start; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
h1 { margin: 0; font-size: var(--bec-font-size-system); }
h2 { margin: 0; font-size: var(--bec-font-size-title); }
p { margin: var(--bec-space-tight) 0 0; color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }
.menu-assignment { display: grid; gap: var(--bec-space-group); }
</style>
