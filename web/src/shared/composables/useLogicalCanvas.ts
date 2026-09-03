import {
  computed,
  onBeforeUnmount,
  onMounted,
  ref,
  type CSSProperties,
} from 'vue'

const LOGICAL_CANVAS_WIDTH = 1920
const LOGICAL_CANVAS_HEIGHT = 1080

/**
 * 大屏内容使用固定的设计坐标而非视觉 Token：位置和交互热点必须等比缩放，
 * 颜色、字体等视觉属性仍由页面的 CSS Token 管理。
 */
export function useLogicalCanvas() {
  const viewportRef = ref<HTMLElement | null>(null)
  const scale = ref(1)
  const left = ref(0)
  const top = ref(0)
  let resizeObserver: ResizeObserver | null = null

  const canvasStyle = computed<CSSProperties>(() => ({
    position: 'absolute',
    width: `${LOGICAL_CANVAS_WIDTH}px`,
    height: `${LOGICAL_CANVAS_HEIGHT}px`,
    transformOrigin: 'top left',
    transform: `scale(${scale.value})`,
    left: `${left.value}px`,
    top: `${top.value}px`,
  }))

  function resetViewport(): void {
    scale.value = 1
    left.value = 0
    top.value = 0
  }

  function updateViewport(width: number, height: number): void {
    if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
      resetViewport()
      return
    }

    const nextScale = Math.min(
      width / LOGICAL_CANVAS_WIDTH,
      height / LOGICAL_CANVAS_HEIGHT,
    )
    if (!Number.isFinite(nextScale) || nextScale <= 0) {
      resetViewport()
      return
    }

    scale.value = nextScale
    left.value = (width - LOGICAL_CANVAS_WIDTH * nextScale) / 2
    top.value = (height - LOGICAL_CANVAS_HEIGHT * nextScale) / 2
  }

  function measureViewport(viewport: HTMLElement): void {
    updateViewport(viewport.clientWidth, viewport.clientHeight)
  }

  onMounted(() => {
    const viewport = viewportRef.value
    if (!viewport) return

    measureViewport(viewport)
    if (typeof ResizeObserver === 'undefined') return

    resizeObserver = new ResizeObserver((entries) => {
      const entry = entries.find((item) => item.target === viewport)
      if (!entry) return
      updateViewport(entry.contentRect.width, entry.contentRect.height)
    })
    resizeObserver.observe(viewport)
  })

  onBeforeUnmount(() => {
    resizeObserver?.disconnect()
    resizeObserver = null
  })

  return {
    viewportRef,
    canvasStyle,
    scale,
  }
}
