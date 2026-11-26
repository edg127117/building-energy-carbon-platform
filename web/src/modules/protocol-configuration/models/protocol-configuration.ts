import type { DeviceProductDetail, ProductPointTemplate } from '@/modules/device-onboarding/public'

export type ProtocolFieldType = 'NUMBER' | 'STRING' | 'BOOLEAN' | 'NULL'
export type ProtocolAckMode = 'DEVICE_DIRECT' | 'ADAPTER_PROXY' | 'EVIDENCE_ONLY'
export type ProtocolCorrelationPolicy = 'SOURCE_MESSAGE_ID' | 'BOOT_ID_AND_SEQ' | 'SEQ_AND_COLLECTED_AT' | 'UNIQUE_COLLECTED_AT' | 'NONE'
export type ProtocolMappingValueType =
  | 'DECIMAL'
  | 'ELECTRICITY_KWH'
  | 'CO2_FACTOR'
  | 'INTERVAL_START'
  | 'INTERVAL_END'
  | 'INTERVAL_REVISION'

/** 未冻结草稿在只读发布预览中的固定占位版本标识，仅用于展示，不作为正式版本提交。 */
export const DRAFT_PREVIEW_VERSION_ID = 'DRAFT_PREVIEW'

const INTERVAL_PARAMETER_TYPES = new Set<ProtocolMappingValueType>(['INTERVAL_START', 'INTERVAL_END', 'INTERVAL_REVISION'])

export type ProtocolMapping = {
  valueType?: ProtocolMappingValueType | null
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

/** 后端草稿暂存（B1）允许部分路径、原始单位、倍率与偏移为空，读取后需先归一化再交给编辑器。 */
export type ProtocolMappingWire = Omit<ProtocolMapping, 'sourcePath' | 'sourceUnit' | 'targetUnit' | 'scale' | 'offset' | 'required' | 'enabled' | 'sortOrder'> & {
  sourcePath?: string | null
  sourceUnit?: string | null
  targetUnit?: string | null
  scale?: string | number | null
  offset?: string | number | null
  required?: boolean | null
  enabled?: boolean | null
  sortOrder?: number | null
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
  seqPath?: string | null
  messageIdPath?: string | null
  bootIdPath?: string | null
  batchIdPath?: string | null
  retransmittedAtPath?: string | null
  maxAckMode?: ProtocolAckMode | null
  correlationPolicy?: ProtocolCorrelationPolicy | null
  mappings: ProtocolMapping[]
}

export type ProtocolConfigurationWire = Omit<ProtocolConfiguration, 'name' | 'productId' | 'profileCode' | 'sourceTopic' | 'identityType' | 'identityPath' | 'mappings'> & {
  name?: string | null
  productId?: string | null
  profileCode?: string | null
  sourceTopic?: string | null
  identityType?: string | null
  identityPath?: string | null
  mappings?: ProtocolMappingWire[] | null
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

export type PreviewErrorField =
  | 'sourceTopic'
  | 'identityPath'
  | 'timestampPath'
  | 'discriminatorPath'
  | 'discriminatorValue'
  | 'mappings'

export type PreviewMappingField = 'sourcePath' | 'sourceUnit' | 'targetUnit' | 'scale' | 'offset'

export type PreviewErrorTarget =
  | { kind: 'field'; field: PreviewErrorField }
  | { kind: 'mapping'; metricCode: string; field: PreviewMappingField | null }
  | { kind: 'pointer'; pointer: string }
  | { kind: 'unknown'; raw: string }

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
  targetId: string
  expectedSequence: number
  digest: string
  currentVersions: ProtocolVersionSummary[]
  targetVersions: ProtocolVersionSummary[]
  changes: {
    profileCode: string
    changeType: 'ADDED' | 'REPLACED' | 'RETAINED' | 'REMOVED'
    beforeVersion: ProtocolVersionSummary | null
    afterVersion: ProtocolVersionSummary | null
  }[]
}
export type ProtocolDeploymentDetail = ProtocolDeployment & { versions: ProtocolVersionSummary[] }

export type ProtocolImportProfile = { profileId: string; profileCode: string; archived: boolean }
export type ParsedProtocolImport = { snapshotJson: string; archiveJson: string | null; profiles: ProtocolImportProfile[] }

const TOP_LEVEL_PREVIEW_FIELDS = new Set<PreviewErrorField>([
  'sourceTopic',
  'identityPath',
  'timestampPath',
  'discriminatorPath',
  'discriminatorValue',
  'mappings',
])

const MAPPING_SUB_FIELDS = new Set<PreviewMappingField>(['sourcePath', 'sourceUnit', 'targetUnit', 'scale', 'offset'])

/** 周期量参数映射（起止时间与修订号）不属于产品测点模板，但属于运行时协议契约，编辑产品时必须保留。 */
export function isIntervalParameterMapping(mapping: Pick<ProtocolMapping, 'valueType'>): boolean {
  return Boolean(mapping.valueType && INTERVAL_PARAMETER_TYPES.has(mapping.valueType))
}

export function normalizeProtocolMapping(raw: ProtocolMappingWire): ProtocolMapping {
  const normalized: ProtocolMapping = {
    sourcePath: raw.sourcePath ?? '',
    metricCode: raw.metricCode ?? '',
    sourceUnit: raw.sourceUnit ?? '',
    targetUnit: raw.targetUnit ?? '',
    scale: raw.scale == null ? '' : String(raw.scale),
    offset: raw.offset == null ? '' : String(raw.offset),
    required: Boolean(raw.required),
    enabled: raw.enabled ?? true,
    sortOrder: typeof raw.sortOrder === 'number' && Number.isFinite(raw.sortOrder) ? raw.sortOrder : 0,
  }
  if (raw.valueType !== undefined) normalized.valueType = raw.valueType
  return normalized
}

/**
 * 将后端返回的协议草稿归一化为前端可编辑结构：处理 B1 允许的 null 字段并原样保留 V2 与周期量元数据。
 */
export function normalizeProtocolConfiguration(raw: ProtocolConfigurationWire | null | undefined): ProtocolConfiguration {
  const base = emptyProtocolConfiguration()
  if (!raw) return base
  return {
    ...base,
    name: raw.name ?? '',
    productId: raw.productId ?? '',
    profileCode: raw.profileCode ?? '',
    sourceTopic: raw.sourceTopic ?? '',
    identityType: raw.identityType ?? '',
    identityPath: raw.identityPath ?? '',
    discriminatorPath: raw.discriminatorPath ?? null,
    discriminatorValue: raw.discriminatorValue ?? null,
    timestampPath: raw.timestampPath ?? null,
    seqPath: raw.seqPath ?? null,
    messageIdPath: raw.messageIdPath ?? null,
    bootIdPath: raw.bootIdPath ?? null,
    batchIdPath: raw.batchIdPath ?? null,
    retransmittedAtPath: raw.retransmittedAtPath ?? null,
    maxAckMode: raw.maxAckMode ?? null,
    correlationPolicy: raw.correlationPolicy ?? null,
    mappings: Array.isArray(raw.mappings) ? raw.mappings.map(normalizeProtocolMapping) : [],
  }
}

/**
 * 序列化草稿供后端保存：剥离 Vue 响应式代理，并将空倍率/偏移转为 null 以匹配后端 BigDecimal 反序列化。
 */
export function serializeProtocolConfigurationForSave(configuration: ProtocolConfiguration): ProtocolConfiguration {
  const snapshot = JSON.parse(JSON.stringify(configuration)) as ProtocolConfiguration
  snapshot.mappings = (snapshot.mappings ?? []).map(mapping => ({
    ...mapping,
    scale: (mapping.scale ?? '').trim() === '' ? (null as unknown as string) : mapping.scale,
    offset: (mapping.offset ?? '').trim() === '' ? (null as unknown as string) : mapping.offset,
  }))
  return snapshot
}

/**
 * 解析后端试解析返回的 PreviewError.path，兼容顶层配置字段、mappings.<metricCode>[.<field>] 与样例 JSON Pointer。
 */
export function parsePreviewErrorTarget(path: string): PreviewErrorTarget {
  const normalized = path.trim()
  if (!normalized) return { kind: 'unknown', raw: '' }
  if (normalized.startsWith('/')) return { kind: 'pointer', pointer: normalized }
  if (TOP_LEVEL_PREVIEW_FIELDS.has(normalized as PreviewErrorField)) {
    return { kind: 'field', field: normalized as PreviewErrorField }
  }
  if (normalized.startsWith('mappings.')) {
    const rest = normalized.slice('mappings.'.length)
    const dotIndex = rest.indexOf('.')
    if (dotIndex === -1 && rest) return { kind: 'mapping', metricCode: rest, field: null }
    if (dotIndex > 0) {
      const metricCode = rest.slice(0, dotIndex)
      const subField = rest.slice(dotIndex + 1)
      return {
        kind: 'mapping',
        metricCode,
        field: MAPPING_SUB_FIELDS.has(subField as PreviewMappingField) ? (subField as PreviewMappingField) : null,
      }
    }
  }
  return { kind: 'unknown', raw: normalized }
}

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
  seqPath: null,
  messageIdPath: null,
  bootIdPath: null,
  batchIdPath: null,
  retransmittedAtPath: null,
  maxAckMode: null,
  correlationPolicy: null,
  mappings: [],
})

export function applyProductContract(configuration: ProtocolConfiguration, product: DeviceProductDetail): ProtocolConfiguration {
  const points = new Map(product.points.filter(point => point.enabled).map(point => [point.metricCode, point]))
  return {
    ...configuration,
    productId: product.productId,
    profileCode: product.expectedProfileCode,
    identityType: product.identityType,
    mappings: (configuration.mappings ?? [])
      .filter(mapping => isIntervalParameterMapping(mapping) || points.has(mapping.metricCode))
      .map(mapping => {
        if (isIntervalParameterMapping(mapping)) return { ...mapping }
        const point = points.get(mapping.metricCode)!
        return { ...mapping, targetUnit: point.unit, required: point.required || mapping.required }
      }),
  }
}

/**
 * 草稿暂存级校验（对应后端 B1 放宽契约）：仅要求名称与产品一致，允许 Topic、身份路径与映射后补。
 */
export function validateProtocolDraft(configuration: ProtocolConfiguration, product: DeviceProductDetail | null): string | null {
  if (!(configuration.name ?? '').trim()) return 'draftNameRequired'
  if (!product || configuration.productId !== product.productId) return 'product'
  if (configuration.profileCode !== product.expectedProfileCode || configuration.identityType !== product.identityType) return 'productContract'
  const hasDiscriminatorPath = Boolean(configuration.discriminatorPath?.trim())
  const hasDiscriminatorValue = Boolean(configuration.discriminatorValue?.trim())
  if (hasDiscriminatorPath !== hasDiscriminatorValue) return 'discriminator'
  if ((configuration.mappings ?? []).length > 128) return 'mappingLimit'
  return null
}

/** 完整配置校验：用于发布前准备与前端完整度提示；周期量参数跳过产品测点表核对，与后端语义一致。 */
export function validateProtocolConfiguration(configuration: ProtocolConfiguration, product: DeviceProductDetail | null): string | null {
  if (!(configuration.name ?? '').trim() || !(configuration.sourceTopic ?? '').trim() || !(configuration.identityPath ?? '').trim()) return 'required'
  if (!product || configuration.productId !== product.productId) return 'product'
  if (configuration.profileCode !== product.expectedProfileCode || configuration.identityType !== product.identityType) return 'productContract'
  const hasDiscriminatorPath = Boolean(configuration.discriminatorPath?.trim())
  const hasDiscriminatorValue = Boolean(configuration.discriminatorValue?.trim())
  if (hasDiscriminatorPath !== hasDiscriminatorValue) return 'discriminator'
  const mappings = configuration.mappings ?? []
  if (mappings.length > 128) return 'mappingLimit'
  const enabled = mappings.filter(mapping => mapping.enabled)
  if (!enabled.length) return 'mappingRequired'
  if (new Set(enabled.map(mapping => mapping.metricCode)).size !== enabled.length) return 'duplicateMetric'
  const pointMap = new Map(product.points.filter(point => point.enabled).map(point => [point.metricCode, point]))
  for (const mapping of mappings) {
    if (isIntervalParameterMapping(mapping)) {
      if (!(mapping.sourcePath ?? '').trim()) return 'mappingFields'
      continue
    }
    const point = pointMap.get(mapping.metricCode)
    if (!point || mapping.targetUnit !== point.unit || (point.required && !mapping.required)) return 'mappingContract'
    if (!(mapping.sourcePath ?? '').trim() || !(mapping.sourceUnit ?? '').trim() || !(mapping.scale ?? '').trim() || !(mapping.offset ?? '').trim()) return 'mappingFields'
  }
  const mapped = new Set(enabled.map(mapping => mapping.metricCode))
  if (product.points.some(point => point.enabled && point.required && !mapped.has(point.metricCode))) return 'missingRequiredMetric'
  return null
}
