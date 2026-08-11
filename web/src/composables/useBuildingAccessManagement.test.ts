import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listBuildingAccessRequests } from '@/api/systemAdmin'
import { useBuildingAccessManagement } from './useBuildingAccessManagement'
vi.mock('@/api/systemAdmin', () => ({ listBuildingAccessRequests: vi.fn(), pageUsers: vi.fn().mockResolvedValue({ records: [], total: 0, current: 1, size: 100 }),
  pageBuildings: vi.fn().mockResolvedValue({ records: [], total: 0, current: 1, size: 100 }), replaceUserBuildings: vi.fn(), getUserDetail: vi.fn(),
  approveBuildingAccessRequest: vi.fn(), rejectBuildingAccessRequest: vi.fn() }))
describe('building access management', () => {
  beforeEach(() => vi.clearAllMocks())
  it('loads pending requests by default', async () => { vi.mocked(listBuildingAccessRequests).mockResolvedValue([]); const access = useBuildingAccessManagement(); await access.load(); expect(listBuildingAccessRequests).toHaveBeenCalledWith('PENDING') })
})
