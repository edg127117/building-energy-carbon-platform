import { requestApi } from '@/infrastructure/http/public'
import type {
  InspectResult,
  ProtocolConfiguration,
  ProtocolConfigurationDetail,
  ProtocolConfigurationPage,
  ProtocolPreview,
} from '../models/protocol-configuration'

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
