<script setup lang="ts">
import { computed, type Component } from 'vue'
import { Activity, CircleAlert, CircleCheck, CircleX, Snowflake, Sun, Wind } from '@/shared/ui'
import { t } from '@/locales'
import type { ScreenDeviceSummaryData, ScreenDeviceSummaryEntry } from '../models/screen-summaries'

interface SummaryEntry {
  key: string
  label: string
  count: number | null | undefined
  icon: Component
  tone: string
}

const props = defineProps<{ data?: ScreenDeviceSummaryData; updatedAt?: number; animate?: boolean }>()
const refreshTime = computed(() => typeof props.updatedAt === 'number' && Number.isFinite(props.updatedAt)
  ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(props.updatedAt) : null)
const numberFormatter = new Intl.NumberFormat('zh-CN')

function isFiniteNumber(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function displayNumber(value: number | null | undefined): string {
  return isFiniteNumber(value) ? numberFormatter.format(value) : t('largeScreen.deviceSummaryNumberMissing')
}

function countWithTotal(value: number | null | undefined): string {
  return t('largeScreen.deviceSummaryCountWithTotal', {
    count: displayNumber(value),
    total: displayNumber(props.data?.total),
  })
}

function modePresentation(entry: ScreenDeviceSummaryEntry): Pick<SummaryEntry, 'icon' | 'tone'> {
  const token = [entry.key, entry.label].join(' ').toLocaleLowerCase()
  if (token.includes('fan') || entry.label === t('largeScreen.deviceSummaryFan')) return { icon: Wind, tone: 'mode-fan' }
  if (token.includes('cool') || entry.label === t('largeScreen.deviceSummaryCooling')) return { icon: Snowflake, tone: 'mode-cooling' }
  if (token.includes('heat') || entry.label === t('largeScreen.deviceSummaryHeating')) return { icon: Sun, tone: 'mode-heating' }
  return { icon: Activity, tone: 'mode-other' }
}

const switches = computed<SummaryEntry[]>(() => [
  { key: 'on', label: t('largeScreen.deviceSummaryOn'), count: props.data?.on, icon: CircleCheck, tone: 'switch-on' },
  { key: 'off', label: t('largeScreen.deviceSummaryOff'), count: props.data?.off, icon: CircleX, tone: 'switch-off' },
  { key: 'unknown', label: t('largeScreen.deviceSummaryUnknown'), count: props.data?.unknown, icon: CircleAlert, tone: 'switch-unknown' },
])

const modes = computed<SummaryEntry[]>(() => (props.data?.modes ?? []).map(entry => ({ ...entry, ...modePresentation(entry) })))
const speeds = computed<SummaryEntry[]>(() => (props.data?.speeds ?? []).map((entry, index) => ({
  ...entry,
  icon: Wind,
  tone: ['speed-cyan', 'speed-blue', 'speed-orange', 'speed-blue-soft'][index % 4],
})))
</script>

<template>
  <section class="device-summary" :class="{ 'is-paused': animate === false }" :aria-label="t('largeScreen.deviceSummaryLabel')">
    <div class="device-summary-meta">
      <p class="device-summary-scope"><Snowflake :size="16" aria-hidden="true" />{{ t('largeScreen.deviceSummaryScope') }}</p>
      <span v-if="refreshTime" class="device-summary-live" :title="t('largeScreen.deviceSummaryRefresh', { time: refreshTime })"><i aria-hidden="true" />{{ t('largeScreen.deviceSummaryLive') }}<time>{{ refreshTime }}</time></span>
    </div>
    <section class="device-summary-zone">
      <h3><Activity :size="16" aria-hidden="true" />{{ t('largeScreen.deviceSummarySwitch') }}</h3>
      <div class="device-summary-switches">
        <article v-for="entry in switches" :key="entry.key" class="device-summary-entry switch" :class="[entry.tone, { 'is-active': (entry.count ?? 0) > 0 }]" :data-testid="'device-' + entry.key" :aria-label="t('largeScreen.deviceSummaryStatusCount', { label: entry.label, count: countWithTotal(entry.count) })">
          <component :is="entry.icon" :size="18" aria-hidden="true" />
          <div><strong>{{ entry.label }}</strong><small>{{ countWithTotal(entry.count) }}</small></div>
        </article>
      </div>
    </section>
    <section class="device-summary-zone">
      <h3><Wind :size="16" aria-hidden="true" />{{ t('largeScreen.deviceSummaryModes') }}</h3>
      <div v-if="modes.length" class="device-summary-items">
        <article v-for="entry in modes" :key="entry.key" class="device-summary-entry mode" :class="[entry.tone, { 'is-active': (entry.count ?? 0) > 0 }]" :data-testid="'mode-' + entry.key" :aria-label="t('largeScreen.deviceSummaryStatusCount', { label: entry.label, count: countWithTotal(entry.count) })">
          <component :is="entry.icon" :size="18" aria-hidden="true" />
          <div><strong>{{ entry.label || entry.key }}</strong><small>{{ countWithTotal(entry.count) }}</small></div>
        </article>
      </div>
      <p v-else class="device-summary-missing">{{ t('largeScreen.deviceSummaryMissing') }}</p>
    </section>
    <section class="device-summary-zone">
      <h3><Wind :size="16" aria-hidden="true" />{{ t('largeScreen.deviceSummarySpeeds') }}</h3>
      <div v-if="speeds.length" class="device-summary-items">
        <article v-for="entry in speeds" :key="entry.key" class="device-summary-entry speed" :class="[entry.tone, { 'is-active': (entry.count ?? 0) > 0 }]" :data-testid="'speed-' + entry.key" :aria-label="t('largeScreen.deviceSummaryStatusCount', { label: entry.label, count: countWithTotal(entry.count) })">
          <component :is="entry.icon" :size="18" aria-hidden="true" />
          <div><strong>{{ entry.label || entry.key }}</strong><small>{{ countWithTotal(entry.count) }}</small></div>
        </article>
      </div>
      <p v-else class="device-summary-missing">{{ t('largeScreen.deviceSummaryMissing') }}</p>
    </section>
  </section>
</template>

<style scoped>
.device-summary { width: 100%; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); align-content: start; gap: var(--bec-screen-space-4); color: var(--bec-screen-ink); }
.device-summary-meta { grid-column: 1 / -1; display: flex; align-items: center; justify-content: space-between; gap: var(--bec-screen-space-8); }
.device-summary-scope { margin: 0; display: inline-flex; align-items: center; gap: var(--bec-screen-space-4); padding: var(--bec-screen-space-2) var(--bec-screen-space-6); color: var(--bec-screen-ink); background: var(--bec-screen-neutral); border-radius: var(--bec-screen-radius); font-size: var(--bec-ref-font-16); font-weight: var(--bec-screen-weight); line-height: var(--bec-screen-heading-line-height); }
.device-summary-live { display: inline-flex; align-items: center; gap: var(--bec-screen-space-6); color: var(--bec-screen-device-green); font-size: var(--bec-screen-font-small); white-space: nowrap; }
.device-summary-live i { width: var(--bec-screen-space-6); height: var(--bec-screen-space-6); border-radius: var(--bec-screen-radius-round); background: currentColor; }
.device-summary-live time { color: var(--bec-screen-muted); font-variant-numeric: tabular-nums; }
.device-summary-zone { min-width: 0; display: flex; flex-direction: column; gap: var(--bec-screen-space-4); }
.device-summary-zone h3 { display: flex; align-items: center; gap: var(--bec-screen-space-6); margin: 0; color: var(--bec-screen-ink); font-size: var(--bec-screen-font-body); font-weight: var(--bec-screen-weight); }
.device-summary-switches,.device-summary-items { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--bec-screen-space-4); }
.device-summary-entry { min-width: 0; display: flex; align-items: center; gap: var(--bec-screen-space-4); padding: var(--bec-screen-space-2) var(--bec-screen-space-4); color: var(--bec-screen-summary-tone); background: var(--bec-screen-detail-panel); border: var(--bec-screen-border-width) solid var(--bec-screen-divider); border-left: var(--bec-screen-space-4) solid var(--bec-screen-summary-tone); border-radius: var(--bec-screen-radius); }
.device-summary-entry>div { min-width: 0; flex: 1; display: flex; align-items: baseline; justify-content: space-between; gap: var(--bec-screen-space-4); }
.device-summary-entry strong { overflow: hidden; color: var(--bec-screen-ink); font-size: var(--bec-screen-font-small); font-weight: var(--bec-screen-weight); text-overflow: ellipsis; white-space: nowrap; }
.device-summary-entry small { color: var(--bec-screen-summary-tone); font-size: var(--bec-screen-font-small); font-weight: var(--bec-screen-weight); white-space: nowrap; }
.switch-on { --bec-screen-summary-tone: var(--bec-screen-device-green); }
.switch-off { --bec-screen-summary-tone: var(--bec-screen-device-off); }
.switch-unknown { --bec-screen-summary-tone: var(--bec-screen-muted); }
.mode-fan { --bec-screen-summary-tone: var(--bec-screen-cyan); }
.mode-cooling { --bec-screen-summary-tone: var(--bec-screen-blue); }
.mode-heating { --bec-screen-summary-tone: var(--bec-screen-orange); }
.mode-other { --bec-screen-summary-tone: var(--bec-screen-blue-soft); }
.speed-cyan { --bec-screen-summary-tone: var(--bec-screen-cyan); }
.speed-blue { --bec-screen-summary-tone: var(--bec-screen-blue); }
.speed-orange { --bec-screen-summary-tone: var(--bec-screen-orange); }
.speed-blue-soft { --bec-screen-summary-tone: var(--bec-screen-blue-soft); }
.device-summary-missing { margin: 0; padding: var(--bec-screen-space-8); color: var(--bec-screen-muted); background: var(--bec-screen-detail-panel); border: var(--bec-screen-border-width) dashed var(--bec-screen-divider); font-size: var(--bec-screen-font-small); }
.mode-cooling.is-active>svg { animation: device-cooling-turn var(--bec-screen-status-turn-time) linear infinite; }
.mode-fan.is-active>svg,.speed.is-active>svg { animation: device-airflow var(--bec-screen-status-pulse-time) ease-in-out infinite; }
@keyframes device-cooling-turn { to { transform: rotate(360deg); } }
@keyframes device-airflow { 0%,100% { transform: translateX(0); } 50% { transform: translateX(var(--bec-screen-space-2)); } }
.device-summary.is-paused :is(svg,small,i) { animation: none; }
@media (prefers-reduced-motion: reduce) { .device-summary :is(.device-summary-entry,.device-summary-live) :is(svg,small,i) { animation: none; } }
</style>
