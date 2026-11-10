import { describe, expect, it, vi } from 'vitest'
import type { JointFlowGraph, JointFlowView } from '@/modules/energy-classification/public'
import { jointFlowRequest, screenJointFlow, validateJointFlow } from './joint-flow'

vi.mock('@/locales', () => ({ t: (key: string) => key }))

export function jointFlowFixture(): JointFlowView {
  const graph: JointFlowGraph = { state: 'READY', reason: null, unit: 'tCO2e', total: '0', unallocated: '0',
    nodes: [
      { id: 'E', code: 'ELEC', name: '电力', depth: 0, order: 10, value: '0' },
      { id: 'F', code: 'F8', name: '八层', depth: 1, order: 20, value: '0' },
      { id: 'T', code: 'TOTAL', name: '建筑合计', depth: 2, order: 0, value: '0' },
    ],
    links: [{ source: 'E', target: 'F', value: '0' }, { source: 'F', target: 'T', value: '0' }] }
  return { buildingId: 'B', periodType: 'MONTH', period: '2024-01', throughMonth: null, timezoneId: 'Asia/Shanghai',
    startInclusive: '2023-12-31T16:00:00Z', endExclusive: '2024-01-31T16:00:00Z', state: 'READY', classificationBasis: 'CONFIGURED_VIEW',
    configurationVersionId: 'CFG-1', configurationHash: 'hash', resultVersion: 'v', missingMonths: [], sources: [], parameterVersionIds: [], formulaVersionIds: [], standardCoalVersionIds: [],
    conversionOptions: [], warnings: [], dimensions: [
      { key: 'ENERGY_TYPE', title: '能源类型', source: 'ENERGY_ITEM', nodes: [{ code: 'ELEC', name: '电力', order: 10 }] },
      { key: 'FLOOR', title: '楼层', source: 'FROZEN_SCHEME', schemeCode: 'FLOOR', scopeLevel: 'FLOOR', nodes: [{ code: 'F8', name: '八层', order: 20 }] },
    ], coverage: { totalAtoms: 1, allocatedAtoms: 1, dimensions: [{ key: 'ENERGY_TYPE', allocatedAtoms: 1, unallocatedAtoms: 0 }, { key: 'FLOOR', allocatedAtoms: 1, unallocatedAtoms: 0 }] },
    carbon: graph, energy: { ...graph, state: 'SELECTION_REQUIRED', unit: 'tce', total: null, nodes: [], links: [] } }
}

describe('联合流大屏适配', () => {
  it('只按当前建筑选用配置读取，允许显式正式版本覆盖且保留年度截止月', () => {
    const now = Date.UTC(2026, 9, 29)
    expect(jointFlowRequest('B', { kind: 'year', value: '2026' }, undefined, now)?.throughMonth).toBe('2026-09')
    expect(jointFlowRequest('B', { kind: 'year', value: '2024' }, 'CFG-1', now)).toMatchObject({ throughMonth: '2024-12', configurationVersionId: 'CFG-1' })
    expect(jointFlowRequest('B', { kind: 'year', value: '2027' }, undefined, now)).toBeNull()
    expect(jointFlowRequest('B', { kind: 'year', value: '2026' }, undefined, Date.UTC(2026, 0, 1))).toBeNull()
  })

  it('保留后端零值、顺序和动态列，不合并阶段或重算覆盖率', () => {
    const view = jointFlowFixture()
    const graph = screenJointFlow(view.carbon, 'tCO2e', view.dimensions)!
    expect(graph.total).toBe(0)
    expect(graph.links).toHaveLength(2)
    expect(graph.columns).toEqual(['能源类型', '楼层', '建筑合计'])
    expect(graph.nodes.map(node => node.order)).toEqual([10, 20, 0])
    expect(screenJointFlow(view.energy, 'tce', view.dimensions)).toBeUndefined()
    expect(screenJointFlow({ ...view.carbon, total: '1E-7' }, 'tCO2e', view.dimensions)?.total).toBe(0.0000001)
    expect(() => screenJointFlow({ ...view.carbon, unit: 'KG_CO2E' }, 'tCO2e', view.dimensions)).toThrow()
    expect(() => screenJointFlow({ ...view.carbon, links: [{ source: 'E', target: 'T', value: '0' }] }, 'tCO2e', view.dimensions)).toThrow()
    expect(() => screenJointFlow({ ...view.carbon, total: '-1' }, 'tCO2e', view.dimensions)).toThrow()
  })

  it('拒绝另一建筑、期间、时区、草稿预览或非法覆盖率响应', () => {
    const view = jointFlowFixture(), request = jointFlowRequest('B', { kind: 'month', value: '2024-01' })!
    expect(() => validateJointFlow(view, request)).not.toThrow()
    for (const bad of [
      { ...view, buildingId: 'OTHER' }, { ...view, period: '2024-02' }, { ...view, timezoneId: 'UTC' },
      { ...view, classificationBasis: 'DRAFT_PREVIEW' as const }, { ...view, endExclusive: '2024-02-01T00:00:00Z' },
      { ...view, coverage: { ...view.coverage, allocatedAtoms: -1 } },
    ]) expect(() => validateJointFlow(bad, request)).toThrow()
  })
})
