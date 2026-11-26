import { describe, expect, it } from 'vitest'
import {
  applyProductContract,
  emptyProtocolConfiguration,
  isIntervalParameterMapping,
  normalizeProtocolConfiguration,
  parsePreviewErrorTarget,
  parseProtocolExportPackage,
  serializeProtocolConfigurationForSave,
  validateProtocolConfiguration,
  validateProtocolDraft,
} from './protocol-configuration'
import type { DeviceProductDetail } from '@/modules/device-onboarding/public'

const product = {
  productId: 'P1', expectedProfileCode: 'V1', identityType: 'SN',
  points: [
    { metricCode: 'temperature', pointNameTemplate: '温度', suffixCode: 'T', unit: '℃', minValue: null, maxValue: null, forCalc: false, required: true, sortOrder: 0, enabled: true },
    { metricCode: 'humidity', pointNameTemplate: '湿度', suffixCode: 'H', unit: '%', minValue: null, maxValue: null, forCalc: false, required: false, sortOrder: 1, enabled: true },
  ],
} as DeviceProductDetail

describe('协议草稿与产品模板约束', () => {
  it('选择产品后固定协议、身份、目标单位和必需性，并保留周期量参数映射', () => {
    const intervalStart = {
      valueType: 'INTERVAL_START' as const,
      sourcePath: '/windowStart',
      metricCode: '__interval_start',
      sourceUnit: 'ms',
      targetUnit: 'ms',
      scale: '1',
      offset: '0',
      required: true,
      enabled: true,
      sortOrder: 10,
    }
    const form = {
      ...emptyProtocolConfiguration(),
      mappings: [
        { sourcePath: '/t', metricCode: 'temperature', sourceUnit: '℃', targetUnit: 'K', scale: '1', offset: '0', required: false, enabled: true, sortOrder: 0 },
        intervalStart,
      ],
    }
    const applied = applyProductContract(form, product)
    expect(isIntervalParameterMapping(intervalStart)).toBe(true)
    expect(applied).toMatchObject({
      productId: 'P1',
      profileCode: 'V1',
      identityType: 'SN',
      mappings: [{ metricCode: 'temperature', targetUnit: '℃', required: true }, intervalStart],
    })
    expect(validateProtocolConfiguration({ ...applied, name: '周期量电表', sourceTopic: 'raw/up', identityPath: '/sn' }, product)).toBeNull()
  })

  it('归一化后端允许为空的草稿字段，并在保存时将空倍率与偏移序列化为 null', () => {
    const normalized = normalizeProtocolConfiguration({
      name: '空字段草稿',
      productId: 'P1',
      profileCode: 'V1',
      sourceTopic: null,
      identityType: 'SN',
      identityPath: null,
      discriminatorPath: null,
      discriminatorValue: null,
      timestampPath: null,
      mappings: [
        { metricCode: 'temperature', sourcePath: '/t', sourceUnit: null, targetUnit: '℃', scale: null, offset: null, required: true, enabled: true, sortOrder: 0 },
      ],
    })
    expect(normalized.sourceTopic).toBe('')
    expect(normalized.identityPath).toBe('')
    expect(normalized.mappings[0]).toMatchObject({ sourceUnit: '', scale: '', offset: '' })
    const serialized = serializeProtocolConfigurationForSave(normalized)
    expect(serialized.mappings[0].scale).toBeNull()
    expect(serialized.mappings[0].offset).toBeNull()
  })

  it('草稿暂存校验允许仅填名称和产品，Topic/身份路径/映射可后补', () => {
    const partial = applyProductContract({ ...emptyProtocolConfiguration(), name: '半成品协议' }, product)
    expect(validateProtocolDraft(partial, product)).toBeNull()
    expect(validateProtocolDraft({ ...partial, name: '   ' }, product)).toBe('draftNameRequired')
    expect(validateProtocolDraft({ ...partial, discriminatorPath: '/type', discriminatorValue: null }, product)).toBe('discriminator')
  })

  it('完整校验在缺少必需测点或单位不一致时阻断发布准备', () => {
    const form = { ...emptyProtocolConfiguration(), name: '测试', productId: 'P1', profileCode: 'V1', identityType: 'SN', sourceTopic: 'raw/up', identityPath: '/sn' }
    expect(validateProtocolConfiguration(form, product)).toBe('mappingRequired')
    form.mappings = [{ sourcePath: '/h', metricCode: 'humidity', sourceUnit: '%', targetUnit: '%', scale: '1', offset: '0', required: false, enabled: true, sortOrder: 0 }]
    expect(validateProtocolConfiguration(form, product)).toBe('missingRequiredMetric')
    form.mappings = [{ sourcePath: '/t', metricCode: 'temperature', sourceUnit: '℃', targetUnit: 'K', scale: '1', offset: '0', required: true, enabled: true, sortOrder: 0 }]
    expect(validateProtocolConfiguration(form, product)).toBe('mappingContract')
  })

  it('解析后端试解析错误路径到顶层字段、映射行或 JSON Pointer', () => {
    expect(parsePreviewErrorTarget('sourceTopic')).toEqual({ kind: 'field', field: 'sourceTopic' })
    expect(parsePreviewErrorTarget('mappings.temperature')).toEqual({ kind: 'mapping', metricCode: 'temperature', field: null })
    expect(parsePreviewErrorTarget('mappings.temperature.sourceUnit')).toEqual({ kind: 'mapping', metricCode: 'temperature', field: 'sourceUnit' })
    expect(parsePreviewErrorTarget('/data/temp')).toEqual({ kind: 'pointer', pointer: '/data/temp' })
    expect(parsePreviewErrorTarget('')).toEqual({ kind: 'unknown', raw: '' })
  })
})

describe('协议迁移导出包边界', () => {
  it('仅拆分配置对象并列出启用与归档规则的产品绑定键', () => {
    const result = parseProtocolExportPackage(JSON.stringify({
      snapshot: { schemaVersion: 1, outputVersion: 'V1', profiles: [{ profile: { profileId: 'active-1', profileCode: 'A' }, mappings: [{ source: '/p' }] }] },
      archived: { schemaVersion: 1, outputVersion: 'V1', profiles: [{ profile: { profileId: 'old-1', profileCode: 'OLD' }, mappings: [] }] },
    }))
    expect(result.profiles).toEqual([
      { profileId: 'active-1', profileCode: 'A', archived: false },
      { profileId: 'old-1', profileCode: 'OLD', archived: true },
    ])
    expect(JSON.parse(result.snapshotJson).profiles[0].mappings).toEqual([{ source: '/p' }])
    expect(JSON.parse(result.archiveJson!).profiles).toHaveLength(1)
  })

  it.each([
    '{}',
    '{"snapshot":{"schemaVersion":2,"profiles":[]}}',
    '{"snapshot":{"schemaVersion":1,"profiles":[{"profile":{"profileId":"same","profileCode":"A"}}]},"archived":{"schemaVersion":1,"profiles":[{"profile":{"profileId":"same","profileCode":"B"}}]}}',
  ])('拒绝缺少快照、版本错误或重复规则标识的导出包', payload => {
    expect(() => parseProtocolExportPackage(payload)).toThrow('INVALID_PROTOCOL_EXPORT')
  })
})
