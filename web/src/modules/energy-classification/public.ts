import { carbonResultsApi } from './api/carbon-results'
import { carbonHourlyApi } from './api/carbon-hourly'

export { queryEnergyFlow } from './api/energy-flow'
export type { EnergyFlowView, EnergyFlowGraph, EnergyFlowOption, EnergyFlowSelection } from './api/energy-flow'
export { readDynamicElectricity } from './api/dynamic-electricity'
export type {
  DynamicElectricityPeriodType,
  DynamicElectricityPoint,
  DynamicElectricityPointStatus,
  DynamicElectricityView,
} from './api/dynamic-electricity'

export { routes } from './routes'
export { default as messages } from './locales/zh-CN'
export { default as EnergyClassificationPage } from './pages/EnergyClassificationPage.vue'
export { carbonFactorsApi } from './api/carbon-factors'
export { carbonPlanApi } from './api/carbon-plan'
export type { CarbonPlanResponse, CarbonPlanVersion, CarbonPlanBaseline } from './api/carbon-plan'
export { default as CarbonPlanPanel } from './components/CarbonPlanPanel.vue'
export { default as CarbonPlanPage } from './pages/CarbonPlanPage.vue'
export { previewJointFlow, queryJointFlow, queryPreparedJointFlow } from './api/joint-flow'
export { jointFlowConfigurationApi } from './api/joint-flow-configurations'
export type { JointFlowCoverage, JointFlowGraph, JointFlowNode, JointFlowRequest, JointFlowView } from './api/joint-flow'
export type {
  JointFlowConfiguration, JointFlowConfigurationVersion, JointFlowConversion, JointFlowDimension, JointFlowDimensionKey, JointFlowFloorReference,
  JointFlowDimensionNode, JointFlowDimensionSource, JointFlowSelection,
} from './models/joint-flow-configuration'
export type {
  CarbonDenominatorVersion,
  CarbonFactorLifecycleRequest,
  CarbonFactorReviewRequest,
  CreateCarbonDenominatorRequest,
} from './api/carbon-factors'
export type {
  CalculationBatch,
  CalculationDetail,
  CarbonClassificationReport,
  MonthlyCalculationResult,
} from './api/carbon-results'
export type {
  HourlyCarbonDeclaredSourceNature,
  HourlyCarbonPoint,
  HourlyCarbonPointStatus,
  HourlyCarbonState,
  HourlyCarbonView,
} from './api/carbon-hourly'

/** 大屏只公开既有结果、冻结证据、动态监测和分类报告读取，不暴露计算、发布或审批写操作。 */
export const screenCarbonQueries = Object.freeze({
  getCalculation: carbonResultsApi.getCalculation,
  getEvidence: carbonResultsApi.getEvidence,
  getMonthlyResults: carbonResultsApi.getMonthlyResults,
  getClassificationReport: carbonResultsApi.getClassificationReport,
})

/** 大屏小时图只获取后端已裁决的监测结果，不能从此入口触发核算。 */
export const screenHourlyCarbonQueries = Object.freeze({
  getHourly: carbonHourlyApi.getHourly,
})
