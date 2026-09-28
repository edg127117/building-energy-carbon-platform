import { describe, expect, it } from 'vitest'
import { prepareIndoorBatchBindings } from './indoor-batch-binding'
import type { PendingBindRequest, PendingDevice } from './onboarding'

const binding: PendingBindRequest = {
  productId: 'PRODUCT-IN', buildingId: 'BLD001', spaceId: 'WRONG-SHARED-SPACE',
  systemGroupId: 'DAIKIN-SYSTEM', existingEquipmentId: null,
  newEquipment: { equipmentName: 'ignored common name', manufacturer: '大金' }, pointBindings: [],
}

function indoor(pendingId: string, spaceId: string, roomCode: string, monitorAddress = '1-01'): PendingDevice {
  return {
    pendingId, identityType: 'DAIKIN_UNIT', maskedIdentityValue: '****', profileCode: 'DAIKIN_INDOOR_V2',
    lastProfileVersion: 2, status: 'DISCOVERED', identityStatus: 'UNBOUND', reportCount: 1, firstSeenTime: 0, lastSeenTime: 0,
    sampleTruncated: false,
    location: { roomSpaceId: spaceId, roomCode, monitorAddress, assetReferenceCode: 'F000002' },
  }
}

describe('大金内机批量绑定', () => {
  it('为每台内机提交自己的空间与设备名称，同房间多台内机按监控地址自动生成机位序号', () => {
    const result = prepareIndoorBatchBindings([
      indoor('P1', 'ROOM-308', 'B308', '1-02'),
      indoor('P2', 'ROOM-308', 'B308', '1-01'),
      indoor('P3', 'ROOM-313', 'B313', '1-06'),
    ], binding, '大金内机')
    expect(result.map(item => [item.pendingId, item.binding.spaceId, item.binding.newEquipment?.equipmentName]))
      .toEqual([
        ['P1', 'ROOM-308', '大金内机-B308-2'],
        ['P2', 'ROOM-308', '大金内机-B308-1'],
        ['P3', 'ROOM-313', '大金内机-B313'],
      ])
    expect(result.every(item => item.binding.systemGroupId === 'DAIKIN-SYSTEM')).toBe(true)
    expect(binding.spaceId).toBe('WRONG-SHARED-SPACE')
  })

  it('拒绝缺少位置映射和已有设备批量复用', () => {
    expect(() => prepareIndoorBatchBindings([
      indoor('P1', 'ROOM-1', 'B314-3'), indoor('P2', '', ''),
    ], binding, '大金内机')).toThrow('missingLocation')
    expect(() => prepareIndoorBatchBindings([
      indoor('P1', 'ROOM-1', 'B314-3'), indoor('P2', 'ROOM-2', 'B303-2'),
    ], { ...binding, existingEquipmentId: 'E1', newEquipment: null }, '大金内机')).toThrow('unsupportedBinding')
  })
})
