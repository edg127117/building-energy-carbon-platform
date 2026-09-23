import type { PendingBindRequest, PendingDevice } from './onboarding'

export type IndoorBatchBindingItem = { pendingId: string; binding: PendingBindRequest }

export type IndoorBatchBindingErrorReason = 'invalidSelection' | 'unsupportedBinding' | 'missingLocation' | 'duplicateRoom'

export class IndoorBatchBindingError extends Error {
  constructor(readonly reason: IndoorBatchBindingErrorReason) { super(reason) }
}

export function indoorEquipmentName(roomCode: string, prefix: string): string {
  return `${prefix}-${roomCode.trim()}`
}

/** 批量只复用产品和系统归属；空间与设备名称必须来自每台内机自己的核对记录。 */
export function prepareIndoorBatchBindings(rows: PendingDevice[], binding: PendingBindRequest, namePrefix: string): IndoorBatchBindingItem[] {
  if (rows.length < 2 || rows.some(row => row.status !== 'DISCOVERED'
      || row.identityType !== 'DAIKIN_UNIT' || row.profileCode !== 'DAIKIN_INDOOR_V2')) {
    throw new IndoorBatchBindingError('invalidSelection')
  }
  if (!binding.newEquipment || binding.existingEquipmentId || binding.pointBindings.length > 0) {
    throw new IndoorBatchBindingError('unsupportedBinding')
  }
  const rooms = new Set<string>()
  return rows.map(row => {
    const location = row.location
    if (!location?.roomSpaceId || !location.roomCode?.trim()) {
      throw new IndoorBatchBindingError('missingLocation')
    }
    if (rooms.has(location.roomSpaceId)) {
      throw new IndoorBatchBindingError('duplicateRoom')
    }
    rooms.add(location.roomSpaceId)
    return {
      pendingId: row.pendingId,
      binding: {
        ...binding,
        spaceId: location.roomSpaceId,
        existingEquipmentId: null,
        newEquipment: { ...binding.newEquipment!, equipmentName: indoorEquipmentName(location.roomCode, namePrefix) },
      },
    }
  })
}
