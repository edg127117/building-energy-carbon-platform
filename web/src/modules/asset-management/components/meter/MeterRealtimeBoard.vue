<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, watch } from 'vue'
import { ElAlert, ElEmpty, ElSkeleton } from '@/shared/ui'
import { t } from '@/locales'
import type { AssetEquipmentDetail } from '../../models/assets'
import { useEquipmentReadings } from '../../composables/use-equipment-readings'
import { useEquipmentTrendHistory } from '../../composables/use-equipment-trend-history'
import SinglePhaseMeterBoard from './SinglePhaseMeterBoard.vue'
import ThreePhaseMeterBoard from './ThreePhaseMeterBoard.vue'
import { getMeterPhaseType } from './meter-display'

const props = defineProps<{
  equipment: AssetEquipmentDetail
}>()

const { readings, loading, error, load, clear: clearReadings } = useEquipmentReadings()
const trendHistory = useEquipmentTrendHistory()
const POLL_INTERVAL_MS = 60_000 // 实时追踪静默轮询间隔：1分钟（适配电表3分钟上报周期，降低无效请求）
let pollTimer: ReturnType<typeof setInterval> | null = null

const phase = computed(() => getMeterPhaseType(props.equipment, readings.value?.points))

async function fetchReadingsSilently() {
  try {
    const result = await load(props.equipment.equipmentId, Boolean(readings.value))
    if (result && result.points) {
      trendHistory.appendRealtimeReading(result.points, result.generatedAt, props.equipment.equipmentId, phase.value)
    }
  } catch {
    // 静默轮询错误保留在 error 中，不中断前端交互
  }
}

function startPolling() {
  stopPolling()
  trendHistory.init(props.equipment.equipmentId, phase.value)
  fetchReadingsSilently()
  pollTimer = setInterval(fetchReadingsSilently, POLL_INTERVAL_MS)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  clearReadings()
  trendHistory.clear()
}

watch(
  () => props.equipment.equipmentId,
  () => {
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
    <!-- 初次加载骨架屏（仅在无任何读数时展示，后续1分钟静默轮询不闪屏） -->
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
        :trend-records="trendHistory.records.value"
        :loading="loading"
        :history-loading="trendHistory.loading.value"
        :api-pending="trendHistory.apiPending.value"
        :range-type="trendHistory.rangeType.value"
        :custom-range="trendHistory.customRange.value"
        :is-large-dataset="trendHistory.isLargeDataset.value"
        @change-range="(type) => trendHistory.setRangeType(type, props.equipment.equipmentId, '3P')"
        @change-custom-range="(range) => trendHistory.setCustomRange(range, props.equipment.equipmentId, '3P')"
        @refresh-history="() => trendHistory.refresh(props.equipment.equipmentId, '3P')"
      />
      <SinglePhaseMeterBoard
        v-else
        :points="readings.points"
        :trend-records="trendHistory.records.value"
        :loading="loading"
        :history-loading="trendHistory.loading.value"
        :api-pending="trendHistory.apiPending.value"
        :range-type="trendHistory.rangeType.value"
        :custom-range="trendHistory.customRange.value"
        :is-large-dataset="trendHistory.isLargeDataset.value"
        @change-range="(type) => trendHistory.setRangeType(type, props.equipment.equipmentId, '1P')"
        @change-custom-range="(range) => trendHistory.setCustomRange(range, props.equipment.equipmentId, '1P')"
        @refresh-history="() => trendHistory.refresh(props.equipment.equipmentId, '1P')"
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
