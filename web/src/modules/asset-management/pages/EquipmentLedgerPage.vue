<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElAlert, ElButton, ElDrawer, ElEmpty, ElForm, ElInput, ElMessage, ElOption, ElPagination,
  ElPopover, ElPopconfirm, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag, ElTabs, ElTabPane, ElTree,
  Cpu, Pencil, Plus, RefreshCw, Search, Trash2, Building2, LayoutGrid, Zap, Snowflake, Sun, Cloud, CircleHelp, WifiOff, Layers,
} from '@/shared/ui'
import { formatDateTime, formatNumber } from '@/shared/utils/format'
import { t } from '@/locales'
import EquipmentEditorDialog from '../components/EquipmentEditorDialog.vue'
import PointEditorDialog from '../components/PointEditorDialog.vue'
import EquipmentRuntimePassport from '../components/EquipmentRuntimePassport.vue'
import AssetStatusTag from '../components/AssetStatusTag.vue'
import MeterCoveragePanel from '../components/meter/MeterCoveragePanel.vue'
import { isHvacEquipment, isMeterEquipment, isMeterCoverageEquipment } from '../components/meter/meter-display'
import { useAssetManagement } from '../composables/use-asset-management'
import { useMeterCoverage } from '../composables/use-meter-coverage'
import { useLedgerScope } from '../composables/use-ledger-scope'
import { useLedgerRuntime } from '../composables/use-ledger-runtime'
import { canRunAssetAction, type AssetEquipmentDetail, type AssetEquipmentQuery, type AssetPoint, type AssetSpace } from '../models/assets'
import {
  filterLedgerSpaces,
  isLedgerCommunicationDelayed,
  ledgerCommunicationDisplayValue,
  ledgerDisplayPointValue,
  ledgerGroups,
  type AssetLedgerItem,
  type AssetLedgerPage,
  type LedgerGroup,
} from '../models/ledger'

defineProps<{ title?: string; panel?: boolean }>()
const route = useRoute()
const router = useRouter()
const management = useAssetManagement()
const filterScope = useAssetManagement()
const archiveScope = useAssetManagement()
const meterCoverage = useMeterCoverage()
const inventory = useLedgerScope()
const runtime = useLedgerRuntime()
const ledgerText = (key: string, params?: Record<string, string | number>) => t('assetManagement.ledger.' + key, params)
const filters = reactive<Partial<AssetEquipmentQuery>>({})
const spaceKeyword = ref('')
const activeGroup = ref<LedgerGroup>(route?.path?.endsWith('/meters') ? 'POWER' : 'ALL')
const currentPage = ref(1)
const pageSize = 20
const selectedId = ref('')
const selectedPoints = reactive(new Map<string, string>())
const passportOpen = ref(true)
const passportElement = ref<HTMLElement | null>(null)
const activeDetailTab = ref('archive')
const equipmentDrawerOpen = ref(false)
const equipmentEditorOpen = ref(false)
const pointEditorOpen = ref(false)
const editingEquipment = ref<AssetEquipmentDetail | null>(null)
const editingPoint = ref<AssetPoint | null>(null)
const selectedEquipment = computed(() => management.selectedEquipment.value?.equipmentId === selectedId.value ? management.selectedEquipment.value : null)
const selectedRow = computed(() => inventory.data.value?.items.find(item => item.equipment.equipmentId === selectedId.value) ?? null)
const selectedReadings = computed(() => runtime.readings.value)
const selectedPointId = computed(() => selectedPoints.get(selectedId.value) ?? '')
const equipmentSubmitting = computed(() => editingEquipment.value ? management.pending.value.has('equipment:update:' + editingEquipment.value.equipmentId) : management.pending.value.has('equipment:create'))
const pointSubmitting = computed(() => editingPoint.value ? management.pending.value.has('point:update:' + editingPoint.value.pointId) : false)
const filteredSpaces = computed(() => filterLedgerSpaces(filterScope.scopeSpaces.value, spaceKeyword.value))
const currentBuildingName = computed(() => management.buildingOptions.value.find(item => item.value === filters.buildingId)?.label ?? t('assetManagement.equipment.allBuildings'))
const groupIcons = { ALL: LayoutGrid, POWER: Zap, HVAC: Snowflake, RENEWABLE: Sun, WATER: Cloud, ENVIRONMENT: Cpu, UNCLASSIFIED: CircleHelp }
const ledgerItems = computed(() => inventory.data.value?.items ?? [])
let queryGeneration = 0
let buildingGeneration = 0
let disposed = false
onBeforeUnmount(() => { disposed = true; queryGeneration++; buildingGeneration++ })
function groupCount(group: LedgerGroup) {
  const count = inventory.data.value?.groupCounts[group]
  return typeof count === 'number' && Number.isFinite(count) ? String(count) : '—'
}
function asLedgerItem(value: unknown): AssetLedgerItem { return value as AssetLedgerItem }
function readingTime(value: number | null) { return value == null ? t('common.missing') : new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }).format(value) }
function displayPointStatus(item: AssetLedgerItem) { return ledgerText(`displayPoint.${item.displayPoint.status}`) }
function displayPointName(item: AssetLedgerItem) { return item.displayPoint.pointName || item.displayPoint.pointCode || '—' }
function displayPointValue(item: AssetLedgerItem) {
  const value = ledgerDisplayPointValue(item.displayPoint)
  if (value == null) return '—'
  return `${formatNumber(value)}${item.displayPoint.unit ? ` ${item.displayPoint.unit}` : ''}`
}
function displayPointEvidence(item: AssetLedgerItem) {
  const point = item.displayPoint
  if (point.status !== 'AVAILABLE') return displayPointStatus(item)
  const quality = point.dataQuality == null ? ledgerText('qualityUnknown') : `Q${point.dataQuality}`
  return `${quality} · ${readingTime(point.eventTime)}`
}
function displayPointTooltip(item: AssetLedgerItem) {
  return item.displayPoint.status === 'AVAILABLE' ? displayPointEvidence(item) : displayPointStatus(item)
}
function operationText(item: AssetLedgerItem) {
  const state = item.operation
  return ledgerText(`operation.${state.scope}.${state.value}`)
}
function communicationText(item: AssetLedgerItem) {
  const state = item.communication
  const displayValue = isLedgerCommunicationDelayed(state) ? 'ONLINE_DELAYED' : ledgerCommunicationDisplayValue(state)
  return ledgerText(`communicationState.${displayValue}`)
}
function summaryValue(key: keyof AssetLedgerPage['summary']) {
  const value = inventory.data.value?.summary[key]
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : '—'
}
function hasSummaryValue(key: keyof AssetLedgerPage['summary']) {
  const value = inventory.data.value?.summary[key]
  return typeof value === 'number' && Number.isFinite(value)
}
async function selectRow(row: AssetLedgerItem) {
  selectedId.value = row.equipment.equipmentId
  passportOpen.value = true
  const pointId = row.displayPoint.pointId
  if (pointId) selectedPoints.set(row.equipment.equipmentId, pointId)
  else selectedPoints.delete(row.equipment.equipmentId)
  runtime.clear()
  void runtime.loadReadings(row.equipment)
  try { await management.selectEquipment(row.equipment.equipmentId) } catch { /* 详情错误在右栏独立显示。 */ }
  if (selectedId.value === row.equipment.equipmentId && pointId) void runtime.loadTrend(row.equipment.equipmentId)
}
function selectPoint(pointId: string) {
  if (!selectedRow.value || !selectedReadings.value?.points.some(point => point.pointId === pointId)) return
  selectedPoints.set(selectedId.value, pointId)
  void runtime.loadTrend(selectedId.value)
}
function showReadings() { passportOpen.value = true; passportElement.value?.scrollIntoView({ block: 'nearest' }) }
async function reloadLedger(preferredId = selectedId.value, resetPage = true) {
  if (disposed) return
  const owner = ++queryGeneration
  if (resetPage) currentPage.value = 1
  selectedId.value = ''; selectedPoints.clear(); runtime.clear()
  await management.selectEquipment(null)
  if (disposed) return
  await inventory.load({
    page: currentPage.value,
    size: pageSize,
    buildingId: filters.buildingId || undefined,
    spaceId: filters.spaceId || undefined,
    systemGroupId: filters.systemGroupId || undefined,
    keyword: filters.keyword?.trim() || undefined,
    typeCode: filters.typeCode?.trim() || undefined,
    professionalGroup: activeGroup.value,
  })
  if (owner !== queryGeneration || inventory.error.value) return
  const row = ledgerItems.value.find(item => item.equipment.equipmentId === preferredId) ?? ledgerItems.value[0]
  if (row) await selectRow(row)
}
async function query() { await reloadLedger() }
async function changePage(page: number) { currentPage.value = page; await reloadLedger('', false) }
async function selectGroup(group: LedgerGroup) {
  if (activeGroup.value === group) return
  activeGroup.value = group
  await reloadLedger()
}
async function buildingFilterChanged(buildingId: string | undefined) {
  const owner = ++buildingGeneration
  filters.spaceId = undefined; filters.systemGroupId = undefined
  selectedId.value = ''; equipmentDrawerOpen.value = false; inventory.clear(); runtime.clear()
  await management.selectEquipment(null)
  await filterScope.loadScope(buildingId).catch(() => undefined)
  if (owner === buildingGeneration) await reloadLedger()
}
async function selectLedgerSpace(space: AssetSpace | null) { filters.spaceId = space?.spaceId; await reloadLedger() }
async function resetFilters() { filters.spaceId = undefined; filters.systemGroupId = undefined; filters.keyword = ''; filters.typeCode = undefined; spaceKeyword.value = ''; activeGroup.value = 'ALL'; await reloadLedger() }
watch(ledgerItems, items => { void meterCoverage.loadListCoverages(filters.buildingId, items.map(item => item.equipment).filter(isMeterEquipment).map(item => item.equipmentId)).catch(() => undefined) })
async function openEquipment(equipmentId: string, showPoints = false) {
  selectedId.value = equipmentId
  activeDetailTab.value = showPoints ? 'points' : 'archive'
  equipmentDrawerOpen.value = true
  try {
    await management.selectEquipment(equipmentId)
    if (selectedEquipment.value && isMeterEquipment(selectedEquipment.value)) {
      await archiveScope.loadScope(selectedEquipment.value.buildingId).catch(() => undefined)
    }
  } catch {
    equipmentDrawerOpen.value = false
  }
}

function goRealtimeMonitoring(item: Record<string, unknown>) {
  if (!isMeterEquipment(item) && !isHvacEquipment(item)) return
  const targetPath = isMeterEquipment(item) ? '/operations/realtime/power' : '/operations/realtime/hvac'
  const queryParams: Record<string, string> = {}
  if (item.equipmentId) queryParams.equipmentId = String(item.equipmentId)
  if (item.buildingId) queryParams.buildingId = String(item.buildingId)
  void router?.push({ path: targetPath, query: queryParams })
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
    await reloadLedger()
  } catch {
    // 请求错误已经转换为页面状态，避免将服务端异常原文展示给管理员。
  }
}

async function deleteEquipment(equipmentId: string) {
  try {
    await management.removeEquipment(equipmentId)
    equipmentDrawerOpen.value = false
    ElMessage.success(t('assetManagement.messages.deleted'))
    await reloadLedger()
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
    await reloadLedger()
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
    await reloadLedger()
  } catch {
    // 删除是否允许由后端引用校验决定。
  }
}

function can(value: unknown, action: string) {
  return canRunAssetAction(value as { allowedActions?: string[] }, action)
}

function asPoint(value: unknown): AssetPoint { return value as AssetPoint }

onMounted(async () => {
  try {
    await management.ensureBuildingOptions()
    if (disposed) return
    filters.buildingId = typeof route?.query?.buildingId === 'string' ? route.query.buildingId : management.buildingOptions.value[0]?.value
    await filterScope.loadScope(filters.buildingId).catch(() => undefined)
    const requestedEquipment = typeof route?.query?.equipmentId === 'string' ? route.query.equipmentId : ''
    await reloadLedger(requestedEquipment)
    if (requestedEquipment && ledgerItems.value.some(item => item.equipment.equipmentId === requestedEquipment)) await openEquipment(requestedEquipment)
  } catch { /* 建筑选项错误由页面现有状态呈现。 */ }
})
</script>
<template>
  <section class="equipment-page">
    <header class="page-heading">
      <div>
        <h1>
          <LayoutGrid aria-hidden="true" />{{ t('assetManagement.equipment.ledgerTitle') }}
        </h1>
        <p>{{ ledgerText('description') }}</p>
      </div>
      <div class="heading-actions">
        <ElButton :icon="RefreshCw" :loading="inventory.loading.value" @click="reloadLedger()">{{ ledgerText('refresh') }}</ElButton>
        <ElPopover trigger="click" :teleported="false" width="var(--bec-dialog-width)">
          <template #reference>
            <ElButton>{{ ledgerText('dataCaliber') }}</ElButton>
          </template>
          <p>{{ ledgerText('dataCaliberHint') }}</p>
          <p>{{ ledgerText('pointSelectionHint') }}</p>
          <p>{{ ledgerText('mappingHint') }}</p>
        </ElPopover>
        <ElButton :icon="Plus" @click="openCreateEquipment">{{ t('assetManagement.actions.createEquipment') }}</ElButton>
      </div>
    </header>
    <ElAlert v-if="management.buildingsError.value" :title="management.buildingsError.value.message" type="error" :closable="false" />
    <nav class="ledger-categories" :aria-label="t('assetManagement.equipment.filters')">
      <button v-for="group in ledgerGroups" :key="group" :data-group="group" type="button" :class="{ active: activeGroup === group }" :aria-pressed="activeGroup === group" @click="selectGroup(group)">
        <component :is="groupIcons[group]" aria-hidden="true" />{{ ledgerText('groups.' + group) }}<span>{{ '(' + groupCount(group) + ')' }}</span>
      </button>
    </nav>
    <section class="ledger-summary" :aria-label="ledgerText('dataCaliber')">
      <span>
        <LayoutGrid aria-hidden="true" />{{ ledgerText('assets') }}<strong>{{ summaryValue('assets') }}</strong>{{ ledgerText('unitAssets') }}</span>
      <span>
        <WifiOff aria-hidden="true" />{{ ledgerText('online') }}<strong>{{ summaryValue('online') }}</strong>{{ ledgerText('unitAssets') }}
        <small v-if="hasSummaryValue('delayed')">{{ ledgerText('onlineDelayed', { total: summaryValue('delayed') }) }}</small>
      </span>
      <span>
        <WifiOff aria-hidden="true" />{{ ledgerText('offline') }}<strong>{{ summaryValue('offline') }}</strong>{{ ledgerText('unitAssets') }}</span>
      <span>
        <WifiOff aria-hidden="true" />{{ ledgerText('communicationUnknown') }}<strong>{{ summaryValue('communicationUnknown') }}</strong>{{ ledgerText('unitAssets') }}</span>
      <span>
        <WifiOff aria-hidden="true" />{{ ledgerText('notMonitored') }}<strong>{{ summaryValue('notMonitored') }}</strong>{{ ledgerText('unitAssets') }}</span>
      <span>
        <Cpu aria-hidden="true" />{{ ledgerText('points') }}<strong>{{ summaryValue('points') }}</strong>{{ ledgerText('unitPoints') }}</span>
      <span v-if="inventory.data.value">
        <Layers aria-hidden="true" />{{ ledgerText('generatedAt') }}<strong>{{ formatDateTime(inventory.data.value.generatedAt) }}</strong>
      </span>
    </section>
    <div class="ledger-layout" :class="{ 'is-collapsed': !passportOpen }">
      <aside class="ledger-spaces" :aria-label="ledgerText('buildingSpaces')">
        <h2>
          <Building2 aria-hidden="true" />{{ ledgerText('buildingSpaces') }}
        </h2>
        <p>{{ currentBuildingName }}</p>
        <ElSelect v-model="filters.buildingId" clearable filterable :aria-label="t('assetManagement.labels.building')" :placeholder="t('assetManagement.equipment.allBuildings')" @change="buildingFilterChanged">
          <ElOption v-for="item in management.buildingOptions.value" :key="item.value" :label="item.label" :value="item.value" />
        </ElSelect>
        <label class="space-search-label">{{ ledgerText('spaceSearch') }}<ElInput v-model="spaceKeyword" clearable :placeholder="ledgerText('spaceSearchPlaceholder')" />
        </label>
        <ElButton class="all-spaces" :type="!filters.spaceId ? 'primary' : 'default'" plain @click="selectLedgerSpace(null)">{{ t('assetManagement.equipment.allSpaces') }}</ElButton>
        <div class="space-tree-region">
          <ElSkeleton v-if="filterScope.scopeLoading.value" :rows="4" animated />
          <ElAlert v-else-if="filterScope.scopeError.value" :title="filterScope.scopeError.value.message" type="error" :closable="false" />
          <ElTree v-else :data="filteredSpaces" node-key="spaceId" :props="{ label: 'spaceName', children: 'children' }" :current-node-key="filters.spaceId" :empty-text="ledgerText('noSpaceMatch')" highlight-current default-expand-all :expand-on-click-node="false" @node-click="selectLedgerSpace" />
        </div>
        <div class="system-filter">
          <label>{{ ledgerText('systemFilter') }}<ElSelect v-model="filters.systemGroupId" clearable :aria-label="ledgerText('systemFilter')" :placeholder="t('assetManagement.equipment.allSystems')" :disabled="!filters.buildingId || filterScope.scopeLoading.value" @change="query">
            <ElOption v-for="item in filterScope.scopeSystemGroups.value" :key="item.systemGroupId" :label="item.systemName" :value="item.systemGroupId" />
          </ElSelect>
          </label>
          <details>
            <summary>{{ ledgerText('advancedFilters') }}</summary>
            <ElInput v-model="filters.typeCode" clearable :aria-label="t('assetManagement.labels.equipmentType')" :placeholder="t('assetManagement.equipment.typePlaceholder')" @change="query" />
          </details>
          <p>{{ t('assetManagement.associations.filterHint') }}</p>
        </div>
      </aside>
      <section class="ledger-center">
        <header class="ledger-list-heading">
          <h2>
            <LayoutGrid aria-hidden="true" />{{ ledgerText('devicesTitle') }}<small>{{ ledgerText('count', { total: inventory.data.value?.total ?? '—' }) }}</small>
          </h2>
          <ElButton @click="resetFilters">{{ ledgerText('resetFilters') }}</ElButton>
        </header>
        <ElForm class="ledger-search" :aria-label="t('assetManagement.equipment.filters')" @submit.prevent="query">
          <label>{{ ledgerText('searchName') }}<ElInput v-model="filters.keyword" clearable :aria-label="ledgerText('searchName')" :placeholder="ledgerText('searchPlaceholder')" @clear="query" />
          </label>
          <ElButton native-type="submit" :icon="Search" :aria-label="t('assetManagement.equipment.query')" :loading="inventory.loading.value" />
        </ElForm>
        <div class="table-navigation">
          <p>{{ ledgerText('tableHint') }}</p>
          <ElButton text @click="showReadings">{{ ledgerText('viewReadings') }}{{ ' →' }}</ElButton>
        </div>
        <ElAlert v-if="inventory.error.value" :title="inventory.error.value" type="error" :closable="false">
          <ElButton text @click="query">{{ ledgerText('retry') }}</ElButton>
        </ElAlert>
        <div class="ledger-table-region">
          <ElTable class="equipment-table" :data="ledgerItems" row-key="equipment.equipmentId" :current-row-key="selectedId" highlight-current-row height="100%" @row-click="selectRow">
            <ElTableColumn :label="ledgerText('equipmentColumn')" min-width="160">
              <template #default="{ row }">
                <div class="ledger-name-cell">
                  <component :is="groupIcons[asLedgerItem(row).professionalGroup]" aria-hidden="true" />
                  <div>
                    <ElButton link class="name-link" @click.stop="selectRow(asLedgerItem(row))">{{ row.equipment.equipmentName }}</ElButton>
                    <small>{{ row.equipment.equipmentCode || t('common.missing') }}</small>
                  </div>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="ledgerText('spaceColumn')" min-width="145">
              <template #default="{ row }">
                <div class="ledger-stacked">
                  <span>{{ row.equipment.spaceName || t('common.missing') }}</span>
                  <small>{{ row.equipment.typeCode || t('common.missing') }}</small>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="ledgerText('runtimeColumn')" min-width="125">
              <template #default="{ row }">
                <div class="ledger-stacked ledger-state">
                  <span v-if="asLedgerItem(row).operation.applicable">{{ operationText(asLedgerItem(row)) }}</span>
                  <span>{{ ledgerText('communication') }}{{ '：' }}{{ communicationText(asLedgerItem(row)) }}</span>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="ledgerText('mainPointColumn')" min-width="195">
              <template #default="{ row }">
                <div class="ledger-stacked">
                  <span>{{ displayPointName(asLedgerItem(row)) }}</span>
                  <strong>{{ displayPointValue(asLedgerItem(row)) }}</strong>
                  <small :title="displayPointTooltip(asLedgerItem(row))">{{ displayPointEvidence(asLedgerItem(row)) }}</small>
                </div>
              </template>
            </ElTableColumn>
            <template #empty>
              <ElSkeleton v-if="inventory.loading.value" :rows="6" animated />
              <ElEmpty v-else :description="ledgerText('inventoryEmpty')" />
            </template>
          </ElTable>
        </div>
        <footer class="ledger-list-footer">
          <p>{{ ledgerText('selectionFooter') }}</p>
          <ElPagination v-if="(inventory.data.value?.total ?? 0) > pageSize" small layout="total, prev, pager, next" :current-page="currentPage" :page-size="pageSize" :total="inventory.data.value?.total ?? 0" @current-change="changePage" />
        </footer>
      </section>
      <aside v-if="passportOpen" ref="passportElement" class="ledger-passport">
        <EquipmentRuntimePassport :equipment="selectedEquipment" :ledger-item="selectedRow" :loading="management.equipmentContextLoading.value" :error="management.equipmentContextError.value?.message" :readings="selectedReadings" :readings-loading="runtime.readingsLoading.value" :readings-error="runtime.readingsError.value" :trend="runtime.trend.value" :trend-loading="runtime.trendLoading.value" :trend-error="runtime.trendError.value" :selected-point-id="selectedPointId" @select-point="selectPoint" @archive="openEquipment(selectedId)" @collapse="passportOpen = false" />
      </aside>
    </div>
    <ElDrawer :model-value="equipmentDrawerOpen" size="min(100%, var(--bec-dialog-width))" class="equipment-detail-drawer" :title="t('assetManagement.equipment.detail')" @update:model-value="equipmentDrawerOpen = false">
      <template #header="{ titleId }">
        <div class="detail-heading">
          <template v-if="selectedEquipment && !management.equipmentContextLoading.value">
            <div class="detail-title">
              <h2 :id="titleId">{{ selectedEquipment.equipmentName }}</h2>
              <AssetStatusTag :status="selectedEquipment.status" />
              <ElButton v-if="isMeterEquipment(selectedEquipment) || isHvacEquipment(selectedEquipment)" type="primary" plain @click="goRealtimeMonitoring(selectedEquipment)">
                {{ isMeterEquipment(selectedEquipment) ? t('assetManagement.equipment.goPowerMonitoring') : t('assetManagement.equipment.goHvacMonitoring') }}
              </ElButton>
            </div>
            <p>{{ selectedEquipment.equipmentCode || t('common.missing') }}</p>
          </template>
          <h2 v-else :id="titleId">{{ t('assetManagement.equipment.detail') }}</h2>
        </div>
      </template>
      <ElSkeleton v-if="management.equipmentContextLoading.value" animated :rows="8" />
      <ElAlert v-else-if="management.equipmentContextError.value" :title="management.equipmentContextError.value.message" type="error" show-icon :closable="false" />
      <ElTabs v-else-if="selectedEquipment" v-model="activeDetailTab" class="detail-tabs">
        <ElTabPane name="archive" :label="t('assetManagement.equipment.archiveTab')">
          <div class="detail-content">
            <section class="detail-section">
              <h3>{{ t('assetManagement.equipment.basicInformation') }}</h3>
              <dl class="detail-fields">
                <div>
                  <dt>{{ t('assetManagement.labels.equipmentName') }}</dt>
                  <dd>{{ selectedEquipment.equipmentName || t('common.missing') }}</dd>
                </div>
                <div>
                  <dt>{{ t('assetManagement.labels.equipmentCode') }}</dt>
                  <dd>{{ selectedEquipment.equipmentCode || t('common.missing') }}</dd>
                </div>
                <div>
                  <dt>{{ t('assetManagement.labels.equipmentType') }}</dt>
                  <dd>{{ selectedEquipment.typeCode || t('common.missing') }}</dd>
                </div>
                <div>
                  <dt>{{ t('assetManagement.labels.productName') }}</dt>
                  <dd>{{ selectedEquipment.productName || t('common.missing') }}</dd>
                </div>
                <div>
                  <dt>{{ t('assetManagement.labels.manufacturer') }}</dt>
                  <dd>{{ selectedEquipment.manufacturer || t('common.missing') }}</dd>
                </div>
              </dl>
            </section>
            <section class="detail-section">
              <h3>{{ t(isMeterEquipment(selectedEquipment) ? 'assetManagement.equipment.archiveSpaceInformation' : 'assetManagement.equipment.installationInformation') }}</h3>
              <dl class="detail-fields">
                <div>
                  <dt>{{ t('assetManagement.labels.building') }}</dt>
                  <dd>{{ selectedEquipment.buildingName || t('common.missing') }}</dd>
                </div>
                <div v-if="!isMeterEquipment(selectedEquipment)">
                  <dt>{{ t('assetManagement.labels.space') }}</dt>
                  <dd>{{ selectedEquipment.spaceName || t('common.missing') }}</dd>
                </div>
                <div>
                  <dt>{{ t('assetManagement.labels.system') }}</dt>
                  <dd>{{ selectedEquipment.systemGroupName || t('common.missing') }}</dd>
                </div>
              </dl>
            </section>
          </div>
        </ElTabPane>
        <ElTabPane name="points" :label="t('assetManagement.equipment.points')">
          <div class="detail-content">
            <div v-if="isMeterEquipment(selectedEquipment)" class="static-points-banner">
              <p>{{ t('assetManagement.equipment.staticPointsNote') }}</p>
              <ElButton v-if="isMeterEquipment(selectedEquipment) || isHvacEquipment(selectedEquipment)" type="primary" plain @click="goRealtimeMonitoring(selectedEquipment)">
                {{ t('assetManagement.equipment.goPowerMonitoring') }}
              </ElButton>
            </div>
            <section class="drawer-section">
              <h3>{{ t('assetManagement.equipment.points') }}</h3>
              <ElTable :data="management.points.value" row-key="pointId">
                <ElTableColumn :label="t('assetManagement.labels.pointName')" prop="pointName" min-width="160" />
                <ElTableColumn :label="t('assetManagement.labels.pointCode')" prop="pointCode" min-width="160" />
                <ElTableColumn :label="t('assetManagement.labels.unit')" prop="unit" min-width="90" />
                <ElTableColumn :label="t('assetManagement.labels.required')" min-width="90">
                  <template #default="{ row }">{{ t(row.required ? 'assetManagement.equipment.yes' : 'assetManagement.equipment.no') }}</template>
                </ElTableColumn>
                <ElTableColumn :label="t('assetManagement.labels.calculation')" min-width="110">
                  <template #default="{ row }">
                    <ElTag :type="row.forCalculation ? 'success' : 'info'">{{ row.forCalculation ? t('assetManagement.labels.calculation') : t('common.missing') }}</ElTag>
                  </template>
                </ElTableColumn>
                <ElTableColumn :label="t('assetManagement.actions.viewDetail')" min-width="190">
                  <template #default="{ row }">
                    <div class="row-actions">
                      <ElButton v-if="can(row, 'UPDATE')" :icon="Pencil" link @click="openEditPoint(asPoint(row))">{{ t('assetManagement.actions.editPoint') }}</ElButton>
                      <ElPopconfirm v-if="can(row, 'DELETE')" :title="t('assetManagement.messages.deleteConfirm')" :confirm-button-text="t('assetManagement.actions.confirmDelete')" :cancel-button-text="t('assetManagement.actions.cancel')" @confirm="deletePoint(asPoint(row))">
                        <template #reference>
                          <ElButton :icon="Trash2" link type="danger">{{ t('assetManagement.actions.deletePoint') }}</ElButton>
                        </template>
                      </ElPopconfirm>
                    </div>
                  </template>
                </ElTableColumn>
                <template #empty>
                  <ElEmpty :description="t('assetManagement.empty.points')" />
                </template>
              </ElTable>
            </section>
          </div>
        </ElTabPane>
        <ElTabPane v-if="isMeterCoverageEquipment(selectedEquipment)" name="meter-coverage" :label="t('assetManagement.equipment.meterCoverageTab')">
          <div class="detail-content">
            <MeterCoveragePanel :equipment-id="selectedEquipment.equipmentId" :spaces="archiveScope.scopeSpaces.value" @saved="meterCoverage.loadListCoverages(filters.buildingId, ledgerItems.map(item => item.equipment).filter(isMeterEquipment).map(item => item.equipmentId)).catch(() => undefined)" />
          </div>
        </ElTabPane>
        <ElTabPane name="connection" :label="t('assetManagement.equipment.connectionTab')">
          <div class="detail-content">
            <ElButton v-if="selectedEquipment.identities.some(identity => identity.identityType === 'DAIKIN_UNIT')" @click="goRealtimeMonitoring(selectedEquipment)">{{ t('dashboard.daikin.detail') }}</ElButton>
            <section class="detail-section">
              <h3>{{ t('assetManagement.equipment.protocolInformation') }}</h3>
              <dl class="detail-fields">
                <div>
                  <dt>{{ t('assetManagement.labels.expectedProfile') }}</dt>
                  <dd>{{ selectedEquipment.expectedProfileCode || t('common.missing') }}</dd>
                </div>
              </dl>
            </section>
            <section class="drawer-section">
              <h3>{{ t('assetManagement.equipment.identities') }}</h3>
              <ElTable :data="selectedEquipment.identities" row-key="identityId">
                <ElTableColumn :label="t('assetManagement.labels.identityType')" prop="identityType" min-width="130" />
                <ElTableColumn :label="t('assetManagement.labels.identity')" prop="identityValue" min-width="180" />
                <ElTableColumn :label="t('assetManagement.labels.expectedProfile')" prop="expectedProfileCode" min-width="140" />
                <ElTableColumn :label="t('assetManagement.labels.status')" min-width="100">
                  <template #default="{ row }">
                    <AssetStatusTag :status="row.status" />
                  </template>
                </ElTableColumn>
                <template #empty>
                  <ElEmpty :description="t('assetManagement.empty.identities')" />
                </template>
              </ElTable>
            </section>
          </div>
        </ElTabPane>
        <ElTabPane name="parameters" :label="t('assetManagement.equipment.technicalParameters')">
          <div class="detail-content">
            <section class="detail-section">
              <h3>{{ t('assetManagement.equipment.technicalParameters') }}</h3>
              <dl class="detail-fields">
                <div v-if="isHvacEquipment(selectedEquipment)">
                  <dt>{{ t('assetManagement.labels.ratedCapacity') }}</dt>
                  <dd>{{ formatNumber(selectedEquipment.ratedCapacity) }}</dd>
                </div>
                <div>
                  <dt>{{ t('assetManagement.labels.ratedPower') }}</dt>
                  <dd>{{ formatNumber(selectedEquipment.ratedPower) }}</dd>
                </div>
                <div v-if="isHvacEquipment(selectedEquipment)">
                  <dt>{{ t('assetManagement.labels.designCop') }}</dt>
                  <dd>{{ formatNumber(selectedEquipment.designCop) }}</dd>
                </div>
              </dl>
            </section>
            <p v-if="isMeterEquipment(selectedEquipment)" class="parameter-note">{{ t('assetManagement.equipment.meterParametersNote') }}</p>
            <p class="parameter-note">{{ t('assetManagement.forms.parametersReadOnly') }}</p>
          </div>
        </ElTabPane>
        <ElTabPane name="relations" :label="t('assetManagement.equipment.relationsTab')">
          <div class="detail-pending">{{ t('assetManagement.equipment.pending') }}</div>
        </ElTabPane>
        <ElTabPane name="maintenance" :label="t('assetManagement.equipment.maintenanceTab')">
          <div class="detail-pending">{{ t('assetManagement.equipment.pending') }}</div>
        </ElTabPane>
      </ElTabs>
      <template v-if="selectedEquipment && !management.equipmentContextLoading.value && !management.equipmentContextError.value" #footer>
        <div class="drawer-actions">
          <ElButton v-if="can(selectedEquipment, 'UPDATE')" :icon="Pencil" @click="openEditEquipment(selectedEquipment.equipmentId)">{{ t('assetManagement.actions.editEquipment') }}</ElButton>
          <ElPopconfirm v-if="can(selectedEquipment, 'DELETE')" :title="t('assetManagement.messages.deleteConfirm')" :confirm-button-text="t('assetManagement.actions.confirmDelete')" :cancel-button-text="t('assetManagement.actions.cancel')" @confirm="deleteEquipment(selectedEquipment.equipmentId)">
            <template #reference>
              <ElButton :icon="Trash2" type="danger" plain>{{ t('assetManagement.actions.deleteEquipment') }}</ElButton>
            </template>
          </ElPopconfirm>
        </div>
      </template>
    </ElDrawer>

    <EquipmentEditorDialog :open="equipmentEditorOpen" :equipment="editingEquipment" :buildings="management.buildingOptions.value" :spaces="management.scopeSpaces.value" :system-groups="management.scopeSystemGroups.value" :submitting="equipmentSubmitting" @close="equipmentEditorOpen = false" @building-change="management.loadScope" @save="saveEquipment" />
    <PointEditorDialog :open="pointEditorOpen" :point="editingPoint" :submitting="pointSubmitting" @close="pointEditorOpen = false" @save="savePoint" />
  </section>
</template>
<style scoped>
.equipment-page { --bec-ledger-left: calc(var(--bec-ref-space-64) * 2 + var(--bec-ref-space-48) + var(--bec-ref-space-4)); --bec-ledger-right: calc(var(--bec-ref-space-64) * 5 + var(--bec-ref-space-32)); height: 100%; min-height: 0; display: flex; flex-direction: column; gap: var(--bec-space-group); }
.page-heading { display: flex; align-items: start; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; flex-shrink: 0; }
h1,h2,h3,p { margin: 0; }
h1 { font-size: var(--bec-ref-font-24); font-weight: var(--bec-font-weight-heading); }
h2 { font-size: var(--bec-ref-font-14); font-weight: var(--bec-font-weight-heading); }
h3 { font-size: var(--bec-ref-font-14); }
h1,h2 { display: flex; align-items: center; gap: var(--bec-space-tight); }
h1 svg,h2 svg { width: var(--bec-icon-small); height: var(--bec-icon-small); color: var(--bec-color-action-primary); flex-shrink: 0; }
.page-heading p { margin-top: var(--bec-ref-space-4); font-size: var(--bec-ref-font-12); color: var(--bec-color-text-secondary); }
.heading-actions { display: flex; flex-wrap: wrap; gap: var(--bec-space-tight); }
.ledger-categories { display: flex; flex-wrap: wrap; gap: var(--bec-space-tight); flex-shrink: 0; }
.ledger-categories button { display: inline-flex; align-items: center; gap: var(--bec-ref-space-4); padding: var(--bec-space-tight) var(--bec-space-group); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-control); background: var(--bec-color-surface); color: var(--bec-color-text-secondary); cursor: pointer; font-size: var(--bec-ref-font-12); }
.ledger-categories button svg { width: var(--bec-icon-small); height: var(--bec-icon-small); }
.ledger-categories button[data-group='POWER'] svg { color: var(--bec-color-warning); }
.ledger-categories button[data-group='HVAC'] svg { color: var(--bec-color-action-primary); }
.ledger-categories button[data-group='RENEWABLE'] svg { color: var(--bec-color-success); }
.ledger-categories button[data-group='WATER'] svg { color: var(--bec-color-action-primary); }
.ledger-categories button.active { color: var(--bec-color-action-primary); border-color: var(--bec-color-action-primary); background: var(--bec-color-action-soft); }
.ledger-summary { display: flex; align-items: center; flex-wrap: wrap; gap: var(--bec-space-section); padding: var(--bec-space-group); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-control); flex-shrink: 0; font-size: var(--bec-ref-font-12); color: var(--bec-color-text-secondary); }
.ledger-summary span { display: inline-flex; gap: var(--bec-ref-space-4); align-items: center; }
.ledger-summary svg { width: var(--bec-icon-small); height: var(--bec-icon-small); }
.ledger-summary strong { color: var(--bec-color-text-primary); }
.ledger-layout { flex: 1; min-height: 0; display: grid; grid-template-columns: var(--bec-ledger-left) minmax(0,1fr) var(--bec-ledger-right); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-control); overflow: hidden; }
.ledger-layout.is-collapsed { grid-template-columns: var(--bec-ledger-left) minmax(0,1fr); }
.ledger-spaces { background: var(--bec-color-surface-secondary); padding: var(--bec-space-group); display: flex; flex-direction: column; gap: var(--bec-space-group); min-width: 0; overflow: auto; border-right: var(--bec-border-width) solid var(--bec-color-border); font-size: var(--bec-ref-font-12); }
.ledger-spaces :deep(.el-select) { width: 100%; }
.space-search-label { display: grid; gap: var(--bec-ref-space-4); color: var(--bec-color-text-secondary); }
.all-spaces { width: 100%; margin: 0; justify-content: flex-start; }
.space-tree-region { flex: 1; min-height: var(--bec-ref-space-64); }
.space-tree-region :deep(.el-tree) { background: transparent; font-size: var(--bec-ref-font-12); }
.space-tree-region :deep(.el-tree-node__content) { min-height: var(--bec-ref-space-40); }
.system-filter { display: grid; gap: var(--bec-space-group); border-top: var(--bec-border-width) solid var(--bec-color-divider); padding-top: var(--bec-space-group); color: var(--bec-color-text-secondary); }
.system-filter label { display: grid; gap: var(--bec-space-tight); }
.system-filter details summary { cursor: pointer; margin-bottom: var(--bec-space-tight); }
.system-filter p { font-size: var(--bec-ref-font-12); line-height: var(--bec-line-height); }
.ledger-center { display: flex; flex-direction: column; gap: var(--bec-space-tight); min-width: 0; min-height: 0; padding: var(--bec-space-group); }
.ledger-list-heading,.table-navigation { display: flex; justify-content: space-between; align-items: center; gap: var(--bec-space-tight); flex-shrink: 0; }
.ledger-list-heading small { font-size: var(--bec-ref-font-12); color: var(--bec-color-text-secondary); font-weight: var(--bec-font-weight-normal); }
.ledger-search { display: flex; align-items: end; gap: var(--bec-space-tight); padding-top: var(--bec-space-tight); }
.ledger-search label { flex: 1; min-width: 0; display: grid; gap: var(--bec-space-tight); font-size: var(--bec-ref-font-12); color: var(--bec-color-text-secondary); }
.table-navigation p,.ledger-list-footer p { font-size: var(--bec-ref-font-12); color: var(--bec-color-text-secondary); }
.ledger-table-region { flex: 1; min-height: 0; }
.equipment-table { font-size: var(--bec-ref-font-12); }
.equipment-table :deep(th.el-table__cell) { background: var(--bec-color-surface-secondary); font-weight: var(--bec-font-weight-normal); }
.equipment-table :deep(td.el-table__cell) { padding-block: var(--bec-space-group); vertical-align: top; }
.equipment-table :deep(.cell) { padding-inline: var(--bec-space-tight); }
.ledger-name-cell { display: flex; align-items: start; gap: var(--bec-space-tight); }
.ledger-name-cell > svg { width: var(--bec-icon-small); height: var(--bec-icon-small); flex-shrink: 0; color: var(--bec-color-action-primary); margin-top: var(--bec-ref-space-4); }
.ledger-name-cell > div,.ledger-stacked { display: grid; gap: var(--bec-ref-space-4); }
.name-link { white-space: normal; height: auto; padding: 0; justify-content: flex-start; text-align: left; font-size: var(--bec-ref-font-12); }
.ledger-name-cell small,.ledger-stacked small { font-size: var(--bec-ref-font-12); color: var(--bec-color-text-secondary); }
.ledger-state { color: var(--bec-color-text-secondary); }
.ledger-list-footer { display: grid; gap: var(--bec-space-tight); border-top: var(--bec-border-width) solid var(--bec-color-divider); padding-top: var(--bec-space-tight); }
.ledger-passport { border-left: var(--bec-border-width) solid var(--bec-color-border); min-width: 0; min-height: 0; overflow: hidden; }
.drawer-actions { display: flex; flex-wrap: wrap; gap: var(--bec-space-tight); }
.row-actions { display: flex; gap: var(--bec-space-tight); }
.detail-heading p,.secondary,.parameter-note { color: var(--bec-color-text-secondary); font-size: var(--bec-ref-font-12); }
:deep(.equipment-detail-drawer .el-drawer__header) { padding: var(--bec-space-section); margin: 0; align-items: start; }
:deep(.equipment-detail-drawer .el-drawer__body) { display: flex; flex-direction: column; padding: 0; min-height: 0; overflow: hidden; }
:deep(.equipment-detail-drawer .el-drawer__footer) { padding: var(--bec-space-group) var(--bec-space-section); border-top: var(--bec-border-width) solid var(--bec-color-divider); }
.detail-title { display: flex; align-items: center; flex-wrap: wrap; gap: var(--bec-space-tight); }
.detail-title h2 { font-size: var(--bec-ref-font-18); }
.detail-tabs { display: flex; flex-direction: column; flex: 1; min-height: 0; }
.detail-tabs :deep(.el-tabs__header) { margin: 0; padding-inline: var(--bec-space-section); flex-shrink: 0; }
.detail-tabs :deep(.el-tabs__content) { flex: 1; min-height: 0; overflow: hidden; }
.detail-tabs :deep(.el-tab-pane) { height: 100%; overflow: auto; }
.detail-content { padding: var(--bec-space-section); display: grid; gap: var(--bec-space-section); }
.detail-fields { display: grid; gap: var(--bec-space-group); margin: var(--bec-space-group) 0 0; }
.detail-fields > div { display: grid; grid-template-columns: minmax(0,1fr) minmax(0,2fr); gap: var(--bec-space-group); }
.detail-fields dt { color: var(--bec-color-text-secondary); }
.detail-fields dd { margin: 0; overflow-wrap: anywhere; }
.drawer-section,.detail-section { min-width: 0; }
.detail-pending { padding: var(--bec-space-section); color: var(--bec-color-text-secondary); }
@media (max-width: 1200px) { .equipment-page { height: auto; } .ledger-layout { min-height: calc(var(--bec-ref-space-64) * 10); } .ledger-passport { overflow: auto; } }
@media (max-width: 900px) { .ledger-layout,.ledger-layout.is-collapsed { display: flex; flex-direction: column; overflow: visible; } .ledger-spaces { max-height: calc(var(--bec-ref-space-64) * 5); } .ledger-center { min-height: calc(var(--bec-ref-space-64) * 8); } .ledger-passport { border-left: 0; border-top: var(--bec-border-width) solid var(--bec-color-border); min-height: calc(var(--bec-ref-space-64) * 8); } .heading-actions { width: 100%; } }
</style>
