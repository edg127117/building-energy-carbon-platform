import { cloneConfiguration, type ClassificationConfiguration } from './classification'

export type JointFlowDimensionKey = 'ENERGY_TYPE' | 'USAGE' | 'FLOOR' | 'DEVICE_BREAKDOWN'
export type JointFlowDimensionSource = 'ENERGY_ITEM' | 'FROZEN_SCHEME' | 'MAPPED_SCHEME'

export interface JointFlowDimensionNode {
  code: string
  name: string
  order: number
}

export interface JointFlowDimension {
  key: JointFlowDimensionKey
  title: string
  source: JointFlowDimensionSource
  schemeCode?: string | null
  scopeLevel?: 'FLOOR' | null
  nodes: JointFlowDimensionNode[]
}

export interface JointFlowConversion {
  energyItemCode: string
  inputUnitCode: string
  parameterVersionId: string
}

export interface JointFlowFloorReference {
  relationVersionId: string
  schemeCode: string
  nodeCode: string
  spaceId: string
}

export interface JointFlowConfiguration {
  schemaVersion: 1
  startInclusive: string
  endExclusive: string
  relationVersionIds: string[]
  dimensions: JointFlowDimension[]
  mappings: ClassificationConfiguration[]
  conversions: JointFlowConversion[]
  floorReferences: JointFlowFloorReference[]
  sourceReference: string
}

export interface JointFlowConfigurationVersion {
  versionId: string
  buildingId: string
  versionNo: number
  revision: number
  status: 'DRAFT' | 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED'
  configuration: JointFlowConfiguration
  configurationHash: string
  createdBy: string | number
  reviewedBy?: string | number | null
}

export interface JointFlowSelection {
  buildingId: string
  versionId: string | null
  revision: number
}

export type JointFlowConfigurationIssueCode =
  | 'SCHEMA_VERSION' | 'INVALID_START' | 'INVALID_END' | 'INVALID_RANGE'
  | 'EMPTY_RELATION_VERSION' | 'DUPLICATE_RELATION_VERSION'
  | 'EMPTY_MAPPING_RELATION' | 'DUPLICATE_MAPPING_RELATION' | 'RELATION_MAPPING_MISMATCH'
  | 'INCOMPLETE_FLOOR_REFERENCE' | 'FLOOR_REFERENCE_RELATION_MISMATCH'
  | 'DIMENSION_COUNT' | 'DUPLICATE_DIMENSION' | 'EMPTY_DIMENSION_TITLE'
  | 'ENERGY_ITEM_SOURCE' | 'CLASSIFICATION_SOURCE' | 'FLOOR_SCOPE' | 'EMPTY_SCHEME_CODE' | 'MAPPED_SCHEME_MISMATCH'
  | 'EMPTY_NODE_CODE' | 'DUPLICATE_NODE_CODE' | 'INVALID_NODE' | 'MISSING_FLOOR_REFERENCE'

export interface JointFlowConfigurationIssue {
  path: string
  code: JointFlowConfigurationIssueCode
}

export function emptyJointFlowConfiguration(): JointFlowConfiguration {
  return {
    schemaVersion: 1,
    startInclusive: '',
    endExclusive: '',
    relationVersionIds: [],
    dimensions: [],
    mappings: [],
    conversions: [],
    floorReferences: [],
    sourceReference: '',
  }
}

export function cloneJointFlowConfiguration(value: JointFlowConfiguration | null | undefined): JointFlowConfiguration {
  const configuration = value ?? emptyJointFlowConfiguration()
  return {
    schemaVersion: 1,
    startInclusive: configuration.startInclusive ?? '',
    endExclusive: configuration.endExclusive ?? '',
    relationVersionIds: [...(configuration.relationVersionIds ?? [])],
    dimensions: (configuration.dimensions ?? []).map(dimension => ({
      key: dimension.key,
      title: dimension.title ?? '',
      source: dimension.source,
      schemeCode: dimension.schemeCode?.trim() || null,
      scopeLevel: dimension.scopeLevel ?? null,
      nodes: (dimension.nodes ?? []).map(node => ({ code: node.code ?? '', name: node.name ?? '', order: node.order })),
    })),
    mappings: (configuration.mappings ?? []).map(mapping => cloneConfiguration(mapping)),
    conversions: (configuration.conversions ?? []).map(conversion => ({ ...conversion })),
    floorReferences: (configuration.floorReferences ?? []).map(reference => ({ ...reference })),
    sourceReference: configuration.sourceReference ?? '',
  }
}

/** 这是保存前的结构提示，不替代后端对关系快照、互斥归属、权限和冻结原子的最终校验。 */
export function validateJointFlowConfiguration(value: JointFlowConfiguration): JointFlowConfigurationIssue[] {
  const issues: JointFlowConfigurationIssue[] = []
  const add = (path: string, code: JointFlowConfigurationIssueCode) => issues.push({ path, code })
  if (value.schemaVersion !== 1) add('schemaVersion', 'SCHEMA_VERSION')
  const start = Date.parse(value.startInclusive)
  const end = Date.parse(value.endExclusive)
  if (!value.startInclusive || Number.isNaN(start)) add('startInclusive', 'INVALID_START')
  if (!value.endExclusive || Number.isNaN(end)) add('endExclusive', 'INVALID_END')
  if (!Number.isNaN(start) && !Number.isNaN(end) && start >= end) add('range', 'INVALID_RANGE')

  const relationIds = value.relationVersionIds.map(item => item.trim())
  if (relationIds.some(item => !item)) add('relationVersionIds', 'EMPTY_RELATION_VERSION')
  if (new Set(relationIds).size !== relationIds.length) add('relationVersionIds', 'DUPLICATE_RELATION_VERSION')
  const mappingIds = value.mappings.map(item => item.relationVersionId?.trim() ?? '')
  if (mappingIds.some(item => !item)) add('mappings', 'EMPTY_MAPPING_RELATION')
  if (new Set(mappingIds).size !== mappingIds.length) add('mappings', 'DUPLICATE_MAPPING_RELATION')
  if (relationIds.some(id => !mappingIds.includes(id)) || mappingIds.some(id => !relationIds.includes(id))) {
    add('mappings', 'RELATION_MAPPING_MISMATCH')
  }
  const floorReferences = value.floorReferences ?? []
  floorReferences.forEach((reference, index) => {
    if (!reference.relationVersionId?.trim() || !reference.schemeCode?.trim() || !reference.nodeCode?.trim() || !reference.spaceId?.trim()) {
      add(`floorReferences[${index}]`, 'INCOMPLETE_FLOOR_REFERENCE')
    }
    if (!relationIds.includes(reference.relationVersionId?.trim() ?? '')) {
      add(`floorReferences[${index}]`, 'FLOOR_REFERENCE_RELATION_MISMATCH')
    }
  })

  if (value.dimensions.length < 1 || value.dimensions.length > 3) add('dimensions', 'DIMENSION_COUNT')
  const keys = value.dimensions.map(dimension => dimension.key)
  if (new Set(keys).size !== keys.length) add('dimensions', 'DUPLICATE_DIMENSION')
  value.dimensions.forEach((dimension, index) => {
    const path = `dimensions[${index}]`
    if (!dimension.title.trim()) add(path, 'EMPTY_DIMENSION_TITLE')
    if (dimension.key === 'ENERGY_TYPE' && dimension.source !== 'ENERGY_ITEM') {
      add(path, 'ENERGY_ITEM_SOURCE')
    }
    if (dimension.key !== 'ENERGY_TYPE' && !['FROZEN_SCHEME', 'MAPPED_SCHEME'].includes(dimension.source)) {
      add(path, 'CLASSIFICATION_SOURCE')
    }
    if (dimension.key === 'FLOOR' && dimension.scopeLevel !== 'FLOOR') {
      add(path, 'FLOOR_SCOPE')
    }
    if (dimension.key !== 'ENERGY_TYPE' && !dimension.schemeCode?.trim()) {
      add(path, 'EMPTY_SCHEME_CODE')
    }
    if (dimension.source === 'MAPPED_SCHEME' && !value.mappings.some(mapping => mapping.schemes.some(scheme => scheme.code === dimension.schemeCode))) {
      add(path, 'MAPPED_SCHEME_MISMATCH')
    }
    const nodeCodes = dimension.nodes.map(node => node.code.trim())
    if (nodeCodes.some(code => !code)) add(path, 'EMPTY_NODE_CODE')
    if (new Set(nodeCodes).size !== nodeCodes.length) add(path, 'DUPLICATE_NODE_CODE')
    dimension.nodes.forEach((node, nodeIndex) => {
      if (!node.name.trim() || !Number.isSafeInteger(node.order)) add(`${path}.nodes[${nodeIndex}]`, 'INVALID_NODE')
      if (dimension.key === 'FLOOR' && !floorReferences.some(reference => relationIds.includes(reference.relationVersionId)
        && reference.schemeCode === dimension.schemeCode && reference.nodeCode === node.code)) {
        add(`${path}.nodes[${nodeIndex}]`, 'MISSING_FLOOR_REFERENCE')
      }
    })
  })
  return issues
}
