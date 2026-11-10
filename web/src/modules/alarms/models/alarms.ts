export type AlarmCategory = 'DEVICE' | 'EMISSION'
export type AlarmRuleType = 'OFFLINE' | 'FAULT' | 'QUOTA' | 'QUALITY' | 'CONDITION' | 'JUMP' | 'STUCK' | 'EMISSION'
export type AlarmType = 'OFFLINE' | 'FAULT' | 'QUOTA' | 'QUALITY' | 'VALUE' | 'INTENSITY' | 'MOM' | 'YOY' | 'BASELINE' | 'COMPLETENESS' | 'FORECAST'
export type AlarmStatus = 'ACTIVE' | 'RECOVERED' | 'CLOSED'
export type QualityLevel = 'Q0' | 'Q1' | 'Q2'
export type RuleSeverity = 'URGENT' | 'IMPORTANT' | 'GENERAL' | 'INFO'
export type AlarmSeverity = RuleSeverity | 'WARNING'
export interface RuleOption { value: string; label: string }
export type DeviceClassification = 'OFFLINE' | 'QUALITY' | 'VALUE' | 'FAULT'
export type ConditionOperator = 'GT' | 'GE' | 'LT' | 'LE' | 'EQ' | 'NE' | 'BETWEEN' | 'OUTSIDE'
export interface AlarmStateOption { value: number; label: string }
export interface AlarmCondition { pointId: string; operator: ConditionOperator; value: number | null; upperValue?: number | null; states?: AlarmStateOption[] | null; dictionaryId?: string | null; dictionaryRevision?: number | null }
export interface AlarmPointOption extends RuleOption { unit: string; dataType: string; equipmentId: string; spaceId: string | null; deviceCategory: string; dictionaryId?: string | null; dictionaryRevision?: number | null; states?: AlarmStateOption[] }
export interface RuleOptions { spaces: RuleOption[]; deviceCategories: RuleOption[]; points?: AlarmPointOption[]; equipment?: RuleOption[] }

export interface AlarmGroup { type: AlarmType | DeviceClassification; count: number }
export interface AlarmSummary {
  deviceAnomalies: number | null
  emissionWarnings: number | null
  deviceGroups: AlarmGroup[]
  emissionGroups: AlarmGroup[]
  updatedAt: number
  period: string
  deviceReason: string | null
  emissionReason: string | null
}
export interface AlarmRule {
  id?: string
  name?: string | null
  qualityLevel?: QualityLevel | null
  severity?: RuleSeverity | null
  spaceId?: string | null
  deviceCategory?: string | null
  type: AlarmRuleType
  classification?: DeviceClassification | null
  recoveryCycles?: number
  triggerMinutes?: number
  recoveryMinutes?: number
  conditionLogic?: 'ALL' | 'ANY'
  advanced?: AlarmAdvanced | null
  conditions?: AlarmCondition[]
  enabled: boolean
  parameter: number
  revision: number
  updatedAt: number | null
  updatedBy: number | null
}
export interface AlarmRuleRevision extends Partial<Pick<AlarmRule, 'type' | 'classification' | 'recoveryCycles' | 'triggerMinutes' | 'recoveryMinutes' | 'conditions' | 'conditionLogic' | 'advanced'>> {
  name?: string
  severity?: RuleSeverity | null
  qualityLevel?: QualityLevel
  revision: number
  enabled: boolean
  parameter_value: number
  changed_at: number
  changed_by: number | null
}
export interface AlarmLog { at: number; actor: number | null; action: string; note: string | null }
export interface AlarmEvent {
  id: string
  buildingId: string
  category: AlarmCategory
  type: AlarmType
  objectId: string
  objectName: string
  spaceName: string | null
  period: string | null
  status: AlarmStatus
  severity: AlarmSeverity
  description: string
  firstAt: number
  lastAt: number
  closedAt?: number | null
  closedBy?: number | null
  recoveredAt: number | null
  ackBy: number | null
  ackAt: number | null
  silencedUntil: number | null
  ruleRevision: number
  triggerEvidence: string
  evidence: string
  logs?: AlarmLog[]
}
export interface AlarmEventPage { items: AlarmEvent[]; total: number; page: number; size: number }
export interface AlarmBuilding { buildingId: string; buildingName: string }


export interface AlarmConditionNode { logic?: 'ALL' | 'ANY' | null; children?: AlarmConditionNode[]; condition?: AlarmCondition | null }
export interface AlarmTiming { expectedIntervalSeconds?: number | null; allowedDelaySeconds?: number | null; alignmentSeconds?: number | null; staleAfterSeconds?: number | null }
export interface AlarmWindow { minutes: number; tolerance: number; changeMode: 'ABS' | 'PERCENT'; runningPointId?: string | null; runningValue?: number | null }
export type EmissionTemplate = 'QUOTA_PROGRESS' | 'INTENSITY' | 'MONTH_ON_MONTH' | 'YEAR_ON_YEAR' | 'BASELINE' | 'COMPLETENESS' | 'YEAR_END_FORECAST'
export interface EmissionSettings { template: string; period: 'MONTH' | 'YEAR'; threshold: number; recoveryThreshold: number; changeMode: 'ABS' | 'PERCENT'; baselineModelId?: string | null; forecastMethod?: 'LINEAR_DAY' | null; parameters?: Record<string,number | null> }
export interface AlarmAdvanced { primaryEquipmentId?: string | null; trigger?: AlarmConditionNode | null; recovery?: AlarmConditionNode | null; timing?: AlarmTiming | null; window?: AlarmWindow | null; emission?: EmissionSettings | null }
export interface AlarmDictionary { id: string; name: string; revision: number; states: AlarmStateOption[]; updatedAt: number; updatedBy: number | null }
export interface TrendSeries { pointId: string; pointName: string; unit: string; samples: { at: number; value: number; quality: number }[]; truncated: boolean; unavailableReason: string | null }

export interface AlarmTemplateDescriptor { code:string; label:string; periods:('MONTH'|'YEAR')[]; unit:string; requiredFields:string[]; professionalInputs:string[]; parameterLabels?:Record<string,string> }
