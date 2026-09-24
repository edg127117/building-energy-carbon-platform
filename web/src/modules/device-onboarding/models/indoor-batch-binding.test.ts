import { describe, expect, it } from 'vitest'
import { prepareIndoorBatchBindings } from './indoor-batch-binding'
import type { PendingBindRequest, PendingDevice } from './onboarding'

const binding: PendingBindRequest = {
  productId: 'PRODUCT-IN', buildingId: 'BLD001', spaceId: 'WRONG-SHARED-SPACE',
  systemGroupId: 'DAIKIN-SYSTEM', existingEquipmentId: null,
  newEquipment: { equipmentName: 'ignored common name', manufacturer: '大金' }, pointBindings: [],
}

function indoor(pendingId: string, spaceId: string, roomCode: string): PendingDevice {
  return {
    pendingId, identityType: 'DAIKIN_UNIT', maskedIdentityValue: '****', profileCode: 'DAIKIN_INDOOR_V2',
    lastProfileVersion: 2, status: 'DISCOVERED', reportCount: 1, firstSeenTime: 0, lastSeenTime: 0,
    sampleTruncated: false,
    location: { roomSpaceId: spaceId, roomCode, monitorAddress: '1-01', assetReferenceCode: 'F000002' },
  }
}

describe('大金内机批量绑定', () => {
  it('为每台内机提交自己的空间与设备名称，不修改共享表单', () => {
    const result = prepareIndoorBatchBindings([
      indoor('P1', 'ROOM-1', 'B314-3'), indoor('P2', 'ROOM-2', 'B303-2'),
    ], binding, '大金内机')
    expect(result.map(item => [item.pendingId, item.binding.spaceId, item.binding.newEquipment?.equipmentName]))
      .toEqual([['P1', 'ROOM-1', '大金内机-B314-3'], ['P2', 'ROOM-2', '大金内机-B303-2']])
    expect(result.every(item => item.binding.systemGroupId === 'DAIKIN-SYSTEM')).toBe(true)
    expect(binding.spaceId).toBe('WRONG-SHARED-SPACE')
  })

  it('拒绝缺少映射、重复房间和已有设备批量复用', () => {
    expect(() => prepareIndoorBatchBindings([
      indoor('P1', 'ROOM-1', 'B314-3'), indoor('P2', '', ''),
    ], binding, '大金内机')).toThrow('missingLocation')
    expect(() => prepareIndoorBatchBindings([
      indoor('P1', 'ROOM-1', 'B314-3'), indoor('P2', 'ROOM-1', 'B314-3'),
    ], binding, '大金内机')).toThrow('duplicateRoom')
    expect(() => prepareIndoorBatchBindings([
      indoor('P1', 'ROOM-1', 'B314-3'), indoor('P2', 'ROOM-2', 'B303-2'),
    ], { ...binding, existingEquipmentId: 'E1', newEquipment: null }, '大金内机')).toThrow('unsupportedBinding')
  })
})
