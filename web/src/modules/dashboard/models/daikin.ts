/** 平台只读响应；null 不转换成零，厂家状态及统计单位保留服务端语义。 */
export interface CursorPage<T> { items: T[]; nextCursor: string | null }
export interface StateSummary { value: string | null; status: string; lastValidAt: number | null; stale: boolean }
export interface DaikinDevice {
  identityId: string; equipmentId: string; pendingId: string; buildingId: string; spaceId: string | null
  systemGroupId: string | null; deviceKind: string; mappingVersion: number; active: boolean
  stale: boolean; lastValidAt: number | null; onOff: StateSummary | null; mode: StateSummary | null
  unitStatus: StateSummary | null; hasActiveException: boolean
  equipmentCode: string; equipmentName: string
}
export interface CurrentField {
  fieldName: string; rawJson: string | null; normalizedValue: string | null; status: string
  lastValidAt: number | null; lastAttemptAt: number; lastAttemptRawJson: string | null
  valueVisible: boolean; stale: boolean; mappingVersion: number; lastAttemptMappingVersion: number
}
export interface DeviceCurrent { equipmentId: string; active: boolean; lastValidAt: number | null; fields: CurrentField[] }
export interface StateEvent {
  eventId: number; fieldName: string; beforeNormalizedValue: string | null; afterNormalizedValue: string | null
  previousObservedAt: number; observedAt: number; afterGap: boolean
}
export interface DaikinException {
  equipmentName?: string | null; equipmentCode?: string | null; exceptionId: number; type: string; scopeType: string; sourceId: string; equipmentId: string | null
  fieldName: string | null; firstDetectedAt: number; lastDetectedAt: number; recoveredAt: number | null
}
export interface TemperatureReading { observedAt: number; value: number | null; dataQuality: number; gapBefore: boolean; stale: boolean; quality?: { decision: string; reason: string } }
export interface TemperatureHistory { items: TemperatureReading[]; nextCursor: number | null; unit: string }
export interface TemperatureCurrent { fieldStatus: string; unit: string; reading: TemperatureReading | null }
export interface RuntimeValue {
  valueId: string; granularity: string; periodStart: number; periodEnd: number; statisticsZone: string; unit: string
  metrics: Record<string, number> | null; periodComplete: boolean; ownershipVerified: boolean; revision: number
  lastSuccessAt: number | null; lastAttemptAt: number; lastAttemptStatus: string
}
export interface RuntimeRevision extends Omit<RuntimeValue, 'valueId' | 'lastSuccessAt' | 'lastAttemptAt' | 'lastAttemptStatus'> { observedAt: number }
export interface RuntimePage extends CursorPage<RuntimeValue> { synchronization: { status: string; periods: number }[] }
export interface ObservedRuntimePage {
  source: 'PLATFORM_OBSERVED'; granularity: string; statisticsZone: string
  items: { periodStart: number; periodEnd: number; elapsedMillis: number; onMillis: number; coveredMillis: number }[]
  nextCursor: number | null
}
