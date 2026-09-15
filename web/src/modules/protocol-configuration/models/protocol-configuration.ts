import type { DeviceProductDetail, ProductPointTemplate } from '@/modules/device-onboarding/public'

export type ProtocolFieldType = 'NUMBER' | 'STRING' | 'BOOLEAN' | 'NULL'

export type ProtocolMapping = {
  sourcePath: string
  metricCode: string
  sourceUnit: string
  targetUnit: string
  scale: string
  offset: string
  required: boolean
  enabled: boolean
  sortOrder: number
}

export type ProtocolConfiguration = {
  name: string
  productId: string
  profileCode: string
  sourceTopic: string
  identityType: string
  identityPath: string
  discriminatorPath: string | null
  discriminatorValue: string | null
  timestampPath: string | null
  mappings: ProtocolMapping[]
}

export type ProtocolConfigurationDetail = {
  id: string
  revision: number
  status: 'DRAFT'
  configuration: ProtocolConfiguration
  updatedAt: number
}

export type ProtocolConfigurationPage = {
  page: number
  size: number
  total: number
  items: ProtocolConfigurationDetail[]
}

export type InspectedField = { path: string; type: ProtocolFieldType; value: string }
export type InspectResult = { fields: InspectedField[] }

export type PreviewError = { code: string; path: string; message: string }
export type PreviewMetric = {
  metricCode: string
  sourcePath: string
  rawValue: string | null
  value: string | null
  unit: string
  status: 'PRESENT' | 'MISSING_OPTIONAL'
}
export type ProtocolPreview = {
  success: boolean
  errors: PreviewError[]
  identityType: string | null
  identityValue: string | null
  timeSource: string | null
  eventTime: number | null
  metrics: PreviewMetric[]
}

export type ProductPointOption = ProductPointTemplate & { disabled?: boolean }

export const emptyProtocolConfiguration = (): ProtocolConfiguration => ({
  name: '',
  productId: '',
  profileCode: '',
  sourceTopic: '',
  identityType: '',
  identityPath: '',
  discriminatorPath: null,
  discriminatorValue: null,
  timestampPath: null,
  mappings: [],
})

export function applyProductContract(configuration: ProtocolConfiguration, product: DeviceProductDetail): ProtocolConfiguration {
  const points = new Map(product.points.filter(point => point.enabled).map(point => [point.metricCode, point]))
  return {
    ...configuration,
    productId: product.productId,
    profileCode: product.expectedProfileCode,
    identityType: product.identityType,
    mappings: configuration.mappings
      .filter(mapping => points.has(mapping.metricCode))
      .map(mapping => {
        const point = points.get(mapping.metricCode)!
        return { ...mapping, targetUnit: point.unit, required: point.required || mapping.required }
      }),
  }
}

export function validateProtocolConfiguration(configuration: ProtocolConfiguration, product: DeviceProductDetail | null): string | null {
  if (!configuration.name.trim() || !configuration.sourceTopic.trim() || !configuration.identityPath.trim()) return 'required'
  if (!product || configuration.productId !== product.productId) return 'product'
  if (configuration.profileCode !== product.expectedProfileCode || configuration.identityType !== product.identityType) return 'productContract'
  if (configuration.discriminatorPath && !configuration.discriminatorValue?.trim()) return 'discriminator'
  if (configuration.mappings.length > 128) return 'mappingLimit'
  const enabled = configuration.mappings.filter(mapping => mapping.enabled)
  if (!enabled.length) return 'mappingRequired'
  if (new Set(enabled.map(mapping => mapping.metricCode)).size !== enabled.length) return 'duplicateMetric'
  const pointMap = new Map(product.points.filter(point => point.enabled).map(point => [point.metricCode, point]))
  for (const mapping of configuration.mappings) {
    const point = pointMap.get(mapping.metricCode)
    if (!point || mapping.targetUnit !== point.unit || (point.required && !mapping.required)) return 'mappingContract'
    if (!mapping.sourcePath || !mapping.sourceUnit.trim() || !mapping.scale.trim() || !mapping.offset.trim()) return 'mappingFields'
  }
  const mapped = new Set(enabled.map(mapping => mapping.metricCode))
  if (product.points.some(point => point.enabled && point.required && !mapped.has(point.metricCode))) return 'missingRequiredMetric'
  return null
}
