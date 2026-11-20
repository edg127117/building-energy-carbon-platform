<script setup lang="ts">
import { computed, ref } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import { ElPagination, ElPopover } from '@/shared/ui'
import { formatDateTime } from '@/shared/utils/format'
import { messages as alarmCopy, type AlarmEvent, type AlarmEventPage } from '@/modules/alarms/public'
import { messages as dashboardCopy } from '@/modules/dashboard/public'
import { t } from '@/locales'
import copy from '../locales/zh-CN'
import { formatDoorElapsed } from '../models/sensor-charts'

const props = defineProps<{
  room: string
  summary: AlarmEventPage | null
  detail: AlarmEventPage | null
  loading: boolean
  error: string
  detailLoading: boolean
  detailError: string
  pageSize: number
  fullPage: RouteLocationRaw | null
}>()
const page = defineModel<number>('page', { required: true })
const popover = ref<InstanceType<typeof ElPopover> | null>(null)
const first = computed(() => props.summary?.items[0])
const additional = computed(() => Math.max((props.summary?.total ?? 0) - 1, 0))
const emptyStatus = computed(() => props.error || (props.loading ? t('common.loading') : copy.roomNoAlarms))
const alarmClock = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
})
const firstClock = computed(() => first.value ? alarmClock.format(new Date(first.value.firstAt)) : '')

function metric(event: AlarmEvent) {
  try {
    const evidence = JSON.parse(event.evidence)
    const observation = Array.isArray(evidence?.observations)
      ? evidence.observations.find(item => typeof item?.pointName === 'string'
        && typeof item.value === 'number' && Number.isFinite(item.value))
      : undefined
    if (observation) {
      const unit = typeof observation.unit === 'string' ? observation.unit : ''
      if (event.ruleId === 'comfort-door-timeout' && unit === alarmCopy.secondsUnit) {
        return `${observation.pointName} ${formatDoorElapsed(observation.value)}`
      }
      return [observation.pointName, observation.value, unit].filter(part => part !== '').join(' ')
    }
    if (typeof evidence?.sourceCode === 'string') {
      const labels: Record<string, string> = dashboardCopy.daikin.statusNames
      return Object.prototype.hasOwnProperty.call(labels, evidence.sourceCode) ? labels[evidence.sourceCode] : evidence.sourceCode
    }
  } catch {
    // 旧事件或部分模板没有结构化观测值，展示原事件描述。
  }
  return ''
}
</script>

<template>
  <ElPopover
    v-if="first" ref="popover" trigger="click" placement="bottom-start" role="dialog"
    :title="copy.roomAlarmDetails(room)" :teleported="false" :persistent="false"
    width="var(--bec-screen-popover-width)" @after-leave="page = 1"
  >
    <template #reference>
      <button
        type="button" class="room-alarm-pill" :data-severity="first.severity"
        :title="first.description" @keydown.esc="popover?.hide()"
      >
        <span class="room-alarm-marker" aria-hidden="true">{{ copy.alarmMarker }}</span>
        <span class="room-alarm-summary">{{ [room, first.objectName, metric(first), first.description].filter(Boolean).join(copy.dotSeparator) }}</span>
        <time v-if="firstClock" class="room-alarm-time" :datetime="new Date(first.firstAt).toISOString()">{{ firstClock }}</time>
        <span v-if="additional" class="room-alarm-extra">{{ copy.moreAlarms(additional) }}</span>
        <span class="room-alarm-expand" aria-hidden="true">{{ copy.alarmExpand }}</span>
      </button>
    </template>
    <section class="room-alarm-detail" @keydown.esc="popover?.hide()">
      <p v-if="detailError || (detailLoading && !detail)" class="room-alarm-state" role="status">
        {{ detailError || t('common.loading') }}
      </p>
      <ul class="room-alarm-list">
        <li v-for="event in detail?.items" :key="event.id" class="room-alarm-item">
          <div class="room-alarm-item-title">
            <strong>{{ event.objectName }}</strong>
            <span class="room-alarm-level" :data-severity="event.severity">{{ alarmCopy[event.severity] }}</span>
          </div>
          <span>{{ [metric(event), event.description].filter(Boolean).join(copy.dotSeparator) }}</span>
          <time :datetime="new Date(event.firstAt).toISOString()">{{ formatDateTime(event.firstAt, 'Asia/Shanghai') }}</time>
        </li>
      </ul>
      <ElPagination
        v-if="(summary?.total ?? 0) > pageSize" v-model:current-page="page" size="small" layout="prev, pager, next"
        :page-size="pageSize" :total="summary?.total ?? 0"
      />
      <RouterLink v-if="fullPage" class="room-alarm-link" :to="fullPage">{{ copy.openAlarmPage }}</RouterLink>
    </section>
  </ElPopover>
  <span v-if="!first || error" class="room-alarm-state" :class="{ 'room-alarm-empty': !error && !loading }" role="status">
    <span v-if="!error && !loading" aria-hidden="true">{{ copy.roomOkMark }}</span>
    <span>{{ emptyStatus }}</span>
  </span>
</template>

<style scoped>
.room-alarm-pill {
  display: inline-flex;
  align-items: center;
  gap: var(--bec-ref-space-5);
  max-width: min(100%, var(--bec-dialog-width));
  padding: var(--bec-ref-space-2) var(--bec-ref-space-8);
  border: var(--bec-border-width) solid color-mix(in srgb, currentColor 28%, transparent);
  border-radius: var(--bec-radius-control);
  background: var(--bec-color-surface);
  color: var(--bec-color-text-secondary);
  font: inherit;
  font-size: var(--bec-comfort-small);
  font-weight: var(--bec-font-weight-heading);
  line-height: var(--bec-ref-comfort-line-snug);
  cursor: pointer;
}
.room-alarm-pill[data-severity="URGENT"] {
  background: var(--bec-ref-comfort-alarm-danger-bg);
  color: var(--bec-color-error);
}
.room-alarm-level[data-severity="URGENT"] { color: var(--bec-color-error); }
.room-alarm-pill[data-severity="IMPORTANT"], .room-alarm-pill[data-severity="WARNING"] {
  background: var(--bec-ref-comfort-alarm-warn-bg);
  color: var(--bec-color-warning);
}
.room-alarm-level[data-severity="IMPORTANT"], .room-alarm-level[data-severity="WARNING"] { color: var(--bec-color-warning); }
.room-alarm-summary { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.room-alarm-time {
  flex: none;
  font-family: var(--bec-font-family-number);
  font-size: var(--bec-ref-comfort-font-sub);
  font-weight: var(--bec-font-weight-normal);
  opacity: .82;
}
.room-alarm-marker, .room-alarm-extra, .room-alarm-expand { flex: none; }
.room-alarm-detail { display: grid; gap: var(--bec-space-tight); }
.room-alarm-list {
  display: grid;
  gap: var(--bec-space-tight);
  max-height: var(--bec-screen-popover-width);
  margin: 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}
.room-alarm-item {
  display: grid;
  gap: var(--bec-ref-space-3);
  padding: var(--bec-ref-space-6);
  background: var(--bec-color-surface-secondary);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-radius-control);
  overflow-wrap: anywhere;
}
.room-alarm-item-title { display: flex; justify-content: space-between; gap: var(--bec-space-tight); }
.room-alarm-item time, .room-alarm-state { color: var(--bec-color-text-secondary); font-size: var(--bec-comfort-small); }
.room-alarm-empty {
  display: inline-flex;
  align-items: center;
  gap: var(--bec-ref-space-4);
  padding: var(--bec-ref-space-2) var(--bec-ref-space-8);
  border: var(--bec-border-width) solid color-mix(in srgb, currentColor 24%, transparent);
  border-radius: var(--bec-radius-control);
  background: var(--bec-ref-comfort-alarm-ok-bg);
  color: var(--bec-color-success);
  font-weight: var(--bec-font-weight-heading);
  line-height: var(--bec-ref-comfort-line-snug);
  white-space: nowrap;
}
.room-alarm-link { justify-self: end; color: var(--bec-color-brand-primary); }
</style>
