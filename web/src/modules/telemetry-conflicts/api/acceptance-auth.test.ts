import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosHeaders, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import { configureHttpAuthentication } from '@/infrastructure/http/public'
import { telemetryConflictApi } from './telemetry-conflicts'

const transport = vi.hoisted(() => ({ adapter: vi.fn() }))
// 仅替换网络适配器；请求仍经过应用真实认证桥接、Bearer 注入与错误处理。
vi.mock('@/infrastructure/http/public', async importOriginal => {
  const actual = await importOriginal<typeof import('@/infrastructure/http/public')>()
  return { ...actual, requestApi: (config: AxiosRequestConfig) => actual.requestApi({ ...config, adapter: transport.adapter }) }
})

describe('D01 审核和重试使用正常会话鉴权', () => {
  beforeEach(() => vi.resetAllMocks())

  it('每次从当前登录会话获取令牌，原样传递建筑、任务及更正修订', async () => {
    let token = 'synthetic-reviewer'
    configureHttpAuthentication({ getToken: () => token, onUnauthorized: vi.fn() })
    const calls: InternalAxiosRequestConfig[] = []
    transport.adapter.mockImplementation(async (config: InternalAxiosRequestConfig) => {
      calls.push(config)
      expect(config.headers.get('Authorization')).toBe(`Bearer ${token}`)
      return { data: { code: 200, data: [] }, status: 200, statusText: 'OK', headers: new AxiosHeaders(), config }
    })
    await telemetryConflictApi.correctionVersions('B1', 'P1')
    await telemetryConflictApi.approveCorrection('C/1', { expectedRevision: 3, reviewComment: '复核' })
    token = 'synthetic-owner'
    await telemetryConflictApi.recovery('B1')
    await telemetryConflictApi.retry({ kind: 'MESSAGE_CONFLICT', id: 'M/1' })
    expect(calls.map(c => [c.method, c.url, c.params, c.data ? JSON.parse(c.data) : undefined])).toEqual([
      ['get', '/v1/energy-aggregation/correction-versions', { buildingId: 'B1', pointId: 'P1' }, undefined],
      ['post', '/v1/energy-aggregation/correction-versions/C%2F1/approve', undefined, { expectedRevision: 3, reviewComment: '复核' }],
      ['get', '/v1/telemetry-conflicts/recovery', { buildingId: 'B1' }, undefined],
      ['post', '/v1/telemetry-conflicts/recovery/retry', undefined, { kind: 'MESSAGE_CONFLICT', id: 'M/1' }],
    ])
  })

  it.each(['approve', 'retry'])('%s 令牌失效触发现有会话处理，不降级匿名或绕过权限', async action => {
    const onUnauthorized = vi.fn()
    configureHttpAuthentication({ getToken: () => 'expired-test-token', onUnauthorized })
    transport.adapter.mockImplementation(async (config: InternalAxiosRequestConfig) => ({
      data: { code: 401, data: null }, status: 200, statusText: 'OK', headers: new AxiosHeaders(), config,
    }))
    await expect(action === 'approve'
      ? telemetryConflictApi.approveCorrection('C1', { expectedRevision: 0, reviewComment: '审核' })
      : telemetryConflictApi.retry({ kind: 'RAW', id: 'P1@1000' })).rejects.toMatchObject({ kind: 'unauthorized' })
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
    expect(transport.adapter).toHaveBeenCalledTimes(1)
  })
})
