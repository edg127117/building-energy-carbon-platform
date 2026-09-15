import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import {
  createProtocolConfiguration,
  freezeProtocolVersion,
  importProtocolVersions,
  inspectProtocolSample,
  listProtocolConfigurations,
  listProtocolDeploymentHistory,
  listProtocolPublicationTargets,
  listProtocolVersions,
  previewProtocolConfiguration,
  registerProtocolPublicationTarget,
  requestProtocolPublication,
  requestProtocolRollback,
  updateProtocolConfiguration,
} from './protocol-configuration'
import { emptyProtocolConfiguration } from '../models/protocol-configuration'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('协议配置接口契约', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.mocked(requestApi).mockResolvedValue({} as never) })

  it('使用 items 分页契约并编码草稿标识', async () => {
    await listProtocolConfigurations({ page: 2, size: 20 })
    await updateProtocolConfiguration('draft/1', 3, emptyProtocolConfiguration())
    expect(requestApi).toHaveBeenNthCalledWith(1, { method: 'get', url: '/v1/protocol-configurations', params: { page: 2, size: 20 } })
    expect(requestApi).toHaveBeenNthCalledWith(2, { method: 'put', url: '/v1/protocol-configurations/draft%2F1', data: { revision: 3, configuration: emptyProtocolConfiguration() } })
  })

  it('保存体不包含样例，样例只进入检查和预览请求', async () => {
    const configuration = { ...emptyProtocolConfiguration(), name: '电表协议' }
    await createProtocolConfiguration(configuration)
    await inspectProtocolSample('{"sn":"A"}')
    await previewProtocolConfiguration(configuration, '{"sn":"A"}', 1234)
    expect(requestApi).toHaveBeenNthCalledWith(1, { method: 'post', url: '/v1/protocol-configurations', data: configuration })
    expect(requestApi).toHaveBeenNthCalledWith(2, { method: 'post', url: '/v1/protocol-configurations/inspect', data: { samplePayload: '{"sn":"A"}' } })
    expect(requestApi).toHaveBeenNthCalledWith(3, { method: 'post', url: '/v1/protocol-configurations/preview', data: { configuration, samplePayload: '{"sn":"A"}', receivedTime: 1234 } })
  })

  it('严格使用发布、回退和目标状态契约', async () => {
    await listProtocolPublicationTargets()
    await registerProtocolPublicationTarget({ name: 'adapter-a', outputVersion: 'V2', allowedTopics: ['raw/a'] })
    await freezeProtocolVersion('draft/1', 4)
    await listProtocolVersions()
    await listProtocolDeploymentHistory('target/1')
    await requestProtocolPublication({ targetId: 'T1', expectedSequence: 2, versionIds: ['V1', 'V2'], idempotencyKey: 'publish-key' })
    await requestProtocolRollback({ targetId: 'T1', historicalSequence: 1, expectedSequence: 2, idempotencyKey: 'rollback-key' })
    expect(vi.mocked(requestApi).mock.calls).toEqual([
      [{ method: 'get', url: '/v1/protocol-deployments/targets' }],
      [{ method: 'post', url: '/v1/protocol-deployments/targets', data: { name: 'adapter-a', outputVersion: 'V2', allowedTopics: ['raw/a'] } }],
      [{ method: 'post', url: '/v1/protocol-deployments/versions/draft%2F1', data: { revision: 4 } }],
      [{ method: 'get', url: '/v1/protocol-deployments/versions' }],
      [{ method: 'get', url: '/v1/protocol-deployments/targets/target%2F1/history' }],
      [{ method: 'post', url: '/v1/protocol-deployments/requests', data: { targetId: 'T1', expectedSequence: 2, versionIds: ['V1', 'V2'], idempotencyKey: 'publish-key' } }],
      [{ method: 'post', url: '/v1/protocol-deployments/rollback-requests', data: { targetId: 'T1', historicalSequence: 1, expectedSequence: 2, idempotencyKey: 'rollback-key' } }],
    ])
  })

  it('迁移接口保留原始配置 JSON 并提交完整产品绑定', async () => {
    await importProtocolVersions({ snapshotJson: '{"schemaVersion":1}', archiveJson: null, productBindings: { P1: 'PRODUCT-1' } })
    expect(requestApi).toHaveBeenCalledWith({
      method: 'post', url: '/v1/protocol-deployments/import',
      data: { snapshotJson: '{"schemaVersion":1}', archiveJson: null, productBindings: { P1: 'PRODUCT-1' } },
    })
  })
})
