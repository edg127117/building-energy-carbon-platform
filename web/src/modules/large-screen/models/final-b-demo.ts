import { prototypeAlarms } from './screen-prototype'
import { periodBuckets, type ScreenPeriod } from './screen-period'
import { periodEntry, type ScreenRedesignData } from './screen-redesign'

/** 报警沿用当前展示素材。碳趋势按所选期间保留真实静态结果和空档。 */
export function withApprovedScreenDemo(data: ScreenRedesignData, period: ScreenPeriod): ScreenRedesignData {
  const base = periodEntry(data.mirror, period)
  const missing = () => Array<number | null>(periodBuckets(period).length).fill(null)
  return { ...data, alarms: prototypeAlarms,
    mirror: { period, data: {
      dynamicEmissions: missing(), dynamicFactors: missing(),
      staticEmissions: base?.staticEmissions ?? missing(), staticFactors: base?.staticFactors ?? missing(),
      emissionUnit: base?.emissionUnit ?? 'tCO₂e', factorUnit: base?.factorUnit ?? 'kgCO₂e/kWh',
    } },
  }
}
