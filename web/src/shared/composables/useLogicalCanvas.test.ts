import { defineComponent, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLogicalCanvas } from './useLogicalCanvas'

const LogicalCanvasHarness = defineComponent({
  setup() {
    return useLogicalCanvas()
  },
  template: `
    <div ref="viewportRef">
      <div data-test="canvas" :style="canvasStyle" />
    </div>
  `,
})

describe('useLogicalCanvas', () => {
  let resizeCallback: ResizeObserverCallback
  const observe = vi.fn()
  const disconnect = vi.fn()

  beforeEach(() => {
    observe.mockReset()
    disconnect.mockReset()
    class ResizeObserverMock {
      constructor(callback: ResizeObserverCallback) {
        resizeCallback = callback
      }

      observe = observe
      disconnect = disconnect
      unobserve = vi.fn()
    }
    vi.stubGlobal('ResizeObserver', ResizeObserverMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  async function resize(
    wrapper: ReturnType<typeof mount>,
    width: number,
    height: number,
  ): Promise<void> {
    resizeCallback([{
      target: wrapper.element,
      contentRect: { width, height },
    } as ResizeObserverEntry], {} as ResizeObserver)
    await nextTick()
  }

  it('uses the logical 16:9 size without stretching', async () => {
    const wrapper = mount(LogicalCanvasHarness)

    await resize(wrapper, 960, 540)

    expect(wrapper.vm.scale).toBe(0.5)
    const canvas = wrapper.get('[data-test="canvas"]').element as HTMLElement
    expect(canvas.style).toMatchObject({
      position: 'absolute',
      width: '1920px',
      height: '1080px',
      transformOrigin: 'top left',
      transform: 'scale(0.5)',
      left: '0px',
      top: '0px',
    })
  })

  it('letterboxes non-16:9 containers while preserving one scale', async () => {
    const wrapper = mount(LogicalCanvasHarness)

    await resize(wrapper, 1000, 1000)
    expect(wrapper.vm.scale).toBeCloseTo(1000 / 1920)
    let canvas = wrapper.get('[data-test="canvas"]').element as HTMLElement
    expect(Number.parseFloat(canvas.style.left)).toBeCloseTo(0)
    expect(Number.parseFloat(canvas.style.top)).toBeCloseTo(218.75)

    await resize(wrapper, 1600, 600)
    expect(wrapper.vm.scale).toBeCloseTo(600 / 1080)
    canvas = wrapper.get('[data-test="canvas"]').element as HTMLElement
    expect(Number.parseFloat(canvas.style.left)).toBeCloseTo(266.6666666667)
    expect(Number.parseFloat(canvas.style.top)).toBeCloseTo(0)
  })

  it('keeps a safe finite default for zero-sized containers and disconnects', async () => {
    const wrapper = mount(LogicalCanvasHarness)

    await resize(wrapper, 0, 0)

    expect(wrapper.vm.scale).toBe(1)
    const canvas = wrapper.get('[data-test="canvas"]').element as HTMLElement
    expect(canvas.style.transform).toBe('scale(1)')
    expect(canvas.style.left).toBe('0px')
    expect(canvas.style.top).toBe('0px')
    expect(canvas.getAttribute('style')).not.toContain('Infinity')

    wrapper.unmount()
    expect(observe).toHaveBeenCalledWith(wrapper.element)
    expect(disconnect).toHaveBeenCalledTimes(1)
  })
})
