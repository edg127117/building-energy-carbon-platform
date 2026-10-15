import { effectScope, nextTick, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TransportError } from '@/infrastructure/http/public'
import type { HvacControlBatch, HvacControlBatchPreview } from '../models/hvac-control'

const api = vi.hoisted(() => ({
  previewBatch: vi.fn(), createBatch: vi.fn(), batch: vi.fn(), batches: vi.fn(), cancelBatch: vi.fn(),
}))
const keyFactory = vi.hoisted(() => vi.fn())
vi.mock('../api/hvac-control', () => ({
  hvacControlApi: api,
  newHvacControlIdempotencyKey: keyFactory,
}))

import { useHvacBatchControl } from './use-hvac-batch-control'

const previewFor = (previewId: string, equipmentIds: string[]): HvacControlBatchPreview => ({
  previewId,
  expiresAt: 1_700_000_120_000,
  items: equipmentIds.map(equipmentId => ({
    equipmentId,
    revision: 'r-1',
    reasonCode: null,
    actions: [{ action: 'ON_OFF', allowed: true, reasonCode: null, values: ['on', 'off'], minimum: null, maximum: null }],
    impactedEquipmentIds: [],
    impactKnown: true,
  })),
})

const batchFor = (batchId: string, overrides: Partial<HvacControlBatch> = {}): HvacControlBatch => ({
  batchId,
  action: 'ON_OFF',
  value: 'on',
  createdAt: 1_700_000_000_000,
  updatedAt: 1_700_000_000_001,
  completed: false,
  total: 1,
  completedCount: 0,
  queuedCount: 1,
  activeCount: 0,
  attentionCount: 0,
  items: [{
    itemId: `item-${batchId}`,
    equipmentId: 'A',
    status: 'QUEUED',
    reasonCode: null,
    operationId: null,
    observedValue: null,
    unitStatus: null,
    createdAt: 1_700_000_000_000,
    dispatchedAt: null,
    acceptedAt: null,
    readbackAt: null,
    queuedMillis: null,
    acceptanceMillis: null,
    readbackMillis: null,
  }],
  ...overrides,
})

const deferred = <T>() => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

describe('批量控制状态资源', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    keyFactory.mockReturnValue('batch-key-1')
    api.previewBatch.mockImplementation((equipmentIds: string[]) => Promise.resolve(previewFor(`preview-${equipmentIds.join('-')}`, equipmentIds)))
    api.createBatch.mockResolvedValue(batchFor('batch-1'))
    api.batch.mockResolvedValue(batchFor('batch-1'))
    api.batches.mockResolvedValue({ page: 1, size: 10, total: 0, items: [] })
    api.cancelBatch.mockResolvedValue(batchFor('batch-1', { completed: true }))
  })

  afterEach(() => vi.useRealTimers())

  it('切换选择范围后忽略迟到的预检响应', async () => {
    const delayed = deferred<HvacControlBatchPreview>()
    api.previewBatch.mockImplementation((equipmentIds: string[]) => equipmentIds[0] === 'A'
      ? delayed.promise
      : Promise.resolve(previewFor('preview-B', equipmentIds)))
    const equipmentIds = ref(['A'])
    const scope = effectScope()
    const control = scope.run(() => useHvacBatchControl(equipmentIds))!

    const first = control.createPreview()
    equipmentIds.value = ['B']
    await nextTick()
    await control.createPreview()
    expect(control.preview.value?.previewId).toBe('preview-B')

    delayed.resolve(previewFor('preview-A', ['A']))
    await first
    expect(control.preview.value?.previewId).toBe('preview-B')
    scope.stop()
  })

  it('提交结果未知后重新预检，仍复用旧 previewId 和幂等键', async () => {
    api.previewBatch
      .mockResolvedValueOnce(previewFor('preview-old', ['A']))
      .mockResolvedValueOnce(previewFor('preview-new', ['A']))
    api.createBatch
      .mockRejectedValueOnce(new Error('network response lost'))
      .mockResolvedValueOnce(batchFor('batch-old'))
    const scope = effectScope()
    const control = scope.run(() => useHvacBatchControl(ref(['A'])))!

    await control.createPreview()
    await control.submit('ON_OFF', 'on', false)
    await control.createPreview()
    await control.submit('ON_OFF', 'on', true)

    expect(api.createBatch).toHaveBeenNthCalledWith(1, {
      previewId: 'preview-old', action: 'ON_OFF', value: 'on', idempotencyKey: 'batch-key-1', confirmLinkedImpact: false,
    })
    expect(api.createBatch).toHaveBeenNthCalledWith(2, {
      previewId: 'preview-old', action: 'ON_OFF', value: 'on', idempotencyKey: 'batch-key-1', confirmLinkedImpact: false,
    })
    expect(keyFactory).toHaveBeenCalledTimes(1)
    expect(control.preview.value).toBeNull()
    expect(control.activeBatch.value?.batchId).toBe('batch-old')
    scope.stop()
  })

  it('服务端明确预检已过期后，才允许新的预检使用新幂等键', async () => {
    keyFactory.mockReturnValueOnce('old-key').mockReturnValueOnce('new-key')
    api.previewBatch
      .mockResolvedValueOnce(previewFor('preview-old', ['A']))
      .mockResolvedValueOnce(previewFor('preview-new', ['A']))
    api.createBatch
      .mockRejectedValueOnce(new TransportError('request', 409, undefined, 'HVAC_CONTROL_PREVIEW_EXPIRED'))
      .mockResolvedValueOnce(batchFor('batch-new'))
    const scope = effectScope()
    const control = scope.run(() => useHvacBatchControl(ref(['A'])))!

    await control.createPreview()
    await control.submit('ON_OFF', 'on', false)
    await control.createPreview()
    await control.submit('ON_OFF', 'on', false)

    expect(api.createBatch).toHaveBeenNthCalledWith(2, {
      previewId: 'preview-new', action: 'ON_OFF', value: 'on', idempotencyKey: 'new-key', confirmLinkedImpact: false,
    })
    scope.stop()
  })

  it('从本人历史打开未完成批次后恢复只读轮询', async () => {
    api.batches.mockResolvedValue({ page: 1, size: 10, total: 1, items: [batchFor('unfinished')] })
    api.batch.mockResolvedValue(batchFor('unfinished'))
    const scope = effectScope()
    const control = scope.run(() => useHvacBatchControl(ref(['A'])))!

    await control.loadHistory(1, true)
    await Promise.resolve()
    expect(api.batch).toHaveBeenCalledWith('unfinished')
    expect(control.activeBatch.value?.batchId).toBe('unfinished')
    scope.stop()
  })
})
