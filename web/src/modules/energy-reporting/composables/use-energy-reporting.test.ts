import { describe, expect, it, vi } from 'vitest'
import { TransportError } from '@/infrastructure/http/public'
import type { ReportingContext } from '../models/reporting'

const api = vi.hoisted(() => ({ context: vi.fn() }))
vi.mock('../api/energy-reporting', () => ({ energyReportingApi: api }))

import { useEnergyReporting } from './use-energy-reporting'

function deferred<T>() {
  let resolve: (value: T) => void
  let reject: (reason?: unknown) => void
  const promise = new Promise<T>((nextResolve, nextReject) => {
    resolve = nextResolve
    reject = nextReject
  })
  return { promise, resolve: resolve!, reject: reject! }
}

function context(buildingId: string, month: string): ReportingContext {
  return {
    buildingId,
    month,
    sources: [],
    batches: [],
    energyOptions: [],
    unitOptions: [],
    conversionOptions: [],
    capabilities: { maintainSources: false, reviewSources: false, reviewBatches: false, sealBatches: false },
  }
}

describe('能源账单填报上下文', () => {
  it('忽略建筑或月份切换前发出的迟到响应', async () => {
    const first = deferred<ReportingContext>()
    const second = deferred<ReportingContext>()
    api.context.mockReset()
    api.context.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const reporting = useEnergyReporting()

    const pendingFirst = reporting.loadContext('B-1', '2026-10-01')
    const pendingSecond = reporting.loadContext('B-2', '2026-11-01')
    second.resolve(context('B-2', '2026-11-01'))
    await pendingSecond
    first.resolve(context('B-1', '2026-10-01'))
    await pendingFirst

    expect(reporting.context.value).toMatchObject({ buildingId: 'B-2', month: '2026-11-01' })
    expect(reporting.contextLoading.value).toBe(false)
  })

  it('将当前命令的权限失败映射为展示状态，不把异常继续抛给页面', async () => {
    const reporting = useEnergyReporting()
    await reporting.loadContext('B-1', '2026-10-01')
    const result = await reporting.execute(() => Promise.reject(new TransportError('forbidden', 403)))

    expect(result).toBeNull()
    expect(reporting.commandStatus.value).toBe(403)
    expect(reporting.commandError.value).not.toBe('')
  })
})
