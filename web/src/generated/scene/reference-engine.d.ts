/**
 * Runtime contract of the isolated V10.1 reference renderer.  Its values describe
 * an authored demonstration model and must not be treated as platform telemetry.
 */
export type ReferenceSceneVector = readonly [number, number, number]

export interface ReferenceSceneFloor {
  readonly name: string
  readonly y: number
}

export interface ReferenceSceneAsset {
  readonly id: string
  readonly label: string
  readonly system: string
  readonly floor: string
  readonly center: ReferenceSceneVector
  readonly bounds?: readonly [ReferenceSceneVector, ReferenceSceneVector]
  readonly kind?: string | null
  /** Number of authored demonstration luminaires in this geometry group, not registered devices. */
  readonly fixtureCount?: number
  readonly floors?: readonly ReferenceSceneFloor[]
  readonly sources?: readonly string[]
  readonly maxPlanDeviationM?: number
}

export interface ReferenceSceneHotspot {
  readonly id: string
  readonly name: string
  readonly description: string
  readonly color: string
  readonly x: number
  readonly y: number
  readonly visible: boolean
}

export interface ReferenceSceneTransparency {
  readonly style: string
  readonly filledMeshes: number
  readonly materials: readonly {
    readonly opacity: number
    readonly depthWrite: boolean
  }[]
}

export interface ReferenceSceneVisual {
  readonly id: string
  readonly y: number
  readonly doorLeft: number
  readonly doorRight: number
  readonly screenX: number
  readonly screenY: number
}

export interface ReferenceSceneLift {
  readonly id: string
  readonly y: number
  readonly open: number
  readonly from: string
  readonly to: string
  readonly position: string
  readonly direction: string
  readonly door: string
  readonly travel: boolean
}

export interface ReferenceSceneSnapshot {
  readonly topic: string | null
  readonly selected: string | null
  readonly selectionRevision: number
  readonly clock: number
  readonly playing: boolean
  readonly blend: number
  readonly scenario: string
  readonly floorFilter: string
  readonly transparency: ReferenceSceneTransparency
  readonly assetStates: readonly {
    readonly id: string
    readonly state: string
  }[]
  readonly assets: readonly ReferenceSceneAsset[]
  readonly visuals: readonly ReferenceSceneVisual[]
  readonly lifts: readonly ReferenceSceneLift[]
}

export interface ReferenceSceneStats {
  ready: boolean
  active: boolean
  frames: number
  triangles: number
  drawCalls: number
  view: string
  geometryCount: number
  textureCount: number
  gpu: string
  contextLosses: number
  renderMs: number[]
}

export interface ReferenceView {
  readonly id: string
  readonly name: string
  readonly position: ReferenceSceneVector
  readonly target: ReferenceSceneVector
  readonly span: number
}

export interface ReferenceScene {
  readonly stats: ReferenceSceneStats
  preset(id: string, immediate?: boolean): void
  dispose(): void
  prepareSystem(topic: string): Promise<void>
  viewState(): {
    readonly position: number[]
    readonly target: number[]
    readonly transition: boolean
    readonly opaque: boolean
  }
  enterSystem(topic: string | null): void
  setSystemFloor(floor: string): void
  setScenario(scenario: string): void
  selectAsset(id: string | null): void
  systemSnapshot(): ReferenceSceneSnapshot
  hotspots(): readonly ReferenceSceneHotspot[]
  setDemoPlaying(playing: boolean): void
  inspectView(position: ReferenceSceneVector, target: ReferenceSceneVector, span?: number): void
  setMotion(motion: boolean): void
  setActive(active: boolean): void
  setFree(free: boolean): void
  reset(): void
  getCanvas(): HTMLCanvasElement
}

export function createReferenceScene(stage: HTMLElement, onContextLost: () => void, signal?: AbortSignal): Promise<ReferenceScene>

export const referenceViews: readonly ReferenceView[]
