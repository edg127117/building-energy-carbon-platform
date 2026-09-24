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
  if (fieldName === 'modelName' || fieldName === 'formalName' || fieldName === 'errorCode') return value
  return daikinLabel(value)
}

/** 未确认的厂家扩展字段仍可展开核对，但不挤占已确认状态的默认视图。 */
export function daikinCurrentFields(fields: CurrentField[]): { primary: CurrentField[]; extended: CurrentField[] } {
  const primary: CurrentField[] = []
  const extended: CurrentField[] = []
  for (const field of fields) {
    const knownField = Object.prototype.hasOwnProperty.call(words.fieldNames, field.fieldName)
    const knownValue = !field.valueVisible || field.normalizedValue == null || field.normalizedValue === ''
      || ['modelName', 'formalName', 'errorCode'].includes(field.fieldName)
      || Object.prototype.hasOwnProperty.call(words.statusNames, field.normalizedValue)
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
