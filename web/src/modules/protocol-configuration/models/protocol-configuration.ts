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

export type ProtocolOutputVersion = 'V1' | 'V2'
export type ProtocolTargetStatus = 'READY' | 'PENDING_SYNC' | 'LOADED' | 'FAILED' | 'UNKNOWN'
export type ProtocolDeploymentStatus = 'PENDING_SYNC' | 'LOADED' | 'FAILED' | 'UNKNOWN'

export type ProtocolPublicationTarget = {
  targetId: string
  name: string
  outputVersion: ProtocolOutputVersion
  allowedTopics: string[]
  lastSeen: number
  currentSequence: number
  status: ProtocolTargetStatus
  errorCode: string | null
}

export type ProtocolTargetCreated = {
  target: ProtocolPublicationTarget
  oneTimeKey: string
}

export type ProtocolFrozenVersion = {
  versionId: string
  draftId: string
  draftRevision: number
  digest: string
  configuration: ProtocolConfiguration
  createdAt: number
}

export type ProtocolDeployment = {
  targetId: string
  sequence: number
  digest: string
  approvalId: string
  status: ProtocolDeploymentStatus
  errorCode: string | null
  createdAt: number
  loadedAt: number
}

export type ProtocolVersionSummary = { versionId: string; name: string; profileCode: string; revision: number; mappingCount: number }
export type ProtocolPublicationPreview = {
  targetId: string; expectedSequence: number; digest: string
  currentVersions: ProtocolVersionSummary[]; targetVersions: ProtocolVersionSummary[]
  changes: { profileCode: string; changeType: 'ADDED' | 'REPLACED' | 'RETAINED' | 'REMOVED'; beforeVersion: ProtocolVersionSummary | null; afterVersion: ProtocolVersionSummary | null }[]
}
export type ProtocolDeploymentDetail = ProtocolDeployment & { versions: ProtocolVersionSummary[] }

export type ProtocolImportProfile = { profileId: string; profileCode: string; archived: boolean }
export type ParsedProtocolImport = { snapshotJson: string; archiveJson: string | null; profiles: ProtocolImportProfile[] }

/** 只拆分适配器导出对象并提取绑定键；业务映射内容原样交给后端校验与导入。 */
export function parseProtocolExportPackage(payload: string): ParsedProtocolImport {
  const value = JSON.parse(payload) as Record<string, unknown>
  const snapshot = parseExportGroup(value.snapshot, false)
  const archived = value.archived == null ? null : parseExportGroup(value.archived, true)
  const profiles = [...snapshot.profiles, ...(archived?.profiles ?? [])]
  if (!profiles.length || new Set(profiles.map(item => item.profileId)).size !== profiles.length) throw new Error('INVALID_PROTOCOL_EXPORT')
  return { snapshotJson: JSON.stringify(snapshot.raw), archiveJson: archived ? JSON.stringify(archived.raw) : null, profiles }
}

function parseExportGroup(input: unknown, archived: boolean) {
  if (!input || typeof input !== 'object') throw new Error('INVALID_PROTOCOL_EXPORT')
  const raw = input as Record<string, unknown>
  if (raw.schemaVersion !== 1 || !['V1', 'V2'].includes(String(raw.outputVersion)) || !Array.isArray(raw.profiles)) throw new Error('INVALID_PROTOCOL_EXPORT')
  const profiles = raw.profiles.map(item => {
    if (!item || typeof item !== 'object') throw new Error('INVALID_PROTOCOL_EXPORT')
    const profile = (item as Record<string, unknown>).profile
    if (!profile || typeof profile !== 'object') throw new Error('INVALID_PROTOCOL_EXPORT')
    const { profileId, profileCode } = profile as Record<string, unknown>
    if (typeof profileId !== 'string' || !profileId.trim() || typeof profileCode !== 'string' || !profileCode.trim()) throw new Error('INVALID_PROTOCOL_EXPORT')
    return { profileId, profileCode, archived }
  })
  return { raw, profiles }
}

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
