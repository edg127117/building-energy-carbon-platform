<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Bell, CircleAlert, ElSkeleton } from '@/shared/ui'
import { t } from '@/locales'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { useSession, type GrantedMenu } from '@/modules/auth/public'
import { alarmsApi } from '../api/alarms'
import type { AlarmCategory, AlarmSummary } from '../models/alarms'

const props = defineProps<{ buildingId: string }>()
const router = useRouter()
const session = useSession()
const text = (key: string, params: Record<string, string | number> = {}) => t(`alarms.${key}`, params)
const root = ref<HTMLElement | null>(null)
const summary = ref<AlarmSummary | null>(null)
const error = ref('')
const loading = ref(false)
const selected = ref<AlarmCategory | null>(null)
const tabButton = ref<HTMLButtonElement | null>(null)
const closeButton = ref<HTMLButtonElement | null>(null)
const overlayTarget = ref<HTMLElement | null>(null)
let generation = 0
let poll: ReturnType<typeof setInterval> | undefined
let idle: ReturnType<typeof setTimeout> | undefined
let returnFocus: HTMLElement | null = null

const activeTotal = computed(() => selected.value === 'DEVICE' ? summary.value?.deviceAnomalies ?? null : summary.value?.emissionWarnings ?? null)
const selectedGroups = computed(() => selected.value === 'DEVICE' ? summary.value?.deviceGroups ?? [] : summary.value?.emissionGroups ?? [])
const selectedUnit = computed(() => text(selected.value === 'DEVICE' ? 'deviceUnit' : 'eventUnit'))
const selectedReason = computed(() => selected.value === 'DEVICE' ? summary.value?.deviceReason : summary.value?.emissionReason)
const statisticTime = computed(() => selected.value === 'EMISSION' ? (summary.value?.period || '—') : (summary.value ? new Date(summary.value.updatedAt).toLocaleTimeString('zh-CN', { hour12: false }) : '—'))
const statisticLabel = computed(() => text(selected.value === 'EMISSION' ? 'assessmentMonth' : 'statisticTime'))
const canOpenEvents = computed(() => hasVisibleAlarmPage(session.menus))

function hasVisibleAlarmPage(menus: GrantedMenu[]): boolean {
  return menus.some(menu => {
    if (menu.status !== 1 || menu.visible !== 1) return false
    if (menu.menuType === 'C' && menu.path === '/operations/alarms/liveAlarms') return true
    return menu.children ? hasVisibleAlarmPage(menu.children) : false
  })
}

watch(() => props.buildingId, () => { void refresh(true) })
onMounted(() => {
  void refresh(true)
  poll = setInterval(() => { void refresh(false) }, 30_000)
  window.addEventListener('keydown', keydown)
})
onUnmounted(() => { generation++; clearInterval(poll); clearTimeout(idle); window.removeEventListener('keydown', keydown) })

async function refresh(clear = false) {
  const owner = ++generation
  error.value = ''
  if (clear) summary.value = null
  if (!props.buildingId) { summary.value = null; loading.value = false; return }
  loading.value = !summary.value
  try {
    const response = await alarmsApi.summary(props.buildingId)
    if (owner === generation && props.buildingId) summary.value = response
  } catch (reason) {
    if (owner === generation) { summary.value = null; error.value = requestErrorMessage(reason) }
  } finally { if (owner === generation) loading.value = false }
}

async function open(kind: AlarmCategory, trigger: HTMLElement) {
  returnFocus = trigger
  selected.value = kind
  const scene = root.value?.closest<HTMLElement>('.scene-layout')
  overlayTarget.value = scene ?? root.value
  await nextTick()
  ;(kind === 'DEVICE' ? tabButton.value : closeButton.value)?.focus()
  resetIdle()
}
function openFromEvent(kind: AlarmCategory, event: MouseEvent) {
  if (event.currentTarget instanceof HTMLElement) void open(kind, event.currentTarget)
}
function close() {
  clearTimeout(idle)
  selected.value = null
  nextTick(() => returnFocus?.focus())
}
function resetIdle() { clearTimeout(idle); if (selected.value) idle = setTimeout(close, 5 * 60_000) }
function keydown(event: KeyboardEvent) {
  if (!selected.value) return
  resetIdle()
  if (event.key === 'Escape') { event.preventDefault(); close(); return }
  if (event.key === 'Tab') {
    const buttons = overlayTarget.value?.querySelectorAll<HTMLElement>('.summary-dialog button:not(:disabled), .summary-dialog a[href]')
    if (!buttons?.length) return
    const first = buttons[0], last = buttons[buttons.length - 1]
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
  }
}
function goToEvents() {
  if (!canOpenEvents.value || !selected.value) return
  void router.push({ path: '/operations/alarms/liveAlarms', query: { buildingId: props.buildingId, type: selected.value } })
  close()
}
</script>

<template>
  <section ref="root" class="screen-alarm-summary" :aria-label="text('summaryAria')">
    <ElSkeleton v-if="loading && !summary && !error" :rows="2" animated />
    <p v-else-if="error" class="summary-error">{{ text('summaryUnavailable') }} <span>{{ error }}</span></p>
    <p v-else-if="!summary" class="summary-empty">{{ text('noSummary') }}</p>
    <template v-else>
      <button class="summary-card device" type="button" @click="openFromEvent('DEVICE', $event)"><span class="card-mark" aria-hidden="true"><CircleAlert /></span><strong>{{ summary.deviceAnomalies == null ? '—' : `${summary.deviceAnomalies}${text('deviceUnit')}` }}</strong><span class="card-label">{{ text('deviceAnomalies') }}</span></button>
      <button class="summary-card emission" type="button" @click="openFromEvent('EMISSION', $event)"><span class="card-mark" aria-hidden="true"><Bell /></span><strong>{{ summary.emissionWarnings == null ? '—' : `${summary.emissionWarnings}${text('eventUnit')}` }}</strong><span class="card-label">{{ text('emissionWarnings') }}</span></button>
    </template>
  </section>
  <Teleport v-if="selected" :to="overlayTarget ?? root">
    <div class="summary-overlay" @click.self="close" @pointermove="resetIdle" @pointerdown="resetIdle">
      <section class="summary-dialog" role="dialog" aria-modal="true" :aria-label="text('detail')" tabindex="-1">
        <header class="summary-tabs">
          <button ref="tabButton" :aria-pressed="selected === 'DEVICE'" :class="{ active: selected === 'DEVICE' }" type="button" @click="selected = 'DEVICE'; resetIdle()"><span class="tab-dot" aria-hidden="true"></span> {{ text('deviceSummaryTab') }} <strong>{{ text('summaryTabCount', { count: summary?.deviceAnomalies == null ? '—' : `${summary.deviceAnomalies}${text('deviceUnit')}` }) }}</strong></button>
          <button :aria-pressed="selected === 'EMISSION'" :class="{ active: selected === 'EMISSION' }" type="button" @click="selected = 'EMISSION'; resetIdle()"><span class="tab-dot" aria-hidden="true"></span> {{ text('emissionSummaryTab') }} <strong>{{ text('summaryTabCount', { count: summary?.emissionWarnings == null ? '—' : `${summary.emissionWarnings}${text('eventUnit')}` }) }}</strong></button>
          <button ref="closeButton" class="close-button" type="button" :aria-label="text('closed')" @click="close">{{ text('glyphClose') }}</button>
        </header>
        <div class="summary-body" aria-live="polite">
          <p class="summary-total">
            <strong>{{ selected === 'DEVICE' ? text('currentDeviceTotal') : text('currentEmissionTotal') }}</strong>
            <b v-if="activeTotal != null">{{ activeTotal }}</b>
            <b v-else class="unavailable">{{ text('literal10') }}</b>
            <span>{{ selectedUnit }}</span>
            <small v-if="selected === 'DEVICE' && activeTotal != null" class="scope-note">{{ text('summaryScopeNote') }}</small>
            <small class="summary-asof">{{ statisticLabel }} {{ statisticTime }}{{ text('glyphSeparator') }}{{ text('idleCloseNote') }}</small>
          </p>
          <ul v-if="activeTotal != null && activeTotal > 0 && selectedGroups.length">
            <li v-for="(group, index) in selectedGroups" :key="`${group.type}-${index}`" :class="{ 'has-count': group.count > 0, 'fault-group': group.type === 'FAULT', 'quality-group': group.type === 'QUALITY', 'value-group': group.type === 'VALUE' }"><div class="group-copy"><strong>{{ text(group.type === 'FAULT' ? 'classificationFault' : group.type === 'QUOTA' ? 'quotaProgressGroup' : group.type) }}</strong><small>{{ text(group.type === 'OFFLINE' ? 'offlineGroupHint' : group.type === 'FAULT' ? 'faultGroupHint' : group.type === 'QUALITY' ? 'qualityGroupHint' : group.type === 'VALUE' ? 'valueGroupHint' : group.type === 'QUOTA' ? 'quotaGroupHint' : 'emissionGroupHint') }}</small></div><span class="group-count">{{ group.count }} <small>{{ selectedUnit }}</small></span></li>
          </ul>
          <p v-else-if="activeTotal == null" class="summary-empty-state">
            {{ selectedReason || text('summaryUnavailable') }}
          </p>
          <p v-else class="summary-empty-state">
            {{ text(selected === 'DEVICE' ? 'noDeviceAlarms' : 'noEmissionAlarms') }}
          </p>
        </div>
        <footer><span>{{ selected === 'DEVICE' ? text('deviceSummaryNote') : text('emissionSummaryNote') }}</span><button v-if="canOpenEvents" type="button" class="events-link" @click="goToEvents">{{ text('viewEvents') }} <span aria-hidden="true">{{ text('glyphArrow') }}</span></button></footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.screen-alarm-summary {
  position:relative;
  display:grid;
  grid-template-columns:repeat(2,minmax(0,1fr));
  gap:var(--bec-space-group);
  min-width:0
}
.summary-card {
  display:grid;
  justify-items:center;
  gap:var(--bec-space-group);
  text-align:center;
  padding:var(--bec-panel-padding);
  border:var(--bec-border-width) solid var(--bec-color-border);
  border-radius:var(--bec-radius-card);
  background:var(--bec-color-surface);
  color:var(--bec-color-text-primary);
  cursor:pointer;
  font:inherit
}
.summary-card:focus-visible {
  outline:var(--bec-focus-width) solid var(--bec-color-action-primary);
  outline-offset:var(--bec-focus-offset)
}
.summary-card:hover {
  border-color:var(--bec-color-brand-primary);
  background:var(--bec-color-action-soft);
  box-shadow:var(--bec-shadow-focus);
}
.summary-card strong {
  font-size:var(--bec-ref-font-24);
  font-variant-numeric:tabular-nums;
  white-space:nowrap
}
.summary-card .card-mark {
  display:grid;
  place-items:center;
  width:var(--bec-ref-space-32);
  height:var(--bec-ref-space-32);
  border-radius:var(--bec-radius-control);
  background:var(--bec-color-action-soft)
}
.summary-card.device .card-mark {
  color:var(--bec-color-error);
  background:color-mix(in srgb,var(--bec-color-error) 12%,var(--bec-color-surface))
}
.summary-card.emission .card-mark {
  color:var(--bec-color-warning);
  background:color-mix(in srgb,var(--bec-color-warning) 14%,var(--bec-color-surface))
}
.card-label {
  white-space:nowrap
}
.card-mark :deep(svg) {
  width:var(--bec-ref-icon-24);
  height:var(--bec-ref-icon-24);
}
.summary-card.device {
  border-color:color-mix(in srgb,var(--bec-color-error) 35%,var(--bec-color-border))
}
.summary-card.emission {
  border-color:color-mix(in srgb,var(--bec-color-warning) 45%,var(--bec-color-border))
}
.summary-error,.summary-empty {
  grid-column:1/-1;
  color:var(--bec-color-text-secondary)
}
.summary-error span {
  display:block;
  color:var(--bec-color-error)
}
.summary-overlay {
  position:absolute;
  inset:0;
  z-index:var(--bec-layer-notices);
  display:grid;
  place-items:center;
  padding:var(--bec-monitor-padding);
  background:color-mix(in srgb,var(--bec-ref-ink) 37%,transparent);
  backdrop-filter:blur(var(--bec-ref-space-4))
}
.summary-dialog {
  --panel-blue:var(--bec-ref-blue);
  --panel-ink:color-mix(in srgb,var(--bec-ref-blue-active) 60%,var(--bec-ref-ink));
  --panel-muted:color-mix(in srgb,var(--bec-ref-muted) 86%,var(--panel-blue));
  box-sizing:border-box;
  width:min(calc(var(--bec-ref-space-64) * 11),100%);
  max-height:100%;
  overflow:auto;
  border:var(--bec-border-width) solid var(--bec-ref-border);
  border-radius:var(--bec-ref-space-12);
  background:var(--bec-ref-bg);
  color:var(--panel-ink);
  font:var(--bec-font-weight-normal) var(--bec-ref-font-14)/var(--bec-line-height) var(--bec-ref-family-body);
  box-shadow:var(--bec-shadow-overlay)
}
.summary-tabs,.summary-dialog footer {
  display:flex;
  align-items:center;
  gap:var(--bec-ref-space-12);
  background:linear-gradient(100deg,var(--bec-ref-blue-label),var(--bec-ref-blue-soft));
  padding:var(--bec-ref-space-16) var(--bec-ref-space-24);
  border-bottom:var(--bec-border-width) solid var(--bec-ref-border)
}
.summary-tabs button {
  display:inline-flex;
  align-items:center;
  justify-content:center;
  gap:var(--bec-ref-space-4);
  min-height:var(--bec-ref-space-40);
  padding:0 var(--bec-ref-space-16);
  border:var(--bec-border-width) solid var(--bec-ref-border);
  border-radius:var(--bec-ref-radius-8);
  background:var(--bec-ref-white);
  color:var(--panel-muted);
  font:inherit;
  font-size:var(--bec-ref-font-16);
  font-weight:var(--bec-font-weight-heading);
  white-space:nowrap;
  cursor:pointer
}
.summary-tabs button.active {
  color:var(--bec-ref-white);
  border-color:var(--panel-blue);
  background:var(--panel-blue);
  box-shadow:var(--bec-ref-shadow-card)
}
.tab-dot {
  width:var(--bec-ref-space-8);
  height:var(--bec-ref-space-8);
  border-radius:var(--bec-ref-radius-pill);
  background:currentColor
}
.summary-tabs .close-button {
  margin-left:auto;
  min-height:var(--bec-ref-space-32);
  width:var(--bec-ref-space-32);
  padding:0;
  color:var(--panel-ink)
}
.summary-tabs button:focus-visible,.events-link:focus-visible {
  outline:var(--bec-focus-width) solid var(--panel-blue);
  outline-offset:var(--bec-focus-offset)
}
.summary-body { padding:var(--bec-ref-space-20) var(--bec-ref-space-24) }
.summary-total {
  display:flex;
  flex-wrap:wrap;
  align-items:baseline;
  gap:var(--bec-ref-space-4) var(--bec-ref-space-8);
  margin:0 0 var(--bec-ref-space-12)
}
.summary-total b { font-size:var(--bec-ref-font-24); color:var(--bec-ref-red) }
.summary-total .unavailable { color:var(--panel-muted) }
.summary-total small,.group-copy small { color:var(--panel-muted); font-size:calc((var(--bec-ref-font-12) + var(--bec-ref-font-14)) / 2) }
.summary-asof { margin-left:auto }
.summary-body ul {
  display:grid;
  gap:var(--bec-ref-space-12);
  margin:0;
  padding:0;
  list-style:none
}
.summary-body li {
  --row-color:var(--bec-ref-disabled);
  position:relative;
  display:flex;
  align-items:center;
  justify-content:space-between;
  gap:var(--bec-ref-space-12);
  box-sizing:border-box;
  min-height:calc(var(--bec-ref-space-48) + var(--bec-ref-space-4));
  padding:var(--bec-ref-space-8) var(--bec-ref-space-16) var(--bec-ref-space-8) var(--bec-ref-space-20);
  overflow:hidden;
  border:var(--bec-border-width) solid var(--bec-ref-border);
  border-radius:var(--bec-ref-radius-8);
  background:var(--bec-ref-white)
}
.summary-body li.has-count { --row-color:var(--bec-ref-red) }
.summary-body li.has-count.fault-group { --row-color:var(--bec-ref-red) }
.summary-body li.has-count.quality-group { --row-color:var(--bec-ref-amber) }
.summary-body li.has-count.value-group { --row-color:var(--bec-ref-blue) }
.summary-body li.has-count::before {
  position:absolute;
  inset:0 auto 0 0;
  width:var(--bec-ref-space-4);
  content:'';
  background:var(--row-color)
}
.group-copy { display:flex; flex-wrap:wrap; align-items:baseline; gap:var(--bec-ref-space-8) }
.group-copy strong { font-size:var(--bec-ref-font-16) }
.group-count {
  color:var(--row-color);
  font-size:var(--bec-ref-font-20);
  font-weight:var(--bec-font-weight-heading);
  font-variant-numeric:tabular-nums;
  white-space:nowrap
}
.group-count small { font-size:var(--bec-ref-font-14) }
.summary-empty-state {
  margin:0;
  padding:var(--bec-ref-space-16);
  border:var(--bec-border-width) solid var(--bec-ref-border);
  border-radius:var(--bec-ref-radius-8);
  background:var(--bec-ref-white);
  color:var(--panel-muted);
  text-align:center
}
.summary-dialog footer {
  justify-content:space-between;
  padding-block:var(--bec-ref-space-12);
  border-top:var(--bec-border-width) solid var(--bec-ref-border);
  border-bottom:0;
  color:var(--panel-muted);
  background:var(--bec-ref-blue-soft);
  font-size:var(--bec-ref-font-12)
}
.events-link {
  padding:0;
  border:0;
  background:transparent;
  color:var(--panel-blue);
  font:inherit;
  font-size:var(--bec-ref-font-14);
  font-weight:var(--bec-font-weight-heading);
  cursor:pointer;
  white-space:nowrap
}
@media(max-width:600px) {
  .screen-alarm-summary {
    grid-template-columns:1fr
  }
  .summary-tabs {
    flex-wrap:wrap
  }
  .summary-total {
    flex-wrap:wrap
  }
  .summary-dialog footer {
    align-items:flex-start;
    flex-direction:column
  }
}
</style>
