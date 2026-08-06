<template>
  <!--
    统一历史面板只组织模式、范围、测点选择和状态展示。请求、按建筑记忆、竞态隔离
    与 DTO 适配全部由 useHvacHistoryTrends 负责，图表由 HvacTrendChart 纯渲染。
  -->
  <div class="history-panel">
    <header class="history-heading">
      <div>
        <h2>历史运行趋势</h2>
        <p>真实 TDengine 历史 · 服务端自动分辨率 · 缺口与 Q0/Q1/Q2 原样保留</p>
      </div>
      <a-button
        :loading="loading"
        :disabled="!buildingId || (mode === 'points' && selectedPointIds.length === 0)"
        @click="refresh"
      >
        刷新趋势
      </a-button>
    </header>

    <div class="history-controls" aria-label="历史趋势查询条件">
      <div class="control-block">
        <span class="control-label">趋势对象</span>
        <a-segmented
          :value="mode"
          :options="modeOptions"
          @change="handleModeChange"
        />
      </div>

      <div class="control-block control-block--range">
        <span class="control-label">时间范围</span>
        <div class="range-buttons">
          <a-button
            v-for="item in presetOptions"
            :key="item.value"
            size="small"
            :type="preset === item.value ? 'primary' : 'default'"
            @click="handlePresetChange(item.value)"
          >
            {{ item.label }}
          </a-button>
        </div>
      </div>

      <div v-if="preset === 'custom'" class="control-block control-block--custom">
        <span class="control-label">自定义区间</span>
        <a-range-picker
          show-time
          format="YYYY-MM-DD HH:mm"
          :allow-clear="true"
          @change="handleCustomRangeChange"
        />
        <span v-if="customValidation" class="control-error" role="alert">
          {{ customValidation }}
        </span>
      </div>

      <div v-if="mode === 'points'" class="control-block control-block--points">
        <span class="control-label">
          原始测点
          <b>已选 {{ selectedPointIds.length }}/8</b>
        </span>
        <a-select
          mode="multiple"
          :value="selectedPointIds"
          :options="pointSelectOptions"
          :max-tag-count="4"
          placeholder="从当前建筑 19 个冻结测点中选择"
          @change="handlePointSelection"
        />
      </div>
    </div>

    <div class="history-meta" aria-live="polite">
      <span>
        <b>当前模式</b>{{ mode === 'indicators' ? '能效指标' : '原始测点' }}
      </span>
      <span>
        <b>范围</b>{{ rangeSummary }}
      </span>
      <span>
        <b>分辨率</b>{{ resolutionMinutes ? `${resolutionMinutes} 分钟` : '自动分辨率' }}
      </span>
      <span>
        <b>最近成功</b>{{ updatedSummary }}
      </span>
      <span>
        <b>图表分组</b>{{ groupingSummary }}
      </span>
    </div>

    <a-alert
      v-if="stale"
      class="history-alert"
      type="warning"
      show-icon
      message="趋势刷新失败，当前显示上次成功结果"
      :description="error?.message"
    />

    <a-alert
      v-if="hasAnyData && emptySeriesNames.length"
      class="history-alert"
      type="info"
      show-icon
      message="部分序列暂无数据"
      :description="emptySeriesNames.join('、')"
    />

    <div v-if="!buildingId" class="history-state">
      <a-empty description="请先选择一个可访问建筑" />
    </div>

    <div v-else-if="loading && groups.length === 0" class="history-state">
      <a-skeleton active :paragraph="{ rows: 7 }" />
      <p>正在查询真实历史趋势</p>
    </div>

    <div
      v-else-if="error && !stale"
      class="history-state history-state--error"
      role="alert"
    >
      <strong>{{ error.message }}</strong>
      <p v-if="error.status">HTTP {{ error.status }}</p>
      <a-button
        v-if="error.status !== 403"
        type="primary"
        @click="refresh"
      >
        重试趋势请求
      </a-button>
    </div>

    <div
      v-else-if="mode === 'points' && selectedPointIds.length === 0"
      class="history-state"
    >
      <a-empty description="请选择 1～8 个测点查看历史趋势" />
      <p>首次进入不默认选择；保存后的有效选择按建筑恢复。</p>
    </div>

    <div
      v-else-if="mode === 'indicators' && indicatorIds.length === 0"
      class="history-state"
    >
      <a-empty description="当前建筑暂无可查询的能效指标" />
    </div>

    <div v-else-if="groups.length > 0 && !hasAnyData" class="history-state">
      <a-empty description="所选范围内无成功历史数据" />
      <p>失败分钟保持缺口，不会转换为 0 或模拟值。</p>
    </div>

    <div v-else-if="groups.length === 0" class="history-state">
      <a-empty description="当前条件尚无历史结果" />
    </div>

    <div v-else class="history-charts">
      <HvacTrendChart
        v-for="group in groups"
        :key="group.unit || 'dimensionless'"
        :group="group"
        :from="responseRange?.from ?? 0"
        :to="responseRange?.to ?? 0"
        :resolution-minutes="resolutionMinutes ?? 1"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import HvacTrendChart from './HvacTrendChart.vue'
import { useHvacHistoryTrends } from '@/composables/useHvacHistoryTrends'
import {
  validateHistoryRange,
  type HvacHistoryMode,
  type HvacHistoryPreset,
} from '@/domain/hvacHistoryTrends'
import type { DashboardIndicatorView } from '@/domain/hvacDashboard'
import type { SnapshotPoint } from '@/types/hvac'

const props = defineProps<{
  buildingId: string | null
  indicators: DashboardIndicatorView[]
  snapshotPoints: SnapshotPoint[]
}>()

const {
  mode,
  preset,
  pointOptions,
  selectedPointIds,
  groups,
  responseRange,
  resolutionMinutes,
  loading,
  error,
  stale,
  updatedAt,
  setBuildingContext,
  setMode,
  setPreset,
  setCustomRange,
  setSelectedPointIds,
  refresh,
  dispose,
} = useHvacHistoryTrends()

const customValidation = ref<string | null>(null)
const modeOptions = [
  { label: '能效指标', value: 'indicators' },
  { label: '原始测点', value: 'points' },
]
const presetOptions: Array<{ label: string; value: HvacHistoryPreset }> = [
  { label: '1 小时', value: '1h' },
  { label: '6 小时', value: '6h' },
  { label: '最近 24 小时', value: '24h' },
  { label: '7 天', value: '7d' },
  { label: '自定义', value: 'custom' },
]

const indicatorIds = computed(() =>
  props.indicators
    .map((item) => item.indicatorId)
    .filter((id): id is string => Boolean(id)),
)

const indicatorSignature = computed(() => indicatorIds.value.join(','))
const pointSignature = computed(() =>
  props.snapshotPoints.map((point) => point.pointId).join(','),
)
const contextKey = computed(() =>
  `${props.buildingId ?? ''}|${indicatorSignature.value}|${pointSignature.value}`,
)

const pointSelectOptions = computed(() => {
  const selected = new Set(selectedPointIds.value)
  const full = selected.size >= 8
  return pointOptions.value.map((option) => ({
    value: option.pointId,
    label: `${option.label} · ${option.pointCode}${option.unit ? ` · ${option.unit}` : ''}`,
    disabled: full && !selected.has(option.pointId),
  }))
})

const allSeries = computed(() => groups.value.flatMap((group) => group.series))

/** 汇总当前真实序列与单位分组，明确四项指标会因同单位合并而显示为三张图。 */
const groupingSummary = computed(() => {
  if (groups.value.length === 0) return '尚无图表分组'
  const subject = mode.value === 'indicators'
    ? `${allSeries.value.length} 项指标`
    : `${allSeries.value.length} 个测点`
  const comparison = mode.value === 'indicators' ? ' · 同单位合并对比' : ''
  return `${subject} · ${groups.value.length} 个单位图表${comparison}`
})
const hasAnyData = computed(() => allSeries.value.some((series) =>
  series.points.some((point) => point.average !== null),
))
const emptySeriesNames = computed(() => allSeries.value
  .filter((series) => !series.points.some((point) => point.average !== null))
  .map((series) => series.label))

const rangeSummary = computed(() => {
  if (!responseRange.value) return presetLabel(preset.value)
  return `${formatTime(responseRange.value.from)} ～ ${formatTime(responseRange.value.to)} [from,to)`
})
const updatedSummary = computed(() =>
  updatedAt.value === null ? '尚未成功' : formatTime(updatedAt.value),
)

let lastBuildingId: string | null = null
let lastHadIndicators = false
let lastHadPoints = false

/**
 * 建筑改变或该建筑首次获得完整指标/测点 ID 时同步一次上下文。
 * 后续 30 秒轮询即使返回新对象，只要 ID 集合不变就不会重新查询历史。
 */
watch(contextKey, () => {
  if (!props.buildingId) return
  const buildingChanged = props.buildingId !== lastBuildingId
  const hasIndicators = indicatorIds.value.length > 0
  const hasPoints = props.snapshotPoints.length > 0
  const firstIndicators = hasIndicators && !lastHadIndicators
  const firstPoints = hasPoints && !lastHadPoints
  if (!buildingChanged && !firstIndicators && !firstPoints) return
  lastBuildingId = props.buildingId
  lastHadIndicators = hasIndicators
  lastHadPoints = hasPoints
  void setBuildingContext({
    buildingId: props.buildingId,
    indicatorIds: indicatorIds.value,
    points: props.snapshotPoints,
  })
}, { immediate: true })

/** 模式切换只交给状态层处理，面板不直接调用任何历史 API。 */
function handleModeChange(value: string | number): void {
  void setMode(value as HvacHistoryMode)
}

/** 快捷范围切换清除本地日期错误，再由状态层按触发时整分钟查询。 */
function handlePresetChange(value: HvacHistoryPreset): void {
  customValidation.value = null
  void setPreset(value)
}

type PickerValue = { valueOf: () => number }

/**
 * 日期控件值先在页面即时校验；合法 Unix 毫秒才交给状态层，避免无效请求触发后端扫描。
 */
function handleCustomRangeChange(values: PickerValue[] | null): void {
  const from = values?.[0]?.valueOf() ?? null
  const to = values?.[1]?.valueOf() ?? null
  const validation = validateHistoryRange(from, to)
  customValidation.value = validation
  if (!validation) void setCustomRange(from, to)
}

/** 多选变化由状态层再次过滤当前建筑 ID、去重并限制为前八项。 */
function handlePointSelection(value: unknown): void {
  const ids = Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : []
  void setSelectedPointIds(ids)
}

/** 把快捷范围代码转换为无响应时的查询摘要。 */
function presetLabel(value: HvacHistoryPreset): string {
  return presetOptions.find((item) => item.value === value)?.label ?? '自动范围'
}

/** 历史范围和更新时间统一按浏览器本地时区展示到分钟。 */
function formatTime(value: number): string {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value))
}

/** 页面卸载时关闭状态层写回入口，使迟到的 TDengine 响应失效。 */
onBeforeUnmount(dispose)
</script>

<style scoped>
.history-panel {
  min-width: 0;
  color: #c8d9e9;
}

.history-heading {
  min-height: 62px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 12px 16px;
  border-bottom: 1px solid rgba(127, 167, 201, 0.12);
}

.history-heading h2 {
  margin: 0;
  color: #edf6ff;
  font-size: 16px;
  font-weight: 650;
}

.history-heading p {
  margin: 4px 0 0;
  color: #7890a7;
  font-size: 11px;
  line-height: 1.5;
}

.history-controls {
  display: grid;
  grid-template-columns: minmax(180px, auto) minmax(420px, 1fr);
  gap: 14px 20px;
  padding: 14px 16px;
  background: rgba(5, 17, 30, 0.48);
  border-bottom: 1px solid rgba(127, 167, 201, 0.10);
}

.control-block {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.control-block--custom,
.control-block--points {
  grid-column: 1 / -1;
}

.control-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  color: #8299af;
  font-size: 11px;
}

.control-label b {
  color: #55b9ff;
  font-weight: 600;
}

.range-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.control-error {
  color: #ff9a86;
  font-size: 11px;
}

.history-meta {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 1px;
  background: rgba(127, 167, 201, 0.10);
  border-bottom: 1px solid rgba(127, 167, 201, 0.10);
}

.history-meta span {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 9px 12px;
  background: #081727;
  color: #a9bdcf;
  font-size: 11px;
  overflow-wrap: anywhere;
}

.history-meta b {
  color: #7890a7;
  font-size: 10px;
  font-weight: 500;
}

.history-alert {
  margin: 12px 16px 0;
}

.history-state {
  min-height: 260px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 10px;
  padding: 28px 16px;
  color: #7890a7;
  text-align: center;
}

.history-state p {
  max-width: 62ch;
  margin: 0;
  font-size: 11px;
  line-height: 1.6;
}

.history-state--error strong {
  color: #ffc0b4;
  font-size: 14px;
}

.history-charts {
  min-width: 0;
}

:deep(.ant-segmented) {
  background: rgba(91, 129, 158, 0.14);
}

:deep(.ant-select) {
  width: 100%;
}

:deep(.ant-picker) {
  width: min(520px, 100%);
}

@media (max-width: 900px) {
  .history-controls {
    grid-template-columns: 1fr;
  }

  .control-block--custom,
  .control-block--points {
    grid-column: auto;
  }

  .history-meta {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .history-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .history-controls {
    padding: 12px;
  }

  .history-meta {
    grid-template-columns: 1fr;
  }
}
</style>
