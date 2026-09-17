import { requestApi } from '@/infrastructure/http/public'
import type {
  InspectResult,
  ProtocolConfiguration,
  ProtocolConfigurationDetail,
  ProtocolConfigurationPage,
  ProtocolPreview,
  ProtocolDeployment,
  ProtocolFrozenVersion,
  ProtocolOutputVersion,
  ProtocolPublicationTarget,
  ProtocolTargetCreated,
} from '../models/protocol-configuration'
import type { SensitiveChange } from '@/modules/access-control/public'

const path = '/v1/protocol-configurations'
const encoded = (value: string) => encodeURIComponent(value)

export function listProtocolConfigurations(params: { page: number; size: number }) {
  return requestApi<ProtocolConfigurationPage>({ method: 'get', url: path, params })
}

export function getProtocolConfiguration(id: string) {
  return requestApi<ProtocolConfigurationDetail>({ method: 'get', url: `${path}/${encoded(id)}` })
}

export function createProtocolConfiguration(configuration: ProtocolConfiguration) {
  return requestApi<ProtocolConfigurationDetail>({ method: 'post', url: path, data: configuration })
}

export function updateProtocolConfiguration(id: string, revision: number, configuration: ProtocolConfiguration) {
  return requestApi<ProtocolConfigurationDetail>({ method: 'put', url: `${path}/${encoded(id)}`, data: { revision, configuration } })
}

export function inspectProtocolSample(samplePayload: string) {
  return requestApi<InspectResult>({ method: 'post', url: `${path}/inspect`, data: { samplePayload } })
}

export function previewProtocolConfiguration(configuration: ProtocolConfiguration, samplePayload: string, receivedTime: number) {
  return requestApi<ProtocolPreview>({ method: 'post', url: `${path}/preview`, data: { configuration, samplePayload, receivedTime } })
}

const deploymentPath = '/v1/protocol-deployments'

export function previewProtocolPublication(data: { targetId: string; expectedSequence: number; versionIds: string[] }) {
  return requestApi<import('../models/protocol-configuration').ProtocolPublicationPreview>({ method: 'post', url: `${deploymentPath}/preview`, data })
}
export function previewProtocolRollback(data: { targetId: string; expectedSequence: number; historicalSequence: number }) {
  return requestApi<import('../models/protocol-configuration').ProtocolPublicationPreview>({ method: 'post', url: `${deploymentPath}/rollback-preview`, data })
}
export function getProtocolDeploymentDetail(targetId: string, sequence: number) {
  return requestApi<import('../models/protocol-configuration').ProtocolDeploymentDetail>({ method: 'get', url: `${deploymentPath}/targets/${encoded(targetId)}/history/${sequence}` })
}

export function listProtocolPublicationTargets() {
  return requestApi<ProtocolPublicationTarget[]>({ method: 'get', url: `${deploymentPath}/targets` })
}

export function registerProtocolPublicationTarget(data: { name: string; outputVersion: ProtocolOutputVersion; allowedTopics: string[] }) {
  return requestApi<ProtocolTargetCreated>({ method: 'post', url: `${deploymentPath}/targets`, data })
}

export function freezeProtocolVersion(draftId: string, revision: number) {
  return requestApi<ProtocolFrozenVersion>({ method: 'post', url: `${deploymentPath}/versions/${encoded(draftId)}`, data: { revision } })
}

export function listProtocolVersions() {
  return requestApi<ProtocolFrozenVersion[]>({ method: 'get', url: `${deploymentPath}/versions` })
}

export function listProtocolDeploymentHistory(targetId: string) {
  return requestApi<ProtocolDeployment[]>({ method: 'get', url: `${deploymentPath}/targets/${encoded(targetId)}/history` })
}

export function requestProtocolPublication(data: { targetId: string; expectedSequence: number; versionIds: string[]; idempotencyKey: string }) {
  return requestApi<SensitiveChange>({ method: 'post', url: `${deploymentPath}/requests`, data })
}

export function requestProtocolRollback(data: { targetId: string; historicalSequence: number; expectedSequence: number; idempotencyKey: string }) {
  return requestApi<SensitiveChange>({ method: 'post', url: `${deploymentPath}/rollback-requests`, data })
}

export function importProtocolVersions(data: { snapshotJson: string; archiveJson: string | null; productBindings: Record<string, string> }) {
  return requestApi<ProtocolFrozenVersion[]>({ method: 'post', url: `${deploymentPath}/import`, data })
}
