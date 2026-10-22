<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElEmpty, ElFormItem, ElOption, ElSelect } from '@/shared/ui'
import ChartView from '@/shared/charts/ChartView.vue'
import type { ChartOption } from '@/shared/charts/echarts'
import { t } from '@/locales'
import type { CarbonClassificationReport } from '../api/carbon-results'

const props = defineProps<{ report: CarbonClassificationReport }>()
const scheme = ref('')
const graph = computed(() => props.report.flows.find(item => item.schemeCode === scheme.value))
watch(() => props.report, report => { scheme.value = report.flows[0]?.schemeCode ?? '' }, { immediate: true })
const graphOption = computed<ChartOption>(() => {
  const source = graph.value
  if (!source?.available) return {}
  const labels = new Map(source.nodes.map(node => [node.id, node.type === 'TOTAL' ? t('energyClassification.carbonResults.total') : node.type === 'SCOPE' ? t(`energyClassification.carbonResults.scopes.${node.code}`) : node.type === 'UNALLOCATED' ? t('energyClassification.carbonResults.unallocated') : node.name]))
  return {
    tooltip: { trigger: 'item', renderMode: 'richText', formatter: params => {
      if (Array.isArray(params)) return ''
      const data = params.data as { source?: string; target?: string; rawValue?: string }
      return data.source && data.target ? t('energyClassification.carbonResults.flowTooltip', { source: labels.get(data.source) ?? data.source, target: labels.get(data.target) ?? data.target, value: data.rawValue ?? '—' }) : labels.get(params.name) ?? params.name
    } },
    series: [{ type: 'sankey', emphasis: { focus: 'adjacency' },
      data: source.nodes.map(node => ({ name: node.id, label: { formatter: labels.get(node.id) } })),
      // 只把服务端十进制结果转成绘图值；权威明细仍显示原始字符串，不在此计算流量。
      links: source.links.map(link => ({ source: link.source, target: link.target, value: Number(link.value), rawValue: link.value })),
    }],
  }
})
const trendOption = computed<ChartOption>(() => {
  const periods = [...new Set(props.report.periods.map(item => item.startInclusive))].sort()
  const scopes = [...new Set(props.report.periods.map(item => item.scopeType))]
  return {
    tooltip: { trigger: 'axis', renderMode: 'richText' }, legend: {},
    xAxis: { type: 'category', data: periods }, yAxis: { type: 'value', name: t('energyClassification.carbonResults.unit') },
    series: scopes.map(scope => ({ type: 'bar', stack: 'scope', name: t(`energyClassification.carbonResults.scopes.${scope}`),
      data: periods.map(period => {
        const value = props.report.periods.find(item => item.startInclusive === period && item.scopeType === scope)
        return value ? Number(value.emissionKgCo2e) : null
      }),
    })),
  }
})
</script>

<template>
  <section class="carbon-result-charts">
    <div><h3>{{ t('energyClassification.carbonResults.trend') }}</h3><div class="result-chart"><ChartView :option="trendOption" :empty="!report.periods.length" :accessible-label="t('energyClassification.carbonResults.trend')" /></div></div>
    <div>
      <h3>{{ t('energyClassification.carbonResults.flow') }}</h3><p>{{ t('energyClassification.carbonResults.flowHint') }}</p>
      <ElFormItem v-if="report.flows.length" :label="t('energyClassification.carbonResults.schemeCode')"><ElSelect v-model="scheme"><ElOption v-for="item in report.flows" :key="item.schemeCode" :value="item.schemeCode" :label="item.schemeCode" /></ElSelect></ElFormItem>
      <ElAlert v-if="graph && !graph.available" :title="graph.unavailableReason ?? ''" type="info" :closable="false" />
      <div v-else-if="graph?.links.length" class="result-chart"><ChartView :option="graphOption" :accessible-label="t('energyClassification.carbonResults.flow')" /></div>
      <ElEmpty v-else :description="t('energyClassification.carbonResults.noFlow')" />
    </div>
  </section>
</template>

<style scoped>
.carbon-result-charts { display: grid; gap: var(--bec-space-section); }
.carbon-result-charts p { color: var(--bec-color-text-secondary); }
.result-chart { height: calc(var(--bec-ref-space-64) * 7); min-width: 0; }
</style>
