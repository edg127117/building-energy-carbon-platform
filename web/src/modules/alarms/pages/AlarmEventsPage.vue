<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElEmpty, ElInput, ElInputNumber, ElOption, ElPagination, ElSelect, ElSkeleton, ElMessage } from '@/shared/ui'
import ChartView from '@/shared/charts/ChartView.vue'
import type { ChartOption } from '@/shared/charts/echarts'
import { t } from '@/locales'
import { formatDateTime } from '@/shared/utils/format'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { DaikinAlarmsPage } from '@/modules/dashboard/public'
import { alarmsApi } from '../api/alarms'
import type { AlarmBuilding, AlarmCategory, AlarmEvent, AlarmEventPage, AlarmSummary, AlarmType, TrendSeries } from '../models/alarms'

const route = useRoute()
const router = useRouter()
const text = (key: string, params?: Record<string, string | number>) => t(`alarms.${key}`, params)
const buildings = ref<AlarmBuilding[]>([])
const buildingId = ref('')
const category = ref<AlarmCategory>(route.query.type === 'EMISSION' ? 'EMISSION' : 'DEVICE')
const stateFilter = ref<'UNACK' | 'ACK' | 'SILENCED' | 'ACTIVE' | 'RECOVERED' | 'CLOSED' | ''>('')
const typeFilter = ref<AlarmType | ''>('')
const keyword = ref('')
const fromAtInput = ref('')
const toAtInput = ref('')
const page = ref(1)
const result = ref<AlarmEventPage | null>(null)
const summary = ref<AlarmSummary | null>(null)
const summaryError = ref('')
const loading = ref(false)
const error = ref('')
const drawerEvent = ref<AlarmEvent | null>(null)
const noteText = ref('')
const silenceMinutes = ref(60)
const trendSeries = ref<TrendSeries[]>([])
const trendLoading = ref(false)
const trendError = ref('')
const trendOptions = computed<ChartOption[]>(() => trendSeries.value.map(series => ({
  tooltip:{trigger:'axis'},grid:{left:'12%',right:'5%',top:'15%',bottom:'15%'},
  xAxis:{type:'time'},yAxis:{type:'value',name:series.unit},
  series:[{name:series.pointName,type:'line',step:'end',showSymbol:true,connectNulls:false,
    data:series.samples.map(sample=>[sample.at,sample.value])}],
})))
async function loadTrend() {
  const event=drawerEvent.value
  if(!event) return
  const owner=detailGeneration
  const now=Date.now(),from=Math.max(0,event.firstAt-30*60_000),to=Math.min(now,Math.max(event.firstAt+30*60_000,from+60_000))
  trendLoading.value=true; trendError.value=''
  try { const rows=await alarmsApi.trend(event.buildingId,event.id,from,to); if(owner===detailGeneration) trendSeries.value=rows }
  catch(reason) {if(owner===detailGeneration)trendError.value=requestErrorMessage(reason)}
  finally {if(owner===detailGeneration)trendLoading.value=false}
}
const drawerLoading = ref(false)
const drawerError = ref('')
const actionBusy = ref(false)
const drawerRef = ref<HTMLElement | null>(null)
const closeButton = ref<HTMLButtonElement | null>(null)
const legacyOpen = ref(false)
const history = computed(() => route.path.endsWith('/historyAlarms'))
const buildingName = computed(() => buildings.value.find(item => item.buildingId === buildingId.value)?.buildingName ?? '')
let generation = 0
let detailGeneration = 0
let summaryGeneration = 0
let searchTimer: ReturnType<typeof setTimeout> | undefined
let liveRefresh: ReturnType<typeof setInterval> | undefined
let returnFocus: HTMLElement | null = null

watch(() => route.query.buildingId, value => {
  const id = typeof value === 'string' ? value : ''
  if (id && id !== buildingId.value && buildings.value.some(building => building.buildingId === id)) void changeBuilding(id)
})
watch(() => route.query.type, value => { if (value === 'DEVICE' || value === 'EMISSION') selectCategory(value) })
watch(() => route.path, () => { stateFilter.value=''; page.value = 1; syncLiveRefresh(); void loadBuildings() })
watch([category, page, stateFilter, typeFilter, fromAtInput, toAtInput], () => { void loadEvents() })
watch([stateFilter, typeFilter, fromAtInput, toAtInput], () => { page.value = 1 })
watch([category, typeFilter], () => { if (drawerEvent.value) closeDrawer() })
watch(keyword, () => {
  clearTimeout(searchTimer); generation++; result.value = null; loading.value = Boolean(buildingId.value)
  searchTimer = setTimeout(() => { page.value = 1; void loadEvents() }, 250)
})
onMounted(() => { void loadBuildings(); syncLiveRefresh() })
onUnmounted(() => { generation++; detailGeneration++; summaryGeneration++; clearTimeout(searchTimer); clearInterval(liveRefresh) })

function syncLiveRefresh() {
  clearInterval(liveRefresh)
  liveRefresh = history.value ? undefined : setInterval(() => {
    void loadEvents(true)
    if (buildingId.value) void loadSummary(buildingId.value, true)
  }, 30_000)
}

async function loadBuildings() {
  const owner = ++generation
  detailGeneration++; summaryGeneration++; drawerEvent.value = null; drawerError.value = ''; summary.value = null; summaryError.value = ''; loading.value = true; error.value = ''; result.value = null; buildings.value = []; buildingId.value = ''
  try {
    const response = await alarmsApi.buildings(history.value ? 'history' : 'live')
    if (owner !== generation) return
    buildings.value = response
    const queryId = typeof route.query.buildingId === 'string' ? route.query.buildingId : ''
    const selected = response.some(item => item.buildingId === queryId) ? queryId : response[0]?.buildingId ?? ''
    buildingId.value = selected
    if (selected && selected !== queryId) await router.replace({ path: route.path, query: { ...route.query, buildingId: selected } })
    if (owner === generation && selected) {
      if (!history.value) void loadSummary(selected)
      await fetchEvents(selected, owner)
    }
  } catch (reason) {
    if (owner === generation) { buildings.value = []; buildingId.value = ''; result.value = null; error.value = requestErrorMessage(reason) }
  } finally { if (owner === generation) loading.value = false }
}

async function changeBuilding(next: string) {
  if (!next || next === buildingId.value) return
  generation++; detailGeneration++; summaryGeneration++; buildingId.value = next; result.value = null; summary.value = null; summaryError.value = ''; drawerEvent.value = null; page.value = 1; error.value = ''
  loading.value = true
  await router.replace({ path: route.path, query: { ...route.query, buildingId: next } })
  const owner = generation
  if (!history.value) void loadSummary(next)
  await fetchEvents(next, owner)
  if (owner === generation) loading.value = false
}

async function loadSummary(id: string, retain = false) {
  const owner = ++summaryGeneration
  if (!retain) { summary.value = null; summaryError.value = '' }
  try {
    const response = await alarmsApi.summary(id)
    if (owner === summaryGeneration && id === buildingId.value && !history.value) summary.value = response
  } catch (reason) {
    if (owner === summaryGeneration && id === buildingId.value) { summary.value = null; summaryError.value = requestErrorMessage(reason) }
  }
}

async function loadEvents(retain = false) {
  if (!buildingId.value) return
  const owner = ++generation
  loading.value = !(retain && result.value); error.value = ''
  if (!retain) result.value = null
  const fromAt = inputMilliseconds(fromAtInput.value)
  const toAt = inputMilliseconds(toAtInput.value)
  if (fromAt != null && toAt != null && fromAt >= toAt) {
    result.value = null; error.value = text('invalidDateRange'); loading.value = false; return
  }
  await fetchEvents(buildingId.value, owner, retain)
  if (owner === generation) loading.value = false
}
function manualRefresh() { void loadEvents(); if (!history.value && buildingId.value) void loadSummary(buildingId.value) }

async function fetchEvents(id: string, owner: number, retain = false) {
  error.value = ''; if (!retain) result.value = null
  try {
    const response = await alarmsApi.events(id, { history: history.value, type: category.value, page: page.value, size: 20, state: stateFilter.value || undefined, alarmType: typeFilter.value || undefined, keyword: keyword.value, fromAt: inputMilliseconds(fromAtInput.value), toAt: inputMilliseconds(toAtInput.value) })
    if (owner === generation && id === buildingId.value) result.value = response
  } catch (reason) { if (owner === generation && id === buildingId.value) error.value = requestErrorMessage(reason) }
}

async function openEvent(row: AlarmEvent) {
  returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  drawerEvent.value = null; drawerError.value = ''; noteText.value = ''; silenceMinutes.value=60; trendSeries.value=[]; trendError.value=''; trendLoading.value=false; drawerLoading.value = true
  const owner = ++detailGeneration
  try {
    const event = await alarmsApi.event(row.buildingId, row.id)
    if (owner === detailGeneration) { drawerEvent.value = event; await nextTick(); closeButton.value?.focus() }
  } catch (reason) { if (owner === detailGeneration) drawerError.value = requestErrorMessage(reason) }
  finally { if (owner === detailGeneration) drawerLoading.value = false }
}
function closeDrawer() { detailGeneration++; trendSeries.value=[]; trendError.value=''; trendLoading.value=false; drawerEvent.value = null; drawerError.value = ''; drawerLoading.value = false; nextTick(() => returnFocus?.focus()) }
function keydown(event: KeyboardEvent) {
  if (!drawerEvent.value) return
  if (event.key === 'Escape') { event.preventDefault(); closeDrawer(); return }
  if (event.key === 'Tab' && drawerRef.value) {
    const targets = [...drawerRef.value.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),textarea:not(:disabled),[tabindex="0"]')]
    const first = targets[0], last = targets.at(-1)
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
  }
}
onMounted(() => window.addEventListener('keydown', keydown))
onUnmounted(() => window.removeEventListener('keydown', keydown))

async function performAction(action: 'ack' | 'note' | 'silence' | 'close') {
  const event = drawerEvent.value
  if (!event || actionBusy.value) return
  if (!['ack','close'].includes(action) && !noteText.value.trim()) { drawerError.value = text('noteRequired'); return }
  const actionOwner = detailGeneration
  const submittedNote = noteText.value
  actionBusy.value = true; drawerError.value = ''
  try {
    if (action === 'ack') await alarmsApi.acknowledge(event.buildingId, event.id)
    if (action === 'note') await alarmsApi.note(event.buildingId, event.id, noteText.value.trim())
    if (action === 'silence') await alarmsApi.silence(event.buildingId, event.id, noteText.value.trim(),silenceMinutes.value)
    if(action==='close') await alarmsApi.close(event.buildingId,event.id,noteText.value.trim())
    if (actionOwner !== detailGeneration || buildingId.value !== event.buildingId) return
    ElMessage.success(text(action === 'ack' ? 'acknowledged' : action === 'note' ? 'addedNote' : action==='close'?'CLOSED':'silenced'))
    if (action !== 'ack' && noteText.value === submittedNote) noteText.value = ''
    await Promise.all([loadEvents(), refreshDrawerEvent(event)])
  } catch (reason) { drawerError.value = requestErrorMessage(reason) }
  finally { actionBusy.value = false }
}
async function refreshDrawerEvent(event: AlarmEvent) {
  const owner = ++detailGeneration
  drawerLoading.value = true
  try { const latest = await alarmsApi.event(event.buildingId, event.id); if (owner === detailGeneration) drawerEvent.value = latest }
  catch (reason) { if (owner === detailGeneration) drawerError.value = requestErrorMessage(reason) }
  finally { if (owner === detailGeneration) drawerLoading.value = false }
}
function inputMilliseconds(value: string): number | undefined { if (!value) return undefined; const time = new Date(value).getTime(); return Number.isFinite(time) ? time : undefined }

function typeLabel(type: AlarmType,period?:string|null) { return text(type==='QUOTA'&&period?.includes('/')?'annualQuotaWarning':type) }
function eventState(event: AlarmEvent) {
  if(event.status==='CLOSED') return text('CLOSED')
  if (event.status === 'RECOVERED') return text('recovered')
  if (event.silencedUntil != null && event.silencedUntil > Date.now()) return text('maintenanceMutedStatus')
  return event.ackAt != null ? text('acknowledged') : text('pendingAction')
}
function selectCategory(next: AlarmCategory) {
  if (category.value === next) return
  typeFilter.value = ''
  stateFilter.value = ''
  page.value = 1
  category.value = next
  closeDrawer()
}
function elapsed(event: AlarmEvent) {
  const end = event.recoveredAt ?? Date.now()
  return text('durationMinutes', { minutes: Math.max(0, Math.floor((end - event.firstAt) / 60000)) })
}
function evidenceValue(event: AlarmEvent, label: string) {
  return evidenceRows(event.evidence).find(row => row.label === text(label))?.value ?? t('common.missing')
}
function inlineCommunication(event: AlarmEvent) {
  return evidenceValue(event, 'lastCommunication')
}
async function acknowledgeRow(event: AlarmEvent) {
  await openEvent(event)
  if (drawerEvent.value?.id === event.id && drawerEvent.value.buildingId === event.buildingId) await performAction('ack')
}
interface EvidenceRow { label: string; value: string }
function evidenceRows(raw: string): EvidenceRow[] {
  const labels: Record<string, string> = {
    fromMonth:text('periodFrom'),toMonth:text('periodTo'),value:text('sourceValue'),reference:text('referenceValue'),evaluatedValue:text('evaluatedValue'),threshold:text('threshold'),recoveryThreshold:text('recoveryThreshold'),forecastTCO2e:text('forecastAmount'),annualQuotaTCO2e:text('annualQuota'),forecastElapsedDays:text('elapsedDays'),
    month: text('evaluationMonth'), actual: text('actualEmission'), quota: text('monthlyQuota'), ratioPercent: text('quotaUsage'), thresholdPercent: text('warningThreshold'),
    lastCommunicationAt: text('lastCommunication'), monitoringSince: text('monitoringSince'), intervalSeconds: text('reportingCycle'), allowedDelaySeconds: text('allowedDelay'), sourceType: text('faultSource'), qualityLevel: text('scopeQuality'), triggerMinutes: text('triggerDurationLabel'), recoveryMinutes: text('recoveryDurationLabel'), recoveryCycles: text('recoveryCyclesLabel'), unavailableReason: text('unavailableReason'),
  }
  const sourceLabels: Record<string, string> = { VENDOR_EQUIPMENT: text('equipmentSide'), VENDOR_COMMUNICATION: text('communicationSide') }
  try {
    const evidence = JSON.parse(raw) as Record<string, unknown>
    if (!evidence || typeof evidence !== 'object' || Array.isArray(evidence)) return []
    const rows: EvidenceRow[] = []
    for (const [key, label] of Object.entries(labels)) {
      const value = evidence[key]
      if (value == null || (typeof value !== 'string' && typeof value !== 'number')) continue
      if (key === 'qualityLevel') rows.push({ label, value: text(({ Q0: 'formalQuality', Q1: 'monitorQuality', Q2: 'baseQuality' } as Record<string, string>)[String(value)] ?? 'literal10') })
      else if(['evaluatedValue','threshold','recoveryThreshold'].includes(key)) rows.push({label,value:`${value} ${typeof evidence.evaluatedUnit==='string'?evidence.evaluatedUnit:''}`.trim()})
      else if(['value','reference'].includes(key)) rows.push({label,value:`${value} ${typeof evidence.unit==='string'?evidence.unit:''}`.trim()})
      else if(['forecastTCO2e','annualQuotaTCO2e'].includes(key)) rows.push({label,value:`${value} tCO2e`})
      else if (['triggerMinutes','recoveryMinutes'].includes(key)) rows.push({ label, value: String(value) + text('minutesSuffix') })
      else if (key === 'sourceType') rows.push({ label, value: sourceLabels[String(value)] ?? text('otherSource') })
      else if (['lastCommunicationAt', 'monitoringSince'].includes(key) && typeof value === 'number') rows.push({ label, value: formatDateTime(value) })
      else if (['intervalSeconds', 'allowedDelaySeconds'].includes(key)) rows.push({ label, value: `${value} ${text('secondsUnit')}` })
      else if (['actual', 'quota'].includes(key)) rows.push({ label, value: `${value} ${typeof evidence.unit === 'string' ? evidence.unit : 'tCO2e'}` })
      else if (['ratioPercent', 'thresholdPercent'].includes(key)) rows.push({ label, value: `${value}%` })
      else rows.push({ label, value: String(value) })
    }
    if (Array.isArray(evidence.observations)) {
      for (const [index, item] of evidence.observations.entries()) {
        if (!item || typeof item !== 'object') continue
        const observation = item as Record<string, unknown>
        const name = typeof observation.pointName === 'string' ? observation.pointName : text('observationItem', { number: index + 1 })
        const value = observation.value == null ? t('common.missing') : String(observation.value)
        const unit = typeof observation.unit === 'string' ? observation.unit : ''
        const qualityValue=observation.dataQuality ?? observation.quality
        const quality = typeof qualityValue === 'number' ? text('observationQuality', { level: qualityValue }) : ''
        rows.push({ label: name, value: [value, unit, quality].filter(Boolean).join(' ') })
      }
    }
    return rows
  } catch { return [] }
}
function logAction(action: string) {
  const labels: Record<string, string> = { CLOSED:text('CLOSED'), ACK: text('acknowledged'), ACKNOWLEDGED: text('acknowledged'), NOTE: text('noteAction'), NOTE_ADDED: text('noteAction'), SILENCE: text('silenced'), SILENCED: text('silenced'), TRIGGERED: text('TRIGGERED'), RECOVERED: text('RECOVERED'), LEVEL_CHANGED: text('LEVEL_CHANGED') }
  return labels[action] ?? text('otherAction')
}

</script>

<template>
  <section class="alarms-page" :class="{ 'drawer-open': drawerEvent || drawerLoading || drawerError }">
    <header class="page-heading">
      <div>
        <h1>{{ text(history ? 'historyTitle' : 'title') }}</h1>
        <p>{{ text('workspaceLocation') }}{{ text('glyphSeparator') }}{{ text('subtitle') }}</p>
      </div>
      <RouterLink class="history-link" :to="{ path: history ? '/operations/alarms/liveAlarms' : '/operations/alarms/historyAlarms', query: { ...route.query, type: category } }">
        {{ text(history ? 'current' : 'history') }}{{ text('glyphArrow') }}
      </RouterLink>
    </header>

    <div class="category-cards" :aria-label="text('type')">
      <button type="button" :aria-pressed="category === 'DEVICE'" :class="{ selected: category === 'DEVICE' }" @click="selectCategory('DEVICE')">
        <div class="category-copy">
          <strong><span aria-hidden="true">{{ text('glyphDevice') }}</span> {{ text('deviceMonitor') }} <span class="view-hint">{{ text(category === 'DEVICE' ? 'currentView' : 'switchView') }}</span></strong>
          <small>{{ summaryError ? text('summaryUnavailable') : summary?.deviceReason || text('deviceMonitorHint') }}{{ text('glyphSeparator') }}{{ text('scopeNote') }}</small>
        </div>
        <div v-if="!history" class="category-total device-total"><b>{{ summary?.deviceAnomalies ?? '—' }}</b><span>{{ text('deviceUnit') }}{{ text('anomaly') }}</span></div>
      </button>
      <button type="button" :aria-pressed="category === 'EMISSION'" :class="{ selected: category === 'EMISSION' }" @click="selectCategory('EMISSION')">
        <div class="category-copy">
          <strong><span aria-hidden="true">{{ text('glyphEmission') }}</span> {{ text('emissionMonitor') }} <span class="view-hint">{{ text(category === 'EMISSION' ? 'currentView' : 'switchView') }}</span></strong>
          <small>{{ summaryError ? text('summaryUnavailable') : summary?.emissionReason || text('emissionMonitorHint') }}<template v-if="summary?.period">{{ text('glyphSeparator') }}{{ summary.period }}</template></small>
        </div>
        <div v-if="!history" class="category-total emission-total"><b>{{ summary?.emissionWarnings ?? '—' }}</b><span>{{ text('eventUnit') }}{{ text('previewWarning') }}</span></div>
      </button>
    </div>

    <div class="filters">
      <label class="building-filter"><span>{{ text('building') }}</span><ElSelect :model-value="buildingId" :placeholder="text('chooseBuilding')" :disabled="!buildings.length" @change="changeBuilding"><ElOption v-for="building in buildings" :key="building.buildingId" :label="building.buildingName" :value="building.buildingId" /></ElSelect></label>
      <label><span>{{ text('type') }}</span><ElSelect v-model="typeFilter" :placeholder="text('allTypes')" clearable><ElOption v-for="type in (category === 'DEVICE' ? ['OFFLINE', 'QUALITY', 'VALUE', 'FAULT'] : ['QUOTA','INTENSITY','MOM','YOY','BASELINE','COMPLETENESS','FORECAST'])" :key="type" :label="typeLabel(type as AlarmType)" :value="type" /></ElSelect></label>
      <label><span>{{ text('status') }}</span><ElSelect v-model="stateFilter" :placeholder="text('allStatuses')" clearable><ElOption v-if="!history" :label="text('UNACK')" value="UNACK" /><ElOption v-if="!history" :label="text('ACK')" value="ACK" /><ElOption v-if="!history" :label="text('SILENCED')" value="SILENCED" /><ElOption v-if="!history" :label="text('ACTIVE')" value="ACTIVE" /><ElOption v-if="history" value="RECOVERED" :label="text('recovered')" /><ElOption v-if="history" value="CLOSED" :label="text('CLOSED')" /></ElSelect></label>
      <label v-if="history" class="date-filter"><span>{{ text('fromDate') }}</span><input v-model="fromAtInput" type="datetime-local"></label>
      <label v-if="history" class="date-filter"><span>{{ text('toDate') }}</span><input v-model="toAtInput" type="datetime-local"></label>
      <ElInput v-model="keyword" class="keyword" clearable :placeholder="text('search')" />
      <ElButton :disabled="loading || !buildingId" @click="manualRefresh">{{ text('refresh') }}</ElButton>
    </div>

    <ElAlert v-if="error" :title="text('loadFailed')" :description="error" type="error" show-icon />
    <ElAlert v-if="!loading && !error && !buildings.length" :title="text('noBuildings')" type="info" :closable="false" />
    <ElSkeleton v-if="loading" :rows="5" animated />
    <div v-else-if="result && !error" class="table-region">
      <table v-if="result.items.length" class="alarm-table">
        <colgroup><col style="width:19%"><col style="width:16%"><col style="width:29%"><col style="width:16%"><col style="width:7%"><col style="width:13%"></colgroup>
        <thead><tr><th>{{ text(category === 'DEVICE' ? 'deviceName' : 'object') }}</th><th>{{ text(category === 'DEVICE' ? 'installation' : 'evaluationMonth') }}</th><th>{{ text('alarmPhenomenon') }}</th><th>{{ text('triggerDuration') }}</th><th>{{ text('severity') }}</th><th>{{ text('statusHandling') }}</th></tr></thead>
        <tbody>
          <tr v-for="item in result.items" :key="item.id" :class="{ chosen: drawerEvent?.id === item.id }">
            <td><button type="button" class="object-link" @click="openEvent(item)">{{ item.objectName }}</button><small>{{ text(item.category === 'DEVICE' ? 'device' : 'emission') }}</small></td>
            <td><span>{{ category === 'DEVICE' ? buildingName : item.period }}</span><small>{{ category === 'DEVICE' ? item.spaceName || t('common.missing') : buildingName }}</small></td>
            <td class="phenomenon"><span class="type-chip" :class="{ amber: item.type === 'QUOTA' }">{{ typeLabel(item.type,item.period) }}</span><span>{{ item.description }}</span><small v-if="item.type === 'OFFLINE'">{{ text('lastCommunication') }}{{ text('glyphColon') }}{{ inlineCommunication(item) }}</small><div v-else-if="item.category==='EMISSION' && item.type==='QUOTA' && evidenceValue(item,'evaluatedValue')===t('common.missing')" class="emission-evidence"><span>{{ text('actualEmission') }} {{ evidenceValue(item, 'actualEmission') }}</span><span>{{ text('monthlyQuota') }} {{ evidenceValue(item, 'monthlyQuota') }}{{ text('glyphSeparator') }}{{ evidenceValue(item, 'quotaUsage') }}</span></div><div v-else-if="item.category==='EMISSION'" class="emission-evidence"><span>{{ text('evaluatedValue') }} {{ evidenceValue(item,'evaluatedValue') }}</span><span>{{ text('threshold') }} {{ evidenceValue(item,'threshold') }}</span></div></td>
            <td><span>{{ formatDateTime(item.firstAt) }}</span><small class="duration">{{ text('duration') }} {{ elapsed(item) }}</small><small v-if="history">{{ text('recoveredAt') }} {{ formatDateTime(item.recoveredAt) }}</small></td>
            <td><span class="level-chip" :class="item.severity.toLowerCase()">{{ text(item.severity === 'WARNING' ? 'warning' : item.severity) }}</span></td>
            <td><span class="event-state" :class="{ confirmed: item.ackAt != null, muted: item.silencedUntil != null && item.silencedUntil > Date.now() }"><i aria-hidden="true"></i>{{ eventState(item) }}</span><div class="row-actions"><button v-if="!history && item.ackAt == null" type="button" :disabled="actionBusy" @click="acknowledgeRow(item)">{{ text('acknowledgeOwner') }}</button><button type="button" @click="openEvent(item)">{{ text('view') }}</button><button v-if="!history && item.category === 'DEVICE'" type="button" @click="openEvent(item)">{{ text('customSilence') }}</button></div></td>
          </tr>
        </tbody>
      </table>
      <ElEmpty v-else :description="text('empty')" />
      <ElPagination v-if="result.total > result.size" v-model:current-page="page" :page-size="result.size" :total="result.total" layout="prev, pager, next, total" />
    </div>

    <details class="legacy-entry" @toggle="legacyOpen = ($event.target as HTMLDetailsElement).open"><summary>{{ text('legacyRecords') }}</summary><DaikinAlarmsPage v-if="legacyOpen" /></details>

    <div v-if="drawerEvent || drawerLoading || drawerError" class="drawer-overlay" @click.self="closeDrawer">
      <aside ref="drawerRef" class="event-drawer" role="dialog" aria-modal="true" :aria-label="text('detail')" tabindex="-1">
        <header class="drawer-header"><strong>{{ text('detail') }}</strong><span v-if="drawerEvent" class="type-chip">{{ eventState(drawerEvent) }}</span><button ref="closeButton" class="close-icon" :aria-label="text('closed')" @click="closeDrawer">{{ text('glyphClose') }}</button></header>
        <div class="drawer-body">
          <ElSkeleton v-if="drawerLoading && !drawerEvent" :rows="6" animated />
          <ElAlert v-if="drawerError" :title="drawerError" type="error" :closable="false" />
          <template v-if="drawerEvent">
            <section class="detail-card identity-card"><h2>{{ drawerEvent.objectName }}</h2><p>{{ text('installation') }}{{ text('glyphColon') }}{{ buildingName }}{{ text('glyphSlash') }}{{ drawerEvent.spaceName || t('common.missing') }}</p><p>{{ text('type') }}{{ text('glyphColon') }}{{ typeLabel(drawerEvent.type,drawerEvent.period) }}<span class="identity-status">{{ eventState(drawerEvent) }}</span></p></section>
            <section class="detail-card snapshot-card"><h3>{{ text('causeSnapshot') }} <small>{{ text('detailReadonly') }}</small></h3><p><b>{{ text('type') }}</b>{{ text('glyphColon') }}{{ typeLabel(drawerEvent.type,drawerEvent.period) }}</p><p><b>{{ text('description') }}</b>{{ text('glyphColon') }}{{ drawerEvent.description }}</p><p><b>{{ text('firstAt') }}</b>{{ text('glyphColon') }}{{ formatDateTime(drawerEvent.firstAt) }}</p><dl v-if="evidenceRows(drawerEvent.triggerEvidence).length" class="evidence-list"><div v-for="row in evidenceRows(drawerEvent.triggerEvidence)" :key="row.label"><dt>{{ row.label }}</dt><dd>{{ row.value }}</dd></div></dl><p><b>{{ text('ruleRevision') }}</b>{{ text('glyphColon') }}{{ drawerEvent.ruleRevision }}</p></section>
            <section class="detail-card"><h3>{{ text('evidence') }}</h3><dl v-if="evidenceRows(drawerEvent.evidence).length" class="evidence-list"><div v-for="row in evidenceRows(drawerEvent.evidence)" :key="row.label"><dt>{{ row.label }}</dt><dd>{{ row.value }}</dd></div></dl><p v-else class="muted-copy">{{ text('evidenceUnavailable') }}</p></section>
            <section class="detail-card">
              <h3>{{ text('trendTitle') }}</h3><p>{{ text('trendHours') }}</p><ElButton :loading="trendLoading" @click="loadTrend">{{ text('loadTrend') }}</ElButton>
              <p v-if="trendError">{{ trendError }}</p>
              <p v-if="!trendLoading && !trendSeries.length">{{ text('noTrend') }}</p>
              <article v-for="(series,index) in trendSeries" :key="series.pointId" class="alarm-trend">
                <strong>{{ series.pointName }}</strong><p v-if="series.unavailableReason">{{ series.unavailableReason }}</p>
                <p v-if="series.truncated">{{ text('trendTruncated') }}</p><ChartView :option="trendOptions[index]" :loading="trendLoading" :empty="series.samples.length===0" :accessible-label="series.pointName" />
              </article>
            </section>
            <section v-if="drawerEvent.status === 'RECOVERED'" class="detail-card">
              <p>{{ text('closeHelp') }}</p><ElInput v-model="noteText" type="textarea" :maxlength="500" :placeholder="text('notePlaceholder')" /><ElButton :loading="actionBusy" type="primary" @click="performAction('close')">{{ text('closeEvent') }}</ElButton>
            </section>
            <section v-if="drawerEvent.status === 'CLOSED'" class="detail-card"><p>{{ text('closedAt') }} {{ formatDateTime(drawerEvent.closedAt ?? '') }}</p></section>
            <section v-if="drawerEvent.status === 'ACTIVE'" class="detail-card action-card"><h3>{{ text('operationNotes') }}</h3><ElInput v-model="noteText" type="textarea" :rows="3" :maxlength="500" :placeholder="text('notePlaceholder')" /><div class="note-save"><ElButton text type="primary" :loading="actionBusy" @click="performAction('note')">{{ text('saveNote') }}</ElButton></div><div v-if="drawerEvent.category === 'DEVICE'" class="maintenance-note"><span>{{ text('maintenanceNote') }}</span><label>{{ text('silenceMinutes') }}<ElInputNumber v-model="silenceMinutes" :min="1" :max="10080" :precision="0" :controls="false" /></label><ElButton text type="warning" :loading="actionBusy" @click="performAction('silence')">{{ text('customSilence') }}</ElButton></div></section>
            <section v-if="drawerEvent.logs?.length" class="detail-card"><h3>{{ text('logs') }}</h3><ol class="event-logs"><li v-for="(log, index) in drawerEvent.logs" :key="`${log.at}-${index}`"><strong>{{ logAction(log.action) }}</strong><span>{{ log.note }}</span><small>{{ log.actor ?? text('systemActor') }}{{ text('glyphSeparator') }}{{ formatDateTime(log.at) }}</small></li></ol></section>
          </template>
        </div>
        <footer class="drawer-footer"><ElButton @click="closeDrawer">{{ text('closePanel') }}</ElButton><ElButton v-if="drawerEvent?.status === 'ACTIVE' && drawerEvent.ackAt == null" type="primary" :loading="actionBusy" @click="performAction('ack')">{{ text('glyphCheck') }}{{ text(drawerEvent.category === 'DEVICE' ? 'acknowledgeOwner' : 'confirmAwareness') }}</ElButton></footer>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.alarm-trend { display:grid; gap:var(--bec-space-tight); margin-top:var(--bec-space-group) }
.alarm-trend :deep(.chart-view) { height:calc(var(--bec-ref-space-64) * 4) }
.maintenance-note label { display:grid; gap:var(--bec-space-tight); font-size:var(--bec-ref-font-12) }

.alarms-page {
  --bec-alarm-number-leading: 1;
  --bec-alarm-copy-leading: 1.7;
  --bec-alarm-heading-weight: 700;
  min-width: 0;
  min-height: 100%;
  padding: var(--bec-ref-space-24);
  border: var(--bec-border-width) solid var(--bec-color-border);
  border-radius: var(--bec-ref-space-12);
  background: var(--bec-color-surface);
  color: var(--bec-color-text-primary);
  box-shadow: var(--bec-shadow-card);
  font-size: var(--bec-ref-font-12);
}
.page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--bec-ref-space-16);
  margin-bottom: var(--bec-ref-space-16);
}
.page-heading h1 {
  margin: 0 0 var(--bec-focus-width);
  font-size: var(--bec-ref-font-20);
  line-height: var(--bec-line-height);
  font-weight: var(--bec-alarm-heading-weight);
}
.page-heading p {
  margin: 0;
  color: var(--bec-color-text-secondary);
  line-height: var(--bec-line-height);
}
.history-link {
  color: var(--bec-color-brand-primary);
  text-decoration: none;
  white-space: nowrap;
}
.category-cards {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--bec-ref-space-16);
  margin-bottom: var(--bec-ref-space-16);
}
.category-cards > button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--bec-ref-space-12);
  min-height: calc(var(--bec-ref-space-64) + var(--bec-ref-space-8));
  padding: calc(var(--bec-ref-space-12) + var(--bec-focus-width)) calc(var(--bec-ref-space-16) + var(--bec-focus-width));
  border: var(--bec-border-width) solid var(--bec-color-border);
  border-radius: calc(var(--bec-ref-space-8) + var(--bec-border-width));
  background: var(--bec-color-surface-secondary);
  color: var(--bec-color-text-primary);
  text-align: left;
  cursor: pointer;
  font: inherit;
}
.category-cards > button.selected {
  padding: calc(var(--bec-ref-space-12) + var(--bec-border-width)) calc(var(--bec-ref-space-16) + var(--bec-border-width));
  border: var(--bec-focus-width) solid var(--bec-color-brand-primary);
  background: var(--bec-color-action-soft);
}
.category-copy strong {
  display: block;
  font-size: var(--bec-ref-font-14);
  font-weight: var(--bec-font-weight-heading);
}
.category-copy small {
  display: block;
  margin-top: calc(var(--bec-ref-space-4) + var(--bec-border-width));
  color: var(--bec-color-text-secondary);
  font-size: calc(var(--bec-ref-space-8) + var(--bec-focus-width) + var(--bec-border-width));
}
.selected .category-copy strong {
  color: var(--bec-color-brand-primary);
}
.view-hint {
  margin-left: var(--bec-ref-space-4);
  font-weight: var(--bec-font-weight-normal);
}
.category-total {
  display: flex;
  align-items: baseline;
  flex-shrink: 0;
  gap: calc(var(--bec-focus-width) + var(--bec-border-width));
  white-space: nowrap;
}
.category-total b {
  font-size: calc(var(--bec-ref-space-24) + var(--bec-focus-width));
  font-family: var(--bec-font-family-number);
  line-height: var(--bec-alarm-number-leading);
}
.device-total b {
  color: var(--bec-color-error);
}
.emission-total b {
  color: var(--bec-color-warning);
}
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: calc(var(--bec-ref-space-12) + var(--bec-focus-width));
  padding: var(--bec-ref-space-12) var(--bec-ref-space-16);
  margin-bottom: var(--bec-ref-space-16);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: calc(var(--bec-ref-space-4) + var(--bec-focus-width));
  background: var(--bec-color-surface-secondary);
}
.filters label {
  display: flex;
  align-items: center;
  gap: var(--bec-ref-space-8);
  white-space: nowrap;
  color: var(--bec-color-text-secondary);
}
.filters .el-select {
  width: calc(var(--bec-ref-space-64) * 2);
}
.filters .building-filter .el-select {
  width: calc(var(--bec-ref-space-64) * 2 + var(--bec-ref-space-24));
}
.filters .keyword {
  flex: 1;
  width: calc(var(--bec-ref-space-64) * 2 + var(--bec-ref-space-48) + var(--bec-ref-space-12) + var(--bec-focus-width));
  min-width: calc(var(--bec-ref-space-64) * 2 + var(--bec-ref-space-32));
}
.filters .el-button {
  margin-left: auto;
}
.date-filter input {
  max-width: calc(var(--bec-ref-space-64) * 2 + var(--bec-ref-space-48) + var(--bec-ref-space-12) + var(--bec-focus-width));
  min-height: calc(var(--bec-ref-space-24) + var(--bec-ref-space-4) + var(--bec-focus-width));
  padding: var(--bec-ref-space-4) var(--bec-ref-space-8);
  border: var(--bec-border-width) solid var(--bec-color-border);
  border-radius: var(--bec-ref-space-4);
  background: var(--bec-color-surface);
  color: var(--bec-color-text-primary);
  font: inherit;
}
.table-region {
  overflow-x: auto;
}
.alarm-table {
  width: 100%;
  min-width: calc(var(--bec-ref-space-64) * 14 + var(--bec-ref-space-32) + var(--bec-focus-width));
  border-collapse: collapse;
  table-layout: fixed;
  text-align: left;
}
.alarm-table th {
  padding: var(--bec-ref-space-12) calc(var(--bec-ref-space-12) + var(--bec-focus-width));
  background: var(--bec-color-surface-secondary);
  border-bottom: var(--bec-border-width) solid var(--bec-color-divider);
  color: var(--bec-color-text-secondary);
  font-weight: var(--bec-font-weight-heading);
  white-space: nowrap;
}
.alarm-table td {
  padding: calc(var(--bec-ref-space-16) + var(--bec-focus-width)) calc(var(--bec-ref-space-12) + var(--bec-focus-width));
  border-bottom: var(--bec-border-width) solid var(--bec-color-divider);
  vertical-align: top;
  line-height: var(--bec-alarm-copy-leading);
  overflow-wrap: anywhere;
}
.alarm-table tr.chosen td {
  background: var(--bec-color-action-soft);
}
.alarm-table td > small {
  display: block;
  margin-top: var(--bec-ref-space-4);
  color: var(--bec-color-text-secondary);
  font-size: calc(var(--bec-ref-space-8) + var(--bec-focus-width) + var(--bec-border-width));
}
.object-link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--bec-color-text-primary);
  font: inherit;
  font-weight: var(--bec-font-weight-heading);
  cursor: pointer;
  text-align: left;
}
.chosen .object-link, .object-link:hover {
  color: var(--bec-color-brand-primary);
}
.type-chip {
  display: inline-block;
  padding: var(--bec-border-width) calc(var(--bec-ref-space-4) + var(--bec-border-width));
  margin-right: calc(var(--bec-ref-space-4) + var(--bec-focus-width));
  border: var(--bec-border-width) solid color-mix(in srgb, var(--bec-color-error) 30%, var(--bec-color-surface));
  border-radius: calc(var(--bec-focus-width) + var(--bec-border-width));
  background: color-mix(in srgb, var(--bec-color-error) 5%, var(--bec-color-surface));
  color: var(--bec-color-error);
  font-size: calc(var(--bec-ref-space-8) + var(--bec-focus-width) + var(--bec-border-width));
  line-height: var(--bec-alarm-copy-leading);
}
.type-chip.amber {
  border-color: color-mix(in srgb, var(--bec-color-warning) 35%, var(--bec-color-surface));
  background: color-mix(in srgb, var(--bec-color-warning) 7%, var(--bec-color-surface));
  color: var(--bec-color-warning);
}
.alarm-table td .duration {
  color: var(--bec-color-error);
}
.level-chip {
  display: inline-block;
  padding: var(--bec-border-width) calc(var(--bec-ref-space-4) + var(--bec-focus-width));
  border: var(--bec-border-width) solid color-mix(in srgb, var(--bec-color-warning) 40%, var(--bec-color-surface));
  border-radius: calc(var(--bec-focus-width) + var(--bec-border-width));
  background: color-mix(in srgb, var(--bec-color-warning) 6%, var(--bec-color-surface));
  color: var(--bec-color-warning);
  white-space: nowrap;
}
.level-chip.urgent { color:var(--bec-color-error); background:color-mix(in srgb,var(--bec-color-error) 9%,var(--bec-color-surface)) }
.level-chip.general { color:var(--bec-color-action-primary); background:var(--bec-color-action-soft) }
.level-chip.info,.level-chip.warning {
  border-color: var(--bec-color-border);
  background: var(--bec-color-action-soft);
  color: var(--bec-color-brand-primary);
}
.event-state {
  display: flex;
  align-items: center;
  gap: var(--bec-ref-space-4);
  color: var(--bec-color-error);
  font-weight: var(--bec-font-weight-heading);
}
.event-state i {
  width: calc(var(--bec-ref-space-4) + var(--bec-border-width));
  height: calc(var(--bec-ref-space-4) + var(--bec-border-width));
  border-radius: var(--bec-ref-radius-pill);
  background: currentColor;
}
.event-state.confirmed {
  color: var(--bec-color-success);
}
.event-state.muted {
  color: var(--bec-color-warning);
}
.row-actions button {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--bec-color-brand-primary);
  font: inherit;
  cursor: pointer;
}
.emission-evidence {
  display: grid;
  margin-top: calc(var(--bec-ref-space-4) + var(--bec-border-width));
  color: var(--bec-color-text-secondary);
  font-size: calc(var(--bec-ref-space-8) + var(--bec-focus-width) + var(--bec-border-width));
}
.legacy-entry {
  margin-top: var(--bec-ref-space-24);
  color: var(--bec-color-text-secondary);
  font-size: calc(var(--bec-ref-space-8) + var(--bec-focus-width) + var(--bec-border-width));
}
.legacy-entry summary {
  cursor: pointer;
  width: fit-content;
}
.drawer-overlay {
  position: fixed;
  inset: var(--bec-workspace-header-height) 0 0;
  z-index: 40;
  display: flex;
  justify-content: flex-end;
  background: transparent;
  pointer-events: none;
}
.event-drawer {
  display: flex;
  flex-direction: column;
  width: min(calc(var(--bec-ref-space-64) * 8 + var(--bec-ref-space-24) + var(--bec-ref-space-4)), 100%);
  height: 100%;
  border-left: var(--bec-border-width) solid var(--bec-color-border);
  background: var(--bec-color-surface);
  box-shadow: var(--bec-shadow-overlay);
  outline: none;
  pointer-events: auto;
}
.drawer-header {
  display: flex;
  align-items: center;
  gap: var(--bec-ref-space-8);
  min-height: calc(var(--bec-ref-space-48) + var(--bec-ref-space-12));
  padding: calc(var(--bec-ref-space-16) + var(--bec-focus-width)) var(--bec-ref-space-24);
  border-bottom: var(--bec-border-width) solid var(--bec-color-divider);
}
.drawer-header strong {
  font-size: var(--bec-ref-font-14);
}
.close-icon {
  margin-left: auto;
  padding: 0;
  border: 0;
  background: none;
  color: var(--bec-color-text-secondary);
  font-size: var(--bec-ref-space-20);
  line-height: var(--bec-ref-space-20);
  cursor: pointer;
}
.drawer-body {
  flex: 1;
  min-height: 0;
  padding: var(--bec-ref-space-20) var(--bec-ref-space-24);
  overflow-y: auto;
}
.detail-card {
  margin-bottom: var(--bec-ref-space-16);
  padding: var(--bec-ref-space-16);
  border: var(--bec-border-width) solid var(--bec-color-border);
  border-radius: var(--bec-ref-space-8);
  background: var(--bec-color-surface-secondary);
}
.identity-card {
  background: var(--bec-color-surface);
}
.detail-card h2 {
  margin: 0 0 calc(var(--bec-ref-space-4) + var(--bec-focus-width));
  font-size: var(--bec-ref-font-16);
  line-height: var(--bec-line-height);
}
.detail-card h3 {
  margin: 0 0 var(--bec-ref-space-12);
  font-size: var(--bec-ref-font-12);
  line-height: var(--bec-line-height);
}
.detail-card h3 small {
  margin-left: calc(var(--bec-ref-space-4) + var(--bec-focus-width));
  color: var(--bec-color-text-secondary);
  font-size: calc(var(--bec-ref-space-8) + var(--bec-focus-width) + var(--bec-border-width));
  font-weight: var(--bec-font-weight-normal);
}
.detail-card p {
  margin: calc(var(--bec-focus-width) + var(--bec-border-width)) 0;
  line-height: var(--bec-alarm-copy-leading);
}
.identity-card p, .muted-copy {
  color: var(--bec-color-text-secondary);
}
.identity-status {
  margin-left: var(--bec-ref-space-12);
}
.evidence-list {
  margin: calc(var(--bec-ref-space-8) + var(--bec-focus-width)) 0 0;
}
.evidence-list > div {
  display: flex;
  gap: calc(var(--bec-ref-space-8) + var(--bec-focus-width));
  margin: var(--bec-ref-space-4) 0;
  line-height: var(--bec-alarm-copy-leading);
}
.evidence-list dt {
  min-width: calc(var(--bec-ref-space-64) + var(--bec-ref-space-24) + var(--bec-focus-width));
  color: var(--bec-color-text-secondary);
}
.evidence-list dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.note-save {
  display: flex;
  justify-content: flex-end;
  margin: var(--bec-ref-space-4) 0;
}
.maintenance-note {
  display: flex;
  align-items: center;
  gap: var(--bec-ref-space-8);
  padding: var(--bec-ref-space-8) calc(var(--bec-ref-space-8) + var(--bec-focus-width));
  border: var(--bec-border-width) solid color-mix(in srgb, var(--bec-color-warning) 35%, var(--bec-color-surface));
  border-radius: var(--bec-ref-space-4);
  background: color-mix(in srgb, var(--bec-color-warning) 5%, var(--bec-color-surface));
  color: var(--bec-color-warning);
  font-size: calc(var(--bec-ref-space-8) + var(--bec-focus-width) + var(--bec-border-width));
}
.maintenance-note .el-button {
  flex-shrink: 0;
  padding: 0;
}
.event-logs {
  padding-left: var(--bec-ref-space-16);
  margin: 0;
}
.event-logs li {
  margin-top: var(--bec-ref-space-8);
}
.event-logs span, .event-logs small {
  display: block;
  margin-top: calc(var(--bec-focus-width) + var(--bec-border-width));
  color: var(--bec-color-text-secondary);
}
.drawer-footer {
  display: flex;
  flex-shrink: 0;
  justify-content: flex-end;
  gap: var(--bec-ref-space-12);
  padding: calc(var(--bec-ref-space-12) + var(--bec-focus-width)) var(--bec-ref-space-24);
  border-top: var(--bec-border-width) solid var(--bec-color-divider);
  background: var(--bec-color-surface-secondary);
}
.alarms-page button:focus-visible, .legacy-entry summary:focus-visible {
  outline: var(--bec-focus-width) solid var(--bec-color-brand-primary);
  outline-offset: var(--bec-focus-width);
}
@media (max-width: 960px) {
  .category-cards {
    grid-template-columns: 1fr;
  }
  .alarms-page {
    padding: var(--bec-ref-space-16);
  }
  .category-copy small {
    font-size: calc(var(--bec-ref-space-8) + var(--bec-focus-width) + var(--bec-border-width));
  }
}
@media (max-width: 600px) {
  .page-heading {
    align-items: flex-start;
  }
  .filters {
    gap: calc(var(--bec-ref-space-8) + var(--bec-focus-width));
  }
  .filters label {
    width: 100%;
    justify-content: space-between;
  }
  .filters .el-select, .filters .building-filter .el-select {
    width: 65%;
  }
  .event-drawer {
    width: 100%;
  }
  .category-copy .view-hint {
    display: none;
  }
}
.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0 var(--bec-ref-space-8);
}
@media (min-width: 1800px) {
  .alarms-page.drawer-open {
    margin-right: calc(var(--bec-ref-space-64) * 8 + var(--bec-ref-space-4));
  }
}
</style>
