import words from '../locales/daikin'
import type { CurrentField, TemperatureReading } from './daikin'

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

export function daikinCurrentValue(fieldName: string, value: string | null): string {
  if (value == null || value === '') return '—'
  if (fieldName === 'controller.status') {
    const known: Record<string, string> = words.controllerStatusNames
    return known[value] ?? words.controllerStatusUnknown(value)
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
