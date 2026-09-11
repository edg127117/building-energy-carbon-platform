import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  approveChangeRequest,
  createChangeRequest,
  executeChangeRequest,
  getChangeRequest,
  newIdempotencyKey,
  rejectChangeRequest,
  submitChangeRequest,
  withdrawChangeRequest,
} from '../api/access-control'
import { useSensitiveChange } from './use-sensitive-change'

vi.mock('../api/access-control', () => ({
  approveChangeRequest: vi.fn(),
  createChangeRequest: vi.fn(),
  executeChangeRequest: vi.fn(),
  getChangeRequest: vi.fn(),
  newIdempotencyKey: vi.fn(),
  rejectChangeRequest: vi.fn(),
  submitChangeRequest: vi.fn(),
  withdrawChangeRequest: vi.fn(),
}))

const draft = { requestId: 'request-1', operationCode: 'UPDATE_USER_STATUS', status: 'DRAFT' } as unknown as Record<string, unknown>
const pending = { ...draft, status: 'PENDING_REVIEW' } as unknown as Record<string, unknown>

describe('sensitive change workflow', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(newIdempotencyKey).mockReturnValue('idempotency-key')
  })

  it('creates then submits a sensitive command without calling a direct write endpoint', async () => {
    vi.mocked(createChangeRequest).mockResolvedValue(draft as never)
    vi.mocked(submitChangeRequest).mockResolvedValue(pending as never)
    const changes = useSensitiveChange()

    await changes.start('UPDATE_USER_STATUS', { userId: 7, status: 0 }, 'status:7')

    expect(createChangeRequest).toHaveBeenCalledWith(
      'UPDATE_USER_STATUS',
      { userId: 7, status: 0 },
      'idempotency-key',
    )
    expect(submitChangeRequest).toHaveBeenCalledWith('request-1')
    expect(changes.current.value?.status).toBe('PENDING_REVIEW')
  })

  it('coalesces duplicate in-flight starts for the same business command', async () => {
    let resolveDraft: ((value: Record<string, unknown>) => void) | undefined
    const deferredDraft = new Promise<Record<string, unknown>>(resolve => { resolveDraft = resolve })
    vi.mocked(createChangeRequest).mockImplementation(() => deferredDraft as never)
    vi.mocked(submitChangeRequest).mockResolvedValue(pending as never)
    const changes = useSensitiveChange()

    const first = changes.start('UPDATE_USER_STATUS', { userId: 7, status: 0 }, 'status:7')
    const second = changes.start('UPDATE_USER_STATUS', { userId: 7, status: 0 }, 'status:7')
    expect(createChangeRequest).toHaveBeenCalledTimes(1)

    resolveDraft?.(draft)
    await Promise.all([first, second])
    expect(submitChangeRequest).toHaveBeenCalledTimes(1)
  })

  it('keeps each lifecycle operation behind the shared request client', async () => {
    vi.mocked(getChangeRequest).mockResolvedValue(pending as never)
    vi.mocked(withdrawChangeRequest).mockResolvedValue({ ...pending, status: 'WITHDRAWN' } as never)
    vi.mocked(approveChangeRequest).mockResolvedValue({ ...pending, status: 'APPROVED' } as never)
    vi.mocked(rejectChangeRequest).mockResolvedValue({ ...pending, status: 'REJECTED' } as never)
    vi.mocked(executeChangeRequest).mockResolvedValue({ ...pending, status: 'EXECUTED' } as never)
    const changes = useSensitiveChange()

    await changes.load('request-1')
    await changes.withdraw('request-1')
    await changes.approve('request-1', 'approved')
    await changes.reject('request-1', 'rejected')
    await changes.execute('request-1')

    expect(getChangeRequest).toHaveBeenCalledWith('request-1')
    expect(withdrawChangeRequest).toHaveBeenCalledWith('request-1')
    expect(approveChangeRequest).toHaveBeenCalledWith('request-1', 'approved')
    expect(rejectChangeRequest).toHaveBeenCalledWith('request-1', 'rejected')
    expect(executeChangeRequest).toHaveBeenCalledWith('request-1')
  })
})
