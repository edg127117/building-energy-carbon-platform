<template>
  <div
    class="chart-view"
    :aria-busy="loading"
    :aria-label="accessibleLabel"
  >
    <div
      ref="chartElement"
      class="chart-view__canvas"
      data-test="chart-canvas"
      role="img"
      :aria-label="accessibleLabel"
      :hidden="loading || empty || chartError"
    />

    <div v-if="loading" class="chart-view__status" data-test="chart-loading">
      <ElSkeleton :rows="3" animated />
    </div>

    <div v-else-if="empty" class="chart-view__status" data-test="chart-empty">
      <ElEmpty :description="t('common.empty')" />
    </div>

    <div v-else-if="chartError" class="chart-view__status" data-test="chart-error">
      <ElAlert :closable="false" :title="t('error.chart')" type="error" show-icon>
        <template #default>
          <ElButton type="primary" @click="retryChart">
            {{ t('common.retry') }}
          </ElButton>
        </template>
      </ElAlert>
    </div>
  </div>
</template>

<script setup lang="ts">
import {
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from 'vue'
import { ElAlert, ElButton, ElEmpty, ElSkeleton } from '@/shared/ui'
import { t } from '@/locales'
import { echarts, type ChartOption } from './echarts'

type OptionRecord = Record<string, unknown>

type ChartTokens = {
  series: string[]
  textPrimary?: string
  textSecondary?: string
  border?: string
  surface?: string
  fontFamily?: string
  fontSize?: number
}

const props = withDefaults(defineProps<{
  option: ChartOption
  loading?: boolean
  empty?: boolean
  accessibleLabel: string
}>(), {
  loading: false,
  empty: false,
})

const chartElement = ref<HTMLElement | null>(null)
const chartError = ref(false)
let chart: ReturnType<typeof echarts.init> | null = null
let resizeObserver: ResizeObserver | null = null
let themeObserver: MutationObserver | null = null

function canRenderChart(): boolean {
  return !props.loading && !props.empty && !chartError.value
}

function hasRenderableSize(element: HTMLElement): boolean {
  return Number.isFinite(element.clientWidth)
    && Number.isFinite(element.clientHeight)
    && element.clientWidth > 0
    && element.clientHeight > 0
}

function disposeChart(): void {
  chart?.dispose()
  chart = null
}

function handleChartFailure(): void {
  disposeChart()
  chartError.value = true
}

function syncChart(): void {
  if (!canRenderChart()) {
    disposeChart()
    return
  }

  const element = chartElement.value
  if (!element || !hasRenderableSize(element)) return

  try {
    // 中文界面不能让 ECharts 的自动 ARIA 描述退回英文。
    chart ??= echarts.init(element, undefined, { renderer: 'svg', locale: 'ZH' })
    updateChartOption()
  } catch {
    handleChartFailure()
  }
}

function updateChartOption(): void {
  if (!chart || !canRenderChart()) return

  try {
    chart.setOption(buildThemedOption(), { notMerge: true, lazyUpdate: false })
  } catch {
    handleChartFailure()
  }
}

function retryChart(): void {
  chartError.value = false
  void nextTick(syncChart)
}

function readChartTokens(): ChartTokens {
  const parent = chartElement.value?.parentElement
  if (!parent) return { series: [] }

  const styles = window.getComputedStyle(parent)
  return {
    series: [1, 2, 3, 4]
      .map((index) => readToken(styles, `--bec-chart-series-${index}`))
      .filter((value): value is string => Boolean(value)),
    textPrimary: readToken(styles, '--bec-color-text-primary'),
    textSecondary: readToken(styles, '--bec-color-text-secondary'),
    border: readToken(styles, '--bec-color-border'),
    surface: readToken(styles, '--bec-color-surface'),
    fontFamily: readToken(styles, '--bec-font-family-body'),
    fontSize: readFontSize(styles, '--bec-chart-font-size'),
  }
}

function readToken(styles: CSSStyleDeclaration, name: string): string | undefined {
  const value = styles.getPropertyValue(name).trim()
  return value || undefined
}

function readFontSize(styles: CSSStyleDeclaration, name: string): number | undefined {
  const value = readToken(styles, name)
  const size = value ? Number.parseFloat(value) : Number.NaN
  return Number.isFinite(size) && size > 0 ? size : undefined
}

function buildThemedOption(): ChartOption {
  const source: OptionRecord = isOptionRecord(props.option) ? props.option : {}
  const tokens = readChartTokens()
  const textStyle = tokenTextStyle(tokens, tokens.textPrimary)
  const result: OptionRecord = { ...source }

  if (tokens.series.length > 0) result.color = tokens.series
  if (Object.keys(textStyle).length > 0) {
    result.textStyle = mergeRecord(source.textStyle, textStyle)
  }

  result.tooltip = mapComponentOptions(source.tooltip, (option) => ({
    ...option,
    ...definedValues({
      backgroundColor: tokens.surface,
      borderColor: tokens.border,
    }),
    textStyle: mergeRecord(option.textStyle, textStyle),
    confine: true,
    appendToBody: false,
    // richText 由当前实例绘制，调用方不能借 appendTo 将 Tooltip 挂到 body。
    renderMode: 'richText',
  }), true)

  if ('legend' in source) {
    result.legend = mapComponentOptions(source.legend, (option) => ({
      ...option,
      textStyle: mergeRecord(
        option.textStyle,
        tokenTextStyle(tokens, tokens.textSecondary),
      ),
    }))
  }

  if ('xAxis' in source) result.xAxis = mapComponentOptions(source.xAxis, (option) => themeAxis(option, tokens))
  if ('yAxis' in source) result.yAxis = mapComponentOptions(source.yAxis, (option) => themeAxis(option, tokens))
  result.aria = mergeRecord(source.aria, {
    enabled: true,
    description: props.accessibleLabel,
  })

  return result as unknown as ChartOption
}

function themeAxis(option: OptionRecord, tokens: ChartTokens): OptionRecord {
  return {
    ...option,
    axisLabel: mergeRecord(
      option.axisLabel,
      tokenTextStyle(tokens, tokens.textSecondary),
    ),
    axisLine: mergeRecord(option.axisLine, {
      lineStyle: mergeRecord(option.axisLine && isOptionRecord(option.axisLine)
        ? option.axisLine.lineStyle
        : undefined, definedValues({ color: tokens.border })),
    }),
    splitLine: mergeRecord(option.splitLine, {
      lineStyle: mergeRecord(option.splitLine && isOptionRecord(option.splitLine)
        ? option.splitLine.lineStyle
        : undefined, definedValues({ color: tokens.border })),
    }),
  }
}

function tokenTextStyle(tokens: ChartTokens, color: string | undefined): OptionRecord {
  return definedValues({
    color,
    fontFamily: tokens.fontFamily,
    fontSize: tokens.fontSize,
  })
}

function mapComponentOptions(
  value: unknown,
  transform: (option: OptionRecord) => OptionRecord,
  createWhenMissing = false,
): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => transform(isOptionRecord(item) ? item : {}))
  }
  if (isOptionRecord(value)) return transform(value)
  return createWhenMissing ? transform({}) : value
}

function mergeRecord(value: unknown, additions: OptionRecord): OptionRecord {
  return {
    ...(isOptionRecord(value) ? value : {}),
    ...additions,
  }
}

function definedValues(values: OptionRecord): OptionRecord {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => value !== undefined))
}

function isOptionRecord(value: unknown): value is OptionRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function startResizeObserver(): void {
  const element = chartElement.value
  if (!element || typeof ResizeObserver === 'undefined') return

  resizeObserver = new ResizeObserver(() => {
    if (!canRenderChart() || !hasRenderableSize(element)) return
    if (!chart) {
      syncChart()
      return
    }
    try {
      chart.resize()
    } catch {
      handleChartFailure()
    }
  })
  resizeObserver.observe(element)
}

function startThemeObserver(): void {
  if (typeof MutationObserver === 'undefined') return

  themeObserver = new MutationObserver((records) => {
    if (records.some((record) => record.type === 'attributes' && record.attributeName === 'data-theme')) {
      updateChartOption()
    }
  })
  themeObserver.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ['data-theme'],
  })
}

onMounted(() => {
  startResizeObserver()
  startThemeObserver()
  syncChart()
})

watch(
  () => props.option,
  () => updateChartOption(),
  { deep: true, flush: 'post' },
)

watch(
  () => [props.loading, props.empty],
  () => {
    void nextTick(syncChart)
  },
  { flush: 'post' },
)

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  themeObserver?.disconnect()
  themeObserver = null
  disposeChart()
})
</script>

<style scoped>
.chart-view,
.chart-view__canvas,
.chart-view__status {
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 100%;
}
</style>
