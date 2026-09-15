<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ElAlert, ElButton, ElCard, ElCheckbox, ElEmpty, ElForm, ElFormItem, ElInput, ElInputNumber, ElMessage,
  ElOption, ElPagination, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag, ElTree, Plus, RefreshCw, Search, Trash2,
} from '@/shared/ui'
import { formatDateTime } from '@/shared/utils/format'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { t } from '@/locales'
import { getDeviceProduct, listDeviceProducts, type DeviceProductDetail, type DeviceProductListItem } from '@/modules/device-onboarding/public'
import { useProtocolConfiguration } from '../composables/use-protocol-configuration'
import { applyProductContract, validateProtocolConfiguration, type InspectedField, type ProtocolMapping } from '../models/protocol-configuration'

type FieldTreeNode = { id: string; label: string; path?: string; field?: InspectedField; children?: FieldTreeNode[] }

const router = useRouter()
const management = useProtocolConfiguration()
const productPage = ref<{ page: number; size: number; total: number; items: DeviceProductListItem[] }>({ page: 1, size: 20, total: 0, items: [] })
const product = ref<DeviceProductDetail | null>(null)
const productsLoading = ref(false)
const productsError = ref<string | null>(null)
const productStatus = ref<'DRAFT' | 'ENABLED'>('DRAFT')
const productKeyword = ref('')
const samplePayload = ref('')
const selectedField = ref<InspectedField | null>(null)
const validationKey = ref<string | null>(null)
let productListGeneration = 0
let productDetailGeneration = 0
let editorGeneration = 0

const sampleBytes = computed(() => new TextEncoder().encode(samplePayload.value).byteLength)
const fieldTree = computed(() => buildFieldTree(management.fields.value))
const productPoints = computed(() => product.value?.points.filter(point => point.enabled) ?? [])
const previewReady = computed(() => !validateProtocolConfiguration(management.form.value, product.value) && sampleBytes.value > 0 && sampleBytes.value <= 64 * 1024)

watch(() => management.form.value, () => management.invalidatePreview(), { deep: true })
watch(samplePayload, () => {
  management.invalidatePreview()
  management.invalidateInspection()
  selectedField.value = null
})

async function loadProducts(page = 1, keyword = productKeyword.value) {
  const owner = ++productListGeneration
  productsLoading.value = true
  productsError.value = null
  try {
    const result = await listDeviceProducts({ page, size: productPage.value.size, status: productStatus.value, keyword: keyword.trim() || undefined })
    if (owner === productListGeneration) productPage.value = result
  } catch (reason) {
    if (owner === productListGeneration) productsError.value = requestErrorMessage(reason)
  } finally {
    if (owner === productListGeneration) productsLoading.value = false
  }
}

async function selectProduct(productId: string) {
  const owner = ++productDetailGeneration
  product.value = null
  validationKey.value = null
  if (!productId) return
  try {
    const detail = await getDeviceProduct(productId)
    if (owner !== productDetailGeneration) return
    product.value = detail
    if (detail.status === 'DRAFT' || detail.status === 'ENABLED') productStatus.value = detail.status
    management.form.value = applyProductContract(management.form.value, detail)
  } catch {
    if (owner === productDetailGeneration) validationKey.value = 'product'
  }
}

async function loadSelectedDraft(id: string) {
  if (!id) return
  const owner = ++editorGeneration
  try {
    const detail = await management.selectDraft(id)
    if (owner !== editorGeneration) return
    await selectProduct(detail.configuration.productId)
    if (owner !== editorGeneration) return
    void loadProducts(1)
    samplePayload.value = ''
    selectedField.value = null
  } catch {
    // 请求错误已进入页面受控错误区。
  }
}

function newDraft() {
  ++editorGeneration
  ++productDetailGeneration
  ++productListGeneration
  productsLoading.value = false
  management.newDraft()
  product.value = null
  samplePayload.value = ''
  selectedField.value = null
  validationKey.value = null
}

async function inspect() {
  if (!samplePayload.value.trim()) return showValidation('sampleRequired')
  if (sampleBytes.value > 64 * 1024) return showValidation('sampleTooLarge')
  validationKey.value = null
  try {
    await management.inspect(samplePayload.value)
    selectedField.value = null
    ElMessage.success(t('protocolConfiguration.messages.inspected'))
  } catch {
    // 服务端结构校验与权限错误由统一错误状态展示。
  }
}

function selectFieldNode(node: FieldTreeNode) {
  if (node.field) selectedField.value = node.field
}

function setPath(kind: 'identity' | 'discriminator' | 'timestamp') {
  const field = selectedField.value
  if (!field) return showValidation('selectedField')
  if (kind === 'identity') management.form.value.identityPath = field.path
  if (kind === 'discriminator') management.form.value.discriminatorPath = field.path
  if (kind === 'timestamp') management.form.value.timestampPath = field.path
  validationKey.value = null
}

function addMapping() {
  const field = selectedField.value
  if (!field) return showValidation('selectedField')
  if (field.type !== 'NUMBER') return showValidation('numericMapping')
  if (management.form.value.mappings.length >= 128) return showValidation('mappingLimit')
  management.form.value.mappings.push({ sourcePath: field.path, metricCode: '', sourceUnit: '', targetUnit: '', scale: '1', offset: '0', required: false, enabled: true, sortOrder: management.form.value.mappings.length })
  validationKey.value = null
}

function updateMetric(row: unknown, metricCode: string) {
  const mapping = row as ProtocolMapping
  const point = productPoints.value.find(item => item.metricCode === metricCode)
  mapping.metricCode = metricCode
  mapping.targetUnit = point?.unit ?? ''
  mapping.required = point?.required ?? false
}

function isRequiredMetric(metricCode: string) {
  return productPoints.value.find(point => point.metricCode === metricCode)?.required === true
}

function removeMapping(index: number) {
  management.form.value.mappings.splice(index, 1)
  management.form.value.mappings.forEach((mapping, order) => { mapping.sortOrder = order })
}

async function save() {
  const issue = validateProtocolConfiguration(management.form.value, product.value)
  if (issue) return showValidation(issue)
  validationKey.value = null
  const owner = editorGeneration
  try {
    const detail = await management.save()
    if (detail && owner === editorGeneration) ElMessage.success(t('protocolConfiguration.messages.saved'))
  } catch {
    // 冲突、权限和传输错误统一保留在页面错误区。
  }
}

async function preview() {
  const issue = validateProtocolConfiguration(management.form.value, product.value)
  if (issue) return showValidation(issue)
  if (!samplePayload.value.trim()) return showValidation('sampleRequired')
  if (sampleBytes.value > 64 * 1024) return showValidation('sampleTooLarge')
  validationKey.value = null
  try { await management.runPreview(samplePayload.value, Date.now()) } catch {
    // 结构与权限错误由统一请求层转换；语义失败仍以 HTTP 200 结果呈现。
  }
}

function showValidation(key: string) { validationKey.value = key }

function buildFieldTree(fields: InspectedField[]): FieldTreeNode[] {
  const roots: FieldTreeNode[] = []
  for (const field of fields) {
    const segments = pointerSegments(field.path)
    if (!segments.length) {
      roots.push({ id: '$', label: t('protocolConfiguration.fieldRoot'), path: field.path, field })
      continue
    }
    let level = roots
    let current = ''
    segments.forEach((segment, index) => {
      current += '/' + segment.replace(/~/g, '~0').replace(/\//g, '~1')
      let node = level.find(item => item.id === current)
      if (!node) {
        node = { id: current, label: segment, children: [] }
        level.push(node)
      }
      if (index === segments.length - 1) { node.path = field.path; node.field = field }
      level = node.children!
    })
  }
  return roots
}

function pointerSegments(path: string) {
  return path.split('/').slice(1).map(segment => segment.replace(/~1/g, '/').replace(/~0/g, '~'))
}

function searchProducts(keyword: string) {
  productKeyword.value = keyword
  void loadProducts(1)
}

function changeProductStatus(status: 'DRAFT' | 'ENABLED') {
  ++productDetailGeneration
  productStatus.value = status
  product.value = null
  management.form.value.productId = ''
  management.form.value.profileCode = ''
  management.form.value.identityType = ''
  management.form.value.mappings = []
  productKeyword.value = ''
  void loadProducts(1)
}

onMounted(() => { void Promise.all([management.loadDrafts(), loadProducts()]).catch(() => undefined) })
</script>

<template>
  <section class="protocol-page">
    <header class="page-heading">
      <div><h1>{{ t('protocolConfiguration.title') }}</h1><p>{{ t('protocolConfiguration.description') }}</p></div>
      <div class="heading-actions"><ElButton @click="router.push('/configuration/ingestion/products')">{{ t('protocolConfiguration.actions.openProducts') }}</ElButton><ElButton :icon="Plus" @click="newDraft">{{ t('protocolConfiguration.actions.newDraft') }}</ElButton><ElButton type="primary" :loading="management.saving.value" @click="save">{{ t('protocolConfiguration.actions.saveDraft') }}</ElButton></div>
    </header>
    <ElAlert :title="t('protocolConfiguration.draftNotice')" type="warning" show-icon :closable="false" />
    <ElAlert v-if="management.error.value" :title="management.error.value" type="error" show-icon :closable="false" />
    <ElAlert v-if="productsError" :title="productsError" type="error" show-icon :closable="false" />
    <ElAlert v-if="validationKey" :title="t(`protocolConfiguration.validation.${validationKey}`)" type="error" show-icon :closable="false" />

    <div class="editor-grid">
      <div class="editor-column">
        <ElCard shadow="never">
          <template #header><div class="section-heading"><h2>{{ t('protocolConfiguration.sections.basics') }}</h2><ElTag type="warning">{{ t('protocolConfiguration.states.draft') }}</ElTag></div></template>
          <ElSkeleton v-if="management.loading.value" animated :rows="5" />
          <ElForm v-else label-position="top" class="form-grid">
            <ElFormItem :label="t('protocolConfiguration.labels.draft')" class="span-2"><ElSelect :model-value="management.draft.value?.id ?? ''" filterable clearable :placeholder="t('protocolConfiguration.placeholders.draft')" @change="loadSelectedDraft"><ElOption v-for="item in management.drafts.value.items" :key="item.id" :value="item.id" :label="`${item.configuration.name} · r${item.revision}`" /></ElSelect></ElFormItem>
            <ElFormItem :label="t('protocolConfiguration.labels.name')" required><ElInput v-model="management.form.value.name" maxlength="100" :placeholder="t('protocolConfiguration.placeholders.name')" /></ElFormItem>
            <ElFormItem :label="t('protocolConfiguration.labels.productStatus')"><ElSelect :model-value="productStatus" @change="changeProductStatus"><ElOption value="DRAFT" :label="t('protocolConfiguration.productStatus.draft')" /><ElOption value="ENABLED" :label="t('protocolConfiguration.productStatus.enabled')" /></ElSelect></ElFormItem>
            <ElFormItem :label="t('protocolConfiguration.labels.product')" required><ElSelect :model-value="management.form.value.productId" filterable remote :remote-method="searchProducts" :loading="productsLoading" :placeholder="t('protocolConfiguration.placeholders.product')" @change="selectProduct"><ElOption v-for="item in productPage.items" :key="item.productId" :value="item.productId" :label="`${item.productName} · ${item.productCode}`" /></ElSelect></ElFormItem>
            <div class="span-2 product-pagination"><ElPagination size="small" layout="total, prev, pager, next" :current-page="productPage.page" :page-size="productPage.size" :total="productPage.total" @current-change="page => loadProducts(page)" /></div>
            <ElFormItem :label="t('protocolConfiguration.labels.profileCode')"><ElInput v-model="management.form.value.profileCode" disabled /></ElFormItem>
            <ElFormItem :label="t('protocolConfiguration.labels.identityType')"><ElInput v-model="management.form.value.identityType" disabled /></ElFormItem>
            <ElFormItem :label="t('protocolConfiguration.labels.sourceTopic')" required class="span-2"><ElInput v-model="management.form.value.sourceTopic" maxlength="255" :placeholder="t('protocolConfiguration.placeholders.topic')" /></ElFormItem>
            <ElFormItem :label="t('protocolConfiguration.labels.identityPath')" required><ElInput v-model="management.form.value.identityPath" readonly /></ElFormItem>
            <ElFormItem :label="t('protocolConfiguration.labels.timestampPath')"><ElInput v-model="management.form.value.timestampPath" readonly clearable /></ElFormItem>
            <ElFormItem :label="t('protocolConfiguration.labels.discriminatorPath')"><ElInput v-model="management.form.value.discriminatorPath" readonly clearable /></ElFormItem>
            <ElFormItem :label="t('protocolConfiguration.labels.discriminatorValue')"><ElInput v-model="management.form.value.discriminatorValue" maxlength="100" :disabled="!management.form.value.discriminatorPath" :placeholder="t('protocolConfiguration.placeholders.discriminatorValue')" /></ElFormItem>
          </ElForm>
          <div class="pagination"><ElPagination size="small" layout="total, prev, next" :current-page="management.drafts.value.page" :page-size="management.drafts.value.size" :total="management.drafts.value.total" @current-change="page => management.loadDrafts(page).catch(() => undefined)" /></div>
        </ElCard>

        <ElCard shadow="never">
          <template #header><div class="section-heading"><h2>{{ t('protocolConfiguration.sections.sample') }}</h2><span class="byte-count">{{ t('protocolConfiguration.counters.sampleBytes', { count: sampleBytes }) }}</span></div></template>
          <ElInput v-model="samplePayload" type="textarea" :rows="10" resize="vertical" :placeholder="t('protocolConfiguration.placeholders.sample')" />
          <div class="section-actions"><ElButton type="primary" :icon="Search" :loading="management.inspecting.value" @click="inspect">{{ t('protocolConfiguration.actions.inspect') }}</ElButton><ElButton :icon="RefreshCw" :disabled="!previewReady" :loading="management.previewing.value" @click="preview">{{ t('protocolConfiguration.actions.preview') }}</ElButton></div>
        </ElCard>
      </div>

      <ElCard shadow="never" class="field-panel">
        <template #header><div class="section-heading"><h2>{{ t('protocolConfiguration.sections.fields') }}</h2><ElTag v-if="selectedField" type="info">{{ selectedField.type }}</ElTag></div></template>
        <ElEmpty v-if="!fieldTree.length" :description="t('protocolConfiguration.states.fieldEmpty')" />
        <template v-else>
          <ElTree :data="fieldTree" node-key="id" default-expand-all highlight-current @node-click="selectFieldNode"><template #default="{ data }"><div class="tree-node"><span>{{ data.label }}</span><span v-if="data.field" class="field-value">{{ data.field.value }}</span></div></template></ElTree>
          <div v-if="selectedField" class="field-selection">
            <dl><dt>{{ t('protocolConfiguration.labels.selectedField') }}</dt><dd>{{ selectedField.path }}</dd><dt>{{ t('protocolConfiguration.labels.sampleValue') }}</dt><dd>{{ selectedField.value || '—' }}</dd></dl>
            <div class="field-actions"><ElButton size="small" @click="setPath('identity')">{{ t('protocolConfiguration.actions.useIdentity') }}</ElButton><ElButton size="small" @click="setPath('discriminator')">{{ t('protocolConfiguration.actions.useDiscriminator') }}</ElButton><ElButton size="small" @click="setPath('timestamp')">{{ t('protocolConfiguration.actions.useTimestamp') }}</ElButton><ElButton size="small" type="primary" :disabled="selectedField.type !== 'NUMBER'" @click="addMapping">{{ t('protocolConfiguration.actions.addMapping') }}</ElButton></div>
          </div>
        </template>
      </ElCard>
    </div>

    <ElCard shadow="never">
      <template #header><div class="section-heading"><h2>{{ t('protocolConfiguration.sections.mapping') }}</h2><span>{{ t('protocolConfiguration.counters.mappings', { count: management.form.value.mappings.length }) }}</span></div></template>
      <ElTable :data="management.form.value.mappings">
        <ElTableColumn :label="t('protocolConfiguration.labels.sourcePath')" prop="sourcePath" min-width="180" show-overflow-tooltip />
        <ElTableColumn :label="t('protocolConfiguration.labels.metric')" min-width="210"><template #default="{ row }"><ElSelect :model-value="row.metricCode" filterable @change="value => updateMetric(row, value)"><ElOption v-for="point in productPoints" :key="point.metricCode" :value="point.metricCode" :label="`${point.pointNameTemplate} · ${point.metricCode}`" /></ElSelect></template></ElTableColumn>
        <ElTableColumn :label="t('protocolConfiguration.labels.sourceUnit')" min-width="125"><template #default="{ row }"><ElInput v-model="row.sourceUnit" maxlength="20" :placeholder="t('protocolConfiguration.placeholders.sourceUnit')" /></template></ElTableColumn>
        <ElTableColumn :label="t('protocolConfiguration.labels.targetUnit')" min-width="100"><template #default="{ row }"><ElInput v-model="row.targetUnit" disabled /></template></ElTableColumn>
        <ElTableColumn :label="t('protocolConfiguration.labels.scale')" min-width="100"><template #default="{ row }"><ElInput v-model="row.scale" maxlength="40" /></template></ElTableColumn>
        <ElTableColumn :label="t('protocolConfiguration.labels.offset')" min-width="100"><template #default="{ row }"><ElInput v-model="row.offset" maxlength="40" /></template></ElTableColumn>
        <ElTableColumn :label="t('protocolConfiguration.labels.required')" width="76"><template #default="{ row }"><ElCheckbox v-model="row.required" :disabled="isRequiredMetric(row.metricCode)" /></template></ElTableColumn>
        <ElTableColumn :label="t('protocolConfiguration.labels.enabled')" width="76"><template #default="{ row }"><ElCheckbox v-model="row.enabled" /></template></ElTableColumn>
        <ElTableColumn :label="t('protocolConfiguration.labels.sortOrder')" width="110"><template #default="{ row }"><ElInputNumber v-model="row.sortOrder" :min="0" controls-position="right" /></template></ElTableColumn>
        <ElTableColumn width="70" fixed="right"><template #default="{ $index }"><ElButton link type="danger" :icon="Trash2" :aria-label="t('protocolConfiguration.actions.removeMapping')" @click="removeMapping($index)" /></template></ElTableColumn>
        <template #empty><ElEmpty :description="t('protocolConfiguration.states.mappingEmpty')" /></template>
      </ElTable>
    </ElCard>

    <ElCard shadow="never">
      <template #header><div class="section-heading"><h2>{{ t('protocolConfiguration.sections.preview') }}</h2><ElTag v-if="management.preview.value" :type="management.preview.value.success ? 'success' : 'danger'">{{ t(`protocolConfiguration.states.${management.preview.value.success ? 'success' : 'failed'}`) }}</ElTag></div></template>
      <ElEmpty v-if="!management.preview.value" :description="t('protocolConfiguration.states.previewEmpty')" />
      <template v-else>
        <dl class="preview-summary"><div><dt>{{ t('protocolConfiguration.labels.identityType') }}</dt><dd>{{ management.preview.value.identityType || '—' }}</dd></div><div><dt>{{ t('protocolConfiguration.labels.identityValue') }}</dt><dd>{{ management.preview.value.identityValue || '—' }}</dd></div><div><dt>{{ t('protocolConfiguration.labels.timeSource') }}</dt><dd>{{ management.preview.value.timeSource || '—' }}</dd></div><div><dt>{{ t('protocolConfiguration.labels.eventTime') }}</dt><dd>{{ management.preview.value.eventTime ? formatDateTime(management.preview.value.eventTime) : '—' }}</dd></div></dl>
        <ElTable v-if="management.preview.value.success" :data="management.preview.value.metrics" row-key="metricCode"><ElTableColumn :label="t('protocolConfiguration.labels.metric')" prop="metricCode" min-width="150" /><ElTableColumn :label="t('protocolConfiguration.labels.sourcePath')" prop="sourcePath" min-width="170" /><ElTableColumn :label="t('protocolConfiguration.labels.rawValue')" prop="rawValue" min-width="110" /><ElTableColumn :label="t('protocolConfiguration.labels.value')" prop="value" min-width="110" /><ElTableColumn :label="t('protocolConfiguration.labels.targetUnit')" prop="unit" min-width="90" /><ElTableColumn :label="t('protocolConfiguration.labels.status')" min-width="120"><template #default="{ row }"><ElTag :type="row.status === 'PRESENT' ? 'success' : 'info'">{{ t(`protocolConfiguration.states.${row.status === 'PRESENT' ? 'present' : 'missingOptional'}`) }}</ElTag></template></ElTableColumn></ElTable>
        <ElTable v-else :data="management.preview.value.errors"><ElTableColumn :label="t('protocolConfiguration.labels.result')" prop="code" min-width="150" /><ElTableColumn :label="t('protocolConfiguration.labels.errorPath')" prop="path" min-width="180" /><ElTableColumn :label="t('protocolConfiguration.labels.status')" prop="message" min-width="260" /></ElTable>
      </template>
    </ElCard>
  </section>
</template>

<style scoped>
.protocol-page, .editor-column { display: grid; gap: var(--bec-space-section); min-width: 0; }
.page-heading, .heading-actions, .section-heading, .section-actions, .field-actions { display: flex; align-items: center; gap: var(--bec-space-group); }
.page-heading, .section-heading { justify-content: space-between; }
.page-heading { align-items: flex-start; }
.heading-actions, .section-actions { flex-wrap: wrap; justify-content: flex-end; }
h1, h2, p, dl, dd { margin: 0; }
h1 { font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); }
h2 { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
p, .byte-count, .field-value, dt { color: var(--bec-color-text-secondary); }
p { max-width: var(--bec-text-measure); }
.editor-grid { display: grid; grid-template-columns: minmax(0, 3fr) minmax(var(--bec-navigation-width), 2fr); gap: var(--bec-space-section); align-items: stretch; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 var(--bec-space-group); }
.span-2 { grid-column: 1 / -1; }
.pagination, .section-actions { margin-top: var(--bec-space-group); }
.pagination, .product-pagination { display: flex; justify-content: flex-end; }
.product-pagination { margin-bottom: var(--bec-space-group); }
.field-panel :deep(.el-card__body) { display: grid; gap: var(--bec-space-group); align-content: start; }
.tree-node { display: flex; justify-content: space-between; gap: var(--bec-space-group); width: 100%; min-width: 0; }
.field-value { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: var(--bec-navigation-width); }
.field-selection { display: grid; gap: var(--bec-space-group); padding-top: var(--bec-space-group); border-top: var(--bec-border-width) solid var(--bec-color-divider); }
.field-selection dl { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: var(--bec-space-tight) var(--bec-space-group); }
.field-selection dd { overflow-wrap: anywhere; }
.field-actions { flex-wrap: wrap; }
.preview-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--bec-space-group); margin-bottom: var(--bec-space-group); }
.preview-summary div { display: grid; gap: var(--bec-space-tight); }
.preview-summary dd { overflow-wrap: anywhere; }
@media (max-width: 960px) {
  .page-heading { display: grid; }
  .heading-actions { justify-content: flex-start; }
  .editor-grid, .preview-summary { grid-template-columns: 1fr; }
}
@media (max-width: 640px) {
  .form-grid { grid-template-columns: 1fr; }
  .span-2 { grid-column: auto; }
}
</style>
