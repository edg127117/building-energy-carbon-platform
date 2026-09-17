<script setup lang="ts">
import { computed, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import {
  ElAlert, ElButton, ElCard, ElEmpty, ElForm, ElFormItem, ElInput, ElMessage, ElMessageBox,
  ElOption, ElPagination, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag, ElTree, Plus, RefreshCw, Search,
} from '@/shared/ui'
import { formatDateTime } from '@/shared/utils/format'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { t } from '@/locales'
import { getDeviceProduct, listDeviceProducts, ProductWorkspace, type DeviceProductDetail, type DeviceProductListItem } from '@/modules/device-onboarding/public'
import { useProtocolConfiguration } from '../composables/use-protocol-configuration'
import { emptyProtocolConfiguration, applyProductContract, validateProtocolConfiguration, type InspectedField } from '../models/protocol-configuration'
import MetricMappingEditor from '../components/MetricMappingEditor.vue'
import ProtocolPublicationPanel from '../components/ProtocolPublicationPanel.vue'

type FieldTreeNode = { id: string; label: string; path?: string; field?: InspectedField; children?: FieldTreeNode[] }

const router = useRouter()
const route = useRoute()
const step = ref(0)
const preparing = ref(false)
const publicationPanel = ref<InstanceType<typeof ProtocolPublicationPanel> | null>(null)
const dirty = computed(() => JSON.stringify(management.form.value) !== JSON.stringify(management.draft.value?.configuration ?? emptyProtocolConfiguration()))
const missingPoints = computed(() => productPoints.value.filter(point => point.required && !management.form.value.mappings.some(mapping => mapping.enabled && mapping.metricCode === point.metricCode)))
async function confirmDiscard() {
  if (!dirty.value && !samplePayload.value) return true
  try { await ElMessageBox.confirm(t('protocolConfiguration.flow.leaveWarning'), t('protocolConfiguration.flow.confirm'), { type: 'warning' }); return true } catch { return false }
}
onBeforeRouteLeave(() => confirmDiscard())
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || samplePayload.value) { event.preventDefault(); event.returnValue = '' } }
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
async function preparePublication() {
  if (preparing.value) return
  const issue = validateProtocolConfiguration(management.form.value, product.value)
  if (issue) { showValidation(issue); return }
  if (product.value?.status !== 'ENABLED') { showValidation('productDisabled'); return }
  preparing.value = true
  try {
    // 保存可能归一化配置，因此只用保存后的当前内容重新预览，再冻结相同修订。
    const saved = await management.save()
    if (!saved || dirty.value) return
    ElMessage.success(t('protocolConfiguration.messages.saved'))
    await management.runPreview(samplePayload.value, Date.now())
    if (!management.preview.value?.success || dirty.value) return
    step.value = 3
    await nextTick()
    await publicationPanel.value?.freezeCurrentDraft()
  } catch { /* 保存与解析错误由页面展示；失败后不继续冻结。 */ }
  finally { preparing.value = false }
}
function locateError(path: string) {
  const mapping = management.form.value.mappings.find(item => item.sourcePath === path || item.metricCode === path)
  if (mapping) locateMetric(mapping.metricCode)
  else step.value = 1
}
function timeSourceName(source: string | null) {
  return source === 'DEVICE_REPORTED' || source === 'ADAPTER_RECEIVED' ? t('protocolConfiguration.timeSources.' + source) : source || t('common.missing')
}
function metricName(code: string) { return productPoints.value.find(point => point.metricCode === code)?.pointNameTemplate ?? code }
function locateMetric(code: string) { step.value = 1; requestAnimationFrame(() => document.getElementById('metric-' + code)?.scrollIntoView({ block: 'center', behavior: 'smooth' })) }

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

async function selectProduct(productId: string, internal = false) {
  if (!internal && management.form.value.mappings.length && !await confirmDiscard()) return
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
  if (preparing.value) return
  if (!id || !await confirmDiscard()) return
  const owner = ++editorGeneration
  try {
    const detail = await management.selectDraft(id)
    if (owner !== editorGeneration) return
    await selectProduct(detail.configuration.productId, true)
    if (owner !== editorGeneration) return
    void loadProducts(1)
    samplePayload.value = ''
    selectedField.value = null
  } catch {
    // 请求错误已进入页面受控错误区。
  }
}

async function newDraft() {
  if (preparing.value) return
  if (!await confirmDiscard()) return
  step.value = 0
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

function openMatchingPendingDevices() {
  const profileCode = management.form.value.profileCode.trim()
  if (profileCode) void router.push({ path: '/configuration/ingestion/pendingDevices', query: { profileCode, draftId: management.draft.value?.id ?? undefined } })
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
  try {
    await management.runPreview(samplePayload.value, Date.now())
    if (management.preview.value) step.value = 2
  } catch {
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

async function changeProductStatus(status: 'DRAFT' | 'ENABLED') {
  if (management.form.value.mappings.length && !await confirmDiscard()) return
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

onMounted(() => {
  void Promise.all([management.loadDrafts(), loadProducts()]).catch(() => undefined)
  if (typeof route.query.draftId === 'string') void loadSelectedDraft(route.query.draftId)
})
</script>

<template>
  <section class="protocol-page">
    <header class="page-heading">
      <div><h1>{{ t('protocolConfiguration.title') }}</h1><p>{{ t('protocolConfiguration.description') }}</p></div>
      <div class="heading-actions"><ElButton @click="router.push('/configuration/ingestion/products')">{{ t('protocolConfiguration.actions.openProducts') }}</ElButton><ElButton :disabled="!management.form.value.profileCode.trim()" @click="openMatchingPendingDevices">{{ t('protocolConfiguration.actions.openPendingDevices') }}</ElButton><ElButton :icon="Plus" @click="newDraft">{{ t('protocolConfiguration.actions.newDraft') }}</ElButton><ElButton type="primary" :loading="management.saving.value" @click="save">{{ t('protocolConfiguration.actions.saveDraft') }}</ElButton></div>
    </header>
    <ElAlert :title="t('protocolConfiguration.draftNotice')" type="warning" show-icon :closable="false" />
    <ElAlert v-if="management.error.value" :title="management.error.value" type="error" show-icon :closable="false" />
    <ElAlert v-if="productsError" :title="productsError" type="error" show-icon :closable="false" />
    <ElAlert v-if="validationKey" :title="t(`protocolConfiguration.validation.${validationKey}`)" type="error" show-icon :closable="false" />

    <nav class="step-navigation" :aria-label="t('protocolConfiguration.flow.steps')"><ElButton v-for="(label, index) in ['product', 'mapping', 'preview', 'publication', 'device']" :key="label" :type="step === index ? 'primary' : 'default'" :aria-current="step === index ? 'step' : undefined" @click="step = index">{{ index + 1 }}{{ "." }}{{ t('protocolConfiguration.flow.' + label) }}</ElButton></nav>
    <div class="flow-summary"><strong>{{ product?.productName || t('protocolConfiguration.validation.product') }}</strong><span>{{ t(management.saving.value ? 'protocolConfiguration.flow.saving' : dirty ? 'protocolConfiguration.flow.unsaved' : 'protocolConfiguration.flow.saved') }}</span><span>{{ t('protocolConfiguration.flow.remaining', { count: missingPoints.length }) }}</span></div>
    <fieldset :disabled="preparing" class="editor-lock">
      <div v-show="step < 2" class="editor-grid" :class="{ 'product-stage': step === 0 }">
        <div class="editor-column">
          <ElCard v-show="step === 0" shadow="never">
            <template #header><div class="section-heading"><h2>{{ t('protocolConfiguration.sections.basics') }}</h2><ElTag type="warning">{{ t('protocolConfiguration.states.draft') }}</ElTag></div></template>
            <ElSkeleton v-if="management.loading.value" animated :rows="5" />
            <ElForm v-else label-position="top" class="form-grid">
              <ElFormItem :label="t('protocolConfiguration.labels.draft')" class="span-2"><ElSelect :model-value="management.draft.value?.id ?? ''" filterable clearable :placeholder="t('protocolConfiguration.placeholders.draft')" @change="loadSelectedDraft"><ElOption v-for="item in management.drafts.value.items" :key="item.id" :value="item.id" :label="t('protocolConfiguration.publication.versionLabel', { name: item.configuration.name, revision: item.revision })" /></ElSelect></ElFormItem>
              <ElFormItem :label="t('protocolConfiguration.labels.name')" required><ElInput v-model="management.form.value.name" maxlength="100" :placeholder="t('protocolConfiguration.placeholders.name')" /></ElFormItem>
              <ElFormItem :label="t('protocolConfiguration.labels.productStatus')"><ElSelect :model-value="productStatus" @change="changeProductStatus"><ElOption value="DRAFT" :label="t('protocolConfiguration.productStatus.draft')" /><ElOption value="ENABLED" :label="t('protocolConfiguration.productStatus.enabled')" /></ElSelect></ElFormItem>
              <ElFormItem :label="t('protocolConfiguration.labels.product')" required><ElSelect :model-value="management.form.value.productId" filterable remote :remote-method="searchProducts" :loading="productsLoading" :placeholder="t('protocolConfiguration.placeholders.product')" @change="selectProduct"><ElOption v-for="item in productPage.items" :key="item.productId" :value="item.productId" :label="`${item.productName} · ${item.productCode}`" /></ElSelect></ElFormItem>
              <div class="span-2 product-pagination"><ElPagination size="small" layout="total, prev, pager, next" :current-page="productPage.page" :page-size="productPage.size" :total="productPage.total" @current-change="page => loadProducts(page)" /></div>
              <details class="span-2">
                <summary>{{ t('protocolConfiguration.flow.technical') }}</summary><ElFormItem :label="t('protocolConfiguration.labels.profileCode')"><ElInput v-model="management.form.value.profileCode" disabled /></ElFormItem>
                <ElFormItem :label="t('protocolConfiguration.labels.identityType')"><ElInput v-model="management.form.value.identityType" disabled /></ElFormItem>
              </details>
            </ElForm>
            <ProductWorkspace :product="product" @selected="id => selectProduct(id, true)" />
            <ElButton type="primary" :disabled="!product" @click="step = 1">{{ t('protocolConfiguration.flow.mapping') }}</ElButton>
            <div class="pagination"><ElPagination size="small" layout="total, prev, next" :current-page="management.drafts.value.page" :page-size="management.drafts.value.size" :total="management.drafts.value.total" @current-change="page => management.loadDrafts(page).catch(() => undefined)" /></div>
          </ElCard>

          <ElCard v-show="step === 1" shadow="never">
            <ElForm label-position="top" class="form-grid">
              <ElFormItem :label="t('protocolConfiguration.labels.sourceTopic')" required class="span-2"><ElInput v-model="management.form.value.sourceTopic" maxlength="255" :placeholder="t('protocolConfiguration.placeholders.topic')" /></ElFormItem>
              <ElFormItem :label="t('protocolConfiguration.labels.identityPath')" required><ElInput v-model="management.form.value.identityPath" readonly /></ElFormItem>
              <ElFormItem :label="t('protocolConfiguration.labels.timestampPath')"><ElInput v-model="management.form.value.timestampPath" readonly clearable /></ElFormItem>
              <ElFormItem :label="t('protocolConfiguration.labels.discriminatorPath')"><ElInput v-model="management.form.value.discriminatorPath" readonly clearable /></ElFormItem>
              <ElFormItem :label="t('protocolConfiguration.labels.discriminatorValue')"><ElInput v-model="management.form.value.discriminatorValue" maxlength="100" :disabled="!management.form.value.discriminatorPath" :placeholder="t('protocolConfiguration.placeholders.discriminatorValue')" /></ElFormItem>
            </ElForm>
          </ElCard>
          <ElCard v-show="step === 1" shadow="never">
            <template #header><div class="section-heading"><h2>{{ t('protocolConfiguration.sections.sample') }}</h2><span class="byte-count">{{ t('protocolConfiguration.counters.sampleBytes', { count: sampleBytes }) }}</span></div></template>
            <ElInput v-model="samplePayload" type="textarea" :rows="10" resize="vertical" :placeholder="t('protocolConfiguration.placeholders.sample')" />
            <div class="section-actions"><ElButton type="primary" :icon="Search" :loading="management.inspecting.value" @click="inspect">{{ t('protocolConfiguration.actions.inspect') }}</ElButton><ElButton :icon="RefreshCw" :disabled="!previewReady" :loading="management.previewing.value" @click="preview">{{ t('protocolConfiguration.actions.preview') }}</ElButton></div>
          </ElCard>
          <ElCard v-show="step === 1" shadow="never" class="field-panel">
            <template #header><div class="section-heading"><h2>{{ t('protocolConfiguration.sections.fields') }}</h2><ElTag v-if="selectedField" type="info">{{ t('protocolConfiguration.fieldTypes.' + selectedField.type) }}</ElTag></div></template>
            <ElEmpty v-if="!fieldTree.length" :description="t('protocolConfiguration.states.fieldEmpty')" />
            <template v-else>
              <ElTree :data="fieldTree" node-key="id" default-expand-all highlight-current @node-click="selectFieldNode"><template #default="{ data }"><div class="tree-node"><span>{{ data.label }}</span><span v-if="data.field" class="field-value">{{ data.field.value }}</span></div></template></ElTree>
              <div v-if="selectedField" class="field-selection">
                <dl><dt>{{ t('protocolConfiguration.labels.selectedField') }}</dt><dd>{{ selectedField.path }}</dd><dt>{{ t('protocolConfiguration.labels.sampleValue') }}</dt><dd>{{ selectedField.value || '—' }}</dd></dl>
                <div class="field-actions"><ElButton size="small" @click="setPath('identity')">{{ t('protocolConfiguration.actions.useIdentity') }}</ElButton><ElButton size="small" @click="setPath('discriminator')">{{ t('protocolConfiguration.actions.useDiscriminator') }}</ElButton><ElButton size="small" @click="setPath('timestamp')">{{ t('protocolConfiguration.actions.useTimestamp') }}</ElButton></div>
              </div>
            </template>
          </ElCard>
        </div>

        <ElCard v-show="step === 1" shadow="never">
          <template #header><h2>{{ t('protocolConfiguration.sections.mapping') }}</h2></template>
          <MetricMappingEditor v-model:mappings="management.form.value.mappings" :points="productPoints" :fields="management.fields.value" />
          <div class="section-actions"><ElButton type="primary" :disabled="!previewReady" :loading="management.previewing.value" @click="preview().then(() => { step = 2 })">{{ t('protocolConfiguration.actions.preview') }}</ElButton></div>
        </ElCard>
      </div>


      <ElCard v-show="step === 2" shadow="never">
        <template #header><div class="section-heading"><h2>{{ t('protocolConfiguration.sections.preview') }}</h2><ElTag v-if="management.preview.value" :type="management.preview.value.success ? 'success' : 'danger'">{{ t(`protocolConfiguration.states.${management.preview.value.success ? 'success' : 'failed'}`) }}</ElTag></div></template>
        <ElEmpty v-if="!management.preview.value" :description="t('protocolConfiguration.states.previewEmpty')" />
        <template v-else>
          <dl class="preview-summary"><div><dt>{{ t('protocolConfiguration.labels.identityType') }}</dt><dd>{{ management.preview.value.identityType === 'SN' ? t('protocolConfiguration.flow.serialNumber') : management.preview.value.identityType || '—' }}</dd></div><div><dt>{{ t('protocolConfiguration.labels.identityValue') }}</dt><dd>{{ management.preview.value.identityValue || '—' }}</dd></div><div><dt>{{ t('protocolConfiguration.labels.timeSource') }}</dt><dd>{{ timeSourceName(management.preview.value.timeSource) }}</dd></div><div><dt>{{ t('protocolConfiguration.labels.eventTime') }}</dt><dd>{{ management.preview.value.eventTime ? formatDateTime(management.preview.value.eventTime) : '—' }}</dd></div></dl>
          <details v-if="management.preview.value.success"><summary>{{ t('protocolConfiguration.flow.results', { count: management.preview.value.metrics.length }) }}</summary><ElTable :data="management.preview.value.metrics" row-key="metricCode"><ElTableColumn :label="t('protocolConfiguration.labels.metric')" min-width="150"><template #default="{ row }">{{ metricName(row.metricCode) }}</template></ElTableColumn><ElTableColumn :label="t('protocolConfiguration.labels.sourcePath')" prop="sourcePath" min-width="170" /><ElTableColumn :label="t('protocolConfiguration.labels.rawValue')" prop="rawValue" min-width="110" /><ElTableColumn :label="t('protocolConfiguration.labels.value')" prop="value" min-width="110" /><ElTableColumn :label="t('protocolConfiguration.labels.targetUnit')" prop="unit" min-width="90" /><ElTableColumn :label="t('protocolConfiguration.labels.status')" min-width="120"><template #default="{ row }"><ElTag :type="row.status === 'PRESENT' ? 'success' : 'info'">{{ t(`protocolConfiguration.states.${row.status === 'PRESENT' ? 'present' : 'missingOptional'}`) }}</ElTag></template></ElTableColumn></ElTable></details>
          <ElTable v-else :data="management.preview.value.errors"><ElTableColumn :label="t('protocolConfiguration.labels.result')" min-width="150"><template #default="{ row }"><details><summary>{{ t('protocolConfiguration.flow.technical') }}</summary>{{ row.code }}</details></template></ElTableColumn><ElTableColumn :label="t('protocolConfiguration.labels.errorPath')" prop="path" min-width="180" /><ElTableColumn :label="t('protocolConfiguration.labels.status')" prop="message" min-width="260" /><ElTableColumn><template #default="{ row }"><ElButton link @click="locateError(row.path)">{{ t('protocolConfiguration.flow.fixMapping') }}</ElButton></template></ElTableColumn></ElTable>
          <div class="section-actions"><ElButton @click="step = 1">{{ t('protocolConfiguration.flow.fixMapping') }}</ElButton><ElButton type="primary" :disabled="!management.preview.value.success || product?.status !== 'ENABLED'" :loading="preparing" @click="preparePublication">{{ t('protocolConfiguration.flow.prepare') }}</ElButton></div>
          <ElAlert v-if="product?.status !== 'ENABLED'" :title="t('protocolConfiguration.validation.productDisabled')" type="info" :closable="false" />
        </template>
        <div v-if="missingPoints.length"><ElButton v-for="point in missingPoints" :key="point.metricCode" link @click="locateMetric(point.metricCode)">{{ point.pointNameTemplate }}</ElButton></div>
      </ElCard>
    </fieldset>
    <section v-show="step === 4"><ElAlert :title="t('protocolConfiguration.flow.deviceBoundary')" type="info" :closable="false" /><ElButton type="primary" :disabled="!management.form.value.profileCode" @click="openMatchingPendingDevices">{{ t('protocolConfiguration.actions.openPendingDevices') }}</ElButton></section>
    <ProtocolPublicationPanel
      v-show="step === 3" ref="publicationPanel" :active="step === 3" :freeze-ready="!dirty && management.preview.value?.success === true"

      :draft-id="management.draft.value?.id ?? null"
      :draft-revision="management.draft.value?.revision ?? null"
      :product-enabled="product?.status === 'ENABLED'"
      :selected-product="product?.status === 'ENABLED' ? product : null"
    />
  </section>
</template>

<style scoped>
.editor-lock { border: 0; padding: 0; margin: 0; min-width: 0; display: grid; gap: var(--bec-space-section); }
.step-navigation, .flow-summary { display: flex; flex-wrap: wrap; gap: var(--bec-space-group); align-items: center; }
.step-navigation :deep(.el-button) { margin-left: 0; }
summary { cursor: pointer; padding-block: var(--bec-space-group); }
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
.editor-grid.product-stage { grid-template-columns: 1fr; }
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
