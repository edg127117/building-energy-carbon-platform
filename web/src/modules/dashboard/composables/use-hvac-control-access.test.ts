import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ deviceGrantAccess: vi.fn() }))
vi.mock('../api/hvac-control', () => ({ hvacControlApi: api }))

import { useHvacControlAccess } from './use-hvac-control-access'

describe('空调测试开放访问状态', () => {
  const access = useHvacControlAccess()

  beforeEach(() => {
    access.clear()
    vi.clearAllMocks()
  })
  afterEach(() => access.clear())

  it('只采纳服务端明确开启的测试开放标志', async () => {
    api.deviceGrantAccess.mockResolvedValue({ canManage: true, testOpenControls: true })

    await access.refresh()

    expect(access.testOpenControls.value).toBe(true)
    expect(access.loaded.value).toBe(true)
  })

  it('读取失败时收回前端例外，不影响其他菜单的原授权路径', async () => {
    api.deviceGrantAccess.mockResolvedValueOnce({ canManage: true, testOpenControls: true })
    await access.refresh()
    api.deviceGrantAccess.mockRejectedValueOnce(new Error('network unavailable'))

    await access.refresh()

    expect(access.testOpenControls.value).toBe(false)
    expect(access.loaded.value).toBe(true)
  })
})
