<script setup lang="ts">
import { computed, type Component } from 'vue'
import { Building2, Users, BookOpen } from '@/shared/ui'
import { t } from '@/locales'
import intensityCopy from '../locales/carbon-intensity'
import type { ScreenIntensityData, ScreenIntensityBenchmarks } from '../models/screen-summaries'
import { formatScreenReading } from '../models/screen-reading-format'

type RatioState = 'missing' | 'in-range' | 'out-of-range'

interface IntensityRow {
  key: 'area' | 'person'
  icon: Component
  label: string
  value: number | null | undefined
  unit: string | undefined
  ratio: number | null | undefined
  ratioState: RatioState
  visualPercentage: number | null
}

const props = defineProps<{ data?: ScreenIntensityData; showBenchmarks?: boolean; benchmarks?: ScreenIntensityBenchmarks; prototypeReference?: boolean }>()
const numberFormatter = new Intl.NumberFormat('zh-CN')
function isFiniteNumber(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function displayNumber(value: number | null | undefined): string {
  return isFiniteNumber(value) ? formatScreenReading(value) : t('largeScreen.scoreMissing')
}

function displayUnit(value: string | undefined): string {
  return value?.trim() || t('largeScreen.scoreMissing')
}

function ratioState(value: number | null | undefined): RatioState {
  if (!isFiniteNumber(value)) return 'missing'
  return value < 0 || value > 100 ? 'out-of-range' : 'in-range'
}

// 上游定义比例语义；这里只裁剪可绘制长度，文本和属性始终保留原始比例。
function visualPercentage(value: number | null | undefined): number | null {
  if (!isFiniteNumber(value)) return null
  return Math.min(100, Math.max(0, value))
}

function displayRatio(value: number | null | undefined): string {
  return isFiniteNumber(value)
    ? t('largeScreen.scoreRatioValue', { value: numberFormatter.format(value) })
    : t('largeScreen.scoreMissing')
}

const rows = computed<IntensityRow[]>(() => [
  {
    key: 'area',
    icon: Building2,
    label: t('largeScreen.scoreAreaLabel'),
    value: props.data?.areaValue,
    unit: props.data?.areaUnit ?? t('largeScreen.redesignPerAreaUnit'),
    ratio: props.data?.areaPercentage,
    ratioState: ratioState(props.data?.areaPercentage),
    visualPercentage: visualPercentage(props.data?.areaPercentage),
  },
  {
    key: 'person',
    icon: Users,
    label: t('largeScreen.scorePersonLabel'),
    value: props.data?.personValue,
    unit: props.data?.personUnit ?? t('largeScreen.redesignPerPersonUnit'),
    ratio: props.data?.personPercentage,
    ratioState: ratioState(props.data?.personPercentage),
    visualPercentage: visualPercentage(props.data?.personPercentage),
  },
])
const displayedRows = computed(() => props.showBenchmarks ? rows.value.filter(row => row.key === 'area') : rows.value)
const referenceRows = computed(() => [
  { key: 'constraint' as const, label: t('largeScreen.intensityConstraintLabel') },
  { key: 'baseline' as const, label: t('largeScreen.intensityBaselineLabel') },
  { key: 'guide' as const, label: t('largeScreen.intensityGuideLabel') },
])
</script>

<template>
  <section class="intensity-summary" :class="{ 'has-benchmarks': showBenchmarks }" :aria-label="t(showBenchmarks ? 'largeScreen.carbonIntensityTitle' : 'largeScreen.scoreSummary')">
    <article v-for="row in displayedRows" :key="row.key" class="intensity-row" :class="['row-' + row.key, { 'has-out-of-range-ratio': row.ratioState === 'out-of-range' }]">
      <span class="intensity-icon" aria-hidden="true"><component :is="row.icon" :size="22" /></span>
      <div class="intensity-main">
        <div class="intensity-label-line">
          <span class="intensity-label">{{ row.label }}</span>
          <div class="intensity-value">
            <strong :data-testid="'score-' + row.key + '-value'">{{ displayNumber(row.value) }}</strong>
            <span>{{ displayUnit(row.unit) }}</span>
          </div>
        </div>
        <div class="intensity-track" :data-testid="'score-' + row.key + '-track'" :data-state="row.ratioState" :data-raw-ratio="isFiniteNumber(row.ratio) ? String(row.ratio) : undefined" :aria-label="row.ratioState === 'missing' ? t('largeScreen.scoreRatioMissing') : t('largeScreen.scoreRatioAccessible', { label: row.label, ratio: displayRatio(row.ratio), state: row.ratioState === 'out-of-range' ? t('largeScreen.scoreRatioOutOfRange') : '' })">
          <span v-if="row.visualPercentage !== null" class="intensity-fill" :data-testid="'score-' + row.key + '-bar'" :style="{ width: row.visualPercentage + '%' }" />
          <span v-else class="intensity-ratio-missing">{{ t('largeScreen.scoreRatioMissing') }}</span>
        </div>
      </div>
    </article>
    <div v-if="showBenchmarks" class="intensity-benchmark-section">
      <div class="intensity-benchmarks" data-testid="intensity-benchmarks"><article v-for="entry in referenceRows" :key="entry.key" class="benchmark-card" :class="'tone-' + entry.key" :data-testid="'benchmark-' + entry.key"><div class="benchmark-head"><i aria-hidden="true" /><span class="benchmark-label">{{ entry.label }}</span></div><div class="benchmark-reading"><strong>{{ displayNumber(benchmarks?.[entry.key]) }}</strong><small>{{ prototypeReference ? t('largeScreen.redesignPerAreaUnit') : displayUnit(benchmarks?.unit) }}</small></div></article></div>
      <footer class="benchmark-source-note" data-testid="intensity-benchmark-source"><span class="benchmark-source-pill"><BookOpen class="benchmark-source-icon" :size="12" aria-hidden="true" /><span class="benchmark-source-label">{{ t('largeScreen.intensityBenchmarkSourceLabel') }}</span><i class="benchmark-source-divider" aria-hidden="true" /><span class="benchmark-source-doc" :title="prototypeReference ? undefined : benchmarks?.sourceReference">{{ prototypeReference ? t('largeScreen.intensityBenchmarkSource') : (benchmarks?.sourceName || intensityCopy.sourceMissing) }} {{ prototypeReference ? '' : benchmarks?.documentVersion }}</span><b class="benchmark-source-zone">{{ prototypeReference ? t('largeScreen.intensityBenchmarkZone') : benchmarks?.climateLabel }}</b></span></footer>
    </div>
  </section>
</template>

<style scoped src="./screen-intensity-summary.css"></style>
