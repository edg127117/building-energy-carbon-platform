<script setup lang="ts">
import { computed } from 'vue'
import ChartView from '@/shared/charts/ChartView.vue'
import type { ChartOption } from '@/shared/charts/echarts'
import { t } from '@/locales'
import type { ScreenBusinessFlowGraph } from '../models/screen-redesign'

const props = defineProps<{ mode: 'carbon' | 'energy'; data?: ScreenBusinessFlowGraph }>()

interface RenderedFlowGraph extends ScreenBusinessFlowGraph {
  columns: string[]
  totalDepth: number
  nodes: Array<{ id: string; name: string; depth: number; order: number; value?: number | null }>
}

const graph = computed<RenderedFlowGraph | undefined>(() => {
  const value = props.data
  if (!value || value.unit !== (props.mode === 'carbon' ? 'tCO₂e' : 'tce') || !value.nodes.length || !value.links.length) return undefined
  const nodes = new Map(value.nodes.map(node => [node.id, node]))
  const depths = value.nodes.map(node => node.depth)
  const totalDepth = Math.max(...depths)
  if (nodes.size !== value.nodes.length || !Number.isSafeInteger(totalDepth) || totalDepth < 1
    || depths.some(depth => !Number.isSafeInteger(depth) || depth < 0 || depth > totalDepth)
    || value.nodes.filter(node => node.depth === totalDepth).length !== 1
    || Array.from({ length: totalDepth + 1 }, (_, depth) => !value.nodes.some(node => node.depth === depth)).some(Boolean)
    || value.links.some(link => {
      const from = nodes.get(link.source), to = nodes.get(link.target)
      return !from || !to || to.depth !== from.depth + 1 || !Number.isFinite(link.value) || link.value < 0
    })) return undefined
  const defaultColumns = Array.from({ length: totalDepth + 1 }, (_, depth) => {
    if (depth === 0) return t('largeScreen.redesignPurpose')
    if (depth === totalDepth) return nodes.get(value.nodes.find(node => node.depth === totalDepth)!.id)?.name ?? t('largeScreen.buildingTotal')
    return t('largeScreen.redesignFloor')
  })
  const columns = value.columns?.length === totalDepth + 1 ? value.columns : defaultColumns
  if (columns.some(title => !title?.trim())) return undefined
  return {
    ...value,
    columns,
    totalDepth,
    nodes: value.nodes.map(node => ({ ...node, order: node.order ?? 0 }))
      .sort((left, right) => left.depth - right.depth || left.order - right.order || left.id.localeCompare(right.id)),
  }
})
const total = computed(() => formatValue(graph.value?.total))
const columnStyle = computed(() => ({ gridTemplateColumns: `repeat(${graph.value?.columns.length ?? 1}, minmax(0, 1fr))` }))
// 沿用既有来源色和浅蓝灰汇总线；维度数、标题、节点顺序均只使用后端返回的展示元数据。
const purposeColors = ['blue', 'teal', 'amber', 'pale'].map(tone => `var(--bec-screen-flow-${tone})`)
const nodeColors = computed(() => {
  const colors = new Map<string, string>()
  let index = 0
  graph.value?.nodes.forEach(node => colors.set(node.id, node.depth === 0
    ? purposeColors[index++ % purposeColors.length]
    : node.depth === graph.value?.totalDepth ? 'var(--bec-screen-flow-total)' : 'var(--bec-screen-flow-floor)'))
  return colors
})
const option = computed<ChartOption>(() => ({
  animation: false,
  tooltip: { trigger: 'item', appendToBody: false, renderMode: 'richText', formatter: flowTooltip },
  series: [{
    type: 'sankey', left: 72, right: 66, top: 22, bottom: 14, nodeWidth: 10, nodeGap: 22,
    draggable: false, layoutIterations: 0,
    label: { fontSize: 12, textBorderWidth: 0 },
    data: graph.value?.nodes.map(node => ({ name: node.id, value: node.value ?? undefined, depth: node.depth,
      localY: node.depth === graph.value?.totalDepth ? 0.15 : undefined,
      itemStyle: { color: nodeColors.value.get(node.id) },
      label: node.depth === graph.value?.totalDepth ? {
        position: 'right', color: 'var(--bec-screen-flow-ink)',
        formatter: `{name|${safeText(node.name)}}\n{value|${formatFixedTotal()}}\n{unit|${safeText(graph.value?.unit ?? '')}}`,
        rich: {
          name: { fontSize: 11, lineHeight: 19 },
          value: { fontSize: 17, fontWeight: 700, lineHeight: 23 },
          unit: { fontSize: 11, lineHeight: 18 },
        },
      } : { formatter: safeText(node.name), position: node.depth === 0 ? 'left' : 'right',
        color: 'var(--bec-screen-flow-ink)', backgroundColor: 'var(--bec-screen-flow-label)', padding: [2, 2] },
    })),
    links: graph.value?.links.map(link => ({ ...link, lineStyle: {
      color: graph.value?.nodes.find(node => node.id === link.source)?.depth === 0
        ? nodeColors.value.get(link.source) : 'var(--bec-screen-flow-merge)',
    } })),
    lineStyle: { opacity: 0.52, curveness: 0.5 }, emphasis: { focus: 'adjacency' },
  }],
}))

function safeText(value: unknown): string {
  if (typeof value !== 'string') return t('largeScreen.missing')
  return value.replace(/[\r\n{}|]/g, character => ({ '\r': ' ', '\n': ' ', '{': '（', '}': '）', '|': '｜' })[character] ?? character)
}
function formatFixedTotal(): string {
  const value = graph.value?.total
  return typeof value === 'number' && Number.isFinite(value) ? value.toFixed(2) : t('largeScreen.missing')
}
function formatValue(value: unknown): string {
  return typeof value === 'number' && Number.isFinite(value)
    ? new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(value) : t('largeScreen.missing')
}
function flowTooltip(params: unknown): string {
  if (!params || typeof params !== 'object') return ''
  const item = params as { dataType?: string; data?: { source?: string; target?: string; name?: string; value?: number } }
  const nodes = graph.value?.nodes ?? []
  if (item.dataType === 'edge') return t('largeScreen.redesignFlowLinkTooltip', {
    source: safeText(nodes.find(node => node.id === item.data?.source)?.name),
    target: safeText(nodes.find(node => node.id === item.data?.target)?.name),
    value: formatValue(item.data?.value), unit: safeText(graph.value?.unit ?? ''),
  })
  const node = nodes.find(node => node.id === item.data?.name)
  return node ? t('largeScreen.redesignFlowTotalTooltip', { name: safeText(node.name), value: node.depth === graph.value?.totalDepth ? total.value : formatValue(node.value), unit: safeText(graph.value?.unit ?? '') }) : ''
}
</script>

<template>
  <section class="business-flow" :aria-label="t('largeScreen.flow')">
    <div v-if="graph" class="business-flow-head" :style="columnStyle"><span v-for="(title, index) in graph.columns" :key="`${index}-${title}`">{{ title }}</span></div>
    <ChartView v-if="graph" :option="option" :accessible-label="t('largeScreen.redesignFlowTotalTooltip', { name: t(mode === 'carbon' ? 'largeScreen.carbonFlow' : 'largeScreen.energyFlow'), value: total, unit: graph.unit })" />
    <div v-else class="business-flow-empty">{{ t(data ? 'largeScreen.redesignFlowUnavailable' : 'largeScreen.redesignFlowPending') }}</div>
    <small v-if="graph" class="flow-pending">{{ t('largeScreen.redesignFlowHoverNote') }}</small>
  </section>
</template>

<style scoped src="./screen-business-flow.css"></style>
