import { describe, expect, it } from 'vitest'
import { applyProductContract, emptyProtocolConfiguration, validateProtocolConfiguration } from './protocol-configuration'
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
