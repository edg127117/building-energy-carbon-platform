import { afterEach, describe, expect, it, vi } from 'vitest'

const { requestApi } = vi.hoisted(() => ({ requestApi: vi.fn().mockResolvedValue({}) }))
vi.mock('@/infrastructure/http/public', () => ({ requestApi }))

import { hvacControlApi, newHvacControlIdempotencyKey } from './hvac-control'

describe('大金离线控制 API 契约', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('通过平台客户端请求权限、操作和历史，不绕过 /api 基地址', async () => {
    await hvacControlApi.deviceGrantAccess()
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'GET', url: '/v1/hvac-control/device-grants/access' })

    await hvacControlApi.buildings()
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'GET', url: '/v1/hvac-control/buildings' })

    await hvacControlApi.grantDevices({ equipmentIds: ['idu/01'], actions: ['ON_OFF', 'TEMPERATURE'], evidence: '统一开放办公区权限' })
    expect(requestApi).toHaveBeenLastCalledWith({
      method: 'POST', url: '/v1/hvac-control/device-grants/batch', timeout: 180_000,
      data: { equipmentIds: ['idu/01'], actions: ['ON_OFF', 'TEMPERATURE'], evidence: '统一开放办公区权限' },
    })

    await hvacControlApi.options('idu/01')
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'GET', url: '/v1/hvac-control/devices/idu%2F01/options' })

    await hvacControlApi.createOperation('idu/01', { action: 'ON_OFF', value: 'on', idempotencyKey: 'key-1', revision: 'r-1' })
    expect(requestApi).toHaveBeenLastCalledWith({
      method: 'POST', url: '/v1/hvac-control/devices/idu%2F01/operations',
      data: { action: 'ON_OFF', value: 'on', idempotencyKey: 'key-1', revision: 'r-1' },
    })

    await hvacControlApi.operations('idu/01')
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'GET', url: '/v1/hvac-control/devices/idu%2F01/operations', params: { page: 1, size: 20 } })

    await hvacControlApi.recheck('op/a')
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'POST', url: '/v1/hvac-control/operations/op%2Fa/recheck' })

    await hvacControlApi.close('op/a', '人工确认结束')
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'POST', url: '/v1/hvac-control/operations/op%2Fa/close', data: { reason: '人工确认结束' } })

    await hvacControlApi.confirm('op/a', '现场核对设备已稳定')
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'POST', url: '/v1/hvac-control/operations/op%2Fa/confirm', data: { reason: '现场核对设备已稳定' } })

    await hvacControlApi.release('op/a', '现场核对后解除隔离')
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'POST', url: '/v1/hvac-control/operations/op%2Fa/release', data: { reason: '现场核对后解除隔离' } })

    await hvacControlApi.recovery('idu/01')
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'GET', url: '/v1/hvac-control/devices/idu%2F01/recovery' })

    await hvacControlApi.observeRecovery('op/a', { onOff: 'on', temperature: 24 })
    expect(requestApi).toHaveBeenLastCalledWith({
      method: 'POST', url: '/v1/hvac-control/operations/op%2Fa/recovery/observe', timeout: 30_000,
      data: { onOff: 'on', temperature: 24 },
    })

    await hvacControlApi.completeRecovery('op/a', {
      onOff: 'on', temperature: 24, proof: 'fresh-proof', reason: '现场确认当前设置，按新基线恢复旧隔离，不重发旧指令', onsiteConfirmed: true,
    })
    expect(requestApi).toHaveBeenLastCalledWith({
      method: 'POST', url: '/v1/hvac-control/operations/op%2Fa/recovery/complete', timeout: 30_000,
      data: { onOff: 'on', temperature: 24, proof: 'fresh-proof', reason: '现场确认当前设置，按新基线恢复旧隔离，不重发旧指令', onsiteConfirmed: true },
    })

    await hvacControlApi.continueRecovery('op/a', { reason: '测试模式按当前状态继续处理历史异常' })
    expect(requestApi).toHaveBeenLastCalledWith({
      method: 'POST', url: '/v1/hvac-control/operations/op%2Fa/recovery/continue', timeout: 30_000,
      data: { reason: '测试模式按当前状态继续处理历史异常' },
    })
  })

  it('浏览器不支持 randomUUID 时仍生成幂等键', () => {
    vi.stubGlobal('crypto', {})
    expect(newHvacControlIdempotencyKey()).toMatch(/^web-control-\d+-[a-z0-9]+$/)
  })
})
