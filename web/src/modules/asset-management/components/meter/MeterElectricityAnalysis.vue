<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ChartView from '@/shared/charts/ChartView.vue'
import type { ChartOption } from '@/shared/charts/echarts'
import { ElAlert, ElButton, ElEmpty, ElOption, ElSelect, ElTable, ElTableColumn, ElTag, RefreshCw } from '@/shared/ui'
import { t } from '@/locales'
import { getMeterElectricity } from '../../api/meter-electricity'
import type { AssetEquipment } from '../../models/assets'
import type { MeterElectricityView } from '../../models/meter-electricity'

const props = defineProps<{ meters: AssetEquipment[]; initialMeterId?: string }>()
const selectedId = ref(props.initialMeterId || '')
const days = ref<7 | 14 | 30>(7)
const loading = ref(false)
const error = ref('')
const result = ref<MeterElectricityView | null>(null)
let requestNumber = 0

watch(() => props.initialMeterId, value => { if (value) selectedId.value = value })
watch(() => props.meters, items => {
  if (!items.some(item => item.equipmentId === selectedId.value)) selectedId.value = items[0]?.equipmentId || ''
}, { immediate: true })

async function load() {
  const sequence = ++requestNumber
  const meter = props.meters.find(item => item.equipmentId === selectedId.value)
  result.value = null
  error.value = ''
  if (!meter) return
  loading.value = true
  try {
    const data = await getMeterElectricity(meter.equipmentId, meter.buildingId, days.value)
    if (sequence === requestNumber) result.value = data
  } catch (cause) {
    if (sequence === requestNumber) error.value = cause instanceof Error ? cause.message : t('assetManagement.electricity.loadFailed')
  } finally {
    if (sequence === requestNumber) loading.value = false
  }
}
watch([selectedId, days], () => { void load() }, { immediate: true })

const orderedDays = computed(() => [...(result.value?.days ?? [])].reverse())
const latest = computed(() => orderedDays.value[0] ?? null)
const availableCount = computed(() => result.value?.days.filter(day => day.status === 'AVAILABLE').length ?? 0)
const breakdownOption = computed<ChartOption>(() => ({
  tooltip: { show: false },
  series: [{ type: 'pie', radius: ['64%', '84%'], center: ['50%', '50%'], silent: true,
    label: { show: false }, data: [{ value: 1, itemStyle: { color: 'var(--bec-color-border)' } }],
  }],
}))
const chartOption = computed<ChartOption>(() => ({
  grid: { top: 24, right: 24, bottom: 44, left: 60 },
  tooltip: { trigger: 'axis', valueFormatter: (value: unknown) => value == null ? t('common.missing') : `${formatNumber(Number(value))} kWh` },
  xAxis: { type: 'category', data: result.value?.days.map(day => day.date.slice(5)) ?? [], axisLabel: { rotate: days.value === 30 ? 45 : 0 } },
  yAxis: { type: 'value', name: 'kWh', min: 0 },
  series: [{ type: 'bar', name: t('assetManagement.electricity.dailyKwh'), data: result.value?.days.map(day => day.kwh) ?? [], barMaxWidth: 32, itemStyle: { color: 'var(--bec-color-action-primary)', borderRadius: [4, 4, 0, 0] } }],
}))

function formatNumber(value: number | null | undefined, digits = 2) {
  return value == null ? '—' : new Intl.NumberFormat('zh-CN', { maximumFractionDigits: digits, minimumFractionDigits: digits }).format(value)
}
function formatTime(value: number | null) {
  return value == null ? '—' : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(value)
}
function statusText(reason: string) {
  return t(`assetManagement.electricity.reasons.${reason}`)
}
</script>

<template>
  <div class="electricity-analysis">
    <div class="analysis-shell">
      <aside class="analysis-rail" :aria-label="t('assetManagement.electricity.chooseMeter')">
        <div class="rail-block">
          <label for="meter-period">{{ t('assetManagement.electricity.period') }}</label>
          <ElSelect id="meter-period" v-model="days" class="period-select">
            <ElOption :value="7" :label="t('assetManagement.electricity.lastDays', { days: 7 })" />
            <ElOption :value="14" :label="t('assetManagement.electricity.lastDays', { days: 14 })" />
            <ElOption :value="30" :label="t('assetManagement.electricity.lastDays', { days: 30 })" />
          </ElSelect>
          <span class="rail-help">{{ t('assetManagement.electricity.completedOnly') }}</span>
        </div>
        <div class="rail-block rail-meters">
          <h2>{{ t('assetManagement.electricity.chooseMeter') }}</h2>
          <div class="meter-tree-heading">{{ t('assetManagement.electricity.meterTreeHeading') }}</div>
          <button
            v-for="meter in meters" :key="meter.equipmentId" type="button" class="meter-tree-item"
            :class="{ 'meter-tree-item-active': selectedId === meter.equipmentId }"
            :aria-current="selectedId === meter.equipmentId ? 'true' : undefined" @click="selectedId = meter.equipmentId"
          >
            <span class="meter-tree-dot" aria-hidden="true" />
            <span><strong>{{ meter.equipmentName }}</strong><small>{{ meter.equipmentCode }}</small></span>
          </button>
          <ElEmpty v-if="!meters.length" :description="t('assetManagement.powerMonitoring.emptyMeters')" />
        </div>
        <p class="rail-note">{{ t('assetManagement.electricity.scopeNote') }}</p>
      </aside>

      <main class="analysis-main">
        <div class="analysis-toolbar">
          <span>{{ t('assetManagement.electricity.singleMeter') }}</span>
          <span class="toolbar-separator">{{ '/' }}</span>
          <span>{{ result?.unit || 'kWh' }}</span>
          <span class="toolbar-separator">{{ '/' }}</span>
          <span>{{ t('assetManagement.electricity.dailyTrend') }}</span>
          <ElButton class="refresh-action" :icon="RefreshCw" :loading="loading" @click="load">{{ t('assetManagement.associations.refresh') }}</ElButton>
        </div>
        <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
        <div v-if="result" class="analysis-content">
          <header class="chart-heading">
            <h2>{{ result.equipmentName }} {{ '·' }} {{ t('assetManagement.electricity.dailyTrend') }}</h2>
            <p>{{ result.days[0]?.date }} {{ '—' }} {{ result.days[result.days.length - 1]?.date }} {{ '·' }} {{ result.timeZone }}</p>
          </header>
          <div class="chart-legend"><span class="legend-dot" />{{ t('assetManagement.electricity.dailyKwh') }}</div>
          <ChartView class="daily-chart" :option="chartOption" :loading="loading" :empty="availableCount === 0" :accessible-label="t('assetManagement.electricity.dailyTrend')" />
          <p class="method-note">{{ t('assetManagement.electricity.methodNote', { minutes: result.boundaryWindowMinutes }) }}</p>
          <section class="details-section">
            <h2>{{ t('assetManagement.electricity.dailyDetails') }}</h2>
            <ElTable :data="orderedDays" row-key="date">
              <ElTableColumn prop="date" :label="t('assetManagement.electricity.date')" min-width="125" />
              <ElTableColumn :label="t('assetManagement.electricity.dailyKwh')" min-width="130">
                <template #default="{ row }">{{ formatNumber(row.kwh) }}</template>
              </ElTableColumn>
              <ElTableColumn :label="t('assetManagement.electricity.dayChange')" min-width="130">
                <template #default="{ row }">{{ formatNumber(row.changeKwh) }}</template>
              </ElTableColumn>
              <ElTableColumn :label="t('assetManagement.electricity.status')" min-width="170">
                <template #default="{ row }"><ElTag :type="row.status === 'AVAILABLE' ? 'success' : 'warning'">{{ statusText(String(row.reason)) }}</ElTag></template>
              </ElTableColumn>
              <ElTableColumn :label="t('assetManagement.electricity.boundarySamples')" min-width="180">
                <template #default="{ row }">{{ formatTime(row.startSampleTime) }} {{ '→' }} {{ formatTime(row.endSampleTime) }}</template>
              </ElTableColumn>
            </ElTable>
          </section>
        </div>
        <div v-else-if="!loading && !error" class="analysis-empty"><ElEmpty :description="t('assetManagement.electricity.chooseMeter')" /></div>
      </main>

      <aside class="analysis-insights" :aria-label="t('assetManagement.electricity.dataAssessment')">
        <template v-if="result">
          <div class="insight-section">
            <h2>{{ t('assetManagement.electricity.dataAssessment') }}</h2>
            <div class="insight-metric"><span>{{ t('assetManagement.electricity.latestDay') }} {{ '·' }} {{ latest?.date }}</span><strong>{{ formatNumber(latest?.kwh) }}<small> {{ result.unit }}</small></strong></div>
            <div class="insight-metric"><span>{{ t('assetManagement.electricity.dayChange') }}</span><strong>{{ latest?.changeKwh == null ? '—' : `${latest.changeKwh > 0 ? '+' : ''}${formatNumber(latest.changeKwh)}` }}<small> {{ result.unit }}</small></strong><small>{{ latest?.changePercent == null ? '—' : `${formatNumber(latest.changePercent, 1)}%` }}</small></div>
          </div>
          <div class="insight-section breakdown-section">
            <h2>{{ t('assetManagement.electricity.subitemShare') }}</h2>
            <p class="breakdown-period">{{ t('assetManagement.electricity.lastDays', { days }) }}</p>
            <div class="breakdown-ring">
              <ChartView class="breakdown-chart" :option="breakdownOption" :accessible-label="t('assetManagement.electricity.subitemShare')" />
              <span class="breakdown-center">{{ t('assetManagement.electricity.zeroPercentPlaceholder') }}<small>{{ t('assetManagement.electricity.awaitingSubmeter') }}</small></span>
            </div>
            <p class="breakdown-note">{{ t('assetManagement.electricity.noSubitemData') }}</p>
            <h3>{{ t('assetManagement.electricity.subitemDetails') }}</h3>
            <div v-for="target in result.currentCoverage.targets" :key="target.equipmentId" class="subitem-row">
              <span class="subitem-name">{{ target.equipmentName }}<small>{{ t('assetManagement.electricity.awaitingSubmeter') }}</small></span>
              <span class="subitem-values">{{ t('assetManagement.electricity.zeroKwhPlaceholder') }}<small>{{ t('assetManagement.electricity.zeroPercentPlaceholder') }}</small></span>
            </div>
            <p v-if="!result.currentCoverage.targets.length" class="breakdown-note">{{ t('assetManagement.electricity.noTargets') }}</p>
          </div>
          <div class="insight-section coverage-section">
            <h2>{{ t('assetManagement.electricity.currentCoverage') }} <ElTag type="info">{{ result.equipmentCode }}</ElTag></h2>
            <p class="coverage-label">{{ result.currentCoverage.scopeLabel || t('assetManagement.meterCoverage.unconfigured') }}</p>
            <p>{{ t('assetManagement.electricity.installation') }}{{ t('assetManagement.electricity.separator') }}{{ result.currentCoverage.installationSpaceName || t('assetManagement.meterCoverage.toConfirm') }}</p>
            <p>{{ t('assetManagement.electricity.targets') }}{{ t('assetManagement.electricity.separator') }}{{ result.currentCoverage.targets.map(target => target.equipmentName).join('、') || '—' }}</p>
            <p class="coverage-caveat">{{ t('assetManagement.electricity.coverageHistoryNote') }}</p>
          </div>
        </template>
        <div v-else class="insight-placeholder">{{ loading ? t('assetManagement.meterCoverage.loading') : t('assetManagement.electricity.chooseMeter') }}</div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.electricity-analysis { min-width: 0; }
.analysis-shell { display: grid; grid-template-columns: minmax(calc(var(--bec-ref-space-64) * 3.5), calc(var(--bec-ref-space-64) * 4)) minmax(0, 1fr) minmax(calc(var(--bec-ref-space-64) * 3.75), calc(var(--bec-ref-space-64) * 4.5)); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); overflow: hidden; min-height: calc(var(--bec-ref-space-64) * 10); }
.analysis-rail { background: var(--bec-color-surface-secondary); border-right: var(--bec-border-width) solid var(--bec-color-border); }
.rail-block { padding: var(--bec-space-group); border-bottom: var(--bec-border-width) solid var(--bec-color-border); }
.rail-block label, .rail-block h2 { display: block; margin: 0 0 var(--bec-space-tight); color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); font-weight: var(--bec-font-weight-heading); }
.period-select { width: 100%; }
.rail-help, .rail-note { display: block; margin-top: var(--bec-space-tight); font-size: var(--bec-font-size-small); color: var(--bec-color-text-secondary); line-height: var(--bec-line-height); }
.rail-note { padding: var(--bec-space-group); margin: 0; }
.meter-tree-heading { padding: var(--bec-space-tight); color: var(--bec-color-brand-primary); background: var(--bec-color-action-subtle); font-weight: var(--bec-font-weight-heading); }
.meter-tree-item { width: 100%; display: flex; align-items: flex-start; gap: var(--bec-space-tight); border: 0; background: transparent; color: var(--bec-color-text-primary); padding: var(--bec-space-group) var(--bec-space-tight); text-align: left; cursor: pointer; }
.meter-tree-item:hover, .meter-tree-item-active { background: var(--bec-color-action-subtle); }
.meter-tree-item:focus-visible { outline: var(--bec-focus-width) solid var(--bec-color-brand-primary); outline-offset: calc(-1 * var(--bec-focus-width)); }
.meter-tree-item strong, .meter-tree-item small { display: block; overflow-wrap: anywhere; }
.meter-tree-item strong { font-size: var(--bec-font-size-small); font-weight: var(--bec-font-weight-heading); }
.meter-tree-item small { margin-top: var(--bec-ref-space-4); color: var(--bec-color-text-secondary); }
.meter-tree-dot, .legend-dot { flex: none; display: inline-block; width: var(--bec-ref-space-8); height: var(--bec-ref-space-8); border-radius: var(--bec-radius-tag); background: var(--bec-color-action-primary); margin-top: var(--bec-ref-space-4); }
.analysis-main { min-width: 0; }
.analysis-toolbar { min-height: var(--bec-ref-space-48); display: flex; align-items: center; gap: var(--bec-space-tight); padding: 0 var(--bec-space-group); border-bottom: var(--bec-border-width) solid var(--bec-color-border); color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.toolbar-separator { color: var(--bec-color-border); }
.refresh-action { margin-left: auto; }
.analysis-content { padding: var(--bec-space-section); }
.chart-heading { text-align: center; }
.chart-heading h2 { margin: 0; font-size: var(--bec-font-size-navigation); color: var(--bec-color-text-primary); }
.chart-heading p { margin: var(--bec-space-tight) 0 0; color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.chart-legend { display: flex; align-items: center; gap: var(--bec-space-tight); margin: var(--bec-space-section) 0 var(--bec-space-tight); font-size: var(--bec-font-size-small); color: var(--bec-color-text-secondary); }
.chart-legend .legend-dot { margin: 0; }
.daily-chart { height: var(--bec-chart-height); }
.method-note { margin: 0; color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); line-height: var(--bec-line-height); }
.details-section { margin-top: var(--bec-space-section); border-top: var(--bec-border-width) solid var(--bec-color-border); padding-top: var(--bec-space-section); }
.details-section h2, .insight-section h2 { margin: 0 0 var(--bec-space-group); font-size: var(--bec-font-size-navigation); color: var(--bec-color-text-primary); }
.analysis-insights { border-left: var(--bec-border-width) solid var(--bec-color-border); min-width: 0; }
.insight-section { padding: var(--bec-space-group); border-bottom: var(--bec-border-width) solid var(--bec-color-border); }
.insight-metric { display: grid; gap: var(--bec-ref-space-4); padding: var(--bec-space-tight) 0 var(--bec-space-group); }
.insight-metric + .insight-metric { border-top: var(--bec-border-width) solid var(--bec-color-border); }
.insight-metric span, .insight-metric > small { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.insight-metric strong { font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); font-variant-numeric: tabular-nums; }
.insight-metric strong small { font-size: var(--bec-font-size-small); font-weight: var(--bec-font-weight-normal); }
.breakdown-period, .breakdown-note { margin: 0 0 var(--bec-space-tight); color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); line-height: var(--bec-line-height); }
.breakdown-ring { position: relative; height: calc(var(--bec-ref-space-64) * 2.5); }
.breakdown-chart { height: 100%; }
.breakdown-center { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; flex-direction: column; pointer-events: none; font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); color: var(--bec-color-text-secondary); }
.breakdown-center small { font-size: var(--bec-font-size-small); font-weight: var(--bec-font-weight-normal); }
.breakdown-section h3 { margin: var(--bec-space-group) 0 var(--bec-space-tight); font-size: var(--bec-font-size-small); font-weight: var(--bec-font-weight-heading); }
.subitem-row { display: flex; justify-content: space-between; gap: var(--bec-space-tight); border-top: var(--bec-border-width) solid var(--bec-color-border); padding: var(--bec-space-tight) 0; font-size: var(--bec-font-size-small); }
.subitem-name { min-width: 0; overflow-wrap: anywhere; }
.subitem-name small, .subitem-values small { display: block; margin-top: var(--bec-ref-space-4); color: var(--bec-color-text-secondary); }
.subitem-values { flex: none; text-align: right; font-variant-numeric: tabular-nums; }
.coverage-section h2 { display: flex; align-items: center; gap: var(--bec-space-tight); flex-wrap: wrap; }
.coverage-section p { margin: var(--bec-space-tight) 0; color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); line-height: var(--bec-line-height); overflow-wrap: anywhere; }
.coverage-section .coverage-label { color: var(--bec-color-text-primary); font-weight: var(--bec-font-weight-heading); }
.coverage-section .coverage-caveat { border-top: var(--bec-border-width) solid var(--bec-color-border); padding-top: var(--bec-space-tight); }
.insight-placeholder, .analysis-empty { padding: var(--bec-space-section); color: var(--bec-color-text-secondary); }
@media (max-width: 1080px) { .analysis-shell { grid-template-columns: calc(var(--bec-ref-space-64) * 3.25) minmax(0, 1fr); } .analysis-insights { grid-column: 2; border-left: 0; border-top: var(--bec-border-width) solid var(--bec-color-border); display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); } .coverage-section { grid-column: 1 / -1; } }
@media (max-width: 700px) { .analysis-shell { display: block; } .analysis-rail { border-right: 0; border-bottom: var(--bec-border-width) solid var(--bec-color-border); } .rail-meters { display: flex; flex-wrap: wrap; gap: var(--bec-ref-space-4); } .rail-meters h2, .meter-tree-heading { width: 100%; } .meter-tree-item { width: auto; max-width: 100%; } .analysis-insights { display: block; } .analysis-content { padding: var(--bec-space-group); } }
</style>
