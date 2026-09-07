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
  ElOption,
  ElPagination,
  ElPopconfirm,
  ElSelect,
  ElSkeleton,
  ElTable,
  ElTableColumn,
  ElTag,
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
import { canRunAssetAction, type AssetEquipmentDetail, type AssetPoint } from '../models/assets'

const management = useAssetManagement()
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
      buildingId: management.equipmentQuery.value.buildingId,
      status: management.equipmentQuery.value.status,
      keyword: management.equipmentQuery.value.keyword,
    })
  } catch {
    // 查询错误已保留在 equipmentError，避免事件处理器产生未处理拒绝。
  }
}

async function buildingFilterChanged(buildingId: string | undefined) {
  management.equipmentQuery.value.spaceId = undefined
  management.equipmentQuery.value.systemGroupId = undefined
  try {
    await Promise.all([management.loadScope(buildingId), management.setEquipmentQuery({ buildingId })])
  } catch {
    // 依赖范围与列表分别保留受控错误状态。
  }
}

async function resetFilters() {
  management.equipmentQuery.value = { page: 1, size: management.equipmentQuery.value.size }
  try {
    await management.loadEquipment()
  } catch {
    // 查询错误已保留在 equipmentError。
  }
}

async function openEquipment(equipmentId: string) {
  equipmentDrawerOpen.value = true
  try {
    await management.selectEquipment(equipmentId)
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
  return `${value.configuredRequired}/${value.required}/${value.total}`
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
    <ElAlert v-if="management.scopeError.value" :title="management.scopeError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="management.operationError.value" :title="management.operationError.value.message" type="error" show-icon :closable="false" />
    <ElCard shadow="never">
      <div class="filter-bar">
        <ElSelect v-model="management.equipmentQuery.value.buildingId" clearable :placeholder="t('assetManagement.labels.building')" @change="buildingFilterChanged">
          <ElOption v-for="item in management.buildingOptions.value" :key="item.value" :label="item.label" :value="item.value" />
        </ElSelect>
        <ElInput v-model="management.equipmentQuery.value.keyword" :placeholder="t('assetManagement.labels.keyword')" clearable @keyup.enter="query"><template #prefix><Search aria-hidden="true" /></template></ElInput>
        <ElButton :icon="Search" @click="query">{{ t('assetManagement.actions.query') }}</ElButton>
        <ElButton :icon="RefreshCw" @click="resetFilters">{{ t('assetManagement.actions.reset') }}</ElButton>
      </div>
      <ElSkeleton v-if="management.equipmentLoading.value && !management.equipment.value.items.length" animated :rows="5" />
      <ElTable v-else v-loading="management.equipmentLoading.value" :data="management.equipment.value.items" row-key="equipmentId">
        <ElTableColumn :label="t('assetManagement.labels.equipmentName')" min-width="180"><template #default="{ row }"><ElButton link @click="openEquipment(row.equipmentId)">{{ row.equipmentName }}</ElButton></template></ElTableColumn>
        <ElTableColumn :label="t('assetManagement.labels.equipmentCode')" prop="equipmentCode" min-width="140" />
        <ElTableColumn :label="t('assetManagement.labels.building')" prop="buildingName" min-width="140" />
        <ElTableColumn :label="t('assetManagement.labels.system')" prop="systemGroupName" min-width="140" />
        <ElTableColumn :label="t('assetManagement.labels.pointSummary')" min-width="140"><template #default="{ row }">{{ summary(row.pointSummary) }}</template></ElTableColumn>
        <ElTableColumn :label="t('assetManagement.labels.status')" min-width="110"><template #default="{ row }"><AssetStatusTag :status="row.status" /></template></ElTableColumn>
        <ElTableColumn :label="t('assetManagement.actions.viewDetail')" min-width="220" fixed="right"><template #default="{ row }"><div class="row-actions"><ElButton link @click="openEquipment(row.equipmentId)">{{ t('assetManagement.actions.viewDetail') }}</ElButton><ElButton v-if="can(row, 'UPDATE')" :icon="Pencil" link @click="openEditEquipment(row.equipmentId)">{{ t('assetManagement.actions.editEquipment') }}</ElButton><ElPopconfirm v-if="can(row, 'DELETE')" :title="t('assetManagement.messages.deleteConfirm')" :confirm-button-text="t('assetManagement.actions.confirmDelete')" :cancel-button-text="t('assetManagement.actions.cancel')" @confirm="deleteEquipment(row.equipmentId)"><template #reference><ElButton :icon="Trash2" link type="danger">{{ t('assetManagement.actions.deleteEquipment') }}</ElButton></template></ElPopconfirm></div></template></ElTableColumn>
        <template #empty><ElEmpty :description="t('assetManagement.empty.equipment')" /></template>
      </ElTable>
      <div class="pagination"><ElPagination background layout="total, prev, pager, next" :current-page="management.equipment.value.page" :page-size="management.equipment.value.size" :total="management.equipment.value.total" @current-change="page => management.setEquipmentQuery({ page }, false)" /></div>
    </ElCard>

    <ElDrawer :model-value="equipmentDrawerOpen" size="55%" :title="t('assetManagement.equipment.detail')" @update:model-value="equipmentDrawerOpen = false">
      <ElSkeleton v-if="management.equipmentContextLoading.value" animated :rows="8" />
      <ElAlert v-else-if="management.equipmentContextError.value" :title="management.equipmentContextError.value.message" type="error" show-icon :closable="false" />
      <template v-else-if="selectedEquipment">
        <div class="drawer-heading"><div><h2>{{ selectedEquipment.equipmentName }}</h2><p>{{ selectedEquipment.equipmentCode || t('common.missing') }}</p></div><AssetStatusTag :status="selectedEquipment.status" /></div>
        <ElDescriptions :column="2" border><ElDescriptionsItem :label="t('assetManagement.labels.building')">{{ selectedEquipment.buildingName || t('common.missing') }}</ElDescriptionsItem><ElDescriptionsItem :label="t('assetManagement.labels.space')">{{ selectedEquipment.spaceName || t('common.missing') }}</ElDescriptionsItem><ElDescriptionsItem :label="t('assetManagement.labels.system')">{{ selectedEquipment.systemGroupName || t('common.missing') }}</ElDescriptionsItem><ElDescriptionsItem :label="t('assetManagement.labels.productName')">{{ selectedEquipment.productName || t('common.missing') }}</ElDescriptionsItem><ElDescriptionsItem :label="t('assetManagement.labels.manufacturer')">{{ selectedEquipment.manufacturer || t('common.missing') }}</ElDescriptionsItem><ElDescriptionsItem :label="t('assetManagement.labels.expectedProfile')">{{ selectedEquipment.expectedProfileCode || t('common.missing') }}</ElDescriptionsItem><ElDescriptionsItem :label="t('assetManagement.labels.ratedCapacity')">{{ formatNumber(selectedEquipment.ratedCapacity) }}</ElDescriptionsItem><ElDescriptionsItem :label="t('assetManagement.labels.ratedPower')">{{ formatNumber(selectedEquipment.ratedPower) }}</ElDescriptionsItem></ElDescriptions>
        <section class="drawer-section"><h3>{{ t('assetManagement.equipment.identities') }}</h3><ElTable :data="selectedEquipment.identities" row-key="identityId"><ElTableColumn :label="t('assetManagement.labels.identityType')" prop="identityType" min-width="130" /><ElTableColumn :label="t('assetManagement.labels.identity')" prop="identityValue" min-width="180" /><ElTableColumn :label="t('assetManagement.labels.expectedProfile')" prop="expectedProfileCode" min-width="140" /><ElTableColumn :label="t('assetManagement.labels.status')" min-width="100"><template #default="{ row }"><AssetStatusTag :status="row.status" /></template></ElTableColumn><template #empty><ElEmpty :description="t('assetManagement.empty.identities')" /></template></ElTable></section>
        <section class="drawer-section"><h3>{{ t('assetManagement.equipment.points') }}</h3><ElTable :data="management.points.value" row-key="pointId"><ElTableColumn :label="t('assetManagement.labels.pointName')" prop="pointName" min-width="160" /><ElTableColumn :label="t('assetManagement.labels.pointCode')" prop="pointCode" min-width="160" /><ElTableColumn :label="t('assetManagement.labels.unit')" prop="unit" min-width="90" /><ElTableColumn :label="t('assetManagement.labels.calculation')" min-width="110"><template #default="{ row }"><ElTag :type="row.forCalculation ? 'success' : 'info'">{{ row.forCalculation ? t('assetManagement.labels.calculation') : t('common.missing') }}</ElTag></template></ElTableColumn><ElTableColumn :label="t('assetManagement.actions.viewDetail')" min-width="190"><template #default="{ row }"><div class="row-actions"><ElButton v-if="can(row, 'UPDATE')" :icon="Pencil" link @click="openEditPoint(asPoint(row))">{{ t('assetManagement.actions.editPoint') }}</ElButton><ElPopconfirm v-if="can(row, 'DELETE')" :title="t('assetManagement.messages.deleteConfirm')" :confirm-button-text="t('assetManagement.actions.confirmDelete')" :cancel-button-text="t('assetManagement.actions.cancel')" @confirm="deletePoint(asPoint(row))"><template #reference><ElButton :icon="Trash2" link type="danger">{{ t('assetManagement.actions.deletePoint') }}</ElButton></template></ElPopconfirm></div></template></ElTableColumn><template #empty><ElEmpty :description="t('assetManagement.empty.points')" /></template></ElTable></section>
      </template>
    </ElDrawer>

    <EquipmentEditorDialog :open="equipmentEditorOpen" :equipment="editingEquipment" :buildings="management.buildingOptions.value" :spaces="management.scopeSpaces.value" :system-groups="management.scopeSystemGroups.value" :submitting="equipmentSubmitting" @close="equipmentEditorOpen = false" @building-change="management.loadScope" @save="saveEquipment" />
    <PointEditorDialog :open="pointEditorOpen" :point="editingPoint" :submitting="pointSubmitting" @close="pointEditorOpen = false" @save="savePoint" />
  </section>
</template>

<style scoped>
.equipment-page { display: grid; gap: var(--bec-space-section); min-width: 0; }
.page-heading, .drawer-heading, .filter-bar, .row-actions { display: flex; align-items: center; gap: var(--bec-space-group); }
.page-heading, .drawer-heading { justify-content: space-between; }
.page-heading { align-items: flex-start; }
h1, h2, h3, p { margin: 0; }
h1 { font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); }
h2 { font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
h3 { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
p { color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }
.filter-bar { align-items: stretch; }
.filter-bar :deep(.el-input) { flex: 1; }
.filter-bar :deep(.el-select) { min-width: var(--bec-navigation-width); }
.pagination { display: flex; justify-content: flex-end; padding-top: var(--bec-space-group); }
.drawer-heading { align-items: flex-start; margin-bottom: var(--bec-space-section); }
.drawer-section { display: grid; gap: var(--bec-space-group); margin-top: var(--bec-space-section); }
.row-actions { gap: var(--bec-space-tight); flex-wrap: wrap; }
</style>
