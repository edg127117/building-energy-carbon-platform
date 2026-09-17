import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useProtocolConfiguration } from './use-protocol-configuration'
import { emptyProtocolConfiguration } from '../models/protocol-configuration'
import { createProtocolConfiguration, getProtocolConfiguration, inspectProtocolSample, listProtocolConfigurations, previewProtocolConfiguration } from '../api/protocol-configuration'
import { TransportError } from '@/infrastructure/http/public'

vi.mock('../api/protocol-configuration', () => ({
  createProtocolConfiguration: vi.fn(), getProtocolConfiguration: vi.fn(), inspectProtocolSample: vi.fn(),
  listProtocolConfigurations: vi.fn(), previewProtocolConfiguration: vi.fn(), updateProtocolConfiguration: vi.fn(),
}))

const deferred = <T>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }

describe('协议配置异步状态', () => {
  beforeEach(() => vi.clearAllMocks())
  it('通过深链接加载详情时保留并行返回的草稿名称列表', async () => {
    const detail = { id: 'D1', revision: 1, status: 'DRAFT' as const, configuration: { ...emptyProtocolConfiguration(), name: '外机电表接入' }, updatedAt: 1 }
    const pending = deferred<{ page: number; size: number; total: number; items: typeof detail[] }>()
    vi.mocked(listProtocolConfigurations).mockReturnValueOnce(pending.promise)
    vi.mocked(getProtocolConfiguration).mockResolvedValueOnce(detail)
    const state = useProtocolConfiguration()
    const listing = state.loadDrafts()
    await state.selectDraft('D1')
    pending.resolve({ page: 1, size: 20, total: 1, items: [detail] })
    await listing
    expect(state.draft.value?.id).toBe('D1')
    expect(state.drafts.value.items[0]?.configuration.name).toBe('外机电表接入')
  })
  it('忽略迟到的字段识别响应', async () => {
    const first = deferred<{ fields: Array<{ path: string; type: 'NUMBER'; value: string }> }>()
    vi.mocked(inspectProtocolSample).mockReturnValueOnce(first.promise).mockResolvedValueOnce({ fields: [{ path: '/new', type: 'NUMBER', value: '2' }] })
    const state = useProtocolConfiguration()
    const oldRequest = state.inspect('{"old":1}')
    await state.inspect('{"new":2}')
    first.resolve({ fields: [{ path: '/old', type: 'NUMBER', value: '1' }] })
    await oldRequest
    expect(state.fields.value.map(field => field.path)).toEqual(['/new'])
  })

  it('配置变化使在途预览结果失效', async () => {
    const pending = deferred<{ success: boolean; errors: never[]; identityType: null; identityValue: null; timeSource: null; eventTime: null; metrics: never[] }>()
    vi.mocked(previewProtocolConfiguration).mockReturnValueOnce(pending.promise)
    const state = useProtocolConfiguration()
    state.form.value = emptyProtocolConfiguration()
    const request = state.runPreview('{}', 1)
    state.invalidatePreview()
    pending.resolve({ success: true, errors: [], identityType: null, identityValue: null, timeSource: null, eventTime: null, metrics: [] })
    await request
    expect(state.preview.value).toBeNull()
  })

  it('样例变化清除字段，并给出草稿冲突和频率限制的可操作错误', async () => {
    const state = useProtocolConfiguration()
    state.fields.value = [{ path: '/old', type: 'NUMBER', value: '1' }]
    state.invalidateInspection()
    expect(state.fields.value).toEqual([])
    vi.mocked(createProtocolConfiguration).mockRejectedValueOnce(new TransportError('request', 409))
    await expect(state.save()).rejects.toBeInstanceOf(TransportError)
    expect(state.error.value).toContain('更新')
    vi.mocked(inspectProtocolSample).mockRejectedValueOnce(new TransportError('request', 429))
    await expect(state.inspect('{}')).rejects.toBeInstanceOf(TransportError)
    expect(state.error.value).toContain('频繁')
  })

  it('新建草稿后不让旧保存响应覆盖当前编辑器', async () => {
    const pending = deferred<{ id: string; revision: number; status: 'DRAFT'; configuration: ReturnType<typeof emptyProtocolConfiguration>; updatedAt: number }>()
    vi.mocked(createProtocolConfiguration).mockReturnValueOnce(pending.promise)
    const state = useProtocolConfiguration()
    state.form.value.name = '旧草稿'
    const saving = state.save()
    state.newDraft()
    pending.resolve({ id: 'OLD', revision: 1, status: 'DRAFT', configuration: { ...emptyProtocolConfiguration(), name: '旧草稿' }, updatedAt: 1 })
    await saving
    expect(state.draft.value).toBeNull()
    expect(state.form.value.name).toBe('')
  })

  it('新建草稿取消旧加载状态，保存期间的后续输入不会被响应覆盖', async () => {
    const loading = deferred<{ id: string; revision: number; status: 'DRAFT'; configuration: ReturnType<typeof emptyProtocolConfiguration>; updatedAt: number }>()
    vi.mocked(getProtocolConfiguration).mockReturnValueOnce(loading.promise)
    const state = useProtocolConfiguration()
    const selecting = state.selectDraft('OLD')
    expect(state.loading.value).toBe(true)
    state.newDraft()
    expect(state.loading.value).toBe(false)
    loading.resolve({ id: 'OLD', revision: 1, status: 'DRAFT', configuration: { ...emptyProtocolConfiguration(), name: '迟到草稿' }, updatedAt: 1 })
    await selecting
    expect(state.form.value.name).toBe('')

    const saving = deferred<{ id: string; revision: number; status: 'DRAFT'; configuration: ReturnType<typeof emptyProtocolConfiguration>; updatedAt: number }>()
    vi.mocked(createProtocolConfiguration).mockReturnValueOnce(saving.promise)
    state.form.value.name = '保存快照'
    const request = state.save()
    state.form.value.name = '继续编辑'
    saving.resolve({ id: 'NEW', revision: 1, status: 'DRAFT', configuration: { ...emptyProtocolConfiguration(), name: '保存快照' }, updatedAt: 1 })
    await request
    expect(state.draft.value?.revision).toBe(1)
    expect(state.form.value.name).toBe('继续编辑')
  })
})
