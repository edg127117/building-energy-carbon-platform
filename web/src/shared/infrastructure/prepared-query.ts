export interface PreparedQueryEnvelope<T> {
  contractVersion: 'CARBON_DISPLAY_QUERY_V1'
  kind: 'JOINT_FLOW' | 'CARBON_INTENSITY'
  buildingId: string
  queryKey: string
  sourceVersion: string
  state: 'READY' | 'PREPARING'
  data: T | null
  retryAfterSeconds: number
}

export interface PreparedQueryOptions {
  timeoutMs?: number
  fallbackRetryAfterSeconds?: number
}

/** 两张大屏卡共用准备状态；总预算同时约束HTTP与等待，切换期间会取消底层请求。 */
export async function readPreparedQuery<T>(
  fetch: (signal?: AbortSignal) => Promise<PreparedQueryEnvelope<T>>,
  expected: { kind: PreparedQueryEnvelope<T>['kind']; buildingId: string },
  signal?: AbortSignal,
  options: PreparedQueryOptions = {},
): Promise<T> {
  const deadline = Date.now() + (options.timeoutMs ?? 120_000)

  while (Date.now() < deadline) {
    if (signal?.aborted) throw abortError()
    const remainingBeforeFetch = deadline - Date.now()
    if (remainingBeforeFetch <= 0) throw new Error('CARBON_DISPLAY_QUERY_TIMEOUT')
    const envelope = await fetchUntilDeadline(fetch, remainingBeforeFetch, signal)
    if (signal?.aborted) throw abortError()
    if (!envelope || envelope.contractVersion !== 'CARBON_DISPLAY_QUERY_V1'
      || envelope.kind !== expected.kind || envelope.buildingId !== expected.buildingId
      || typeof envelope.queryKey !== 'string' || !envelope.queryKey
      || typeof envelope.sourceVersion !== 'string' || !envelope.sourceVersion
      || !['READY', 'PREPARING'].includes(envelope.state)) {
      throw new Error('CARBON_DISPLAY_QUERY_ENVELOPE_MISMATCH')
    }
    if (envelope.state === 'READY') {
      if (envelope.data === null || envelope.data === undefined) throw new Error('CARBON_DISPLAY_QUERY_READY_WITHOUT_DATA')
      return envelope.data
    }

    const remaining = deadline - Date.now()
    if (remaining <= 0) throw new Error('CARBON_DISPLAY_QUERY_TIMEOUT')
    const suggested = Number.isFinite(envelope.retryAfterSeconds) ? envelope.retryAfterSeconds : options.fallbackRetryAfterSeconds ?? 2
    const delay = Math.min(5, Math.max(1, suggested)) * 1_000
    await wait(Math.min(delay, remaining), signal)
    if (Date.now() >= deadline) throw new Error('CARBON_DISPLAY_QUERY_TIMEOUT')
  }
  throw new Error('CARBON_DISPLAY_QUERY_TIMEOUT')
}

function fetchUntilDeadline<T>(
  fetch: (signal?: AbortSignal) => Promise<T>, milliseconds: number, parentSignal?: AbortSignal,
): Promise<T> {
  const controller = new AbortController()
  return new Promise((resolve, reject) => {
    const onParentAbort = () => {
      controller.abort()
      cleanup()
      reject(abortError())
    }
    const timer = setTimeout(() => {
      controller.abort()
      cleanup()
      reject(new Error('CARBON_DISPLAY_QUERY_TIMEOUT'))
    }, milliseconds)
    function cleanup() {
      clearTimeout(timer)
      parentSignal?.removeEventListener('abort', onParentAbort)
    }
    if (parentSignal?.aborted) { onParentAbort(); return }
    parentSignal?.addEventListener('abort', onParentAbort, { once: true })
    Promise.resolve().then(() => fetch(controller.signal)).then(
      value => { cleanup(); resolve(value) }, error => { cleanup(); reject(error) },
    )
  })
}

function wait(milliseconds: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) { reject(abortError()); return }
    const timer = setTimeout(done, milliseconds)
    const onAbort = () => {
      clearTimeout(timer)
      signal?.removeEventListener('abort', onAbort)
      reject(abortError())
    }
    function done() {
      signal?.removeEventListener('abort', onAbort)
      resolve()
    }
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

function abortError() {
  return new DOMException('The operation was aborted', 'AbortError')
}
