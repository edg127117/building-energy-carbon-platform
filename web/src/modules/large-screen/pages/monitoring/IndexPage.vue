<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElButton, ElDialog, Leaf, Monitor, Zap, Clock, ChevronDown, Maximize, Minimize } from '@/shared/ui'
import { t } from '@/locales'
import { screenShellKey } from '@/shared/models/screen-shell'
import SceneScreenLayout from '../../components/SceneScreenLayout.vue'
import BuildingScene, { type SceneGeometrySnapshot, type SceneHotspot } from '../../components/BuildingScene.vue'
import ProfessionalScenePanels from '../../components/ProfessionalScenePanels.vue'
import ScreenPeriodControl from '../../components/ScreenPeriodControl.vue'
import ScreenBusinessFlow from '../../components/ScreenBusinessFlow.vue'
import ScreenPlanChart from '../../components/ScreenPlanChart.vue'
import ScreenMirrorChart from '../../components/ScreenMirrorChart.vue'
import ScreenUsageChart from '../../components/ScreenUsageChart.vue'
import ScreenIntensitySummary from '../../components/ScreenIntensitySummary.vue'
import ScreenDeviceSummary from '../../components/ScreenDeviceSummary.vue'
import ScreenAlarmSummary from '../../components/ScreenAlarmSummary.vue'
import ScreenSummaryCandidate from '../../components/ScreenSummaryCandidate.vue'
import { currentBeijingDate, currentScreenPeriod, followBeijingDay, type ScreenPeriod } from '../../models/screen-period'
import { periodEntry, PLAN_PROTOTYPE, type ScreenRedesignData, type ScreenPeriods } from '../../models/screen-redesign'
import { prototypeMetrics, prototypeDeviceFrames, PROTOTYPE_DEVICE_FRAME_MS, prototypeAlarms, prototypeFlow, prototypeUsage, prototypeMirror, prototypeIntensity, prototypeRuntimeRanking, prototypeIntensityBenchmarks } from '../../models/screen-prototype'

const props = defineProps<{ title?: string; data?: ScreenRedesignData; layoutVariant?: 'a' | 'b' | 'c'; alarmBuildingId?: string; buildingName?: string }>()
const emit = defineEmits<{ periodChange: [periods: ScreenPeriods] }>()
const reviewingLayout = computed(() => props.layoutVariant !== undefined)
const s = (key: string, params?: Record<string, string | number>) => t('largeScreen.' + key, params)
// 独立原型入口使用预置素材；业务入口始终显式传入数据，缺失不能回退为原型读数。
const prototypeMode = computed(() => props.data === undefined)
const deviceFrameIndex = ref(0), deviceFrameTime = ref(Date.now())
let deviceFrameTimer: ReturnType<typeof setInterval> | undefined
function stopDeviceFrames() {
  if (deviceFrameTimer !== undefined) clearInterval(deviceFrameTimer)
  deviceFrameTimer = undefined
}
// 原型只播放预置快照；传入正式数据立即停播，不能覆盖外部设备状态。
function syncDeviceFrames() {
  stopDeviceFrames()
  deviceFrameIndex.value = 0
  deviceFrameTime.value = Date.now()
  if (!prototypeMode.value) return
  deviceFrameTimer = setInterval(() => {
    if (document.visibilityState === 'hidden') return
    deviceFrameIndex.value = (deviceFrameIndex.value + 1) % prototypeDeviceFrames.length
    deviceFrameTime.value = Date.now()
  }, PROTOTYPE_DEVICE_FRAME_MS)
}
onMounted(syncDeviceFrames)
watch(prototypeMode, syncDeviceFrames)
onBeforeUnmount(stopDeviceFrames)
const shell = inject(screenShellKey, null)
const scene = ref<InstanceType<typeof BuildingScene> | null>(null)
const ready = ref(false), selectedView = ref('front'), free = ref(false), tour = ref(false)
const activeSystem = ref<string | null>(null)
const geometry = ref<SceneGeometrySnapshot | null>(null)
const hotspots = ref<readonly SceneHotspot[]>([])
const systems = new Set(['hvac', 'elevator', 'lighting', 'fire'])
const visibleHotspots = computed(() => hotspots.value.filter(item => item.visible && systems.has(item.id) && item.id !== 'fire'))
const professionalGeometry = computed(() => geometry.value?.topic === activeSystem.value ? geometry.value : null)
const motion = ref(!globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches)
const menuOpen = ref(false), infoOpen = ref(false), alarmsOpen = ref(true), devicesOpen = ref(true)
const compositionPeriod = ref<ScreenPeriod>(currentScreenPeriod()), flowPeriod = ref<ScreenPeriod>(currentScreenPeriod())
const planPeriod = ref<ScreenPeriod>(currentScreenPeriod()), mirrorPeriod = ref<ScreenPeriod>(currentScreenPeriod())
const usagePeriod = ref<ScreenPeriod>(currentScreenPeriod()), intensityPeriod = ref<ScreenPeriod>(currentScreenPeriod())
const flowMode = ref<'energy' | 'carbon'>('carbon')
watch([compositionPeriod, flowPeriod, planPeriod, mirrorPeriod, usagePeriod, intensityPeriod], () => {
  emit('periodChange', { composition: compositionPeriod.value, flow: flowPeriod.value, plan: planPeriod.value, mirror: mirrorPeriod.value, usage: usagePeriod.value, intensity: intensityPeriod.value })
}, { immediate: true })
let lastBeijingDate = currentBeijingDate()
const dayRolloverTimer = setInterval(() => {
  const today = currentBeijingDate()
  if (today === lastBeijingDate) return
  const previous = lastBeijingDate
  lastBeijingDate = today
  usagePeriod.value = followBeijingDay(usagePeriod.value, previous, today)
  mirrorPeriod.value = followBeijingDay(mirrorPeriod.value, previous, today)
}, 15_000)
onBeforeUnmount(() => clearInterval(dayRolloverTimer))
const currentYear = currentScreenPeriod().value
const beijingDate = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit' }).format(new Date())
const [year, month] = beijingDate.split('-').map(Number)
const lastMonth = new Date(Date.UTC(year, month - 1, 0))
const previousMonth = `${lastMonth.getUTCFullYear()}-${String(lastMonth.getUTCMonth() + 1).padStart(2, '0')}`
const annual = computed(() => prototypeMode.value ? prototypeMetrics : props.data?.annual?.year === currentYear ? props.data.annual : undefined)
const previous = computed(() => prototypeMode.value ? { carbon: prototypeMetrics.previousCarbon, intensity: prototypeMetrics.previousIntensity } : props.data?.previousMonth?.month === previousMonth ? props.data.previousMonth : undefined)
const metrics = computed(() => [
  { key: 'currentYearCarbon', icon: Leaf, unit: 'tco2', value: annual.value?.carbon },
  { key: 'currentYearIntensity', icon: Monitor, unit: 'intensityUnit', value: annual.value?.intensity },
  { key: 'previousMonthCarbon', icon: Zap, unit: 'tco2', value: previous.value?.carbon },
  { key: 'previousMonthIntensity', icon: Clock, unit: 'intensityUnit', value: previous.value?.intensity },
])
const composition = computed(() => prototypeMode.value ? { total: compositionPeriod.value.kind === 'month' ? 12 : 104, yearOverYear: -4.2, monthOverMonth: -2.8, unit: 'tCO₂e' } : periodEntry(props.data?.composition, compositionPeriod.value))
const flow = computed(() => prototypeMode.value ? prototypeFlow(flowPeriod.value, flowMode.value) : periodEntry(flowMode.value === 'carbon' ? props.data?.carbonFlow : props.data?.energyFlow, flowPeriod.value))
const plan = computed(() => {
  if (prototypeMode.value) return PLAN_PROTOTYPE
  return periodEntry(props.data?.plan, { kind: 'year', value: planPeriod.value.value.slice(0, 4) }) ?? null
})
const mirror = computed(() => prototypeMode.value ? prototypeMirror(mirrorPeriod.value) : periodEntry(props.data?.mirror, mirrorPeriod.value) ?? null)
const usage = computed(() => prototypeMode.value ? prototypeUsage(usagePeriod.value) : periodEntry(props.data?.usage, usagePeriod.value) ?? null)
// 年、月强度必须匹配自身期间与上游单位，不能拿年度数值填充月视图。
const intensity = computed(() => prototypeMode.value ? prototypeIntensity(intensityPeriod.value) : periodEntry(props.data?.intensity, intensityPeriod.value))
const alarms = computed(() => prototypeMode.value ? prototypeAlarms : props.data?.alarms)
const devices = computed(() => prototypeMode.value ? prototypeDeviceFrames[deviceFrameIndex.value] : props.data?.devices ?? {
  total: null, on: null, off: null, unknown: null,
  modes: [{ key: 'fan', label: s('deviceSummaryFan'), count: null }, { key: 'cool', label: s('deviceSummaryCooling'), count: null }, { key: 'heat', label: s('deviceSummaryHeating'), count: null }],
  speeds: [{ key: 'low', label: s('prototypeSpeedLow'), count: null }, { key: 'medium', label: s('prototypeSpeedMedium'), count: null }, { key: 'high', label: s('prototypeSpeedHigh'), count: null }, { key: 'auto', label: s('prototypeSpeedAuto'), count: null }],
})
const runtimeRanking = computed(() => prototypeMode.value ? prototypeRuntimeRanking : props.data?.runtimeRanking)
const intensityBenchmarks = computed(() => prototypeMode.value ? prototypeIntensityBenchmarks : props.data?.intensityBenchmarks)
const clock = ref(Date.now())
let clockTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => { clockTimer = setInterval(() => { clock.value = Date.now() }, 1000) })
onBeforeUnmount(() => { if (clockTimer !== undefined) clearInterval(clockTimer) })
const clockDate = computed(() => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(clock.value))
const clockWeekday = computed(() => new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', weekday: 'long' }).format(clock.value))
const clockTime = computed(() => new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(clock.value))
const metricFormatter = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
function metricNumber(value: number | null | undefined): string {
  return typeof value === 'number' && Number.isFinite(value) ? metricFormatter.format(value) : s('missing')
}
function number(value: number | null | undefined): string {
  return typeof value === 'number' && Number.isFinite(value) ? new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(value) : s('missing')
}
function ratio(value: number | null | undefined): string {
  return typeof value === 'number' && Number.isFinite(value) ? `${value > 0 ? '+' : ''}${number(value)}%` : s('missing')
}
watch(ready, value => { if (value) { scene.value?.enterSystem(activeSystem.value); scene.value?.setMotion(motion.value) } })
function enterSystem(topic: string | null) {
  if (topic !== null && (!ready.value || !systems.has(topic))) return
  activeSystem.value = topic
  tour.value = false
  free.value = false
  scene.value?.setTour(false)
  scene.value?.setFree(false)
  // 下钻只使用场景层过滤后的几何快照，模型动效与对象数量不作为正式设备读数。
  scene.value?.enterSystem(topic)
  if (topic === null) scene.value?.preset(selectedView.value)
}
function preset(id: string) { selectedView.value = id; free.value = false; tour.value = false; scene.value?.setTour(false); scene.value?.preset(id) }
function toggleFree() { free.value = !free.value; tour.value = false; scene.value?.setTour(false); scene.value?.setFree(free.value) }
function toggleTour() { tour.value = !tour.value; free.value = false; scene.value?.setFree(false); scene.value?.setTour(tour.value) }
function toggleMotion() { motion.value = !motion.value; scene.value?.setMotion(motion.value) }
</script>
<template>
  <SceneScreenLayout class="building-screen redesign-screen" :panels-visible="activeSystem === null" :class="[layoutVariant ? 'layout-review layout-' + layoutVariant : '']">
    <template #scene>
      <BuildingScene ref="scene" @ready="ready = $event" @snapshot="geometry = $event" @hotspots="hotspots = $event" />
      <div v-if="ready && activeSystem === null" class="system-hotspots">
        <button v-for="hotspot in visibleHotspots" :key="hotspot.id" class="system-hotspot" :style="{ left: hotspot.x + 'px', top: hotspot.y + 'px' }" :aria-label="s('enterSystem', { system: s(hotspot.id) })" @click="enterSystem(hotspot.id)">{{ s(hotspot.id) }}<ChevronDown :size="14" /></button>
      </div>
      <div v-if="layoutVariant === 'b' && activeSystem === null" class="screen-clock"><time class="screen-clock-time">{{ clockTime }}</time><span class="screen-clock-divider" aria-hidden="true" /><div class="screen-clock-meta"><span class="screen-clock-date">{{ clockDate }}</span><span class="screen-clock-weekday">{{ clockWeekday }}</span></div></div>
      <nav v-show="activeSystem === null" class="scene-controls" :aria-label="s('scene')">
        <div class="camera-views"><button v-for="view in ['front', 'terrace', 'rear', 'aerial']" :key="view" :aria-pressed="selectedView === view && !free" :disabled="!ready" @click="preset(view)">{{ s(view) }}</button></div>
        <div class="camera-actions"><button :disabled="!ready" @click="preset(selectedView)">{{ s('reset') }}</button><button :disabled="!ready" :aria-pressed="free" @click="toggleFree">{{ s('free') }}</button><button :disabled="!ready" :aria-pressed="tour" @click="toggleTour">{{ s('tour') }}</button><button :disabled="!ready" :aria-pressed="!motion" @click="toggleMotion">{{ s(motion ? 'pause' : 'resume') }}</button><button :disabled="!ready" @click="scene?.reload()">{{ s('reload') }}</button><button @click="infoOpen = true">{{ s('dataInfo') }}</button><button class="scene-switch" @click="menuOpen = true">{{ s('switchPage') }}</button></div>
      </nav>
    </template>
    <template #overlay>
      <ProfessionalScenePanels v-if="activeSystem !== null" :topic="activeSystem" :assets="professionalGeometry?.assets ?? []" :selected="professionalGeometry?.selected ?? null" :floor="professionalGeometry?.floorFilter ?? s('all')" :playing="professionalGeometry?.playing ?? false" :building-name="props.buildingName ?? ''" @system="enterSystem" @floor="scene?.setFloor($event)" @select="scene?.selectAsset($event)" @playing="scene?.setPlaying($event)" @menu="menuOpen = true" />
      <ElDialog v-model="menuOpen" :title="s('switchPage')" width="var(--bec-screen-dialog-width)" :append-to-body="false" destroy-on-close><div class="screen-menu"><ElButton :icon="shell?.fullscreen.value ? Minimize : Maximize" @click="shell?.toggleFullscreen()">{{ s(shell?.fullscreen.value ? 'exitFullscreen' : 'fullscreen') }}</ElButton><ElButton @click="infoOpen = true; menuOpen = false">{{ s('dataInfo') }}</ElButton><ElButton @click="shell?.openNavigation(); menuOpen = false">{{ t('navigation.switchScreen') }}</ElButton><ElButton @click="shell?.exit()">{{ s('exit') }}</ElButton></div></ElDialog>
      <ElDialog v-model="infoOpen" :title="s('dataInfo')" width="var(--bec-screen-dialog-width)" :append-to-body="false" destroy-on-close><div class="screen-caliber"><slot name="data-context" /><template v-if="prototypeMode"><p>{{ s('redesignPrototypeNotice') }}</p><p>{{ s('redesignPlanNotice') }}</p><p>{{ s('redesignPendingNotice') }}</p><p>{{ s('modelNotice') }}</p><p>{{ s('redesignHotspotNotice') }}</p></template></div></ElDialog>
    </template>
    <template #top>
      <header class="scene-title"><span class="title-wing" /><div class="title-plaque"><h1>{{ s('redesignPlatform') }}</h1></div><span class="title-wing" /></header>
      <div class="screen-metrics"><article v-for="(metric, index) in metrics" :key="metric.key" :class="'tone-' + ['blue', 'teal', 'amber', 'indigo'][index]"><span class="metric-icon"><component :is="metric.icon" :size="23" /></span><div><span>{{ s(metric.key) }}</span><strong>{{ metricNumber(metric.value) }}</strong></div><small>{{ s(metric.unit) }}</small></article></div>
    </template>
    <template #left>
      <article class="screen-card composition-card"><header><h2>{{ s('composition') }}</h2><ScreenPeriodControl v-model="compositionPeriod" :label="s('composition')" /></header><div class="composition-body"><div class="carbon-ring"><span>{{ s('carbonTotal') }}</span><strong>{{ number(composition?.total) }}</strong><small>{{ composition?.unit ?? s('tco2') }}</small></div><div class="electricity-summary"><span class="scope-caption">{{ s('scopeTwo') }}</span><div class="electricity-source"><strong>{{ s('electricity') }}</strong><b>{{ s('redesignElectricityShare') }}</b></div><div class="comparison-values"><div><span>{{ s(compositionPeriod.kind === 'year' ? (composition?.throughMonth !== undefined && composition.throughMonth < 12 ? 'finalBYearComparison' : 'redesignYearComparison') : 'redesignMonthComparison') }}</span><b>{{ ratio(composition?.yearOverYear) }}</b></div><div v-if="compositionPeriod.kind === 'month'"><span>{{ s('redesignPreviousMonth') }}</span><b>{{ ratio(composition?.monthOverMonth) }}</b></div></div></div></div></article>
      <article v-if="layoutVariant === 'b'" class="screen-card chart-card usage-card"><header><h2>{{ s('redesignUsage') }}</h2><ScreenPeriodControl v-model="usagePeriod" :label="s('redesignUsage')" allow-day /></header><ScreenUsageChart :period="usagePeriod" :data="usage" :loading="props.data?.loading?.usage" :failed="props.data?.failed?.usage" :expanded="reviewingLayout" /></article>
      <article class="screen-card flow-card"><header><h2>{{ s('flow') }}</h2><ScreenPeriodControl v-model="flowPeriod" :label="s('flow')" /></header><div class="flow-tools"><span>{{ s(flowMode === 'carbon' ? 'redesignCarbonTotal' : 'standardCoal') }}<small>{{ s(flowMode === 'carbon' ? 'tco2' : 'tce') }}</small></span><div class="segmented"><button v-for="mode in (layoutVariant === 'b' ? ['carbon', 'energy'] as const : ['energy', 'carbon'] as const)" :key="mode" :aria-pressed="flowMode === mode" @click="flowMode = mode">{{ s(mode === 'carbon' ? 'carbonFlow' : 'energyFlow') }}</button></div></div><ScreenBusinessFlow :mode="flowMode" :data="flow" /></article>
      <article v-if="reviewingLayout && layoutVariant !== 'b'" class="screen-card chart-card usage-card"><header><h2>{{ s('redesignUsage') }}</h2><ScreenPeriodControl v-model="usagePeriod" :label="s('redesignUsage')" /></header><ScreenUsageChart :period="usagePeriod" :data="usage" :loading="props.data?.loading?.usage" :failed="props.data?.failed?.usage" :expanded="reviewingLayout" :wide="layoutVariant === 'c'" /></article>
      <article v-else-if="!reviewingLayout" class="screen-card intensity-card"><header><h2>{{ s('scoreSummary') }}</h2><ScreenPeriodControl v-model="intensityPeriod" :label="s('scoreSummary')" /></header><ScreenIntensitySummary :data="intensity" /></article>
    </template>
    <template #right>
      <article class="screen-card chart-card plan-card"><header><h2>{{ s('redesignPlan') }}</h2><ScreenPeriodControl v-model="planPeriod" :label="s('redesignPlan')" /></header><ScreenPlanChart :period="planPeriod" :data="plan" /></article>
      <article class="screen-card chart-card mirror-card"><header><h2>{{ s('redesignMirror') }}</h2><ScreenPeriodControl v-model="mirrorPeriod" :label="s('redesignMirror')" :allow-day="layoutVariant === 'b'" /></header><ScreenMirrorChart :period="mirrorPeriod" :data="mirror" :loading="props.data?.loading?.mirror" :failed="props.data?.failed?.mirror" :expanded="reviewingLayout" /></article>
      <article v-if="reviewingLayout" class="screen-card intensity-card"><header><h2>{{ s(layoutVariant === 'b' ? 'carbonIntensityTitle' : 'scoreSummary') }}</h2><ScreenPeriodControl v-model="intensityPeriod" :label="s(layoutVariant === 'b' ? 'carbonIntensityTitle' : 'scoreSummary')" /></header><ScreenIntensitySummary :data="intensity" :show-benchmarks="layoutVariant === 'b'" :benchmarks="intensityBenchmarks" :prototype-reference="prototypeMode" /></article>
      <article v-else class="screen-card chart-card usage-card"><header><h2>{{ s('redesignUsage') }}</h2><ScreenPeriodControl v-model="usagePeriod" :label="s('redesignUsage')" /></header><ScreenUsageChart :period="usagePeriod" :data="usage" :loading="props.data?.loading?.usage" :failed="props.data?.failed?.usage" :expanded="reviewingLayout" :wide="layoutVariant === 'c'" /></article>
    </template>
    <template #bottom>
      <ScreenSummaryCandidate v-if="reviewingLayout" class="screen-summary-dock" :variant="layoutVariant ?? 'a'" :alarm-building-id="alarmBuildingId" :alarms="alarms" :devices="devices" :runtime-ranking="runtimeRanking" :updated-at="prototypeMode ? deviceFrameTime : props.data?.deviceSummaryUpdatedAt" embedded />
      <div v-else class="bottom-panels">
        <article v-show="alarmsOpen" class="screen-card bottom-card alarm-card"><header><h2>{{ s('alarms') }}</h2><button class="collapse-bottom" :aria-label="s('collapseAlarms')" @click="alarmsOpen = false"><ChevronDown :size="16" /></button></header><ScreenAlarmSummary :data="alarms" /></article>
        <button v-if="!alarmsOpen" class="bottom-restore restore-alarm" :aria-label="s('expandAlarms')" @click="alarmsOpen = true">{{ s('alarms') }}</button>
        <article v-show="devicesOpen" class="screen-card bottom-card device-card"><header><h2>{{ s('redesignDevices') }}</h2><button class="collapse-bottom" :aria-label="s('redesignCollapseDevices')" @click="devicesOpen = false"><ChevronDown :size="16" /></button></header><ScreenDeviceSummary :data="devices" :updated-at="prototypeMode ? deviceFrameTime : props.data?.deviceSummaryUpdatedAt" :animate="motion" /></article>
        <button v-if="!devicesOpen" class="bottom-restore restore-devices" :aria-label="s('redesignExpandDevices')" @click="devicesOpen = true">{{ s('redesignDevices') }}</button>
      </div>
    </template>
  </SceneScreenLayout>
</template>

<style scoped src="./final-b.css"></style>
