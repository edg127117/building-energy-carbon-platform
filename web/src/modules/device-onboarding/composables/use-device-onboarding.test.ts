import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { OnboardingPage, PendingDevice } from '../models/onboarding'
import { listPendingDevices, updatePendingStatus } from '../api/onboarding'
import { useDeviceOnboarding } from './use-device-onboarding'

vi.mock('../api/onboarding', () => ({
  copyDeviceProduct: vi.fn(),
  createDeviceProduct: vi.fn(),
  getDeviceProduct: vi.fn(),
  getPendingDevice: vi.fn(),
  listDeviceProducts: vi.fn(),
  listPendingDevices: vi.fn(),
  updateDeviceProduct: vi.fn(),
  updatePendingStatus: vi.fn(),
}))
vi.mock('@/shared/utils/request-error', () => ({ requestErrorMessage: () => '请求失败' }))

const emptyPending: OnboardingPage<PendingDevice> = { page: 1, size: 20, total: 0, items: [] }

describe('设备接入异步状态', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listPendingDevices).mockResolvedValue(emptyPending)
    vi.mocked(updatePendingStatus).mockResolvedValue({} as never)
  })

  it('忽略迟到的待处理列表响应，只保留最新条件结果', async () => {
    const stale = deferred<OnboardingPage<PendingDevice>>()
    vi.mocked(listPendingDevices)
      .mockReturnValueOnce(stale.promise)
      .mockResolvedValueOnce(page('latest'))
    const management = useDeviceOnboarding()

    const firstLoad = management.loadPendingDevices()
    await management.setPendingQuery({ status: 'DISCOVERED' })
    stale.resolve(page('stale'))
    await firstLoad

    expect(management.pendingDevices.value.items[0]?.pendingId).toBe('latest')
  })

  it('同一待处理状态变更在刷新完成前只会发送一次', async () => {
    const management = useDeviceOnboarding()

    await Promise.all([
      management.changePendingStatus('D-01', 'IGNORED'),
      management.changePendingStatus('D-01', 'IGNORED'),
    ])

    expect(updatePendingStatus).toHaveBeenCalledTimes(1)
    expect(listPendingDevices).toHaveBeenCalledTimes(1)
    expect(management.running.value.size).toBe(0)
  })

  it('将待处理状态写入失败保留为受控错误状态', async () => {
    vi.mocked(updatePendingStatus).mockRejectedValueOnce(new Error('transport'))
    const management = useDeviceOnboarding()

    await expect(management.changePendingStatus('D-01', 'IGNORED')).rejects.toThrow('transport')

    expect(management.operationError.value).toEqual({ message: '请求失败' })
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

function page(pendingId: string): OnboardingPage<PendingDevice> {
  return {
    page: 1,
    size: 20,
    total: 1,
    items: [{
      pendingId,
      identityType: 'CLIENT_ID',
      maskedIdentityValue: 'de***01',
      profileCode: 'V1',
      lastProfileVersion: 1,
      status: 'DISCOVERED',
      reportCount: 0,
      firstSeenTime: 0,
      lastSeenTime: 0,
      sampleTruncated: false,
    }],
  }
}
