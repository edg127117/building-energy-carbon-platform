import { afterEach, describe, expect, it, vi } from 'vitest'
import { createIdempotencyKey } from './idempotency-key'

const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('HTTP compatible idempotency keys', () => {
  it('uses the native UUID method with its crypto receiver when available', () => {
    const source = { randomUUID: vi.fn(function (this: unknown) { expect(this).toBe(source); return 'native-uuid' }), getRandomValues: vi.fn() }
    vi.stubGlobal('crypto', source)
    expect(createIdempotencyKey()).toBe('native-uuid')
    expect(source.getRandomValues).not.toHaveBeenCalled()
  })

  it('uses random bytes without randomUUID and keeps UUID version and variant bits', () => {
    let sequence = 0
    const source = { getRandomValues: vi.fn(function (this: unknown, bytes: Uint8Array) { expect(this).toBe(source); bytes.fill(++sequence); return bytes }) }
    vi.stubGlobal('crypto', source)
    vi.spyOn(Date, 'now').mockReturnValue(1)
    const keys = Array.from({ length: 100 }, createIdempotencyKey)
    expect(keys.every(key => uuidV4.test(key))).toBe(true)
    expect(new Set(keys).size).toBe(100)
    expect(source.getRandomValues).toHaveBeenCalledTimes(100)
  })

  it.each([undefined, {}])('does not reuse keys without usable crypto even if the clock and random value repeat', crypto => {
    vi.stubGlobal('crypto', crypto)
    vi.spyOn(Date, 'now').mockReturnValue(1)
    vi.spyOn(Math, 'random').mockReturnValue(0.5)
    const keys = Array.from({ length: 100 }, createIdempotencyKey)
    expect(new Set(keys).size).toBe(100)
    expect(keys.every(key => /^[a-z0-9-]+$/.test(key) && key.length <= 128)).toBe(true)
  })
})
