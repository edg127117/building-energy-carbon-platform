<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { t } from '@/locales'
import type {
  ReferenceScene,
  ReferenceSceneAsset,
  ReferenceSceneFloor,
  ReferenceSceneHotspot,
  ReferenceSceneSnapshot,
  ReferenceSceneVector,
  ReferenceView,
} from '@/generated/scene/reference-engine'

interface SceneAssetGeometry {
  readonly id: string
  readonly name: string
  readonly system: string
  readonly floor: string
  readonly center: ReferenceSceneVector
  readonly bounds: readonly [ReferenceSceneVector, ReferenceSceneVector] | null
  readonly kind: string | null
  readonly fixtureCount?: number
  readonly floors: readonly ReferenceSceneFloor[] | null
}

export interface SceneGeometrySnapshot {
  readonly topic: string | null
  readonly selected: string | null
  readonly selectionRevision: number
  readonly floorFilter: string
  readonly playing: boolean
  readonly assets: readonly SceneAssetGeometry[]
}

export interface SceneHotspot {
  readonly id: string
  readonly name: string
  readonly description: string
  readonly color: string
  readonly x: number
  readonly y: number
  readonly visible: boolean
}

const props = withDefaults(defineProps<{
  active?: boolean
}>(), {
  active: true,
})

const emit = defineEmits<{
  snapshot: [snapshot: SceneGeometrySnapshot | null]
  hotspots: [hotspots: readonly SceneHotspot[]]
  ready: [ready: boolean]
}>()

const POLL_INTERVAL_MILLIS = 200
const TOUR_INTERVAL_MILLIS = 14_000

const stage = ref<HTMLElement | null>(null)
const phase = ref<'loading' | 'ready' | 'unavailable'>('loading')

let disposed = false
let scene: ReferenceScene | null = null
let observer: IntersectionObserver | null = null
let pollTimer: ReturnType<typeof setInterval> | undefined
let tourTimer: ReturnType<typeof setInterval> | undefined
let generation = 0
let loadingGeneration: number | undefined
let intersecting = true
let ready = false
let views: readonly ReferenceView[] = []
let activeViewIndex = 0

let requestedPreset: string | null = null
let requestedSystem: string | null = null
let requestedFloor: string | null = null
let requestedAsset: string | null = null
let requestedFree = false
let requestedMotion = false
let requestedPlaying = false
let requestedTour = false

function copyVector(vector: ReferenceSceneVector): ReferenceSceneVector {
  return [vector[0], vector[1], vector[2]]
}

function copyFloor(floor: ReferenceSceneFloor): ReferenceSceneFloor {
  return { name: floor.name, y: floor.y }
}

function toGeometryAsset(asset: ReferenceSceneAsset): SceneAssetGeometry {
  return {
    id: asset.id,
    name: asset.label,
    system: asset.system,
    floor: asset.floor,
    center: copyVector(asset.center),
    bounds: asset.bounds ? [copyVector(asset.bounds[0]), copyVector(asset.bounds[1])] : null,
    kind: asset.kind ?? null,
    ...(asset.fixtureCount !== undefined ? { fixtureCount: asset.fixtureCount } : {}),
    floors: asset.floors ? asset.floors.map(copyFloor) : null,
  }
}

// 参考模型的 assetStates、运行统计和演示动画不能越过此层，被父级误作平台业务状态。
function toGeometrySnapshot(snapshot: ReferenceSceneSnapshot): SceneGeometrySnapshot {
  return {
    topic: snapshot.topic,
    selected: snapshot.selected,
    selectionRevision: snapshot.selectionRevision,
    floorFilter: snapshot.floorFilter,
    playing: snapshot.playing,
    assets: snapshot.assets.map(toGeometryAsset),
  }
}

function toHotspot(hotspot: ReferenceSceneHotspot): SceneHotspot {
  return {
    id: hotspot.id,
    name: hotspot.name,
    description: hotspot.description,
    color: hotspot.color,
    x: hotspot.x,
    y: hotspot.y,
    visible: hotspot.visible,
  }
}

function canRunScene(): boolean {
  return !disposed && props.active && !document.hidden && intersecting
}

function isCurrentGeneration(value: number): boolean {
  return !disposed && value === generation && stage.value !== null
}

function publishReady(value: boolean, force = false): void {
  if (!force && ready === value) return
  ready = value
  emit('ready', value)
}

function clearPublishedScene(): void {
  emit('snapshot', null)
  emit('hotspots', [])
}

function stopPolling(): void {
  if (pollTimer === undefined) return
  clearInterval(pollTimer)
  pollTimer = undefined
}

function stopTour(): void {
  if (tourTimer === undefined) return
  clearInterval(tourTimer)
  tourTimer = undefined
}

function markUnavailable(value: number): void {
  if (!isCurrentGeneration(value)) return
  generation += 1
  loadingGeneration = undefined
  stopPolling()
  stopTour()
  const current = scene
  scene = null
  current?.setActive(false)
  current?.dispose()
  phase.value = 'unavailable'
  publishReady(false, true)
  clearPublishedScene()
}

function publishScene(): void {
  const current = scene
  if (!current || !canRunScene()) return
  try {
    emit('snapshot', toGeometrySnapshot(current.systemSnapshot()))
    emit('hotspots', current.hotspots().map(toHotspot))
  } catch {
    markUnavailable(generation)
  }
}

function startPolling(): void {
  if (pollTimer !== undefined || !scene || !canRunScene()) return
  pollTimer = setInterval(publishScene, POLL_INTERVAL_MILLIS)
}

function advanceTour(): void {
  if (!scene || views.length === 0 || !canRunScene()) return
  activeViewIndex = (activeViewIndex + 1) % views.length
  scene.preset(views[activeViewIndex]!.id)
}

function startTour(): void {
  if (!requestedTour || tourTimer !== undefined || !scene || !canRunScene() || views.length === 0) return
  tourTimer = setInterval(advanceTour, TOUR_INTERVAL_MILLIS)
}

function applyRequestedState(current: ReferenceScene): void {
  current.setDemoPlaying(requestedPlaying)
  current.setMotion(requestedMotion)
  current.setFree(requestedFree)
  current.enterSystem(requestedSystem)
  if (requestedSystem !== null) {
    if (requestedFloor !== null) current.setSystemFloor(requestedFloor)
    current.selectAsset(requestedAsset)
  }
  if (requestedSystem === null && requestedPreset !== null) current.preset(requestedPreset)
}

async function loadScene(): Promise<void> {
  const currentStage = stage.value
  if (!currentStage || !canRunScene() || loadingGeneration !== undefined || phase.value === 'unavailable') return
  const loadGeneration = ++generation
  loadingGeneration = loadGeneration
  phase.value = 'loading'
  try {
    const renderer = await import('@/generated/scene/reference-engine')
    if (!isCurrentGeneration(loadGeneration)) return
    const candidate = await renderer.createReferenceScene(currentStage, () => markUnavailable(loadGeneration))
    if (!isCurrentGeneration(loadGeneration)) {
      candidate.dispose()
      return
    }
    scene = candidate
    views = renderer.referenceViews
    const presetIndex = requestedPreset === null ? 0 : views.findIndex(view => view.id === requestedPreset)
    activeViewIndex = presetIndex < 0 ? 0 : presetIndex
    applyRequestedState(candidate)
    candidate.setActive(canRunScene())
    phase.value = 'ready'
    publishReady(true)
    publishScene()
    startPolling()
    startTour()
  } catch {
    markUnavailable(loadGeneration)
  } finally {
    if (loadingGeneration === loadGeneration) loadingGeneration = undefined
  }
}

function reconcileScene(): void {
  if (disposed) return
  if (!canRunScene()) {
    stopPolling()
    stopTour()
    scene?.setActive(false)
    if (!scene && loadingGeneration !== undefined) {
      generation += 1
      loadingGeneration = undefined
    }
    return
  }
  if (scene) {
    scene.setActive(true)
    publishScene()
    startPolling()
    startTour()
    return
  }
  void loadScene()
}

function preset(id: string): void {
  requestedPreset = id
  const index = views.findIndex(view => view.id === id)
  if (index >= 0) activeViewIndex = index
  scene?.preset(id)
}

function setFree(value: boolean): void {
  requestedFree = value
  scene?.setFree(value)
}

function setMotion(value: boolean): void {
  requestedMotion = value
  scene?.setMotion(value)
}

function setTour(value: boolean): void {
  requestedTour = value
  if (!value) stopTour()
  else startTour()
}

function enterSystem(topic: string | null): void {
  if (requestedSystem !== topic) {
    requestedFloor = null
    requestedAsset = null
  }
  requestedSystem = topic
  scene?.enterSystem(topic)
  publishScene()
}

function setFloor(floor: string): void {
  if (requestedSystem === null) return
  requestedFloor = floor
  // 楼层切换会清除渲染器中的对象选择，重载时也不能恢复上个楼层的对象。
  requestedAsset = null
  scene?.setSystemFloor(floor)
  publishScene()
}

function selectAsset(id: string | null): void {
  if (requestedSystem === null) return
  requestedAsset = id
  scene?.selectAsset(id)
  publishScene()
}

function setPlaying(value: boolean): void {
  requestedPlaying = value
  scene?.setDemoPlaying(value)
  publishScene()
}

function reload(): void {
  if (disposed) return
  generation += 1
  loadingGeneration = undefined
  stopPolling()
  stopTour()
  const current = scene
  scene = null
  current?.setActive(false)
  current?.dispose()
  phase.value = 'loading'
  publishReady(false, true)
  clearPublishedScene()
  reconcileScene()
}

function visibilityChanged(): void {
  reconcileScene()
}

watch(() => props.active, reconcileScene)

onMounted(() => {
  disposed = false
  publishReady(false, true)
  clearPublishedScene()
  document.addEventListener('visibilitychange', visibilityChanged)
  if (typeof IntersectionObserver !== 'undefined' && stage.value) {
    intersecting = false
    observer = new IntersectionObserver(entries => {
      const entry = entries.find(item => item.target === stage.value)
      if (!entry) return
      intersecting = entry.isIntersecting
      reconcileScene()
    })
    observer.observe(stage.value)
  } else {
    intersecting = true
  }
  reconcileScene()
})

onUnmounted(() => {
  disposed = true
  generation += 1
  loadingGeneration = undefined
  stopPolling()
  stopTour()
  document.removeEventListener('visibilitychange', visibilityChanged)
  observer?.disconnect()
  observer = null
  const current = scene
  scene = null
  current?.setActive(false)
  current?.dispose()
})

defineExpose({
  preset,
  setFree,
  setMotion,
  setTour,
  enterSystem,
  setFloor,
  selectAsset,
  setPlaying,
  reload,
})
</script>

<template>
  <section class="building-scene" :aria-busy="phase === 'loading'" :aria-label="t('largeScreen.scene')">
    <div ref="stage" class="scene-stage" />
    <div v-if="phase === 'loading'" class="scene-state" role="status" aria-live="polite">
      <span>{{ t('largeScreen.sceneLoading') }}</span>
    </div>
    <div v-else-if="phase === 'unavailable'" class="scene-state" role="alert">
      <p class="scene-state-title">{{ t('largeScreen.sceneUnavailable') }}</p>
      <p class="scene-state-help">{{ t('largeScreen.sceneHelp') }}</p>
      <button class="scene-retry" type="button" @click="reload">{{ t('largeScreen.retryScene') }}</button>
    </div>
  </section>
</template>

<style scoped>
.building-scene { position: absolute; inset: 0; min-width: 0; min-height: 0; overflow: hidden; background: var(--bec-screen-panel); color: var(--bec-screen-ink); font-size: var(--bec-screen-font-body); }
.scene-stage { position: absolute; inset: 0; width: 100%; height: 100%; }
/* 渲染缓冲区随 DPR 放大，CSS 显示尺寸仍须跟随逻辑容器，避免高 DPI 下裁切模型。 */
.scene-stage :deep(canvas) { display: block; width: 100%; height: 100%; }
.scene-state { position: absolute; inset: 0; display: grid; align-content: center; justify-items: center; gap: var(--bec-screen-gap); padding: var(--bec-screen-gap); text-align: center; color: var(--bec-screen-muted); }
.scene-state-title { margin: 0; color: var(--bec-screen-ink); font-size: var(--bec-screen-font-title); }
.scene-state-help { margin: 0; }
.scene-retry { color: var(--bec-screen-ink); background: var(--bec-screen-panel); border-color: var(--bec-screen-muted); font: inherit; }
</style>
