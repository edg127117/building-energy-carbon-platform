<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElDrawer,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElOption,
  ElPagination,
  ElPopconfirm,
  ElSelect,
  ElSkeleton,
  ElTable,
  ElTableColumn,
  ElTag,
  Cpu,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  Trash2,
} from '@/shared/ui'
import { formatNumber } from '@/shared/utils/format'
import { t } from '@/locales'
import EquipmentEditorDialog from '../components/EquipmentEditorDialog.vue'
import PointEditorDialog from '../components/PointEditorDialog.vue'
import AssetStatusTag from '../components/AssetStatusTag.vue'
import { useAssetManagement } from '../composables/use-asset-management'
import { canRunAssetAction, flattenSpaces, type AssetEquipmentDetail, type AssetEquipmentQuery, type AssetPoint } from '../models/assets'

const management = useAssetManagement()
// 筛选范围与编辑弹窗独立，编辑其他建筑的设备不能替换筛选选项。
const filterScope = useAssetManagement()
const filters = reactive<Partial<AssetEquipmentQuery>>({})
const filterSpaces = computed(() => flattenSpaces(filterScope.scopeSpaces.value))
const pointsSection = ref<HTMLElement | null>(null)
const equipmentDrawerOpen = ref(false)
const equipmentEditorOpen = ref(false)
const pointEditorOpen = ref(false)
const editingEquipment = ref<AssetEquipmentDetail | null>(null)
const editingPoint = ref<AssetPoint | null>(null)
const selectedEquipment = computed(() => management.selectedEquipment.value)
const equipmentSubmitting = computed(() => editingEquipment.value ? management.pending.value.has(`equipment:update:${editingEquipment.value.equipmentId}`) : management.pending.value.has('equipment:create'))
const pointSubmitting = computed(() => editingPoint.value ? management.pending.value.has(`point:update:${editingPoint.value.pointId}`) : false)

async function query() {
  try {
    await management.setEquipmentQuery({
      buildingId: filters.buildingId || undefined,
      spaceId: filters.spaceId || undefined,
      systemGroupId: filters.systemGroupId || undefined,
      typeCode: filters.typeCode?.trim() || undefined,
      keyword: filters.keyword,
    })
  } catch {
    // 查询错误已保留在 equipmentError，避免事件处理器产生未处理拒绝。
  }
}

async function buildingFilterChanged(buildingId: string | undefined) {
  filters.spaceId = undefined
  filters.systemGroupId = undefined
  try {
    await filterScope.loadScope(buildingId)
  } catch {
    // 依赖范围与列表分别保留受控错误状态。
  }
}

async function resetFilters() {
  Object.assign(filters, { buildingId: undefined, spaceId: undefined, systemGroupId: undefined, typeCode: undefined, keyword: undefined })
  await filterScope.loadScope(undefined)
  await query()
}

async function changePage(page: number) {
  try {
    await management.setEquipmentQuery({ page }, false)
  } catch {
    // 查询错误已保留在 equipmentError。
  }
}

async function openEquipment(equipmentId: string, showPoints = false) {
  equipmentDrawerOpen.value = true
  try {
    await management.selectEquipment(equipmentId)
    await nextTick()
    if (showPoints) pointsSection.value?.scrollIntoView({ block: 'start' })
  } catch {
    equipmentDrawerOpen.value = false
  }
}

async function openCreateEquipment() {
  editingEquipment.value = null
  try {
    await management.ensureBuildingOptions()
    equipmentEditorOpen.value = true
  } catch {
    // 建筑选项错误已保留在 buildingsError。
  }
}

async function openEditEquipment(equipmentId: string) {
  try {
    await management.selectEquipment(equipmentId)
    editingEquipment.value = management.selectedEquipment.value
    await management.loadScope(editingEquipment.value?.buildingId)
    equipmentEditorOpen.value = true
  } catch {
    // 页面已保留受控错误状态。
  }
}

async function saveEquipment(value: Parameters<typeof management.saveEquipment>[1]) {
  try {
    await management.saveEquipment(editingEquipment.value?.equipmentId ?? null, value)
    equipmentEditorOpen.value = false
    ElMessage.success(t('assetManagement.messages.saved'))
  } catch {
    // 请求错误已经转换为页面状态，避免将服务端异常原文展示给管理员。
  }
}

async function deleteEquipment(equipmentId: string) {
  try {
    await management.removeEquipment(equipmentId)
    equipmentDrawerOpen.value = false
    ElMessage.success(t('assetManagement.messages.deleted'))
  } catch {
    // 删除是否允许由后端引用校验决定。
  }
}

function openEditPoint(point: AssetPoint) {
  editingPoint.value = point
  pointEditorOpen.value = true
}

async function savePoint(value: Parameters<typeof management.savePoint>[2]) {
  const equipmentId = selectedEquipment.value?.equipmentId
  const pointId = editingPoint.value?.pointId
  if (!equipmentId || !pointId) return
  try {
    await management.savePoint(equipmentId, pointId, value)
    pointEditorOpen.value = false
    ElMessage.success(t('assetManagement.messages.saved'))
  } catch {
    // 请求错误已经转换为页面状态，避免将服务端异常原文展示给管理员。
  }
}

async function deletePoint(point: AssetPoint) {
  const equipmentId = selectedEquipment.value?.equipmentId
  if (!equipmentId) return
  try {
    await management.removePoint(equipmentId, point.pointId)
    ElMessage.success(t('assetManagement.messages.deleted'))
  } catch {
    // 删除是否允许由后端引用校验决定。
  }
}

function can(value: unknown, action: string) {
  return canRunAssetAction(value as { allowedActions?: string[] }, action)
}

function asPoint(value: unknown): AssetPoint { return value as AssetPoint }

function summary(value: { total: number; required: number; configuredRequired: number }) {
  return t('assetManagement.equipment.pointConfiguration', value)
}

onMounted(() => {
  void Promise.all([management.loadEquipment(), management.ensureBuildingOptions()]).catch(() => undefined)
})
</script>

<template>
  <section class="equipment-page">
    <header class="page-heading"><div><h1>{{ t('assetManagement.equipment.title') }}</h1><p>{{ t('assetManagement.equipment.description') }}</p></div><ElButton type="primary" :icon="Plus" @click="openCreateEquipment">{{ t('assetManagement.actions.createEquipment') }}</ElButton></header>
    <ElAlert v-if="management.equipmentError.value" :title="management.equipmentError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="management.buildingsError.value" :title="management.buildingsError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="filterScope.scopeError.value" :title="filterScope.scopeError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="management.scopeError.value" :title="management.scopeError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="management.operationError.value" :title="management.operationError.value.message" type="error" show-icon :closable="false" />
    <ElCard shadow="never" class="filter-panel">
      <ElForm label-position="top" :aria-label="t('assetManagement.equipment.filters')" @submit.prevent="query">
        <div class="filter-grid">
          <ElFormItem :label="t('assetManagement.labels.building')">
            <ElSelect v-model="filters.buildingId" clearable filterable :placeholder="t('assetManagement.equipment.allBuildings')" @change="buildingFilterChanged">
              <ElOption v-for="item in management.buildingOptions.value" :key="item.value" :label="item.label" :value="item.value" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem :label="t('assetManagement.labels.space')">
            <ElSelect v-model="filters.spaceId" clearable filterable :placeholder="t(filters.buildingId ? 'assetManagement.equipment.allSpaces' : 'assetManagement.equipment.chooseBuilding')" :disabled="!filters.buildingId || filterScope.scopeLoading.value" :loading="filterScope.scopeLoading.value">
              <ElOption v-for="item in filterSpaces" :key="item.spaceId" :label="item.spaceName" :value="item.spaceId" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem :label="t('assetManagement.equipment.system')">
            <ElSelect v-model="filters.systemGroupId" clearable filterable :placeholder="t(filters.buildingId ? 'assetManagement.equipment.allSystems' : 'assetManagement.equipment.chooseBuilding')" :disabled="!filters.buildingId || filterScope.scopeLoading.value" :loading="filterScope.scopeLoading.value">
              <ElOption v-for="item in filterScope.scopeSystemGroups.value" :key="item.systemGroupId" :label="item.systemName" :value="item.systemGroupId" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem :label="t('assetManagement.labels.equipmentType')"><ElInput v-model="filters.typeCode" clearable :placeholder="t('assetManagement.equipment.typePlaceholder')" /></ElFormItem>
          <ElFormItem :label="t('assetManagement.equipment.keyword')"><ElInput v-model="filters.keyword" clearable :placeholder="t('assetManagement.equipment.keywordPlaceholder')" /></ElFormItem>
        </div>
        <div class="filter-actions">
          <ElButton :icon="RefreshCw" @click="resetFilters">{{ t('assetManagement.equipment.reset') }}</ElButton>
          <ElButton type="primary" native-type="submit" :icon="Search" :loading="management.equipmentLoading.value">{{ t('assetManagement.equipment.query') }}</ElButton>
        </div>
      </ElForm>
    </ElCard>
    <ElCard shadow="never" class="list-panel">
      <template #header><div class="list-heading"><h2>{{ t('assetManagement.equipment.list') }}</h2><span class="list-count">{{ t('assetManagement.equipment.count', { total: management.equipment.value.total }) }}</span></div></template>
      <ElSkeleton v-if="management.equipmentLoading.value && !management.equipment.value.items.length" animated :rows="5" />
      <ElTable v-else v-loading="management.equipmentLoading.value" :data="management.equipment.value.items" row-key="equipmentId" class="equipment-table">
        <ElTableColumn type="index" :label="t('assetManagement.equipment.index')" width="60" :index="index => (management.equipment.value.page - 1) * management.equipment.value.size + index + 1" />
        <ElTableColumn :label="t('assetManagement.labels.equipmentName')" min-width="190"><template #default="{ row }"><div class="equipment-name"><span class="equipment-icon"><Cpu aria-hidden="true" /></span><ElButton link class="name-link" @click="openEquipment(row.equipmentId)">{{ row.equipmentName }}</ElButton></div></template></ElTableColumn>
        <ElTableColumn :label="t('assetManagement.labels.equipmentCode')" prop="equipmentCode" min-width="130" show-overflow-tooltip />
        <ElTableColumn :label="t('assetManagement.equipment.location')" min-width="160"><template #default="{ row }"><div class="location-cell"><span>{{ row.spaceName || t('common.missing') }}</span><span class="secondary">{{ row.buildingName || t('common.missing') }}</span></div></template></ElTableColumn>
        <ElTableColumn :label="t('assetManagement.equipment.system')" prop="systemGroupName" min-width="120" show-overflow-tooltip />
        <ElTableColumn :label="t('assetManagement.labels.equipmentType')" prop="typeCode" min-width="110" show-overflow-tooltip />
        <ElTableColumn :label="t('assetManagement.equipment.archiveStatus')" min-width="95"><template #default="{ row }"><AssetStatusTag :status="row.status" /></template></ElTableColumn>
        <ElTableColumn :label="t('assetManagement.equipment.pointSummary')" min-width="150"><template #default="{ row }"><div class="point-summary"><span>{{ t('assetManagement.equipment.pointCount', { total: row.pointSummary.total }) }}</span><span class="secondary">{{ summary(row.pointSummary) }}</span></div></template></ElTableColumn>
        <ElTableColumn :label="t('assetManagement.equipment.actions')" min-width="190" fixed="right"><template #default="{ row }"><div class="row-actions"><ElButton link type="primary" @click="openEquipment(row.equipmentId)">{{ t('assetManagement.equipment.viewArchive') }}</ElButton><ElButton link type="primary" @click="openEquipment(row.equipmentId, true)">{{ t('assetManagement.equipment.viewPoints') }}</ElButton></div></template></ElTableColumn>
        <template #empty><ElEmpty :description="t('assetManagement.empty.equipment')" /></template>
      </ElTable>
      <div class="pagination"><ElPagination background layout="total, prev, pager, next" :current-page="management.equipment.value.page" :page-size="management.equipment.value.size" :total="management.equipment.value.total" @current-change="changePage" /></div>
    </ElCard>

    <ElDrawer :model-value="equipmentDrawerOpen" size="55%" :title="t('assetManagement.equipment.detail')" @update:model-value="equipmentDrawerOpen = false">
      <ElSkeleton v-if="management.equipmentContextLoading.value" animated :rows="8" />
      <ElAlert v-else-if="management.equipmentContextError.value" :title="management.equipmentContextError.value.message" type="error" show-icon :closable="false" />
      <template v-else-if="selectedEquipment">
        <div class="drawer-heading"><div><h2>{{ selectedEquipment.equipmentName }}</h2><p>{{ selectedEquipment.equipmentCode || t('common.missing') }}</p></div><AssetStatusTag :status="selectedEquipment.status" /></div>
        <div class="drawer-actions">
          <ElButton v-if="can(selectedEquipment, 'UPDATE')" :icon="Pencil" @click="openEditEquipment(selectedEquipment.equipmentId)">{{ t('assetManagement.actions.editEquipment') }}</ElButton>
          <ElPopconfirm v-if="can(selectedEquipment, 'DELETE')" :title="t('assetManagement.messages.deleteConfirm')" :confirm-button-text="t('assetManagement.actions.confirmDelete')" :cancel-button-text="t('assetManagement.actions.cancel')" @confirm="deleteEquipment(selectedEquipment.equipmentId)"><template #reference><ElButton :icon="Trash2" type="danger" plain>{{ t('assetManagement.actions.deleteEquipment') }}</ElButton></template></ElPopconfirm>
        </div>
        <ElDescriptions :column="2" border><ElDescriptionsItem :label="t('assetManagement.labels.building')">{{ selectedEquipment.buildingName || t('common.missing') }}</ElDescriptionsItem><ElDescriptionsItem :label="t('assetManagement.labels.space')">{{ selectedEquipment.spaceName || t('common.missing') }}</ElDescriptionsItem><ElDescriptionsItem :label="t('assetManagement.labels.system')">{{ selectedEquipment.systemGroupName || t('common.missing') }}</ElDescriptionsItem><ElDescriptionsItem :label="t('assetManagement.labels.productName')">{{ selectedEquipment.productName || t('common.missing') }}</ElDescriptionsItem><ElDescriptionsItem :label="t('assetManagement.labels.manufacturer')">{{ selectedEquipment.manufacturer || t('common.missing') }}</ElDescriptionsItem><ElDescriptionsItem :label="t('assetManagement.labels.expectedProfile')">{{ selectedEquipment.expectedProfileCode || t('common.missing') }}</ElDescriptionsItem><ElDescriptionsItem :label="t('assetManagement.labels.ratedCapacity')">{{ formatNumber(selectedEquipment.ratedCapacity) }}</ElDescriptionsItem><ElDescriptionsItem :label="t('assetManagement.labels.ratedPower')">{{ formatNumber(selectedEquipment.ratedPower) }}</ElDescriptionsItem></ElDescriptions>
        <section class="drawer-section"><h3>{{ t('assetManagement.equipment.identities') }}</h3><ElTable :data="selectedEquipment.identities" row-key="identityId"><ElTableColumn :label="t('assetManagement.labels.identityType')" prop="identityType" min-width="130" /><ElTableColumn :label="t('assetManagement.labels.identity')" prop="identityValue" min-width="180" /><ElTableColumn :label="t('assetManagement.labels.expectedProfile')" prop="expectedProfileCode" min-width="140" /><ElTableColumn :label="t('assetManagement.labels.status')" min-width="100"><template #default="{ row }"><AssetStatusTag :status="row.status" /></template></ElTableColumn><template #empty><ElEmpty :description="t('assetManagement.empty.identities')" /></template></ElTable></section>
        <section ref="pointsSection" class="drawer-section"><h3>{{ t('assetManagement.equipment.points') }}</h3><ElTable :data="management.points.value" row-key="pointId"><ElTableColumn :label="t('assetManagement.labels.pointName')" prop="pointName" min-width="160" /><ElTableColumn :label="t('assetManagement.labels.pointCode')" prop="pointCode" min-width="160" /><ElTableColumn :label="t('assetManagement.labels.unit')" prop="unit" min-width="90" /><ElTableColumn :label="t('assetManagement.labels.calculation')" min-width="110"><template #default="{ row }"><ElTag :type="row.forCalculation ? 'success' : 'info'">{{ row.forCalculation ? t('assetManagement.labels.calculation') : t('common.missing') }}</ElTag></template></ElTableColumn><ElTableColumn :label="t('assetManagement.actions.viewDetail')" min-width="190"><template #default="{ row }"><div class="row-actions"><ElButton v-if="can(row, 'UPDATE')" :icon="Pencil" link @click="openEditPoint(asPoint(row))">{{ t('assetManagement.actions.editPoint') }}</ElButton><ElPopconfirm v-if="can(row, 'DELETE')" :title="t('assetManagement.messages.deleteConfirm')" :confirm-button-text="t('assetManagement.actions.confirmDelete')" :cancel-button-text="t('assetManagement.actions.cancel')" @confirm="deletePoint(asPoint(row))"><template #reference><ElButton :icon="Trash2" link type="danger">{{ t('assetManagement.actions.deletePoint') }}</ElButton></template></ElPopconfirm></div></template></ElTableColumn><template #empty><ElEmpty :description="t('assetManagement.empty.points')" /></template></ElTable></section>
      </template>
    </ElDrawer>

    <EquipmentEditorDialog :open="equipmentEditorOpen" :equipment="editingEquipment" :buildings="management.buildingOptions.value" :spaces="management.scopeSpaces.value" :system-groups="management.scopeSystemGroups.value" :submitting="equipmentSubmitting" @close="equipmentEditorOpen = false" @building-change="management.loadScope" @save="saveEquipment" />
    <PointEditorDialog :open="pointEditorOpen" :point="editingPoint" :submitting="pointSubmitting" @close="pointEditorOpen = false" @save="savePoint" />
  </section>
</template>

<style scoped>
.equipment-page { display: grid; gap: var(--bec-space-section); min-width: 0; }
.page-heading, .drawer-heading, .row-actions { display: flex; align-items: center; gap: var(--bec-space-group); }
.page-heading, .drawer-heading { justify-content: space-between; }
.page-heading { align-items: flex-start; }
h1, h2, h3, p { margin: 0; }
h1 { font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); }
h2 { font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
h3 { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
p { color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }

.filter-panel, .list-panel { border-radius: var(--bec-management-radius); }
.filter-panel :deep(.el-card__body) { padding: var(--bec-space-section); }
.filter-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 3)), 1fr)); gap: var(--bec-space-group); }
.filter-grid :deep(.el-form-item) { margin-bottom: 0; min-width: 0; }
.filter-grid :deep(.el-select) { width: 100%; }
.filter-actions { display: flex; justify-content: flex-end; gap: var(--bec-space-tight); margin-top: var(--bec-space-section); }
.filter-actions :deep(.el-button + .el-button), .row-actions :deep(.el-button + .el-button) { margin-left: 0; }
.list-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); }
.list-heading h2 { font-size: var(--bec-management-title-font-size); }
.list-count, .secondary { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.list-panel :deep(.el-card__header) { padding: var(--bec-space-group) var(--bec-space-section); }
.list-panel :deep(.el-card__body) { padding: 0; }
.equipment-table :deep(th.el-table__cell) { background: var(--bec-color-surface-secondary); padding-block: var(--bec-space-group); }
.equipment-table :deep(td.el-table__cell) { padding-block: var(--bec-space-group); }
.equipment-table :deep(.cell) { padding-inline: var(--bec-space-group); }
.equipment-name { display: flex; align-items: center; gap: var(--bec-space-tight); }
.equipment-icon { display: grid; place-items: center; flex-shrink: 0; width: var(--bec-ref-space-32); height: var(--bec-ref-space-32); border-radius: var(--bec-management-radius); background: var(--bec-color-action-soft); color: var(--bec-color-action-primary); }
.equipment-icon svg { width: var(--bec-icon-small); height: var(--bec-icon-small); }
.name-link { color: var(--bec-color-text-primary); font-weight: var(--bec-font-weight-heading); min-width: 0; white-space: normal; text-align: left; }
.name-link :deep(span) { overflow-wrap: anywhere; }
.location-cell, .point-summary { display: grid; gap: var(--bec-ref-space-4); }
.drawer-actions { display: flex; flex-wrap: wrap; gap: var(--bec-space-tight); margin-bottom: var(--bec-space-group); }
.page-heading { flex-wrap: wrap; }
.list-panel .pagination { padding: var(--bec-space-group) var(--bec-space-section); overflow-x: auto; }
.pagination { display: flex; justify-content: flex-end; padding-top: var(--bec-space-group); }
.drawer-heading { align-items: flex-start; margin-bottom: var(--bec-space-section); }
.drawer-section { display: grid; gap: var(--bec-space-group); margin-top: var(--bec-space-section); }
.row-actions { gap: var(--bec-space-tight); flex-wrap: wrap; }
</style>
