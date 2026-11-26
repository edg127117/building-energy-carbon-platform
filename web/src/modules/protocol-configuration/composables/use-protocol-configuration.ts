import { ref } from 'vue'
import { requestErrorCode, requestErrorMessage, requestErrorStatus } from '@/shared/utils/request-error'
import messages from '../locales/zh-CN'
import {
  createProtocolConfiguration,
  deleteProtocolConfiguration,
  getProtocolConfiguration,
  inspectProtocolSample,
  listProtocolConfigurations,
  previewProtocolConfiguration,
  updateProtocolConfiguration,
} from '../api/protocol-configuration'
import {
  emptyProtocolConfiguration,
  normalizeProtocolConfiguration,
  serializeProtocolConfigurationForSave,
  type InspectedField,
  type ProtocolConfiguration,
  type ProtocolConfigurationDetail,
  type ProtocolConfigurationPage,
  type ProtocolPreview,
} from '../models/protocol-configuration'

export type DraftListFilters = { keyword?: string; productId?: string }

const emptyPage = (): ProtocolConfigurationPage => ({ page: 1, size: 20, total: 0, items: [] })
const normalizeDetail = (detail: ProtocolConfigurationDetail): ProtocolConfigurationDetail => ({
  ...detail,
  configuration: normalizeProtocolConfiguration(detail.configuration),
})
// Vue 会递归代理表单内的映射数组；通过 JSON 契约快照剥离代理后再交给请求层，避免响应式对象进入传输边界。
const cloneNormalized = (value: ProtocolConfiguration): ProtocolConfiguration =>
  normalizeProtocolConfiguration(JSON.parse(JSON.stringify(value)) as ProtocolConfiguration)

/**
 * 协议编辑器只保存后端草稿，不持久化样例。检查、预览与列表查询均使用请求代次隔离迟到响应。
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
  const deleting = ref(false)
  const error = ref<string | null>(null)
  const activeFilters = ref<{ keyword: string; productId: string }>({ keyword: '', productId: '' })
  let loadGeneration = 0
  let listGeneration = 0
  let inspectGeneration = 0
  let previewGeneration = 0
  let editGeneration = 0

  async function loadDrafts(page = 1, filters?: DraftListFilters) {
    if (filters !== undefined) {
      activeFilters.value = {
        keyword: filters.keyword?.trim() ?? '',
        productId: filters.productId?.trim() ?? '',
      }
    }
    const owner = ++loadGeneration
    // 返回指定草稿时列表和详情并行加载；详情不能丢弃列表中的显示名称。
    const listOwner = ++listGeneration
    loading.value = true
    error.value = null
    try {
      const params: { page: number; size: number; keyword?: string; productId?: string } = {
        page,
        size: drafts.value.size,
      }
      if (activeFilters.value.keyword) params.keyword = activeFilters.value.keyword
      if (activeFilters.value.productId) params.productId = activeFilters.value.productId
      const result = await listProtocolConfigurations(params)
      const normalizedPage: ProtocolConfigurationPage = {
        ...result,
        items: result.items.map(normalizeDetail),
      }
      if (listOwner === listGeneration) drafts.value = normalizedPage
      return normalizedPage
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
      const detail = normalizeDetail(await getProtocolConfiguration(id))
      if (owner === loadGeneration && editOwner === editGeneration) {
        draft.value = detail
        form.value = cloneNormalized(detail.configuration)
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
      const payload = serializeProtocolConfigurationForSave(form.value)
      const result = await previewProtocolConfiguration(payload, samplePayload, receivedTime)
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
    const normalizedSnapshot = cloneNormalized(form.value)
    const wirePayload = serializeProtocolConfigurationForSave(normalizedSnapshot)
    saving.value = true
    error.value = null
    try {
      const rawDetail = draft.value
        ? await updateProtocolConfiguration(draft.value.id, draft.value.revision, wirePayload)
        : await createProtocolConfiguration(wirePayload)
      if (owner !== editGeneration) return
      const detail = normalizeDetail(rawDetail)
      draft.value = detail
      if (JSON.stringify(cloneNormalized(form.value)) === JSON.stringify(normalizedSnapshot)) {
        form.value = cloneNormalized(detail.configuration)
      }
      await loadDrafts(drafts.value.page)
      return detail
    } catch (reason) {
      error.value = protocolRequestErrorMessage(reason)
      throw reason
    } finally {
      saving.value = false
    }
  }

  async function deleteDraft(id: string) {
    if (deleting.value) return
    deleting.value = true
    error.value = null
    try {
      await deleteProtocolConfiguration(id)
      const isCurrent = draft.value?.id === id
      if (isCurrent) newDraft()
      const remainingOnPage = drafts.value.items.filter(item => item.id !== id).length
      const nextPage = remainingOnPage === 0 && drafts.value.page > 1 ? drafts.value.page - 1 : drafts.value.page
      await loadDrafts(nextPage)
    } catch (reason) {
      error.value = protocolRequestErrorMessage(reason)
      throw reason
    } finally {
      deleting.value = false
    }
  }

  return {
    drafts,
    draft,
    form,
    fields,
    preview,
    loading,
    inspecting,
    previewing,
    saving,
    deleting,
    error,
    activeFilters,
    loadDrafts,
    selectDraft,
    newDraft,
    invalidatePreview,
    invalidateInspection,
    inspect,
    runPreview,
    save,
    deleteDraft,
  }
}

function protocolRequestErrorMessage(reason: unknown): string {
  const code = requestErrorCode(reason)
  if (code === 'PROTOCOL_DRAFT_REFERENCED') return messages.errors.draftReferenced
  const status = requestErrorStatus(reason)
  if (status === 409) return messages.errors.conflict
  if (status === 429) return messages.errors.rateLimited
  if (status === 400) return messages.errors.validation
  return requestErrorMessage(reason)
}
