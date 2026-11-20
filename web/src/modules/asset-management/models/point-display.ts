export function ledgerPointDisplay(sourceUnit: string | null | undefined, equipmentCategory: string | null | undefined) {
  const unit = sourceUnit?.trim() ?? ''
  // 电表功率在台账固定用 W 显示，三处共用同一规则，避免随读数或查询区间切换单位。
  if (equipmentCategory === 'ELECTRIC_METER' && unit.toLowerCase() === 'kw') {
    return { unit: 'W', multiplier: 1000 }
  }
  return { unit, multiplier: 1 }
}

export function formatLedgerPointNumber(value: number, unit: string): string {
  const normalized = unit.toLowerCase()
  const precision: Intl.NumberFormatOptions = normalized === 'hz'
    ? { minimumFractionDigits: 2, maximumFractionDigits: 2 }
    : normalized === 'w'
      ? { maximumFractionDigits: 2 }
      : { maximumSignificantDigits: 8 }
  return new Intl.NumberFormat('zh-CN', precision).format(value)
}

export function formatLedgerPointValue(value: number, unit: string): string {
  const formatted = formatLedgerPointNumber(value, unit)
  return unit ? `${formatted} ${unit}` : formatted
}
