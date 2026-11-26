<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import {
  ElAlert, ElButton, ElCard, ElDrawer, ElEmpty, ElForm, ElFormItem, ElInput, ElMessage, ElMessageBox,
  ElOption, ElPagination, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag, ElTree,
  FileClock, Plus, RefreshCw, Search, Settings2, Trash2,
} from '@/shared/ui'
import { formatDateTime } from '@/shared/utils/format'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { t } from '@/locales'
import { getDeviceProduct, listDeviceProducts, type DeviceProductDetail, type DeviceProductListItem } from '@/modules/device-onboarding/public'
import { useProtocolConfiguration } from '../composables/use-protocol-configuration'
import {
  applyProductContract,
  emptyProtocolConfiguration,
  parsePreviewErrorTarget,
  validateProtocolConfiguration,
  validateProtocolDraft,
  type InspectedField,
  type PreviewErrorField,
  type PreviewMappingField,
  type ProtocolPublicationTarget,
} from '../models/protocol-configuration'
import MetricMappingEditor from '../components/MetricMappingEditor.vue'
import ProtocolPublicationPanel from '../components/ProtocolPublicationPanel.vue'

type FieldTreeNode = { id: string; label: string; path?: string; field?: InspectedField; children?: FieldTreeNode[] }

const router = useRouter()
const route = useRoute()
const management = useProtocolConfiguration()

const step = ref(0)
const preparing = ref(false)
const draftBoxOpen = ref(false)
const draftSearchKeyword = ref('')
const draftSearchProductId = ref('')
const advancedRecognitionOpen = ref(false)

const publicationPanel = ref<InstanceType<typeof ProtocolPublicationPanel> | null>(null)
const knownTargets = ref<ProtocolPublicationTarget[]>([])

const productPage = ref<{ page: number; size: number; total: number; items: DeviceProductListItem[] }>({ page: 1, size: 20, total: 0, items: [] })
const product = ref<DeviceProductDetail | null>(null)
const productsLoading = ref(false)
const productsError = ref<string | null>(null)
const productStatus = ref<'ENABLED' | 'DRAFT'>('ENABLED')
const productKeyword = ref('')

const samplePayload = ref('')
const selectedField = ref<InspectedField | null>(null)
const validationKey = ref<string | null>(null)
const highlightedField = ref<PreviewErrorField | null>(null)
const highlightedMetric = ref<string | null>(null)
const highlightedMappingField = ref<PreviewMappingField | null>(null)

let productListGeneration = 0
let productDetailGeneration = 0
let editorGeneration = 0

const stepKeys = ['stepBasics', 'stepMapping', 'stepPublish'] as const

const dirty = computed(() => JSON.stringify(management.form.value) !== JSON.stringify(management.draft.value?.configuration ?? emptyProtocolConfiguration()))
const sampleBytes = computed(() => new TextEncoder().encode(samplePayload.value).byteLength)
const fieldTree = computed(() => buildFieldTree(management.fields.value))
const productPoints = computed(() => product.value?.points.filter(point => point.enabled) ?? [])
const requiredPointCount = computed(() => productPoints.value.filter(point => point.required).length)
const missingPoints = computed(() => productPoints.value.filter(
  point => point.required && !management.form.value.mappings.some(mapping => mapping.enabled && mapping.metricCode === point.metricCode),
))
const topicSuggestions = computed(() => {
  const unique = new Set<string>()
  for (const target of knownTargets.value) {
    for (const topic of target.allowedTopics) {
      if (topic.trim()) unique.add(topic.trim())
    }
  }
  return [...unique]
})
const statusBadge = computed(() => {
  if (management.saving.value) return { type: 'info' as const, label: t('protocolConfiguration.flow.saving') }
  if (dirty.value) return { type: 'warning' as const, label: t('protocolConfiguration.flow.unsaved') }
  if (management.preview.value?.success) return { type: 'success' as const, label: t('protocolConfiguration.flow.previewPassed') }
  if (management.draft.value) return { type: 'info' as const, label: t('protocolConfiguration.flow.saved') }
  return { type: 'info' as const, label: t('protocolConfiguration.states.draft') }
})
const identityTypeLabel = computed(() => formatIdentityType(management.form.value.identityType))

watch(() => management.form.value, () => {
  management.invalidatePreview()
  clearHighlights()
}, { deep: true })

watch(samplePayload, () => {
  management.invalidatePreview()
  management.invalidateInspection()
  selectedField.value = null
  clearHighlights()
})

function clearHighlights() {
  highlightedField.value = null
  highlightedMetric.value = null
  highlightedMappingField.value = null
}

function formatIdentityType(identityType: string | null | undefined) {
  if (!identityType) return '—'
  const known = ['SN', 'MAC', 'DEVICE_CODE', 'GATEWAY_SUB_DEVICE', 'IMEI', 'CLIENT_ID']
  return known.includes(identityType) ? t(`protocolConfiguration.identityTypes.${identityType}`) : identityType
}

function timeSourceName(source: string | null) {
  const known = ['DEVICE_REPORTED', 'ADAPTER_RECEIVED', 'DEVICE', 'PLATFORM', 'RECEIVED', 'DEVICE_TIME', 'RECEIVE_TIME']
  if (source && known.includes(source)) return t(`protocolConfiguration.timeSources.${source}`)
  return source || t('common.missing')
}

function metricName(code: string) {
  return productPoints.value.find(point => point.metricCode === code)?.pointNameTemplate ?? code
}

async function confirmDiscard() {
  if (!dirty.value && !samplePayload.value) return true
  try {
    await ElMessageBox.confirm(t('protocolConfiguration.flow.leaveWarning'), t('protocolConfiguration.flow.confirm'), { type: 'warning' })
    return true
  } catch {
    return false
  }
}

onBeforeRouteLeave(() => confirmDiscard())
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value || samplePayload.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))

async function loadProducts(page = 1, keyword = productKeyword.value) {
  const owner = ++productListGeneration
  productsLoading.value = true
  productsError.value = null
  try {
    const result = await listDeviceProducts({
      page,
      size: productPage.value.size,
      status: productStatus.value,
      keyword: keyword.trim() || undefined,
    })
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
    draftBoxOpen.value = false
  } catch {
    // 请求错误统一由页面错误提示区呈现。
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
  clearHighlights()
  draftBoxOpen.value = false
}

async function openDraftBox() {
  draftBoxOpen.value = true
  try {
    await management.loadDrafts(1, { keyword: draftSearchKeyword.value, productId: draftSearchProductId.value })
  } catch {
    // 列表异常由页面统一错误区展示。
  }
}

async function searchDrafts() {
  try {
    await management.loadDrafts(1, { keyword: draftSearchKeyword.value, productId: draftSearchProductId.value })
  } catch {
    // 列表异常由页面统一错误区展示。
  }
}

async function resetDraftFilters() {
  draftSearchKeyword.value = ''
  draftSearchProductId.value = ''
  await searchDrafts()
}

async function removeDraft(id: string, name: string) {
  try {
    await ElMessageBox.confirm(
      t('protocolConfiguration.actions.confirmDeleteMessage', { name }),
      t('protocolConfiguration.actions.confirmDeleteTitle'),
      { type: 'warning' },
    )
  } catch {
    return
  }
  const deletingCurrent = management.draft.value?.id === id
  try {
    await management.deleteDraft(id)
    if (deletingCurrent) {
      product.value = null
      samplePayload.value = ''
      selectedField.value = null
      step.value = 0
    }
    ElMessage.success(t('protocolConfiguration.messages.deleted'))
  } catch {
    // 已引用冻结版本的 409 或其他错误已由 composable 写入错误区。
  }
}

function openMatchingPendingDevices() {
  const profileCode = management.form.value.profileCode.trim()
  if (profileCode) {
    void router.push({
      path: '/configuration/ingestion/pendingDevices',
      query: { sourceScope: 'STANDARD', profileCode, draftId: management.draft.value?.id ?? undefined },
    })
  }
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
    // 服务端结构校验与频率限制错误由统一错误区展示。
  }
}

function selectFieldNode(node: FieldTreeNode) {
  if (node.field) selectedField.value = node.field
}

function setPath(kind: 'identity' | 'discriminator' | 'timestamp') {
  const field = selectedField.value
  if (!field) return showValidation('selectedField')
  if (kind === 'identity') management.form.value.identityPath = field.path
  if (kind === 'discriminator') {
    management.form.value.discriminatorPath = field.path
    advancedRecognitionOpen.value = true
  }
  if (kind === 'timestamp') {
    management.form.value.timestampPath = field.path
    advancedRecognitionOpen.value = true
  }
  validationKey.value = null
}

async function save() {
  const issue = validateProtocolDraft(management.form.value, product.value)
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
  if (!product.value || management.form.value.productId !== product.value.productId) return showValidation('product')
  if (!samplePayload.value.trim()) return showValidation('sampleRequired')
  if (sampleBytes.value > 64 * 1024) return showValidation('sampleTooLarge')
  validationKey.value = null
  clearHighlights()
  try {
    const result = await management.runPreview(samplePayload.value, Date.now())
    if (result && !result.success && result.errors.length) {
      locateError(result.errors[0].path)
    }
  } catch {
    // 传输与权限错误由统一请求层呈现；配置与语义失败通过 Preview.errors 定位展示。
  }
}

async function proceedToPublish() {
  if (preparing.value) return
  const issue = validateProtocolConfiguration(management.form.value, product.value)
  if (issue) {
    showValidation(issue)
    return
  }
  if (product.value?.status !== 'ENABLED') {
    showValidation('productDisabled')
    return
  }
  validationKey.value = null
  preparing.value = true
  try {
    if (!management.draft.value || dirty.value) {
      const saved = await management.save()
      if (!saved || dirty.value) return
      ElMessage.success(t('protocolConfiguration.messages.saved'))
    }
    step.value = 2
    await nextTick()
  } catch {
    // 保存错误保留在当前步骤由用户处理。
  } finally {
    preparing.value = false
  }
}

function locateMetric(code: string, field: PreviewMappingField | null = null) {
  step.value = 1
  highlightedField.value = null
  highlightedMetric.value = code
  highlightedMappingField.value = field
  void nextTick(() => {
    document.getElementById(`metric-${code}`)?.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
  })
}

function locateTopField(field: PreviewErrorField) {
  highlightedMetric.value = null
  highlightedMappingField.value = null
  highlightedField.value = field
  if (field === 'timestampPath' || field === 'discriminatorPath' || field === 'discriminatorValue') {
    step.value = 0
    advancedRecognitionOpen.value = true
  } else if (field === 'mappings') {
    step.value = 1
  } else if (step.value !== 1) {
    step.value = 0
  }
  void nextTick(() => {
    const elementId = step.value === 1 ? `step2-field-${field}` : `field-${field}`
    document.getElementById(elementId)?.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
  })
}

function locateError(path: string) {
  const target = parsePreviewErrorTarget(path)
  if (target.kind === 'mapping') {
    locateMetric(target.metricCode, target.field)
    return
  }
  if (target.kind === 'field') {
    locateTopField(target.field)
    return
  }
  if (target.kind === 'pointer') {
    const mapping = management.form.value.mappings.find(item => item.sourcePath === target.pointer)
    if (mapping) {
      locateMetric(mapping.metricCode, 'sourcePath')
      return
    }
    if (management.form.value.identityPath === target.pointer) {
      locateTopField('identityPath')
      return
    }
    if (management.form.value.timestampPath === target.pointer) {
      locateTopField('timestampPath')
      return
    }
    if (management.form.value.discriminatorPath === target.pointer) {
      locateTopField('discriminatorPath')
      return
    }
  }
  step.value = 1
}

function showValidation(key: string) {
  validationKey.value = key
}

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
      current += `/${segment.replace(/~/g, '~0').replace(/\//g, '~1')}`
      let node = level.find(item => item.id === current)
      if (!node) {
        node = { id: current, label: segment, children: [] }
        level.push(node)
      }
      if (index === segments.length - 1) {
        node.path = field.path
        node.field = field
      }
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

async function changeProductStatus(status: 'ENABLED' | 'DRAFT') {
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
      <div class="heading-title">
        <div class="title-row">
          <h1>{{ t('protocolConfiguration.title') }}</h1>
          <ElTag :type="statusBadge.type">{{ statusBadge.label }}</ElTag>
          <span class="draft-title">{{ management.form.value.name || t('protocolConfiguration.states.newDraftName') }}</span>
        </div>
        <p>{{ t('protocolConfiguration.description') }}</p>
      </div>
      <div class="heading-actions">
        <ElButton :icon="FileClock" @click="openDraftBox">{{ t('protocolConfiguration.actions.draftBox') }}</ElButton>
        <ElButton :icon="Settings2" @click="publicationPanel?.openOperationsDrawer('targets')">{{ t('protocolConfiguration.actions.operationsDrawer') }}</ElButton>
        <ElButton @click="router.push('/configuration/ingestion/products')">{{ t('protocolConfiguration.actions.openProducts') }}</ElButton>
        <ElButton type="primary" :loading="management.saving.value" @click="save">{{ t('protocolConfiguration.actions.saveDraft') }}</ElButton>
      </div>
    </header>

    <ElAlert v-if="management.error.value" :title="management.error.value" type="error" show-icon :closable="false" />
    <ElAlert v-if="productsError" :title="productsError" type="error" show-icon :closable="false" />
    <ElAlert v-if="validationKey" :title="t(`protocolConfiguration.validation.${validationKey}`)" type="error" show-icon :closable="false" />

    <nav class="step-bar" :aria-label="t('protocolConfiguration.flow.steps')">
      <div class="step-buttons">
        <ElButton
          v-for="(key, index) in stepKeys"
          :key="key"
          :type="step === index ? 'primary' : 'default'"
          :aria-current="step === index ? 'step' : undefined"
          @click="step = index"
        >
          {{ `${index + 1}. ${t(`protocolConfiguration.flow.${key}`)}` }}
        </ElButton>
      </div>
      <div v-if="product" class="step-meta">
        <strong>{{ product.productName }}</strong>
        <ElTag :type="missingPoints.length ? 'warning' : 'success'" size="small">
          {{ missingPoints.length ? t('protocolConfiguration.flow.remaining', { count: missingPoints.length }) : t('protocolConfiguration.flow.allMapped') }}
        </ElTag>
      </div>
    </nav>

    <fieldset :disabled="preparing" class="editor-lock">
      <!-- 步骤 1：基础配置与产品 -->
      <ElCard v-show="step === 0" shadow="never" class="basics-card">
        <template #header>
          <div class="section-heading">
            <h2>{{ t('protocolConfiguration.sections.basics') }}</h2>
            <ElButton link :icon="Plus" @click="newDraft">{{ t('protocolConfiguration.actions.newDraft') }}</ElButton>
          </div>
        </template>

        <ElSkeleton v-if="management.loading.value" animated :rows="5" />
        <ElForm v-else label-position="top" class="form-grid basics-form">
          <ElFormItem :label="t('protocolConfiguration.labels.name')" required>
            <ElInput
              v-model="management.form.value.name"
              maxlength="100"
              :placeholder="t('protocolConfiguration.placeholders.name')"
            />
          </ElFormItem>

          <ElFormItem
            id="field-identityPath"
            :label="t('protocolConfiguration.labels.identityPath')"
            :class="{ 'field-highlight': highlightedField === 'identityPath' }"
          >
            <ElInput
              v-model="management.form.value.identityPath"
              maxlength="200"
              :placeholder="t('protocolConfiguration.placeholders.identityPath')"
            />
          </ElFormItem>

          <ElFormItem :label="t('protocolConfiguration.labels.productStatus')">
            <ElSelect :model-value="productStatus" @change="changeProductStatus">
              <ElOption value="ENABLED" :label="t('protocolConfiguration.productStatus.enabled')" />
              <ElOption value="DRAFT" :label="t('protocolConfiguration.productStatus.draft')" />
            </ElSelect>
          </ElFormItem>

          <ElFormItem :label="t('protocolConfiguration.labels.product')" required>
            <ElSelect
              :model-value="management.form.value.productId"
              filterable
              remote
              :remote-method="searchProducts"
              :loading="productsLoading"
              :placeholder="t('protocolConfiguration.placeholders.product')"
              @change="selectProduct"
            >
              <ElOption
                v-for="item in productPage.items"
                :key="item.productId"
                :value="item.productId"
                :label="`${item.productName} · ${item.productCode}`"
              />
            </ElSelect>
          </ElFormItem>

          <div v-if="productPage.total > productPage.size" class="span-2 product-pagination">
            <ElPagination
              size="small"
              layout="total, prev, pager, next"
              :current-page="productPage.page"
              :page-size="productPage.size"
              :total="productPage.total"
              @current-change="page => loadProducts(page)"
            />
          </div>

          <div v-if="product" class="span-2 product-summary">
            <div class="product-summary-head">
              <strong>{{ t('protocolConfiguration.flow.productPointsSummary', { total: productPoints.length, required: requiredPointCount }) }}</strong>
              <span class="meta-text">{{ `${t('protocolConfiguration.labels.identityType')}：${identityTypeLabel}` }}</span>
            </div>
            <div class="point-chip-list">
              <ElTag
                v-for="point in productPoints"
                :key="point.metricCode"
                size="small"
                :type="point.required ? 'warning' : 'info'"
              >
                {{ `${point.pointNameTemplate}（${point.unit}）` }}
              </ElTag>
            </div>
          </div>

          <ElFormItem
            id="field-sourceTopic"
            :label="t('protocolConfiguration.labels.sourceTopic')"
            class="span-2"
            :class="{ 'field-highlight': highlightedField === 'sourceTopic' }"
          >
            <ElInput
              v-model="management.form.value.sourceTopic"
              maxlength="200"
              :placeholder="t('protocolConfiguration.placeholders.topic')"
            />
            <div v-if="topicSuggestions.length" class="topic-suggestions">
              <span class="meta-text">{{ t('protocolConfiguration.flow.topicSuggestions') }}</span>
              <ElButton
                v-for="topic in topicSuggestions"
                :key="topic"
                size="small"
                @click="management.form.value.sourceTopic = topic"
              >
                {{ topic }}
              </ElButton>
            </div>
          </ElFormItem>

          <details :open="advancedRecognitionOpen" class="span-2 collapsible-block" @toggle="advancedRecognitionOpen = ($event.target as HTMLDetailsElement).open">
            <summary>{{ t('protocolConfiguration.flow.advancedRecognition') }}</summary>
            <div class="form-grid collapsible-body">
              <ElFormItem
                id="field-timestampPath"
                :label="t('protocolConfiguration.labels.timestampPath')"
                :class="{ 'field-highlight': highlightedField === 'timestampPath' }"
              >
                <ElInput v-model="management.form.value.timestampPath" clearable maxlength="200" />
              </ElFormItem>
              <ElFormItem
                id="field-discriminatorPath"
                :label="t('protocolConfiguration.labels.discriminatorPath')"
                :class="{ 'field-highlight': highlightedField === 'discriminatorPath' }"
              >
                <ElInput v-model="management.form.value.discriminatorPath" clearable maxlength="200" />
              </ElFormItem>
              <ElFormItem
                id="field-discriminatorValue"
                :label="t('protocolConfiguration.labels.discriminatorValue')"
                class="span-2"
                :class="{ 'field-highlight': highlightedField === 'discriminatorValue' }"
              >
                <ElInput
                  v-model="management.form.value.discriminatorValue"
                  maxlength="50"
                  :disabled="!management.form.value.discriminatorPath"
                  :placeholder="t('protocolConfiguration.placeholders.discriminatorValue')"
                />
              </ElFormItem>
            </div>
          </details>

          <details v-if="product" class="span-2 collapsible-block">
            <summary>{{ t('protocolConfiguration.flow.technical') }}</summary>
            <div class="form-grid collapsible-body">
              <ElFormItem :label="t('protocolConfiguration.labels.profileCode')">
                <ElInput v-model="management.form.value.profileCode" disabled />
              </ElFormItem>
              <ElFormItem :label="t('protocolConfiguration.labels.identityType')">
                <ElInput v-model="management.form.value.identityType" disabled />
              </ElFormItem>
            </div>
          </details>
        </ElForm>

        <div class="section-actions basics-actions">
          <ElButton :loading="management.saving.value" @click="save">{{ t('protocolConfiguration.actions.saveDraft') }}</ElButton>
          <ElButton type="primary" :disabled="!product" @click="step = 1">{{ t('protocolConfiguration.flow.nextMapping') }}</ElButton>
        </div>
      </ElCard>

      <!-- 步骤 2：报文映射与实时试解析（同屏） -->
      <div v-show="step === 1" class="mapping-stage">
        <div class="editor-grid">
          <div class="editor-column">
            <ElCard shadow="never">
              <template #header>
                <div class="section-heading">
                  <h2>{{ t('protocolConfiguration.sections.sample') }}</h2>
                  <span class="byte-count">{{ t('protocolConfiguration.counters.sampleBytes', { count: sampleBytes }) }}</span>
                </div>
              </template>
              <ElInput
                v-model="samplePayload"
                type="textarea"
                :rows="9"
                resize="vertical"
                :placeholder="t('protocolConfiguration.placeholders.sample')"
              />
              <div class="section-actions">
                <ElButton type="primary" :icon="Search" :loading="management.inspecting.value" @click="inspect">
                  {{ t('protocolConfiguration.actions.inspect') }}
                </ElButton>
                <ElButton :icon="RefreshCw" :loading="management.previewing.value" @click="preview">
                  {{ t('protocolConfiguration.actions.preview') }}
                </ElButton>
              </div>
            </ElCard>

            <ElCard shadow="never" class="field-panel">
              <template #header>
                <div class="section-heading">
                  <h2>{{ t('protocolConfiguration.sections.fields') }}</h2>
                  <ElTag v-if="selectedField" type="info">{{ t(`protocolConfiguration.fieldTypes.${selectedField.type}`) }}</ElTag>
                </div>
              </template>
              <ElEmpty v-if="!fieldTree.length" :description="t('protocolConfiguration.states.fieldEmpty')" />
              <template v-else>
                <ElTree :data="fieldTree" node-key="id" default-expand-all highlight-current @node-click="selectFieldNode">
                  <template #default="{ data }">
                    <div class="tree-node">
                      <span>{{ data.label }}</span>
                      <span v-if="data.field" class="field-value">{{ data.field.value }}</span>
                    </div>
                  </template>
                </ElTree>
                <div v-if="selectedField" class="field-selection">
                  <dl>
                    <dt>{{ t('protocolConfiguration.labels.selectedField') }}</dt>
                    <dd>{{ selectedField.path }}</dd>
                    <dt>{{ t('protocolConfiguration.labels.sampleValue') }}</dt>
                    <dd>{{ selectedField.value || '—' }}</dd>
                  </dl>
                  <div class="field-actions">
                    <ElButton size="small" @click="setPath('identity')">{{ t('protocolConfiguration.actions.useIdentity') }}</ElButton>
                    <ElButton size="small" @click="setPath('timestamp')">{{ t('protocolConfiguration.actions.useTimestamp') }}</ElButton>
                    <ElButton size="small" @click="setPath('discriminator')">{{ t('protocolConfiguration.actions.useDiscriminator') }}</ElButton>
                  </div>
                </div>
              </template>
            </ElCard>

            <ElCard shadow="never">
              <ElForm label-position="top" class="form-grid">
                <ElFormItem
                  id="step2-field-sourceTopic"
                  :label="t('protocolConfiguration.labels.sourceTopic')"
                  class="span-2"
                  :class="{ 'field-highlight': highlightedField === 'sourceTopic' }"
                >
                  <ElInput v-model="management.form.value.sourceTopic" maxlength="200" :placeholder="t('protocolConfiguration.placeholders.topic')" />
                </ElFormItem>
                <ElFormItem
                  id="step2-field-identityPath"
                  :label="t('protocolConfiguration.labels.identityPath')"
                  class="span-2"
                  :class="{ 'field-highlight': highlightedField === 'identityPath' }"
                >
                  <ElInput v-model="management.form.value.identityPath" maxlength="200" :placeholder="t('protocolConfiguration.placeholders.identityPath')" />
                </ElFormItem>
              </ElForm>
            </ElCard>
          </div>

          <div class="editor-column">
            <ElCard id="step2-field-mappings" shadow="never">
              <template #header>
                <div class="section-heading">
                  <h2>{{ t('protocolConfiguration.sections.mapping') }}</h2>
                  <span class="meta-text">{{ t('protocolConfiguration.counters.mappings', { count: management.form.value.mappings.length }) }}</span>
                </div>
              </template>

              <div v-if="missingPoints.length" class="missing-chips">
                <span class="meta-text">{{ t('protocolConfiguration.flow.remaining', { count: missingPoints.length }) }}</span>
                <ElButton v-for="point in missingPoints" :key="point.metricCode" size="small" link type="warning" @click="locateMetric(point.metricCode)">
                  {{ point.pointNameTemplate }}
                </ElButton>
              </div>

              <MetricMappingEditor
                v-model:mappings="management.form.value.mappings"
                :points="productPoints"
                :fields="management.fields.value"
                :highlighted-metric="highlightedMetric"
                :highlighted-field="highlightedMappingField"
              />
            </ElCard>

            <ElCard shadow="never" class="preview-card">
              <template #header>
                <div class="section-heading">
                  <h2>{{ t('protocolConfiguration.sections.preview') }}</h2>
                  <div class="heading-actions">
                    <ElTag v-if="management.preview.value" :type="management.preview.value.success ? 'success' : 'danger'">
                      {{ t(`protocolConfiguration.states.${management.preview.value.success ? 'success' : 'failed'}`) }}
                    </ElTag>
                    <ElButton type="primary" :icon="RefreshCw" :loading="management.previewing.value" @click="preview">
                      {{ t('protocolConfiguration.actions.preview') }}
                    </ElButton>
                  </div>
                </div>
              </template>

              <ElEmpty v-if="!management.preview.value" :description="t('protocolConfiguration.states.previewEmpty')" />
              <template v-else>
                <template v-if="management.preview.value.success">
                  <dl class="preview-summary">
                    <div>
                      <dt>{{ t('protocolConfiguration.labels.identityType') }}</dt>
                      <dd>{{ formatIdentityType(management.preview.value.identityType) }}</dd>
                    </div>
                    <div>
                      <dt>{{ t('protocolConfiguration.labels.identityValue') }}</dt>
                      <dd>{{ management.preview.value.identityValue || '—' }}</dd>
                    </div>
                    <div>
                      <dt>{{ t('protocolConfiguration.labels.timeSource') }}</dt>
                      <dd>{{ timeSourceName(management.preview.value.timeSource) }}</dd>
                    </div>
                    <div>
                      <dt>{{ t('protocolConfiguration.labels.eventTime') }}</dt>
                      <dd>{{ management.preview.value.eventTime ? formatDateTime(management.preview.value.eventTime) : '—' }}</dd>
                    </div>
                  </dl>
                  <ElTable :data="management.preview.value.metrics" row-key="metricCode">
                    <ElTableColumn :label="t('protocolConfiguration.labels.metric')" min-width="130">
                      <template #default="{ row }">{{ metricName(row.metricCode) }}</template>
                    </ElTableColumn>
                    <ElTableColumn :label="t('protocolConfiguration.labels.rawValue')" prop="rawValue" min-width="90" />
                    <ElTableColumn :label="t('protocolConfiguration.labels.value')" prop="value" min-width="90" />
                    <ElTableColumn :label="t('protocolConfiguration.labels.targetUnit')" prop="unit" width="84" />
                    <ElTableColumn :label="t('protocolConfiguration.labels.status')" width="110">
                      <template #default="{ row }">
                        <ElTag :type="row.status === 'PRESENT' ? 'success' : 'info'">
                          {{ t(`protocolConfiguration.states.${row.status === 'PRESENT' ? 'present' : 'missingOptional'}`) }}
                        </ElTag>
                      </template>
                    </ElTableColumn>
                  </ElTable>
                </template>
                <ElTable v-else :data="management.preview.value.errors">
                  <ElTableColumn :label="t('protocolConfiguration.labels.errorMessage')" prop="message" min-width="220" />
                  <ElTableColumn :label="t('protocolConfiguration.labels.errorPath')" prop="path" min-width="160" />
                  <ElTableColumn width="100">
                    <template #default="{ row }">
                      <ElButton link type="primary" @click="locateError(row.path)">{{ t('protocolConfiguration.flow.locateError') }}</ElButton>
                    </template>
                  </ElTableColumn>
                </ElTable>
              </template>
            </ElCard>
          </div>
        </div>

        <div class="stage-footer">
          <ElButton @click="step = 0">{{ t('protocolConfiguration.flow.prevStep') }}</ElButton>
          <div class="heading-actions">
            <ElButton :loading="management.saving.value" @click="save">{{ t('protocolConfiguration.actions.saveDraft') }}</ElButton>
            <ElButton type="primary" :loading="preparing" @click="proceedToPublish">{{ t('protocolConfiguration.flow.nextPublish') }}</ElButton>
          </div>
        </div>
      </div>
    </fieldset>

    <!-- 顶部入口也使用该组件的适配器弹层，只由组件隐藏发布卡片。 -->
    <ProtocolPublicationPanel
      ref="publicationPanel"
      :active="step === 2"
      :freeze-ready="!dirty && management.preview.value?.success === true"
      :draft-id="management.draft.value?.id ?? null"
      :draft-revision="management.draft.value?.revision ?? null"
      :draft-dirty="dirty"
      :product-enabled="product?.status === 'ENABLED'"
      :selected-product="product?.status === 'ENABLED' ? product : null"
      :profile-code="management.form.value.profileCode"
      @open-pending-devices="openMatchingPendingDevices"
      @fix-config="step = 1"
      @targets-loaded="list => { knownTargets = list }"
    />

    <!-- 草稿箱抽屉（支持关键词/产品筛选与安全删除） -->
    <ElDrawer
      v-model="draftBoxOpen"
      :title="t('protocolConfiguration.sections.draftBoxTitle')"
      size="min(720px, 95vw)"
    >
      <div class="drawer-body">
        <div class="draft-toolbar">
          <ElInput
            v-model="draftSearchKeyword"
            clearable
            :placeholder="t('protocolConfiguration.placeholders.draftKeyword')"
            @keyup.enter="searchDrafts"
          />
          <ElSelect
            v-model="draftSearchProductId"
            clearable
            filterable
            :placeholder="t('protocolConfiguration.placeholders.draftProductFilter')"
          >
            <ElOption
              v-for="item in productPage.items"
              :key="item.productId"
              :value="item.productId"
              :label="`${item.productName} · ${item.productCode}`"
            />
          </ElSelect>
          <div class="draft-toolbar-actions">
            <ElButton type="primary" :icon="Search" :loading="management.loading.value" @click="searchDrafts">{{ t('protocolConfiguration.actions.search') }}</ElButton>
            <ElButton @click="resetDraftFilters">{{ t('protocolConfiguration.actions.reset') }}</ElButton>
            <ElButton :icon="Plus" @click="newDraft">{{ t('protocolConfiguration.actions.newDraft') }}</ElButton>
          </div>
        </div>

        <ElEmpty v-if="!management.drafts.value.items.length" :description="t('protocolConfiguration.states.draftBoxEmpty')" />
        <ElTable v-else :data="management.drafts.value.items" row-key="id">
          <ElTableColumn :label="t('protocolConfiguration.labels.draftName')" min-width="170">
            <template #default="{ row }">{{ row.configuration.name }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('protocolConfiguration.labels.profileCode')" min-width="120">
            <template #default="{ row }">{{ row.configuration.profileCode || '—' }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('protocolConfiguration.labels.revision')" prop="revision" width="76" />
          <ElTableColumn :label="t('protocolConfiguration.labels.updatedAt')" min-width="150">
            <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
          </ElTableColumn>
          <ElTableColumn width="130" fixed="right">
            <template #default="{ row }">
              <ElButton link type="primary" @click="loadSelectedDraft(row.id)">{{ t('protocolConfiguration.actions.openDraft') }}</ElButton>
              <ElButton
                link
                type="danger"
                :icon="Trash2"
                :loading="management.deleting.value"
                @click="removeDraft(row.id, row.configuration.name)"
              >
                {{ t('protocolConfiguration.actions.deleteDraft') }}
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>

        <div v-if="management.drafts.value.total > management.drafts.value.size" class="pagination">
          <ElPagination
            size="small"
            layout="total, prev, pager, next"
            :current-page="management.drafts.value.page"
            :page-size="management.drafts.value.size"
            :total="management.drafts.value.total"
            @current-change="page => management.loadDrafts(page).catch(() => undefined)"
          />
        </div>
      </div>
    </ElDrawer>
  </section>
</template>

<style scoped>
.protocol-page, .editor-column, .mapping-stage, .drawer-body {
  display: grid;
  gap: var(--bec-space-section);
  min-width: 0;
}
.protocol-page {
  width: 100%;
  max-width: calc(var(--bec-ref-space-64) * 21);
  margin-inline: auto;
}
.basics-form, .basics-actions {
  width: 100%;
  max-width: calc(var(--bec-ref-space-64) * 14);
}
.form-grid :deep(.el-select) {
  width: 100%;
}
.editor-lock { border: 0; padding: 0; margin: 0; min-width: 0; display: grid; gap: var(--bec-space-section); }
.page-heading, .heading-actions, .section-heading, .section-actions, .field-actions, .step-bar, .step-buttons, .step-meta, .stage-footer {
  display: flex;
  align-items: center;
  gap: var(--bec-space-group);
}
.page-heading, .section-heading, .step-bar, .stage-footer { justify-content: space-between; }
.page-heading { align-items: flex-start; flex-wrap: wrap; }
.heading-title { display: grid; gap: var(--bec-space-tight); min-width: 0; }
.title-row { display: flex; align-items: center; gap: var(--bec-space-tight); flex-wrap: wrap; }
.draft-title { color: var(--bec-color-text-secondary); font-weight: var(--bec-font-weight-heading); }
.heading-actions, .section-actions, .step-buttons, .step-meta, .field-actions { flex-wrap: wrap; }
.heading-actions, .section-actions { justify-content: flex-end; }
.step-buttons :deep(.el-button) { margin-left: 0; }
h1, h2, p, dl, dd { margin: 0; }
h1 { font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); }
h2 { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
p, .byte-count, .field-value, dt, .meta-text { color: var(--bec-color-text-secondary); }
p { max-width: var(--bec-text-measure); }
.editor-grid {
  display: grid;
  grid-template-columns: minmax(0, 5fr) minmax(0, 7fr);
  gap: var(--bec-space-section);
  align-items: start;
}
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 var(--bec-space-group); }
.span-2 { grid-column: 1 / -1; }
.product-summary {
  display: grid;
  gap: var(--bec-space-tight);
  padding: var(--bec-space-group);
  margin-bottom: var(--bec-space-group);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-radius-card);
  background: var(--bec-color-surface-secondary);
}
.product-summary-head, .point-chip-list, .topic-suggestions, .missing-chips {
  display: flex;
  align-items: center;
  gap: var(--bec-space-tight);
  flex-wrap: wrap;
}
.product-summary-head { justify-content: space-between; }
.topic-suggestions { margin-top: var(--bec-space-tight); }
.missing-chips { margin-bottom: var(--bec-space-group); }
.collapsible-block { margin-top: var(--bec-space-tight); }
.collapsible-block summary { cursor: pointer; padding-block: var(--bec-space-tight); color: var(--bec-color-text-secondary); }
.collapsible-body { padding-top: var(--bec-space-tight); }
.field-highlight :deep(.el-form-item__label) { color: var(--bec-color-error); font-weight: var(--bec-font-weight-heading); }
.pagination, .product-pagination { display: flex; justify-content: flex-end; }
.product-pagination { margin-bottom: var(--bec-space-group); }
.section-actions { margin-top: var(--bec-space-group); }
.field-panel :deep(.el-card__body) { display: grid; gap: var(--bec-space-group); align-content: start; }
.tree-node { display: flex; justify-content: space-between; gap: var(--bec-space-group); width: 100%; min-width: 0; }
.field-value { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: var(--bec-navigation-width); }
.field-selection {
  display: grid;
  gap: var(--bec-space-group);
  padding-top: var(--bec-space-group);
  border-top: var(--bec-border-width) solid var(--bec-color-divider);
}
.field-selection dl { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: var(--bec-space-tight) var(--bec-space-group); }
.field-selection dd { overflow-wrap: anywhere; }
.preview-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--bec-space-group);
  margin-bottom: var(--bec-space-group);
}
.preview-summary div { display: grid; gap: var(--bec-space-tight); }
.preview-summary dd { overflow-wrap: anywhere; }
.draft-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--bec-space-tight);
}
.draft-toolbar-actions { grid-column: 1 / -1; display: flex; flex-wrap: wrap; gap: var(--bec-space-tight); }
@media (max-width: 960px) {
  .page-heading, .step-bar { display: grid; }
  .heading-actions { justify-content: flex-start; }
  .editor-grid, .preview-summary { grid-template-columns: 1fr; }
}
@media (max-width: 640px) {
  .form-grid, .draft-toolbar { grid-template-columns: 1fr; }
  .span-2 { grid-column: auto; }
  .stage-footer { flex-wrap: wrap; }
}
</style>
