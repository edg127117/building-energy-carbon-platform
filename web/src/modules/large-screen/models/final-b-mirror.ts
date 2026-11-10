import { periodBuckets, type ScreenPeriod } from './screen-period'
import { periodEntry, type ScreenRedesignData } from './screen-redesign'

/** 为所选期间提供静态正式结果与空档，等待独立动态查询。 */
export function withScreenMirror(data: ScreenRedesignData, period: ScreenPeriod): ScreenRedesignData {
  const base = periodEntry(data.mirror, period)
  const missing = () => Array<number | null>(periodBuckets(period).length).fill(null)
  return { ...data,
    mirror: { period, data: {
      dynamicEmissions: missing(), dynamicFactors: missing(),
      staticEmissions: base?.staticEmissions ?? missing(), staticFactors: base?.staticFactors ?? missing(),
      emissionUnit: base?.emissionUnit ?? 'tCO₂e', factorUnit: base?.factorUnit ?? 'kgCO₂e/kWh',
    } },
  }
}
