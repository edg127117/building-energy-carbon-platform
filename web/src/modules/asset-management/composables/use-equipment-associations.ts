import { computed, ref } from 'vue'
import { TransportError } from '../api/associations'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { t } from '@/locales'
import * as api from '../api/associations'
import type { AssociationPage, EquipmentAssociation, RelationAction, RelationDiff, RelationModel, RelationReview, RelationValidation, RelationVersion } from '../models/associations'

/** 当前投影与版本草稿独立读取；编辑只提交版本归属，不回写整份设备档案。 */
export function useEquipmentAssociations() {
  const buildingId = ref('')
  const buildings = ref<Array<{ buildingId: string; buildingName: string }>>([])
  const buildingPage = ref(1)
  const buildingKeyword = ref('')
  const buildingTotal = ref(0)
  const versionId = ref('')
  const model = ref<RelationModel | null>(null)
  const versions = ref<RelationVersion[]>([])
  const reviews = ref<RelationReview[]>([])
  const data = ref<AssociationPage | null>(null)
  const page = ref(1)
  const spaceId = ref('')
  const keyword = ref('')
  const unassigned = ref(false)
  const busy = ref(false)
  const error = ref('')
  const notice = ref('')
  const validation = ref<RelationValidation | null>(null)
  const diff = ref<RelationDiff | null>(null)
  const version = computed(() => versions.value.find(item => item.versionId === versionId.value) ?? null)
  const review = computed(() => reviews.value.find(item => item.versionId === versionId.value && item.status === 'PENDING'))
  const latestReview = computed(() => reviews.value.find(item => item.versionId === versionId.value))
  let buildingRequest = 0

  async function searchBuildings(nextPage = 1, search = buildingKeyword.value) {
    const owner = ++buildingRequest
    buildingKeyword.value = search
    try {
      const result = await api.listAssociationBuildings(nextPage, search)
      if (owner !== buildingRequest) return
      buildings.value = result.records
      buildingPage.value = nextPage
      buildingTotal.value = result.total
    } catch (reason) { if (owner === buildingRequest) error.value = requestErrorMessage(reason) }
  }

  async function read() {
    data.value = null
    validation.value = null
    diff.value = null
    const [currentModel, currentVersions, currentReviews, associations] = await Promise.all([
      api.getRelationModel(buildingId.value).catch(reason => {
        // 仅模型尚未初始化的 404 可视为旧投影；关联查询仍独立验证建筑存在及权限。
        if (reason instanceof TransportError && reason.status === 404) return null
        throw reason
      }),
      api.listRelationVersions(buildingId.value), api.listRelationReviews(buildingId.value),
      api.listEquipmentAssociations(buildingId.value, { versionId: versionId.value || undefined, page: page.value, size: 20,
        spaceId: spaceId.value || undefined, keyword: keyword.value || undefined, unassigned: unassigned.value }),
    ])
    model.value = currentModel
    versions.value = currentVersions
    reviews.value = currentReviews
    data.value = associations
  }

  async function locked(operation: () => Promise<void>) {
    if (busy.value) return false
    busy.value = true; error.value = ''; notice.value = ''
    try { await operation(); return true } catch (reason) {
      error.value = reason instanceof TransportError && reason.status === 409
        ? t('assetManagement.associations.conflict') : requestErrorMessage(reason)
      return false
    } finally { busy.value = false }
  }

  async function selectBuilding(id: string) {
    if (busy.value) return
    buildingId.value = id; versionId.value = ''; page.value = 1; spaceId.value = ''; keyword.value = ''; unassigned.value = false
    model.value = null; versions.value = []; reviews.value = []; data.value = null
    if (id) await locked(read)
  }
  async function selectVersion(id: string) {
    if (busy.value) return
    versionId.value = id; page.value = 1; spaceId.value = ''; unassigned.value = false
    await locked(read)
  }
  async function query(nextPage = 1) {
    if (busy.value || !buildingId.value) return
    page.value = nextPage
    await locked(read)
  }

  async function save(item: EquipmentAssociation, selectedSpace: string | null, selectedSystem: string | null) {
    if (!version.value || version.value.status !== 'DRAFT' || !data.value || typeof data.value.versionRevision !== 'number') return false
    const snapshot = { ...version.value, revision: data.value.versionRevision }
    return locked(async () => {
      await api.updateEquipmentAssociation(snapshot, item.equipmentId, selectedSpace, selectedSystem)
      await read()
      notice.value = t('assetManagement.associations.savedDraft')
    })
  }

  async function validate() {
    if (!version.value || !data.value) return
    const snapshot = version.value
    await locked(async () => {
      validation.value = null; diff.value = null
      const result = await api.validateRelationVersion(snapshot.versionId)
      const changes = snapshot.baseVersionId ? await api.getRelationDiff(buildingId.value, snapshot.versionId, snapshot.baseVersionId) : null
      validation.value = result; diff.value = changes
    })
  }

  async function act(action: RelationAction, reason: string, key: string) {
    if (!buildingId.value || !reason.trim()) return false
    if (['submit', 'withdraw'].includes(action) && typeof data.value?.versionRevision !== 'number') return false
    const snapshot = version.value && typeof data.value?.versionRevision === 'number'
      ? { ...version.value, revision: data.value.versionRevision } : version.value
    return locked(async () => {
      const result = await api.runRelationAction(action, { buildingId: buildingId.value, model: model.value, version: snapshot, review: review.value }, reason.trim(), key)
      if (action === 'initialize' || action === 'create') versionId.value = result.versionId
      if (action === 'activate') versionId.value = ''
      await read()
      notice.value = t('assetManagement.associations.actionCompleted')
    })
  }
  return { buildingId, buildings, buildingPage, buildingKeyword, buildingTotal, versionId, model, versions, version, review, latestReview, data,
    page, spaceId, keyword, unassigned, busy, error, notice, validation, diff,
    searchBuildings, selectBuilding, selectVersion, query, save, validate, act }
}
