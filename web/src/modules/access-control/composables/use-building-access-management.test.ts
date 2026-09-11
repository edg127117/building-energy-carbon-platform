import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  approveBuildingAccessRequest,
  listBuildingAccessRequests,
  listBuildings,
  listUsers,
  rejectBuildingAccessRequest,
} from '../api/access-control'
import { useBuildingAccessManagement } from './use-building-access-management'

vi.mock('../api/access-control', () => ({
  approveBuildingAccessRequest: vi.fn(),
  listBuildingAccessRequests: vi.fn(),
  listBuildings: vi.fn(),
  listUsers: vi.fn(),
  rejectBuildingAccessRequest: vi.fn(),
  createChangeRequest: vi.fn(),
  getChangeRequest: vi.fn(),
  newIdempotencyKey: vi.fn(),
  submitChangeRequest: vi.fn(),
  withdrawChangeRequest: vi.fn(),
  approveChangeRequest: vi.fn(),
  rejectChangeRequest: vi.fn(),
  executeChangeRequest: vi.fn(),
}))

const emptyPage = { records: [], total: 0, current: 1, size: 100 }

describe('building access review workflow', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listBuildingAccessRequests).mockResolvedValue([])
    vi.mocked(listUsers).mockResolvedValue(emptyPage)
    vi.mocked(listBuildings).mockResolvedValue(emptyPage)
  })

  it('loads pending requests by default', async () => {
    const access = useBuildingAccessManagement()

    await access.load()

    expect(listBuildingAccessRequests).toHaveBeenCalledWith('PENDING')
  })

  it('prevents competing approve and reject submissions for the same request', async () => {
    let resolveApproval: (() => void) | undefined
    vi.mocked(approveBuildingAccessRequest).mockImplementation(() => new Promise<void>(resolve => { resolveApproval = resolve }))
    const access = useBuildingAccessManagement()

    const approving = access.review(12, 'approve')
    const rejecting = access.review(12, 'reject')
    expect(rejectBuildingAccessRequest).not.toHaveBeenCalled()

    resolveApproval?.()
    await Promise.all([approving, rejecting])
    expect(approveBuildingAccessRequest).toHaveBeenCalledTimes(1)
    expect(rejectBuildingAccessRequest).not.toHaveBeenCalled()
  })
})
