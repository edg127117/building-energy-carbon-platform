import { describe, expect, it } from 'vitest'
import { emptyJointFlowConfiguration, validateJointFlowConfiguration } from './joint-flow-configuration'

describe('联合流展示配置结构校验', () => {
  it('接受有序的能源、用途和楼层维度，不在浏览器计算覆盖率或数值', () => {
    const configuration = emptyJointFlowConfiguration()
    Object.assign(configuration, {
      startInclusive: '2026-01-01T00:00:00+08:00', endExclusive: '2027-01-01T00:00:00+08:00', relationVersionIds: ['R-1'],
      mappings: [{ relationVersionId: 'R-1', schemes: [{ code: 'USE', name: '用途', dimension: 'USAGE', mode: 'ADDITIVE' }], nodes: [], bindings: [], boundaryRules: [] }],
      floorReferences: [{ relationVersionId: 'R-1', schemeCode: 'FLOOR', nodeCode: 'F1', spaceId: 'SPACE-F1' }],
      dimensions: [
        { key: 'ENERGY_TYPE', title: '能源类型', source: 'ENERGY_ITEM', nodes: [{ code: 'ELEC', name: '电力', order: 10 }] },
        { key: 'USAGE', title: '用途', source: 'MAPPED_SCHEME', schemeCode: 'USE', nodes: [{ code: 'HVAC', name: '空调', order: 20 }] },
        { key: 'FLOOR', title: '楼层', source: 'FROZEN_SCHEME', schemeCode: 'FLOOR', scopeLevel: 'FLOOR', nodes: [{ code: 'F1', name: '一层', order: 30 }] },
      ],
    })

    expect(validateJointFlowConfiguration(configuration)).toEqual([])
  })

  it('拒绝重复维度、错误来源和不匹配的关系映射', () => {
    const configuration = emptyJointFlowConfiguration()
    Object.assign(configuration, {
      startInclusive: '2026-02-01T00:00:00Z', endExclusive: '2026-01-01T00:00:00Z', relationVersionIds: ['R-1', 'R-1'],
      mappings: [], floorReferences: [],
      dimensions: [
        { key: 'ENERGY_TYPE', title: '', source: 'FROZEN_SCHEME', nodes: [] },
        { key: 'ENERGY_TYPE', title: '再次能源', source: 'ENERGY_ITEM', nodes: [] },
        { key: 'FLOOR', title: '楼层', source: 'MAPPED_SCHEME', schemeCode: 'NOPE', nodes: [{ code: 'F1', name: '一层', order: 1 }] },
        { key: 'USAGE', title: '超出上限', source: 'FROZEN_SCHEME', schemeCode: 'USE', nodes: [] },
      ],
    })

    expect(validateJointFlowConfiguration(configuration).map(issue => issue.code)).toEqual(expect.arrayContaining([
      'INVALID_RANGE', 'DUPLICATE_RELATION_VERSION', 'DIMENSION_COUNT', 'DUPLICATE_DIMENSION', 'ENERGY_ITEM_SOURCE', 'FLOOR_SCOPE', 'MAPPED_SCHEME_MISMATCH',
      'MISSING_FLOOR_REFERENCE',
    ]))
  })
})
