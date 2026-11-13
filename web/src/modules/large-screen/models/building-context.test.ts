import { readFileSync } from 'node:fs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createHash } from 'node:crypto'
import { clearScenePreparationCache, preparedLandscape, preparedModel, roadDistance } from '@/generated/scene/scene-preparation'

type Pose = { position: number[]; target: number[]; span: number }
interface Controller {
  enter(topic: string | null): void
  focus(id: string | null): void
  update(delta: number): void
  setFloor(floor: string): void
  snapshot(): { selected: string | null; topic: string | null }
  dispose(): void
}
interface TestCamera {
  aspect: number
  fov: number
  position: { fromArray(values: number[]): void }
  lookAt(x: number, y: number, z: number): void
  updateProjectionMatrix(): void
  updateMatrixWorld(force: boolean): void
}
interface Internals {
  jSt(scene: unknown): (time: number) => void
  buildLandscapeTemplate(scene: unknown): unknown
  YSt(scene: unknown, camera: unknown, meshes: unknown[], geometry: Map<string, unknown>, stage: HTMLElement, move: (position: number[], target: number[], span: number) => void, overview: () => Pose): Controller
  yFt: new () => { traverse(visitor: (object: GeometryObject) => void): void }
  qr: new (...args: number[]) => TestCamera
  ht: new (...args: number[]) => { project(camera: TestCamera): { x: number; y: number; z: number } }
  Lz: new (...args: number[]) => { translate(x: number, y: number, z: number): unknown }
}
interface GeometryObject {
  geometry?: { attributes: Record<string, { array: ArrayBufferView }>; index: { array: ArrayBufferView } | null; dispose(): void }
  instanceMatrix?: { array: ArrayBufferView }
  material?: { dispose(): void }
}
// Exercise the actual bundled controller without creating a WebGL context or fetching assets.
const source = readFileSync('src/generated/scene/reference-engine.js', 'utf8')
const engine = new Function('preparedModel', 'preparedLandscape', 'roadDistance',
  source.replace(/^import .*from "\.\/scene-preparation";\s*$/m, '').replace(/import\.meta\.url/g, '"file:///unused-renderer.js"')
    .replace(/export\s*\{[^}]+\};?\s*$/, '') + ';return {YSt,yFt,qr,Lz,ht,jSt,buildLandscapeTemplate};')(preparedModel, preparedLandscape, roadDistance) as Internals
let controller: Controller
beforeEach(() => {
  vi.stubGlobal('matchMedia', () => ({ matches: false }))
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ fillRect() {}, fillText() {} } as unknown as CanvasRenderingContext2D)
})
afterEach(() => { controller?.dispose(); clearScenePreparationCache(); vi.restoreAllMocks(); vi.unstubAllGlobals(); document.body.replaceChildren() })
function setup(overview: Pose) {
  const stage = document.createElement('div'); document.body.append(stage)
  Object.defineProperties(stage, { clientWidth: { value: 1920, configurable: true }, clientHeight: { value: 1080 } })
  const moves: Pose[] = []
  controller = engine.YSt(new engine.yFt(), new engine.qr(40, 1920 / 1080, .5, 20000), [], new Map([['M_White_aluminium', new engine.Lz(120, 25, 40)]]), stage, (position, target, span) => moves.push({ position, target, span }), () => overview)
  return { stage, moves }
}
describe('elevator-led whole-building composition', () => {
  it('preserves the landscape geometry and instances while giving each renderer disposable resources', () => {
    const gradient = { addColorStop() {} }
    vi.mocked(HTMLCanvasElement.prototype.getContext).mockReturnValue(new Proxy({ createLinearGradient: () => gradient, createRadialGradient: () => gradient },
      { get: (target, key) => target[key] ?? (() => {}) }) as unknown as CanvasRenderingContext2D)
    const original = new engine.yFt(), first = new engine.yFt(), second = new engine.yFt()
    engine.buildLandscapeTemplate(original)
    engine.jSt(first)(0); engine.jSt(second)(0)
    const meshes = (scene: typeof first) => {
      const result: GeometryObject[] = []
      scene.traverse(object => { if (object.geometry) result.push(object) })
      return result
    }
    const before = meshes(original), a = meshes(first), b = meshes(second)
    expect(a.length).toBeGreaterThan(100)
    expect(a).toHaveLength(before.length); expect(b).toHaveLength(before.length)
    const hash = (array: ArrayBufferView) => createHash('sha256').update(new Uint8Array(array.buffer, array.byteOffset, array.byteLength)).digest('hex')
    before.forEach((object, i) => {
      for (const [name, attribute] of Object.entries(object.geometry.attributes)) {
        expect(hash(a[i].geometry.attributes[name].array)).toBe(hash(attribute.array))
        expect(a[i].geometry.attributes[name].array).toBe(b[i].geometry.attributes[name].array)
      }
      if (object.instanceMatrix) {
        expect(hash(a[i].instanceMatrix.array)).toBe(hash(object.instanceMatrix.array))
        expect(a[i].instanceMatrix.array).not.toBe(b[i].instanceMatrix.array)
      }
      expect(a[i].geometry).not.toBe(b[i].geometry)
      expect(a[i].material).not.toBe(b[i].material)
      a[i].geometry.dispose(); a[i].material.dispose()
      for (const [name, attribute] of Object.entries(object.geometry.attributes)) expect(hash(b[i].geometry.attributes[name].array)).toBe(hash(attribute.array))
    })
  })
  it.each([1920, 900])('keeps actual facade corners clear of side panels at viewport width %s', width => {
    const { stage, moves } = setup({ position: [126, 53, 157], target: [0, 10, 0], span: 40 })
    Object.defineProperty(stage, 'clientWidth', { value: width })
    controller.enter('elevator'); controller.update(.1)
    const pose = moves.at(-1)!, camera = new engine.qr(pose.span, width / 1080, .5, 20000)
    camera.position.fromArray(pose.position); camera.lookAt(pose.target[0]!, pose.target[1]!, pose.target[2]!)
    camera.updateProjectionMatrix(); camera.updateMatrixWorld(true)
    for (const x of [-60, 60]) for (const y of [-12.5, 12.5]) for (const z of [-20, 20]) {
      const projected = new engine.ht(x, y, z).project(camera)
      expect(Math.abs(projected.x)).toBeLessThan(.7)
      expect(Math.abs(projected.y)).toBeLessThan(.76)
      expect(projected.z).toBeLessThan(1)
    }
  })
  const overview = { position: [126, 53, 157], target: [0, 10, 0], span: 40 }
  const distance = (pose: Pose) => Math.hypot(...pose.position.map((v, i) => v - pose.target[i]!))
  it('moves toward the elevator and modestly closer while preserving overview direction', () => {
    const { moves } = setup(overview)
    controller.enter('elevator'); controller.update(.1)
    const pose = moves.at(-1)!
    expect(controller.snapshot().selected).toBeNull()
    expect(pose.target[0]).toBeLessThan(overview.target[0]!)
    expect(pose.target[0]).toBeGreaterThan(-18.1)
    expect(distance(pose)).toBeLessThan(distance(overview))
    expect(distance(pose)).toBeGreaterThanOrEqual(distance(overview) * .839)
    pose.position.forEach((v, i) => expect((v - pose.target[i]!) / distance(pose)).toBeCloseTo((overview.position[i]! - overview.target[i]!) / distance(overview)))
    const count = moves.length
    for (let i = 0; i < 30; i++) controller.update(.1)
    expect(moves.length).toBe(count)
  })
  it('restores system composition after explicit close-up and floor changes; resizing refits once', () => {
    const { moves, stage } = setup(overview)
    controller.enter('elevator'); controller.update(.1)
    const systemPose = moves.at(-1)
    controller.focus('EL-01'); expect(controller.snapshot().selected).toBe('EL-01')
    expect(moves.at(-1)).not.toEqual(systemPose)
    controller.focus(null); expect(moves.at(-1)).toEqual(systemPose)
    controller.setFloor('F3'); expect(moves.at(-1)).toEqual(systemPose)
    Object.defineProperty(stage, 'clientWidth', { value: 900 }); controller.update(.1)
    const narrowPose = moves.at(-1)!
    expect(distance(narrowPose)).toBeGreaterThan(distance(systemPose!))
    const count = moves.length; controller.update(.1); expect(moves.length).toBe(count)
    expect(controller.snapshot().selected).toBeNull()
  })
})

describe('professional scene inside the scaled large-screen canvas', () => {
  it('keeps lighting framing and floor callouts in logical coordinates at different display scales', () => {
    document.body.innerHTML = '<div class="building-screen"><div id="stage"></div><aside class="asset-list"></aside><aside class="asset-details"></aside></div>'
    const stage = document.querySelector<HTMLElement>('#stage')!
    Object.defineProperties(stage, { clientWidth: { value: 1920 }, clientHeight: { value: 1080 } })
    let scale = 1
    stage.getBoundingClientRect = () => ({ left: 10, top: 20, width: 1920 * scale, height: 1080 * scale }) as DOMRect
    for (const panel of document.querySelectorAll<HTMLElement>('aside')) panel.getBoundingClientRect = () => ({ left: 10, right: 10 + 300 * scale, width: 280 * scale, height: 900 * scale }) as DOMRect
    const camera = new engine.qr(40, 1920 / 1080, .5, 20000), moves: Pose[] = []
    const geometry = new Map<string, unknown>([['M_White_aluminium', new engine.Lz(120, 25, 40)]])
    for (let index = 0; index < 6; index++) geometry.set('R_Interior_linear_light_' + index, new engine.Lz(1, .02, .1).translate(-45, 3.455 + index * 3.8, 10))
    controller = engine.YSt(new engine.yFt(), camera, [], geometry, stage, (position, target, span) => {
      moves.push({ position, target, span }); camera.position.fromArray(position); camera.lookAt(target[0]!, target[1]!, target[2]!)
      camera.updateProjectionMatrix(); camera.updateMatrixWorld(true)
    }, () => ({ position: [126, 53, 157], target: [0, 10, 0], span: 40 }))
    const capture = () => {
      controller.enter('lighting'); controller.update(1)
      const rows = [...stage.querySelectorAll<SVGGElement>('svg > g')].filter(row => row.style.display !== 'none')
      expect(rows).toHaveLength(6)
      return { pose: moves.at(-1), labels: rows.map(row => ({ x: row.querySelector('rect')!.getAttribute('x'), y: row.querySelector('rect')!.getAttribute('y') })) }
    }
    const baseline = capture()
    for (const value of [.75, 1.25, 2]) { scale = value; expect(capture()).toEqual(baseline) }
  })
})
