import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DaikinDevice } from '../models/daikin'

const api = vi.hoisted(() => ({ deviceGrantAccess: vi.fn(), grantDevices: vi.fn() }))
vi.mock('../api/hvac-control', () => ({ hvacControlApi: api }))

import HvacDeviceGrantPanel from './HvacDeviceGrantPanel.vue'

function device(equipmentId: string, overrides: Partial<DaikinDevice> = {}): DaikinDevice {
  return {
    identityId: `identity-${equipmentId}`, equipmentId, pendingId: `pending-${equipmentId}`, buildingId: 'BLD-1', spaceId: 'SPACE-1', systemGroupId: 'SYSTEM-1',
    deviceKind: 'INDOOR', mappingVersion: 1, active: true, stale: false, lastValidAt: 1, onOff: null, mode: null, unitStatus: null,
    hasActiveException: false, equipmentCode: equipmentId, equipmentName: `设备 ${equipmentId}`,
    ...overrides,
  }
}
function mountPanel(devices: DaikinDevice[]) {
  return mount(HvacDeviceGrantPanel, {
    props: {
      buildingName: '测试大楼', devices,
      spaceOptions: [{ value: 'SPACE-1', label: '3F 办公区', spaceIds: ['SPACE-1'] }],
    },
  })
}

describe('批量设备控制权限入口', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.deviceGrantAccess.mockResolvedValue({ canManage: true, testOpenControls: false })
    api.grantDevices.mockResolvedValue({ items: [] })
  })

  it('管理员一次提交统一说明，自动排除异常和非内机，且只配置权限不发控制命令', async () => {
    api.grantDevices.mockResolvedValue({
      items: [
        { equipmentId: 'IDU-1', opened: true, reasonCode: null, version: 3, actions: [] },
        { equipmentId: 'IDU-3', opened: false, reasonCode: 'CONTROLLER_BUSY', version: null, actions: [] },
      ],
    })
    const wrapper = mountPanel([
      device('IDU-1'),
      device('IDU-3'),
      device('IDU-2', { hasActiveException: true }),
      device('ODU-1', { deviceKind: 'OUTDOOR' }),
    ])
    await flushPromises()

    expect(wrapper.text()).toContain('不发送控制命令')
    expect(wrapper.text()).toContain('自动异常清单（2 台）')
    expect(wrapper.text()).toContain('设备 IDU-2')
    expect(wrapper.text()).toContain('存在异常')
    expect(wrapper.text()).toContain('非内机')
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('批量开放所选设备控制许可')
    await wrapper.find('[data-testid="submit-device-grants"]').trigger('click')
    await flushPromises()

    expect(api.grantDevices).toHaveBeenCalledWith({
      equipmentIds: ['IDU-1', 'IDU-3'], actions: ['ON_OFF', 'TEMPERATURE'], evidence: '批量开放所选设备控制许可',
    })
    expect(wrapper.text()).toContain('已开放')
    expect(wrapper.text()).toContain('控制器仍有未结束或隔离中的控制操作')
    expect(wrapper.text()).toContain('CONTROLLER_BUSY')
    wrapper.unmount()
  })

  it('选择超过 100 台时明确阻止提交，不截断设备列表', async () => {
    const wrapper = mountPanel(Array.from({ length: 101 }, (_, index) => device(`IDU-${index + 1}`)))
    await flushPromises()
    await wrapper.find('textarea').setValue('统一开放全部办公区内机权限')
    await flushPromises()

    expect(wrapper.text()).toContain('已选择 101 台可授权设备，单次最多 100 台')
    expect(wrapper.text()).toContain('不会截断设备清单')
    const submit = wrapper.find('[data-testid="submit-device-grants"]')
    expect(submit.attributes('disabled')).toBeDefined()
    await submit.trigger('click')
    expect(api.grantDevices).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('按 10 台顺序提交，某批失败后保留已返回结果并标明未知和未处理设备', async () => {
    const devices = Array.from({ length: 21 }, (_, index) => device(`IDU-${index + 1}`))
    api.grantDevices
      .mockResolvedValueOnce({ items: devices.slice(0, 10).map((item, index) => ({ equipmentId: item.equipmentId, opened: true, reasonCode: null, version: index + 1, actions: [] })) })
      .mockRejectedValueOnce(new Error('network down'))
    const wrapper = mountPanel(devices)
    await flushPromises()
    await wrapper.find('textarea').setValue('统一开放当前办公区设备控制权限')
    await wrapper.find('[data-testid="submit-device-grants"]').trigger('click')
    await flushPromises()

    expect(api.grantDevices).toHaveBeenCalledTimes(2)
    expect(api.grantDevices).toHaveBeenNthCalledWith(1, {
      equipmentIds: devices.slice(0, 10).map(item => item.equipmentId), actions: ['ON_OFF', 'TEMPERATURE'], evidence: '统一开放当前办公区设备控制权限',
    })
    expect(api.grantDevices).toHaveBeenNthCalledWith(2, {
      equipmentIds: devices.slice(10, 20).map(item => item.equipmentId), actions: ['ON_OFF', 'TEMPERATURE'], evidence: '统一开放当前办公区设备控制权限',
    })
    expect(wrapper.text()).toContain('已返回结果（10 台）')
    expect(wrapper.text()).toContain('10 台设备结果未知')
    expect(wrapper.text()).toContain('1 台设备未处理')
    expect(wrapper.text()).toContain('页面不会自动重试')
    wrapper.unmount()
  })
})
