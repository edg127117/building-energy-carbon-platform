<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCard, ElDialog, ElEmpty, ElFormItem, ElInput, ElOption, ElRadio, ElRadioGroup, ElSelect, ElSkeleton, ElTag } from '@/shared/ui'
import {
  type CarbonFactorApplicability as FactorApplicability,
  type CarbonFactorCategory as FactorCategory,
  type CarbonFactorComponentType as FactorComponentType,
  type CarbonFactorScope as FactorScope,
  type CarbonFactorSource as FactorSource,
  type CarbonFactorUsageNature as FactorUsageNature,
  type CarbonFactorVersion as FactorVersion,
  type CarbonFormulaOption as RuleFormulaOption,
  type CarbonRoundingPolicyOption as RuleRoundingPolicyOption,
} from '../api/carbon-factors'
import { useCarbonFactors } from '../composables/use-carbon-factors'
import copy from '../locales/carbon-factors.zh-CN'

interface SourceDraft {
  sourceCode: string
  sourceName: string
  publisher: string
  documentReference: string
  publicationYear: string
  publishedOn: string
  applicabilityNote: string
  evidenceReference: string
  usageNature: FactorUsageNature | ''
}

interface ComponentDraft {
  componentType: FactorComponentType
  value: string
  sourceVersionId: string
  evidenceReference: string
}

interface FactorDraft {
  factorCode: string
  scopeType: FactorScope | ''
  factorCategory: FactorCategory | ''
  energyItemCode: string
  sourceVersionId: string
  applicabilityLevel: FactorApplicability | ''
  regionCode: string
  inputUnitCode: string
  standardConditionCode: string
  usageNature: FactorUsageNature | ''
  effectiveFrom: string
  effectiveTo: string
  formulaVersionId: string
  roundingPolicyVersionId: string
  dataYear: string
  accountingYear: string
  components: ComponentDraft[]
}

interface ReviewDraft {
  factor: FactorVersion
  approved: boolean
  reviewComment: string
}

interface FactorDefinition {
  scopeType: FactorScope
  algorithmCode: string
  resultBasis: string
  gasCode: string
  gasCoverage: string
  compatibleCategory: string
  usageScope: string
  componentTypes: FactorComponentType[]
}

// 固定身份只筛选服务端返回的候选规则；提交时仍由服务端完成最终受理校验。
const definitions: Record<FactorCategory, FactorDefinition> = {
  STATIONARY_COMBUSTION: {
    scopeType: 'SCOPE_1', algorithmCode: 'STATIONARY_COMBUSTION_CO2_V1', resultBasis: 'GAS_MASS', gasCode: 'CO2', gasCoverage: 'CO2_ONLY_FOR_STATIONARY_COMBUSTION',
    compatibleCategory: 'FUEL', usageScope: 'STATIONARY_COMBUSTION',
    componentTypes: ['LOWER_HEATING_VALUE', 'CARBON_CONTENT_PER_HEAT', 'OXIDATION_RATE'],
  },
  PURCHASED_ELECTRICITY_LOCATION: {
    scopeType: 'SCOPE_2', algorithmCode: 'PURCHASED_ELECTRICITY_LOCATION_CO2E_V1', resultBasis: 'CO2E_DIRECT', gasCode: 'CO2E', gasCoverage: 'DIRECT_FACTOR_GAS_COVERAGE',
    compatibleCategory: 'ELECTRICITY', usageScope: 'PURCHASED_ELECTRICITY', componentTypes: ['DIRECT_EMISSION_FACTOR'],
  },
  PURCHASED_HEAT: {
    scopeType: 'SCOPE_2', algorithmCode: 'PURCHASED_HEAT_CO2E_V1', resultBasis: 'CO2E_DIRECT', gasCode: 'CO2E', gasCoverage: 'DIRECT_FACTOR_GAS_COVERAGE',
    compatibleCategory: 'HEAT', usageScope: 'PURCHASED_HEAT', componentTypes: ['DIRECT_EMISSION_FACTOR'],
  },
}

const props = defineProps<{ buildingId: string }>()
const emit = defineEmits<{ 'dirty-change': [dirty: boolean] }>()
const workspace = useCarbonFactors(() => props.buildingId)
const sourceDraft = ref<SourceDraft>(emptySource())
const factorDraft = ref<FactorDraft>(emptyFactor())
const reviewDraft = ref<ReviewDraft | null>(null)

const definition = computed(() => factorDraft.value.factorCategory ? definitions[factorDraft.value.factorCategory] : null)
const categoryOptions = computed(() => (Object.entries(definitions) as Array<[FactorCategory, FactorDefinition]>)
  .filter(([, item]) => !factorDraft.value.scopeType || item.scopeType === factorDraft.value.scopeType))
const energyOptions = computed(() => {
  const current = definition.value
  if (!current) return []
  return workspace.approvedEnergyItems.value.filter(item => item.compatibleCategory === current.compatibleCategory
    && item.usageScopes.includes(current.usageScope))
})
const unitOptions = computed(() => {
  const energyItemCode = factorDraft.value.energyItemCode
  if (!energyItemCode) return []
  const allowedUnitCodes = new Set(workspace.approvedEnergyCompatibilities.value
    .filter(item => item.energyItemCode === energyItemCode)
    .map(item => item.unitCode))
  return workspace.approvedEnergyUnits.value.filter(unit => allowedUnitCodes.has(unit.unitCode))
})
const needsRegion = computed(() => factorDraft.value.applicabilityLevel === 'PROVINCE' || factorDraft.value.applicabilityLevel === 'GRID_REGION')
const needsCurrentBuilding = computed(() => factorDraft.value.applicabilityLevel === 'BUILDING_SPECIFIC')
const needsElectricityYears = computed(() => factorDraft.value.factorCategory === 'PURCHASED_ELECTRICITY_LOCATION')
const yearsAreValid = computed(() => {
  if (!needsElectricityYears.value) return !factorDraft.value.dataYear && !factorDraft.value.accountingYear
  const dataYear = factorDraft.value.dataYear.trim()
  const accountingYear = factorDraft.value.accountingYear.trim()
  return (!dataYear && !accountingYear) || (/^\d{4}$/.test(dataYear) && /^\d{4}$/.test(accountingYear))
})
const sourceYearIsValid = computed(() => !sourceDraft.value.publicationYear.trim() || /^\d{4}$/.test(sourceDraft.value.publicationYear.trim()))
const formulaOptions = computed(() => {
  const current = definition.value
  const effectiveAt = localDateTime(factorDraft.value.effectiveFrom)
  const usageNature = factorDraft.value.usageNature
  if (!current || effectiveAt === null || !usageNature) return []
  return workspace.ruleOptions.value.formulas.filter(formula => {
    const formulaFrom = localDateTime(formula.effectiveFrom)
    const formulaTo = formula.effectiveTo ? localDateTime(formula.effectiveTo) : null
    return formula.algorithmCode === current.algorithmCode && formula.resultBasis === current.resultBasis
      && (usageNature === 'DEVELOPMENT_REFERENCE' || formula.usageNature === 'FORMAL')
      && formulaFrom !== null && formulaFrom <= effectiveAt
      && (formulaTo === null || effectiveAt < formulaTo)
  })
})
const roundingOptions = computed(() => workspace.ruleOptions.value.roundingPolicies)
const canCreateSource = computed(() => !workspace.loading.value && !workspace.saving.value && sourceYearIsValid.value
  && !!sourceDraft.value.sourceCode.trim() && !!sourceDraft.value.sourceName.trim() && !!sourceDraft.value.publisher.trim()
  && !!sourceDraft.value.documentReference.trim() && !!sourceDraft.value.applicabilityNote.trim()
  && !!sourceDraft.value.evidenceReference.trim() && !!sourceDraft.value.usageNature)
const canCreateFactor = computed(() => {
  const current = definition.value
  const draft = factorDraft.value
  const applicabilityReady = !!draft.applicabilityLevel
    && (!needsCurrentBuilding.value || !!props.buildingId.trim())
    && (!needsRegion.value || !!draft.regionCode.trim())
  return !workspace.loading.value && !workspace.saving.value && !!current && !!draft.factorCode.trim()
    && !!draft.scopeType && !!draft.energyItemCode && !!draft.sourceVersionId && applicabilityReady
    && !!draft.inputUnitCode && !!draft.usageNature && !!draft.effectiveFrom && !!draft.formulaVersionId.trim()
    && formulaOptions.value.some(item => item.versionId === draft.formulaVersionId)
    && roundingOptions.value.some(item => item.versionId === draft.roundingPolicyVersionId)
    && unitOptions.value.some(item => item.unitCode === draft.inputUnitCode)
    && yearsAreValid.value && draft.components.length === current.componentTypes.length
    && draft.components.every(item => !!item.value.trim() && !!item.sourceVersionId && !!item.evidenceReference.trim())
})
const dirty = computed(() => workspace.saving.value || hasSourceDraftInput(sourceDraft.value)
  || hasFactorDraftInput(factorDraft.value) || reviewDraft.value !== null)

watch(() => props.buildingId, () => {
  if (needsCurrentBuilding.value) factorDraft.value.applicabilityLevel = ''
})
watch(dirty, value => emit('dirty-change', value), { immediate: true })

function emptySource(): SourceDraft {
  return { sourceCode: '', sourceName: '', publisher: '', documentReference: '', publicationYear: '', publishedOn: '', applicabilityNote: '', evidenceReference: '', usageNature: '' }
}

function emptyFactor(): FactorDraft {
  return { factorCode: '', scopeType: '', factorCategory: '', energyItemCode: '', sourceVersionId: '', applicabilityLevel: '', regionCode: '', inputUnitCode: '', standardConditionCode: '', usageNature: '', effectiveFrom: '', effectiveTo: '', formulaVersionId: '', roundingPolicyVersionId: '', dataYear: '', accountingYear: '', components: [] }
}

function hasSourceDraftInput(value: SourceDraft): boolean {
  return !!value.sourceCode.trim() || !!value.sourceName.trim() || !!value.publisher.trim()
    || !!value.documentReference.trim() || !!value.publicationYear.trim() || !!value.publishedOn.trim()
    || !!value.applicabilityNote.trim() || !!value.evidenceReference.trim() || !!value.usageNature
}

function hasFactorDraftInput(value: FactorDraft): boolean {
  return !!value.factorCode.trim() || !!value.scopeType || !!value.factorCategory || !!value.energyItemCode
    || !!value.sourceVersionId || !!value.applicabilityLevel || !!value.regionCode.trim()
    || !!value.inputUnitCode || !!value.standardConditionCode.trim() || !!value.usageNature
    || !!value.effectiveFrom || !!value.effectiveTo || !!value.formulaVersionId.trim()
    || !!value.roundingPolicyVersionId.trim() || !!value.dataYear.trim() || !!value.accountingYear.trim()
    || value.components.length > 0
}

function label(values: Record<string, string>, value: string): string {
  return values[value] ?? value
}

function codeLabel(name: string, code: string): string {
  return [name, code].join(copy.separator)
}

function sourceLabel(source: FactorSource): string {
  return [source.sourceName, source.sourceCode, `${copy.versionPrefix}${source.versionNo}`].join(copy.separator)
}

function sourceName(versionId: string): string {
  const source = workspace.sources.value.find(item => item.sourceVersionId === versionId)
  return source ? sourceLabel(source) : versionId
}

function scopeChanged(): void {
  factorDraft.value.factorCategory = ''
  factorDraft.value.energyItemCode = ''
  factorDraft.value.inputUnitCode = ''
  factorDraft.value.formulaVersionId = ''
  factorDraft.value.components = []
}

function categoryChanged(): void {
  const current = definition.value
  if (!current) return
  factorDraft.value.scopeType = current.scopeType
  factorDraft.value.energyItemCode = ''
  factorDraft.value.inputUnitCode = ''
  factorDraft.value.formulaVersionId = ''
  factorDraft.value.dataYear = ''
  factorDraft.value.accountingYear = ''
  factorDraft.value.components = current.componentTypes.map(componentType => ({
    componentType,
    value: '',
    sourceVersionId: factorDraft.value.sourceVersionId,
    evidenceReference: '',
  }))
}

function sourceChanged(): void {
  const sourceVersionId = factorDraft.value.sourceVersionId
  factorDraft.value.components.forEach(component => {
    if (!component.sourceVersionId) component.sourceVersionId = sourceVersionId
  })
}

function componentUnit(componentType: FactorComponentType): string {
  const inputUnit = factorDraft.value.inputUnitCode.trim()
  if (componentType === 'LOWER_HEATING_VALUE') return inputUnit ? `MJ/${inputUnit}` : ''
  if (componentType === 'CARBON_CONTENT_PER_HEAT') return 'KG_C_PER_MJ'
  if (componentType === 'OXIDATION_RATE') return 'RATIO'
  return inputUnit ? `KG_CO2E/${inputUnit}` : ''
}

function nullable(value: string): string | null {
  const normalized = value.trim()
  return normalized || null
}

function optionalYear(value: string): number | null {
  return value.trim() ? Number(value.trim()) : null
}

function localDateTime(value: string): number | null {
  const timestamp = Date.parse(value)
  return Number.isNaN(timestamp) ? null : timestamp
}

function formulaLabel(formula: RuleFormulaOption): string {
  const effectiveRange = formula.effectiveTo ? `${formula.effectiveFrom}${copy.rangeSeparator}${formula.effectiveTo}` : formula.effectiveFrom
  return [formula.code, `${copy.versionPrefix}${formula.versionNo}`, effectiveRange].join(copy.separator)
}

function roundingLabel(policy: RuleRoundingPolicyOption): string {
  const scales = [
    `${copy.factor.totalScale}${policy.totalScale}`,
    `${copy.factor.intensityScale}${policy.intensityScale}`,
    `${copy.factor.shareScale}${policy.shareScale}`,
  ].join(copy.scaleSeparator)
  return [policy.code, `${copy.versionPrefix}${policy.versionNo}`, scales, policy.roundingMode].join(copy.separator)
}

async function submitSource(): Promise<void> {
  const usageNature = sourceDraft.value.usageNature
  if (!canCreateSource.value || !usageNature) return
  const draft = sourceDraft.value
  const created = await workspace.createSource({
    sourceCode: draft.sourceCode.trim(), sourceName: draft.sourceName.trim(), publisher: draft.publisher.trim(),
    documentReference: draft.documentReference.trim(), publicationYear: optionalYear(draft.publicationYear),
    publishedOn: nullable(draft.publishedOn), applicabilityNote: draft.applicabilityNote.trim(),
    evidenceReference: draft.evidenceReference.trim(), usageNature,
  })
  if (created) sourceDraft.value = emptySource()
}

async function submitFactor(): Promise<void> {
  const current = definition.value
  const draft = factorDraft.value
  if (!current || !canCreateFactor.value || !draft.scopeType || !draft.factorCategory || !draft.applicabilityLevel || !draft.usageNature) return
  const created = await workspace.createFactor({
    factorCode: draft.factorCode.trim(), scopeType: draft.scopeType, energyItemCode: draft.energyItemCode,
    factorCategory: draft.factorCategory, resultBasis: current.resultBasis, gasCode: current.gasCode,
    gasCoverage: current.gasCoverage, sourceVersionId: draft.sourceVersionId,
    applicabilityLevel: draft.applicabilityLevel, buildingId: needsCurrentBuilding.value ? props.buildingId.trim() : null,
    regionCode: needsRegion.value ? nullable(draft.regionCode) : null, inputUnitCode: draft.inputUnitCode,
    standardConditionCode: nullable(draft.standardConditionCode), usageNature: draft.usageNature,
    effectiveFrom: draft.effectiveFrom, effectiveTo: nullable(draft.effectiveTo),
    formulaVersionId: draft.formulaVersionId.trim(), roundingPolicyVersionId: draft.roundingPolicyVersionId.trim(),
    components: draft.components.map(component => ({ componentType: component.componentType, value: component.value.trim(), unit: componentUnit(component.componentType), sourceVersionId: component.sourceVersionId, evidenceReference: component.evidenceReference.trim() })),
    dataYear: needsElectricityYears.value ? optionalYear(draft.dataYear) : null,
    accountingYear: needsElectricityYears.value ? optionalYear(draft.accountingYear) : null,
  })
  if (created) factorDraft.value = emptyFactor()
}

function openReview(factor: FactorVersion): void {
  reviewDraft.value = { factor, approved: true, reviewComment: '' }
}

async function submitReview(): Promise<void> {
  const draft = reviewDraft.value
  if (!draft || !draft.reviewComment.trim()) return
  const reviewed = await workspace.reviewFactor(draft.factor.factorVersionId, {
    expectedRevision: draft.factor.configRevision, approved: draft.approved, reviewComment: draft.reviewComment.trim(),
  })
  if (reviewed) reviewDraft.value = null
}

function activate(factor: FactorVersion): void {
  void workspace.activateFactor(factor.factorVersionId, { expectedRevision: factor.configRevision })
}

function disable(factor: FactorVersion): void {
  void workspace.disableFactor(factor.factorVersionId, { expectedRevision: factor.configRevision })
}
</script>

<template>
  <section class="carbon-factor-panel">
    <ElCard shadow="never">
      <template #header>
        <div class="panel-heading">
          <div>
            <h2>{{ copy.title }}</h2>
            <p>{{ copy.description }}</p>
          </div>
          <ElButton data-test="factor-refresh" :disabled="workspace.loading.value || workspace.saving.value" :loading="workspace.loading.value" @click="workspace.load()">
            {{ copy.refresh }}
          </ElButton>
        </div>
      </template>

      <ElAlert type="info" :title="copy.factor.description" :closable="false" show-icon />
      <ElAlert v-if="workspace.error.value" class="panel-alert" type="error" :title="workspace.error.value" :closable="false" show-icon />
      <ElSkeleton v-if="workspace.loading.value" class="panel-loading" animated :rows="5" />

      <template v-else>
        <section class="editor-section" :aria-label="copy.source.title">
          <div class="section-heading">
            <div><h3>{{ copy.source.title }}</h3><p>{{ copy.source.description }}</p></div>
          </div>
          <div class="form-grid">
            <ElFormItem :label="copy.source.code"><ElInput v-model="sourceDraft.sourceCode" data-test="source-code" /></ElFormItem>
            <ElFormItem :label="copy.source.name"><ElInput v-model="sourceDraft.sourceName" data-test="source-name" /></ElFormItem>
            <ElFormItem :label="copy.source.publisher"><ElInput v-model="sourceDraft.publisher" data-test="source-publisher" /></ElFormItem>
            <ElFormItem :label="copy.source.documentReference"><ElInput v-model="sourceDraft.documentReference" data-test="source-document" /></ElFormItem>
            <ElFormItem :label="copy.source.publicationYear"><ElInput v-model="sourceDraft.publicationYear" data-test="source-year" inputmode="numeric" /></ElFormItem>
            <ElFormItem :label="copy.source.publishedOn"><input v-model="sourceDraft.publishedOn" data-test="source-date" type="date" class="date-input"></ElFormItem>
            <ElFormItem :label="copy.source.usageNature"><ElSelect v-model="sourceDraft.usageNature" data-test="source-usage" clearable><ElOption v-for="value in Object.keys(copy.usageNature)" :key="value" :value="value" :label="label(copy.usageNature, value)" /></ElSelect></ElFormItem>
            <ElFormItem :label="copy.source.applicabilityNote"><ElInput v-model="sourceDraft.applicabilityNote" data-test="source-applicability" type="textarea" /></ElFormItem>
            <ElFormItem :label="copy.source.evidenceReference"><ElInput v-model="sourceDraft.evidenceReference" data-test="source-evidence" type="textarea" /></ElFormItem>
          </div>
          <ElButton data-test="source-create" :disabled="!canCreateSource" :loading="workspace.saving.value" @click="submitSource">{{ copy.source.create }}</ElButton>

          <ElEmpty v-if="!workspace.sources.value.length" :description="copy.source.empty" :image-size="40" />
          <div v-else class="source-list">
            <article v-for="source in workspace.sources.value" :key="source.sourceVersionId" class="source-row" :data-test="'source-' + source.sourceVersionId">
              <strong>{{ sourceLabel(source) }}</strong>
              <span>{{ source.publisher }}{{ copy.separator }}{{ source.documentReference }}</span>
              <code>{{ source.sourceVersionId }}</code>
              <ElTag>{{ label(copy.usageNature, source.usageNature) }}</ElTag>
            </article>
          </div>
        </section>

        <section class="editor-section factor-editor" :aria-label="copy.factor.title">
          <div class="section-heading"><div><h3>{{ copy.factor.title }}</h3><p>{{ copy.factor.description }}</p></div></div>
          <ElAlert type="info" :title="copy.factor.ruleOptionsDescription" :closable="false" show-icon />
          <div class="form-grid">
            <ElFormItem :label="copy.factor.factorCode"><ElInput v-model="factorDraft.factorCode" data-test="factor-code" /></ElFormItem>
            <ElFormItem :label="copy.factor.scope"><ElSelect v-model="factorDraft.scopeType" data-test="factor-scope" clearable @change="scopeChanged"><ElOption v-for="value in Object.keys(copy.scopes)" :key="value" :value="value" :label="label(copy.scopes, value)" /></ElSelect></ElFormItem>
            <ElFormItem :label="copy.factor.category"><ElSelect v-model="factorDraft.factorCategory" data-test="factor-category" :disabled="!factorDraft.scopeType" clearable @change="categoryChanged"><ElOption v-for="[value] in categoryOptions" :key="value" :value="value" :label="label(copy.categories, value)" /></ElSelect></ElFormItem>
            <ElFormItem :label="copy.factor.energyItem"><ElSelect v-model="factorDraft.energyItemCode" data-test="factor-energy" :disabled="!definition || !energyOptions.length" clearable filterable><ElOption v-for="item in energyOptions" :key="item.versionId" :value="item.itemCode" :label="codeLabel(item.itemName, item.itemCode)" /></ElSelect><small v-if="definition && !energyOptions.length">{{ copy.factor.noEnergyItems }}</small></ElFormItem>
            <ElFormItem :label="copy.factor.sourceVersion"><ElSelect v-model="factorDraft.sourceVersionId" data-test="factor-source" :disabled="!workspace.sources.value.length" clearable filterable @change="sourceChanged"><ElOption v-for="source in workspace.sources.value" :key="source.sourceVersionId" :value="source.sourceVersionId" :label="sourceLabel(source)" /></ElSelect><small v-if="!workspace.sources.value.length">{{ copy.factor.noSources }}</small></ElFormItem>
            <ElFormItem :label="copy.factor.applicability"><ElSelect v-model="factorDraft.applicabilityLevel" data-test="factor-applicability" clearable><ElOption v-for="value in Object.keys(copy.applicability)" :key="value" :value="value" :label="label(copy.applicability, value)" /></ElSelect></ElFormItem>
            <ElFormItem v-if="needsCurrentBuilding" :label="copy.factor.building"><ElInput :model-value="buildingId" readonly data-test="factor-building" /><small v-if="!buildingId">{{ copy.factor.buildingRequired }}</small></ElFormItem>
            <ElFormItem v-if="needsRegion" :label="copy.factor.region"><ElInput v-model="factorDraft.regionCode" data-test="factor-region" /></ElFormItem>
            <ElFormItem :label="copy.factor.inputUnit"><ElSelect v-model="factorDraft.inputUnitCode" data-test="factor-unit" :disabled="!unitOptions.length" clearable filterable><ElOption v-for="unit in unitOptions" :key="unit.versionId" :value="unit.unitCode" :label="codeLabel(unit.unitName, unit.unitCode)" /></ElSelect><small v-if="factorDraft.energyItemCode && !unitOptions.length">{{ copy.factor.noCompatibleUnits }}</small></ElFormItem>
            <ElFormItem :label="copy.factor.standardCondition"><ElInput v-model="factorDraft.standardConditionCode" data-test="factor-standard-condition" /></ElFormItem>
            <ElFormItem :label="copy.factor.usageNature"><ElSelect v-model="factorDraft.usageNature" data-test="factor-usage" clearable><ElOption v-for="value in Object.keys(copy.usageNature)" :key="value" :value="value" :label="label(copy.usageNature, value)" /></ElSelect></ElFormItem>
            <ElFormItem :label="copy.factor.effectiveFrom"><input v-model="factorDraft.effectiveFrom" data-test="factor-effective-from" type="datetime-local" class="date-input"></ElFormItem>
            <ElFormItem :label="copy.factor.effectiveTo"><input v-model="factorDraft.effectiveTo" data-test="factor-effective-to" type="datetime-local" class="date-input"></ElFormItem>
            <ElFormItem :label="copy.factor.formulaVersion"><ElSelect v-model="factorDraft.formulaVersionId" data-test="factor-formula" :disabled="!formulaOptions.length" clearable filterable><ElOption v-for="formula in formulaOptions" :key="formula.versionId" :value="formula.versionId" :label="formulaLabel(formula)" /></ElSelect><small v-if="factorDraft.factorCategory && factorDraft.usageNature && factorDraft.effectiveFrom && !formulaOptions.length">{{ copy.factor.noFormulaOptions }}</small></ElFormItem>
            <ElFormItem :label="copy.factor.roundingPolicyVersion"><ElSelect v-model="factorDraft.roundingPolicyVersionId" data-test="factor-rounding" :disabled="!roundingOptions.length" clearable filterable><ElOption v-for="policy in roundingOptions" :key="policy.versionId" :value="policy.versionId" :label="roundingLabel(policy)" /></ElSelect><small v-if="!roundingOptions.length">{{ copy.factor.noRoundingOptions }}</small></ElFormItem>
            <ElFormItem v-if="needsElectricityYears" :label="copy.factor.dataYear"><ElInput v-model="factorDraft.dataYear" data-test="factor-data-year" inputmode="numeric" /></ElFormItem>
            <ElFormItem v-if="needsElectricityYears" :label="copy.factor.accountingYear"><ElInput v-model="factorDraft.accountingYear" data-test="factor-accounting-year" inputmode="numeric" /></ElFormItem>
          </div>

          <div v-if="definition" class="identity-summary" data-test="factor-identity">
            <span>{{ copy.factor.identity }}</span>
            <code>{{ definition.resultBasis }}{{ copy.separator }}{{ definition.gasCode }}{{ copy.separator }}{{ definition.gasCoverage }}</code>
          </div>
          <div v-if="factorDraft.components.length" class="component-list">
            <h4>{{ copy.factor.components }}</h4>
            <article v-for="component in factorDraft.components" :key="component.componentType" class="component-row">
              <strong>{{ label(copy.componentTypes, component.componentType) }}</strong>
              <ElFormItem :label="copy.factor.componentValue"><ElInput v-model="component.value" :data-test="'component-value-' + component.componentType" inputmode="decimal" /></ElFormItem>
              <ElFormItem :label="copy.factor.componentUnit"><code :data-test="'component-unit-' + component.componentType">{{ componentUnit(component.componentType) || copy.unavailable }}</code></ElFormItem>
              <ElFormItem :label="copy.factor.componentSource"><ElSelect v-model="component.sourceVersionId" :data-test="'component-source-' + component.componentType" clearable filterable><ElOption v-for="source in workspace.sources.value" :key="source.sourceVersionId" :value="source.sourceVersionId" :label="sourceLabel(source)" /></ElSelect></ElFormItem>
              <ElFormItem :label="copy.factor.componentEvidence"><ElInput v-model="component.evidenceReference" :data-test="'component-evidence-' + component.componentType" /></ElFormItem>
            </article>
          </div>
          <ElButton data-test="factor-create" type="primary" :disabled="!canCreateFactor" :loading="workspace.saving.value" @click="submitFactor">{{ copy.factor.create }}</ElButton>
        </section>

        <section class="editor-section" :aria-label="copy.factor.lifecycle">
          <div class="section-heading"><div><h3>{{ copy.factor.lifecycle }}</h3><p>{{ copy.description }}</p></div></div>
          <ElEmpty v-if="!workspace.factors.value.length" :description="copy.factor.empty" :image-size="40" />
          <div v-else class="factor-list">
            <article v-for="factor in workspace.factors.value" :key="factor.factorVersionId" class="factor-row" :data-test="'factor-' + factor.factorVersionId">
              <div class="factor-row-heading">
                <div><strong>{{ factor.factorCode }}{{ copy.separator }}{{ copy.versionPrefix }}{{ factor.versionNo }}</strong><code>{{ factor.factorVersionId }}</code></div>
                <ElTag>{{ label(copy.statuses, factor.status) }}</ElTag>
              </div>
              <dl>
                <div><dt>{{ copy.factor.scope }}</dt><dd>{{ label(copy.scopes, factor.scopeType) }}</dd></div>
                <div><dt>{{ copy.factor.category }}</dt><dd>{{ label(copy.categories, factor.factorCategory) }}</dd></div>
                <div><dt>{{ copy.factor.energyItem }}</dt><dd>{{ factor.energyItemCode }}</dd></div>
                <div><dt>{{ copy.factor.sourceVersion }}</dt><dd>{{ sourceName(factor.sourceVersionId) }}</dd></div>
                <div><dt>{{ copy.factor.formula }}</dt><dd><code>{{ factor.formulaVersionId }}</code></dd></div>
                <div><dt>{{ copy.factor.rounding }}</dt><dd><code>{{ factor.roundingPolicyVersionId }}</code></dd></div>
                <div><dt>{{ copy.factor.revision }}</dt><dd>{{ factor.configRevision }}</dd></div>
                <div><dt>{{ copy.factor.effectiveRange }}</dt><dd>{{ factor.effectiveFrom }}{{ copy.rangeSeparator }}{{ factor.effectiveTo ?? copy.openEnded }}</dd></div>
              </dl>
              <ul v-if="factor.components.length" class="factor-components">
                <li v-for="component in factor.components" :key="component.componentId">
                  <strong>{{ label(copy.componentTypes, component.componentType) }}</strong>
                  <span>{{ String(component.value) }} {{ component.unit }}</span>
                  <code>{{ sourceName(component.sourceVersionId) }}</code>
                </li>
              </ul>
              <p v-if="factor.reviewComment" class="review-comment">{{ factor.reviewComment }}</p>
              <div class="factor-actions">
                <ElButton v-if="factor.status === 'PENDING_REVIEW'" :data-test="'review-' + factor.factorVersionId" :disabled="workspace.saving.value" @click="openReview(factor)">{{ copy.factor.review }}</ElButton>
                <ElButton v-if="factor.status === 'APPROVED'" :data-test="'activate-' + factor.factorVersionId" type="primary" :disabled="workspace.saving.value" :loading="workspace.saving.value" @click="activate(factor)">{{ copy.factor.activate }}</ElButton>
                <ElButton v-if="factor.status === 'ACTIVE'" :data-test="'disable-' + factor.factorVersionId" type="danger" :disabled="workspace.saving.value" :loading="workspace.saving.value" @click="disable(factor)">{{ copy.factor.disable }}</ElButton>
              </div>
            </article>
          </div>
        </section>
      </template>
    </ElCard>

    <ElDialog :model-value="reviewDraft !== null" :title="copy.review.title" width="min(100%, 520px)" @close="reviewDraft = null">
      <template v-if="reviewDraft">
        <ElAlert type="warning" :title="copy.review.notice" :closable="false" show-icon />
        <p><code>{{ reviewDraft.factor.factorVersionId }}</code>{{ copy.separator }}{{ reviewDraft.factor.factorCode }}</p>
        <ElRadioGroup v-model="reviewDraft.approved" data-test="review-decision">
          <ElRadio :label="true">{{ copy.review.approved }}</ElRadio>
          <ElRadio :label="false">{{ copy.review.rejected }}</ElRadio>
        </ElRadioGroup>
        <ElFormItem :label="copy.review.comment"><ElInput v-model="reviewDraft.reviewComment" data-test="review-comment" type="textarea" /></ElFormItem>
      </template>
      <template #footer>
        <ElButton data-test="review-cancel" @click="reviewDraft = null">{{ copy.review.cancel }}</ElButton>
        <ElButton data-test="review-submit" type="primary" :disabled="!reviewDraft?.reviewComment.trim() || workspace.saving.value" :loading="workspace.saving.value" @click="submitReview">{{ copy.review.submit }}</ElButton>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.carbon-factor-panel, .editor-section, .source-list, .factor-list, .component-list { display: grid; gap: var(--bec-space-section); min-width: 0; }
.panel-heading, .section-heading, .factor-row-heading, .factor-actions { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
.panel-heading h2, .section-heading h3, .component-list h4 { margin: 0; font-size: var(--bec-font-size-navigation); font-weight: var(--bec-font-weight-heading); }
.panel-heading p, .section-heading p { max-width: var(--bec-text-measure); margin: 0; color: var(--bec-color-text-secondary); }
.panel-alert, .panel-loading, .factor-editor { margin-top: var(--bec-space-section); }
.editor-section { padding-top: var(--bec-space-section); border-top: var(--bec-border-width) solid var(--bec-color-border); }
.form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 3)), 1fr)); gap: var(--bec-space-group); }
.form-grid :deep(.el-form-item), .component-row :deep(.el-form-item) { margin-bottom: 0; }
.date-input { width: 100%; min-height: var(--bec-ref-space-40); padding-inline: var(--bec-space-group); color: var(--bec-color-text-primary); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-control); font: inherit; }
.form-grid small { display: block; margin-top: var(--bec-space-tight); color: var(--bec-color-text-secondary); }
.source-list, .factor-list { grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 4)), 1fr)); }
.source-row, .factor-row, .component-row { display: grid; gap: var(--bec-space-tight); padding: var(--bec-space-group); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); background: var(--bec-color-surface-secondary); }
.source-row span, .source-row code, .factor-row code, .factor-row dt, .review-comment { color: var(--bec-color-text-secondary); overflow-wrap: anywhere; }
.identity-summary { display: grid; gap: var(--bec-space-tight); padding: var(--bec-space-group); border-left: var(--bec-border-width) solid var(--bec-color-primary); background: var(--bec-color-surface-secondary); }
.component-list h4 { margin-bottom: 0; }
.component-row { grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 2)), 1fr)); align-items: end; }
.component-row > strong { align-self: center; }
.factor-row dl { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 2)), 1fr)); gap: var(--bec-space-group); margin: 0; }
.factor-row dl div { display: grid; gap: var(--bec-space-tight); }
.factor-row dd { margin: 0; overflow-wrap: anywhere; }
.factor-components { display: grid; gap: var(--bec-space-tight); padding-left: var(--bec-space-section); margin: 0; }
.factor-components li { display: flex; flex-wrap: wrap; gap: var(--bec-space-tight); }
.review-comment { margin: 0; }
</style>
