import { describe, expect, it, vi } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { configureHttpAuthentication, createHttpClient, requestApi, requestFile, TransportError } from './client'
describe('transport boundary', () => {
  it('下载走当前认证并拒绝把JSON业务错误保存成文件', async () => {
    const onUnauthorized = vi.fn()
    configureHttpAuthentication({ getToken: () => 'download-token', onUnauthorized })
    const blob = Object.assign(new Blob([], { type: 'application/json' }), { text: async () => '{"code":403,"errorCode":"RELATION_FORBIDDEN"}' })
    await expect(requestFile({ url: '/test-download', adapter: async config => {
      expect(config.headers.get('Authorization')).toBe('Bearer download-token')
      expect(config.responseType).toBe('blob')
      return { data: blob, status: 200, statusText: 'OK', headers: new AxiosHeaders(), config }
    } })).rejects.toMatchObject({ kind: 'forbidden', errorCode: 'RELATION_FORBIDDEN' })
    expect(onUnauthorized).not.toHaveBeenCalled()
  })
  it('登录态服务故障时保留会话并返回可识别的服务错误', async () => {
    const onUnauthorized = vi.fn()
    configureHttpAuthentication({ getToken: () => 'current', onUnauthorized })
    await expect(requestApi({ url: '/only-a-test', adapter: async config => {
      throw new AxiosError('unavailable', 'ERR_BAD_RESPONSE', config, undefined, {
        status: 503, data: { errorCode: 'AUTH_STATE_UNAVAILABLE' },
        statusText: '', headers: new AxiosHeaders(), config,
      })
    } })).rejects.toMatchObject({ kind: 'request', status: 503, errorCode: 'AUTH_STATE_UNAVAILABLE' })
    expect(onUnauthorized).not.toHaveBeenCalled()
  })
  it('保留稳定错误码但不透传服务端异常原文', async () => {
    const client = createHttpClient('/test', () => null)
    await expect(client.request({ url: '/only-a-test', adapter: async config => {
      throw new AxiosError('secret', 'ERR_BAD_RESPONSE', config, undefined, {
        status: 400, data: { errorCode: 'PROTOCOL_SNAPSHOT_AMBIGUOUS_PROFILE_SELECTOR', msg: 'SQL secret' },
        statusText: '', headers: new AxiosHeaders(), config,
      })
    } })).rejects.toMatchObject({ message: 'request', errorCode: 'PROTOCOL_SNAPSHOT_AMBIGUOUS_PROFILE_SELECTOR' })
  })
  it('does not expire a newer session on a late unauthorized reply', async () => {
    let token = 'old'
    const onUnauthorized = vi.fn()
    configureHttpAuthentication({ getToken: () => token, onUnauthorized })
    await expect(requestApi({ url: '/only-a-test', adapter: async config => {
      token = 'new'
      return { data: { code: 401, data: null }, status: 200, statusText: 'OK', headers: new AxiosHeaders(), config }
    } })).rejects.toMatchObject({ kind: 'unauthorized' })
    expect(onUnauthorized).not.toHaveBeenCalled()
  })
  it('injects current credentials and returns only response data', async () => {
    let token = 'first'
    const client = createHttpClient('/test', () => token)
    const adapter = async config => {
      expect(config.headers.get('Authorization')).toBe('Bearer second')
      return { data: { value: 1 }, status: 200, statusText: 'OK', headers: new AxiosHeaders(), config }
    }
    token = 'second'
    expect(await client.request({ url: '/only-a-test', adapter })).toEqual({ value: 1 })
  })
  it.each([[401, 'unauthorized'], [403, 'forbidden'], [500, 'request']] as const)('sanitizes %s without guessing business errors', async (status, kind) => {
    const client = createHttpClient('/test', () => null)
    const failure = client.request({ url: '/only-a-test', adapter: async config => {
      throw new AxiosError('SQL internal secret', 'ERR_BAD_RESPONSE', config, undefined, { status, data: { msg: 'secret' }, statusText: '', headers: new AxiosHeaders(), config })
    } })
    await expect(failure).rejects.toMatchObject({ kind, status, message: kind })
    await expect(failure).rejects.toBeInstanceOf(TransportError)
  })
  it('unwraps the platform envelope and clears authentication on a business 401', async () => {
    const onUnauthorized = vi.fn()
    configureHttpAuthentication({ getToken: () => 'current', onUnauthorized })
    const adapter = async config => ({
      data: { success: false, code: 401, msg: 'internal detail', data: null },
      status: 200,
      statusText: 'OK',
      headers: new AxiosHeaders(),
      config,
    })

    await expect(requestApi({ url: '/only-a-test', adapter })).rejects.toMatchObject({
      kind: 'unauthorized',
      status: 401,
      message: 'unauthorized',
    })
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })
})
