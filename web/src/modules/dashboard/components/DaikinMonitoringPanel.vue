<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  ElAlert, ElButton, ElEmpty, ElInput, ElOption, ElPagination,
  ElRadioButton, ElRadioGroup, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag,
} from '@/shared/ui'
import { t } from '@/locales'
import { formatDateTime } from '@/shared/utils/format'
import { listAccessibleBuildings } from '../api/hvac'
import { daikinApi } from '../api/daikin'
import {
  daikinLabel, daikinQuickStatusCounts, filterDaikinDevicesByQuickStatus,
  groupDaikinDevicesBySpace, groupDaikinDevicesBySystem, isDaikinDeviceRunning,
  type DaikinQuickStatus,
} from '../models/daikin-display'
import type { DaikinDevice } from '../models/daikin'
import { useDaikinResource } from '../composables/use-daikin-resource'
import DaikinDeviceDetail from './DaikinDeviceDetail.vue'

const props = withDefaults(defineProps<{ alarms?: boolean; history?: boolean }>(), { alarms: false, history: false })
const route = useRoute()
const text = (key: string) => t(`dashboard.daikin.${key}`)
const buildings = useDaikinResource<Awaited<ReturnType<typeof listAccessibleBuildings>>>()
const devices = useDaikinResource<Awaited<ReturnType<typeof daikinApi.devices>>>()
const exceptions = useDaikinResource<Awaited<ReturnType<typeof daikinApi.exceptions>>>()
const spaces = useDaikinResource<Awaited<ReturnType<typeof daikinApi.spaces>>>()

const building = ref('')
const kind = ref('')
const space = ref('')
const state = ref('')
const exceptionFilter = ref('')
const quickStatus = ref<DaikinQuickStatus>('ALL')
const groupMode = ref<'SYSTEM' | 'SPACE'>('SYSTEM')
const viewMode = ref<'CARD' | 'TABLE'>('CARD')
const collapsedGroups = ref<Record<string, boolean>>({})
const searchInput = ref('')
const keyword = ref('')
const page = ref(1)
const selected = ref<string | null>(null)
const refreshTick = ref(0)

const rawDevices = computed(() => devices.data.value?.items ?? [])
const statusCounts = computed(() => daikinQuickStatusCounts(rawDevices.value))
const filteredDevices = computed(() => filterDaikinDevicesByQuickStatus(rawDevices.value, quickStatus.value))
const systemGroups = computed(() => groupDaikinDevicesBySystem(filteredDevices.value))
const spaceGroups = computed(() => groupDaikinDevicesBySpace(filteredDevices.value, spaces.data.value ?? []))
const selectedDevice = computed(() => rawDevices.value.find(item => item.equipmentId === selected.value))

function spaceNameOf(spaceId: string | null | undefined): string {
  if (!spaceId) return text('unassignedSpace')
  return spaces.data.value?.find(item => item.spaceId === spaceId)?.spaceName ?? text('unassignedSpace')
}

function toggleGroup(groupKey: string) {
  collapsedGroups.value[groupKey] = !collapsedGroups.value[groupKey]
}

function deviceHealthLabel(row: DaikinDevice): string {
  if (!row.active) return text('inactive')
  if (row.lastValidAt == null) return text('noObservation')
  if (row.stale) return text('stale')
  return text('onlineNormal')
}

function search() {
  keyword.value = searchInput.value.trim()
  selected.value = null
  if (page.value === 1) load()
  else page.value = 1
}

let timer: ReturnType<typeof setInterval> | undefined
let disposed = false

function load(cursor?: string) {
  if (!building.value) return
  if (props.alarms) void exceptions.run(() => daikinApi.exceptions(building.value, props.history, cursor))
  else void devices.run(() => daikinApi.devices(building.value, page.value, kind.value, keyword.value, space.value, state.value, exceptionFilter.value === '' ? undefined : exceptionFilter.value === 'yes'))
}

function refresh() {
  load()
  refreshTick.value++
}

watch(building, () => {
  space.value = ''
  spaces.clear()
  if (!props.alarms && building.value) void spaces.run(() => daikinApi.spaces(building.value))
  selected.value = null
  page.value = 1
  quickStatus.value = 'ALL'
  devices.clear()
  exceptions.clear()
  load()
})
watch([kind, space, state, exceptionFilter], () => {
  page.value = 1
  selected.value = null
  load()
})
watch(page, () => {
  selected.value = null
  load()
})

onMounted(async () => {
  await buildings.run(listAccessibleBuildings)
  if (disposed) return
  const requestedBuilding = typeof route.query.buildingId === 'string' ? route.query.buildingId : ''
  building.value = buildings.data.value?.find(item => item.buildingId === requestedBuilding)?.buildingId ?? buildings.data.value?.[0]?.buildingId ?? ''
  // 深链接只选已授权建筑；设备接口仍独立校验真实归属和菜单。
  if (!props.alarms && building.value === requestedBuilding && typeof route.query.equipmentId === 'string') {
    await Promise.resolve()
    if (!disposed) selected.value = route.query.equipmentId
  }
  // 后台采集独立运行；浏览器只在可见且没有未完成请求时每分钟重读当前页。
  timer = setInterval(() => {
    if (!document.hidden && !devices.loading.value && !exceptions.loading.value && !props.history) refresh()
  }, 60000)
})
onUnmounted(() => {
  disposed = true
  clearInterval(timer)
})
</script>

<template>
  <section class="daikin-panel">
    <header class="panel-header">
      <div class="title-block">
        <h1>{{ text(alarms ? (history ? 'historyAlarms' : 'currentAlarms') : 'title') }}</h1>
        <p>{{ text(alarms ? 'alarmNotice' : 'notice') }}</p>
      </div>
      <div class="filters">
        <label class="filter-field">
          <span>{{ text('building') }}</span>
          <ElSelect v-model="building" filterable :placeholder="text('building')" :aria-label="text('building')">
            <ElOption v-for="item in buildings.data.value ?? []" :key="item.buildingId" :value="item.buildingId" :label="item.buildingName" />
          </ElSelect>
        </label>
        <label v-if="!alarms" class="filter-field">
          <span>{{ text('space') }}</span>
          <ElSelect v-model="space" :placeholder="text('all')" filterable :aria-label="text('space')">
            <ElOption value="" :label="text('all')" />
            <ElOption v-for="item in spaces.data.value ?? []" :key="item.spaceId" :value="item.spaceId" :label="item.spaceName" />
          </ElSelect>
        </label>
        <label v-if="!alarms" class="filter-field">
          <span>{{ text('kind') }}</span>
          <ElSelect v-model="kind" :placeholder="text('all')" :aria-label="text('kind')">
            <ElOption value="" :label="text('all')" />
            <ElOption v-for="key in ['INDOOR', 'OUTDOOR']" :key="key" :value="key" :label="text(key)" />
          </ElSelect>
        </label>
        <label v-if="!alarms" class="filter-field">
          <span>{{ text('searchDevice') }}</span>
          <ElInput v-model="searchInput" clearable :placeholder="text('searchPlaceholder')" :aria-label="text('searchDevice')" @keyup.enter="search" @clear="search" />
        </label>
        <ElButton v-if="!alarms" :disabled="!building" @click="search">{{ text('query') }}</ElButton>
        <ElButton :disabled="!building" :loading="devices.loading.value || exceptions.loading.value" @click="refresh">{{ text('refresh') }}</ElButton>
      </div>

      <div v-if="building && !alarms" class="toolbar-row">
        <div class="status-pills" role="group" :aria-label="text('state')">
          <button
            v-for="pill in ([
              ['ALL', 'quickAll', statusCounts.ALL],
              ['RUNNING', 'quickRunning', statusCounts.RUNNING],
              ['STOPPED', 'quickStopped', statusCounts.STOPPED],
              ['EXCEPTION', 'quickException', statusCounts.EXCEPTION],
              ['STALE', 'quickStale', statusCounts.STALE],
            ] as const)"
            :key="pill[0]"
            type="button"
            class="status-pill"
            :class="{ 'status-pill-active': quickStatus === pill[0] }"
            @click="quickStatus = pill[0]"
          >
            <span>{{ text(pill[1]) }}</span>
            <span class="pill-count">{{ '（' }}{{ pill[2] }}{{ '）' }}</span>
          </button>
        </div>

        <div class="view-switchers">
          <ElRadioGroup v-if="viewMode === 'CARD'" v-model="groupMode">
            <ElRadioButton value="SYSTEM">{{ text('groupBySystem') }}</ElRadioButton>
            <ElRadioButton value="SPACE">{{ text('groupBySpace') }}</ElRadioButton>
          </ElRadioGroup>
          <ElRadioGroup v-model="viewMode">
            <ElRadioButton value="CARD">{{ text('cardView') }}</ElRadioButton>
            <ElRadioButton value="TABLE">{{ text('tableView') }}</ElRadioButton>
          </ElRadioGroup>
        </div>
      </div>
    </header>

    <ElAlert v-if="spaces.error.value" :title="spaces.error.value" type="error" :closable="false" />
    <ElAlert v-if="buildings.error.value" :title="buildings.error.value" type="error" :closable="false" />
    <ElSkeleton v-if="buildings.loading.value" :rows="5" animated />
    <ElEmpty v-else-if="!building && !buildings.error.value" :description="t('dashboard.noBuilding')" />

    <template v-if="building && !alarms">
      <div class="device-list">
        <ElAlert v-if="devices.error.value" :title="devices.error.value" type="error" :closable="false" />
        <ElSkeleton v-if="devices.loading.value" :rows="5" animated />
        <ElEmpty v-else-if="!filteredDevices.length" :description="text('none')" />

        <template v-else-if="viewMode === 'CARD'">
          <div v-if="groupMode === 'SYSTEM'" class="group-stack">
            <section v-for="sysGroup in systemGroups" :key="sysGroup.groupKey" class="device-group">
              <header class="group-header">
                <div class="group-header-main">
                  <ElButton text @click="toggleGroup(sysGroup.groupKey)">
                    {{ text(collapsedGroups[sysGroup.groupKey] ? 'expandGroup' : 'collapseGroup') }}
                  </ElButton>
                  <ElTag type="primary">{{ text('outdoorSystemBadge') }}</ElTag>
                  <strong class="group-title">
                    {{ sysGroup.outdoorUnit ? (sysGroup.outdoorUnit.equipmentName || text('unnamed')) : text('unassignedSystem') }}
                  </strong>
                  <span v-if="sysGroup.outdoorUnit" class="group-subtitle">
                    {{ sysGroup.outdoorUnit.equipmentCode }}{{ ' · ' }}{{ text('unitStatusLabel') }}{{ '：' }}{{ daikinLabel(sysGroup.outdoorUnit.unitStatus?.value) }}
                  </span>
                </div>
                <div class="group-header-actions">
                  <span class="group-summary">
                    {{ text('linkedIndoorPrefix') }}{{ ' ' }}{{ sysGroup.indoorUnits.length }}{{ ' ' }}{{ text('unitCountSuffix') }}{{ ' · ' }}{{ text('runningIndoorPrefix') }}{{ ' ' }}{{ sysGroup.runningCount }}{{ ' ' }}{{ text('unitCountSuffix') }}
                  </span>
                  <ElButton
                    v-if="sysGroup.outdoorUnit"
                    type="primary"
                    plain
                    @click="selected = sysGroup.outdoorUnit.equipmentId"
                  >
                    {{ text('viewOutdoorDetail') }}
                  </ElButton>
                </div>
              </header>

              <div v-if="!collapsedGroups[sysGroup.groupKey]" class="card-grid">
                <article
                  v-for="item in (sysGroup.indoorUnits.length ? sysGroup.indoorUnits : (sysGroup.outdoorUnit ? [sysGroup.outdoorUnit] : []))"
                  :key="item.equipmentId"
                  class="device-card"
                  :class="{
                    'device-card-active': selected === item.equipmentId,
                    'device-card-warning': item.hasActiveException,
                  }"
                  @click="selected = item.equipmentId"
                >
                  <header class="device-card-header">
                    <div>
                      <strong class="device-card-name">{{ item.equipmentName || text('unnamed') }}</strong>
                      <p class="device-card-sub">{{ item.equipmentCode }}{{ ' · ' }}{{ spaceNameOf(item.spaceId) }}</p>
                    </div>
                    <div class="device-card-tags">
                      <ElTag v-if="item.hasActiveException" type="danger">{{ text('exception') }}</ElTag>
                      <ElTag :type="item.stale || !item.active ? 'warning' : 'success'">{{ deviceHealthLabel(item) }}</ElTag>
                    </div>
                  </header>

                  <div class="device-card-metrics">
                    <div class="metric-cell">
                      <span class="metric-label">{{ text('runStateLabel') }}</span>
                      <strong class="metric-value">
                        {{ item.deviceKind === 'OUTDOOR' ? daikinLabel(item.unitStatus?.value) : (isDaikinDeviceRunning(item) ? text('runningLabel') : text('stoppedLabel')) }}
                      </strong>
                    </div>
                    <div class="metric-cell">
                      <span class="metric-label">{{ text('modeLabel') }}</span>
                      <strong class="metric-value">
                        {{ item.deviceKind === 'OUTDOOR' ? text('outdoorNotApplicable') : daikinLabel(item.mode?.value) }}
                      </strong>
                    </div>
                    <div class="metric-cell">
                      <span class="metric-label">{{ text('unitStatusLabel') }}</span>
                      <strong class="metric-value">{{ daikinLabel(item.unitStatus?.value) }}</strong>
                    </div>
                  </div>

                  <footer class="device-card-footer">
                    <span class="sync-time">{{ text('syncTimeLabel') }}{{ '：' }}{{ item.lastValidAt == null ? t('common.missing') : formatDateTime(item.lastValidAt) }}</span>
                    <ElButton text type="primary" @click.stop="selected = item.equipmentId">{{ text('detail') }}</ElButton>
                  </footer>
                </article>
              </div>
            </section>
          </div>

          <div v-else class="group-stack">
            <section v-for="spGroup in spaceGroups" :key="spGroup.groupKey" class="device-group">
              <header class="group-header">
                <div class="group-header-main">
                  <ElButton text @click="toggleGroup(spGroup.groupKey)">
                    {{ text(collapsedGroups[spGroup.groupKey] ? 'expandGroup' : 'collapseGroup') }}
                  </ElButton>
                  <strong class="group-title">{{ spGroup.spaceName }}</strong>
                </div>
                <div class="group-header-actions">
                  <span class="group-summary">
                    {{ spGroup.devices.length }}{{ ' ' }}{{ text('unitCountSuffix') }}{{ ' · ' }}{{ text('runningIndoorPrefix') }}{{ ' ' }}{{ spGroup.runningCount }}{{ ' ' }}{{ text('unitCountSuffix') }}
                  </span>
                </div>
              </header>

              <div v-if="!collapsedGroups[spGroup.groupKey]" class="card-grid">
                <article
                  v-for="item in spGroup.devices"
                  :key="item.equipmentId"
                  class="device-card"
                  :class="{
                    'device-card-active': selected === item.equipmentId,
                    'device-card-warning': item.hasActiveException,
                  }"
                  @click="selected = item.equipmentId"
                >
                  <header class="device-card-header">
                    <div>
                      <strong class="device-card-name">{{ item.equipmentName || text('unnamed') }}</strong>
                      <p class="device-card-sub">{{ item.equipmentCode }}{{ ' · ' }}{{ text(item.deviceKind === 'OUTDOOR' ? 'outdoorBadge' : 'indoorBadge') }}</p>
                    </div>
                    <div class="device-card-tags">
                      <ElTag v-if="item.hasActiveException" type="danger">{{ text('exception') }}</ElTag>
                      <ElTag :type="item.stale || !item.active ? 'warning' : 'success'">{{ deviceHealthLabel(item) }}</ElTag>
                    </div>
                  </header>

                  <div class="device-card-metrics">
                    <div class="metric-cell">
                      <span class="metric-label">{{ text('runStateLabel') }}</span>
                      <strong class="metric-value">
                        {{ item.deviceKind === 'OUTDOOR' ? daikinLabel(item.unitStatus?.value) : (isDaikinDeviceRunning(item) ? text('runningLabel') : text('stoppedLabel')) }}
                      </strong>
                    </div>
                    <div class="metric-cell">
                      <span class="metric-label">{{ text('modeLabel') }}</span>
                      <strong class="metric-value">
                        {{ item.deviceKind === 'OUTDOOR' ? text('outdoorNotApplicable') : daikinLabel(item.mode?.value) }}
                      </strong>
                    </div>
                    <div class="metric-cell">
                      <span class="metric-label">{{ text('unitStatusLabel') }}</span>
                      <strong class="metric-value">{{ daikinLabel(item.unitStatus?.value) }}</strong>
                    </div>
                  </div>

                  <footer class="device-card-footer">
                    <span class="sync-time">{{ text('syncTimeLabel') }}{{ '：' }}{{ item.lastValidAt == null ? t('common.missing') : formatDateTime(item.lastValidAt) }}</span>
                    <ElButton text type="primary" @click.stop="selected = item.equipmentId">{{ text('detail') }}</ElButton>
                  </footer>
                </article>
              </div>
            </section>
          </div>
        </template>

        <ElTable
          v-else
          row-key="equipmentId"
          highlight-current-row
          :current-row-key="selected ?? undefined"
          :data="filteredDevices"
          :empty-text="text('none')"
        >
          <ElTableColumn :label="text('equipment')" min-width="180">
            <template #default="{ row }">{{ row.equipmentName || text('unnamed') }}<br>{{ row.equipmentCode || row.equipmentId }}</template>
          </ElTableColumn>
          <ElTableColumn :label="text('kind')">
            <template #default="{ row }">{{ ['INDOOR', 'OUTDOOR'].includes(row.deviceKind) ? text(row.deviceKind) : row.deviceKind }}</template>
          </ElTableColumn>
          <ElTableColumn :label="text('space')" min-width="140">
            <template #default="{ row }">{{ spaceNameOf(row.spaceId) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="text('onOff')">
            <template #default="{ row }">{{ row.deviceKind === 'OUTDOOR' ? daikinLabel(row.unitStatus?.value) : daikinLabel(row.onOff?.value) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="text('unitStatusLabel')">
            <template #default="{ row }">{{ daikinLabel(row.unitStatus?.value) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="text('mode')">
            <template #default="{ row }">{{ row.deviceKind === 'OUTDOOR' ? text('outdoorNotApplicable') : daikinLabel(row.mode?.value) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="text('state')" min-width="140">
            <template #default="{ row }">
              <ElTag :type="row.stale ? 'warning' : 'info'">{{ text(!row.active ? 'inactive' : row.lastValidAt == null ? 'noObservation' : row.stale ? 'stale' : 'normal') }}</ElTag>
              <ElTag v-if="row.hasActiveException" type="danger">{{ text('exception') }}</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn :label="text('fresh')" min-width="175">
            <template #default="{ row }">{{ row.lastValidAt == null ? t('common.missing') : formatDateTime(row.lastValidAt) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="text('detail')">
            <template #default="{ row }"><ElButton text @click="selected = row.equipmentId">{{ text('detail') }}</ElButton></template>
          </ElTableColumn>
        </ElTable>

        <ElPagination
          v-if="devices.data.value"
          v-model:current-page="page"
          :page-size="20"
          :total="devices.data.value.total"
          layout="prev, pager, next, total"
        />
      </div>

      <DaikinDeviceDetail
        v-if="selected"
        :key="selected"
        :equipment-id="selected"
        :equipment-name="selectedDevice?.equipmentName"
        :equipment-code="selectedDevice?.equipmentCode"
        :device-kind="selectedDevice?.deviceKind"
        :space-name="spaceNameOf(selectedDevice?.spaceId)"
        :refresh-tick="refreshTick"
        :visible="Boolean(selected)"
        @close="selected = null"
      />
    </template>

    <template v-if="building && alarms">
      <ElAlert v-if="exceptions.error.value" :title="exceptions.error.value" type="error" :closable="false" />
      <ElSkeleton v-if="exceptions.loading.value" :rows="5" animated />
      <ElTable v-else :data="exceptions.data.value?.items ?? []" :empty-text="text('empty')">
        <ElTableColumn :label="text('type')"><template #default="{ row }">{{ daikinLabel(row.type) }}</template></ElTableColumn>
        <ElTableColumn prop="sourceId" :label="text('source')" />
        <ElTableColumn :label="text('equipment')">
          <template #default="{ row }">{{ row.equipmentId ? (row.equipmentName || text('unnamed')) : text('sourceException') }}<br><span v-if="row.equipmentCode">{{ row.equipmentCode }}</span></template>
        </ElTableColumn>
        <ElTableColumn :label="text('detected')" min-width="175"><template #default="{ row }">{{ formatDateTime(row.firstDetectedAt) }}</template></ElTableColumn>
        <ElTableColumn v-if="history" :label="text('recovered')" min-width="175"><template #default="{ row }">{{ row.recoveredAt == null ? t('common.missing') : formatDateTime(row.recoveredAt) }}</template></ElTableColumn>
      </ElTable>
      <div class="exception-pagination">
        <ElButton @click="load()">{{ text('first') }}</ElButton>
        <ElButton :disabled="!exceptions.data.value?.nextCursor" @click="load(exceptions.data.value?.nextCursor ?? undefined)">{{ text('next') }}</ElButton>
      </div>
    </template>
  </section>
</template>

<style scoped>
.daikin-panel { display: grid; gap: var(--bec-space-section); min-width: 0; }
.panel-header { display: grid; gap: var(--bec-space-group); }
.title-block h1 { margin: 0; font-size: var(--bec-font-size-system); }
.title-block p { margin: var(--bec-space-tight) 0 0; color: var(--bec-color-text-secondary); line-height: var(--bec-line-height); }
.filters { display: flex; gap: var(--bec-space-group); flex-wrap: wrap; align-items: center; }
.filter-field { display: flex; align-items: center; gap: var(--bec-space-tight); }
.filter-field > span { white-space: nowrap; }
.filter-field .el-input { width: calc(var(--bec-control-height) * 7); }
.el-select { width: calc(var(--bec-control-height) * 6); }
.toolbar-row { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--bec-space-group); padding: var(--bec-space-group); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
.status-pills { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-tight); }
.status-pill { display: inline-flex; align-items: center; gap: var(--bec-space-tight); padding: var(--bec-space-tight) var(--bec-space-group); background: var(--bec-color-surface-secondary); color: var(--bec-color-text-primary); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-tag); cursor: pointer; font-size: var(--bec-font-size-body); }
.status-pill-active { background: var(--bec-color-action-primary); color: var(--bec-color-on-action); border-color: var(--bec-color-action-primary); font-weight: var(--bec-font-weight-heading); }
.pill-count { opacity: 0.85; }
.view-switchers { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-group); }
.device-list { min-width: 0; display: grid; gap: var(--bec-space-group); }
.group-stack { display: grid; gap: var(--bec-space-group); }
.device-group { background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); overflow: hidden; }
.group-header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--bec-space-group); padding: var(--bec-space-group); background: var(--bec-color-surface-secondary); border-bottom: var(--bec-border-width) solid var(--bec-color-border); }
.group-header-main { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-tight); }
.group-title { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
.group-subtitle { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.group-header-actions { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-group); }
.group-summary { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(calc(var(--bec-control-height) * 9), 1fr)); gap: var(--bec-space-group); padding: var(--bec-panel-padding); }
.device-card { display: grid; gap: var(--bec-space-group); padding: var(--bec-panel-padding); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); box-shadow: var(--bec-shadow-card); cursor: pointer; }
.device-card-active { border-color: var(--bec-color-brand-primary); box-shadow: var(--bec-shadow-focus); }
.device-card-warning { border-color: var(--bec-color-warning); }
.device-card-header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--bec-space-tight); }
.device-card-name { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); color: var(--bec-color-text-primary); }
.device-card-sub { margin: var(--bec-space-tight) 0 0; color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.device-card-tags { display: flex; flex-wrap: wrap; gap: var(--bec-space-tight); }
.device-card-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--bec-space-tight); padding: var(--bec-space-tight) var(--bec-space-group); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
.metric-cell { display: grid; gap: var(--bec-space-tight); }
.metric-label { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.metric-value { font-weight: var(--bec-font-weight-heading); color: var(--bec-color-text-primary); }
.device-card-footer { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-tight); }
.sync-time { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.exception-pagination { display: flex; gap: var(--bec-space-tight); justify-content: flex-start; }
.el-pagination { max-width: 100%; overflow-x: auto; }
</style>
