import { requestApi } from '@/infrastructure/http/public'
import type { JointFlowRequest, JointFlowView } from './joint-flow'
import type { JointFlowConfiguration, JointFlowConfigurationVersion, JointFlowSelection } from '../models/joint-flow-configuration'

const basePath = '/v1/carbon-management/joint-flow/buildings'
const encoded = (value: string) => encodeURIComponent(value)
const configurationsPath = (buildingId: string) => `${basePath}/${encoded(buildingId)}/configurations`
const configurationPath = (buildingId: string, versionId: string) => `${configurationsPath(buildingId)}/${encoded(versionId)}`

export interface JointFlowCreateConfigurationRequest {
  reason: string
  copiedFromVersionId?: string | null
}

export interface JointFlowSaveConfigurationRequest {
  expectedRevision: number
  configuration: JointFlowConfiguration
  reason: string
}

export interface JointFlowRevisionReasonRequest {
  expectedRevision: number
  reason: string
}

export interface JointFlowSelectionRequest extends JointFlowRevisionReasonRequest {
  versionId: string
}

/** 配置写入只存在于管理页；展示查询由 query/preview 端点单独承担。 */
export const jointFlowConfigurationApi = {
  list(buildingId: string, signal?: AbortSignal) {
    return requestApi<JointFlowConfigurationVersion[]>({ method: 'get', url: configurationsPath(buildingId), signal })
  },
  get(buildingId: string, versionId: string, signal?: AbortSignal) {
    return requestApi<JointFlowConfigurationVersion>({ method: 'get', url: configurationPath(buildingId, versionId), signal })
  },
  create(buildingId: string, data: JointFlowCreateConfigurationRequest) {
    return requestApi<JointFlowConfigurationVersion>({ method: 'post', url: configurationsPath(buildingId), data })
  },
  save(buildingId: string, versionId: string, data: JointFlowSaveConfigurationRequest) {
    return requestApi<JointFlowConfigurationVersion>({ method: 'put', url: configurationPath(buildingId, versionId), data })
  },
  submit(buildingId: string, versionId: string, data: JointFlowRevisionReasonRequest) {
    return requestApi<JointFlowConfigurationVersion>({ method: 'post', url: `${configurationPath(buildingId, versionId)}/submit`, data })
  },
  approve(buildingId: string, versionId: string, data: JointFlowRevisionReasonRequest) {
    return requestApi<JointFlowConfigurationVersion>({ method: 'post', url: `${configurationPath(buildingId, versionId)}/approve`, data })
  },
  reject(buildingId: string, versionId: string, data: JointFlowRevisionReasonRequest) {
    return requestApi<JointFlowConfigurationVersion>({ method: 'post', url: `${configurationPath(buildingId, versionId)}/reject`, data })
  },
  getSelection(buildingId: string, signal?: AbortSignal) {
    return requestApi<JointFlowSelection>({ method: 'get', url: `${configurationsPath(buildingId)}/selection`, signal })
  },
  updateSelection(buildingId: string, data: JointFlowSelectionRequest) {
    return requestApi<JointFlowSelection>({ method: 'put', url: `${configurationsPath(buildingId)}/selection`, data })
  },
  preview(buildingId: string, data: JointFlowRequest, signal?: AbortSignal) {
    return requestApi<JointFlowView>({ method: 'post', url: '/v1/carbon-management/joint-flow/preview', data: { ...data, buildingId }, signal })
  },
}
