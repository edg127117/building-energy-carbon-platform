import { describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { createPinia } from 'pinia'
import { mount, flushPromises } from '@vue/test-utils'
import { createPlatformRouter } from './index'
import App from '@/app/App.vue'
import { useShellStore } from '@/app/providers/shell-store'

describe('office and monitor composition', () => {
  it('keeps office content alive after a lazy route failure and recovers', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const pinia = createPinia()
    const router = createPlatformRouter(createMemoryHistory())
    await router.push('/office')
    const wrapper = mount(App, { global: { plugins: [pinia, router] } })
    router.addRoute({ path: '/test-unavailable', component: () => Promise.reject(new Error('internal chunk path')) })
    await expect(router.push('/test-unavailable')).rejects.toThrow()
    await flushPromises()
    expect(useShellStore(pinia).navigationFailed).toBe(true)
    expect(wrapper.text()).toContain('页面加载失败')
    expect(wrapper.text()).not.toContain('internal chunk path')
    expect(wrapper.find('[data-page-mode="office"]').exists()).toBe(true)
    await router.push('/office/dashboard')
    await flushPromises()
    expect(useShellStore(pinia).navigationFailed).toBe(false)
    expect(wrapper.text()).toContain('核心看板')
    wrapper.unmount()
    warn.mockRestore()
  })
  it('resolves all screen addresses to the shared monitor parent and loads pages lazily', () => {
    const router = createPlatformRouter(createMemoryHistory())
    for (const path of ['monitoring', 'trend', 'situation', 'status', 'analysis']) {
      const route = router.resolve('/monitor/' + path)
      expect(route.matched[0].path).toBe('/monitor')
      expect(route.meta.mode).toBe('monitor')
      expect(typeof route.matched.at(-1).components.default).toBe('function')
    }
    expect(router.resolve('/monitor/unknown').matched.at(-1).props.default).toEqual({ state: 'not-found' })
  })
})
