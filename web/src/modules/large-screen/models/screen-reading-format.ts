const decimalFormatter = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const integerFormatter = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 })

export function formatScreenReading(value: number, digits: 0 | 2 = 2): string {
  return (digits === 0 ? integerFormatter : decimalFormatter).format(value)
}
