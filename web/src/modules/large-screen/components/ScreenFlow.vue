<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ChartView from '@/shared/charts/ChartView.vue'
import type { ChartOption } from '@/shared/charts/echarts'
import type { CarbonClassificationReport, EnergyFlowView } from '@/modules/energy-classification/public'
import { t } from '@/locales'
const props = defineProps<{ mode: 'energy' | 'carbon'; report: CarbonClassificationReport | null; energyReport?: EnergyFlowView | null; loading: boolean; failed: boolean; state?: string }>()
const scheme = ref('')
watch(() => [props.mode, props.report?.batchId, props.energyReport?.resultVersion], () => { scheme.value = '' })
const graphs = computed(() => (props.mode === 'energy' ? props.energyReport?.flows : props.report?.flows) ?? [])
const selectedGraph = computed(() => graphs.value.find(item => item.schemeCode === scheme.value) ?? graphs.value.find(item => item.available && item.links.length) ?? graphs.value[0])
const flow = computed(() => !props.loading && !props.failed && selectedGraph.value?.available && selectedGraph.value.links.length ? selectedGraph.value : undefined)
const explanation = computed(() => {
  if (props.failed) return t('largeScreen.failed')
  if (props.loading) return t('largeScreen.loading')
  if (props.mode === 'energy') return props.energyReport?.reason || selectedGraph.value?.unavailableReason || t('largeScreen.noFlow')
  if (props.state === 'limited') return t('largeScreen.batchListLimited')
  if (props.state === 'ambiguous') return t('largeScreen.batchAmbiguous')
  return selectedGraph.value?.unavailableReason || t(props.report ? 'largeScreen.flowEvidenceMissing' : 'largeScreen.noCarbon')
})
const option = computed<ChartOption>(() => ({ animation: false, tooltip: { trigger: 'item', appendToBody: false }, series: [{ type: 'sankey', left: '4%', right: '24%', top: '3%', bottom: '3%', nodeWidth: 9, nodeGap: 12, emphasis: { focus: 'adjacency' }, data: flow.value?.nodes.map(node => ({ name: node.id, label: { formatter: node.name } })), links: flow.value?.links.map(link => ({ source: link.source, target: link.target, value: Number(link.value) })), lineStyle: { color: 'source', opacity: 0.32, curveness: 0.5 } }] }))
const endUses = ['hvac', 'heating', 'ventilation', 'water', 'lighting', 'power', 'fire', 'hotWater', 'cooking']
</script>
<template>
  <section class="flow-body" :aria-label="t('largeScreen.flow')" :aria-busy="loading">
    <div v-if="!flow" class="flow-head"><span>{{ t('largeScreen.source') }}</span><span>{{ t('largeScreen.endUse') }}</span><span>{{ t('largeScreen.buildingTotal') }}</span></div>
    <div v-if="graphs.length" class="scheme-picker"><label>{{ t('largeScreen.classificationScheme') }}<select :value="selectedGraph?.schemeCode" @change="scheme = ($event.target as HTMLSelectElement).value"><option v-for="graph in graphs" :key="graph.schemeCode" :value="graph.schemeCode">{{ graph.schemeCode }}</option></select></label></div>
    <ChartView v-if="flow" :option="option" :accessible-label="t(mode === 'energy' ? 'largeScreen.energyFlow' : 'largeScreen.carbonFlow')" />
    <small v-if="mode === 'energy' && flow" class="flow-note" :title="[energyReport?.classificationVersionId, energyReport?.relationVersionId, energyReport?.parameterVersionIds.join(', '), energyReport?.formulaVersionIds.join(', '), energyReport?.resultVersion].filter(Boolean).join(' / ')">{{ t('largeScreen.totalEnergy') }} {{ energyReport?.flows.find(item => item.schemeCode === selectedGraph?.schemeCode)?.total }} {{ t('largeScreen.tce') }} {{ t('largeScreen.separator') }} {{ t(energyReport?.resultNature === 'FORMAL_SOURCE' ? 'largeScreen.flowSourceFormal' : 'largeScreen.flowSourceManual') }}</small>
    <div v-if="!flow" class="flow-columns"><div><span v-for="key in ['gas','diesel','electricity','heat']" :key="key">{{ t('largeScreen.' + key) }}</span></div><div><span v-for="key in endUses" :key="key">{{ t('largeScreen.' + key) }}</span></div><div class="flow-total"><span>{{ t(mode === 'energy' ? 'largeScreen.totalEnergy' : 'largeScreen.carbonTotal') }}</span><strong>{{ t('largeScreen.missing') }}</strong><small>{{ t(mode === 'energy' ? 'largeScreen.tce' : 'largeScreen.kgco2') }}</small></div></div>
    <p v-if="!flow" class="flow-empty">{{ explanation }}</p>
  </section>
</template>
<style scoped>
.flow-body { display: flex; flex-direction: column; min-height: 0; flex: 1; }
.flow-head { display: flex; justify-content: space-between; padding: var(--bec-screen-space-12) var(--bec-screen-space-20); font-size: var(--bec-screen-font-small); }
.flow-columns { flex: 1; display: grid; grid-template-columns: 1fr 1fr 0.7fr; min-height: 0; gap: var(--bec-screen-space-12); padding: var(--bec-screen-space-12) var(--bec-screen-space-8); }
.flow-columns > div { display: flex; flex-direction: column; justify-content: space-around; align-items: flex-start; }
.flow-columns span { background: var(--bec-screen-panel); font-size: var(--bec-screen-font-small); }
.flow-columns .flow-total { justify-content: center; border-left: var(--bec-screen-space-8) solid var(--bec-screen-neutral); padding-left: var(--bec-screen-space-6); }
.flow-total strong { font-size: var(--bec-screen-font-title); }
.flow-empty { margin: var(--bec-screen-space-8) 0 0; font-size: var(--bec-screen-font-small); color: var(--bec-screen-muted); }
.flow-note { font-size: var(--bec-screen-font-small); color: var(--bec-screen-muted); }
.flow-body :deep(.chart-view) { flex: 1; min-height: 0; }
.scheme-picker { padding: var(--bec-screen-space-8) 0; font-size: var(--bec-screen-font-small); }
.scheme-picker label { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-screen-space-8); }
.scheme-picker select { min-width: 0; max-width: 60%; color: inherit; font: inherit; background: var(--bec-screen-panel); border: var(--bec-screen-border-width) solid var(--bec-screen-divider); padding: var(--bec-screen-space-4); }
</style>
