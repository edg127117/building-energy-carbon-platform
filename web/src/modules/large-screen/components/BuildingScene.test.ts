import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReferenceScene, ReferenceSceneSnapshot } from '@/generated/scene/reference-engine'
import BuildingScene from './BuildingScene.vue'

const renderer = vi.hoisted(() => ({
  createReferenceScene: vi.fn(),
  referenceViews: [
    { id: 'front', name: '主立面全景', position: [1, 2, 3], target: [0, 0, 0], span: 40 },
    { id: 'terrace', name: '露台侧全景', position: [4, 5, 6], target: [0, 0, 0], span: 41 },
  ],
}))

vi.mock('@/generated/scene/reference-engine', () => renderer)
vi.mock('@/locales', () => ({
  t: (key: string) => ({
    'largeScreen.scene': '试点建筑场景',
    'largeScreen.sceneLoading': '场景加载中',
    'largeScreen.sceneUnavailable': '场景暂不可用',
    'largeScreen.sceneHelp': '请检查浏览器图形能力后重试。',
    'largeScreen.retryScene': '重试场景',
  }[key] ?? key),
}))

class TestIntersectionObserver {
  static instances: TestIntersectionObserver[] = []

  private target: Element | null = null
  readonly observe = vi.fn((target: Element) => { this.target = target; this.emit(true) })
  readonly disconnect = vi.fn()

  constructor(private readonly callback: IntersectionObserverCallback) {
    TestIntersectionObserver.instances.push(this)
  }

  emit(isIntersecting: boolean): void {
    if (!this.target) return
    this.callback([{ target: this.target, isIntersecting } as IntersectionObserverEntry], this as unknown as IntersectionObserver)
  }
}

interface SceneDouble {
  readonly instance: ReferenceScene
  readonly preset: ReturnType<typeof vi.fn<(id: string, immediate?: boolean) => void>>
  readonly dispose: ReturnType<typeof vi.fn<() => void>>
  readonly setActive: ReturnType<typeof vi.fn<(active: boolean) => void>>
  readonly setDemoPlaying: ReturnType<typeof vi.fn<(playing: boolean) => void>>
  readonly setMotion: ReturnType<typeof vi.fn<(motion: boolean) => void>>
  readonly setSystemFloor: ReturnType<typeof vi.fn<(floor: string) => void>>
  readonly selectAsset: ReturnType<typeof vi.fn<(id: string | null) => void>>
  readonly systemSnapshot: ReturnType<typeof vi.fn<() => ReferenceSceneSnapshot>>
}

function sceneSnapshot(): ReferenceSceneSnapshot {
  return {
    topic: 'hvac',
    selected: 'HVAC-DEMO-1',
    selectionRevision: 8,
    clock: 12,
    playing: true,
    blend: 1,
    scenario: 'alert',
    floorFilter: 'F3',
    transparency: { style: 'source-slab-envelope', filledMeshes: 2, materials: [] },
    assetStates: [{ id: 'HVAC-DEMO-1', state: '运行样本' }],
    assets: [{
      id: 'HVAC-DEMO-1',
      label: '典型空调内机（示意）',
      system: 'hvac',
      floor: 'F3',
      center: [1, 2, 3],
      bounds: [[0, 1, 2], [2, 3, 4]],
      floors: [{ name: 'F3', y: 8 }],
    }],
    visuals: [],
    lifts: [],
  }
}

function sceneDouble(snapshot = sceneSnapshot()): SceneDouble {
  const preset = vi.fn<(id: string, immediate?: boolean) => void>()
  const dispose = vi.fn<() => void>()
  const setActive = vi.fn<(active: boolean) => void>()
  const setDemoPlaying = vi.fn<(playing: boolean) => void>()
  const setMotion = vi.fn<(motion: boolean) => void>()
  const setSystemFloor = vi.fn<(floor: string) => void>()
  const selectAsset = vi.fn<(id: string | null) => void>()
  const systemSnapshot = vi.fn<() => ReferenceSceneSnapshot>(() => snapshot)
  const instance: ReferenceScene = {
    stats: { ready: true, active: false, frames: 0, triangles: 0, drawCalls: 0, view: 'front', geometryCount: 0, textureCount: 0, gpu: '', contextLosses: 0, renderMs: [] },
    preset,
    dispose,
    viewState: () => ({ position: [1, 2, 3], target: [0, 0, 0], transition: false, opaque: true }),
    enterSystem: vi.fn<(topic: string | null) => void>(),
    setSystemFloor,
    setScenario: vi.fn<(scenario: string) => void>(),
    selectAsset,
    systemSnapshot,
    hotspots: vi.fn(() => [{ id: 'hvac', name: '空调', description: '示意热点', color: '#008577', x: 12, y: 24, visible: true }]),
    setDemoPlaying,
    inspectView: vi.fn<(position: readonly [number, number, number], target: readonly [number, number, number], span?: number) => void>(),
    setMotion,
    setActive,
    setFree: vi.fn<(free: boolean) => void>(),
    reset: vi.fn<() => void>(),
    getCanvas: vi.fn<() => HTMLCanvasElement>(() => document.createElement('canvas')),
  }
  return { instance, preset, dispose, setActive, setDemoPlaying, setMotion, setSystemFloor, selectAsset, systemSnapshot }
}

let hidden = false
let originalHidden: PropertyDescriptor | undefined

beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  renderer.createReferenceScene.mockReset()
  TestIntersectionObserver.instances = []
  hidden = false
  originalHidden = Object.getOwnPropertyDescriptor(document, 'hidden')
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
  vi.stubGlobal('IntersectionObserver', TestIntersectionObserver)
})

afterEach(() => {
  if (originalHidden) Object.defineProperty(document, 'hidden', originalHidden)
  else Reflect.deleteProperty(document, 'hidden')
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('reference building scene wrapper', () => {
  it('keeps the authored camera framing for initial load and preset changes', async () => {
    const scene = sceneDouble()
    renderer.createReferenceScene.mockResolvedValue(scene.instance)
    const wrapper = mount(BuildingScene)
    await flushPromises()
    expect(scene.instance.inspectView).not.toHaveBeenCalled()
    expect(scene.preset).not.toHaveBeenCalled()
    const exposed = wrapper.vm as unknown as { preset(id: string): void }
    exposed.preset('terrace')
    expect(scene.preset).toHaveBeenLastCalledWith('terrace')
    expect(scene.instance.inspectView).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('publishes only model geometry and disables authored motion by default', async () => {
    const scene = sceneDouble()
    renderer.createReferenceScene.mockResolvedValue(scene.instance)
    const wrapper = mount(BuildingScene)

    await flushPromises()

    expect(scene.setDemoPlaying).toHaveBeenCalledWith(false)
    expect(scene.setMotion).toHaveBeenCalledWith(false)
    expect(scene.setSystemFloor).not.toHaveBeenCalled()
    expect(scene.selectAsset).not.toHaveBeenCalled()
    expect(wrapper.emitted('ready')?.at(-1)).toEqual([true])
    expect(wrapper.emitted('snapshot')?.at(-1)?.[0]).toEqual({
      topic: 'hvac',
      selected: 'HVAC-DEMO-1',
      selectionRevision: 8,
      floorFilter: 'F3',
      playing: true,
      assets: [{
        id: 'HVAC-DEMO-1',
        name: '典型空调内机（示意）',
        system: 'hvac',
        floor: 'F3',
        center: [1, 2, 3],
        bounds: [[0, 1, 2], [2, 3, 4]],
        kind: null,
        floors: [{ name: 'F3', y: 8 }],
      }],
    })
    expect(wrapper.emitted('snapshot')?.at(-1)?.[0]).not.toHaveProperty('assetStates')

    const initialPolls = scene.systemSnapshot.mock.calls.length
    await vi.advanceTimersByTimeAsync(200)
    expect(scene.systemSnapshot).toHaveBeenCalledTimes(initialPolls + 1)
    wrapper.unmount()
  })

  it('only allows floor and asset selection after entering a professional system', async () => {
    const scene = sceneDouble()
    renderer.createReferenceScene.mockResolvedValue(scene.instance)
    const wrapper = mount(BuildingScene)

    await flushPromises()

    const exposed = wrapper.vm as unknown as {
      enterSystem(topic: string | null): void
      setFloor(floor: string): void
      selectAsset(id: string | null): void
    }
    exposed.setFloor('F3')
    exposed.selectAsset('HVAC-DEMO-1')
    expect(scene.setSystemFloor).not.toHaveBeenCalled()
    expect(scene.selectAsset).not.toHaveBeenCalled()

    exposed.enterSystem('hvac')
    exposed.setFloor('F3')
    exposed.selectAsset(null)
    expect(scene.setSystemFloor).toHaveBeenCalledWith('F3')
    expect(scene.selectAsset).toHaveBeenCalledWith(null)
    wrapper.unmount()
  })

  it('shows retry state when construction fails and retries from a new generation', async () => {
    const retried = sceneDouble()
    renderer.createReferenceScene.mockRejectedValueOnce(new Error('WebGL unavailable')).mockResolvedValueOnce(retried.instance)
    const wrapper = mount(BuildingScene)

    await flushPromises()

    expect(wrapper.text()).toContain('场景暂不可用')
    expect(wrapper.get('button').text()).toBe('重试场景')
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(renderer.createReferenceScene).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('ready')?.at(-1)).toEqual([true])
    wrapper.unmount()
  })

  it('disposes an older scene that resolves after reload', async () => {
    const older = sceneDouble()
    const newer = sceneDouble()
    let resolveOlder: ((scene: ReferenceScene) => void) | undefined
    const olderPromise = new Promise<ReferenceScene>(resolve => { resolveOlder = resolve })
    renderer.createReferenceScene.mockReturnValueOnce(olderPromise).mockResolvedValueOnce(newer.instance)
    const wrapper = mount(BuildingScene)

    await flushPromises()
    expect(renderer.createReferenceScene).toHaveBeenCalledTimes(1)

    const exposed = wrapper.vm as unknown as { reload(): void }
    exposed.reload()
    await flushPromises()
    expect(renderer.createReferenceScene).toHaveBeenCalledTimes(2)

    resolveOlder?.(older.instance)
    await flushPromises()

    expect(older.dispose).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('ready')?.at(-1)).toEqual([true])
    wrapper.unmount()
  })

  it('pauses work while hidden or outside the viewport, advances enabled tours, and cleans up on unmount', async () => {
    const scene = sceneDouble()
    renderer.createReferenceScene.mockResolvedValue(scene.instance)
    const wrapper = mount(BuildingScene)
    await flushPromises()
    const observer = TestIntersectionObserver.instances.at(-1)
    expect(observer).toBeDefined()

    hidden = true
    document.dispatchEvent(new Event('visibilitychange'))
    expect(scene.setActive).toHaveBeenLastCalledWith(false)
    const pausedPolls = scene.systemSnapshot.mock.calls.length
    await vi.advanceTimersByTimeAsync(400)
    expect(scene.systemSnapshot).toHaveBeenCalledTimes(pausedPolls)

    hidden = false
    document.dispatchEvent(new Event('visibilitychange'))
    expect(scene.setActive).toHaveBeenLastCalledWith(true)

    const exposed = wrapper.vm as unknown as { setTour(value: boolean): void }
    exposed.setTour(true)
    await vi.advanceTimersByTimeAsync(14_000)
    expect(scene.preset).toHaveBeenCalledWith('terrace')

    observer?.emit(false)
    expect(scene.setActive).toHaveBeenLastCalledWith(false)
    observer?.emit(true)
    expect(scene.setActive).toHaveBeenLastCalledWith(true)

    wrapper.unmount()
    expect(observer?.disconnect).toHaveBeenCalledTimes(1)
    expect(scene.dispose).toHaveBeenCalledTimes(1)
    expect(scene.setActive).toHaveBeenLastCalledWith(false)
    const pollsAfterUnmount = scene.systemSnapshot.mock.calls.length
    await vi.advanceTimersByTimeAsync(14_000)
    expect(scene.systemSnapshot).toHaveBeenCalledTimes(pollsAfterUnmount)
  })
})
