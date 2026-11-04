import { prototypeAlarms, prototypeDevices, prototypeRuntimeRanking } from './screen-prototype'
import { periodBuckets, type ScreenPeriod } from './screen-period'
import { periodEntry, type ScreenRedesignData } from './screen-redesign'

/** 明确批准的演示范围仅保留设备、报警和运行排名；动态与静态碳均不使用演示回退。 */
export function withApprovedScreenDemo(data: ScreenRedesignData, period: ScreenPeriod): ScreenRedesignData {
  const base = periodEntry(data.mirror, period)
  const missing = () => Array<number | null>(periodBuckets(period).length).fill(null)
  return { ...data, devices: prototypeDevices, alarms: prototypeAlarms, runtimeRanking: prototypeRuntimeRanking,
    mirror: { period, data: {
      dynamicEmissions: missing(), dynamicFactors: missing(),
      staticEmissions: base?.staticEmissions ?? missing(), staticFactors: base?.staticFactors ?? missing(),
      emissionUnit: base?.emissionUnit ?? 'tCO₂e', factorUnit: base?.factorUnit ?? 'kgCO₂e/kWh',
    } },
  }
}
