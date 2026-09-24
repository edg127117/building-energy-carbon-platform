<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { ref } from 'vue'
import { ElAlert, ElButton, ElDatePicker, ElEmpty, ElOption, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTabs, ElTabPane, ElTag } from '@/shared/ui'
import ChartView from '@/shared/charts/ChartView.vue'
import type { ChartOption } from '@/shared/charts/echarts'
import { formatDateTime } from '@/shared/utils/format'
import { t } from '@/locales'
import { daikinApi } from '../api/daikin'
import { daikinLabel, daikinFieldLabel, temperatureSeries, runtimePeriodTime, temperatureWindow } from '../models/daikin-display'
import { useDaikinResource } from '../composables/use-daikin-resource'

const props = defineProps<{ equipmentId: string; equipmentName?: string | null; equipmentCode?: string | null; refreshTick: number }>()
defineEmits<{ close: [] }>()
const text = (key: string) => t(`dashboard.daikin.${key}`)
const date = (value: number | null) => value == null ? t('common.missing') : formatDateTime(value)
const current = useDaikinResource<Awaited<ReturnType<typeof daikinApi.current>>>()
const events = useDaikinResource<Awaited<ReturnType<typeof daikinApi.events>>>()
const temperature = useDaikinResource<Awaited<ReturnType<typeof daikinApi.temperature>>>()
const history = useDaikinResource<Awaited<ReturnType<typeof daikinApi.history>>>()
const runtime = useDaikinResource<Awaited<ReturnType<typeof daikinApi.runtime>>>()
const revisions = useDaikinResource<Awaited<ReturnType<typeof daikinApi.revisions>>>()
const field = ref('roomTemp')
const granularity = ref('DAY')
const tab = ref('state')
const range = ref<[number, number]>([Date.now() - 86400000, Date.now()])
const queriedRange = ref<[number, number] | null>(null)
const rangeError = ref(false)
const revisionId = ref<string | null>(null)
const runtimeRows = computed(() => [...(runtime.data.value?.items ?? [])].sort((a, b) => b.periodStart - a.periodStart))
const option = computed<ChartOption>(() => ({
  tooltip: { trigger: 'axis' }, grid: { left: 55, right: 20, top: 25, bottom: 45 },
  xAxis: { type: 'time' }, yAxis: { type: 'value', scale: true },
  // 缺口插入空节点仅用于断线，不新增任何业务读数或插值。
  series: [{ type: 'line', connectNulls: false, showSymbol: true, data: temperatureSeries(history.data.value?.items ?? []) }],
}))
function loadCurrent() { void current.run(() => daikinApi.current(props.equipmentId)) }
function loadEvents(cursor?: string) { void events.run(() => daikinApi.events(props.equipmentId, cursor)) }
function loadTemperature(after?: number) {
  if (after == null) {
    const window = temperatureWindow(range.value, Date.now())
    if (!window) { rangeError.value = true; history.clear(); return }
    // 滚动保留边界随时钟推进；裁剪首端，后续翻页保持同一查询窗口。
    rangeError.value = false; queriedRange.value = window
  }
  const selected = queriedRange.value
  if (selected) void history.run(() => daikinApi.history(props.equipmentId, field.value, selected[0], selected[1], after))
}
function loadRuntime(cursor?: string) { revisionId.value = null; revisions.clear(); void runtime.run(() => daikinApi.runtime(props.equipmentId, granularity.value, cursor)) }
function loadRevisions(valueId: string, after?: number) { revisionId.value = valueId; void revisions.run(() => daikinApi.revisions(props.equipmentId, valueId, after)) }
function loadTab() {
  if (tab.value === 'events') loadEvents()
  if (tab.value === 'temperature') { void temperature.run(() => daikinApi.temperature(props.equipmentId, field.value)); loadTemperature() }
  if (tab.value === 'runtime') loadRuntime()
}
watch(tab, loadTab)
watch(field, () => { history.clear(); loadTab() })
watch(granularity, () => loadRuntime())
watch(() => props.refreshTick, () => { loadCurrent(); if (tab.value === 'temperature') void temperature.run(() => daikinApi.temperature(props.equipmentId, field.value)) })
onMounted(loadCurrent)
</script>

<template>
  <section class="detail">
    <header class="detail-heading"><div><h2>{{ equipmentName || text('unnamed') }}</h2><p v-if="equipmentCode" class="device-code">{{ text('code') }}{{ ': ' }}{{ equipmentCode }}</p></div><ElButton @click="$emit('close')">{{ text('close') }}</ElButton></header>
    <ElTabs v-model="tab">
      <ElTabPane :label="text('state')" name="state">
        <ElAlert v-if="current.error.value" :title="current.error.value" type="error" :closable="false" />
        <ElSkeleton v-if="current.loading.value" :rows="5" animated />
        <ElTable v-else :data="current.data.value?.fields ?? []">
          <ElTableColumn :label="text('field')"><template #default="{ row }">{{ daikinFieldLabel(row.fieldName) }}</template></ElTableColumn>
          <ElTableColumn :label="text('value')"><template #default="{ row }">{{ row.valueVisible ? daikinLabel(row.normalizedValue) : t('common.missing') }} <ElTag v-if="row.stale" type="warning">{{ text('stale') }}</ElTag></template></ElTableColumn>
          <ElTableColumn :label="text('status')"><template #default="{ row }">{{ daikinLabel(row.status) }}</template></ElTableColumn>
          <ElTableColumn :label="text('fresh')"><template #default="{ row }">{{ date(row.lastValidAt) }}</template></ElTableColumn>
          <ElTableColumn :label="text('attempt')"><template #default="{ row }">{{ date(row.lastAttemptAt) }}</template></ElTableColumn>
        </ElTable>
      </ElTabPane>
      <ElTabPane :label="text('events')" name="events">
        <p>{{ text('eventNotice') }}</p>
        <ElAlert v-if="events.error.value" :title="events.error.value" type="error" :closable="false" />
        <ElTable :data="events.data.value?.items ?? []">
          <ElTableColumn :label="text('field')"><template #default="{ row }">{{ daikinFieldLabel(row.fieldName) }}</template></ElTableColumn>
          <ElTableColumn :label="text('before')"><template #default="{ row }">{{ daikinLabel(row.beforeNormalizedValue) }}{{ ' · ' }}{{ date(row.previousObservedAt) }}</template></ElTableColumn>
          <ElTableColumn :label="text('after')"><template #default="{ row }">{{ daikinLabel(row.afterNormalizedValue) }}{{ ' · ' }}{{ date(row.observedAt) }} <ElTag v-if="row.afterGap" type="warning">{{ text('gap') }}</ElTag></template></ElTableColumn>
        </ElTable>
        <ElButton :loading="events.loading.value" @click="loadEvents()">{{ text('first') }}</ElButton>
        <ElButton :disabled="!events.data.value?.nextCursor" @click="loadEvents(events.data.value?.nextCursor ?? undefined)">{{ text('next') }}</ElButton>
      </ElTabPane>
      <ElTabPane :label="text('temperature')" name="temperature">
        <p>{{ text('temperatureNotice') }}</p>
        <div class="controls">
          <ElSelect v-model="field" :aria-label="text('temperature')"><ElOption value="roomTemp" :label="text('roomTemp')" /><ElOption value="temperature" :label="text('setpoint')" /></ElSelect>
          <ElDatePicker class="temperature-range" v-model="range" type="datetimerange" format="YYYY-MM-DD HH:mm" value-format="x" :aria-label="text('fromTo')" @change="range = range?.map(Number) as [number, number]" />
          <ElButton :loading="history.loading.value" @click="loadTemperature()">{{ text('query') }}</ElButton>
        </div>
        <ElAlert v-if="rangeError" :title="text('invalidRange')" type="warning" :closable="false" />
        <ElAlert v-if="temperature.error.value" :title="temperature.error.value" type="warning" :closable="false" />
        <p v-if="temperature.data.value">{{ text('value') }}{{ ': ' }}{{ temperature.data.value.reading?.value ?? t('common.missing') }} {{ temperature.data.value.unit }}{{ ' · ' }}{{ daikinLabel(temperature.data.value.fieldStatus) }}{{ ' · ' }}{{ date(temperature.data.value.reading?.observedAt ?? null) }} <ElTag v-if="temperature.data.value.reading?.quality && temperature.data.value.reading.quality.decision !== 'ALLOW'" type="warning">{{ text('qualityBlocked') }}</ElTag><ElTag v-if="temperature.data.value.reading?.stale" type="warning">{{ text('stale') }}</ElTag></p>
        <ElAlert v-if="history.error.value" :title="history.error.value" type="error" :closable="false" />
        <p v-if="history.data.value?.unit" class="chart-unit">{{ text('temperatureUnit') }}{{ '（' }}{{ history.data.value.unit }}{{ '）' }}</p>
        <div class="chart"><ChartView :option="option" :loading="history.loading.value" :empty="!history.data.value?.items.length" :accessible-label="text('temperature')" /></div>
        <ElButton :disabled="history.data.value?.nextCursor == null" @click="loadTemperature(history.data.value?.nextCursor ?? undefined)">{{ text('next') }}</ElButton>
      </ElTabPane>
      <ElTabPane :label="text('runtime')" name="runtime">
        <p>{{ text('runtimeNotice') }}</p>
        <ElSelect v-model="granularity" :aria-label="text('runtime')"><ElOption v-for="key in ['DAY', 'MONTH', 'YEAR']" :key="key" :value="key" :label="text(key)" /></ElSelect>
        <ElAlert v-if="runtime.error.value" :title="runtime.error.value" type="error" :closable="false" />
        <p v-for="count in runtime.data.value?.synchronization ?? []" :key="count.status">{{ daikinLabel(count.status) }}{{ ': ' }}{{ count.periods }}</p>
        <ElTable :data="runtimeRows">
          <ElTableColumn :label="text('period')" min-width="220"><template #default="{ row }">{{ runtimePeriodTime(row.periodStart, row.statisticsZone) }}{{ ' — ' }}{{ runtimePeriodTime(row.periodEnd, row.statisticsZone) }}<br>{{ row.statisticsZone }}</template></ElTableColumn>
          <ElTableColumn :label="text('value')" min-width="150"><template #default="{ row }"><span v-if="row.metrics == null">{{ t('common.missing') }}</span><div v-for="(value, name) in row.metrics" :key="name">{{ daikinLabel(String(name)) }}{{ ': ' }}{{ value }} {{ row.unit }}</div></template></ElTableColumn>
          <ElTableColumn :label="text('status')" min-width="180"><template #default="{ row }">{{ daikinLabel(row.lastAttemptStatus) }}<br><ElTag v-if="!row.periodComplete" type="warning">{{ text('incomplete') }}</ElTag><ElTag v-if="!row.ownershipVerified" type="info">{{ text('unverified') }}</ElTag></template></ElTableColumn>
          <ElTableColumn :label="text('success')" min-width="160"><template #default="{ row }">{{ date(row.lastSuccessAt) }}</template></ElTableColumn>
          <ElTableColumn :label="text('revisions')"><template #default="{ row }"><ElButton text @click="loadRevisions(row.valueId)">{{ text('revision') }} {{ row.revision }}</ElButton></template></ElTableColumn>
        </ElTable>
        <ElButton :loading="runtime.loading.value" @click="loadRuntime()">{{ text('first') }}</ElButton><ElButton :disabled="!runtime.data.value?.nextCursor" @click="loadRuntime(runtime.data.value?.nextCursor ?? undefined)">{{ text('next') }}</ElButton>
        <template v-if="revisionId">
          <h3>{{ text('revisions') }}</h3>
          <ElAlert v-if="revisions.error.value" :title="revisions.error.value" type="error" :closable="false" />
          <ElTable :data="revisions.data.value?.items ?? []"><ElTableColumn prop="revision" :label="text('revision')" /><ElTableColumn :label="text('value')"><template #default="{ row }"><div v-for="(value, name) in row.metrics" :key="name">{{ daikinLabel(String(name)) }}{{ ': ' }}{{ value }} {{ row.unit }}</div></template></ElTableColumn><ElTableColumn :label="text('fresh')"><template #default="{ row }">{{ date(row.observedAt) }}</template></ElTableColumn></ElTable>
          <ElButton :loading="revisions.loading.value" :disabled="revisions.data.value?.nextCursor == null" @click="loadRevisions(revisionId, revisions.data.value?.nextCursor ?? undefined)">{{ text('next') }}</ElButton>
        </template>
      </ElTabPane>
    </ElTabs>
    <ElEmpty v-if="tab === 'state' && !current.loading.value && !current.error.value && !current.data.value?.fields.length" :description="text('empty')" />
  </section>
</template>

<style scoped>
.detail { min-width: 0; padding: var(--bec-panel-padding); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); } .detail p { color: var(--bec-color-text-secondary); line-height: var(--bec-line-height); }
.detail-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); padding-bottom: var(--bec-space-group); }
.detail-heading h2 { margin: 0; font-size: var(--bec-font-size-title); }
.device-code { margin: var(--bec-space-tight) 0 0; }
.controls :deep(.temperature-range) { flex: 0 1 calc(var(--bec-control-height) * 12); width: calc(var(--bec-control-height) * 12); max-width: 100%; box-sizing: border-box; }
.controls { align-items: center; }
.controls { display: flex; flex-wrap: wrap; gap: var(--bec-space-tight); margin-block: var(--bec-space-group); } .el-select { width: calc(var(--bec-control-height) * 5); }
.chart { height: var(--bec-chart-height); min-height: var(--bec-chart-height); } .el-alert { margin-block: var(--bec-space-tight); }
</style>
