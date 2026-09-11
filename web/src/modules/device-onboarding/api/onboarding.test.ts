import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { copyDeviceProduct, listDeviceProducts, listPendingDevices, updatePendingStatus } from './onboarding'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('设备接入接口契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestApi).mockResolvedValue({} as never)
  })

  it('通过统一请求层查询产品模板和待接入设备', async () => {
    await listDeviceProducts({ page: 1, size: 20, status: 'ENABLED', keyword: '空调' })
    await listPendingDevices({ page: 2, size: 20, status: 'DISCOVERED', identity: 'dev', profileCode: 'V1' })

    expect(requestApi).toHaveBeenNthCalledWith(1, {
      method: 'get',
      url: '/v1/device-products',
      params: { page: 1, size: 20, status: 'ENABLED', keyword: '空调' },
    })
    expect(requestApi).toHaveBeenNthCalledWith(2, {
      method: 'get',
      url: '/v1/device-onboarding/pending',
      params: { page: 2, size: 20, status: 'DISCOVERED', identity: 'dev', profileCode: 'V1' },
    })
  })

  it('保留可直接编辑的草稿与待处理状态接口，不登记已拒绝的敏感直写接口', async () => {
    await copyDeviceProduct('P/01', { productCode: 'P-02', productName: '副本' })
    await updatePendingStatus('D/01', { status: 'IGNORED', reason: null })
    const urls = vi.mocked(requestApi).mock.calls.map(([config]) => config.url)

    expect(requestApi).toHaveBeenNthCalledWith(1, {
      method: 'post',
      url: '/v1/device-products/P%2F01/copy',
      data: { productCode: 'P-02', productName: '副本' },
    })
    expect(requestApi).toHaveBeenNthCalledWith(2, {
      method: 'put',
      url: '/v1/device-onboarding/pending/D%2F01/status',
      data: { status: 'IGNORED', reason: null },
    })
    expect(urls.some(url => url?.includes('/bind') || url?.includes('/activate') || url?.includes('/deactivate'))).toBe(false)
  })
})
