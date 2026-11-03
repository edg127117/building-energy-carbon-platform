import type { JointFlowGraph, JointFlowRequest, JointFlowView } from '@/modules/energy-classification/public'
import type { ScreenBusinessFlowGraph } from './screen-redesign'
import { validScreenPeriod, type ScreenPeriod } from './screen-period'
import { previousMonth } from './final-b-monthly'

export function jointFlowRequest(buildingId: string, period: ScreenPeriod, configurationVersionId?: string, now = Date.now()): JointFlowRequest | null {
  if (!buildingId || !validScreenPeriod(period)) return null
  const year = new Intl.DateTimeFormat('en', { timeZone: 'Asia/Shanghai', year: 'numeric' }).format(now)
  const throughMonth = period.kind === 'year' ? period.value === year ? previousMonth(now) : `${period.value}-12` : undefined
  if (period.value.slice(0, 4) > year || (throughMonth && !throughMonth.startsWith(period.value))) return null
  return {
    buildingId, periodType: period.kind === 'year' ? 'YEAR' : 'MONTH', period: period.value, throughMonth,
    ...(configurationVersionId ? { configurationVersionId } : {}),
  }
}

/** 大屏只接受已选用的正式展示配置；草稿预览是管理页专用结果，不能进入运营视图。 */
export function validateJointFlow(view: JointFlowView, request: JointFlowRequest): void {
  const first = request.periodType === 'YEAR' ? `${request.period}-01` : request.period
  const last = request.throughMonth ?? request.period
  const [year, month] = first.split('-').map(Number), [endYear, endMonth] = last.split('-').map(Number)
  if (view.buildingId !== request.buildingId || view.period !== request.period || view.periodType !== request.periodType
    || (view.throughMonth ?? undefined) !== request.throughMonth || view.timezoneId !== 'Asia/Shanghai'
    || (request.configurationVersionId !== undefined && view.configurationVersionId !== request.configurationVersionId)
    || view.classificationBasis !== 'CONFIGURED_VIEW'
    || Date.parse(view.startInclusive) !== Date.UTC(year, month - 1, 1) - 28800000
    || Date.parse(view.endExclusive) !== Date.UTC(endYear, endMonth, 1) - 28800000
    || !Array.isArray(view.dimensions) || !validCoverage(view)) throw new Error('JOINT_FLOW_SCOPE_MISMATCH')
}

function validCoverage(view: JointFlowView): boolean {
  const coverage = view.coverage
  return !!coverage && Number.isSafeInteger(coverage.totalAtoms) && coverage.totalAtoms >= 0
    && Number.isSafeInteger(coverage.allocatedAtoms) && coverage.allocatedAtoms >= 0
    && Array.isArray(coverage.dimensions) && coverage.dimensions.every(item => typeof item.key === 'string'
      && Number.isSafeInteger(item.allocatedAtoms) && item.allocatedAtoms >= 0
      && Number.isSafeInteger(item.unallocatedAtoms) && item.unallocatedAtoms >= 0)
}

const decimal = (value: unknown): number | null => {
  if (typeof value !== 'string' || !/^-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(value)) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null
}

/** 只转换绘图类型并核对后端结构，不在浏览器重排归属、汇总连线或计算覆盖率。 */
export function screenJointFlow(graph: JointFlowGraph | null | undefined, unit: 'tCO2e' | 'tce', dimensions: ReadonlyArray<{ title: string }>): ScreenBusinessFlowGraph | undefined {
  if (!graph || !['READY', 'PARTIAL'].includes(graph.state)) return undefined
  const total = decimal(graph.total)
  const totalDepth = dimensions.length
  if (graph.unit !== unit || total === null || totalDepth < 1 || totalDepth > 3 || !Array.isArray(graph.nodes) || !Array.isArray(graph.links)
    || graph.nodes.length > 500 || graph.links.length > 2000 || dimensions.some(dimension => !dimension.title?.trim())) throw new Error('JOINT_FLOW_GRAPH_INVALID')
  const ids = new Map(graph.nodes.map(node => [node.id, node]))
  const totalNode = graph.nodes.find(node => node.depth === totalDepth)
  if (ids.size !== graph.nodes.length || !totalNode || graph.nodes.filter(node => node.depth === totalDepth).length !== 1
    || Array.from({ length: totalDepth }, (_, depth) => !graph.nodes.some(node => node.depth === depth)).some(Boolean)) throw new Error('JOINT_FLOW_STAGES_INVALID')
  const nodes = graph.nodes.map(node => {
    const value = decimal(node.value)
    if (!Number.isSafeInteger(node.depth) || node.depth < 0 || node.depth > totalDepth || !Number.isSafeInteger(node.order)
      || !node.id || !node.name || value === null) throw new Error('JOINT_FLOW_NODE_INVALID')
    return { id: node.id, name: node.name, depth: node.depth, order: node.order, value }
  })
  const links = graph.links.map(link => {
    const value = decimal(link.value), source = ids.get(link.source), target = ids.get(link.target)
    if (!source || !target || target.depth !== source.depth + 1 || value === null) throw new Error('JOINT_FLOW_LINK_INVALID')
    return { source: link.source, target: link.target, value }
  })
  return { unit: unit === 'tCO2e' ? 'tCO₂e' : 'tce', total, columns: [...dimensions.map(dimension => dimension.title), totalNode.name], nodes, links }
}
