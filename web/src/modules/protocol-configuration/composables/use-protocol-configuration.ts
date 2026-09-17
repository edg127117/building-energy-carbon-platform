import { ref } from 'vue'
import { requestErrorMessage, requestErrorStatus } from '@/shared/utils/request-error'
import messages from '../locales/zh-CN'
import {
  createProtocolConfiguration,
  getProtocolConfiguration,
  inspectProtocolSample,
  listProtocolConfigurations,
  previewProtocolConfiguration,
  updateProtocolConfiguration,
} from '../api/protocol-configuration'
import { emptyProtocolConfiguration, type InspectedField, type ProtocolConfiguration, type ProtocolConfigurationDetail, type ProtocolConfigurationPage, type ProtocolPreview } from '../models/protocol-configuration'

const emptyPage = (): ProtocolConfigurationPage => ({ page: 1, size: 20, total: 0, items: [] })
// Vue 会递归代理表单内的映射数组；通过 JSON 契约快照剥离代理后再交给请求层，避免响应式对象进入传输边界。
const clone = (value: ProtocolConfiguration): ProtocolConfiguration => JSON.parse(JSON.stringify(value)) as ProtocolConfiguration

/**
 * 协议编辑器只保存后端草稿，不持久化样例。检查与预览使用请求代次隔离迟到响应，配置变化后由页面立即清除旧预览。
 */
export function useProtocolConfiguration() {
  const drafts = ref(emptyPage())
  const draft = ref<ProtocolConfigurationDetail | null>(null)
  const form = ref(emptyProtocolConfiguration())
  const fields = ref<InspectedField[]>([])
  const preview = ref<ProtocolPreview | null>(null)
  const loading = ref(false)
  const inspecting = ref(false)
  const previewing = ref(false)
  const saving = ref(false)
  const error = ref<string | null>(null)
  let loadGeneration = 0
  let listGeneration = 0
  let inspectGeneration = 0
  let previewGeneration = 0
  let editGeneration = 0

  async function loadDrafts(page = 1) {
    const owner = ++loadGeneration
    // 返回指定草稿时列表和详情并行加载；详情不能丢弃列表中的显示名称。
    const listOwner = ++listGeneration
    loading.value = true
    error.value = null
    try {
      const result = await listProtocolConfigurations({ page, size: drafts.value.size })
      if (listOwner === listGeneration) drafts.value = result
      return result
    } catch (reason) {
      if (owner === loadGeneration) error.value = protocolRequestErrorMessage(reason)
      throw reason
    } finally {
      if (owner === loadGeneration) loading.value = false
    }
  }

  async function selectDraft(id: string) {
    const owner = ++loadGeneration
    const editOwner = ++editGeneration
    ++inspectGeneration
    ++previewGeneration
    loading.value = true
    inspecting.value = false
    previewing.value = false
    fields.value = []
    preview.value = null
    error.value = null
    try {
      const detail = await getProtocolConfiguration(id)
      if (owner === loadGeneration && editOwner === editGeneration) {
        draft.value = detail
        form.value = clone(detail.configuration)
        fields.value = []
        preview.value = null
      }
      return detail
    } catch (reason) {
      if (owner === loadGeneration) error.value = protocolRequestErrorMessage(reason)
      throw reason
    } finally {
      if (owner === loadGeneration) loading.value = false
    }
  }

  function newDraft() {
    ++loadGeneration
    ++inspectGeneration
    ++previewGeneration
    ++editGeneration
    draft.value = null
    form.value = emptyProtocolConfiguration()
    fields.value = []
    preview.value = null
    error.value = null
    loading.value = false
    inspecting.value = false
    previewing.value = false
  }

  function invalidatePreview() {
    ++previewGeneration
    preview.value = null
    previewing.value = false
  }

  function invalidateInspection() {
    ++inspectGeneration
    fields.value = []
    inspecting.value = false
  }

  async function inspect(samplePayload: string) {
    const owner = ++inspectGeneration
    inspecting.value = true
    error.value = null
    try {
      const result = await inspectProtocolSample(samplePayload)
      if (owner === inspectGeneration) fields.value = result.fields
      return result
    } catch (reason) {
      if (owner === inspectGeneration) error.value = protocolRequestErrorMessage(reason)
      throw reason
    } finally {
      if (owner === inspectGeneration) inspecting.value = false
    }
  }

  async function runPreview(samplePayload: string, receivedTime: number) {
    const owner = ++previewGeneration
    previewing.value = true
    error.value = null
    try {
      const result = await previewProtocolConfiguration(clone(form.value), samplePayload, receivedTime)
      if (owner === previewGeneration) preview.value = result
      return result
    } catch (reason) {
      if (owner === previewGeneration) error.value = protocolRequestErrorMessage(reason)
      throw reason
    } finally {
      if (owner === previewGeneration) previewing.value = false
    }
  }

  async function save() {
    if (saving.value) return
    const owner = editGeneration
    const snapshot = clone(form.value)
    saving.value = true
    error.value = null
    try {
      const detail = draft.value
        ? await updateProtocolConfiguration(draft.value.id, draft.value.revision, snapshot)
        : await createProtocolConfiguration(snapshot)
      if (owner !== editGeneration) return
      draft.value = detail
      if (JSON.stringify(form.value) === JSON.stringify(snapshot)) form.value = clone(detail.configuration)
      await loadDrafts(drafts.value.page)
      return detail
    } catch (reason) {
      error.value = protocolRequestErrorMessage(reason)
      throw reason
    } finally {
      saving.value = false
    }
  }

  return { drafts, draft, form, fields, preview, loading, inspecting, previewing, saving, error, loadDrafts, selectDraft, newDraft, invalidatePreview, invalidateInspection, inspect, runPreview, save }
}

function protocolRequestErrorMessage(reason: unknown): string {
  const status = requestErrorStatus(reason)
  if (status === 409) return messages.errors.conflict
  if (status === 429) return messages.errors.rateLimited
  if (status === 400) return messages.errors.validation
  return requestErrorMessage(reason)
}
