import { requestApi } from '@/infrastructure/http/public'

export type ConfigurationGuideDomain = 'RELATION' | 'CLASSIFICATION' | 'PROJECT' | 'SEALED_SOURCE' | 'FACTORS'
export type ConfigurationGuideState = 'MISSING' | 'BLOCKED' | 'REVIEW_REQUIRED' | 'ACTIVATION_REQUIRED' | 'CONFIGURED' | 'NOT_CHECKED' | 'RESERVED'
export type ConfigurationGuideResponsibility = 'ENERGY_MANAGER' | 'INDEPENDENT_REVIEWER' | 'RULE_ACTIVATOR' | 'CARBON_RULE_MAINTAINER' | 'PLATFORM_ADMIN'
export type ConfigurationGuideAction = 'CONFIGURE' | 'RESOLVE_ISSUES' | 'SUBMIT' | 'REVIEW' | 'ACTIVATE' | 'VIEW' | 'VERIFY_SEALED_MONTH' | 'VERIFY_FACTORS'

export interface ConfigurationGuideIssue {
  code: string
  reference: string | null
  message: string
}

export interface ConfigurationGuideStep {
  domain: ConfigurationGuideDomain
  reference: string | null
  revision: number | null
  state: ConfigurationGuideState
  responsibility: ConfigurationGuideResponsibility
  action: ConfigurationGuideAction
  issues: ConfigurationGuideIssue[]
}

export interface ConfigurationGuide {
  buildingId: string
  versionId: string
  revision: number
  calculationStatus: 'NOT_CHECKED'
  steps: ConfigurationGuideStep[]
}

const basePath = '/v1/carbon-projects/buildings'
const encoded = (value: string) => encodeURIComponent(value)

export const configurationGuideApi = {
  get(buildingId: string, versionId: string, signal?: AbortSignal) {
    return requestApi<ConfigurationGuide>({
      method: 'get',
      url: basePath + '/' + encoded(buildingId) + '/versions/' + encoded(versionId) + '/guide',
      signal,
    })
  },
}
