<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElAlert, ElEmpty, ElSkeleton } from '@/shared/ui'
import { t } from '@/locales'
import type { AssetEquipmentDetail, AssetPointReading } from '../../models/assets'
import { useEquipmentReadings } from '../../composables/use-equipment-readings'
import SinglePhaseMeterBoard from './SinglePhaseMeterBoard.vue'
import ThreePhaseMeterBoard from './ThreePhaseMeterBoard.vue'
import type { MeterTrendRecord } from './MeterRealtimeTrendChart.vue'
import { getMeterPhaseType } from './meter-display'

const props = defineProps<{
  equipment: AssetEquipmentDetail
}>()

const { readings, loading, error, load, clear } = useEquipmentReadings()
const trendRecords = ref<MeterTrendRecord[]>([])
let pollTimer: ReturnType<typeof setInterval> | null = null

const phase = computed(() => getMeterPhaseType(props.equipment, readings.value?.points))

function appendTrendPoint(pts: AssetPointReading[], generatedAt: number) {
  function getVal(code: string): number | null {
    const p = pts.find(x => (x.pointCode ?? '').toUpperCase() === code)
    return p ? p.value : null
  }

  const pTotal = getVal('P_TOTAL') ?? getVal('POWER')
  const ia = getVal('I_A') ?? getVal('CURRENT')
  const ib = getVal('I_B')
  const ic = getVal('I_C')
  const u = getVal('VOLTAGE') ?? getVal('U_A')
  const q = pts.length > 0 ? pts[0].dataQuality : 0

  const newRecord: MeterTrendRecord = {
    time: generatedAt || Date.now(),
    power: pTotal,
    currentA: ia,
    currentB: ib,
    currentC: ic,
    voltage: u,
    dataQuality: q,
  }

  // 维护滑动窗口，最大保留 30 个时间点
  trendRecords.value.push(newRecord)
  if (trendRecords.value.length > 30) {
    trendRecords.value.shift()
  }
}

async function fetchReadingsSilently() {
  try {
    const result = await load(props.equipment.equipmentId)
    if (result && result.points) {
      appendTrendPoint(result.points, result.generatedAt)
    }
  } catch {
    // 静默轮询错误保留在 error 中，不中断前端交互
  }
}

function startPolling() {
  stopPolling()
  fetchReadingsSilently()
  pollTimer = setInterval(fetchReadingsSilently, 10000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  clear()
}

watch(
  () => props.equipment.equipmentId,
  () => {
    trendRecords.value = []
    startPolling()
  },
)

onMounted(() => {
  startPolling()
})

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<template>
  <div class="meter-realtime-board">
    <!-- 初次加载骨架屏（仅在无任何读数时展示，后续10秒静默轮询不闪屏） -->
    <ElSkeleton v-if="loading && !readings" animated :rows="6" class="board-skeleton" />

    <ElAlert
      v-else-if="error && !readings"
      :title="error"
      type="warning"
      show-icon
      :closable="false"
      class="board-alert"
    />

    <template v-else-if="readings?.points && readings.points.length > 0">
      <ThreePhaseMeterBoard
        v-if="phase === '3P'"
        :points="readings.points"
        :trend-records="trendRecords"
        :loading="loading"
      />
      <SinglePhaseMeterBoard
        v-else
        :points="readings.points"
        :trend-records="trendRecords"
        :loading="loading"
      />
    </template>

    <ElEmpty
      v-else
      :description="t('assetManagement.meter.waitingFirstReport')"
      class="board-empty"
    />
  </div>
</template>

<style scoped>
.meter-realtime-board {
  min-height: var(--bec-chart-height);
  display: grid;
  gap: var(--bec-space-section);
}

.board-skeleton,
.board-alert,
.board-empty {
  margin-block: var(--bec-space-section);
}
</style>
