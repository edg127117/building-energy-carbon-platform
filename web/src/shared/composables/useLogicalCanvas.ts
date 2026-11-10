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
 * 以 1920×1080 为设计基准，文字和交互保持统一缩放比例。
 * 非 16:9 窗口扩展逻辑画布承接额外空间，避免留边、拉伸或裁切整屏。
 */
export function useLogicalCanvas() {
  const viewportRef = ref<HTMLElement | null>(null)
  const scale = ref(1)
  const logicalWidth = ref(LOGICAL_CANVAS_WIDTH)
  const logicalHeight = ref(LOGICAL_CANVAS_HEIGHT)
  let resizeObserver: ResizeObserver | null = null

  const canvasStyle = computed<CSSProperties>(() => ({
    position: 'absolute',
    width: `${logicalWidth.value}px`,
    height: `${logicalHeight.value}px`,
    transformOrigin: 'top left',
    transform: `scale(${scale.value})`,
    left: '0px',
    top: '0px',
  }))

  function resetViewport(): void {
    scale.value = 1
    logicalWidth.value = LOGICAL_CANVAS_WIDTH
    logicalHeight.value = LOGICAL_CANVAS_HEIGHT
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
    logicalWidth.value = width / nextScale
    logicalHeight.value = height / nextScale
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
