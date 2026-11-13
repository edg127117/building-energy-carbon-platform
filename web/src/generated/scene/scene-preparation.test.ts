import { readFileSync } from 'node:fs'
import { gunzipSync } from 'node:zlib'
import { createHash } from 'node:crypto'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearScenePreparationCache, decodeSceneModel, preparedBuildingModel, preparedLandscape, preparedModel, roadDistance } from './scene-preparation'

const binary = () => {
  const metadata = new TextEncoder().encode(JSON.stringify({ meshes: [{ material: 'test', vertices: 1, offset: 0, indices: 1 }] }))
  const buffer = new ArrayBuffer(4 + metadata.length + 22), bytes = new Uint8Array(buffer), view = new DataView(buffer)
  view.setUint32(0, metadata.length, true); bytes.set(metadata, 4)
  const start = 4 + metadata.length
  for (let i = 0; i < 3; i++) { view.setFloat32(start + i * 4, i + .5, true); view.setInt16(start + 12 + i * 2, i - 1, true) }
  view.setUint32(start + 18, 0, true)
  return buffer
}
const response = () => ({ ok: true, arrayBuffer: async () => binary() })
const url = (name: string) => new URL('https://scene.invalid/' + name)
afterEach(() => { clearScenePreparationCache(); vi.unstubAllGlobals() })

describe('bounded static scene preparation', () => {
  it('shares early building preparation with the renderer resource read', async () => {
    const fetch = vi.fn(async () => response()); vi.stubGlobal('fetch', fetch)
    const early = preparedBuildingModel()
    expect(preparedModel(new URL('./building-scene.bin.gz', import.meta.url))).toBe(early)
    await early
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('preserves unaligned mesh arrays without rewriting positions, normals or indices', async () => {
    const mesh = (await decodeSceneModel(binary())).meshes[0]
    expect([...mesh.positions]).toEqual([.5, 1.5, 2.5])
    expect([...mesh.normals]).toEqual([-1, 0, 1])
    expect([...mesh.index]).toEqual([0])
  })

  it.each(['building-scene.bin.gz', 'f3-hvac.bin.gz'])('keeps every original geometry byte for %s', async name => {
    const raw = gunzipSync(readFileSync(new URL('./' + name, import.meta.url)))
    const decoded = await decodeSceneModel(Uint8Array.from(raw).buffer)
    const size = raw.readUInt32LE(0), header = JSON.parse(raw.subarray(4, 4 + size).toString())
    const body = raw.subarray(4 + size)
    const hash = (value: Uint8Array) => createHash('sha256').update(value).digest('hex')
    expect(decoded.meshes).toHaveLength(header.meshes.length)
    decoded.meshes.forEach((mesh, i) => {
      const old = header.meshes[i], normals = old.offset + old.vertices * 12
      for (const [array, offset, bytes] of [[mesh.positions, old.offset, old.vertices * 12], [mesh.normals, normals, old.vertices * 6],
        ...(old.indices !== undefined ? [[mesh.index, normals + old.vertices * 6, old.indices * 4]] : [])] as const) {
        const typed = array as ArrayBufferView
        expect(hash(new Uint8Array(typed.buffer, typed.byteOffset, typed.byteLength))).toBe(hash(body.subarray(offset as number, (offset as number) + (bytes as number))))
      }
    })
  })

  it('coalesces concurrent reads and retains only the two current model resources', async () => {
    const fetch = vi.fn(async () => response()); vi.stubGlobal('fetch', fetch)
    const first = preparedModel(url('building-v1'))
    expect(preparedModel(url('building-v1'))).toBe(first)
    await first
    await preparedModel(url('hvac-v1'))
    expect(fetch).toHaveBeenCalledTimes(2)
    await preparedModel(url('building-v2'))
    await preparedModel(url('building-v1'))
    expect(fetch).toHaveBeenCalledTimes(4)
  })

  it('retries failed resources and does not let old failure evict a replacement after logout', async () => {
    let fail: (error: Error) => void
    const fetch = vi.fn().mockImplementationOnce(() => new Promise((_resolve, reject) => { fail = reject })).mockResolvedValue(response())
    vi.stubGlobal('fetch', fetch)
    const previous = preparedModel(url('building'))
    clearScenePreparationCache()
    const current = preparedModel(url('building'))
    fail(new Error('offline'))
    await expect(previous).rejects.toThrow('offline')
    await current
    expect(preparedModel(url('building'))).toBe(current)
    expect(fetch).toHaveBeenCalledTimes(2)
    clearScenePreparationCache()
    await preparedModel(url('building'))
    expect(fetch).toHaveBeenCalledTimes(3)
  })

  it('rejects HTTP and truncated geometry failures, allowing a later retry', async () => {
    const fetch = vi.fn().mockResolvedValueOnce({ ok: false }).mockResolvedValue(response())
    vi.stubGlobal('fetch', fetch)
    await expect(preparedModel(url('building'))).rejects.toThrow('resource unavailable')
    await expect(preparedModel(url('building'))).resolves.toHaveProperty('meshes')
    await expect(decodeSceneModel(binary().slice(0, -1))).rejects.toThrow('mesh is incomplete')
  })

  it('retains one version of the prepared landscape and clears it on identity reset', () => {
    const build = vi.fn(() => ({ geometry: new Float32Array([1, 2, 3]) }))
    const first = preparedLandscape('v1', build)
    expect(preparedLandscape('v1', build)).toBe(first)
    preparedLandscape('v2', build)
    expect(build).toHaveBeenCalledTimes(2)
    clearScenePreparationCache()
    expect(preparedLandscape('v2', build)).not.toBe(first)
    expect(build).toHaveBeenCalledTimes(3)
  })

  it('preserves exact road distance results for terrain and tree locations', () => {
    const primary = Array.from({ length: 321 }, (_, i) => ({ x: Math.sin(i) * 107, z: Math.cos(i) * 61 }))
    const secondary = Array.from({ length: 101 }, (_, i) => ({ x: i * 3, z: i / 4 }))
    const points = [...primary.filter((_, i) => i % 4 === 0), ...secondary.filter((_, i) => i % 3 === 0)]
    for (let x = -380; x <= 380; x += 19) for (let z = -360; z <= 270; z += 21) {
      const previous = Math.sqrt(Math.min(...primary.filter((_, i) => i % 4 === 0).map(p => (p.x - x) ** 2 + (p.z - z) ** 2),
        ...secondary.filter((_, i) => i % 3 === 0).map(p => (p.x - x) ** 2 + (p.z - z) ** 2)))
      expect(roadDistance(points, x, z)).toBe(previous)
    }
  })
})
