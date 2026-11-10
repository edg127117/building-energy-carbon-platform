<script setup lang="ts">
import { computed, ref, useId, watch, type Component } from 'vue'
import { Activity, Bell, ChevronDown, CircleAlert, CircleCheck, CircleX, Clock, Snowflake, Sun, Wind } from '@/shared/ui'
import { t } from '@/locales'
import type { ScreenAlarmSummaryData, ScreenDeviceSummaryData, ScreenRuntimeRankingEntry } from '../models/screen-summaries'

import { ScreenAlarmSummary } from '@/modules/alarms/public'

type GroupKey = 'switch' | 'mode' | 'speed'
interface Reading { key: string; label: string; count: number | null; icon: Component; tone: string }
interface Group { key: GroupKey; label: string; icon: Component; readings: Reading[] }
const props = defineProps<{ variant: 'a' | 'b' | 'c'; alarms?: ScreenAlarmSummaryData; devices?: ScreenDeviceSummaryData; runtimeRanking?: readonly ScreenRuntimeRankingEntry[]; updatedAt?: number; embedded?: boolean; alarmBuildingId?: string }>()
const s = (key: string, params?: Record<string, string | number>) => t('largeScreen.' + key, params)
const id = useId()
const alarmOpen = ref(true), deviceOpen = ref(true)
const deviceView = ref<'status' | 'ranking'>('status')
const selected = ref<GroupKey | 'all'>(props.variant === 'c' ? 'switch' : 'all')
const expanded = ref<Record<GroupKey, boolean>>({ switch: true, mode: true, speed: true })
watch(() => props.variant, variant => {
  deviceView.value = 'status'
  selected.value = variant === 'c' ? 'switch' : 'all'
  expanded.value = { switch: true, mode: true, speed: true }
  alarmOpen.value = true
  deviceOpen.value = true
})
const numberFormatter = new Intl.NumberFormat('zh-CN')
const number = (value: number | null | undefined) => typeof value === 'number' && Number.isFinite(value)
  ? numberFormatter.format(value) : s('missing')
const refreshTime = computed(() => typeof props.updatedAt === 'number' && Number.isFinite(props.updatedAt)
  ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(props.updatedAt) : null)
const alarms = computed(() => [
  { key: 'device', label: s('alarmSummaryDeviceAnomalies'), value: props.alarms?.deviceAnomalies, unit: s('alarmSummaryDeviceUnit'), icon: CircleAlert, tone: 'danger' },
  { key: 'emission', label: s('alarmSummaryEmissionWarnings'), value: props.alarms?.emissionWarnings, unit: s('alarmSummaryWarningUnit'), icon: Bell, tone: 'amber' },
] as const)
const groups = computed<Group[]>(() => [
  { key: 'switch', label: s('deviceSummarySwitch'), icon: Activity, readings: [
    { key: 'on', label: s('deviceSummaryOn'), count: props.devices?.on ?? null, icon: CircleCheck, tone: 'green' },
    { key: 'off', label: s('deviceSummaryOff'), count: props.devices?.off ?? null, icon: CircleX, tone: 'slate' },
    { key: 'unknown', label: s('deviceSummaryUnknown'), count: props.devices?.unknown ?? null, icon: CircleAlert, tone: props.variant === 'b' ? 'amber' : 'muted' },
  ] },
  { key: 'mode', label: s('deviceSummaryModes'), icon: Wind, readings: (props.devices?.modes ?? []).map(entry => ({
    ...entry,
    icon: entry.key === 'cool' ? Snowflake : entry.key === 'heat' ? Sun : Wind,
    tone: entry.key === 'cool' ? 'blue' : entry.key === 'heat' ? 'amber' : 'teal',
  })) },
  { key: 'speed', label: s('deviceSummarySpeeds'), icon: Wind, readings: (props.devices?.speeds ?? []).map((entry, index) => ({ ...entry, icon: Wind, tone: ['teal', 'blue', 'amber', 'violet'][index % 4] })) },
])
const displayedGroups = computed(() => props.variant === 'a' || selected.value === 'all' ? groups.value : groups.value.filter(group => group.key === selected.value))
const filters = computed(() => props.variant === 'c' ? groups.value : [{ key: 'all', label: s('summaryViewAll') }, ...groups.value])
const deviceViews = computed(() => [{ key: 'status' as const, label: s('summaryRuntimeStatus') }, { key: 'ranking' as const, label: s('summaryRuntimeRanking') }])
const rankingTones = ['amber', 'blue', 'teal', 'green', 'slate']
function visualWidth(value: number | null) {
  return typeof value === 'number' && Number.isFinite(value) ? `${Math.max(0, Math.min(100, value))}%` : '0%'
}
function readingLabel(entry: Reading) {
  return s('summaryReading', { label: entry.label, count: number(entry.count), total: number(props.devices?.total) })
}
</script>

<template>
  <div class="summary-pair" :class="['summary-' + variant, { 'is-embedded': embedded }]">
    <section class="summary-panel alarm-panel" :class="{ collapsed: !alarmOpen }">
      <header class="panel-heading">
        <h2><Bell :size="18" aria-hidden="true" />{{ s('alarms') }}</h2>
        <button class="icon-button" :aria-label="s(alarmOpen ? 'summaryCollapsePanel' : 'summaryExpandPanel', { label: s('alarms') })" :aria-expanded="alarmOpen" :aria-controls="id + '-alarms'" @click="alarmOpen = !alarmOpen"><ChevronDown :size="18" :class="{ turned: !alarmOpen }" /></button>
      </header>
      <div v-show="alarmOpen" :id="id + '-alarms'" class="alarm-readings">
        <ScreenAlarmSummary v-if="alarmBuildingId !== undefined" v-slot="{ counts, open }" :building-id="alarmBuildingId">
          <button v-for="alarm in alarms" :key="alarm.key" type="button" class="alarm-reading alarm-action" :class="'tone-' + alarm.tone" :data-test="'candidate-alarm-' + alarm.key" :aria-label="s('alarmSummaryCount', { label: alarm.label, count: number(counts[alarm.key]), unit: alarm.unit })" @click="open(alarm.key, $event)">
            <span class="alarm-symbol"><component :is="alarm.icon" :size="18" aria-hidden="true" /></span>
            <span class="alarm-copy"><span class="alarm-name">{{ alarm.label }}</span><i class="alarm-accent" aria-hidden="true" /></span>
            <span class="alarm-number"><b>{{ number(counts[alarm.key]) }}</b><small>{{ alarm.unit }}</small></span>
          </button>
        </ScreenAlarmSummary>
        <article v-for="alarm in alarmBuildingId === undefined ? alarms : []" :key="alarm.key" class="alarm-reading" :class="'tone-' + alarm.tone" :data-test="'candidate-alarm-' + alarm.key" tabindex="0" :aria-label="s('alarmSummaryCount', { label: alarm.label, count: number(alarm.value), unit: alarm.unit })">
          <span class="alarm-symbol"><component :is="alarm.icon" :size="18" aria-hidden="true" /></span>
          <div class="alarm-copy"><span class="alarm-name">{{ alarm.label }}</span><i class="alarm-accent" aria-hidden="true" /></div>
          <span class="alarm-number"><b>{{ number(alarm.value) }}</b><small>{{ alarm.unit }}</small></span>
        </article>
      </div>
    </section>

    <section class="summary-panel device-panel" :class="{ collapsed: !deviceOpen, focused: variant === 'c' && selected !== 'all' }">
      <header class="panel-heading device-panel-heading">
        <div class="device-heading-main"><h2>{{ s('redesignDevices') }}</h2></div>
        <button class="icon-button" :aria-label="s(deviceOpen ? 'summaryCollapsePanel' : 'summaryExpandPanel', { label: s('redesignDevices') })" :aria-expanded="deviceOpen" :aria-controls="id + '-devices'" @click="deviceOpen = !deviceOpen"><ChevronDown :size="18" :class="{ turned: !deviceOpen }" /></button>
      </header>
      <div v-show="deviceOpen" :id="id + '-devices'" class="device-content">
        <div class="device-toolbar">
          <div class="device-meta">
            <span class="system-label"><Snowflake :size="14" aria-hidden="true" />{{ s('deviceSummaryScope') }}</span>
            <span v-if="refreshTime" class="live-stamp" :title="s('deviceSummaryRefresh', { time: refreshTime })"><i aria-hidden="true" /><span>{{ s('deviceSummaryLive') }}</span><time>{{ refreshTime }}</time></span>
          </div>
          <nav v-if="variant === 'b'" class="group-filter" :aria-label="s('summaryViewFilter')"><button v-for="view in deviceViews" :key="view.key" :aria-pressed="deviceView === view.key" @click="deviceView = view.key">{{ view.label }}</button></nav>
          <nav v-else-if="variant === 'c'" class="group-filter" :aria-label="s('summaryViewFilter')">
            <button v-for="filter in filters" :key="filter.key" :aria-pressed="selected === filter.key" @click="selected = filter.key as GroupKey | 'all'">{{ filter.label }}</button>
          </nav>
        </div>
        <div v-if="variant === 'b' && deviceView === 'ranking'" class="runtime-ranking" data-test="candidate-runtime-ranking">
          <article v-for="(entry, index) in runtimeRanking ?? []" :key="entry.rank" class="ranking-card" :class="'tone-' + rankingTones[index % rankingTones.length]" :data-test="'candidate-rank-' + entry.rank" tabindex="0" :aria-label="s('summaryRankReading', { rank: entry.rank, room: entry.room, hours: number(entry.hours) })">
            <div class="ranking-top"><span class="ranking-badge">{{ s('summaryRankBadge', { rank: entry.rank }) }}</span><span class="ranking-floor">{{ entry.floor }}</span></div>
            <strong class="ranking-room">{{ entry.room }}</strong>
            <div class="ranking-bottom"><span class="ranking-period"><Clock :size="12" aria-hidden="true" />{{ s('summaryRuntimePeriod') }}</span><span class="ranking-hours"><b>{{ number(entry.hours) }}</b><small>{{ s('summaryRuntimeUnit') }}</small></span></div>
            <span class="reading-bar" aria-hidden="true"><i :style="{ width: visualWidth(entry.percentage) }" /></span>
          </article>
          <p v-if="!runtimeRanking?.length" class="missing-state">{{ s('deviceSummaryMissing') }}</p>
        </div>
        <div v-else class="status-groups">
          <section v-for="group in displayedGroups" :key="group.key" class="status-group" :class="'group-' + group.key" :data-test="'candidate-group-' + group.key">
            <button v-if="variant === 'a'" class="group-heading" :aria-expanded="expanded[group.key]" :aria-controls="id + '-' + group.key" :aria-label="s('summaryGroupToggle', { action: s(expanded[group.key] ? 'summaryCollapse' : 'summaryExpand'), label: group.label })" @click="expanded[group.key] = !expanded[group.key]"><component :is="group.icon" :size="16" aria-hidden="true" /><span>{{ group.label }}</span><ChevronDown :size="14" :class="{ turned: !expanded[group.key] }" /></button>
            <div v-else-if="variant === 'b'" class="group-heading-row"><h3 class="group-heading"><component :is="group.icon" :size="14" aria-hidden="true" /><span>{{ group.label }}</span></h3></div>
            <div v-show="variant !== 'a' || expanded[group.key]" :id="id + '-' + group.key" class="status-readings">
              <article v-for="entry in group.readings" :key="entry.key" class="status-reading" :class="'tone-' + entry.tone" :data-test="'candidate-' + group.key + '-' + entry.key" tabindex="0" :title="readingLabel(entry)" :aria-label="readingLabel(entry)">
                <div class="reading-main">
                  <span class="reading-head"><component :is="entry.icon" :size="15" aria-hidden="true" /><span class="reading-label">{{ entry.label }}</span></span>
                  <span class="reading-number"><b>{{ number(entry.count) }}</b><small>{{ s('summaryTotalSuffix', { total: number(devices?.total) }) }}</small></span>
                </div>
              </article>
              <p v-if="!group.readings.length" class="missing-state">{{ s('deviceSummaryMissing') }}</p>
            </div>
          </section>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped src="./screen-summary-candidate.css"></style>
