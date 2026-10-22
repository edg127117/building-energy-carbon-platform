import { describe, expect, it } from 'vitest'
import { createBindingBatch, localDateTimeToUtc, mergeBindingBatch, nextClassificationNodeCode, parentOptionsForNode, updateBindingBatch, utcToLocalDateTime } from './classification-editor'
import type { ClassificationNode } from './classification'

describe('分类配置编辑辅助函数', () => {
  it('根据当前版本已有编码稳定生成下一个未占用编号', () => {
    expect(nextClassificationNodeCode([node('NODE_001'), node('node_002'), node('CUSTOM')])).toBe('NODE_003')
    expect(nextClassificationNodeCode([])).toBe('NODE_001')
  })

  it('批量绑定最多一次添加100个对象并保留待专家确认默认值', () => {
    const bindings = createBindingBatch({ schemeCode: 'USAGE', nodeCode: 'HVAC', objectType: 'EQUIPMENT', objectIds: Array.from({ length: 100 }, (_, index) => `equipment-${index}`), evidence: '来自设备清单' })
    expect(bindings).toHaveLength(100)
    expect(bindings[0]).toMatchObject({ semantics: 'DESCRIPTION', status: 'PENDING_EXPERT', meterPointId: null, evidence: '来自设备清单' })
    expect(() => createBindingBatch({ schemeCode: 'USAGE', nodeCode: 'HVAC', objectType: 'POINT', objectIds: Array.from({ length: 101 }, (_, index) => `point-${index}`), evidence: '依据' })).toThrow('BINDING_BATCH_LIMIT')
    expect(() => createBindingBatch({ schemeCode: 'USAGE', nodeCode: 'HVAC', objectType: 'POINT', objectIds: ['point-1'], evidence: '' })).toThrow('BINDING_EVIDENCE_INVALID')
  })

  it('父节点只能选同方案的非自身和非后代节点', () => {
    const nodes = [node('ROOT'), node('CHILD', 'ROOT'), node('LEAF', 'CHILD'), node('OTHER', null, 'DEPARTMENT')]
    expect(parentOptionsForNode(nodes[1]!, nodes).map(item => item.code)).toEqual(['ROOT'])
  })

  it('批量确认必须显式选择，重复绑定不覆盖既有专业结论', () => {
    const selection = { schemeCode: 'USAGE', nodeCode: 'HVAC', objectType: 'POINT' as const, objectIds: ['point-1'], evidence: '确认单', semantics: 'METERING' as const, status: 'CONFIRMED' as const }
    const first = createBindingBatch(selection)
    expect(first[0]).toMatchObject({ semantics: 'METERING', status: 'CONFIRMED' })
    expect(mergeBindingBatch(first, [...first, ...createBindingBatch({ ...selection, objectIds: ['point-2'] })])).toHaveLength(1)
    expect(first[0]?.evidence).toBe('确认单')
  })

  it('在本地时区与UTC之间保留同一时刻', () => {
    const local = '2026-10-19T08:45'
    const utc = localDateTimeToUtc(local)
    expect(utc).toBe(new Date(local).toISOString())
    expect(utcToLocalDateTime(utc)).toBe(local)
    expect(localDateTimeToUtc('')).toBeNull()
    expect(localDateTimeToUtc('2026-02-30T08:45')).toBeNull()
    expect(localDateTimeToUtc('2026-10-19T08:45:00Z')).toBeNull()
  })

  it('批量确认现有绑定只更新选中范围，保留通道和其它对象', () => {
    const existing = createBindingBatch({ schemeCode: 'USAGE', nodeCode: 'HVAC', objectType: 'EQUIPMENT', objectIds: ['E1', 'E2'], evidence: '原依据', semantics: 'METERING' })
    existing[0]!.meterPointId = 'CHANNEL_1'
    const command = createBindingBatch({ schemeCode: 'USAGE', nodeCode: 'HVAC', objectType: 'EQUIPMENT', objectIds: ['E1'], evidence: '专业确认单', semantics: 'METERING', status: 'CONFIRMED' })
    const result = updateBindingBatch(existing, command)
    expect(result.matched).toBe(1)
    expect(result.bindings[0]).toMatchObject({ meterPointId: 'CHANNEL_1', status: 'CONFIRMED', evidence: '专业确认单' })
    expect(result.bindings[1]).toEqual(existing[1])
    expect(existing[0]!.status).toBe('PENDING_EXPERT')
  })
})

function node(code: string, parentCode: string | null = null, schemeCode = 'USAGE'): ClassificationNode {
  return { schemeCode, code, name: code, parentCode }
}
