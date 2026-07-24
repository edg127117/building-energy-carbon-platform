<template>
  <ScreenLayout>
    <div class="grid grid-cols-1 gap-5 lg:grid-cols-12">
      <div class="lg:col-span-8">
        <GlassCard>
          <div class="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
            <div class="min-w-0">
              <div class="text-sm font-semibold text-zinc-50">能效综合监控大屏</div>
              <div class="mt-1 text-xs tracking-[0.18em] text-zinc-400">WS 实时推送 · TDengine 特征数据 · MySQL 在线状态</div>
            </div>
            <div class="flex flex-wrap items-center gap-3">
              <a-select v-model:value="activeDeviceId" class="min-w-[220px]" size="small" :options="deviceOptions" />
              <a-select
                v-model:value="historyRange"
                size="small"
                class="min-w-[100px]"
                :options="rangeOptions"
              />
              <div class="rounded-xl border border-white/10 bg-black/25 px-3 py-2 text-xs text-zinc-300">
                WS: <span :class="wsTone">{{ wsStatusLabel }}</span>
              </div>
            </div>
          </div>

          <div class="mt-5 grid grid-cols-1 gap-4 md:grid-cols-3">
            <div class="rounded-2xl border border-white/10 bg-black/20 p-4">
              <MetricFlipper label="在线设备" :value="deviceStore.onlineCount" unit="台" tone="success" hint="来自 MySQL status + WS 上行更新" />
            </div>
            <div class="rounded-2xl border border-white/10 bg-black/20 p-4">
              <MetricFlipper label="离线设备" :value="deviceStore.offlineCount" unit="台" tone="danger" hint="offline 消息触发离线告警" />
            </div>
            <div class="rounded-2xl border border-white/10 bg-black/20 p-4">
              <MetricFlipper label="当前功率" :value="lastPower" unit="kW" tone="info" hint="active_power (若无则显示 --)" />
            </div>
          </div>
        </GlassCard>

        <div class="mt-5 grid grid-cols-1 gap-5 lg:grid-cols-2">
          <GlassCard>
            <ChartPanel title="电压趋势" subtitle="voltage_a" :series="voltageSeries" :xMin="chartXMin" :xMax="chartXMax" />
          </GlassCard>
          <GlassCard>
            <ChartPanel title="电流趋势" subtitle="current_a" :series="currentSeries" :xMin="chartXMin" :xMax="chartXMax" />
          </GlassCard>
        </div>

        <div class="mt-5">
          <GlassCard>
            <ChartPanel title="有功功率趋势" subtitle="active_power" :series="powerSeries" :xMin="chartXMin" :xMax="chartXMax" />
          </GlassCard>
        </div>
      </div>

      <div class="lg:col-span-4">
        <GlassCard>
          <div class="flex items-center justify-between">
            <div class="text-sm font-semibold text-zinc-50">设备告警滚筒</div>
            <a-button size="small" @click="clearAlarms">清空</a-button>
          </div>
          <div class="mt-4 space-y-3">
            <div v-if="deviceStore.alarms.length === 0" class="rounded-xl border border-white/10 bg-black/20 px-4 py-6 text-center text-sm text-zinc-400">
              暂无告警。等待设备上报或离线事件。
            </div>
            <div
              v-for="a in deviceStore.alarms"
              :key="a.id"
              class="rounded-2xl border border-white/10 bg-black/20 px-4 py-3"
            >
              <div class="flex items-center justify-between gap-3">
                <div class="min-w-0">
                  <div class="truncate text-sm text-zinc-100">{{ a.text }}</div>
                  <div class="mt-1 text-xs text-zinc-500">{{ new Date(a.t).toLocaleString() }}</div>
                </div>
                <div
                  class="h-2.5 w-2.5 rounded-full"
                  :class="a.level === 'danger' ? 'bg-rose-400' : a.level === 'warn' ? 'bg-amber-300' : 'bg-sky-300'"
                />
              </div>
            </div>
          </div>
        </GlassCard>

        <div class="mt-5">
          <GlassCard>
            <div class="text-sm font-semibold text-zinc-50">实时数据预览</div>
            <div class="mt-1 text-xs text-zinc-400">当前设备：{{ activeDeviceId || '--' }}</div>
            <div class="mt-4 rounded-2xl border border-white/10 bg-black/25 p-4 text-xs leading-6 text-zinc-300">
              <div>voltage_a：<span class="text-zinc-100">{{ lastPoint?.voltage_a ?? '--' }}</span></div>
              <div>current_a：<span class="text-zinc-100">{{ lastPoint?.current_a ?? '--' }}</span></div>
              <div>active_power：<span class="text-zinc-100">{{ lastPoint?.active_power ?? '--' }}</span></div>
              <div class="mt-2 text-zinc-500">timestamp：{{ lastPoint?.t ? new Date(lastPoint.t).toLocaleString() : '--' }}</div>
            </div>
          </GlassCard>
        </div>
      </div>
    </div>
  </ScreenLayout>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ScreenLayout from '@/layouts/ScreenLayout.vue'
import GlassCard from '@/components/GlassCard.vue'
import MetricFlipper from '@/components/MetricFlipper.vue'
import ChartPanel from '@/components/ChartPanel.vue'
import { useDeviceStore } from '@/store/device'
import { DashboardWsClient } from '@/utils/websocket'
import { getTelemetryHistoryApi, type TelemetryPoint } from '@/api/device'
import type { WsDeviceMessage } from '@/types/ws'

const deviceStore = useDeviceStore()

const activeDeviceId = ref<string>('')
const wsStatus = ref<'idle' | 'connecting' | 'open' | 'closed' | 'error'>('idle')
const historyRange = ref<number>(1)
const historyLoading = ref(false)
const historyPoints = ref<TelemetryPoint[]>([])

const rangeOptions = [
  { label: '1 小时', value: 1 },
  { label: '3 小时', value: 3 },
  { label: '6 小时', value: 6 },
  { label: '12 小时', value: 12 },
  { label: '24 小时', value: 24 },
]

const wsStatusLabel = computed(() => {
  if (wsStatus.value === 'open') return 'OPEN'
  if (wsStatus.value === 'connecting') return 'CONNECTING'
  if (wsStatus.value === 'error') return 'ERROR'
  return 'CLOSED'
})

const wsTone = computed(() => {
  if (wsStatus.value === 'open') return 'text-emerald-300'
  if (wsStatus.value === 'connecting') return 'text-amber-300'
  if (wsStatus.value === 'error') return 'text-rose-300'
  return 'text-zinc-400'
})

const deviceOptions = computed(() =>
  (deviceStore.devices ?? []).map((d) => ({
    label: `${d.deviceName || d.deviceId} · ${d.deviceId}`,
    value: d.deviceId,
  })),
)

const chartXMin = computed(() => Date.now() - historyRange.value * 3600_000)
const chartXMax = computed(() => Date.now())

const realTimePoints = computed(() =>
  activeDeviceId.value ? deviceStore.telemetryByDevice[activeDeviceId.value] ?? [] : [],
)

const mergedPoints = computed(() => {
  const seen = new Set<number>()
  const all: TelemetryPoint[] = []
  // history first, then real-time
  for (const p of historyPoints.value) {
    if (p.t && !seen.has(p.t)) {
      seen.add(p.t)
      all.push(p)
    }
  }
  for (const p of realTimePoints.value) {
    if (p.t && !seen.has(p.t)) {
      seen.add(p.t)
      all.push(p)
    }
  }
  all.sort((a, b) => (a.t ?? 0) - (b.t ?? 0))
  return all
})

const lastPoint = computed(() => {
  const pts = mergedPoints.value
  return pts.length ? pts[pts.length - 1] : null
})

const lastPower = computed(() => lastPoint.value?.active_power ?? null)

function lineSeries(name: string, color: string, accessor: (p: TelemetryPoint) => number | null) {
  return [
    {
      name,
      color,
      values: mergedPoints.value.map((p) => ({ t: p.t!, v: accessor(p) })),
    },
  ]
}

const voltageSeries = computed(() => lineSeries('voltage_a', '#2F7DFF', (p) => p.voltage_a ?? null))
const currentSeries = computed(() => lineSeries('current_a', '#1DD6A4', (p) => p.current_a ?? null))
const powerSeries = computed(() => lineSeries('active_power', '#FF4D6D', (p) => p.active_power ?? null))

let ws: DashboardWsClient | null = null

function handleWsMessage(msg: WsDeviceMessage) {
  deviceStore.applyWsMessage(msg)
  if (!activeDeviceId.value && deviceStore.devices.length) activeDeviceId.value = deviceStore.devices[0].deviceId
}

function clearAlarms() {
  deviceStore.alarms = []
}

async function loadHistory() {
  if (!activeDeviceId.value) return
  historyLoading.value = true
  try {
    const res = await getTelemetryHistoryApi(activeDeviceId.value, historyRange.value)
    historyPoints.value = res.data ?? []
  } catch {
    historyPoints.value = []
  } finally {
    historyLoading.value = false
  }
}

// 切换设备或时间范围时重新加载历史数据
watch([activeDeviceId, historyRange], () => {
  historyPoints.value = []
  loadHistory()
})

// 5s 定时轮询刷新（测试用）
let pollTimer: number | null = null
onMounted(async () => {
  await deviceStore.fetchAllForDashboard()
  await deviceStore.fetchStatusSummary()
  if (deviceStore.devices.length) activeDeviceId.value = deviceStore.devices[0].deviceId

  ws = new DashboardWsClient({
    onMessage: handleWsMessage,
    onStatus: (s) => (wsStatus.value = s),
  })
  ws.connect()

  pollTimer = window.setInterval(() => {
    if (activeDeviceId.value) loadHistory()
  }, 5000)
})

onBeforeUnmount(() => {
  if (pollTimer !== null) window.clearInterval(pollTimer)
  ws?.close()
  ws = null
})
</script>

