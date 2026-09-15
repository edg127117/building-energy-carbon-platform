import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import {
  createProtocolConfiguration,
  inspectProtocolSample,
  listProtocolConfigurations,
  previewProtocolConfiguration,
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
})
