import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { jointFlowConfigurationApi } from './joint-flow-configurations'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

beforeEach(() => {
  vi.mocked(requestApi).mockReset()
  vi.mocked(requestApi).mockResolvedValue({} as never)
})

describe('联合流展示配置接口契约', () => {
  it('按建筑隔离版本、审核与当前选用配置，并编码路径标识', async () => {
    await jointFlowConfigurationApi.list('B/01')
    await jointFlowConfigurationApi.get('B/01', 'V/1')
    await jointFlowConfigurationApi.create('B/01', { reason: '建立首版', copiedFromVersionId: 'COPY/1' })
    await jointFlowConfigurationApi.save('B/01', 'V/1', { expectedRevision: 2, configuration: {} as never, reason: '修正维度' })
    await jointFlowConfigurationApi.submit('B/01', 'V/1', { expectedRevision: 3, reason: '提交核验' })
    await jointFlowConfigurationApi.approve('B/01', 'V/1', { expectedRevision: 4, reason: '独立审核通过' })
    await jointFlowConfigurationApi.reject('B/01', 'V/1', { expectedRevision: 5, reason: '退回补充证据' })
    await jointFlowConfigurationApi.getSelection('B/01')
    await jointFlowConfigurationApi.updateSelection('B/01', { expectedRevision: 6, versionId: 'V/0', reason: '回切已批准版本' })

    expect(requestApi).toHaveBeenNthCalledWith(1, expect.objectContaining({ method: 'get', url: '/v1/carbon-management/joint-flow/buildings/B%2F01/configurations' }))
    expect(requestApi).toHaveBeenNthCalledWith(2, expect.objectContaining({ method: 'get', url: '/v1/carbon-management/joint-flow/buildings/B%2F01/configurations/V%2F1' }))
    expect(requestApi).toHaveBeenNthCalledWith(3, expect.objectContaining({ method: 'post', url: '/v1/carbon-management/joint-flow/buildings/B%2F01/configurations', data: { reason: '建立首版', copiedFromVersionId: 'COPY/1' } }))
    expect(requestApi).toHaveBeenNthCalledWith(4, expect.objectContaining({ method: 'put', url: '/v1/carbon-management/joint-flow/buildings/B%2F01/configurations/V%2F1' }))
    expect(requestApi).toHaveBeenNthCalledWith(5, expect.objectContaining({ method: 'post', url: '/v1/carbon-management/joint-flow/buildings/B%2F01/configurations/V%2F1/submit' }))
    expect(requestApi).toHaveBeenNthCalledWith(6, expect.objectContaining({ method: 'post', url: '/v1/carbon-management/joint-flow/buildings/B%2F01/configurations/V%2F1/approve' }))
    expect(requestApi).toHaveBeenNthCalledWith(7, expect.objectContaining({ method: 'post', url: '/v1/carbon-management/joint-flow/buildings/B%2F01/configurations/V%2F1/reject' }))
    expect(requestApi).toHaveBeenNthCalledWith(8, expect.objectContaining({ method: 'get', url: '/v1/carbon-management/joint-flow/buildings/B%2F01/configurations/selection' }))
    expect(requestApi).toHaveBeenNthCalledWith(9, expect.objectContaining({ method: 'put', url: '/v1/carbon-management/joint-flow/buildings/B%2F01/configurations/selection', data: { expectedRevision: 6, versionId: 'V/0', reason: '回切已批准版本' } }))
  })
})
