import { requestApi } from '@/infrastructure/http/public'
import type {
  InspectResult,
  ProtocolConfiguration,
  ProtocolConfigurationDetail,
  ProtocolConfigurationPage,
  ProtocolDeployment,
  ProtocolDeploymentDetail,
  ProtocolFrozenVersion,
  ProtocolOutputVersion,
  ProtocolPreview,
  ProtocolPublicationPreview,
  ProtocolPublicationTarget,
  ProtocolTargetCreated,
} from '../models/protocol-configuration'
import type { SensitiveChange } from '@/modules/access-control/public'

const path = '/v1/protocol-configurations'
const encoded = (value: string) => encodeURIComponent(value)

export function listProtocolConfigurations(params: { page: number; size: number; keyword?: string; productId?: string }) {
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

/** 仅允许删除尚未被不可变冻结版本引用的废弃草稿；已引用草稿由后端持行锁返回 409。 */
export function deleteProtocolConfiguration(id: string) {
  return requestApi<null>({ method: 'delete', url: `${path}/${encoded(id)}` })
}

export function inspectProtocolSample(samplePayload: string) {
  return requestApi<InspectResult>({ method: 'post', url: `${path}/inspect`, data: { samplePayload } })
}

export function previewProtocolConfiguration(configuration: ProtocolConfiguration, samplePayload: string, receivedTime: number) {
  return requestApi<ProtocolPreview>({ method: 'post', url: `${path}/preview`, data: { configuration, samplePayload, receivedTime } })
}

const deploymentPath = '/v1/protocol-deployments'

/** 草稿只读发布差异预览：不写库、不创建冻结版本，未冻结项以 DRAFT_PREVIEW 占位。 */
export function previewDraftProtocolPublication(data: { draftId: string; revision: number; targetId: string; expectedSequence: number }) {
  return requestApi<ProtocolPublicationPreview>({ method: 'post', url: `${deploymentPath}/draft-preview`, data })
}

/** 同事务完成草稿冻结、增量合并校验并直接提交至 PENDING_REVIEW 独立审批。 */
export function requestDraftProtocolPublication(data: {
  draftId: string
  revision: number
  targetId: string
  expectedSequence: number
  idempotencyKey: string
}) {
  return requestApi<SensitiveChange>({ method: 'post', url: `${deploymentPath}/draft-publish-requests`, data })
}

export function previewProtocolPublication(data: { targetId: string; expectedSequence: number; versionIds: string[] }) {
  return requestApi<ProtocolPublicationPreview>({ method: 'post', url: `${deploymentPath}/preview`, data })
}

export function previewProtocolRollback(data: { targetId: string; expectedSequence: number; historicalSequence: number }) {
  return requestApi<ProtocolPublicationPreview>({ method: 'post', url: `${deploymentPath}/rollback-preview`, data })
}

export function getProtocolDeploymentDetail(targetId: string, sequence: number) {
  return requestApi<ProtocolDeploymentDetail>({ method: 'get', url: `${deploymentPath}/targets/${encoded(targetId)}/history/${sequence}` })
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

/** 回退审批仍保持后端返回 DRAFT，由公共审批控件二次提交与审核。 */
export function requestProtocolRollback(data: { targetId: string; historicalSequence: number; expectedSequence: number; idempotencyKey: string }) {
  return requestApi<SensitiveChange>({ method: 'post', url: `${deploymentPath}/rollback-requests`, data })
}

export function importProtocolVersions(data: { snapshotJson: string; archiveJson: string | null; productBindings: Record<string, string> }) {
  return requestApi<ProtocolFrozenVersion[]>({ method: 'post', url: `${deploymentPath}/import`, data })
}
