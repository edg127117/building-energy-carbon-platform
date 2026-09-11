import { describe, expect, it, vi } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { configureHttpAuthentication, createHttpClient, requestApi, TransportError } from './client'
describe('transport boundary', () => {
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
