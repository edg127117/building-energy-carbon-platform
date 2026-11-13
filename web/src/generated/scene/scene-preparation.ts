interface PreparedMesh {
  material: string
  offset: number
  vertices: number
  indices?: number
  positions: Float32Array
  normals: Int16Array
  index?: Uint32Array
  [key: string]: unknown
}

export interface PreparedModel {
  meshes: PreparedMesh[]
  [key: string]: unknown
}

// 只保留当前版本的两个静态模型与一份景观；不缓存账号、接口数据、WebGL上下文或运行中的场景。
const models = new Map<string, Promise<PreparedModel>>()
const requests = new Map<string, AbortController>()
let landscape: { key: string; value: unknown } | undefined
let epoch = 0

export function clearScenePreparationCache(): void {
  epoch++
  requests.forEach(request => request.abort())
  requests.clear()
  models.clear()
  landscape = undefined
}

export function preparedLandscape<T>(key: string, build: () => T): T {
  if (landscape?.key === key) return landscape.value as T
  const value = build()
  landscape = { key, value }
  return value
}

export async function decodeSceneModel(buffer: ArrayBuffer): Promise<PreparedModel> {
  let bytes = new Uint8Array(buffer)
  if (bytes[0] === 31 && bytes[1] === 139) {
    buffer = await new Response(new Blob([buffer]).stream().pipeThrough(new DecompressionStream('gzip'))).arrayBuffer()
    bytes = new Uint8Array(buffer)
  }
  const size = new DataView(buffer).getUint32(0, true)
  if (size > bytes.length - 4) throw new Error('Scene model header is incomplete')
  const data = JSON.parse(new TextDecoder().decode(bytes.subarray(4, 4 + size))) as PreparedModel
  // 头部长度不保证四字节对齐；正文至多复制一次，网格直接共享只读TypedArray视图。
  const body = bytes.slice(4 + size)
  const span = (offset: number, count: number, width: number): [ArrayBuffer, number, number] => {
    if (offset < 0 || offset + count * width > body.byteLength) throw new Error('Scene model mesh is incomplete')
    // 源格式的Int16法线可能使下一段偏移只对齐两字节，保留原格式而不是改写几何。
    return offset % width ? [body.slice(offset, offset + count * width).buffer, 0, count] : [body.buffer, offset, count]
  }
  data.meshes = data.meshes.map(mesh => {
    const offset = mesh.offset, normals = offset + mesh.vertices * 12
    return {
      ...mesh,
      positions: new Float32Array(...span(offset, mesh.vertices * 3, 4)),
      normals: new Int16Array(...span(normals, mesh.vertices * 3, 2)),
      ...(mesh.indices !== undefined ? { index: new Uint32Array(...span(normals + mesh.vertices * 6, mesh.indices, 4)) } : {}),
    }
  })
  return data
}

export function preparedModel(url: URL): Promise<PreparedModel> {
  const key = url.href, existing = models.get(key)
  if (existing) return existing
  const generation = epoch
  const controller = new AbortController()
  const pending = (async () => {
    const response = await fetch(url, { signal: controller.signal })
    if (!response.ok) throw new Error('Scene model resource unavailable')
    return decodeSceneModel(await response.arrayBuffer())
  })()
  if (models.size === 2) {
    const oldest = models.keys().next().value
    models.delete(oldest)
    requests.delete(oldest)
  }
  models.set(key, pending)
  requests.set(key, controller)
  // 失败允许重试；清理后旧请求完成或失败均不能覆盖新一代缓存。
  void pending.catch(() => {
    if (generation === epoch && models.get(key) === pending) models.delete(key)
  }).finally(() => {
    if (requests.get(key) === controller) requests.delete(key)
  })
  return pending
}

export function preparedBuildingModel(): Promise<PreparedModel> {
  return preparedModel(new URL('./building-scene.bin.gz', import.meta.url))
}

export function roadDistance(points: readonly { x: number; z: number }[], x: number, z: number): number {
  let squared = Infinity
  for (const point of points) squared = Math.min(squared, (point.x - x) ** 2 + (point.z - z) ** 2)
  return Math.sqrt(squared)
}
