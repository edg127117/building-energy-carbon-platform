import { prototypeAlarms, prototypeDevices, prototypeMirror, prototypeRuntimeRanking } from './screen-prototype'
import { periodBuckets, type ScreenPeriod } from './screen-period'
import { periodEntry, type ScreenRedesignData } from './screen-redesign'

/** 明确批准的演示范围。静态碳、电量、温度及其他指标不使用演示回退。 */
export function withApprovedScreenDemo(data: ScreenRedesignData, period: ScreenPeriod): ScreenRedesignData {
  const base = periodEntry(data.mirror, period), demo = prototypeMirror(period)
  const missing = () => Array<number | null>(periodBuckets(period).length).fill(null)
  return { ...data, devices: prototypeDevices, alarms: prototypeAlarms, runtimeRanking: prototypeRuntimeRanking,
    mirror: { period, data: {
      dynamicEmissions: demo.dynamicEmissions, dynamicFactors: demo.dynamicFactors,
      staticEmissions: base?.staticEmissions ?? missing(), staticFactors: base?.staticFactors ?? missing(),
      emissionUnit: base?.emissionUnit ?? 'tCO₂e', factorUnit: base?.factorUnit ?? 'kgCO₂e/kWh',
    } },
  }
}
