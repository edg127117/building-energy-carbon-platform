<template>
  <div class="admin-page asset-management-page">
    <AdminPageHeader title="建筑与空间" description="维护建筑、空间和系统分组。删除前会核对设备、测点和授权引用，最终归属与权限由后端裁决。">
      <a-button type="primary" @click="openBuildingCreate">新建建筑</a-button>
    </AdminPageHeader>

    <a-alert v-if="pageError" class="admin-error" type="error" show-icon :message="pageError" />

    <section class="admin-panel">
      <div class="admin-filter-bar">
        <a-input v-model:value="keyword" allow-clear placeholder="搜索建筑名称或编码" style="width:280px" @press-enter="applyBuildingFilters" />
        <a-button @click="applyBuildingFilters">查询</a-button>
      </div>
      <a-skeleton v-if="assets.buildingsLoading.value && assets.buildingPage.value.items.length === 0" active :paragraph="{ rows: 6 }" />
      <a-table
        v-else :data-source="assets.buildingPage.value.items" :columns="buildingColumns" row-key="buildingId"
        :loading="assets.buildingsLoading.value" :pagination="{ current: assets.buildingPage.value.page, pageSize: assets.buildingPage.value.size, total: assets.buildingPage.value.total }"
        :scroll="{ x: 780 }"
        @change="changeBuildingPage"
      >
        <template #emptyText><a-empty description="暂无建筑档案" /></template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'"><AssetStatusTag :status="record.status" /></template>
          <template v-else-if="column.key === 'references'"><span class="asset-reference-compact">{{ referenceText(record.references) }}</span></template>
          <template v-else-if="column.key === 'actions'">
            <a-space size="small" wrap>
              <a-button type="link" @click="openBuilding(record)">查看</a-button>
              <a-button type="link" :disabled="!canManage(record, 'UPDATE')" @click="openBuildingEdit(record)">编辑</a-button>
              <a-button type="link" danger :disabled="!canManage(record, 'DELETE')" @click="confirmBuildingRemove(record)">删除</a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </section>

    <section v-if="assets.selectedBuilding.value" class="admin-panel asset-context-panel">
      <div class="asset-context-heading">
        <div><span class="asset-section-kicker">当前建筑</span><h2>{{ assets.selectedBuilding.value.buildingName }}</h2><p>{{ buildingSummary }}</p></div>
        <a-button @click="clearBuilding">收起详情</a-button>
      </div>
      <a-skeleton v-if="assets.buildingContextLoading.value" active :paragraph="{ rows: 5 }" />
      <template v-else>
        <a-alert v-if="contextError" class="admin-error" type="error" show-icon :message="contextError">
          <template #action><a-button size="small" @click="retryBuildingContext">重试</a-button></template>
        </a-alert>
        <div class="asset-reference-grid" aria-label="建筑引用状态">
          <span v-for="item in referenceItems" :key="item.label"><small>{{ item.label }}</small><strong>{{ item.value }}</strong></span>
        </div>
        <a-tabs>
          <a-tab-pane key="spaces" tab="空间树">
            <div class="asset-section-toolbar"><p>空间只能维护在当前建筑内；选择“下级”可建立明确层级。</p><a-button type="primary" :disabled="!canManage(assets.selectedBuilding.value, 'CREATE')" @click="openSpaceCreate(null)">新建空间</a-button></div>
            <a-empty v-if="assets.spaces.value.length === 0" description="当前建筑还没有空间">
              <a-button type="primary" :disabled="!canManage(assets.selectedBuilding.value, 'CREATE')" @click="openSpaceCreate(null)">新建首个空间</a-button>
            </a-empty>
            <AssetSpaceTree v-else :items="assets.spaces.value" @add-child="openSpaceCreate" @edit="openSpaceEdit" @remove="confirmSpaceRemove" />
          </a-tab-pane>
          <a-tab-pane key="systems" tab="系统分组">
            <div class="asset-section-toolbar"><p>系统分组用于设备归类，不会改变已绑定设备的数据链。</p><a-button type="primary" :disabled="!canManage(assets.selectedBuilding.value, 'CREATE')" @click="openSystemGroupCreate">新建系统分组</a-button></div>
            <a-table :data-source="assets.systemGroups.value.items" :columns="systemGroupColumns" row-key="systemGroupId" :pagination="false" :scroll="{ x: 650 }">
              <template #emptyText><a-empty description="当前建筑还没有系统分组" /></template>
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'status'"><AssetStatusTag :status="record.status" /></template>
                <template v-else-if="column.key === 'actions'"><a-space size="small"><a-button type="link" :disabled="!canManage(record, 'UPDATE')" @click="openSystemGroupEdit(record)">编辑</a-button><a-button type="link" danger :disabled="!canManage(record, 'DELETE')" @click="confirmSystemGroupRemove(record)">删除</a-button></a-space></template>
              </template>
            </a-table>
          </a-tab-pane>
        </a-tabs>
      </template>
    </section>

    <AssetBuildingDrawer :open="buildingDrawerOpen" :building="editingBuilding" :submitting="assets.pending.value.has(buildingCommandKey)" @close="buildingDrawerOpen = false" @save="saveBuilding" />
    <AssetSpaceDrawer :open="spaceDrawerOpen" :building-id="assets.selectedBuilding.value?.buildingId ?? ''" :space="editingSpace" :parent-space="spaceParent" :spaces="assets.spaces.value" :submitting="assets.pending.value.has(spaceCommandKey)" @close="spaceDrawerOpen = false" @save="saveSpace" />
    <AssetSystemGroupDrawer :open="systemGroupDrawerOpen" :building-id="assets.selectedBuilding.value?.buildingId ?? ''" :system-group="editingSystemGroup" :submitting="assets.pending.value.has(systemGroupCommandKey)" @close="systemGroupDrawerOpen = false" @save="saveSystemGroup" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Modal, message } from 'ant-design-vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import AssetBuildingDrawer from '@/components/admin/AssetBuildingDrawer.vue'
import AssetSpaceDrawer from '@/components/admin/AssetSpaceDrawer.vue'
import AssetSpaceTree from '@/components/admin/AssetSpaceTree.vue'
import AssetStatusTag from '@/components/admin/AssetStatusTag.vue'
import AssetSystemGroupDrawer from '@/components/admin/AssetSystemGroupDrawer.vue'
import { useAssetManagement } from '@/composables/useAssetManagement'
import { canRunAssetAction, type AssetAllowedAction, type AssetBuildingForm, type AssetBuildingView, type AssetReferenceSummary, type AssetSpaceForm, type AssetSpaceView, type AssetSystemGroupForm, type AssetSystemGroupView } from '@/types/assets'
import { requestErrorMessage } from '@/utils/request'

// 页面只呈现后端返回的引用与 allowedActions；删除保护和建筑归属仍由服务端最终裁决。
const assets = useAssetManagement()
const keyword = ref('')
const explicitPageError = ref<string | null>(null)
const pageError = computed(() => explicitPageError.value ?? assets.buildingsError.value?.message ?? null)
const buildingDrawerOpen = ref(false)
const editingBuilding = ref<AssetBuildingView | null>(null)
const spaceDrawerOpen = ref(false)
const editingSpace = ref<AssetSpaceView | null>(null)
const spaceParent = ref<AssetSpaceView | null>(null)
const systemGroupDrawerOpen = ref(false)
const editingSystemGroup = ref<AssetSystemGroupView | null>(null)

const buildingColumns = [
  { title: '建筑名称', dataIndex: 'buildingName', key: 'buildingName', width: 180 }, { title: '编码', dataIndex: 'buildingCode', key: 'buildingCode', width: 110 },
  { title: '类型', dataIndex: 'buildingType', key: 'buildingType', width: 120 }, { title: '状态', key: 'status', width: 90 },
  { title: '引用', key: 'references', width: 120 }, { title: '操作', key: 'actions', width: 160, fixed: 'right' },
]
const systemGroupColumns = [
  { title: '系统名称', dataIndex: 'systemName', key: 'systemName', width: 180 }, { title: '编码', dataIndex: 'systemCode', key: 'systemCode', width: 120 },
  { title: '类型', dataIndex: 'systemType', key: 'systemType', width: 110 }, { title: '状态', key: 'status', width: 90 }, { title: '操作', key: 'actions', width: 130, fixed: 'right' },
]
const buildingCommandKey = computed(() => editingBuilding.value ? `building:update:${editingBuilding.value.buildingId}` : 'building:create')
const spaceCommandKey = computed(() => editingSpace.value ? `space:update:${editingSpace.value.spaceId}` : 'space:create')
const systemGroupCommandKey = computed(() => editingSystemGroup.value ? `system-group:update:${editingSystemGroup.value.systemGroupId}` : 'system-group:create')
const contextError = computed(() => assets.buildingContextError.value?.message ?? null)
const buildingSummary = computed(() => [assets.selectedBuilding.value?.buildingCode, assets.selectedBuilding.value?.buildingType, assets.selectedBuilding.value?.totalGfa ? `${assets.selectedBuilding.value.totalGfa} ㎡` : null].filter(Boolean).join(' · ') || '尚未补充建筑编码、类型或总建筑面积')
const referenceItems = computed(() => {
  const references = assets.selectedBuilding.value?.references
  return [{ label: '空间', value: references?.spaces ?? 0 }, { label: '系统分组', value: references?.systemGroups ?? 0 }, { label: '设备', value: references?.equipment ?? 0 }, { label: '测点', value: references?.points ?? 0 }, { label: '授权', value: references?.authorizations ?? 0 }]
})

function canManage(record: { status: string; allowedActions?: AssetAllowedAction[] }, action: AssetAllowedAction) { return canRunAssetAction(record, action) }
function referenceText(references?: AssetReferenceSummary) { const count = (references?.equipment ?? 0) + (references?.points ?? 0) + (references?.authorizations ?? 0); return count ? `${count} 项关联` : '无关联' }
function applyBuildingFilters() { void assets.setBuildingQuery({ keyword: keyword.value.trim() || undefined }).catch(showError) }
function changeBuildingPage(pagination: { current?: number }) { void assets.setBuildingQuery({ page: pagination.current ?? 1 }, false).catch(showError) }
function openBuildingCreate() { editingBuilding.value = null; buildingDrawerOpen.value = true }
function openBuildingEdit(building: AssetBuildingView) { editingBuilding.value = building; buildingDrawerOpen.value = true }
function openBuilding(building: AssetBuildingView) { void assets.selectBuilding(building.buildingId).catch(showError) }
function clearBuilding() { void assets.selectBuilding(null) }
function retryBuildingContext() { if (assets.selectedBuilding.value) void assets.selectBuilding(assets.selectedBuilding.value.buildingId).catch(showError) }
function openSpaceCreate(parent: AssetSpaceView | null) { editingSpace.value = null; spaceParent.value = parent; spaceDrawerOpen.value = true }
function openSpaceEdit(space: AssetSpaceView) { editingSpace.value = space; spaceParent.value = null; spaceDrawerOpen.value = true }
function openSystemGroupCreate() { editingSystemGroup.value = null; systemGroupDrawerOpen.value = true }
function openSystemGroupEdit(group: AssetSystemGroupView) { editingSystemGroup.value = group; systemGroupDrawerOpen.value = true }

async function saveBuilding(request: AssetBuildingForm) {
  try { await assets.saveBuilding(editingBuilding.value?.buildingId ?? null, request); buildingDrawerOpen.value = false; message.success('建筑档案已保存') } catch (reason) { showError(reason) }
}
async function saveSpace(request: AssetSpaceForm) {
  try { await assets.saveSpace(editingSpace.value?.spaceId ?? null, request); spaceDrawerOpen.value = false; message.success('空间档案已保存') } catch (reason) { showError(reason) }
}
async function saveSystemGroup(request: AssetSystemGroupForm) {
  try { await assets.saveSystemGroup(editingSystemGroup.value?.systemGroupId ?? null, request); systemGroupDrawerOpen.value = false; message.success('系统分组已保存') } catch (reason) { showError(reason) }
}

async function confirmBuildingRemove(building: AssetBuildingView) {
  try {
    await assets.selectBuilding(building.buildingId)
    const references = assets.selectedBuilding.value?.references
    if (hasReferences(references)) { message.warning(`该建筑仍有 ${referenceText(references)}，不能删除。请先处理关联档案。`); return }
    Modal.confirm({ title: '删除该建筑？', content: '删除前已检查当前详情中的空间、系统、设备、测点和授权引用；后端会再次校验。', okText: '确认删除', cancelText: '取消', okType: 'danger', onOk: () => assets.removeBuilding(building.buildingId).then(() => message.success('建筑已删除')).catch(showError) })
  } catch (reason) { showError(reason) }
}
function confirmSpaceRemove(space: AssetSpaceView) { Modal.confirm({ title: '删除该空间？', content: '后端会拒绝仍被下级空间或设备引用的删除请求。', okText: '确认删除', cancelText: '取消', okType: 'danger', onOk: () => assets.removeSpace(space.spaceId, space.buildingId).then(() => message.success('空间已删除')).catch(showError) }) }
function confirmSystemGroupRemove(group: AssetSystemGroupView) { Modal.confirm({ title: '删除该系统分组？', content: '后端会拒绝仍被设备引用的删除请求。', okText: '确认删除', cancelText: '取消', okType: 'danger', onOk: () => assets.removeSystemGroup(group.systemGroupId, group.buildingId).then(() => message.success('系统分组已删除')).catch(showError) }) }
function hasReferences(references?: AssetReferenceSummary) { return Object.values(references ?? {}).some((value) => typeof value === 'number' && value > 0) }
function showError(reason: unknown) { explicitPageError.value = requestErrorMessage(reason) }

onMounted(() => { void assets.loadBuildings().catch(showError) })
</script>

<style scoped>
.asset-context-panel { min-height: 300px; }
.asset-context-heading, .asset-section-toolbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }
.asset-context-heading { margin-bottom: 18px; }
.asset-section-kicker { color: #72bbed; font-size: 11px; font-weight: 650; letter-spacing: .08em; }
.asset-context-heading h2 { margin: 4px 0 0; font-size: 20px; }
.asset-context-heading p, .asset-section-toolbar p { margin: 5px 0 0; color: #91a8bd; font-size: 12px; }
.asset-section-toolbar { align-items: center; margin: 4px 0 16px; }
.asset-reference-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; margin: 0 0 20px; }
.asset-reference-grid span { min-height: 62px; padding: 10px 12px; display: flex; flex-direction: column; justify-content: center; background: rgba(80, 150, 203, .07); border: 1px solid rgba(120, 169, 207, .12); border-radius: 10px; }
.asset-reference-grid small { color: #86a0b5; font-size: 11px; }.asset-reference-grid strong { margin-top: 2px; color: #ddecfa; font-size: 19px; }.asset-reference-compact { color: #9eb5c8; font-size: 12px; }
@media (max-width: 900px) { .asset-reference-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
