<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElDrawer,
  ElEmpty,
  ElInput,
  ElMessage,
  ElPagination,
  ElPopconfirm,
  ElSkeleton,
  ElTable,
  ElTableColumn,
  ElTree,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  Trash2,
} from '@/shared/ui'
import { formatDateTime, formatNumber } from '@/shared/utils/format'
import { t } from '@/locales'
import BuildingEditorDialog from '../components/BuildingEditorDialog.vue'
import SpaceEditorDialog from '../components/SpaceEditorDialog.vue'
import SystemGroupEditorDialog from '../components/SystemGroupEditorDialog.vue'
import AssetStatusTag from '../components/AssetStatusTag.vue'
import { useAssetManagement } from '../composables/use-asset-management'
import { canRunAssetAction, type AssetBuilding, type AssetSpace, type AssetSystemGroup } from '../models/assets'

const management = useAssetManagement()
const buildingDrawerOpen = ref(false)
const buildingEditorOpen = ref(false)
const spaceEditorOpen = ref(false)
const systemEditorOpen = ref(false)
const editingBuilding = ref<AssetBuilding | null>(null)
const editingSpace = ref<AssetSpace | null>(null)
const editingSystem = ref<AssetSystemGroup | null>(null)

const hasSelectedBuilding = computed(() => management.selectedBuilding.value !== null)
const selectedBuilding = computed(() => management.selectedBuilding.value)
const selectedSpaceSubmitting = computed(() => editingSpace.value ? management.pending.value.has(`space:update:${editingSpace.value.spaceId}`) : management.pending.value.has('space:create'))
const selectedSystemSubmitting = computed(() => editingSystem.value ? management.pending.value.has(`system:update:${editingSystem.value.systemGroupId}`) : management.pending.value.has('system:create'))

async function query() {
  try {
    await management.setBuildingQuery({ keyword: management.buildingQuery.value.keyword })
  } catch {
    // 查询错误已保留在 buildingsError，避免事件处理器产生未处理拒绝。
  }
}

async function resetFilters() {
  management.buildingQuery.value.keyword = ''
  try {
    await management.setBuildingQuery({ keyword: '' })
  } catch {
    // 查询错误已保留在 buildingsError。
  }
}

async function openBuilding(buildingId: string) {
  buildingDrawerOpen.value = true
  try {
    await management.selectBuilding(buildingId)
  } catch {
    buildingDrawerOpen.value = false
  }
}

function openCreateBuilding() {
  editingBuilding.value = null
  buildingEditorOpen.value = true
}

function openEditBuilding(building: AssetBuilding) {
  editingBuilding.value = building
  buildingEditorOpen.value = true
}

async function saveBuilding(value: Parameters<typeof management.saveBuilding>[1]) {
  try {
    await management.saveBuilding(editingBuilding.value?.buildingId ?? null, value)
    buildingEditorOpen.value = false
    ElMessage.success(t('assetManagement.messages.saved'))
  } catch {
    // 请求错误已经转换为页面状态，避免将服务端异常原文展示给管理员。
  }
}

async function deleteBuilding(building: AssetBuilding) {
  try {
    await management.removeBuilding(building.buildingId)
    ElMessage.success(t('assetManagement.messages.deleted'))
  } catch {
    // 删除是否允许由后端引用校验决定。
  }
}

function openCreateSpace() {
  editingSpace.value = null
  spaceEditorOpen.value = true
}

function openEditSpace(space: AssetSpace) {
  editingSpace.value = space
  spaceEditorOpen.value = true
}

async function saveSpace(value: Parameters<typeof management.saveSpace>[1]) {
  try {
    await management.saveSpace(editingSpace.value?.spaceId ?? null, value)
    spaceEditorOpen.value = false
    ElMessage.success(t('assetManagement.messages.saved'))
  } catch {
    // 请求错误已经转换为页面状态，避免将服务端异常原文展示给管理员。
  }
}

async function deleteSpace(space: AssetSpace) {
  const buildingId = selectedBuilding.value?.buildingId
  if (!buildingId) return
  try {
    await management.removeSpace(space.spaceId, buildingId)
    ElMessage.success(t('assetManagement.messages.deleted'))
  } catch {
    // 删除是否允许由后端引用校验决定。
  }
}

function openCreateSystem() {
  editingSystem.value = null
  systemEditorOpen.value = true
}

function openEditSystem(group: AssetSystemGroup) {
  editingSystem.value = group
  systemEditorOpen.value = true
}

async function saveSystem(value: Parameters<typeof management.saveSystemGroup>[1]) {
  try {
    await management.saveSystemGroup(editingSystem.value?.systemGroupId ?? null, value)
    systemEditorOpen.value = false
    ElMessage.success(t('assetManagement.messages.saved'))
  } catch {
    // 请求错误已经转换为页面状态，避免将服务端异常原文展示给管理员。
  }
}

async function deleteSystem(group: AssetSystemGroup) {
  const buildingId = selectedBuilding.value?.buildingId
  if (!buildingId) return
  try {
    await management.removeSystemGroup(group.systemGroupId, buildingId)
    ElMessage.success(t('assetManagement.messages.deleted'))
  } catch {
    // 删除是否允许由后端引用校验决定。
  }
}

function can(value: unknown, action: string) {
  return canRunAssetAction(value as { allowedActions?: string[] }, action)
}

function asBuilding(value: unknown): AssetBuilding { return value as AssetBuilding }
function asSpace(value: unknown): AssetSpace { return value as AssetSpace }
function asSystemGroup(value: unknown): AssetSystemGroup { return value as AssetSystemGroup }

function references(value: AssetBuilding | AssetSpace | AssetSystemGroup) {
  const summary = value.references
  return summary.spaces + summary.systemGroups + summary.equipment + summary.points + summary.authorizations + summary.children + summary.aliases + summary.identities
}

onMounted(() => { void management.loadBuildings().catch(() => undefined) })
</script>

<template>
  <section class="asset-page">
    <header class="page-heading">
      <div><h1>{{ t('assetManagement.archive.title') }}</h1><p>{{ t('assetManagement.archive.description') }}</p></div>
      <ElButton type="primary" :icon="Plus" @click="openCreateBuilding">{{ t('assetManagement.actions.createBuilding') }}</ElButton>
    </header>

    <ElAlert v-if="management.buildingsError.value" :title="management.buildingsError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="management.operationError.value" :title="management.operationError.value.message" type="error" show-icon :closable="false" />
    <ElCard shadow="never">
      <div class="filter-bar">
        <ElInput v-model="management.buildingQuery.value.keyword" :placeholder="t('assetManagement.labels.keyword')" clearable @keyup.enter="query">
          <template #prefix><Search aria-hidden="true" /></template>
        </ElInput>
        <ElButton :icon="Search" @click="query">{{ t('assetManagement.actions.query') }}</ElButton>
        <ElButton :icon="RefreshCw" @click="resetFilters">{{ t('assetManagement.actions.reset') }}</ElButton>
      </div>
      <ElSkeleton v-if="management.buildingsLoading.value && !management.buildings.value.items.length" animated :rows="5" />
      <ElTable v-else v-loading="management.buildingsLoading.value" :data="management.buildings.value.items" row-key="buildingId">
        <ElTableColumn :label="t('assetManagement.labels.buildingName')" min-width="180">
          <template #default="{ row }"><ElButton link @click="openBuilding(row.buildingId)">{{ row.buildingName }}</ElButton></template>
        </ElTableColumn>
        <ElTableColumn :label="t('assetManagement.labels.buildingCode')" prop="buildingCode" min-width="140" />
        <ElTableColumn :label="t('assetManagement.labels.buildingType')" prop="buildingType" min-width="140" />
        <ElTableColumn :label="t('assetManagement.labels.totalGfa')" min-width="140"><template #default="{ row }">{{ formatNumber(row.totalGfa) }}</template></ElTableColumn>
        <ElTableColumn :label="t('assetManagement.labels.status')" min-width="100"><template #default="{ row }"><AssetStatusTag :status="row.status" /></template></ElTableColumn>
        <ElTableColumn :label="t('assetManagement.labels.updateTime')" min-width="180"><template #default="{ row }">{{ formatDateTime(row.updateTime) }}</template></ElTableColumn>
        <ElTableColumn :label="t('assetManagement.actions.viewDetail')" min-width="220" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <ElButton link @click="openBuilding(row.buildingId)">{{ t('assetManagement.actions.viewDetail') }}</ElButton>
              <ElButton v-if="can(row, 'UPDATE')" :icon="Pencil" link @click="openEditBuilding(asBuilding(row))">{{ t('assetManagement.actions.editBuilding') }}</ElButton>
              <ElPopconfirm v-if="can(row, 'DELETE')" :title="t('assetManagement.messages.deleteConfirm')" :confirm-button-text="t('assetManagement.actions.confirmDelete')" :cancel-button-text="t('assetManagement.actions.cancel')" @confirm="deleteBuilding(asBuilding(row))">
                <template #reference><ElButton :icon="Trash2" link type="danger">{{ t('assetManagement.actions.deleteBuilding') }}</ElButton></template>
              </ElPopconfirm>
            </div>
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty :description="t('assetManagement.empty.buildings')" /></template>
      </ElTable>
      <div class="pagination"><ElPagination background layout="total, prev, pager, next" :current-page="management.buildings.value.page" :page-size="management.buildings.value.size" :total="management.buildings.value.total" @current-change="page => management.setBuildingQuery({ page }, false)" /></div>
    </ElCard>

    <ElDrawer :model-value="buildingDrawerOpen" size="50%" :title="t('assetManagement.archive.buildingDetail')" @update:model-value="buildingDrawerOpen = false">
      <ElSkeleton v-if="management.buildingContextLoading.value" animated :rows="8" />
      <ElAlert v-else-if="management.buildingContextError.value" :title="management.buildingContextError.value.message" type="error" show-icon :closable="false" />
      <template v-else-if="hasSelectedBuilding && selectedBuilding">
        <div class="drawer-heading"><div><h2>{{ selectedBuilding.buildingName }}</h2><p>{{ selectedBuilding.buildingCode || t('common.missing') }}</p></div><AssetStatusTag :status="selectedBuilding.status" /></div>
        <ElDescriptions :column="2" border>
          <ElDescriptionsItem :label="t('assetManagement.labels.buildingType')">{{ selectedBuilding.buildingType || t('common.missing') }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('assetManagement.labels.climateZone')">{{ selectedBuilding.climateZone || t('common.missing') }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('assetManagement.labels.constructionYear')">{{ formatNumber(selectedBuilding.constructionYear) }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('assetManagement.labels.totalGfa')">{{ formatNumber(selectedBuilding.totalGfa) }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('assetManagement.labels.references')">{{ formatNumber(references(selectedBuilding)) }}</ElDescriptionsItem>
          <ElDescriptionsItem :label="t('assetManagement.labels.updateTime')">{{ formatDateTime(selectedBuilding.updateTime) }}</ElDescriptionsItem>
        </ElDescriptions>

        <section class="drawer-section">
          <div class="section-heading"><h3>{{ t('assetManagement.archive.spaceTree') }}</h3><ElButton v-if="can(selectedBuilding, 'CREATE')" :icon="Plus" @click="openCreateSpace">{{ t('assetManagement.actions.createSpace') }}</ElButton></div>
          <ElEmpty v-if="!management.spaces.value.length" :description="t('assetManagement.empty.spaces')" />
          <ElTree v-else :data="management.spaces.value" node-key="spaceId" :props="{ label: 'spaceName', children: 'children' }" expand-on-click-node>
            <template #default="{ data }"><div class="tree-node"><span>{{ data.spaceName }}</span><div class="row-actions"><ElButton v-if="can(data, 'UPDATE')" :icon="Pencil" link @click.stop="openEditSpace(asSpace(data))">{{ t('assetManagement.actions.editSpace') }}</ElButton><ElPopconfirm v-if="can(data, 'DELETE')" :title="t('assetManagement.messages.deleteConfirm')" :confirm-button-text="t('assetManagement.actions.confirmDelete')" :cancel-button-text="t('assetManagement.actions.cancel')" @confirm="deleteSpace(asSpace(data))"><template #reference><ElButton :icon="Trash2" link type="danger" @click.stop>{{ t('assetManagement.actions.deleteSpace') }}</ElButton></template></ElPopconfirm></div></div></template>
          </ElTree>
        </section>

        <section class="drawer-section">
          <div class="section-heading"><h3>{{ t('assetManagement.archive.systemGroups') }}</h3><ElButton v-if="can(selectedBuilding, 'CREATE')" :icon="Plus" @click="openCreateSystem">{{ t('assetManagement.actions.createSystem') }}</ElButton></div>
          <ElTable :data="management.systemGroups.value.items" row-key="systemGroupId"><ElTableColumn :label="t('assetManagement.labels.systemName')" prop="systemName" min-width="150" /><ElTableColumn :label="t('assetManagement.labels.systemCode')" prop="systemCode" min-width="120" /><ElTableColumn :label="t('assetManagement.labels.status')" min-width="100"><template #default="{ row }"><AssetStatusTag :status="row.status" /></template></ElTableColumn><ElTableColumn :label="t('assetManagement.actions.viewDetail')" min-width="180"><template #default="{ row }"><div class="row-actions"><ElButton v-if="can(row, 'UPDATE')" :icon="Pencil" link @click="openEditSystem(asSystemGroup(row))">{{ t('assetManagement.actions.editSystem') }}</ElButton><ElPopconfirm v-if="can(row, 'DELETE')" :title="t('assetManagement.messages.deleteConfirm')" :confirm-button-text="t('assetManagement.actions.confirmDelete')" :cancel-button-text="t('assetManagement.actions.cancel')" @confirm="deleteSystem(asSystemGroup(row))"><template #reference><ElButton :icon="Trash2" link type="danger">{{ t('assetManagement.actions.deleteSystem') }}</ElButton></template></ElPopconfirm></div></template></ElTableColumn><template #empty><ElEmpty :description="t('assetManagement.empty.systems')" /></template></ElTable>
        </section>
      </template>
    </ElDrawer>

    <BuildingEditorDialog :open="buildingEditorOpen" :building="editingBuilding" :submitting="editingBuilding ? management.pending.value.has(`building:update:${editingBuilding.buildingId}`) : management.pending.value.has('building:create')" @close="buildingEditorOpen = false" @save="saveBuilding" />
    <SpaceEditorDialog :open="spaceEditorOpen" :building-id="selectedBuilding?.buildingId || ''" :spaces="management.spaces.value" :space="editingSpace" :submitting="selectedSpaceSubmitting" @close="spaceEditorOpen = false" @save="saveSpace" />
    <SystemGroupEditorDialog :open="systemEditorOpen" :building-id="selectedBuilding?.buildingId || ''" :system-group="editingSystem" :submitting="selectedSystemSubmitting" @close="systemEditorOpen = false" @save="saveSystem" />
  </section>
</template>

<style scoped>
.asset-page { display: grid; gap: var(--bec-space-section); min-width: 0; }
.page-heading, .section-heading, .drawer-heading, .filter-bar, .row-actions, .tree-node { display: flex; align-items: center; gap: var(--bec-space-group); }
.page-heading, .section-heading, .drawer-heading { justify-content: space-between; }
.page-heading { align-items: flex-start; }
h1, h2, h3, p { margin: 0; }
h1 { font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); }
h2 { font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
h3 { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
p { color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }
.filter-bar { align-items: stretch; }
.filter-bar :deep(.el-input) { flex: 1; }
.pagination { display: flex; justify-content: flex-end; padding-top: var(--bec-space-group); }
.drawer-section { display: grid; gap: var(--bec-space-group); margin-top: var(--bec-space-section); }
.drawer-heading { align-items: flex-start; margin-bottom: var(--bec-space-section); }
.row-actions { gap: var(--bec-space-tight); flex-wrap: wrap; }
.tree-node { justify-content: space-between; min-width: 0; width: 100%; }
</style>
