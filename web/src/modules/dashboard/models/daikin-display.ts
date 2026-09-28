import words from '../locales/daikin'
import type { CurrentField, DaikinDevice, TemperatureReading } from './daikin'

export type DaikinQuickStatus = 'ALL' | 'RUNNING' | 'STOPPED' | 'EXCEPTION' | 'STALE'

export interface DaikinSystemGroup {
  groupKey: string
  systemGroupId: string | null
  outdoorUnit: DaikinDevice | null
  indoorUnits: DaikinDevice[]
  runningCount: number
}

export interface DaikinSpaceGroup {
  groupKey: string
  spaceId: string | null
  spaceName: string
  devices: DaikinDevice[]
  runningCount: number
}

export interface DaikinDetailRow {
  key: string
  label: string
  value: string
  stale: boolean
  statusLabel: string
  explanation?: string
}

export interface DaikinStructuredDetail {
  coreTiles: DaikinDetailRow[]
  roomTempText: string
  setTempText: string
  healthRows: DaikinDetailRow[]
  archiveRows: DaikinDetailRow[]
  capabilityRows: DaikinDetailRow[]
  extendedRows: CurrentField[]
}

/** 已确认枚举翻译；未知厂家值以中文提示并保留原值，不推断其业务含义。 */
export function daikinLabel(value: string | null | undefined): string {
  if (value == null) return '—'
  const known: Record<string, string> = { ...words.fieldNames, ...words.statusNames }
  return Object.prototype.hasOwnProperty.call(known, value) ? known[value]! : `未确认值（原始值：${value}）`
}

export function daikinFieldLabel(value: string): string {
  const known: Record<string, string> = words.fieldNames
  return Object.prototype.hasOwnProperty.call(known, value) ? known[value]! : `未确认字段（原始字段名：${value}）`
}

export function daikinFieldExplanation(fieldName: string): string | undefined {
  const explanations: Record<string, string> = words.fieldExplanations
  return Object.prototype.hasOwnProperty.call(explanations, fieldName) ? explanations[fieldName] : undefined
}

/** 仅把本次缺失的参考字段显示为未提供；已有历史有效值和被屏蔽值仍遵守原有可见性。 */
export function daikinCurrentFieldValue(field: Pick<CurrentField, 'fieldName' | 'status' | 'valueVisible' | 'normalizedValue'>): string {
  if (field.valueVisible && field.normalizedValue != null) return daikinCurrentValue(field.fieldName, field.normalizedValue)
  if (field.status === 'MISSING' && ['arth1', 'controller.decommissioned'].includes(field.fieldName)) return words.notProvided
  return '—'
}

export function daikinCurrentValue(fieldName: string, value: string | null): string {
  if (value == null || value === '') return '—'
  if (fieldName === 'controller.status') {
    const known: Record<string, string> = words.controllerStatusNames
    if (Object.prototype.hasOwnProperty.call(known, value)) return known[value]!
    // 通用生命周期解释只用于展示，保留原码，不提升为厂家确认的故障或运行状态。
    const references: Record<string, string> = words.controllerStatusReferences
    return Object.prototype.hasOwnProperty.call(references, value)
      ? words.controllerStatusReference(references[value]!, value) : words.controllerStatusUnknown(value)
  }
  if (['fanSpeedSetList', 'modeSetList', 'onOffModeSetList', 'masterSlaveIds', 'DefaultSetpointRange'].includes(fieldName)) {
    try {
      const parsed: unknown = JSON.parse(value)
      if (fieldName === 'DefaultSetpointRange' && parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        const range = parsed as Record<string, unknown>
        if ([range.min, range.max, range.step].every(item => typeof item === 'number' && Number.isFinite(item))) {
          return words.setpointRangeValue(range.min as number, range.max as number, range.step as number)
        }
      }
      if (Array.isArray(parsed) && parsed.every(item => typeof item === 'string')) {
        if (!parsed.length) return words.emptyCapabilityList
        return fieldName === 'masterSlaveIds' ? parsed.join('、') : parsed.map(daikinLabel).join('、')
      }
    } catch { /* 历史或不符合契约的值保留原文，不伪造成空列表。 */ }
    return daikinLabel(value)
  }
  if (['modelName', 'formalName', 'errorCode'].includes(fieldName)) return value
  if (['roomTemp', 'temperature', 'coolLimitsettempU', 'coolLimitsettempL', 'heatLimitsettempU', 'heatLimitsettempL'].includes(fieldName)) return value
  const protocolValues = words.fieldValueNames as Record<string, Record<string, string>>
  if (Object.prototype.hasOwnProperty.call(protocolValues, fieldName)) return protocolValues[fieldName]?.[value] ?? daikinLabel(value)
  return daikinLabel(value)
}

/** 已成功解码的协议能力进入主表；未返回、未解码及厂家新增值仍保留在扩展区。 */
export function daikinCurrentFields(fields: CurrentField[]): { primary: CurrentField[]; extended: CurrentField[] } {
  const primary: CurrentField[] = []
  const extended: CurrentField[] = []
  const coreFields = new Set(['onOff', 'mode', 'fanSpeed', 'airflowDirection', 'unitStatus', 'errorCode', 'errorType', 'roomTemp', 'temperature', 'inCommunicationError', 'inEquipmentError', 'inMantenanceMode', 'isFilterDirty', 'controller.isConnectionUp', 'controller.inForcedStop', 'compressorOnOff', 'formalName', 'modelName'])
  const decodedProtocolFields = new Set(['isGroupSlave', 'masterSlaveFlag', 'masterSlaveIds', 'rcProhibitOnOff', 'rcProhibitOpMode', 'rcProhibitSetpoint', 'limitSettempHeat', 'limitSettempCool', 'coolLimitsettempU', 'coolLimitsettempL', 'heatLimitsettempU', 'heatLimitsettempL', 'fanSpeedSetList', 'modeSetList', 'onOffModeSetList', 'DefaultSetpointRange'])
  for (const field of fields) {
    const knownField = coreFields.has(field.fieldName) || (field.status === 'PRESENT' && decodedProtocolFields.has(field.fieldName))
    const knownValue = !field.valueVisible || field.normalizedValue == null || field.normalizedValue === ''
      || ['modelName', 'formalName', 'errorCode'].includes(field.fieldName)
      || !daikinCurrentValue(field.fieldName, field.normalizedValue).startsWith(words.unconfirmedValuePrefix)
    ;(knownField && knownValue ? primary : extended).push(field)
  }
  return { primary, extended }
}

/** 列表内联启停或机组状态直接取自服务端归一化摘要，不推断外机未返回的模式字段。 */
export function isDaikinDeviceRunning(device: Pick<DaikinDevice, 'onOff' | 'unitStatus'>): boolean {
  return device.onOff?.value === 'on' || device.unitStatus?.value === 'operating'
}

export function daikinQuickStatusCounts(items: DaikinDevice[]): Record<DaikinQuickStatus, number> {
  return {
    ALL: items.length,
    RUNNING: items.filter(isDaikinDeviceRunning).length,
    STOPPED: items.filter(item => !isDaikinDeviceRunning(item)).length,
    EXCEPTION: items.filter(item => item.hasActiveException).length,
    STALE: items.filter(item => item.stale).length,
  }
}

export function filterDaikinDevicesByQuickStatus(items: DaikinDevice[], status: DaikinQuickStatus): DaikinDevice[] {
  if (status === 'RUNNING') return items.filter(isDaikinDeviceRunning)
  if (status === 'STOPPED') return items.filter(item => !isDaikinDeviceRunning(item))
  if (status === 'EXCEPTION') return items.filter(item => item.hasActiveException)
  if (status === 'STALE') return items.filter(item => item.stale)
  return items
}

/** 按共享的多联机系统分组 ID 归集外机与内机；无外机或无内机时仍完整保留设备。 */
export function groupDaikinDevicesBySystem(items: DaikinDevice[]): DaikinSystemGroup[] {
  const buckets = new Map<string, { systemGroupId: string | null; outdoorUnit: DaikinDevice | null; indoorUnits: DaikinDevice[] }>()
  for (const item of items) {
    const key = item.systemGroupId ?? '__UNASSIGNED__'
    let bucket = buckets.get(key)
    if (!bucket) {
      bucket = { systemGroupId: item.systemGroupId, outdoorUnit: null, indoorUnits: [] }
      buckets.set(key, bucket)
    }
    if (item.deviceKind === 'OUTDOOR' && !bucket.outdoorUnit) bucket.outdoorUnit = item
    else bucket.indoorUnits.push(item)
  }
  return Array.from(buckets.entries()).map(([groupKey, bucket]) => ({
    groupKey,
    systemGroupId: bucket.systemGroupId,
    outdoorUnit: bucket.outdoorUnit,
    indoorUnits: bucket.indoorUnits,
    runningCount: bucket.indoorUnits.filter(isDaikinDeviceRunning).length,
  }))
}

export function groupDaikinDevicesBySpace(items: DaikinDevice[], spaces: Array<{ spaceId: string; spaceName: string }>): DaikinSpaceGroup[] {
  const spaceNames = new Map(spaces.map(item => [item.spaceId, item.spaceName]))
  const buckets = new Map<string, { spaceId: string | null; spaceName: string; devices: DaikinDevice[] }>()
  for (const item of items) {
    const key = item.spaceId ?? '__UNASSIGNED__'
    let bucket = buckets.get(key)
    if (!bucket) {
      bucket = {
        spaceId: item.spaceId,
        spaceName: (item.spaceId && spaceNames.get(item.spaceId)) || words.unassignedSpace,
        devices: [],
      }
      buckets.set(key, bucket)
    }
    bucket.devices.push(item)
  }
  return Array.from(buckets.entries()).map(([groupKey, bucket]) => ({
    groupKey,
    spaceId: bucket.spaceId,
    spaceName: bucket.spaceName,
    devices: bucket.devices,
    runningCount: bucket.devices.filter(isDaikinDeviceRunning).length,
  }))
}

/** 将当前详情字段按温控核心、健康维保、设备档案、控制能力与待核验扩展分层组织。 */
export function daikinStructuredDetail(fields: CurrentField[], deviceKind?: string | null): DaikinStructuredDetail {
  const { primary, extended } = daikinCurrentFields(fields)
  const primaryMap = new Map(primary.map(item => [item.fieldName, item]))
  const isOutdoor = deviceKind === 'OUTDOOR'
  const toRow = (fieldName: string, skipWhenMissingForOutdoor = false): DaikinDetailRow | null => {
    const item = primaryMap.get(fieldName)
    if (!item) return null
    if (isOutdoor && skipWhenMissingForOutdoor && item.status === 'MISSING' && !item.valueVisible) return null
    return {
      key: item.fieldName,
      label: daikinFieldLabel(item.fieldName),
      value: daikinCurrentFieldValue(item),
      stale: item.stale,
      statusLabel: daikinLabel(item.status),
      explanation: daikinFieldExplanation(item.fieldName),
    }
  }
  const coreFieldNames = isOutdoor
    ? ['compressorOnOff', 'unitStatus', 'modelName', 'controller.isConnectionUp']
    : ['onOff', 'mode', 'fanSpeed', 'airflowDirection']
  const coreTiles = coreFieldNames.map(name => toRow(name)).filter((row): row is DaikinDetailRow => row != null)
  const roomTempField = primaryMap.get('roomTemp')
  const setTempField = primaryMap.get('temperature')
  const healthFieldNames = [
    'unitStatus', 'errorType', 'errorCode', 'isFilterDirty',
    'inCommunicationError', 'inEquipmentError', 'inMantenanceMode',
    'controller.isConnectionUp', 'controller.inForcedStop', 'controller.status',
  ]
  const healthRows = healthFieldNames
    .map(name => toRow(name, name !== 'unitStatus'))
    .filter((row): row is DaikinDetailRow => row != null)
  const archiveFieldNames = ['formalName', 'modelName', 'masterSlaveFlag', 'isGroupSlave', 'masterSlaveIds']
  const archiveRows = archiveFieldNames
    .map(name => toRow(name, ['masterSlaveFlag', 'isGroupSlave', 'masterSlaveIds'].includes(name)))
    .filter((row): row is DaikinDetailRow => row != null)

  const capabilityRows: DaikinDetailRow[] = []
  const rcFields = ['rcProhibitOnOff', 'rcProhibitOpMode', 'rcProhibitSetpoint']
    .map(name => primaryMap.get(name))
    .filter((item): item is CurrentField => item != null)
  if (rcFields.length) {
    capabilityRows.push({
      key: 'rcPermissions',
      label: words.rcPermissionMerged,
      value: rcFields.map(item => daikinCurrentFieldValue(item)).join(' / '),
      stale: rcFields.some(item => item.stale),
      statusLabel: daikinLabel(rcFields[0]!.status),
    })
  }
  const defaultRange = toRow('DefaultSetpointRange', true)
  if (defaultRange) capabilityRows.push(defaultRange)

  const buildLimitRow = (key: string, label: string, lowName: string, highName: string, flagName: string) => {
    const low = primaryMap.get(lowName)
    const high = primaryMap.get(highName)
    const flag = primaryMap.get(flagName)
    if (!low && !high && !flag) return
    const lowText = low ? daikinCurrentFieldValue(low) : '—'
    const highText = high ? daikinCurrentFieldValue(high) : '—'
    const suffix = flag?.normalizedValue === 'on' ? words.limitEnabledSuffix : words.limitDisabledSuffix
    capabilityRows.push({
      key,
      label,
      value: `${lowText}～${highText} °C${suffix}`,
      stale: Boolean(low?.stale || high?.stale || flag?.stale),
      statusLabel: daikinLabel((low ?? high ?? flag)!.status),
    })
  }
  buildLimitRow('coolLimit', words.coolLimitMerged, 'coolLimitsettempL', 'coolLimitsettempU', 'limitSettempCool')
  buildLimitRow('heatLimit', words.heatLimitMerged, 'heatLimitsettempL', 'heatLimitsettempU', 'limitSettempHeat')

  for (const name of ['modeSetList', 'fanSpeedSetList', 'onOffModeSetList']) {
    const row = toRow(name, true)
    if (row) capabilityRows.push(row)
  }

  return {
    coreTiles,
    roomTempText: roomTempField ? daikinCurrentFieldValue(roomTempField) : '—',
    setTempText: setTempField ? daikinCurrentFieldValue(setTempField) : '—',
    healthRows,
    archiveRows,
    capabilityRows,
    extendedRows: extended,
  }
}

/** 仅统计后端返回的真实采样点与已标记的采集间断数，不在浏览器重算衍生温度指标。 */
export function daikinTemperatureSummary(roomItems: TemperatureReading[], setItems: TemperatureReading[]): { sampleCount: number; gapCount: number } {
  const sampleCount = roomItems.filter(item => item.value != null).length + setItems.filter(item => item.value != null).length
  const gapCount = roomItems.filter(item => item.gapBefore).length + setItems.filter(item => item.gapBefore).length
  return { sampleCount, gapCount }
}

/** 断线节点只控制图形连线；所有非空值均直接来自后端质量门禁后的读数。 */
export function temperatureSeries(rows: TemperatureReading[]): Array<[number, number | null]> {
  return rows.flatMap(row => row.gapBefore
    ? [[row.observedAt - 1, null], [row.observedAt, row.value]] as Array<[number, number | null]>
    : [[row.observedAt, row.value]])
}

/** 统计区间使用厂家确认的时区；浏览器无法识别时明确退回 ISO UTC，不冒充本地统计日。 */
export function runtimePeriodTime(value: number, zone: string): string {
  try { return new Intl.DateTimeFormat('zh-CN', { timeZone: zone === 'Z' ? 'UTC' : zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(value) }
  catch { return new Date(value).toISOString() }
}

export function temperatureWindow(range: [number, number] | null, now: number): [number, number] | null {
  const retention = 90 * 86400000
  if (!range || !range.every(Number.isFinite) || range[0] >= range[1] || range[1] - range[0] > retention || range[1] <= now - retention || range[1] > now) return null
  return [Math.max(range[0], now - retention), range[1]]
}
