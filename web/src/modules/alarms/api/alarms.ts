import { requestApi } from '@/infrastructure/http/public'
import type { AlarmCategory, AlarmEvent, AlarmEventPage, AlarmRule, AlarmRuleRevision, RuleOptions, AlarmSummary, AlarmBuilding, AlarmDictionary, AlarmStateOption, TrendSeries, AlarmTemplateDescriptor } from '../models/alarms'

function buildingPath(buildingId: string): string {
  return `/v1/alarms/buildings/${encodeURIComponent(buildingId)}`
}

type RuleInput = Omit<AlarmRule, 'updatedAt' | 'updatedBy'>

function rulePayload({ type, enabled, parameter, revision, name, severity, qualityLevel, spaceId, deviceCategory, classification, recoveryCycles, triggerMinutes, recoveryMinutes, conditionLogic, conditions, advanced }: RuleInput) {
  return { type, enabled, parameter, revision, name, severity, qualityLevel, spaceId: spaceId || null, deviceCategory: deviceCategory || null, classification, recoveryCycles, triggerMinutes, recoveryMinutes, conditionLogic, conditions, advanced }
}

export const alarmsApi = {
  summary: (buildingId: string) => requestApi<AlarmSummary>({ method: 'get', url: `${buildingPath(buildingId)}/summary` }),
  events: (buildingId: string, options: { history: boolean; type: AlarmCategory; page: number; size: number; state?: 'UNACK' | 'ACK' | 'SILENCED' | 'ACTIVE' | 'RECOVERED' | 'CLOSED'; alarmType?: AlarmEvent['type']; keyword?: string; fromAt?: number; toAt?: number }) => requestApi<AlarmEventPage>({ method: 'get', url: `${buildingPath(buildingId)}/events`, params: { history: options.history, type: options.type, page: options.page, size: options.size, ...(options.state && (!options.history || ['RECOVERED','CLOSED'].includes(options.state)) ? { state: options.state } : {}), ...(options.alarmType ? { alarmType: options.alarmType } : {}), ...(options.keyword?.trim() ? { keyword: options.keyword.trim() } : {}), ...(options.fromAt != null ? { fromAt: options.fromAt } : {}), ...(options.toAt != null ? { toAt: options.toAt } : {}) } }),
  event: (buildingId: string, eventId: string) => requestApi<AlarmEvent>({ method: 'get', url: `${buildingPath(buildingId)}/events/${encodeURIComponent(eventId)}` }),
  rules: (buildingId: string) => requestApi<AlarmRule[]>({ method: 'get', url: `${buildingPath(buildingId)}/rules` }),
  ruleOptions: (buildingId: string) => requestApi<RuleOptions>({ method: 'get', url: `${buildingPath(buildingId)}/rules/options` }),
  ruleHistory: (buildingId: string, id: string) => requestApi<AlarmRuleRevision[]>({ method: 'get', url: `${buildingPath(buildingId)}/rules/${encodeURIComponent(id)}/history` }),
  createRule: (buildingId: string, rule: RuleInput) => requestApi<string>({ method: 'post', url: `${buildingPath(buildingId)}/rules`, data: rulePayload(rule) }),
  updateRule: (buildingId: string, rule: RuleInput) => requestApi<void>({ method: 'put', url: `${buildingPath(buildingId)}/rules/${encodeURIComponent(rule.id ?? rule.type)}`, data: rulePayload(rule) }),
  templates: (buildingId:string) => requestApi<AlarmTemplateDescriptor[]>({ method:'get', url:`${buildingPath(buildingId)}/rule-templates` }),
  dictionaries: (buildingId: string) => requestApi<AlarmDictionary[]>({ method: 'get', url: `${buildingPath(buildingId)}/state-dictionaries` }),
  createDictionary: (buildingId: string, pointId: string, name: string, states: AlarmStateOption[]) => requestApi<string>({ method: 'post', url: `${buildingPath(buildingId)}/points/${encodeURIComponent(pointId)}/state-dictionaries`, data: { name, states } }),
  updateDictionary: (buildingId: string, dictionary: AlarmDictionary) => requestApi<void>({ method: 'put', url: `${buildingPath(buildingId)}/state-dictionaries/${dictionary.id}`, data: { name: dictionary.name, revision: dictionary.revision, states: dictionary.states } }),
  bindDictionary: (buildingId: string, pointId: string, dictionary: AlarmDictionary) => requestApi<void>({ method: 'put', url: `${buildingPath(buildingId)}/points/${encodeURIComponent(pointId)}/state-dictionary`, data: { dictionaryId: dictionary.id, dictionaryRevision: dictionary.revision } }),
  applyDictionary: (buildingId: string, dictionary: AlarmDictionary) => requestApi<number>({ method: 'post', url: `${buildingPath(buildingId)}/state-dictionaries/${dictionary.id}/apply`, data: { dictionaryId: dictionary.id, dictionaryRevision: dictionary.revision } }),
  close: (buildingId: string, eventId: string, note: string) => requestApi<void>({ method: 'post', url: `${buildingPath(buildingId)}/events/${encodeURIComponent(eventId)}/close`, data: { note } }),
  trend: (buildingId: string, eventId: string, from: number, to: number) => requestApi<TrendSeries[]>({ method: 'get', url: `${buildingPath(buildingId)}/events/${encodeURIComponent(eventId)}/trend`, params: { from, to } }),
  acknowledge: (buildingId: string, eventId: string) => requestApi<void>({ method: 'post', url: `${buildingPath(buildingId)}/events/${encodeURIComponent(eventId)}/ack`, data: {} }),
  note: (buildingId: string, eventId: string, note: string) => requestApi<void>({ method: 'post', url: `${buildingPath(buildingId)}/events/${encodeURIComponent(eventId)}/note`, data: { note } }),
  silence: (buildingId: string, eventId: string, note: string, minutes = 60) => requestApi<void>({ method: 'post', url: `${buildingPath(buildingId)}/events/${encodeURIComponent(eventId)}/silence`, data: { minutes, note } }),
  buildings: (page: 'live' | 'history' | 'rules') => requestApi<AlarmBuilding[]>({ method: 'get', url: '/v1/alarms/buildings', params: { page } }),
}
