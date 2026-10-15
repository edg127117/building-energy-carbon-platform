import { defineComponent, h, nextTick, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ startFastWatch: vi.fn(), stopFastWatch: vi.fn() }))
const newWatchId = vi.hoisted(() => vi.fn())

vi.mock('../api/daikin', () => ({
  daikinApi: api,
  newDaikinFastWatchId: newWatchId,
}))

import { useDaikinFastWatch } from './use-daikin-fast-watch'

const lease = (enabled = true) => ({ enabled, intervalMillis: 5_000, expiresAt: 1_700_000_015_000 })
let originalHidden: PropertyDescriptor | undefined

function setDocumentHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
}

function mountFastWatch() {
  const equipmentId = ref('EQ-1')
  const visible = ref(true)
  const refresh = vi.fn().mockResolvedValue(undefined)
  const reset = vi.fn()
  const Harness = defineComponent({
    setup() {
      const fastWatch = useDaikinFastWatch({ equipmentId, visible, refresh, reset })
      return () => h('div', fastWatch.error.value ?? '')
    },
  })
  return { equipmentId, visible, refresh, reset, wrapper: mount(Harness) }
}

describe('Daikin detail fast watch', () => {
  beforeEach(() => {
    originalHidden = Object.getOwnPropertyDescriptor(document, 'hidden')
    setDocumentHidden(false)
    vi.useFakeTimers()
    vi.clearAllMocks()
    newWatchId.mockReturnValue('watch-test-0001')
    api.startFastWatch.mockResolvedValue(lease())
    api.stopFastWatch.mockResolvedValue(true)
  })

  afterEach(() => {
    vi.clearAllTimers()
    vi.useRealTimers()
    if (originalHidden) Object.defineProperty(document, 'hidden', originalHidden)
    else delete (document as unknown as { hidden?: boolean }).hidden
  })

  it('waits for the lease before reading, then renews every five seconds without overlap', async () => {
    let resolveLease!: (value: ReturnType<typeof lease>) => void
    api.startFastWatch
      .mockImplementationOnce(() => new Promise<ReturnType<typeof lease>>(resolve => { resolveLease = resolve }))
      .mockResolvedValue(lease())
    const { refresh, wrapper } = mountFastWatch()

    expect(api.startFastWatch).toHaveBeenCalledWith('EQ-1', 'watch-test-0001')
    await vi.advanceTimersByTimeAsync(10_000)
    expect(api.startFastWatch).toHaveBeenCalledTimes(1)
    expect(refresh).not.toHaveBeenCalled()

    resolveLease(lease())
    await flushPromises()
    expect(refresh).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()
    expect(api.startFastWatch).toHaveBeenCalledTimes(2)
    expect(refresh).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('falls back to existing minute refreshes when the server disables fast watch', async () => {
    api.startFastWatch.mockResolvedValue(lease(false))
    const { refresh, wrapper } = mountFastWatch()

    await flushPromises()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(api.startFastWatch).toHaveBeenCalledTimes(1)
    expect(refresh).not.toHaveBeenCalled()
    expect(api.stopFastWatch).toHaveBeenCalledWith('EQ-1', 'watch-test-0001')
    wrapper.unmount()
  })

  it('bounds refresh callback failures even when lease renewal succeeds', async () => {
    const { refresh, wrapper } = mountFastWatch()
    refresh.mockRejectedValue(new Error('read failed'))
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1_000)
    await vi.advanceTimersByTimeAsync(5_000)
    await vi.advanceTimersByTimeAsync(15_000)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(api.startFastWatch).toHaveBeenCalledTimes(4)
    expect(refresh).toHaveBeenCalledTimes(4)
    expect(wrapper.text()).toContain('请求失败')
    wrapper.unmount()
  })

  it('stops the old lease on a device switch and ignores its late response', async () => {
    let resolveOldLease!: (value: ReturnType<typeof lease>) => void
    newWatchId.mockReturnValueOnce('watch-old-0001').mockReturnValueOnce('watch-new-0002')
    api.startFastWatch
      .mockImplementationOnce(() => new Promise<ReturnType<typeof lease>>(resolve => { resolveOldLease = resolve }))
      .mockResolvedValueOnce(lease())
    const { equipmentId, refresh, reset, wrapper } = mountFastWatch()

    equipmentId.value = 'EQ-2'
    await nextTick()
    await flushPromises()
    expect(reset).toHaveBeenCalledTimes(1)
    expect(api.stopFastWatch).toHaveBeenCalledWith('EQ-1', 'watch-old-0001')
    expect(api.startFastWatch).toHaveBeenLastCalledWith('EQ-2', 'watch-new-0002')
    expect(refresh).toHaveBeenCalledWith('EQ-2')

    resolveOldLease(lease())
    await flushPromises()
    expect(refresh).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('deletes on hidden, resumes immediately when visible, and bounds failed retries', async () => {
    const { refresh, wrapper } = mountFastWatch()
    await flushPromises()
    setDocumentHidden(true)
    document.dispatchEvent(new Event('visibilitychange'))
    expect(api.stopFastWatch).toHaveBeenCalledWith('EQ-1', 'watch-test-0001')

    newWatchId.mockReturnValueOnce('watch-test-0002')
    setDocumentHidden(false)
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(api.startFastWatch).toHaveBeenCalledTimes(2)
    expect(refresh).toHaveBeenCalledTimes(2)
    wrapper.unmount()

    api.startFastWatch.mockRejectedValue(new Error('offline'))
    const retrying = mountFastWatch()
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1_000)
    await vi.advanceTimersByTimeAsync(5_000)
    await vi.advanceTimersByTimeAsync(15_000)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(api.startFastWatch).toHaveBeenCalledTimes(6)
    retrying.wrapper.unmount()
  })
})
