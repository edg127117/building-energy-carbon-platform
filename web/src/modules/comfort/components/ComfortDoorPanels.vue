<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ChartView from '@/shared/charts/ChartView.vue'
import { ElButton, ElDrawer, ElPopover, ElRadioButton, ElRadioGroup } from '@/shared/ui'
import { formatDateTime, formatNumber } from '@/shared/utils/format'
import { t } from '@/locales'
import type { ComfortContact, ComfortDoorStats, DoorBucket, DoorVisualState } from '../api/comfort'
import copy from '../locales/zh-CN'
import { beijingMinute, contactState, contactSummaryText, contactTotal, doorDistributionOption, formatDoorElapsed } from '../models/sensor-charts'
import ComfortPanel from './ComfortPanel.vue'

const props = defineProps<{ stats: ComfortDoorStats | null; spaceId: string; state: DoorVisualState; message: string; notice: string }>()
const emit = defineEmits<{ selectDoor: [equipmentId: string] }>()
const measure = ref<'count' | 'duration'>('count')
const detailsOpen = ref(false)
const tableWrap = ref<HTMLElement | null>(null)
watch(() => props.spaceId, () => { detailsOpen.value = false })
const contacts = computed(() => props.stats?.contacts ?? [])
const groups = computed(() => [
  { kind: 'DOOR', label: copy.contactDoors, summary: props.stats?.doors },
  { kind: 'WINDOW', label: copy.contactWindows, summary: props.stats?.windows },
  { kind: null, label: copy.contactUnclassified, summary: undefined },
].map(group => ({ ...group, items: contacts.value.filter(item => item.kind === group.kind) }))
  .filter(group => group.items.length > 0))
const summaries = computed(() => groups.value.flatMap(group => group.summary && contactTotal(group.summary)
  ? [{ label: group.label, value: contactSummaryText(group.summary) }] : []))
const showSummary = computed(() => Boolean(contactTotal(props.stats?.windows) || contacts.value.length > 1))
const selectedDoorName = computed(() => contactTotal(props.stats?.doors) > 1
  ? contacts.value.find(item => item.equipmentId === props.stats?.selectedDoorEquipmentId)?.name : undefined)
function showDoorStats(contact: ComfortContact) {
  detailsOpen.value = false
  emit('selectDoor', contact.equipmentId)
}

const missing = t('common.missing')
const events = computed(() => props.stats?.recentEvents ?? [])
const rows = computed(() => props.stats?.hourlyRows ?? [])
const hasHours = computed(() => rows.value.some(row => row.openCount != null))
const unit = computed(() => measure.value === 'count' ? copy.countUnit : copy.secondUnit)
const totalLabel = computed(() => measure.value === 'count' ? copy.totalCount : copy.totalDuration)
const buckets = computed(() => (Object.keys(copy.doorBuckets) as DoorBucket[]).map(bucket => {
  const data = props.stats?.durationBuckets.find(item => item.bucket === bucket)
  return {
    name: copy.doorBuckets[bucket],
    value: (measure.value === 'count' ? data?.count : data?.durationSec) ?? null,
  }
}))
const total = computed(() => buckets.value.reduce((sum, item) => sum + (item.value ?? 0), 0))
// 环图合计和比例仅用于展示同一组分桶，次数、时长与长开标记直接使用接口值。
const share = (value: number) => total.value > 0 ? Math.round(value * 100 / total.value) : 0
const distribution = computed(() => doorDistributionOption(
  buckets.value.map(item => ({ name: item.name, value: item.value ?? 0 })), unit.value,
))
const duration = (value: number | null) => formatNumber(value, { maximumFractionDigits: 1 })
const elapsed = (seconds: number | null) => seconds == null ? missing : formatDoorElapsed(seconds)

const noticeLabel = computed(() => {
  if (!props.notice) return ''
  if (props.notice.includes(copy.refreshFailedShort)) return copy.refreshFailedBadge
  if (props.notice.includes(copy.offline)) return copy.offlineBadge
  return copy.staleBadge
})

const heroDetail = computed(() => {
  const data = props.stats
  if (props.message) return props.message
  if (props.state === 'open' && data?.currentDurationSec != null) {
    return `${copy.doorOpeningPrefix}${copy.openingDuration} ${elapsed(data.currentDurationSec)}`
  }
  if (props.state === 'closed' && data?.lastTransitionAt) {
    const seconds = events.value[0]?.durationSec
    return `${copy.doorClosedPrefix}${[
      `${copy.latestClose} ${beijingMinute(data.lastTransitionAt)}`,
      seconds == null ? '' : `${copy.currentOpening} ${elapsed(seconds)}`,
    ].filter(Boolean).join(copy.dotSeparator)}`
  }
  return ''
})

// 后端返回完整 24 小时行时，默认定位到首个活跃时段或 08 时，避免首屏停在凌晨空行。
// 容器高度会在图表布局完成后变化，因此尺寸变化时重新定位；用户手动滚动后不再自动跳转。
let autoFocusHour = true
function stopAutoFocusHour() { autoFocusHour = false }
function focusActiveHour() {
  const container = tableWrap.value
  if (!container || !autoFocusHour || rows.value.length <= 12) return
  const firstActive = rows.value.findIndex(row => (row.openCount ?? 0) > 0)
  const morningIndex = rows.value.findIndex(row => beijingMinute(row.startsAt).startsWith('08'))
  const focusIndex = firstActive >= 0 && (morningIndex < 0 || firstActive < morningIndex)
    ? firstActive : Math.max(0, morningIndex)
  const target = container.querySelectorAll('tbody tr')[focusIndex]
  const head = container.querySelector('thead')
  if (!(target instanceof HTMLElement)) return
  container.scrollTop = Math.max(0, target.offsetTop - (head instanceof HTMLElement ? head.offsetHeight : 0))
}
watch(() => props.spaceId, () => { autoFocusHour = true })
watch(rows, async () => {
  await nextTick()
  focusActiveHour()
})
let tableResize: ResizeObserver | null = null
onMounted(() => {
  if (typeof ResizeObserver === 'undefined' || !tableWrap.value) return
  tableResize = new ResizeObserver(() => focusActiveHour())
  tableResize.observe(tableWrap.value)
})
onBeforeUnmount(() => {
  tableResize?.disconnect()
  tableResize = null
})
</script>

<template>
  <div>
    <ComfortPanel :heading="copy.doors">
      <template #status>
        <span v-if="!showSummary" class="door-header-tag">{{ copy.contactType }}</span>
      </template>
      <template #actions>
        <button
          v-if="showSummary && summaries.length" class="contact-summaries" type="button"
          :title="copy.contactDetails" @click="detailsOpen = true"
        >
          <span v-for="summary in summaries" :key="summary.label" class="contact-summary">
            <strong>{{ summary.label }}</strong><span>{{ summary.value }}</span>
          </span>
        </button>
        <ElButton v-else-if="contacts.length" link size="small" @click="detailsOpen = true">{{ copy.contactDetails }}</ElButton>
      </template>
      <div class="comfort-door-split">
        <div class="comfort-door-state">
          <div v-if="stats?.status !== 'SELECTION_REQUIRED'" class="door-hero" :data-door-state="state">
            <svg class="comfort-door-icon" viewBox="0 0 64 60" aria-hidden="true">
              <rect class="comfort-door-frame" x="6" y="6" width="52" height="48" rx="4" />
              <path class="comfort-door-swing" d="M14 48 A26 26 0 0 0 40 22" stroke-dasharray="3 3" />
              <g :transform="state === 'open' ? 'rotate(52 14 48)' : undefined">
                <rect
                  class="comfort-door-leaf" x="14" y="10" width="26" height="38" rx="2"
                  :stroke-dasharray="state === 'unknown' ? '3 3' : undefined"
                />
                <circle class="comfort-door-handle" cx="34" cy="29" r="2.2" />
              </g>
            </svg>
            <div class="door-hero-text">
              <div class="door-hero-title">
                <span v-if="selectedDoorName" class="door-selected-name" :title="selectedDoorName">{{ selectedDoorName }}</span>
                <strong>{{ copy.doorState[state] }}</strong>
                <ElPopover v-if="notice" trigger="hover" placement="top" :teleported="false" width="auto">
                  <template #reference>
                    <span class="door-status-chip" tabindex="0">{{ noticeLabel }}</span>
                  </template>
                  <span class="door-observation-note">{{ notice }}</span>
                </ElPopover>
              </div>
              <span v-if="heroDetail" class="door-hero-meta" :title="heroDetail">{{ heroDetail }}</span>
            </div>
          </div>
          <div class="door-event-box">
            <div class="door-event-heading">
              <h3>{{ copy.events }}</h3>
              <span v-if="events.length">{{ events.length }} {{ copy.eventUnit }}</span>
            </div>
            <ol v-if="events.length" class="door-events">
              <li v-for="(event, index) in events" :key="index">
                <time :datetime="event.observedAt" :title="formatDateTime(event.observedAt, 'Asia/Shanghai')">
                  {{ beijingMinute(event.observedAt) }}
                </time>
                <strong :class="event.open ? 'door-open-text' : 'door-closed-text'">
                  {{ event.open ? copy.eventState.open : copy.eventState.closed }}
                </strong>
                <span class="door-event-duration" :title="elapsed(event.durationSec)">{{ elapsed(event.durationSec) }}</span>
              </li>
            </ol>
            <div v-else class="door-empty">{{ message || copy.noDoorEvents }}</div>
          </div>
        </div>
        <div
          ref="tableWrap" class="comfort-door-table"
          @wheel.passive="stopAutoFocusHour" @touchstart.passive="stopAutoFocusHour" @pointerdown="stopAutoFocusHour"
        >
          <table>
            <thead><tr><th scope="col">{{ copy.periods }}</th><th scope="col">{{ copy.occurrences }}</th><th scope="col">{{ copy.duration }}</th></tr></thead>
            <tbody v-if="hasHours">
              <tr v-for="row in rows" :key="row.startsAt">
                <th scope="row">{{ copy.hourPeriod(beijingMinute(row.startsAt).slice(0, 2)) }}</th>
                <td :class="{ 'door-future-cell': row.openCount == null }">{{ row.openCount ?? missing }}</td>
                <td
                  :class="{ 'door-long-open': row.hasLongOpen, 'door-future-cell': row.openCount == null }"
                  :title="row.hasLongOpen ? copy.longOpening : undefined"
                >
                  <span v-if="row.hasLongOpen" aria-hidden="true">{{ copy.longOpeningMark }}</span>
                  {{ duration(row.durationSec) }}
                </td>
              </tr>
            </tbody>
            <tbody v-else><tr><td colspan="3" class="door-future-cell">{{ missing }}</td></tr></tbody>
          </table>
        </div>
      </div>
    </ComfortPanel>

    <ComfortPanel :heading="copy.doorDistribution">
      <template #actions>
        <ElRadioGroup v-model="measure" size="small" :aria-label="copy.doorDistribution">
          <ElRadioButton value="count">{{ copy.byCount }}</ElRadioButton>
          <ElRadioButton value="duration">{{ copy.byDuration }}</ElRadioButton>
        </ElRadioGroup>
      </template>
      <div v-if="total > 0" class="door-pie-layout">
        <div class="door-pie">
          <ChartView :option="distribution" :accessible-label="`${copy.doorDistribution}${copy.dotSeparator}${totalLabel}`" />
          <div class="door-pie-total" aria-hidden="true">
            <strong>{{ duration(total) }}</strong>
            <span>{{ totalLabel }}</span>
          </div>
        </div>
        <ul class="door-legend">
          <li v-for="item in buckets" :key="item.name">
            <div class="door-bucket-value">
              <span>{{ item.name }}</span>
              <strong>
                {{ item.value == null ? missing : duration(item.value) + ' ' + unit }}
                <small v-if="item.value != null && total > 0">{{ copy.percentage(share(item.value)) }}</small>
              </strong>
            </div>
            <div v-if="item.value != null" class="door-bucket-track" aria-hidden="true">
              <div :style="{ width: `${share(item.value)}%` }" />
            </div>
          </li>
        </ul>
      </div>
      <div v-else class="door-empty door-empty-full">
        <span class="door-empty-badge">{{ message || copy.noCompletedOpenings }}</span>
      </div>
    </ComfortPanel>

    <ElDrawer
      v-model="detailsOpen" :title="copy.contactDetails"
      :size="'min(94%, var(--bec-ref-comfort-drawer-width))'" destroy-on-close
    >
      <section v-for="group in groups" :key="group.label" class="contact-detail-group" :aria-label="group.label">
        <h3>{{ group.label }}</h3>
        <ul class="contact-details">
          <li v-for="item in group.items" :key="item.equipmentId">
            <div>
              <strong>{{ item.name }}</strong>
              <span class="contact-reading" :data-state="contactState(item.open)">{{ copy.contactState[contactState(item.open)] }}</span>
              <span v-if="item.open == null && item.lastObservedOpen != null" class="contact-observed">
                {{ copy.contactPrevious(copy.contactState[contactState(item.lastObservedOpen)]) }}
              </span>
              <span v-if="item.lastObservedAt" class="contact-observed">
                {{ copy.contactLastReport }}
                <time :datetime="item.lastObservedAt">{{ formatDateTime(item.lastObservedAt, 'Asia/Shanghai') }}</time>
              </span>
              <span v-else class="contact-observed">{{ copy.contactNoReport }}</span>
            </div>
            <ElButton v-if="item.kind === 'DOOR'" link :aria-label="`${item.name} ${copy.viewDoorStats}`" @click="showDoorStats(item)">
              {{ copy.viewDoorStats }}
            </ElButton>
          </li>
        </ul>
      </section>
    </ElDrawer>
  </div>
</template>

<style scoped>
.door-header-tag {
  color: var(--bec-color-text-secondary);
  font-size: var(--bec-comfort-small);
  white-space: nowrap;
}
.contact-summaries {
  display: inline-flex;
  align-items: center;
  gap: var(--bec-ref-space-6);
  max-width: 100%;
  padding: var(--bec-ref-space-2) var(--bec-ref-space-6);
  color: var(--bec-color-text-secondary);
  font: inherit;
  font-size: var(--bec-ref-comfort-font-sub);
  background: var(--bec-color-surface-secondary);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-radius-control);
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
}
.contact-summaries:hover {
  border-color: var(--bec-color-brand-primary);
  color: var(--bec-color-brand-primary);
}
.contact-summary { display: inline-flex; align-items: center; gap: var(--bec-ref-space-3); }
.contact-summary strong { color: var(--bec-color-text-primary); font-weight: var(--bec-font-weight-heading); }
.contact-detail-group { margin-bottom: var(--bec-space-group); }
.contact-detail-group h3 { margin: 0 0 var(--bec-ref-space-8); font-size: var(--bec-font-size-body); }
.contact-details { display: grid; gap: var(--bec-ref-space-8); margin: 0; padding: 0; list-style: none; }
.contact-details li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--bec-ref-space-8);
  align-items: start;
  padding: var(--bec-ref-space-8);
  border: var(--bec-border-width) solid var(--bec-color-border);
  border-radius: var(--bec-radius-control);
  overflow-wrap: anywhere;
}
.contact-reading { margin-inline-start: var(--bec-ref-space-8); font-weight: var(--bec-font-weight-heading); }
.contact-reading[data-state='open'] { color: var(--bec-color-warning); }
.contact-reading[data-state='closed'] { color: var(--bec-color-success); }
.contact-observed { display: block; color: var(--bec-color-text-secondary); font-size: var(--bec-comfort-small); }
.comfort-door-split, .door-pie-layout {
  display: grid;
  grid-template-columns: minmax(0, .94fr) minmax(0, 1.06fr);
  flex: 1;
  min-height: 0;
  gap: var(--bec-ref-space-8);
}
.comfort-door-state {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: var(--bec-ref-space-6);
  min-width: 0;
  min-height: 0;
}
.door-hero, .door-event-box, .comfort-door-table {
  min-width: 0;
  min-height: 0;
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-ref-radius-6);
}
.door-hero {
  display: flex;
  align-items: center;
  gap: var(--bec-ref-space-8);
  padding: var(--bec-ref-space-6) var(--bec-ref-space-8);
  background: var(--bec-color-surface-secondary);
  --bec-door-color: var(--bec-ref-disabled);
}
.door-hero[data-door-state='open'] { --bec-door-color: var(--bec-color-warning); }
.door-hero[data-door-state='closed'] { --bec-door-color: var(--bec-color-success); }
.door-hero-text { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--bec-ref-space-2); min-width: 0; flex: 1; }
.door-hero-title { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-ref-space-2) var(--bec-ref-space-4); min-width: 0; }
.door-selected-name {
  font-size: var(--bec-ref-comfort-font-sub);
  color: var(--bec-color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.door-hero-title strong {
  color: var(--bec-color-text-secondary);
  font-size: var(--bec-ref-comfort-font-door-state);
  font-weight: var(--bec-ref-comfort-weight-bold);
  line-height: var(--bec-ref-comfort-line-snug);
  overflow-wrap: anywhere;
}
.door-hero[data-door-state='open'] .door-hero-title strong,
.door-hero[data-door-state='closed'] .door-hero-title strong { color: var(--bec-door-color); }
.door-status-chip {
  flex: none;
  padding: 0 var(--bec-ref-space-4);
  border: var(--bec-border-width) solid color-mix(in srgb, var(--bec-color-warning) 35%, transparent);
  border-radius: var(--bec-radius-control);
  background: var(--bec-ref-comfort-alarm-warn-bg);
  color: var(--bec-color-warning);
  font-size: var(--bec-ref-comfort-font-micro);
  font-weight: var(--bec-font-weight-heading);
  cursor: help;
  white-space: nowrap;
}
.door-observation-note {
  color: var(--bec-color-warning);
  font-size: var(--bec-comfort-small);
}
.door-hero-meta {
  font-family: var(--bec-font-family-number);
  font-size: var(--bec-ref-comfort-font-sub);
  color: var(--bec-color-text-secondary);
  overflow-wrap: anywhere;
}
.comfort-door-icon { flex: none; width: var(--bec-ref-comfort-door-icon); height: var(--bec-ref-space-44); fill: none; }
.comfort-door-frame { fill: var(--bec-color-surface); stroke: var(--bec-color-border); stroke-width: 2; }
.comfort-door-swing { stroke: var(--bec-ref-comfort-scene-outline); stroke-width: 1.2; }
.comfort-door-leaf { fill: var(--bec-door-color); fill-opacity: .88; stroke: var(--bec-door-color); }
.door-hero[data-door-state='unknown'] .comfort-door-leaf { fill: var(--bec-ref-comfort-scene-fixture); }
.comfort-door-handle { fill: var(--bec-color-surface); }
.door-event-box {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  overflow: hidden;
  background: var(--bec-color-surface-secondary);
}
.door-event-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--bec-ref-space-4);
  padding: var(--bec-ref-space-4) var(--bec-ref-space-8);
  color: var(--bec-color-text-secondary);
  font-size: var(--bec-ref-comfort-font-sub);
  background: var(--bec-color-surface);
  border-bottom: var(--bec-border-width) solid var(--bec-color-divider);
}
.door-event-heading h3 { margin: 0; font-size: inherit; font-weight: var(--bec-font-weight-heading); }
.door-event-heading span { font-family: var(--bec-font-family-number); white-space: nowrap; }
.door-events {
  display: flex;
  flex-direction: column;
  gap: var(--bec-ref-space-5);
  margin: 0;
  padding: var(--bec-ref-space-5);
  overflow-y: auto;
  scrollbar-width: thin;
  list-style: none;
}
.door-events li {
  display: grid;
  flex: none;
  grid-template-columns: auto auto minmax(0, 1fr);
  align-items: center;
  gap: var(--bec-ref-space-6);
  height: var(--bec-ref-comfort-door-event-height);
  padding-inline: var(--bec-ref-space-8);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-radius-control);
  background: var(--bec-color-surface);
  font-family: var(--bec-font-family-number);
  font-size: var(--bec-ref-font-11);
  font-variant-numeric: tabular-nums;
}
.door-events time { color: var(--bec-color-text-secondary); white-space: nowrap; }
.door-events strong { font-family: var(--bec-font-family-body); font-weight: var(--bec-font-weight-heading); white-space: nowrap; }
.door-event-duration {
  text-align: right;
  color: var(--bec-color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.door-open-text { color: var(--bec-color-warning); }
.door-closed-text { color: var(--bec-color-success); }
.comfort-door-table { overflow-y: auto; scrollbar-width: thin; background: var(--bec-color-surface); }
.comfort-door-table table { width: 100%; border-collapse: collapse; font-size: var(--bec-comfort-small); text-align: center; table-layout: fixed; }
.comfort-door-table thead { position: sticky; top: 0; z-index: 1; background: var(--bec-color-surface-secondary); }
.comfort-door-table thead th {
  padding: var(--bec-ref-space-5) var(--bec-ref-space-6);
  color: var(--bec-color-text-secondary);
  font-weight: var(--bec-font-weight-heading);
  border-bottom: var(--bec-border-width) solid var(--bec-color-border);
}
.comfort-door-table tbody th, .comfort-door-table td {
  padding: var(--bec-ref-space-4) var(--bec-ref-space-6);
  border-bottom: var(--bec-border-width) solid var(--bec-color-divider);
  font-family: var(--bec-font-family-number);
  font-weight: var(--bec-font-weight-normal);
  font-variant-numeric: tabular-nums;
}
.comfort-door-table tbody tr { height: var(--bec-ref-comfort-door-table-height); }
.comfort-door-table tbody tr:last-child th,
.comfort-door-table tbody tr:last-child td { border-bottom: 0; }
.comfort-door-table .door-long-open {
  color: var(--bec-color-warning);
  font-weight: var(--bec-ref-comfort-weight-bold);
  background: color-mix(in srgb, var(--bec-color-warning) 5%, var(--bec-color-surface));
}
.comfort-door-table .door-future-cell { color: var(--bec-color-text-disabled); }
.door-pie-layout {
  grid-template-columns: minmax(0, .96fr) minmax(0, 1.04fr);
  gap: var(--bec-ref-space-10);
  align-items: center;
  --bec-chart-series-1: var(--bec-color-success);
  --bec-chart-series-2: var(--bec-color-warning);
  --bec-chart-series-3: var(--bec-color-error);
}
.door-pie { position: relative; height: 100%; min-height: 0; }
.door-pie-total {
  position: absolute;
  inset: 0;
  display: grid;
  place-content: center;
  gap: var(--bec-ref-space-2);
  text-align: center;
  pointer-events: none;
}
.door-pie-total strong {
  color: var(--bec-color-text-primary);
  font-family: var(--bec-font-family-number);
  font-size: var(--bec-ref-comfort-font-pie-total);
  font-weight: var(--bec-ref-comfort-weight-bold);
  line-height: var(--bec-ref-comfort-line-tight);
  font-variant-numeric: tabular-nums;
}
.door-pie-total span { font-size: var(--bec-ref-font-11); color: var(--bec-color-text-secondary); }
.door-legend {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: var(--bec-ref-comfort-pie-gap);
  margin: 0;
  padding: 0;
  min-width: 0;
  list-style: none;
}
.door-legend li {
  flex: none;
  display: grid;
  gap: var(--bec-ref-space-5);
  padding: var(--bec-ref-space-7) var(--bec-ref-space-10);
  min-width: 0;
  border-radius: var(--bec-ref-radius-6);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  background: var(--bec-color-surface-secondary);
  font-size: var(--bec-comfort-small);
  --bec-door-bucket-color: var(--bec-color-success);
}
.door-legend li:nth-child(2) { --bec-door-bucket-color: var(--bec-color-warning); }
.door-legend li:nth-child(3) { --bec-door-bucket-color: var(--bec-color-error); }
.door-bucket-value { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-ref-space-6); min-width: 0; }
.door-bucket-value span {
  display: inline-flex;
  align-items: center;
  gap: var(--bec-ref-space-6);
  color: var(--bec-color-text-primary);
  font-weight: var(--bec-font-weight-heading);
  white-space: nowrap;
}
.door-bucket-value span::before {
  content: '';
  flex: none;
  width: var(--bec-ref-space-7);
  height: var(--bec-ref-space-7);
  border-radius: var(--bec-radius-tag);
  background: var(--bec-door-bucket-color);
}
.door-bucket-value strong {
  color: var(--bec-color-text-primary);
  font-family: var(--bec-font-family-number);
  font-weight: var(--bec-font-weight-heading);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.door-bucket-value small {
  margin-left: var(--bec-ref-space-4);
  font: inherit;
  font-weight: var(--bec-font-weight-normal);
  color: var(--bec-color-text-secondary);
}
.door-bucket-track {
  height: var(--bec-ref-space-5);
  overflow: hidden;
  border-radius: var(--bec-radius-tag);
  background: var(--bec-ref-comfort-track-bg);
}
.door-bucket-track > div { height: 100%; border-radius: inherit; background: var(--bec-door-bucket-color); }
.door-empty {
  display: grid;
  place-content: center;
  color: var(--bec-color-text-secondary);
  text-align: center;
  font-size: var(--bec-comfort-small);
}
.door-empty-full {
  flex: 1;
  min-height: 0;
  border: var(--bec-border-width) dashed var(--bec-color-border);
  border-radius: var(--bec-ref-radius-6);
  background: var(--bec-color-surface-secondary);
}
.door-empty-badge {
  display: inline-flex;
  align-items: center;
  padding: var(--bec-ref-space-2) var(--bec-ref-space-8);
  border: var(--bec-border-width) solid var(--bec-color-border);
  border-radius: var(--bec-radius-tag);
  background: var(--bec-color-surface);
  color: var(--bec-color-text-secondary);
  font-size: var(--bec-ref-comfort-font-sub);
  font-weight: var(--bec-font-weight-heading);
}
@media (height <= 790px) {
  .door-hero { gap: var(--bec-ref-space-4); padding: var(--bec-ref-space-4) var(--bec-ref-space-6); }
  .comfort-door-icon { width: var(--bec-ref-space-40); }
  .door-legend li { padding: var(--bec-ref-space-4) var(--bec-ref-space-7); gap: var(--bec-ref-space-3); }
}
</style>
