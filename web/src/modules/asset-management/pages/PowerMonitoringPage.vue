<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDrawer,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElPagination,
  ElSelect,
  ElSkeleton,
  ElTable,
  ElTableColumn,
  ElTag,
  Cpu,
  RefreshCw,
  Search,
} from '@/shared/ui'
import { t } from '@/locales'
import AssetStatusTag from '../components/AssetStatusTag.vue'
import MeterRealtimeBoard from '../components/meter/MeterRealtimeBoard.vue'
import { getMeterPhaseType, isMeterCoverageEquipment, isMeterEquipment } from '../components/meter/meter-display'
import { useAssetManagement } from '../composables/use-asset-management'
import { useMeterCoverage } from '../composables/use-meter-coverage'
import { flattenSpaces, type AssetEquipmentQuery } from '../models/assets'

type PhaseFilter = 'ALL' | '3P' | '1P'

const management = useAssetManagement()
const meterCoverage = useMeterCoverage()
const filterScope = useAssetManagement()
const router = useRouter()
const route = useRoute()

const filters = reactive<Partial<AssetEquipmentQuery>>({})
const phaseFilter = ref<PhaseFilter>('ALL')
const filterSpaces = computed(() => flattenSpaces(filterScope.scopeSpaces.value))
const meterDrawerOpen = ref(false)
const selectedEquipment = computed(() => management.selectedEquipment.value)
const meterCoverageById = computed(() => new Map(meterCoverage.listCoverages.value.map(item => [item.equipmentId, item])))

const allMeters = computed(() => management.equipment.value.items.filter(item => isMeterEquipment(item)))
const threePhaseCount = computed(() => allMeters.value.filter(item => getMeterPhaseType(item) === '3P').length)
const singlePhaseCount = computed(() => allMeters.value.filter(item => getMeterPhaseType(item) === '1P').length)
const coverageLinkCount = computed(() => meterCoverage.listCoverages.value.reduce((total, item) => total + item.targets.length, 0))

watch(
  [() => management.equipment.value.items, () => management.equipmentQuery.value.buildingId],
  ([items, buildingId]) => {
    const meterIds = items.filter(item => isMeterCoverageEquipment(item)).map(item => item.equipmentId)
    void meterCoverage.loadListCoverages(buildingId, meterIds).catch(() => undefined)
  },
  { immediate: true },
)

const filteredMeters = computed(() => {
  if (phaseFilter.value === 'ALL') return allMeters.value
  return allMeters.value.filter(item => getMeterPhaseType(item) === phaseFilter.value)
})

const phaseOptions = computed<Array<{ key: PhaseFilter; label: string; count: number }>>(() => [
  { key: 'ALL', label: t('assetManagement.powerMonitoring.phaseAll'), count: allMeters.value.length },
  { key: '3P', label: t('assetManagement.powerMonitoring.phase3P'), count: threePhaseCount.value },
  { key: '1P', label: t('assetManagement.powerMonitoring.phase1P'), count: singlePhaseCount.value },
])

async function query() {
  try {
    await management.setEquipmentQuery({
      buildingId: filters.buildingId || undefined,
      spaceId: filters.spaceId || undefined,
      systemGroupId: filters.systemGroupId || undefined,
      keyword: filters.keyword,
      size: 100,
    })
  } catch {
    // 错误状态保留在 management.equipmentError 中展示。
  }
}

async function buildingFilterChanged(buildingId: string | undefined) {
  filters.spaceId = undefined
  filters.systemGroupId = undefined
  try {
    await filterScope.loadScope(buildingId)
  } catch {
    // 空间与系统分组加载失败由 scopeError 独立呈现。
  }
}

async function resetFilters() {
  Object.assign(filters, { buildingId: undefined, spaceId: undefined, systemGroupId: undefined, keyword: undefined })
  phaseFilter.value = 'ALL'
  await filterScope.loadScope(undefined)
  await query()
}

async function changePage(page: number) {
  try {
    await management.setEquipmentQuery({ page, size: 100 }, false)
  } catch {
    // 翻页错误保留在 equipmentError。
  }
}

async function openMeterRealtime(equipmentId: string) {
  meterDrawerOpen.value = true
  try {
    await management.selectEquipment(equipmentId)
  } catch {
    meterDrawerOpen.value = false
  }
}

function goStaticArchive(item: Record<string, unknown>) {
  const queryParams: Record<string, string> = {}
  if (item.equipmentId) queryParams.equipmentId = String(item.equipmentId)
  if (item.buildingId) queryParams.buildingId = String(item.buildingId)
  void router?.push({ path: '/operations/devices/meters', query: queryParams })
}

onMounted(() => {
  const requestedBuilding = typeof route?.query?.buildingId === 'string' ? route.query.buildingId : undefined
  if (requestedBuilding) {
    filters.buildingId = requestedBuilding
  }
  void Promise.all([
    management.setEquipmentQuery({ buildingId: requestedBuilding, size: 100 }),
    management.ensureBuildingOptions(),
    requestedBuilding ? filterScope.loadScope(requestedBuilding) : Promise.resolve(),
  ]).then(() => {
    if (typeof route?.query?.equipmentId === 'string' && route.query.equipmentId) {
      void openMeterRealtime(route.query.equipmentId)
    }
  }).catch(() => undefined)
})
</script>

<template>
  <section class="power-monitoring-page">
    <header class="page-heading">
      <div>
        <h1>{{ t('assetManagement.powerMonitoring.title') }}</h1>
        <p>{{ t('assetManagement.powerMonitoring.description') }}</p>
      </div>
      <ElButton :icon="RefreshCw" :loading="management.equipmentLoading.value" @click="query">
        {{ t('assetManagement.associations.refresh') }}
      </ElButton>
    </header>

    <ElAlert v-if="management.equipmentError.value" :title="management.equipmentError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="management.buildingsError.value" :title="management.buildingsError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="filterScope.scopeError.value" :title="filterScope.scopeError.value.message" type="error" show-icon :closable="false" />
    <ElAlert v-if="meterCoverage.listError.value" :title="meterCoverage.listError.value.message" type="error" show-icon :closable="false" />

    <div class="summary-strip">
      <article class="summary-card">
        <span class="summary-label">{{ t('assetManagement.powerMonitoring.totalMeters') }}</span>
        <div class="summary-value-row">
          <strong class="summary-value">{{ allMeters.length }}</strong>
          <span class="summary-unit">{{ t('assetManagement.powerMonitoring.unitCount') }}</span>
        </div>
      </article>
      <article class="summary-card">
        <span class="summary-label">{{ t('assetManagement.powerMonitoring.threePhaseMeters') }}</span>
        <div class="summary-value-row">
          <strong class="summary-value tone-primary">{{ threePhaseCount }}</strong>
          <span class="summary-unit">{{ t('assetManagement.powerMonitoring.unitCount') }}</span>
        </div>
      </article>
      <article class="summary-card">
        <span class="summary-label">{{ t('assetManagement.powerMonitoring.singlePhaseMeters') }}</span>
        <div class="summary-value-row">
          <strong class="summary-value">{{ singlePhaseCount }}</strong>
          <span class="summary-unit">{{ t('assetManagement.powerMonitoring.unitCount') }}</span>
        </div>
      </article>
      <article class="summary-card">
        <span class="summary-label">{{ t('assetManagement.powerMonitoring.coverageLinks') }}</span>
        <div class="summary-value-row">
          <strong class="summary-value tone-success">{{ meterCoverage.listLoading.value ? t('assetManagement.meterCoverage.loading') : meterCoverage.listError.value ? t('common.missing') : coverageLinkCount }}</strong>
          <span class="summary-unit">{{ t('assetManagement.powerMonitoring.linkUnit') }}</span>
        </div>
      </article>
    </div>

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
          <ElFormItem :label="t('assetManagement.equipment.keyword')">
            <ElInput v-model="filters.keyword" clearable :placeholder="t('assetManagement.equipment.keywordPlaceholder')" />
          </ElFormItem>
        </div>
        <div class="filter-toolbar">
          <div class="phase-pills" role="group" :aria-label="t('assetManagement.powerMonitoring.phaseFilter')">
            <button
              v-for="opt in phaseOptions"
              :key="opt.key"
              type="button"
              class="phase-pill"
              :class="{ 'phase-pill-active': phaseFilter === opt.key }"
              @click="phaseFilter = opt.key"
            >
              <span>{{ opt.label }}</span>
              <span class="pill-count">{{ '（' }}{{ opt.count }}{{ '）' }}</span>
            </button>
          </div>
          <div class="filter-actions">
            <ElButton :icon="RefreshCw" @click="resetFilters">{{ t('assetManagement.equipment.reset') }}</ElButton>
            <ElButton type="primary" native-type="submit" :icon="Search" :loading="management.equipmentLoading.value">{{ t('assetManagement.equipment.query') }}</ElButton>
          </div>
        </div>
      </ElForm>
    </ElCard>

    <ElCard shadow="never" class="list-panel">
      <template #header>
        <div class="list-heading">
          <h2>{{ t('assetManagement.equipment.meterList') }}</h2>
          <span class="list-count">{{ t('assetManagement.equipment.meterCount', { total: filteredMeters.length }) }}</span>
        </div>
      </template>
      <ElSkeleton v-if="management.equipmentLoading.value && !allMeters.length" animated :rows="5" />
      <ElTable v-else v-loading="management.equipmentLoading.value" :data="filteredMeters" row-key="equipmentId" class="equipment-table">
        <ElTableColumn type="index" :label="t('assetManagement.equipment.index')" width="60" />
        <ElTableColumn :label="t('assetManagement.labels.equipmentName')" min-width="200">
          <template #default="{ row }">
            <div class="equipment-name">
              <span class="equipment-icon"><Cpu aria-hidden="true" /></span>
              <ElButton link class="name-link" @click="openMeterRealtime(row.equipmentId)">{{ row.equipmentName }}</ElButton>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn :label="t('assetManagement.labels.equipmentCode')" prop="equipmentCode" min-width="140" show-overflow-tooltip />
        <ElTableColumn :label="t('assetManagement.powerMonitoring.phaseFilter')" min-width="120">
          <template #default="{ row }">
            <ElTag :type="getMeterPhaseType(row) === '3P' ? 'primary' : 'info'">
              {{ getMeterPhaseType(row) === '3P' ? t('assetManagement.equipment.phaseTag3P') : t('assetManagement.equipment.phaseTag1P') }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn :label="t('assetManagement.equipment.location')" min-width="160">
          <template #default="{ row }">
            <div class="location-cell">
              <span>{{ row.spaceName || t('common.missing') }}</span>
              <span class="secondary">{{ row.buildingName || t('common.missing') }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn :label="t('assetManagement.equipment.meterCoverage')" min-width="150">
          <template #default="{ row }">{{ isMeterCoverageEquipment(row) ? (meterCoverage.listLoading.value ? t('assetManagement.meterCoverage.loading') : meterCoverage.listError.value ? t('common.missing') : meterCoverageById.get(row.equipmentId)?.scopeLabel || t('assetManagement.meterCoverage.unconfigured')) : t('common.missing') }}</template>
        </ElTableColumn>
        <ElTableColumn :label="t('assetManagement.equipment.meterTargetCount')" width="130">
          <template #default="{ row }">{{ isMeterCoverageEquipment(row) ? (meterCoverage.listLoading.value ? t('assetManagement.meterCoverage.loading') : meterCoverage.listError.value ? t('common.missing') : meterCoverageById.get(row.equipmentId)?.targets.length ?? 0) : t('common.missing') }}</template>
        </ElTableColumn>
        <ElTableColumn :label="t('assetManagement.equipment.meterInstallationLocation')" min-width="150">
          <template #default="{ row }">{{ isMeterCoverageEquipment(row) ? (meterCoverage.listLoading.value ? t('assetManagement.meterCoverage.loading') : meterCoverage.listError.value ? t('common.missing') : meterCoverageById.get(row.equipmentId)?.installationSpaceName || t('assetManagement.meterCoverage.toConfirm')) : t('common.missing') }}</template>
        </ElTableColumn>
        <ElTableColumn :label="t('assetManagement.equipment.system')" prop="systemGroupName" min-width="130" show-overflow-tooltip />
        <ElTableColumn :label="t('assetManagement.equipment.archiveStatus')" min-width="95">
          <template #default="{ row }"><AssetStatusTag :status="row.status" /></template>
        </ElTableColumn>
        <ElTableColumn :label="t('assetManagement.equipment.actions')" min-width="240" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <ElButton link type="primary" @click="openMeterRealtime(row.equipmentId)">
                {{ t('assetManagement.powerMonitoring.viewRealtimeAndTrend') }}
              </ElButton>
              <ElButton link type="primary" @click="goStaticArchive(row)">
                {{ t('assetManagement.powerMonitoring.viewStaticArchive') }}
              </ElButton>
            </div>
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty :description="t('assetManagement.powerMonitoring.emptyMeters')" /></template>
      </ElTable>
      <div class="pagination">
        <ElPagination
          background
          layout="total, prev, pager, next"
          :current-page="management.equipment.value.page"
          :page-size="management.equipment.value.size"
          :total="filteredMeters.length"
          @current-change="changePage"
        />
      </div>
    </ElCard>

    <ElDrawer
      :model-value="meterDrawerOpen"
      size="min(100%, var(--bec-dialog-width))"
      class="meter-realtime-drawer"
      :title="t('assetManagement.powerMonitoring.drawerTitle')"
      @update:model-value="meterDrawerOpen = false"
    >
      <template #header="{ titleId }">
        <div class="detail-heading">
          <template v-if="selectedEquipment && !management.equipmentContextLoading.value">
            <div class="detail-title">
              <h2 :id="titleId">{{ selectedEquipment.equipmentName }}</h2>
              <ElTag :type="getMeterPhaseType(selectedEquipment) === '3P' ? 'primary' : 'info'">
                {{ getMeterPhaseType(selectedEquipment) === '3P' ? t('assetManagement.equipment.phaseTag3P') : t('assetManagement.equipment.phaseTag1P') }}
              </ElTag>
              <ElButton type="primary" plain @click="goStaticArchive(selectedEquipment)">
                {{ t('assetManagement.powerMonitoring.viewStaticArchive') }}
              </ElButton>
            </div>
            <p>{{ t('assetManagement.powerMonitoring.archiveSpaceDetail', { code: selectedEquipment.equipmentCode || t('common.missing'), space: selectedEquipment.spaceName || t('common.missing') }) }}</p>
          </template>
          <h2 v-else :id="titleId">{{ t('assetManagement.powerMonitoring.drawerTitle') }}</h2>
        </div>
      </template>
      <div class="drawer-body-content">
        <ElSkeleton v-if="management.equipmentContextLoading.value" animated :rows="8" />
        <ElAlert v-else-if="management.equipmentContextError.value" :title="management.equipmentContextError.value.message" type="error" show-icon :closable="false" />
        <MeterRealtimeBoard v-else-if="selectedEquipment" :equipment="selectedEquipment" />
      </div>
    </ElDrawer>
  </section>
</template>

<style scoped>
.power-monitoring-page { display: grid; gap: var(--bec-space-section); min-width: 0; }
.page-heading { display: flex; align-items: flex-start; justify-content: space-between; flex-wrap: wrap; gap: var(--bec-space-group); }
h1, h2, p { margin: 0; }
h1 { font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); }
h2 { font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
p { color: var(--bec-color-text-secondary); max-width: var(--bec-text-measure); }

.summary-strip { display: grid; grid-template-columns: repeat(auto-fit, minmax(calc(var(--bec-ref-space-64) * 3), 1fr)); gap: var(--bec-space-group); }
.summary-card { display: grid; gap: var(--bec-space-tight); padding: var(--bec-space-group) var(--bec-space-section); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); box-shadow: var(--bec-shadow-card); }
.summary-label { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); font-weight: var(--bec-font-weight-heading); }
.summary-value-row { display: flex; align-items: baseline; gap: var(--bec-space-tight); }
.summary-value { font-family: var(--bec-font-family-number); font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); color: var(--bec-color-text-primary); }
.summary-unit { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.tone-primary { color: var(--bec-color-brand-primary); }
.tone-success { color: var(--bec-color-success); }

.filter-panel, .list-panel { border-radius: var(--bec-management-radius); }
.filter-panel :deep(.el-card__body) { padding: var(--bec-space-section); }
.filter-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 3)), 1fr)); gap: var(--bec-space-group); }
.filter-grid :deep(.el-form-item) { margin-bottom: 0; min-width: 0; }
.filter-grid :deep(.el-select) { width: 100%; }
.filter-toolbar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--bec-space-group); margin-top: var(--bec-space-section); }
.phase-pills { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-tight); }
.phase-pill { display: inline-flex; align-items: center; gap: var(--bec-ref-space-4); padding: var(--bec-ref-space-4) var(--bec-space-group); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-tag); background: var(--bec-color-surface-secondary); color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); font-weight: var(--bec-font-weight-heading); cursor: pointer; }
.phase-pill-active { background: var(--bec-color-action-primary); color: var(--bec-color-on-action); border-color: var(--bec-color-action-primary); }
.pill-count { opacity: 0.85; }
.filter-actions { display: flex; gap: var(--bec-space-tight); }
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
.location-cell { display: grid; gap: var(--bec-ref-space-4); }
.row-actions { display: flex; align-items: center; flex-wrap: wrap; gap: var(--bec-space-tight); }
.list-panel .pagination { display: flex; justify-content: flex-end; padding: var(--bec-space-group) var(--bec-space-section); overflow-x: auto; }

:deep(.meter-realtime-drawer .el-drawer__header) { padding: var(--bec-space-section); margin-bottom: 0; align-items: flex-start; }
:deep(.meter-realtime-drawer .el-drawer__body) { padding: 0; overflow: auto; }
.detail-heading { min-width: 0; }
.detail-title { display: flex; align-items: center; flex-wrap: wrap; gap: var(--bec-space-tight); }
.detail-heading p { margin-top: var(--bec-ref-space-4); }
.drawer-body-content { padding: var(--bec-space-section); }
</style>
