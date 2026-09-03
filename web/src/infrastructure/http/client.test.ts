import { describe, expect, it } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { createHttpClient, TransportError } from './client'
describe('transport boundary', () => {
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
})
