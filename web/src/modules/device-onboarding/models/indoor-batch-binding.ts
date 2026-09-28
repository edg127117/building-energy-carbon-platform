import type { PendingBindRequest, PendingDevice } from './onboarding'

export type IndoorBatchBindingItem = { pendingId: string; binding: PendingBindRequest }

export type IndoorBatchBindingErrorReason = 'invalidSelection' | 'unsupportedBinding' | 'missingLocation' | 'duplicateRoom'

export class IndoorBatchBindingError extends Error {
  constructor(readonly reason: IndoorBatchBindingErrorReason) { super(reason) }
}

export function indoorEquipmentName(roomCode: string, prefix: string, unitIndex?: number): string {
  const base = roomCode.trim()
  if (unitIndex == null || /-\d+$/.test(base)) return `${prefix}-${base}`
  return `${prefix}-${base}-${unitIndex}`
}

/** 同房间只有单台内机时直接用房间号命名；同房间存在多台内机时按监控地址排序自动追加 -1、-2 机位序号。 */
export function resolveIndoorBatchEquipmentNames(rows: PendingDevice[], prefix: string): Map<string, string> {
  const byRoom = new Map<string, PendingDevice[]>()
  for (const row of rows) {
    const roomKey = row.location?.roomSpaceId?.trim() || row.location?.roomCode?.trim() || ''
    if (!roomKey) continue
    const group = byRoom.get(roomKey) ?? []
    group.push(row)
    byRoom.set(roomKey, group)
  }
  const names = new Map<string, string>()
  for (const group of byRoom.values()) {
    if (group.length === 1) {
      const only = group[0]!
      const code = only.location?.roomCode ?? ''
      names.set(only.pendingId, code.trim() ? indoorEquipmentName(code, prefix) : '')
      continue
    }
    const sorted = [...group].sort((a, b) =>
      (a.location?.monitorAddress ?? '').localeCompare(b.location?.monitorAddress ?? '', 'zh-CN', { numeric: true })
      || a.pendingId.localeCompare(b.pendingId, 'zh-CN', { numeric: true }),
    )
    sorted.forEach((row, index) => {
      const code = row.location?.roomCode ?? ''
      names.set(row.pendingId, code.trim() ? indoorEquipmentName(code, prefix, index + 1) : '')
    })
  }
  return names
}

/** 批量复用产品和系统归属；空间来自每台内机的真实房间，同房间多台内机自动按监控地址编号。 */
export function prepareIndoorBatchBindings(rows: PendingDevice[], binding: PendingBindRequest, namePrefix: string): IndoorBatchBindingItem[] {
  if (rows.length < 2 || rows.some(row => row.status !== 'DISCOVERED'
      || row.identityType !== 'DAIKIN_UNIT' || row.profileCode !== 'DAIKIN_INDOOR_V2')) {
    throw new IndoorBatchBindingError('invalidSelection')
  }
  if (!binding.newEquipment || binding.existingEquipmentId || binding.pointBindings.length > 0) {
    throw new IndoorBatchBindingError('unsupportedBinding')
  }
  const resolvedNames = resolveIndoorBatchEquipmentNames(rows, namePrefix)
  return rows.map(row => {
    const location = row.location
    if (!location?.roomSpaceId || !location.roomCode?.trim()) {
      throw new IndoorBatchBindingError('missingLocation')
    }
    const equipmentName = resolvedNames.get(row.pendingId) || indoorEquipmentName(location.roomCode, namePrefix)
    return {
      pendingId: row.pendingId,
      binding: {
        ...binding,
        spaceId: location.roomSpaceId,
        existingEquipmentId: null,
        newEquipment: { ...binding.newEquipment!, equipmentName },
      },
    }
  })
}
