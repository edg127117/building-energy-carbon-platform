import { t } from '@/locales'
import { formatDateTime } from '@/shared/utils/format'
import type { AssetRoomSensorReading } from './assets'

const text = (key: string, params?: Record<string, string | number>) =>
  t(`assetManagement.ledgerRuntime.${key}`, params)

export function roomSensorName(sensor: AssetRoomSensorReading) {
  return text(sensor.kind === 'DOOR' ? 'roomSensor.door' : 'roomSensor.radar')
}

export function roomSensorDoorValue(open: boolean) {
  return text(open ? 'roomSensor.open' : 'roomSensor.closed')
}

export function roomSensorValue(sensor: AssetRoomSensorReading, previous = false) {
  if (sensor.kind === 'DOOR') {
    const open = previous ? sensor.lastObservedOpen : sensor.currentOpen
    return open == null ? text('unknown') : roomSensorDoorValue(open)
  }
  const count = previous ? sensor.lastObservedCount : sensor.currentCount
  return count == null ? text('unknown') : text('roomSensor.count', { count })
}

export function roomSensorEvidence(sensor: AssetRoomSensorReading) {
  if (sensor.lastObservedAt == null) return text('roomSensor.missing')
  const time = formatDateTime(sensor.lastObservedAt)
  const expired = sensor.kind === 'DOOR' ? sensor.currentOpen == null : sensor.currentCount == null
  return expired
    ? text('roomSensor.previous', { value: roomSensorValue(sensor, true), time })
    : text('roomSensor.at', { time })
}
