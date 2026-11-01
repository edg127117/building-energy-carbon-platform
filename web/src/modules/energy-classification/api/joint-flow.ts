import { requestApi } from '@/infrastructure/http/public'

export type { JointFlowConfiguration, JointFlowConfigurationVersion, JointFlowConversion, JointFlowDimension, JointFlowDimensionKey, JointFlowDimensionNode, JointFlowDimensionSource, JointFlowFloorReference, JointFlowSelection } from '../models/joint-flow-configuration'

export interface JointFlowRequest {
  buildingId: string
  periodType: 'YEAR' | 'MONTH'
  period: string
  throughMonth?: string
  configurationVersionId?: string
}

export interface JointFlowNode {
  id: string
  code: string
  name: string
  depth: number
  order: number
  value: string
}

export interface JointFlowGraph {
  state: string
  reason: string | null
  unit: string
  total: string | null
  unallocated: string | null
  nodes: JointFlowNode[]
  links: Array<{ source: string; target: string; value: string }>
}

export interface JointFlowCoverage {
  totalAtoms: number
  allocatedAtoms: number
  dimensions: Array<{ key: string; allocatedAtoms: number; unallocatedAtoms: number }>
}

export interface JointFlowView {
  buildingId: string
  periodType: 'YEAR' | 'MONTH'
  period: string
  throughMonth: string | null
  timezoneId: string
  startInclusive: string
  endExclusive: string
  state: string
  classificationBasis: 'CONFIGURED_VIEW' | 'DRAFT_PREVIEW'
  configurationVersionId: string | null
  configurationHash: string | null
  resultVersion: string
  missingMonths: string[]
  sources: Array<{ month: string; batchId: string; classificationVersionId: string; relationVersionId: string;
    roundingPolicyVersionId: string; rawCarbonKg: string; publishedCarbonTons: string; sourceNature: string; evidenceHashes: string[] }>
  parameterVersionIds: string[]
  formulaVersionIds: string[]
  standardCoalVersionIds: string[]
  conversionOptions: Array<{ energyItemCode: string; parameterVersionId: string; method: string; perspective: string;
    consumptionScope: string; regionCode: string; effectiveFrom: string; effectiveTo: string | null }>
  warnings: string[]
  dimensions: Array<{ key: string; title: string; source: string; schemeCode?: string | null; scopeLevel?: string | null; nodes: Array<{ code: string; name: string; order: number }> }>
  coverage: JointFlowCoverage
  carbon: JointFlowGraph
  energy: JointFlowGraph
}

const basePath = '/v1/carbon-management/joint-flow'

/** 查询与草稿预览均由后端完成冻结结果归属和数值计算；浏览器只传递范围及明确配置版本。 */
export function queryJointFlow(request: JointFlowRequest, signal?: AbortSignal) {
  return requestApi<JointFlowView>({ method: 'post', url: `${basePath}/query`, data: request, signal })
}

export function previewJointFlow(request: JointFlowRequest, signal?: AbortSignal) {
  return requestApi<JointFlowView>({ method: 'post', url: `${basePath}/preview`, data: request, signal })
}
