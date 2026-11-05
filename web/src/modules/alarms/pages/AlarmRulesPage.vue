<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElInput, ElInputNumber, ElOption, ElSelect, ElSkeleton, ElSwitch, ElMessage, Wrench, Leaf, Plus, Check, Settings2, Trash2 } from '@/shared/ui'
import { t } from '@/locales'
import { formatDateTime } from '@/shared/utils/format'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { useSession } from '@/modules/auth/public'
import { alarmsApi } from '../api/alarms'
import AlarmConditionTreeEditor from '../components/AlarmConditionTreeEditor.vue'
import type { AlarmTemplateDescriptor, AlarmAdvanced, AlarmConditionNode, AlarmDictionary, AlarmBuilding, AlarmRule, AlarmRuleRevision, AlarmRuleType as AlarmType, RuleOptions, RuleSeverity, QualityLevel, ConditionOperator, AlarmCondition } from '../models/alarms'

const route = useRoute()
const router = useRouter()
const session = useSession()
const text = (key: string, params: Record<string, string | number> = {}) => t(`alarms.${key}`, params)
const buildings = ref<AlarmBuilding[]>([])
const buildingId = ref('')
const view = ref<'DEVICE' | 'EMISSION'>('DEVICE')
const ruleStatus = ref<'ALL' | 'ENABLED' | 'DISABLED'>('ALL')
const rules = ref<AlarmRule[]>([])
const loading = ref(false)
const saving = ref('')
const error = ref('')
const historyError = ref('')
const historyType = ref('')
const historyRows = ref<AlarmRuleRevision[]>([])
const historyLoading = ref(false)
const editing = ref<AlarmRule | null>(null)
const drawerRef = ref<HTMLElement | null>(null)
const creating = ref(false)
const advancedMode = ref(false)
const sharedDictionaries = ref<AlarmDictionary[]>([])
const selectedDictionary = ref('')
const dictionaryName = ref('')
const dictionaryBusy = ref(false)
const stateEditingCondition = ref<AlarmCondition | null>(null)
const templateCatalog=ref<AlarmTemplateDescriptor[]>([])
const emissionDescriptor=computed(()=>templateCatalog.value.find(item=>item.code===editing.value?.advanced?.emission?.template))
const customParameters=computed(()=>emissionDescriptor.value?.requiredFields.filter(field=>field.startsWith('parameters.')).map(field=>field.slice('parameters.'.length)) ?? [])
function changeEmissionTemplate() {
  const settings=editing.value?.advanced?.emission
  if(!settings) return
  const descriptor=emissionDescriptor.value
  if(descriptor&&!descriptor.periods.includes(settings.period)) settings.period=descriptor.periods[0]
  settings.parameters={}
  settings.baselineModelId=null
  settings.forecastMethod=settings.template==='YEAR_END_FORECAST'?'LINEAR_DAY':null
  if(settings.template==='COMPLETENESS') { settings.threshold=90; settings.recoveryThreshold=95 }
  else if((settings.recoveryThreshold ?? 0)>(settings.threshold ?? 0)) settings.recoveryThreshold=settings.threshold
}
function setTemplateParameter(field:string,value:number|null|undefined) {
  const settings=editing.value!.advanced!.emission!
  settings.parameters ??= {}
  settings.parameters[field]=value ?? null
}

const isEmission = (rule: Pick<AlarmRule,'type'>) => ['QUOTA','EMISSION'].includes(rule.type)
function blankTree(): AlarmConditionNode { return { logic:'ALL',children:[{condition:{pointId:'',operator:'GT',value:null}}] } }
function ensureAdvanced(): AlarmAdvanced {
  const rule=editing.value!
  rule.advanced ??= { timing:{},primaryEquipmentId:null }
  rule.advanced.timing ??= {}
  return rule.advanced
}
function enableAdvanced(enabled: boolean) {
  advancedMode.value=enabled
  if(!enabled) { editing.value!.advanced=null; return }
  const advanced=ensureAdvanced()
  advanced.trigger ??= {logic:editing.value!.conditionLogic ?? 'ALL',children:(editing.value!.conditions?.length?editing.value!.conditions:[{pointId:'',operator:'GT' as const,value:null}]).map(condition=>({condition:{...condition}}))}
}
function leaves(node?: AlarmConditionNode | null): AlarmCondition[] {
  return node?.condition?[node.condition]:(node?.children ?? []).flatMap(leaves)
}
function updateTree(kind: 'trigger'|'recovery',node: AlarmConditionNode) {
  ensureAdvanced()[kind]=node
  stateEditingIndex.value=null
}
function openTreeState(condition: AlarmCondition) {
  stateEditingCondition.value=condition
  openStateEditor(-1)
}
const stateCondition = computed(() => stateEditingIndex.value == null ? null : stateEditingIndex.value < 0 ? stateEditingCondition.value : editing.value?.conditions?.[stateEditingIndex.value])
async function loadDictionaries() {
  sharedDictionaries.value=await alarmsApi.dictionaries(buildingId.value)
}
function selectDictionary() {
  const dictionary=sharedDictionaries.value.find(item=>item.id===selectedDictionary.value)
  if(dictionary) { dictionaryName.value=dictionary.name; stateDraft.value=dictionary.states.map(state=>({...state})) }
}
async function useDictionary() {
  const condition=stateCondition.value
  const dictionary=sharedDictionaries.value.find(item=>item.id===selectedDictionary.value)
  if(!condition||!dictionary) return
  dictionaryBusy.value=true
  try {
    await alarmsApi.bindDictionary(buildingId.value,condition.pointId,dictionary)
    condition.states=dictionary.states.map(state=>({...state}))
    condition.dictionaryId=dictionary.id; condition.dictionaryRevision=dictionary.revision
    if(!condition.states.some(state=>state.value===condition.value)) condition.value=null
    options.value=await alarmsApi.ruleOptions(buildingId.value)
    stateEditingIndex.value=null
  } catch(reason) { error.value=requestErrorMessage(reason) }
  finally { dictionaryBusy.value=false }
}
async function saveShared(update: boolean) {
  const condition=stateCondition.value
  if(!condition||!validStateDraft.value||!dictionaryName.value.trim()) return
  dictionaryBusy.value=true
  try {
    const states=stateDraft.value.map(state=>({value:state.value!,label:state.label.trim()}))
    const current=sharedDictionaries.value.find(item=>item.id===selectedDictionary.value)
    if(update&&current) await alarmsApi.updateDictionary(buildingId.value,{...current,name:dictionaryName.value.trim(),states})
    else selectedDictionary.value=await alarmsApi.createDictionary(buildingId.value,condition.pointId,dictionaryName.value.trim(),states)
    await loadDictionaries()
    const dictionary=sharedDictionaries.value.find(item=>item.id===selectedDictionary.value)
    if(dictionary) {
      await alarmsApi.bindDictionary(buildingId.value,condition.pointId,dictionary)
      condition.states=dictionary.states.map(state=>({...state})); condition.dictionaryId=dictionary.id; condition.dictionaryRevision=dictionary.revision
      if(!condition.states.some(state=>state.value===condition.value)) condition.value=null
    }
    options.value=await alarmsApi.ruleOptions(buildingId.value)
    stateEditingIndex.value=null; ElMessage.success(text('sharedApplied'))
  } catch(reason) { error.value=requestErrorMessage(reason) }
  finally { dictionaryBusy.value=false }
}
async function syncDictionary() {
  const dictionary=sharedDictionaries.value.find(item=>item.id===selectedDictionary.value)
  if(!dictionary) return
  dictionaryBusy.value=true
  try { await alarmsApi.applyDictionary(buildingId.value,dictionary); ElMessage.success(text('sharedApplied')) }
  catch(reason) {error.value=requestErrorMessage(reason)}
  finally {dictionaryBusy.value=false}
}
const stateEditingIndex = ref<number | null>(null)
const stateDraft = ref<{ value: number | null; label: string }[]>([])
function validStates(states: { value: number | null; label: string }[]) {
  return states.length > 0 && states.length <= 32
    && states.every(state => state.value != null && Number.isFinite(state.value) && state.label.trim().length > 0 && state.label.trim().length <= 40)
    && new Set(states.map(state => state.value)).size === states.length
    && new Set(states.map(state => state.label.trim())).size === states.length
}
const validStateDraft = computed(() => validStates(stateDraft.value))
function openStateEditor(index: number) {
  stateEditingIndex.value = index
  const condition=index < 0?stateEditingCondition.value:editing.value?.conditions?.[index]
  const states=condition?.states
  selectedDictionary.value=condition?.dictionaryId ?? ''
  dictionaryName.value=pointMetadata(condition?.pointId ?? '')?.label ?? ''
  void loadDictionaries().catch(reason=>{error.value=requestErrorMessage(reason)})
  stateDraft.value = states?.length ? states.map(state => ({ ...state })) : [{ value: null, label: '' }, { value: null, label: '' }]
}
function applyStates() {
  const condition = stateCondition.value
  if (!condition || !validStateDraft.value) return
  condition.dictionaryId=null; condition.dictionaryRevision=null
  condition.states = stateDraft.value.map(state => ({ value: state.value!, label: state.label.trim() }))
  // 未覆盖旧编码时必须重新选择，避免维护中文名称时意外改变判断值。
  if (!condition.states.some(state => state.value === condition.value)) condition.value = null
  if (!['EQ', 'NE'].includes(condition.operator)) condition.operator = 'EQ'
  condition.upperValue = null
  stateEditingIndex.value = null
}
function selectedState(condition: AlarmCondition) {
  return condition.states?.some(state => state.value === condition.value) ? condition.value : undefined
}
function removeCondition(index: number) {
  editing.value?.conditions?.splice(index, 1)
  stateEditingIndex.value = null
}
const options = ref<RuleOptions>({ spaces: [], deviceCategories: [] })
const categoryFilter = ref('')
const spaceFilter = ref('')
const severityLevels: RuleSeverity[] = ['URGENT', 'IMPORTANT', 'GENERAL', 'INFO']
const defaultQuality = { JUMP: 'Q0', STUCK: 'Q0', EMISSION: 'Q0', OFFLINE: 'Q2', FAULT: 'Q1', QUOTA: 'Q0', QUALITY: 'Q0', CONDITION: 'Q1' } as const
const qualities: QualityLevel[] = ['Q2', 'Q1', 'Q0']
const qualityLabels = { Q2: 'baseQuality', Q1: 'monitorQuality', Q0: 'formalQuality' } as const
const qualityDescriptions = { Q2: 'baseChoice', Q1: 'monitorChoice', Q0: 'formalChoice' } as const
const quality = (rule: Pick<AlarmRule, 'type' | 'qualityLevel'>) => rule.qualityLevel ?? defaultQuality[rule.type]
const qualityHelp = { JUMP:'windowHelp', STUCK:'windowHelp', EMISSION:'emissionHelp', OFFLINE: 'baseHelp', FAULT: 'monitorHelp', QUOTA: 'formalHelp', QUALITY: 'qualitySource', CONDITION: 'conditionSource' } as const
const ruleKey = (rule: AlarmRule) => rule.id ?? rule.type
const editorTitle = computed(() => text(creating.value
  ? (view.value === 'DEVICE' ? 'createDevice' : 'createEmission')
  : (view.value === 'DEVICE' ? 'editDevice' : 'editEmission')))
const pointRules = new Set<AlarmType>(['QUALITY', 'CONDITION','JUMP','STUCK'])
const operators: ConditionOperator[] = ['GT', 'GE', 'LT', 'LE', 'EQ', 'NE', 'BETWEEN', 'OUTSIDE']
const availablePoints = computed(() => (options.value.points ?? []).filter(point => {
  const draft = editing.value
  return draft && (!draft.spaceId || draft.spaceId === point.spaceId) && (!draft.deviceCategory || draft.deviceCategory === point.deviceCategory)
}))
function pointOptions(condition: AlarmCondition) {
  const firstId = editing.value?.conditions?.find(item => item !== condition && item.pointId)?.pointId
  const equipment = options.value.points?.find(point => point.value === firstId)?.equipmentId
  return availablePoints.value.filter(point => !equipment || point.equipmentId === equipment)
}
function changePoint(condition: AlarmCondition) {
  condition.states = pointMetadata(condition.pointId)?.states?.map(state=>({...state})) ?? []
  condition.dictionaryId=pointMetadata(condition.pointId)?.dictionaryId
  condition.dictionaryRevision=pointMetadata(condition.pointId)?.dictionaryRevision
  stateEditingIndex.value = null
  if (pointMetadata(condition.pointId)?.dataType === 'DIGITAL') {
    condition.operator = 'EQ'
    condition.value = null
    condition.upperValue = null
  }
}
function pointMetadata(id: string) { return options.value.points?.find(point => point.value === id) }
function addCondition() { editing.value?.conditions?.push({ pointId: '', operator: 'GT', value: 0, upperValue: null }) }
function validCondition(condition: AlarmCondition) {
  if (!condition.pointId || !availablePoints.value.some(point => point.value === condition.pointId)) return false
  if (editing.value?.type === 'QUALITY') return true
  if (condition.value == null || !Number.isFinite(condition.value)) return false
  if (pointMetadata(condition.pointId)?.dataType === 'DIGITAL') {
    return validStates(condition.states ?? []) && condition.states!.some(state => state.value === condition.value) && ['EQ','NE'].includes(condition.operator)
  }
  return !['BETWEEN', 'OUTSIDE'].includes(condition.operator) || (condition.upperValue != null && Number.isFinite(condition.upperValue) && condition.upperValue > condition.value)
}
const validDraft = computed(() => {
  const draft = editing.value
  if (stateEditingIndex.value != null) return false
  if (!draft?.name?.trim() || !Number.isInteger(draft.parameter) || draft.parameter < 1 || draft.parameter > 100) return false
  if (draft.type === 'OFFLINE') return Number.isInteger(draft.recoveryCycles) && draft.recoveryCycles! >= 1 && draft.recoveryCycles! <= 100
  if(draft.type==='EMISSION') return !!draft.advanced?.emission && draft.advanced.emission.threshold!=null && draft.advanced.emission.recoveryThreshold!=null
  if(!pointRules.has(draft.type)) return true
  if(advancedMode.value&&draft.advanced?.trigger) return !!draft.advanced.primaryEquipmentId && leaves(draft.advanced.trigger).length>0
    && leaves(draft.advanced.trigger).length+leaves(draft.advanced.recovery).length<=20
    && [...leaves(draft.advanced.trigger),...leaves(draft.advanced.recovery)].every(condition=>condition.pointId&&condition.value!=null)
  if(['JUMP','STUCK'].includes(draft.type)) return !!draft.conditions?.[0]?.pointId && !!draft.advanced?.window?.minutes
    && (draft.type!=='STUCK'||!!draft.advanced.window.runningPointId&&draft.advanced.window.runningValue!=null)
  const conditions = draft.conditions ?? []
  const devices = new Set(conditions.map(item => pointMetadata(item.pointId)?.equipmentId))
  return conditions.length > 0 && conditions.length <= 10 && conditions.every(validCondition) && devices.size === 1
    && Number.isInteger(draft.triggerMinutes) && draft.triggerMinutes! >= 0 && draft.triggerMinutes! <= 10080
    && Number.isInteger(draft.recoveryMinutes) && draft.recoveryMinutes! >= 0 && draft.recoveryMinutes! <= 10080
})
const drawerClose = ref<HTMLButtonElement | null>(null)
const canManage = computed(() => session.user?.roles.some(role => role === 'ENERGY_MANAGER' || role === 'PLATFORM_ADMIN') === true)
const categoryRules = computed(() => rules.value.filter(rule => view.value === 'EMISSION' ? isEmission(rule) : !isEmission(rule)))
const visibleRules = computed(() => categoryRules.value.filter(rule => (ruleStatus.value === 'ALL' || (ruleStatus.value === 'ENABLED' ? rule.enabled : !rule.enabled)) && (view.value === 'EMISSION' || ((!categoryFilter.value || rule.deviceCategory === categoryFilter.value) && (!spaceFilter.value || rule.spaceId === spaceFilter.value)))))
const selectedBuildingName = computed(() => buildings.value.find(item => item.buildingId === buildingId.value)?.buildingName ?? '')
const deviceEnabledCount = computed(() => rules.value.filter(rule => !isEmission(rule) && rule.enabled).length)
const emissionEnabledCount = computed(() => rules.value.filter(rule => isEmission(rule) && rule.enabled).length)
const labels: Record<AlarmType, string> = { JUMP:text('JUMP_label'), STUCK:text('STUCK_label'), EMISSION:text('EMISSION_label'), OFFLINE: text('OFFLINE_label'), FAULT: text('FAULT_label'), QUOTA: text('QUOTA_label'), QUALITY: text('QUALITY_label'), CONDITION: text('CONDITION_label') }
function scopeName(rule: AlarmRule) {
  const building = selectedBuildingName.value
  const space = options.value.spaces.find(item => item.value === rule.spaceId)?.label ?? rule.spaceId
  return space ? text('scopeName', { building, space }) : text('allBuilding', { building })
}
function categoryName(rule: AlarmRule) {
  if (isEmission(rule)) return text('buildingMonthlyScope')
  return options.value.deviceCategories.find(item => item.value === rule.deviceCategory)?.label ?? rule.deviceCategory ?? text('monitoredDevices')
}
function level(rule: AlarmRule) { return rule.severity ?? (rule.type === 'QUOTA' ? 'dynamicLevel' : 'IMPORTANT') }
watch(buildingId, () => { categoryFilter.value = ''; spaceFilter.value = '' })
let generation = 0
let historyGeneration = 0
let returnFocus: HTMLElement | null = null

onMounted(() => { void loadBuildings() })
watch(() => route.query.buildingId, value => {
  const id = typeof value === 'string' ? value : ''
  if (id && id !== buildingId.value && buildings.value.some(item => item.buildingId === id)) {
    buildingId.value = id; historyGeneration++; historyRows.value = []; historyType.value = ''; historyLoading.value = false; editing.value = null; saving.value = ''; void loadRules(id)
  }
})
onUnmounted(() => { generation++; historyGeneration++ })

async function loadBuildings() {
  const owner = ++generation; loading.value = true; error.value = ''; rules.value = []
  try {
    const response = await alarmsApi.buildings('rules')
    if (owner !== generation) return
    buildings.value = response
    const queryId = typeof route.query.buildingId === 'string' ? route.query.buildingId : ''
    const selected = response.some(item => item.buildingId === queryId) ? queryId : response[0]?.buildingId ?? ''
    buildingId.value = selected
    if (selected && selected !== queryId) await router.replace({ path: route.path, query: { ...route.query, buildingId: selected } })
    if (selected && owner === generation) await fetchRules(selected, owner)
  } catch (reason) { if (owner === generation) { buildings.value = []; buildingId.value = ''; rules.value = []; error.value = requestErrorMessage(reason) } }
  finally { if (owner === generation) loading.value = false }
}

async function changeBuilding(id: string) {
  if (!id || id === buildingId.value) return
  const owner = ++generation; historyGeneration++; buildingId.value = id; rules.value = []; historyRows.value = []; historyType.value = ''; historyLoading.value = false; editing.value = null; saving.value = ''; error.value = ''; loading.value = true
  await router.replace({ path: route.path, query: { ...route.query, buildingId: id } })
  await fetchRules(id, owner)
  if (owner === generation) loading.value = false
}
async function loadRules(id = buildingId.value) {
  if (!id) return
  const owner = ++generation; loading.value = true; rules.value = []; error.value = ''
  await fetchRules(id, owner)
  if (owner === generation) loading.value = false
}
async function fetchRules(id: string, owner: number) {
  try {
    const [rows, choices, templates] = await Promise.all([alarmsApi.rules(id), alarmsApi.ruleOptions(id),alarmsApi.templates(id)])
    if (owner === generation && id === buildingId.value) { rules.value = rows; options.value = choices; templateCatalog.value=templates }
  }
  catch (reason) { if (owner === generation && id === buildingId.value) { rules.value = []; error.value = requestErrorMessage(reason) } }
}

async function toggleRule(rule: AlarmRule, enabled: boolean) {
  if (!canManage.value || saving.value || !buildingId.value) return
  const buildingAtSave = buildingId.value
  const owner = generation
  const previous = rule.enabled
  rule.enabled = enabled
  saving.value = ruleKey(rule); error.value = ''
  try {
    await alarmsApi.updateRule(buildingAtSave, { ...rule, enabled })
    if (owner !== generation || buildingAtSave !== buildingId.value) return
    ElMessage.success(text('saved'))
    saving.value = ''
    await loadRules(buildingAtSave)
  } catch (reason) { if (owner === generation) { rule.enabled = previous; error.value = requestErrorMessage(reason) } }
  finally { if (owner === generation) saving.value = '' }
}
async function openEditor(source?: AlarmRule) {
  if (!canManage.value || !buildingId.value || loading.value || saving.value) return
  returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  creating.value = !source
  advancedMode.value=!!source?.advanced?.trigger
  stateEditingIndex.value = null
  const type = view.value === 'DEVICE' ? 'OFFLINE' : 'QUOTA'
  editing.value = source ? { ...source, advanced:source.advanced?{...JSON.parse(JSON.stringify(source.advanced)),timing:source.advanced.timing??{}}:null, recoveryCycles: source.recoveryCycles ?? 1, triggerMinutes: source.triggerMinutes ?? 0, recoveryMinutes: source.recoveryMinutes ?? 0, conditionLogic: source.conditionLogic ?? 'ALL', conditions: source.conditions?.map(item => ({ ...item, states: item.states?.map(state => ({ ...state })) })) ?? [], name: source.name || labels[source.type], qualityLevel: quality(source), severity: source.severity ?? (isEmission(source) ? null : 'IMPORTANT') }
    : { type, name: '', recoveryCycles: 2, triggerMinutes: 0, recoveryMinutes: 0, conditionLogic: 'ALL', conditions: [], classification: 'OFFLINE', qualityLevel: defaultQuality[type], severity: type === 'QUOTA' ? null : 'IMPORTANT', enabled: true, parameter: type === 'QUOTA' ? 85 : 3, revision: 0, updatedAt: null, updatedBy: null }
  await nextTick(); drawerClose.value?.focus()
}
function changeType(type: AlarmType) {
  if (!editing.value || !creating.value) return
  stateEditingIndex.value = null
  editing.value.advanced=null; advancedMode.value=false
  editing.value.type = type
  editing.value.qualityLevel = defaultQuality[type]
  editing.value.parameter = type === 'OFFLINE' ? 3 : 1
  editing.value.recoveryCycles = type === 'OFFLINE' ? 2 : 1
  editing.value.classification = ['CONDITION','JUMP','STUCK'].includes(type) ? 'VALUE' : ['QUOTA','EMISSION'].includes(type) ? null : type as AlarmRule['classification']
  editing.value.conditions = pointRules.has(type) ? [{ pointId: '', operator: type === 'QUALITY' ? 'EQ' : 'GT', value: 0 }] : []
  if(['JUMP','STUCK'].includes(type)) editing.value.advanced={primaryEquipmentId:null,timing:{},window:{minutes:15,tolerance:0,changeMode:'ABS',runningPointId:null,runningValue:null}}
  if(type==='EMISSION') {
    editing.value.severity=null
    editing.value.advanced={emission:{template:'QUOTA_PROGRESS',period:'MONTH',threshold:85,recoveryThreshold:80,changeMode:'PERCENT'}}
  }

}
function closeEditor() { editing.value = null; stateEditingIndex.value = null; nextTick(() => returnFocus?.focus()) }
async function saveEdit() {
  const rule = editing.value
  if (!rule || !canManage.value || saving.value || !validDraft.value) return
  const buildingAtSave = buildingId.value
  const owner = generation
  saving.value = ruleKey(rule); error.value = ''
  try {
    const payload = { ...rule, name: rule.name?.trim() }
    if(advancedMode.value&&rule.advanced?.trigger) payload.conditions=[]
    if (creating.value) await alarmsApi.createRule(buildingAtSave, payload)
    else await alarmsApi.updateRule(buildingAtSave, payload)
    if (owner !== generation || buildingAtSave !== buildingId.value) return
    ElMessage.success(text('saved')); saving.value = ''; closeEditor(); await loadRules(buildingAtSave)
  } catch (reason) { if (owner === generation) error.value = requestErrorMessage(reason) }
  finally { if (owner === generation) saving.value = '' }
}
async function toggleHistory(rule: AlarmRule) {
  if (historyType.value === ruleKey(rule)) { historyType.value = ''; historyRows.value = []; historyGeneration++; return }
  const owner = ++historyGeneration; historyType.value = ruleKey(rule); historyRows.value = []; historyError.value = ''; historyLoading.value = true
  try { const response = await alarmsApi.ruleHistory(buildingId.value, ruleKey(rule)); if (owner === historyGeneration && historyType.value === ruleKey(rule)) historyRows.value = response }
  catch (reason) { if (owner === historyGeneration) historyError.value = requestErrorMessage(reason) }
  finally { if (owner === historyGeneration) historyLoading.value = false }
}
function keydown(event: KeyboardEvent) {
  if (!editing.value) return
  if (event.key === 'Escape') { if (stateEditingIndex.value != null) stateEditingIndex.value = null; else closeEditor(); return }
  if (event.key !== 'Tab' || !drawerRef.value) return
  const targets = [...drawerRef.value.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),[tabindex="0"]')]
  const first = targets[0], last = targets.at(-1)
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
onMounted(() => window.addEventListener('keydown', keydown))
onUnmounted(() => window.removeEventListener('keydown', keydown))
function formatChangedAt(value: number | null) { return value == null ? t('common.missing') : formatDateTime(value) }
function triggerCondition(rule: Pick<AlarmRule, 'type' | 'parameter'> & Partial<AlarmRule>) {
  if(rule.type==='EMISSION' && rule.advanced?.emission) return (templateCatalog.value.find(item=>item.code===rule.advanced?.emission?.template)?.label ?? rule.name ?? text('EMISSION_label'))+text('glyphSeparator')+rule.advanced.emission.threshold+text('glyphSlash')+rule.advanced.emission.recoveryThreshold
  if(['JUMP','STUCK'].includes(rule.type)) return labels[rule.type]+text('glyphSeparator')+(rule.advanced?.window?.minutes ?? '—')+text('minutesSuffix')
  if(rule.advanced?.trigger) return text('treePreview',{condition:treeText(rule.advanced.trigger),trigger:rule.triggerMinutes??0,
    recovery:rule.advanced.recovery?treeText(rule.advanced.recovery):text('implicitRecovery'),cycles:rule.recoveryCycles??1,minutes:rule.recoveryMinutes??0})
  if (rule.type === 'OFFLINE') return text('offlineRuleDescription', { parameter: rule.parameter, cycles: rule.recoveryCycles ?? 1 })
  if (rule.type === 'QUOTA') return text('quotaCondition', { parameter: rule.parameter })
  if (rule.type === 'QUALITY') return text('qualityRuleDescription', { quality: text(qualityLabels[quality(rule)]), trigger: rule.triggerMinutes ?? 0, recovery: rule.recoveryMinutes ?? 0 })
  if (rule.type === 'CONDITION') {
    const conditions = (rule.conditions ?? []).map(item => [pointMetadata(item.pointId)?.label ?? item.pointId, text(item.operator), item.states?.find(state => state.value === item.value)?.label ?? item.value, item.upperValue ?? '', item.states?.length ? '' : pointMetadata(item.pointId)?.unit ?? ''].join(' '))
    return conditions.join(text(rule.conditionLogic === 'ANY' ? 'ANY' : 'ALL')) + text('conditionTiming', { trigger: rule.triggerMinutes ?? 0, recovery: rule.recoveryMinutes ?? 0 })
  }
  return text('fixedFaultParameter') + text('glyphSeparator') + text('faultRecovery')
}
function treeText(node:AlarmConditionNode):string {
  const condition=node.condition
  if(condition) return [pointMetadata(condition.pointId)?.label ?? condition.pointId,text(condition.operator),condition.states?.find(state=>state.value===condition.value)?.label ?? condition.value,
    condition.upperValue ?? '',condition.states?.length?'':pointMetadata(condition.pointId)?.unit ?? ''].join(' ')
  return text('treeGroup',{value:(node.children ?? []).map(treeText).join(text(node.logic==='ANY'?'treeOr':'treeAnd'))})
}
</script>

<template>
  <section class="rules-page" :class="{ 'drawer-open': editing }">
    <div class="rules-surface">
      <header class="page-heading">
        <div><h1>{{ text('ruleTitle') }}</h1><p>{{ text('ruleSubtitle') }}</p></div>
      </header>
      <ElAlert v-if="!canManage" :title="text('permission')" type="info" :closable="false" />
      <ElAlert v-if="error" :title="text('loadFailed')" :description="error" type="error" show-icon />
      <div class="rule-tabs" role="tablist">
        <button type="button" role="tab" :aria-selected="view === 'DEVICE'" :class="{ active: view === 'DEVICE' }" @click="view = 'DEVICE'"><Wrench aria-hidden="true" class="device-icon" />{{ text('deviceRules') }} <strong>{{ text('enabledRules', { count: deviceEnabledCount }) }}</strong></button>
        <button type="button" role="tab" :aria-selected="view === 'EMISSION'" :class="{ active: view === 'EMISSION' }" @click="view = 'EMISSION'"><Leaf aria-hidden="true" class="emission-icon" />{{ text('emissionRules') }} <strong>{{ text('enabledRules', { count: emissionEnabledCount }) }}</strong></button>
      </div>
      <div class="rule-filters">
        <label><span>{{ text('building') }}</span><ElSelect :model-value="buildingId" :disabled="!buildings.length" :placeholder="text('chooseBuilding')" @change="changeBuilding"><ElOption v-for="building in buildings" :key="building.buildingId" :label="building.buildingName" :value="building.buildingId" /></ElSelect></label>
        <label v-if="view === 'DEVICE'"><span>{{ text('deviceCategory') }}</span><ElSelect v-model="categoryFilter" :placeholder="text('allCategories')"><ElOption :label="text('allCategories')" value="" /><ElOption v-for="item in options.deviceCategories" :key="item.value" :label="item.label" :value="item.value" /></ElSelect></label>
        <label v-if="view === 'DEVICE'"><span>{{ text('applicableSpace') }}</span><ElSelect v-model="spaceFilter" :placeholder="text('allSpaces')"><ElOption :label="text('allSpaces')" value="" /><ElOption v-for="item in options.spaces" :key="item.value" :label="item.label" :value="item.value" /></ElSelect></label>
        <label><span>{{ text('ruleStatus') }}</span><ElSelect v-model="ruleStatus"><ElOption :label="text('allRuleStatuses')" value="ALL" /><ElOption :label="text('enabledOnly')" value="ENABLED" /><ElOption :label="text('disabledOnly')" value="DISABLED" /></ElSelect></label>
        <ElButton type="primary" :disabled="!canManage || !buildingId || loading || !!saving || !!error" @click="openEditor()"><Plus aria-hidden="true" />{{ text(view === 'DEVICE' ? 'createDevice' : 'createEmission') }}</ElButton>
      </div>
      <ElSkeleton v-if="loading" :rows="4" animated />
      <div v-else-if="visibleRules.length" class="rule-table">
        <div class="rule-header"><span>{{ text('listRuleName') }}</span><span>{{ text('listScope') }}</span><span>{{ text('listConditions') }}</span><span>{{ text('severity') }}</span><span>{{ text('enabled') }}</span><span>{{ text('action') }}</span></div>
        <article v-for="rule in visibleRules" :key="ruleKey(rule)" class="rule-article" :class="{ selected: editing && !creating && ruleKey(editing) === ruleKey(rule) }">
          <div class="rule-row">
            <div class="rule-name"><strong>{{ rule.name || labels[rule.type] }}</strong><small>{{ text(rule.type === 'CONDITION' ? (rule.classification === 'FAULT' ? 'classificationFault' : 'classificationValue') : rule.type) }}</small></div>
            <div class="scope-value"><strong>{{ categoryName(rule) }}{{ text('glyphSeparator') }}{{ scopeName(rule) }}</strong><small>{{ text('scopeQualityLabel') }} <span class="pill" :class="quality(rule)">{{ text(qualityLabels[quality(rule)]) }}</span></small></div>
            <div class="trigger-value">{{ triggerCondition(rule) }}</div>
            <span class="pill" :class="level(rule)">{{ text(level(rule)) }}</span>
            <ElSwitch :model-value="rule.enabled" :disabled="!canManage || saving !== ''" :loading="saving === ruleKey(rule)" :aria-label="(rule.name || labels[rule.type]) + text('enabled')" @change="toggleRule(rule, Boolean($event))" />
            <div class="row-actions"><ElButton text type="primary" :disabled="!canManage || saving !== ''" @click="openEditor(rule)">{{ text('edit') }}</ElButton><ElButton text type="primary" :loading="historyLoading && historyType === ruleKey(rule)" @click="toggleHistory(rule)">{{ historyType === ruleKey(rule) ? text('hideRecords') : text('records') }}</ElButton></div>
          </div>
          <section v-if="historyType === ruleKey(rule)" class="history-panel" :aria-label="text('records')">
            <ElSkeleton v-if="historyLoading" :rows="2" animated />
            <p v-else-if="historyError" class="history-error">{{ historyError }}</p>
            <p v-else-if="!historyRows.length" class="history-empty">{{ text('emptyHistory') }}</p>
            <ol v-else class="history-list"><li v-for="(item, index) in historyRows" :key="`${item.revision}-${index}`"><span class="timeline-dot" aria-hidden="true"></span><div class="history-record"><strong>{{ text('revision') }} {{ item.revision }}</strong><span v-if="item.name">{{ item.name }} <span v-if="item.severity" class="pill" :class="item.severity">{{ text(item.severity) }}</span> <span v-if="item.qualityLevel" class="pill" :class="item.qualityLevel">{{ text(qualityLabels[item.qualityLevel]) }}</span></span><span>{{ item.enabled ? text('enabledValue') : text('disabledValue') }}{{ text('glyphSeparator') }}{{ text('parameterValue') }}{{ text('glyphColon') }}{{ item.parameter_value }}</span><span v-if="item.type">{{ triggerCondition({ ...item, type: item.type, parameter: item.parameter_value }) }}</span><small>{{ text('changedAt') }}{{ text('glyphColon') }}{{ formatChangedAt(item.changed_at) }}{{ text('glyphSeparator') }}{{ text('updatedBy') }}{{ text('glyphColon') }}{{ item.changed_by ?? t('common.missing') }}</small></div></li></ol>
          </section>
        </article>
      </div>
      <p v-else-if="!loading && !error" class="empty-rules">{{ text(categoryRules.length ? 'noMatch' : 'emptyRules') }}</p>
    </div>

    <div v-if="editing" class="drawer-overlay" @click.self="closeEditor">
      <aside ref="drawerRef" class="rule-drawer" role="dialog" aria-modal="true" :aria-label="editorTitle" tabindex="-1">
        <header class="drawer-heading">
          <div><h2>{{ editorTitle }}</h2><span class="immediate-badge">{{ text('immediateBadge') }}</span></div>
          <button ref="drawerClose" type="button" :aria-label="text('closed')" @click="closeEditor">{{ text('glyphClose') }}</button>
        </header>
        <div class="drawer-content">
          <section class="editor-section">
            <div class="step-heading"><b>{{ text('stepOne') }}</b><h3>{{ text('identificationStep') }}</h3></div>
            <dl class="rule-identity">
              <div><dt>{{ text('ruleTypeLabel') }}</dt><dd><ElSelect :model-value="editing.type" :disabled="!creating" @change="changeType"><ElOption v-for="kind in view === 'DEVICE' ? ['OFFLINE','QUALITY','CONDITION','JUMP','STUCK','FAULT'] : ['QUOTA','EMISSION']" :key="kind" :label="text(kind + '_method')" :value="kind" /></ElSelect></dd></div>
              <div v-if="editing.type === 'CONDITION'"><dt>{{ text('classification') }}</dt><dd><ElSelect v-model="editing.classification"><ElOption value="VALUE" :label="text('classificationValue')" /><ElOption value="FAULT" :label="text('classificationFault')" /></ElSelect></dd></div>
              <div><dt>{{ text('ruleName') }}</dt><dd><ElInput v-model="editing.name" :aria-label="text('ruleName')" :placeholder="text('ruleNamePlaceholder')" maxlength="100" /></dd></div>
              <div>
                <dt>{{ text('selectSeverity') }}</dt><dd class="severity-choices">
                  <button v-if="isEmission(editing)" type="button" class="pill dynamicLevel" :aria-pressed="!editing.severity" @click="editing.severity = null">{{ text('autoLevel') }}</button>
                  <button v-for="severity in isEmission(editing) ? [] : severityLevels" :key="severity" type="button" class="pill" :class="severity" :aria-pressed="editing.severity === severity" @click="editing.severity = severity"><Check v-if="editing.severity === severity" aria-hidden="true" />{{ text(severity) }}</button>
                </dd>
              </div>
            </dl>
          </section>
          <section class="editor-section">
            <div class="step-heading"><b>{{ text('stepTwo') }}</b><h3>{{ text('scopeStep') }}</h3></div>
            <dl class="rule-identity">
              <div><dt>{{ text('scopeBuilding') }}</dt><dd><ElSelect v-if="!isEmission(editing)" :placeholder="scopeName(editing)" :model-value="editing.spaceId || ''" @change="editing.spaceId = $event || null"><ElOption :label="text('allBuilding', { building: selectedBuildingName })" value="" /><ElOption v-for="item in options.spaces" :key="item.value" :label="text('scopeName', { building: selectedBuildingName, space: item.label })" :value="item.value" /></ElSelect><span v-else>{{ scopeName(editing) }}</span></dd></div>
              <div><dt>{{ text('scopeDevices') }}</dt><dd><ElSelect v-if="!isEmission(editing)" :placeholder="categoryName(editing)" :model-value="editing.deviceCategory || ''" @change="editing.deviceCategory = $event || null"><ElOption :label="text('monitoredDevices')" value="" /><ElOption v-for="item in options.deviceCategories" :key="item.value" :label="item.label" :value="item.value" /></ElSelect><span v-else>{{ text(editing.type==='EMISSION'?'buildingPeriodScope':'buildingMonthlyScope') }}</span></dd></div>
              <div>
                <dt>{{ text('scopeQuality') }}</dt><dd class="quality-options">
                  <label v-for="grade in qualities" :key="grade" :class="{ current: quality(editing) === grade }"><input v-model="editing.qualityLevel" type="radio" name="scene-quality" :value="grade"><b :class="grade">{{ text(qualityLabels[grade]) }}</b> {{ text(qualityDescriptions[grade]) }}</label>
                  <small>{{ text('qualityRuleHint') }}{{ text('actualInput') }}{{ text('glyphColon') }}{{ text(advancedMode ? 'treeHelp' : qualityHelp[editing.type]) }}</small>
                </dd>
              </div>
            </dl>
          </section>
          <section v-if="['CONDITION','QUALITY','JUMP','STUCK'].includes(editing.type)" class="advanced-controls">
            <label v-if="editing.type === 'CONDITION'"><ElSwitch :model-value="advancedMode" @change="enableAdvanced(Boolean($event))" />{{ text('useAdvanced') }}</label>
            <label v-if="editing.type === 'QUALITY'"><ElSwitch :model-value="!!editing.advanced" @change="editing.advanced=$event ? {timing:{}} : null" />{{ text('timingSettings') }}</label>
            <template v-if="advancedMode || ['JUMP','STUCK'].includes(editing.type)">
              <label>{{ text('primaryEquipment') }}<ElSelect :model-value="editing.advanced?.primaryEquipmentId" :placeholder="text('choosePrimary')" @change="ensureAdvanced().primaryEquipmentId=$event"><ElOption v-for="equipment in options.equipment ?? []" :key="equipment.value" :label="equipment.label" :value="equipment.value" /></ElSelect></label>
              <p class="condition-help">{{ text('treeHelp') }}</p>
            </template>
            <template v-if="editing.advanced">
              <strong>{{ text('timingSettings') }}</strong>
              <div class="timing-grid"><label>{{ text('expectedSeconds') }}<ElInputNumber v-model="editing.advanced.timing!.expectedIntervalSeconds" :min="1" :max="86400" :controls="false" /></label><label>{{ text('delaySeconds') }}<ElInputNumber v-model="editing.advanced.timing!.allowedDelaySeconds" :min="0" :max="86400" :controls="false" /></label><label>{{ text('alignmentSeconds') }}<ElInputNumber v-model="editing.advanced.timing!.alignmentSeconds" :min="1" :max="86400" :controls="false" /></label><label>{{ text('staleSeconds') }}<ElInputNumber v-model="editing.advanced.timing!.staleAfterSeconds" :min="1" :max="86400" :controls="false" /></label></div>
              <p class="condition-help">{{ text('timingHelp') }}</p>
            </template>
          </section>
          <section class="editor-section">
            <div class="step-heading"><b>{{ text('stepThree') }}</b><h3>{{ text('conditionStep') }}</h3></div>
            <template v-if="editing.type === 'OFFLINE'">
              <p class="condition-sentence">{{ text('offlineConditionStart') }} <ElInputNumber v-model="editing.parameter" :min="1" :max="100" :precision="0" :controls="false" /> {{ text('offlineCycleTrigger') }}</p>
              <p class="condition-sentence">{{ text('recoverCycleStart') }} <ElInputNumber v-model="editing.recoveryCycles" :min="1" :max="100" :precision="0" :controls="false" :aria-label="text('recoveryCyclesLabel')" /> {{ text('recoverCycleEnd') }}</p>
            </template>
            <p v-else-if="editing.type === 'QUOTA'" class="condition-sentence">{{ text('quotaConditionStart') }} <ElInputNumber v-model="editing.parameter" :min="1" :max="100" :precision="0" :controls="false" />{{ text('quotaConditionEnd') }}</p>
            <template v-else-if="editing.type === 'EMISSION' && editing.advanced?.emission">
              <div class="emission-form">
                <label>{{ text('emissionTemplate') }}<ElSelect v-model="editing.advanced.emission.template" @change="changeEmissionTemplate"><ElOption v-for="template in templateCatalog" :key="template.code" :label="template.label" :value="template.code" /></ElSelect></label>
                <label>{{ text('evaluationPeriod') }}<ElSelect v-model="editing.advanced.emission.period"><ElOption v-for="period in templateCatalog.find(item=>item.code===editing!.advanced!.emission!.template)?.periods ?? ['MONTH','YEAR']" :key="period" :value="period" :label="text(period)" /></ElSelect></label>
                <label>{{ text('threshold') }} {{ templateCatalog.find(item=>item.code===editing!.advanced!.emission!.template)?.unit }}<ElInputNumber v-model="editing.advanced.emission.threshold" :controls="false" /></label>
                <label>{{ text('recoveryThreshold') }}<ElInputNumber v-model="editing.advanced.emission.recoveryThreshold" :controls="false" /></label>
                <label v-if="emissionDescriptor?.requiredFields.includes('changeMode')">{{ text('changeMode') }}<ElSelect v-model="editing.advanced.emission.changeMode"><ElOption value="ABS" :label="text('ABS')" /><ElOption value="PERCENT" :label="text('PERCENT')" /></ElSelect></label>
                <label v-if="editing.advanced.emission.template === 'BASELINE'">{{ text('baselineModel') }}<ElInput v-model="editing.advanced.emission.baselineModelId" /></label>
                <p v-if="editing.advanced.emission.template === 'YEAR_END_FORECAST'">{{ text('forecastMethod') }}{{ text('glyphColon') }}{{ text('LINEAR_DAY') }}</p>
                <label v-for="field in customParameters" :key="field">{{ emissionDescriptor?.parameterLabels?.[field] ?? field }}<ElInputNumber :model-value="editing.advanced.emission.parameters?.[field]" :controls="false" @update:model-value="setTemplateParameter(field,$event)" /></label>
                <p v-for="requirement in emissionDescriptor?.professionalInputs ?? []" :key="requirement" class="condition-help">{{ requirement }}</p>
                <p class="condition-help">{{ text('emissionHelp') }}</p>
              </div>
            </template>
            <template v-else-if="pointRules.has(editing.type)">
              <template v-if="advancedMode && editing.advanced?.trigger">
                <strong>{{ text('triggerTree') }}</strong><AlarmConditionTreeEditor :model-value="editing.advanced.trigger" :points="options.points ?? []" @update:model-value="updateTree('trigger',$event)" @state-settings="openTreeState" />
                <ElButton v-if="!editing.advanced.recovery" text type="primary" @click="editing.advanced.recovery=blankTree()">{{ text('addRecovery') }}</ElButton>
                <template v-if="editing.advanced.recovery"><strong>{{ text('recoveryTree') }}</strong><AlarmConditionTreeEditor :model-value="editing.advanced.recovery" :points="options.points ?? []" @update:model-value="updateTree('recovery',$event)" @state-settings="openTreeState" /></template>
              </template>
              <template v-else>
                <p v-if="editing.type === 'CONDITION'" class="condition-logic"><span>{{ text('conditionLogic') }}</span><ElSelect v-model="editing.conditionLogic"><ElOption value="ALL" :label="text('ALL')" /><ElOption value="ANY" :label="text('ANY')" /></ElSelect><span>{{ text('conditionEnd') }}</span></p>
                <div v-for="(condition, index) in editing.conditions" :key="index" class="condition-row" :class="{ 'digital-condition': pointMetadata(condition.pointId)?.dataType === 'DIGITAL', 'quality-condition': editing.type === 'QUALITY' }">
                  <ElSelect v-model="condition.pointId" class="condition-point" :placeholder="text('selectPoint')" :aria-label="text('selectPoint')" filterable @change="changePoint(condition)">
                    <ElOption v-for="point in pointOptions(condition)" :key="point.value" :value="point.value" :label="point.label" />
                  </ElSelect>
                  <template v-if="editing.type === 'CONDITION'">
                    <ElSelect v-model="condition.operator" :aria-label="text('comparisonAction')"><ElOption v-for="operator in pointMetadata(condition.pointId)?.dataType === 'DIGITAL' ? (['EQ','NE'] as const) : operators" :key="operator" :value="operator" :label="text(operator)" /></ElSelect>
                    <div v-if="pointMetadata(condition.pointId)?.dataType === 'DIGITAL'" class="condition-value state-value">
                      <ElSelect :model-value="selectedState(condition)" :placeholder="text(condition.states?.length ? 'chooseState' : 'missingStateChoices')" :aria-label="text('chooseState')" @update:model-value="condition.value = $event">
                        <ElOption v-for="state in condition.states ?? []" :key="state.value" :label="state.label" :value="state.value" />
                      </ElSelect>
                      <ElButton text :title="text('stateChoices')" :aria-label="text('stateChoices')" @click="openStateEditor(index)"><Settings2 aria-hidden="true" /></ElButton>
                    </div>
                    <div v-else class="condition-value number-value">
                      <ElInputNumber v-model="condition.value" :controls="false" :aria-label="text('lowerValue')" />
                      <ElInputNumber v-if="['BETWEEN','OUTSIDE'].includes(condition.operator)" v-model="condition.upperValue" :controls="false" :aria-label="text('upperValue')" />
                    </div>
                    <span v-if="pointMetadata(condition.pointId)?.dataType !== 'DIGITAL'" class="condition-unit">{{ pointMetadata(condition.pointId)?.unit }}</span>
                  </template>
                  <ElButton class="remove-condition" text type="danger" :title="text('removeCondition')" :aria-label="text('removeCondition')" @click="removeCondition(index)"><Trash2 aria-hidden="true" /></ElButton>
                </div>
                <template v-if="['JUMP','STUCK'].includes(editing.type) && editing.advanced?.window">
                  <label>{{ text('windowMinutes') }}<ElInputNumber v-model="editing.advanced.window.minutes" :min="1" :max="1440" :controls="false" /></label>
                  <label>{{ text(editing.type==='JUMP'?'jumpTolerance':'tolerance') }}<ElInputNumber v-model="editing.advanced.window.tolerance" :min="0" :controls="false" /></label>
                  <label v-if="editing.type === 'JUMP'">{{ text('changeMode') }}<ElSelect v-model="editing.advanced.window.changeMode"><ElOption value="ABS" :label="text('ABS')" /><ElOption value="PERCENT" :label="text('PERCENT')" /></ElSelect></label>
                  <template v-if="editing.type === 'STUCK'">
                    <label>{{ text('runningPoint') }}<ElSelect v-model="editing.advanced.window.runningPointId"><ElOption v-for="point in availablePoints.filter(item=>item.dataType==='DIGITAL')" :key="point.value" :label="point.label" :value="point.value" /></ElSelect></label>
                    <label>{{ text('runningValue') }}<ElSelect v-if="pointMetadata(editing.advanced.window.runningPointId ?? '')?.states?.length" v-model="editing.advanced.window.runningValue"><ElOption v-for="state in pointMetadata(editing.advanced.window.runningPointId ?? '')?.states ?? []" :key="state.value" :label="state.label" :value="state.value" /></ElSelect><ElInputNumber v-else v-model="editing.advanced.window.runningValue" :controls="false" /></label>
                  </template>
                  <p class="condition-help">{{ text('windowHelp') }}</p>
                </template>
              </template>
              <section v-if="stateEditingIndex != null" class="state-editor" :aria-label="text('stateSettingsTitle')">
                <strong>{{ text('stateSettingsTitle') }}</strong>
                <label>{{ text('sharedDictionary') }}<ElSelect v-model="selectedDictionary" :placeholder="text('chooseDictionary')" @change="selectDictionary"><ElOption v-for="dictionary in sharedDictionaries" :key="dictionary.id" :label="dictionary.name + text('glyphSeparator') + dictionary.revision" :value="dictionary.id" /></ElSelect></label>
                <div class="state-editor-actions"><ElButton :disabled="!selectedDictionary" :loading="dictionaryBusy" @click="useDictionary">{{ text('useShared') }}</ElButton><ElButton :disabled="!selectedDictionary" :loading="dictionaryBusy" @click="syncDictionary">{{ text('applyShared') }}</ElButton></div>
                <ElInput v-model="dictionaryName" :placeholder="text('dictionaryName')" />
                <p class="condition-help">{{ text('stateSettingsHelp') }}</p>
                <div v-for="(state, stateIndex) in stateDraft" :key="stateIndex" class="state-option-row">
                  <ElInputNumber v-model="state.value" :controls="false" :aria-label="text('stateCode')" :placeholder="text('stateCode')" />
                  <ElInput v-model="state.label" :aria-label="text('stateLabel')" :placeholder="text('stateLabelPlaceholder')" maxlength="40" />
                  <ElButton text type="danger" :aria-label="text('removeState')" @click="stateDraft.splice(stateIndex, 1)"><Trash2 aria-hidden="true" /></ElButton>
                </div>
                <div class="state-editor-actions">
                  <ElButton text type="primary" :disabled="stateDraft.length >= 32" @click="stateDraft.push({ value: null, label: '' })">{{ text('addState') }}</ElButton>
                  <ElButton @click="stateEditingIndex = null">{{ text('cancel') }}</ElButton>
                  <ElButton type="primary" :disabled="!validStateDraft" @click="applyStates">{{ text('applyStates') }}</ElButton>
                </div>
                <div class="state-editor-actions"><ElButton :disabled="!validStateDraft || !dictionaryName.trim()" :loading="dictionaryBusy" @click="saveShared(false)">{{ text('saveShared') }}</ElButton><ElButton :disabled="!validStateDraft || !selectedDictionary" :loading="dictionaryBusy" @click="saveShared(true)">{{ text('updateShared') }}</ElButton></div>
                <small v-if="!validStateDraft" class="condition-help">{{ text('stateOptionsInvalid') }}</small>
              </section>
              <ElButton v-if="!advancedMode && ['CONDITION','QUALITY'].includes(editing.type)" class="add-condition" text type="primary" :disabled="(editing.conditions?.length ?? 0) >= 10" @click="addCondition"><Plus aria-hidden="true" />{{ text('addCondition') }}</ElButton>
              <p class="condition-help">{{ text(advancedMode ? 'treeHelp' : 'conditionsHint') }} {{ text('stateHelp') }}</p>
              <p class="condition-sentence">{{ text('triggerDurationLabel') }} <ElInputNumber v-model="editing.triggerMinutes" :min="0" :max="10080" :precision="0" :controls="false" :aria-label="text('triggerDurationLabel')" /> {{ text('minutesSuffix') }}</p>
              <p class="condition-sentence">{{ text('recoveryDurationLabel') }} <ElInputNumber v-model="editing.recoveryMinutes" :min="0" :max="10080" :precision="0" :controls="false" :aria-label="text('recoveryDurationLabel')" /> {{ text('minutesSuffix') }}</p>
              <p class="condition-sentence">{{ text('recoverCycleStart') }} <ElInputNumber v-model="editing.recoveryCycles" :min="1" :max="100" :precision="0" :controls="false" :aria-label="text('recoveryCyclesLabel')" /> {{ text('recoverCycleEnd') }}</p>
              <small class="condition-help">{{ text('triggerNowHint') }}</small>
            </template>
            <p v-else class="condition-sentence">{{ text('fixedFaultParameter') }}{{ text('glyphSeparator') }}{{ text('faultRecovery') }}</p>
          </section>
          <section class="editor-section condition-preview">
            <strong>{{ text('completeRulePreview') }}</strong>
            <span>{{ text('scopePreview', { scope: scopeName(editing), target: categoryName(editing), level: text(level(editing)) }) }}{{ text('qualityPreview', { quality: text(qualityLabels[quality(editing)]) }) }}{{ triggerCondition(editing) }}</span>
          </section>
        </div>
        <ElAlert v-if="error" :title="error" type="error" :closable="false" />
        <footer><ElButton @click="closeEditor">{{ text('cancel') }}</ElButton><ElButton type="primary" :loading="saving === ruleKey(editing)" :disabled="!validDraft" @click="saveEdit">{{ text('save') }}</ElButton></footer>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.advanced-controls { display:grid; gap:var(--bec-space-group); padding:var(--bec-panel-padding); border:var(--bec-border-width) solid var(--bec-color-divider); border-radius:var(--bec-radius-card) }
.advanced-controls label,.emission-form label { display:grid; gap:var(--bec-space-tight); font-size:var(--bec-ref-font-12) }
.timing-grid,.emission-form { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:var(--bec-space-group) }
.timing-grid :deep(.el-input-number),.emission-form :deep(.el-input-number) { width:100% }
.emission-form p { grid-column:1/-1 }

.condition-row {
  display:grid;
  grid-template-columns:minmax(0,1.4fr) minmax(0,.9fr) minmax(0,.8fr) auto auto;
  align-items:center;
  gap:var(--bec-ref-space-4);
  padding:var(--bec-space-tight);
  background:var(--bec-color-surface);
  border:var(--bec-border-width) solid var(--bec-color-divider);
  border-radius:var(--bec-radius-control)
}
.condition-row.digital-condition { grid-template-columns:minmax(0,1.4fr) minmax(0,.7fr) minmax(0,1.1fr) auto }
.condition-row.quality-condition { grid-template-columns:minmax(0,1fr) auto }
.condition-row :deep(.el-select),.condition-row :deep(.el-input-number),.condition-row :deep(.el-button) { width:100%; min-width:0 }
.condition-value { display:flex; gap:var(--bec-ref-space-4); align-items:center; min-width:0 }
.state-value > .el-select { flex:1; min-width:0 }
.state-value > .el-button,.condition-row .remove-condition { width:var(--bec-ref-space-24); padding:0 }
.condition-row svg,.state-editor svg { width:var(--bec-ref-space-16); height:var(--bec-ref-space-16) }
.state-editor { display:grid; gap:var(--bec-space-tight); padding:var(--bec-space-group); border:var(--bec-border-width) solid var(--bec-color-action-primary); border-radius:var(--bec-radius-control); background:var(--bec-color-surface) }
.state-option-row { display:grid; grid-template-columns:minmax(0,.7fr) minmax(0,1fr) auto; gap:var(--bec-space-tight); align-items:center }
.state-option-row :deep(.el-input-number) { width:100% }
.state-editor-actions { display:flex; align-items:center; justify-content:flex-end; gap:var(--bec-space-tight); flex-wrap:wrap }
.state-editor-actions > button:first-child { margin-right:auto }
.condition-logic { display:flex; align-items:center; flex-wrap:wrap; gap:var(--bec-space-tight); margin:0 }
.condition-logic .el-select { flex:1 }
.condition-unit,.condition-help { font-size:var(--bec-ref-font-12); color:var(--bec-color-text-secondary) }
.condition-help { margin:0 }
.add-condition { justify-self:start }
.add-condition svg { width:var(--bec-ref-space-16); height:var(--bec-ref-space-16) }

.rules-page {
  position:relative;
  min-width:0;
  min-height:100%;
  display:flex;
  flex-direction:column;
  color:var(--bec-color-text-primary);
  font-size:var(--bec-ref-font-14)
}
.rules-surface {
  flex:1;
  display:grid;
  align-content:start;
  gap:var(--bec-space-section);
  padding:var(--bec-ref-space-24);
  border:var(--bec-border-width) solid var(--bec-color-divider);
  border-radius:var(--bec-management-radius);
  background:var(--bec-color-surface);
  box-shadow:var(--bec-shadow-card)
}
.page-heading {
  display:flex;
  align-items:flex-end;
  justify-content:space-between;
  gap:var(--bec-space-group);
  flex-wrap:wrap
}
.page-heading h1 {
  margin:0;
  font-size:var(--bec-ref-font-20);
  font-weight:var(--bec-font-weight-heading)
}
.page-heading p {
  margin:var(--bec-space-tight) 0 0;
  color:var(--bec-color-text-secondary);
  font-size:var(--bec-ref-font-12)
}
.page-heading label {
  display:flex;
  align-items:center;
  gap:var(--bec-space-tight)
}
.page-heading .el-select {
  min-width:var(--bec-navigation-width)
}
.rule-tabs {
  display:flex;
  justify-content:flex-start;
  gap:var(--bec-space-group)
}
.rule-tabs button {
  display:grid;
  grid-template-columns:auto auto auto;
  justify-content:center;
  align-items:center;
  gap:var(--bec-space-tight) var(--bec-space-group);
  width:calc(var(--bec-ref-space-64) * 3 + var(--bec-ref-space-40));
  min-height:var(--bec-ref-space-40);
  padding:var(--bec-space-tight) var(--bec-space-group);
  border:var(--bec-border-width) solid var(--bec-color-divider);
  border-radius:var(--bec-radius-control);
  background:var(--bec-color-surface-secondary);
  color:var(--bec-color-text-primary);
  font:inherit;
  text-align:left;
  cursor:pointer
}
.rule-tabs button.active {
  border-color:var(--bec-color-action-primary);
  background:var(--bec-color-action-soft);
  color:var(--bec-color-brand-primary)
}
.rule-tabs button::after {
  content:none
}
.rule-tabs strong {
  padding:var(--bec-ref-space-4) var(--bec-space-tight);
  border-radius:var(--bec-radius-tag);
  background:var(--bec-color-surface);
  color:var(--bec-color-text-secondary);
  font-size:var(--bec-ref-font-12);
  font-weight:var(--bec-font-weight-normal)
}
.rule-filters {
  display:flex;
  align-items:center;
  flex-wrap:wrap;
  gap:var(--bec-space-group);
  padding:var(--bec-space-group);
  border:var(--bec-border-width) solid var(--bec-color-divider);
  border-radius:var(--bec-radius-control);
  background:var(--bec-color-surface-secondary)
}
.rule-filters label {
  display:flex;
  align-items:center;
  justify-content:space-between;
  gap:var(--bec-space-tight);
  color:var(--bec-color-text-secondary);
  font-size:var(--bec-ref-font-12)
}
.rule-filters label>span {
  white-space:nowrap
}
.rule-filters label {
  flex:1 1 auto;
  min-width:calc(var(--bec-ref-space-64) * 2);
}
.rule-filters label:last-of-type {
  flex:0 1 calc(var(--bec-ref-space-64) * 2);
}
.rule-filters > button { margin-inline-start:auto }
.rule-filters button svg,.rule-tabs svg { width:var(--bec-ref-space-16); height:var(--bec-ref-space-16) }
.rule-tabs .device-icon { color:var(--bec-color-text-secondary) }
.rule-tabs .emission-icon { color:var(--bec-color-success) }
.rule-tabs button.active strong { color:var(--bec-color-action-primary); background:var(--bec-color-action-soft) }
.rule-article.selected { background:var(--bec-color-action-soft) }
.rule-article.selected .rule-name strong { color:var(--bec-color-action-primary) }
.pill {
  --tone:var(--bec-color-text-secondary);
  display:inline-flex;
  align-items:center;
  gap:var(--bec-ref-space-4);
  width:fit-content;
  padding:var(--bec-ref-space-4) var(--bec-ref-space-8);
  border:var(--bec-border-width) solid color-mix(in srgb,var(--tone) 32%,var(--bec-color-surface));
  border-radius:var(--bec-radius-control);
  color:var(--tone);
  background:color-mix(in srgb,var(--tone) 7%,var(--bec-color-surface));
  font-size:var(--bec-ref-font-12);
  line-height:var(--bec-line-height);
  white-space:nowrap;
}
.URGENT { --tone:var(--bec-color-error) }
.IMPORTANT,.dynamicLevel { --tone:var(--bec-color-warning) }
.GENERAL,.Q1 { --tone:var(--bec-color-action-primary) }
.Q0 { --tone:var(--bec-color-success) }
.severity-choices { display:flex; flex-wrap:wrap; gap:var(--bec-space-tight) }
.severity-choices button { cursor:pointer }
.severity-choices button[aria-pressed=true] { border-color:var(--tone); box-shadow:inset 0 0 0 var(--bec-border-width) var(--tone) }
.severity-choices svg { width:var(--bec-ref-space-12); height:var(--bec-ref-space-12) }
.quality-options { display:grid; gap:var(--bec-space-tight) }
.quality-options small { color:var(--bec-color-text-secondary); font-weight:var(--bec-font-weight-normal) }
.quality-options > label {
  display:block;
  line-height:var(--bec-line-height);
  cursor:pointer;
  padding:var(--bec-space-tight);
  border:var(--bec-border-width) solid var(--bec-color-divider);
  border-radius:var(--bec-radius-control);
  background:var(--bec-color-surface);
  font-size:var(--bec-ref-font-12);
  font-weight:var(--bec-font-weight-normal);
}
.quality-options input { vertical-align:middle; margin:0 var(--bec-ref-space-4) 0 0; accent-color:var(--bec-color-action-primary) }
.quality-options label.current { border-color:var(--bec-color-action-primary); background:var(--bec-color-action-soft) }
.rule-filters :deep(.el-select) {
  flex:1;
  width:100%;
  min-width:0
}
.rule-table {
  border:var(--bec-border-width) solid var(--bec-color-border);
  border-radius:var(--bec-radius-card);
  overflow:hidden;
  background:var(--bec-color-surface)
}
.rule-header,.rule-row {
  display:grid;
  grid-template-columns:minmax(0,1.3fr) minmax(0,1.4fr) minmax(0,2fr) minmax(0,.6fr) minmax(0,.48fr) minmax(0,.55fr);
  align-items:center;
  gap:var(--bec-space-group);
  padding:var(--bec-space-group) var(--bec-space-section)
}
.rule-header {
  background:var(--bec-color-surface-secondary);
  color:var(--bec-color-text-secondary);
  font-size:var(--bec-ref-font-12);
  white-space:nowrap
}
.rule-article+.rule-article {
  border-top:var(--bec-border-width) solid var(--bec-color-border)
}
.rule-name {
  display:grid;
  gap:var(--bec-space-tight)
}
.rule-name small,.history-record small {
  color:var(--bec-color-text-secondary)
}
.scope-value {
  display:grid;
  gap:var(--bec-ref-space-4);
  min-width:0
}
.scope-value strong {
  font-weight:var(--bec-font-weight-normal)
}
.scope-value small,.severity-value {
  color:var(--bec-color-text-secondary);
  font-size:var(--bec-ref-font-12)
}
.trigger-value {
  line-height:var(--bec-line-height)
}
.row-actions {
  display:flex;
  align-items:center;
  gap:var(--bec-space-tight);
  flex-wrap:wrap
}
.history-panel {
  padding:var(--bec-space-group) var(--bec-panel-padding);
  background:var(--bec-color-surface-secondary);
  border-top:var(--bec-border-width) solid var(--bec-color-border)
}
.history-list {
  position:relative;
  display:grid;
  gap:var(--bec-space-group);
  margin:0;
  padding:0 0 0 var(--bec-space-section);
  list-style:none
}
.history-list::before {
  position:absolute;
  top:var(--bec-space-tight);
  bottom:var(--bec-space-tight);
  left:var(--bec-ref-space-4);
  width:var(--bec-border-width);
  background:var(--bec-color-border);
  content:''
}
.history-list li {
  position:relative
}
.timeline-dot {
  position:absolute;
  top:var(--bec-ref-space-4);
  left:calc(-1 * var(--bec-space-section) + var(--bec-ref-space-4));
  width:var(--bec-ref-space-8);
  height:var(--bec-ref-space-8);
  border-radius:var(--bec-radius-tag);
  background:var(--bec-color-brand-primary)
}
.history-record {
  display:grid;
  gap:var(--bec-space-tight)
}
.history-record small {
  line-height:var(--bec-line-height)
}
.history-error {
  color:var(--bec-color-error)
}
.history-empty,.empty-rules {
  color:var(--bec-color-text-secondary)
}
.drawer-overlay {
  position:fixed;
  top:var(--bec-workspace-header-height);
  right:0;
  bottom:0;
  left:var(--bec-navigation-width);
  z-index:var(--bec-layer-notices);
  display:flex;
  justify-content:flex-end;
  background:transparent
}
.rule-drawer {
  display:grid;
  grid-template-rows:auto 1fr auto;
  gap:var(--bec-space-section);
  width:min(calc(var(--bec-ref-space-64) * 8 + var(--bec-ref-space-48)),100%);
  height:100%;
  min-height:0;
  padding:var(--bec-panel-padding) var(--bec-ref-space-24);
  background:var(--bec-color-surface);
  box-shadow:var(--bec-shadow-overlay);
  overflow:hidden
}
.drawer-heading h2 {
  margin:0;
  font-size:var(--bec-ref-font-16)
}
.drawer-heading>div {
  display:flex;
  align-items:center;
  gap:var(--bec-space-tight);
}
.immediate-badge {
  padding:var(--bec-ref-space-4) var(--bec-ref-space-8);
  border:var(--bec-border-width) solid var(--bec-color-border);
  border-radius:var(--bec-radius-control);
  background:var(--bec-color-action-soft);
  color:var(--bec-color-brand-primary);
  font-size:var(--bec-ref-font-12);
  white-space:nowrap;
}
.drawer-content {
  display:grid;
  align-content:start;
  gap:var(--bec-space-group);
  min-height:0;
  overflow:auto;
  padding:0
}
.rule-drawer header {
  display:flex;
  align-items:flex-start;
  justify-content:space-between
}
.rule-drawer h2 {
  margin:0
}
.rule-drawer header p {
  color:var(--bec-color-text-secondary)
}
.rule-drawer header button {
  width:var(--bec-ref-space-40);
  height:var(--bec-ref-space-40);
  border:var(--bec-border-width) solid var(--bec-color-border);
  border-radius:var(--bec-radius-control);
  background:var(--bec-color-surface);
  color:var(--bec-color-brand-primary);
  font-size:var(--bec-ref-font-24);
  cursor:pointer
}
.editor-section {
  display:grid;
  align-content:start;
  gap:var(--bec-space-group);
  padding:var(--bec-panel-padding);
  border:var(--bec-border-width) solid var(--bec-color-border);
  border-radius:var(--bec-radius-card);
  background:var(--bec-color-surface-secondary)
}
.editor-section h3 {
  margin:0
}
.step-heading {
  display:flex;
  align-items:center;
  gap:var(--bec-space-group)
}
.step-heading b {
  display:grid;
  place-items:center;
  width:var(--bec-ref-space-24);
  height:var(--bec-ref-space-24);
  border-radius:var(--bec-radius-tag);
  background:var(--bec-color-action-primary);
  color:var(--bec-color-on-action)
}
.rule-identity {
  display:grid;
  gap:var(--bec-ref-space-8);
  margin:0
}
.rule-identity>div {
  display:grid;
  grid-template-columns:minmax(0,.35fr) 1fr;
  gap:var(--bec-space-group)
}
.rule-identity dt {
  align-self:center;
}
.rule-identity :deep(.el-select) {
  width:100%;
}
.quality-scope {
  padding:var(--bec-space-tight);
  border:var(--bec-border-width) solid var(--bec-color-action-primary);
  border-radius:var(--bec-radius-control);
  background:var(--bec-color-action-soft);
  color:var(--bec-color-brand-primary);
  font-size:var(--bec-ref-font-12);
}
.rule-identity dt {
  color:var(--bec-color-text-secondary)
}
.rule-identity dd {
  margin:0;
  font-weight:var(--bec-font-weight-heading)
}
.editor-section.quality-options > label { display:block; line-height:var(--bec-line-height) }
.quality-options input { vertical-align:middle }
.quality-options label.current b { color:var(--bec-color-action-primary) }
.condition-sentence {
  margin:0;
  padding:var(--bec-space-tight);
  border:var(--bec-border-width) solid var(--bec-color-divider);
  border-radius:var(--bec-radius-control);
  background:var(--bec-color-surface);
  line-height:var(--bec-line-height)
}
.condition-sentence :deep(.el-input-number) {
  width:var(--bec-ref-space-48);
  vertical-align:middle;
}
.condition-preview {
  border-color:color-mix(in srgb,var(--bec-color-success) 30%,var(--bec-color-surface));
  background:color-mix(in srgb,var(--bec-color-success) 5%,var(--bec-color-surface));
  color:var(--bec-color-success);
}
.condition-preview span {
  color:var(--bec-color-success);
  line-height:var(--bec-line-height)
}
.rule-drawer footer {
  display:flex;
  justify-content:flex-end;
  gap:var(--bec-space-group);
  padding-top:var(--bec-space-group);
  border-top:var(--bec-border-width) solid var(--bec-color-border);
  background:var(--bec-color-surface)
}
@media(min-width:1600px) {
  .rules-page.drawer-open {
    margin-right:calc(var(--bec-ref-space-64) * 8 + var(--bec-ref-space-24));
  }
}
@media(max-width:720px) {
  .rules-surface {
    padding:var(--bec-panel-padding)
  }
  .rule-tabs {
    align-items:stretch;
    flex-direction:column
  }
  .rule-tabs button {
    width:100%
  }
  .rule-filters {
    align-items:stretch;
    flex-direction:column
  }
  .rule-filters label:first-child,.rule-filters label:last-child {
    width:100%;
    max-width:none
  }
  .rule-header {
    display:none
  }
  .rule-row {
    grid-template-columns:1fr 1fr
  }
  .rule-row>* {
    min-width:0
  }
  .rule-row .row-actions {
    grid-column:1/-1
  }
  .page-heading label {
    width:100%
  }
  .page-heading .el-select {
    flex:1;
    min-width:0
  }
  .drawer-overlay {
    top:0;
    left:0
  }
  .rule-drawer {
    width:min(calc(var(--bec-ref-space-64) * 8 + var(--bec-ref-space-48)),100%);
    padding:var(--bec-panel-padding)
  }
}
</style>
