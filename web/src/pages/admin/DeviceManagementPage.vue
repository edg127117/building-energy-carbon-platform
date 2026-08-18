<template>
  <div class="admin-page asset-management-page">
    <AdminPageHeader title="设备与测点" description="按建筑、空间、系统、类型、产品和状态查找设备；身份、协议和测点映射仍以服务端记录为准。">
      <a-button type="primary" @click="openEquipmentCreate">新建设备</a-button>
    </AdminPageHeader>

    <a-alert v-if="pageError" class="admin-error" type="error" show-icon :message="pageError" />

    <section class="admin-panel">
      <div class="admin-filter-bar asset-device-filters">
        <a-select v-model:value="filterBuildingId" allow-clear placeholder="全部建筑" style="width:180px" :options="assets.buildingOptions.value" :loading="assets.buildingsLoading.value" @change="changeFilterBuilding" />
        <a-select v-model:value="filterSpaceId" allow-clear placeholder="全部空间" style="width:160px" :options="spaceOptions" :disabled="!filterBuildingId" :loading="assets.equipmentScopeLoading.value" @change="applyFilters" />
        <a-select v-model:value="filterSystemGroupId" allow-clear placeholder="全部系统分组" style="width:170px" :options="systemGroupOptions" :disabled="!filterBuildingId" :loading="assets.equipmentScopeLoading.value" @change="applyFilters" />
        <a-input v-model:value="typeCode" allow-clear placeholder="设备类型编码" style="width:150px" @press-enter="applyFilters" />
        <a-input v-model:value="productId" allow-clear placeholder="产品 ID" style="width:130px" @press-enter="applyFilters" />
        <a-select v-model:value="status" allow-clear placeholder="全部状态" style="width:120px" :options="statusOptions" @change="applyFilters" />
        <a-input v-model:value="keyword" allow-clear placeholder="搜索设备名称或编码" style="width:210px" @press-enter="applyFilters" />
        <a-button @click="applyFilters">查询</a-button>
      </div>
      <a-skeleton v-if="assets.equipmentLoading.value && assets.equipmentPage.value.items.length === 0" active :paragraph="{ rows: 6 }" />
      <a-table
        v-else :data-source="assets.equipmentPage.value.items" :columns="equipmentColumns" row-key="equipmentId"
        :loading="assets.equipmentLoading.value" :scroll="{ x: 1180 }"
        :pagination="{ current: assets.equipmentPage.value.page, pageSize: assets.equipmentPage.value.size, total: assets.equipmentPage.value.total }"
        @change="changeEquipmentPage"
      >
        <template #emptyText><a-empty description="没有符合筛选条件的设备" /></template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'location'"><span>{{ [record.buildingName, record.spaceName, record.systemGroupName].filter(Boolean).join(' / ') || '未归类' }}</span></template>
          <template v-else-if="column.key === 'product'"><span>{{ record.productName || record.productId || '未关联' }}</span></template>
          <template v-else-if="column.key === 'status'"><AssetStatusTag :status="record.status" /></template>
          <template v-else-if="column.key === 'lastDiscoveredTime'"><span>{{ formatTime(record.lastDiscoveredTime) }}</span></template>
          <template v-else-if="column.key === 'actions'"><a-space size="small" wrap><a-button type="link" @click="openEquipmentDetail(record)">详情</a-button><a-button type="link" :disabled="!canManage(record, 'UPDATE')" @click="openEquipmentEdit(record)">编辑</a-button><a-button type="link" danger :disabled="!canManage(record, 'DELETE')" @click="confirmEquipmentRemove(record)">删除</a-button></a-space></template>
        </template>
      </a-table>
    </section>

    <AssetEquipmentDrawer :open="equipmentDrawerOpen" :equipment="editingEquipment" :buildings="assets.buildingOptions.value" :spaces="assets.equipmentScopeSpaces.value" :system-groups="assets.equipmentScopeGroups.value" :submitting="assets.pending.value.has(equipmentCommandKey)" @close="equipmentDrawerOpen = false" @save="saveEquipment" @building-change="loadDrawerScope" />

    <a-drawer :open="detailDrawerOpen" title="设备档案与测点" width="min(880px, 92vw)" destroy-on-close @close="detailDrawerOpen = false">
      <a-skeleton v-if="assets.equipmentDetailLoading.value" active :paragraph="{ rows: 9 }" />
      <template v-else-if="assets.selectedEquipment.value">
        <a-alert v-if="detailError" class="admin-error" type="error" show-icon :message="detailError"><template #action><a-button size="small" @click="retryEquipmentDetail">重试</a-button></template></a-alert>
        <section class="asset-detail-summary">
          <div><span class="asset-section-kicker">设备</span><h2>{{ assets.selectedEquipment.value.equipmentName }}</h2><p>{{ assets.selectedEquipment.value.equipmentCode || '未配置设备编码' }} · {{ assets.selectedEquipment.value.typeCode || '未配置类型' }}</p></div>
          <AssetStatusTag :status="assets.selectedEquipment.value.status" />
        </section>
        <a-descriptions bordered size="small" :column="2" class="asset-descriptions">
          <a-descriptions-item label="建筑">{{ assets.selectedEquipment.value.buildingName || assets.selectedEquipment.value.buildingId }}</a-descriptions-item>
          <a-descriptions-item label="空间 / 系统">{{ [assets.selectedEquipment.value.spaceName, assets.selectedEquipment.value.systemGroupName].filter(Boolean).join(' / ') || '未归类' }}</a-descriptions-item>
          <a-descriptions-item label="产品">{{ assets.selectedEquipment.value.productName || assets.selectedEquipment.value.productId || '未关联' }}</a-descriptions-item>
          <a-descriptions-item label="最近发现">{{ formatTime(assets.selectedEquipment.value.lastDiscoveredTime) }}</a-descriptions-item>
          <a-descriptions-item label="必需测点">{{ assets.selectedEquipment.value.pointSummary.configuredRequired }} / {{ assets.selectedEquipment.value.pointSummary.required }}</a-descriptions-item>
          <a-descriptions-item label="测点总数">{{ assets.selectedEquipment.value.pointSummary.total }}</a-descriptions-item>
        </a-descriptions>
        <section class="asset-detail-section">
          <h3>外部身份与协议</h3>
          <a-empty v-if="assets.selectedEquipment.value.identities.length === 0" description="未返回设备身份" />
          <a-table v-else :data-source="assets.selectedEquipment.value.identities" :columns="identityColumns" row-key="identityId" :pagination="false" size="small">
            <template #bodyCell="{ column, record }"><template v-if="column.key === 'status'"><AssetStatusTag :status="record.status" /></template></template>
          </a-table>
        </section>
        <section class="asset-detail-section">
          <div class="asset-section-toolbar"><div><h3>设备测点</h3><p>来源别名仅用于展示；范围、计算标记和状态可在本页编辑。</p></div></div>
          <a-empty v-if="assets.points.value.length === 0" description="当前设备没有测点" />
          <a-table v-else :data-source="assets.points.value" :columns="pointColumns" row-key="pointId" :pagination="false" size="small" :scroll="{ x: 780 }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'range'"><span>{{ formatRange(record.minValue, record.maxValue, record.unit) }}</span></template>
              <template v-else-if="column.key === 'calculation'"><a-tag :color="record.forCalculation ? 'green' : 'default'">{{ record.forCalculation ? '参与' : '不参与' }}</a-tag></template>
              <template v-else-if="column.key === 'aliases'"><a-space wrap><a-tag v-for="alias in record.sourceAliases" :key="alias">{{ alias }}</a-tag><span v-if="record.sourceAliases.length === 0">—</span></a-space></template>
              <template v-else-if="column.key === 'status'"><AssetStatusTag :status="record.status" /></template>
              <template v-else-if="column.key === 'actions'"><a-space size="small"><a-button type="link" :disabled="!canManage(record, 'UPDATE')" @click="openPointEdit(record)">编辑</a-button><a-button type="link" danger :disabled="!canManage(record, 'DELETE')" @click="confirmPointRemove(record)">删除</a-button></a-space></template>
            </template>
          </a-table>
        </section>
      </template>
      <a-empty v-else description="未选择设备" />
    </a-drawer>

    <AssetPointDrawer :open="pointDrawerOpen" :point="editingPoint" :submitting="assets.pending.value.has(pointCommandKey)" @close="pointDrawerOpen = false" @save="savePoint" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Modal, message } from 'ant-design-vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import AssetEquipmentDrawer from '@/components/admin/AssetEquipmentDrawer.vue'
import AssetPointDrawer from '@/components/admin/AssetPointDrawer.vue'
import AssetStatusTag from '@/components/admin/AssetStatusTag.vue'
import { useAssetManagement } from '@/composables/useAssetManagement'
import { canRunAssetAction, type AssetAllowedAction, type AssetDataPointUpdateRequest, type AssetDataPointView, type AssetEquipmentForm, type AssetEquipmentView, type AssetSpaceView } from '@/types/assets'
import { requestErrorMessage } from '@/utils/request'

// 页面按后端身份、状态和 allowedActions 展示设备；前端不推断绑定关系，也不改写正式数据链。
const assets = useAssetManagement()
const filterBuildingId = ref<string>()
const filterSpaceId = ref<string>()
const filterSystemGroupId = ref<string>()
const typeCode = ref('')
const productId = ref('')
const status = ref<string>()
const keyword = ref('')
const explicitPageError = ref<string | null>(null)
const pageError = computed(() => explicitPageError.value ?? assets.equipmentError.value?.message ?? null)
const equipmentDrawerOpen = ref(false)
const editingEquipment = ref<AssetEquipmentView | null>(null)
const detailDrawerOpen = ref(false)
const pointDrawerOpen = ref(false)
const editingPoint = ref<AssetDataPointView | null>(null)

const equipmentColumns = [
  { title: '设备名称', dataIndex: 'equipmentName', key: 'equipmentName', width: 180 }, { title: '设备编码', dataIndex: 'equipmentCode', key: 'equipmentCode', width: 130 },
  { title: '位置', key: 'location', width: 240 }, { title: '类型', dataIndex: 'typeCode', key: 'typeCode', width: 120 },
  { title: '产品', key: 'product', width: 140 }, { title: '状态', key: 'status', width: 90 }, { title: '最近发现', key: 'lastDiscoveredTime', width: 170 },
  { title: '操作', key: 'actions', width: 170, fixed: 'right' },
]
const identityColumns = [{ title: '身份类型', dataIndex: 'identityType', key: 'identityType' }, { title: '身份值', dataIndex: 'identityValue', key: 'identityValue' }, { title: '期望协议', dataIndex: 'expectedProfileCode', key: 'expectedProfileCode' }, { title: '状态', key: 'status', width: 90 }]
const pointColumns = [{ title: '测点', dataIndex: 'pointName', key: 'pointName', width: 140 }, { title: '编码', dataIndex: 'pointCode', key: 'pointCode', width: 110 }, { title: '范围', key: 'range', width: 145 }, { title: '计算', key: 'calculation', width: 80 }, { title: '来源别名', key: 'aliases', width: 180 }, { title: '状态', key: 'status', width: 90 }, { title: '操作', key: 'actions', width: 130, fixed: 'right' }]
const statusOptions = [
  { label: '启用', value: 'ACTIVE' },
  { label: '停用', value: 'DISABLED' },
  { label: '未绑定', value: 'UNBOUND' },
]
const spaceOptions = computed(() => flatten(assets.equipmentScopeSpaces.value).map((item) => ({ label: item.spaceName, value: item.spaceId })))
const systemGroupOptions = computed(() => assets.equipmentScopeGroups.value.map((item) => ({ label: item.systemName, value: item.systemGroupId })))
const equipmentCommandKey = computed(() => editingEquipment.value ? `equipment:update:${editingEquipment.value.equipmentId}` : 'equipment:create')
const pointCommandKey = computed(() => editingPoint.value ? `point:update:${editingPoint.value.pointId}` : '')
const detailError = computed(() => assets.equipmentDetailError.value?.message ?? null)

function canManage(record: { status: string; allowedActions?: AssetAllowedAction[] }, action: AssetAllowedAction) { return canRunAssetAction(record, action) }
function applyFilters() { void assets.setEquipmentQuery({ buildingId: filterBuildingId.value, spaceId: filterSpaceId.value, systemGroupId: filterSystemGroupId.value, typeCode: typeCode.value.trim() || undefined, productId: productId.value.trim() || undefined, status: status.value, keyword: keyword.value.trim() || undefined }).catch(showError) }
async function changeFilterBuilding(buildingId: string | undefined) { filterSpaceId.value = undefined; filterSystemGroupId.value = undefined; try { await assets.loadEquipmentScope(buildingId); await assets.setEquipmentQuery({ buildingId, spaceId: undefined, systemGroupId: undefined }) } catch (reason) { showError(reason) } }
function changeEquipmentPage(pagination: { current?: number }) { void assets.setEquipmentQuery({ page: pagination.current ?? 1 }, false).catch(showError) }
function openEquipmentCreate() { editingEquipment.value = null; void assets.loadEquipmentScope(filterBuildingId.value).catch(showError); equipmentDrawerOpen.value = true }
async function openEquipmentEdit(equipment: AssetEquipmentView) {
  try {
    await Promise.all([assets.loadEquipmentScope(equipment.buildingId), assets.selectEquipment(equipment.equipmentId)])
    editingEquipment.value = assets.selectedEquipment.value ?? equipment
    equipmentDrawerOpen.value = true
  } catch (reason) { showError(reason) }
}
function loadDrawerScope(buildingId: string | undefined) { void assets.loadEquipmentScope(buildingId).catch(showError) }
function openEquipmentDetail(equipment: AssetEquipmentView) { detailDrawerOpen.value = true; void assets.selectEquipment(equipment.equipmentId).catch(showError) }
function retryEquipmentDetail() { if (assets.selectedEquipment.value) void assets.selectEquipment(assets.selectedEquipment.value.equipmentId).catch(showError) }
function openPointEdit(point: AssetDataPointView) { editingPoint.value = point; pointDrawerOpen.value = true }

async function saveEquipment(request: AssetEquipmentForm) { try { await assets.saveEquipment(editingEquipment.value?.equipmentId ?? null, request); equipmentDrawerOpen.value = false; message.success('设备档案已保存') } catch (reason) { showError(reason) } }
async function savePoint(request: AssetDataPointUpdateRequest) { const equipmentId = assets.selectedEquipment.value?.equipmentId; const pointId = editingPoint.value?.pointId; if (!equipmentId || !pointId) return; try { await assets.savePoint(equipmentId, pointId, request); pointDrawerOpen.value = false; message.success('测点已保存') } catch (reason) { showError(reason) } }
function confirmEquipmentRemove(equipment: AssetEquipmentView) { Modal.confirm({ title: '删除该设备？', content: '后端会再次检查身份、测点与建筑归属，删除不会在浏览器端修改正式时序数据。', okText: '确认删除', cancelText: '取消', okType: 'danger', onOk: () => assets.removeEquipment(equipment.equipmentId).then(() => message.success('设备已删除')).catch(showError) }) }
function confirmPointRemove(point: AssetDataPointView) { const equipmentId = assets.selectedEquipment.value?.equipmentId; if (!equipmentId) return; Modal.confirm({ title: '删除该测点？', content: '后端会检查别名和已绑定设备约束。', okText: '确认删除', cancelText: '取消', okType: 'danger', onOk: () => assets.removePoint(equipmentId, point.pointId).then(() => message.success('测点已删除')).catch(showError) }) }
function formatRange(min: number | null, max: number | null, unit: string | null) { const range = [min ?? '—', max ?? '—'].join(' ～ '); return unit ? `${range} ${unit}` : range }
function formatTime(value: number | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '未发现' }
function flatten(items: AssetSpaceView[]): AssetSpaceView[] { return items.flatMap((item) => [item, ...flatten(item.children)]) }
function showError(reason: unknown) { explicitPageError.value = requestErrorMessage(reason) }

onMounted(() => { void Promise.all([assets.ensureBuildingOptions(), assets.loadEquipment()]).catch(showError) })
</script>

<style scoped>
.asset-device-filters { align-items: flex-start; }
.asset-detail-summary, .asset-section-toolbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.asset-detail-summary { margin-bottom: 18px; }.asset-section-kicker { color: #72bbed; font-size: 11px; font-weight: 650; letter-spacing: .08em; }.asset-detail-summary h2 { margin: 4px 0 0; font-size: 20px; }.asset-detail-summary p, .asset-section-toolbar p { margin: 5px 0 0; color: #91a8bd; font-size: 12px; }
.asset-descriptions { margin-bottom: 24px; }.asset-detail-section + .asset-detail-section { margin-top: 26px; }.asset-detail-section h3 { margin: 0 0 8px; color: #dcecf8; font-size: 15px; }.asset-detail-section .asset-section-toolbar { margin-bottom: 12px; }
</style>
