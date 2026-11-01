import { requestApi } from '@/infrastructure/http/public'

export interface EnergyFlowSelection { energyItemCode: string; unit: string; parameterVersionId: string }
export interface EnergyFlowOption extends EnergyFlowSelection {
  parameterCode: string; method: string; perspective: string; consumptionScope: string; regionCode: string
}
export interface EnergyFlowGraph {
  schemeCode: string; available: boolean; unavailableReason: string | null
  total: string | null; unallocated: string | null; residual: string | null
  nodes: Array<{ id: string; type: string; code: string; name: string }>
  links: Array<{ source: string; target: string; value: string }>
}
export interface EnergyFlowView {
  buildingId: string; month: string; unit: 'TCE'; state: 'READY' | 'SELECTION_REQUIRED' | 'CONFIGURATION_REQUIRED' | 'INCOMPLETE'
  reason: string | null; resultNature: string; classificationVersionId: string; relationVersionId: string
  resultVersion: string | null; parameterVersionIds: string[]; formulaVersionIds: string[]
  standardCoalVersionIds: string[]; sourceEvidenceIds: string[]; conversionOptions: EnergyFlowOption[]; flows: EnergyFlowGraph[]
}

/** 服务端分解和折标，浏览器只提交本次查看口径，不累计能流边。 */
export function queryEnergyFlow(buildingId: string, month: string, selections: EnergyFlowSelection[], signal?: AbortSignal) {
  return requestApi<EnergyFlowView>({ method: 'post', url: '/v1/energy-boundary-summaries/flow/query', data: { buildingId, month, selections }, signal })
}
