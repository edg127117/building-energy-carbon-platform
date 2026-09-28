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
  it('preserves existing data without flashing loading state during silent background refresh', async () => {
    const scope = effectScope()
    const resource = scope.run(() => useDaikinResource<number>())!
    await resource.run(async () => 10)
    expect(resource.data.value).toBe(10)
    let resolve!: (value: number) => void
    const refreshing = resource.run(() => new Promise<number>(done => { resolve = done }), true)
    expect(resource.data.value).toBe(10)
    expect(resource.loading.value).toBe(false)
    resolve(20)
    await refreshing
    expect(resource.data.value).toBe(20)
    scope.stop()
  })
})
