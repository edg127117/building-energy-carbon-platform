import { mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi, afterEach } from 'vitest'
import { useShellRuntime } from './runtime'
import { useShellStore } from './shell-store'
import { useFullscreen } from '../layouts/monitor/useFullscreen'

afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers() })
const mountProbe = (setup: () => void) => mount(defineComponent({ setup() { setup(); return () => null } }))
describe('shell lifecycle', () => {
  it('removes clock and network listeners when leaving a shell', () => {
    vi.useFakeTimers()
    setActivePinia(createPinia())
    const remove = vi.spyOn(window, 'removeEventListener')
    const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
    const wrapper = mountProbe(() => { useShellRuntime() })
    expect(vi.getTimerCount()).toBe(1)
    online.mockReturnValue(false)
    window.dispatchEvent(new Event('offline'))
    expect(useShellStore().offline).toBe(true)
    wrapper.unmount()
    expect(vi.getTimerCount()).toBe(0)
    expect(remove).toHaveBeenCalledWith('online', expect.any(Function))
    expect(remove).toHaveBeenCalledWith('offline', expect.any(Function))
  })
  it('reports fullscreen denial and unregisters the browser event', async () => {
    const remove = vi.spyOn(document, 'removeEventListener')
    let controls: ReturnType<typeof useFullscreen>
    const wrapper = mountProbe(() => { controls = useFullscreen() })
    await controls.toggle()
    expect(controls.failed.value).toBe(true)
    wrapper.unmount()
    expect(remove).toHaveBeenCalledWith('fullscreenchange', expect.any(Function))
  })
})
