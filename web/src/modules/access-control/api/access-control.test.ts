import { afterEach, describe, expect, it, vi } from 'vitest'
import { newIdempotencyKey } from './access-control'

describe('幂等键生成', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('浏览器不支持 randomUUID 时仍生成可用的幂等键', () => {
    vi.stubGlobal('crypto', {})

    expect(newIdempotencyKey()).toMatch(/^web-\d+-[a-z0-9]+$/)
  })
})
