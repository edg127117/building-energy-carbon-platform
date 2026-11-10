export interface ScreenPlanData {
  // 独立固定的年基准由上游提供；不能由可变的月度序列在浏览器中反算。
  annualBaseline?: number | null
  reductionPercent: number
  remainingQuota: number | null
  baseline: Array<number | null>
  quota: Array<number | null>
  actual: Array<number | null>
  unit: string
}

export interface ScreenMirrorData {
  dynamicEmissions: Array<number | null>
  staticEmissions: Array<number | null>
  dynamicFactors: Array<number | null>
  staticFactors: Array<number | null>
  emissionUnit: string
  factorUnit: string
}

export interface ScreenUsageData {
  electricity: Array<number | null>
  temperature: Array<number | null>
  electricityUnit: string
  temperatureUnit: string
}
