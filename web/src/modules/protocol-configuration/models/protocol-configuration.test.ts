import { describe, expect, it } from 'vitest'
import { applyProductContract, emptyProtocolConfiguration, parseProtocolExportPackage, validateProtocolConfiguration } from './protocol-configuration'
import type { DeviceProductDetail } from '@/modules/device-onboarding/public'

const product = {
  productId: 'P1', expectedProfileCode: 'V1', identityType: 'SN',
  points: [
    { metricCode: 'temperature', pointNameTemplate: '温度', suffixCode: 'T', unit: '℃', minValue: null, maxValue: null, forCalc: false, required: true, sortOrder: 0, enabled: true },
    { metricCode: 'humidity', pointNameTemplate: '湿度', suffixCode: 'H', unit: '%', minValue: null, maxValue: null, forCalc: false, required: false, sortOrder: 1, enabled: true },
  ],
} as DeviceProductDetail

describe('协议草稿与产品模板约束', () => {
  it('选择产品后固定协议、身份、目标单位和必需性', () => {
    const form = { ...emptyProtocolConfiguration(), mappings: [{ sourcePath: '/t', metricCode: 'temperature', sourceUnit: '℃', targetUnit: 'K', scale: '1', offset: '0', required: false, enabled: true, sortOrder: 0 }] }
    expect(applyProductContract(form, product)).toMatchObject({
      productId: 'P1', profileCode: 'V1', identityType: 'SN', mappings: [{ targetUnit: '℃', required: true }],
    })
  })

  it('缺少产品必需测点或单位不一致时阻止保存和预览', () => {
    const form = { ...emptyProtocolConfiguration(), name: '测试', productId: 'P1', profileCode: 'V1', identityType: 'SN', sourceTopic: 'raw/up', identityPath: '/sn' }
    expect(validateProtocolConfiguration(form, product)).toBe('mappingRequired')
    form.mappings = [{ sourcePath: '/h', metricCode: 'humidity', sourceUnit: '%', targetUnit: '%', scale: '1', offset: '0', required: false, enabled: true, sortOrder: 0 }]
    expect(validateProtocolConfiguration(form, product)).toBe('missingRequiredMetric')
    form.mappings = [{ sourcePath: '/t', metricCode: 'temperature', sourceUnit: '℃', targetUnit: 'K', scale: '1', offset: '0', required: true, enabled: true, sortOrder: 0 }]
    expect(validateProtocolConfiguration(form, product)).toBe('mappingContract')
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
