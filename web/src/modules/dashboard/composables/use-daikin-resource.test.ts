import { effectScope } from 'vue'
import { describe, expect, it } from 'vitest'
import { useDaikinResource } from './use-daikin-resource'

describe('manufacturer read resource', () => {
  it('rejects previous building responses and clears data on a failed replacement', async () => {
    const scope = effectScope()
    const resource = scope.run(() => useDaikinResource<number>())!
    let resolve!: (value: number) => void
    const previous = resource.run(() => new Promise<number>(done => { resolve = done }))
    await resource.run(async () => 0)
    resolve(42); await previous
    expect(resource.data.value).toBe(0)
    await resource.run(async () => { throw new Error('private upstream detail') })
    expect(resource.data.value).toBeNull()
    expect(resource.error.value).not.toContain('private upstream detail')
    scope.stop()
  })
  it('does not populate after unmount', async () => {
    const scope = effectScope()
    const resource = scope.run(() => useDaikinResource<number>())!
    let resolve!: (value: number) => void
    const loading = resource.run(() => new Promise<number>(done => { resolve = done }))
    scope.stop(); resolve(5); await loading
    expect(resource.data.value).toBeNull()
    expect(resource.loading.value).toBe(false)
  })
})
