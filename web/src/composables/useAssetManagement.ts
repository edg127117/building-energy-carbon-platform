import { computed, ref } from 'vue'
import {
  createAssetBuilding,
  createAssetEquipment,
  createAssetSpace,
  createAssetSystemGroup,
  deleteAssetBuilding,
  deleteAssetEquipment,
  deleteAssetEquipmentPoint,
  deleteAssetSpace,
  deleteAssetSystemGroup,
  getAssetBuilding,
  getAssetEquipment,
  listAssetEquipmentPoints,
  listAssetSpaces,
  pageAssetBuildings,
  pageAssetEquipment,
  pageAssetSystemGroups,
  updateAssetBuilding,
  updateAssetEquipment,
  updateAssetEquipmentPoint,
  updateAssetSpace,
  updateAssetSystemGroup,
} from '@/api/assetManagement'
import { requestErrorMessage } from '@/utils/request'
import type {
  AssetBuildingDetail,
  AssetBuildingForm,
  AssetBuildingQuery,
  AssetBuildingView,
  AssetDataPointUpdateRequest,
  AssetDataPointView,
  AssetEquipmentDetail,
  AssetEquipmentForm,
  AssetEquipmentQuery,
  AssetEquipmentView,
  AssetPageResponse,
  AssetSpaceForm,
  AssetSpaceView,
  AssetSystemGroupForm,
  AssetSystemGroupView,
} from '@/types/assets'

const emptyBuildingPage = (): AssetPageResponse<AssetBuildingView> => ({ page: 1, size: 20, total: 0, items: [] })
const emptyEquipmentPage = (): AssetPageResponse<AssetEquipmentView> => ({ page: 1, size: 20, total: 0, items: [] })
const emptyGroupPage = (): AssetPageResponse<AssetSystemGroupView> => ({ page: 1, size: 100, total: 0, items: [] })

type ErrorState = { message: string; forbidden: boolean }

/**
 * 建筑和设备页共用的异步编排边界。
 * 每次加载分配代次，迟到结果不会覆盖当前筛选或当前详情；每个对象命令也以独立键防重复提交。
 */
export function useAssetManagement() {
  const buildingQuery = ref<AssetBuildingQuery>({ page: 1, size: 20 })
  const buildingPage = ref<AssetPageResponse<AssetBuildingView>>(emptyBuildingPage())
  const buildingsLoading = ref(false)
  const buildingsError = ref<ErrorState | null>(null)
  const selectedBuilding = ref<AssetBuildingDetail | null>(null)
  const spaces = ref<AssetSpaceView[]>([])
  const systemGroups = ref<AssetPageResponse<AssetSystemGroupView>>(emptyGroupPage())
  const buildingContextLoading = ref(false)
  const buildingContextError = ref<ErrorState | null>(null)

  const equipmentQuery = ref<AssetEquipmentQuery>({ page: 1, size: 20 })
  const equipmentPage = ref<AssetPageResponse<AssetEquipmentView>>(emptyEquipmentPage())
  const equipmentLoading = ref(false)
  const equipmentError = ref<ErrorState | null>(null)
  const selectedEquipment = ref<AssetEquipmentDetail | null>(null)
  const points = ref<AssetDataPointView[]>([])
  const equipmentDetailLoading = ref(false)
  const equipmentDetailError = ref<ErrorState | null>(null)
  const equipmentScopeSpaces = ref<AssetSpaceView[]>([])
  const equipmentScopeGroups = ref<AssetSystemGroupView[]>([])
  const equipmentScopeLoading = ref(false)

  const pending = ref(new Set<string>())
  let buildingsGeneration = 0
  let buildingContextGeneration = 0
  let equipmentGeneration = 0
  let equipmentDetailGeneration = 0
  let equipmentScopeGeneration = 0

  const buildingOptions = computed(() => buildingPage.value.items.map((item) => ({ label: item.buildingName, value: item.buildingId })))

  async function loadBuildings() {
    const owner = ++buildingsGeneration
    buildingsLoading.value = true
    buildingsError.value = null
    try {
      const page = await pageAssetBuildings({ ...buildingQuery.value })
      if (owner === buildingsGeneration) buildingPage.value = page
      return page
    } catch (reason) {
      if (owner === buildingsGeneration) buildingsError.value = toErrorState(reason)
      throw reason
    } finally {
      if (owner === buildingsGeneration) buildingsLoading.value = false
    }
  }

  function setBuildingQuery(patch: Partial<AssetBuildingQuery>, resetPage = true) {
    buildingQuery.value = { ...buildingQuery.value, ...patch, page: resetPage ? 1 : (patch.page ?? buildingQuery.value.page) }
    return loadBuildings()
  }

  async function selectBuilding(buildingId: string | null) {
    const owner = ++buildingContextGeneration
    if (!buildingId) {
      selectedBuilding.value = null
      spaces.value = []
      systemGroups.value = emptyGroupPage()
      return
    }
    buildingContextLoading.value = true
    buildingContextError.value = null
    try {
      const [building, nextSpaces, nextGroups] = await Promise.all([
        getAssetBuilding(buildingId),
        listAssetSpaces(buildingId),
        pageAssetSystemGroups({ page: 1, size: 100, buildingId }),
      ])
      if (owner === buildingContextGeneration) {
        selectedBuilding.value = building
        spaces.value = nextSpaces
        systemGroups.value = nextGroups
      }
    } catch (reason) {
      if (owner === buildingContextGeneration) buildingContextError.value = toErrorState(reason)
      throw reason
    } finally {
      if (owner === buildingContextGeneration) buildingContextLoading.value = false
    }
  }

  async function loadEquipment() {
    const owner = ++equipmentGeneration
    equipmentLoading.value = true
    equipmentError.value = null
    try {
      const page = await pageAssetEquipment({ ...equipmentQuery.value })
      if (owner === equipmentGeneration) equipmentPage.value = page
      return page
    } catch (reason) {
      if (owner === equipmentGeneration) equipmentError.value = toErrorState(reason)
      throw reason
    } finally {
      if (owner === equipmentGeneration) equipmentLoading.value = false
    }
  }

  function setEquipmentQuery(patch: Partial<AssetEquipmentQuery>, resetPage = true) {
    equipmentQuery.value = { ...equipmentQuery.value, ...patch, page: resetPage ? 1 : (patch.page ?? equipmentQuery.value.page) }
    return loadEquipment()
  }

  async function selectEquipment(equipmentId: string | null) {
    const owner = ++equipmentDetailGeneration
    if (!equipmentId) {
      selectedEquipment.value = null
      points.value = []
      return
    }
    equipmentDetailLoading.value = true
    equipmentDetailError.value = null
    try {
      const [equipment, nextPoints] = await Promise.all([
        getAssetEquipment(equipmentId),
        listAssetEquipmentPoints(equipmentId),
      ])
      if (owner === equipmentDetailGeneration) {
        selectedEquipment.value = equipment
        points.value = nextPoints
      }
    } catch (reason) {
      if (owner === equipmentDetailGeneration) equipmentDetailError.value = toErrorState(reason)
      throw reason
    } finally {
      if (owner === equipmentDetailGeneration) equipmentDetailLoading.value = false
    }
  }

  async function loadEquipmentScope(buildingId: string | undefined) {
    const owner = ++equipmentScopeGeneration
    if (!buildingId) {
      equipmentScopeSpaces.value = []
      equipmentScopeGroups.value = []
      return
    }
    equipmentScopeLoading.value = true
    try {
      const [nextSpaces, nextGroups] = await Promise.all([
        listAssetSpaces(buildingId),
        pageAssetSystemGroups({ page: 1, size: 100, buildingId }),
      ])
      if (owner === equipmentScopeGeneration) {
        equipmentScopeSpaces.value = nextSpaces
        equipmentScopeGroups.value = nextGroups.items
      }
    } finally {
      if (owner === equipmentScopeGeneration) equipmentScopeLoading.value = false
    }
  }

  async function ensureBuildingOptions() {
    if (buildingPage.value.items.length >= 100 || buildingQuery.value.size >= 100) return
    const page = await pageAssetBuildings({ page: 1, size: 100 })
    buildingPage.value = page
  }

  function runCommand(key: string, command: () => Promise<void>) {
    if (pending.value.has(key)) return Promise.resolve(undefined)
    pending.value = new Set(pending.value).add(key)
    return command().finally(() => {
      const next = new Set(pending.value)
      next.delete(key)
      pending.value = next
    })
  }

  async function saveBuilding(buildingId: string | null, request: AssetBuildingForm) {
    const key = buildingId ? `building:update:${buildingId}` : 'building:create'
    return runCommand(key, async () => {
      if (buildingId) await updateAssetBuilding(buildingId, request)
      else await createAssetBuilding(request)
      await loadBuildings()
      if (buildingId) await selectBuilding(buildingId)
    })
  }

  async function removeBuilding(buildingId: string) {
    return runCommand(`building:delete:${buildingId}`, async () => {
      await deleteAssetBuilding(buildingId)
      if (selectedBuilding.value?.buildingId === buildingId) await selectBuilding(null)
      await loadBuildings()
    })
  }

  async function saveSpace(spaceId: string | null, request: AssetSpaceForm) {
    const key = spaceId ? `space:update:${spaceId}` : 'space:create'
    return runCommand(key, async () => {
      if (spaceId) {
        await updateAssetSpace(spaceId, {
          parentSpaceId: request.parentSpaceId,
          spaceName: request.spaceName,
          spaceCode: request.spaceCode,
          spaceType: request.spaceType,
          sortOrder: request.sortOrder,
          usableArea: request.usableArea,
          status: request.status,
        })
      }
      else await createAssetSpace(request)
      await selectBuilding(request.buildingId)
    })
  }

  async function removeSpace(spaceId: string, buildingId: string) {
    return runCommand(`space:delete:${spaceId}`, async () => {
      await deleteAssetSpace(spaceId)
      await selectBuilding(buildingId)
    })
  }

  async function saveSystemGroup(systemGroupId: string | null, request: AssetSystemGroupForm) {
    const key = systemGroupId ? `system-group:update:${systemGroupId}` : 'system-group:create'
    return runCommand(key, async () => {
      if (systemGroupId) {
        await updateAssetSystemGroup(systemGroupId, {
          systemName: request.systemName,
          systemType: request.systemType,
          status: request.status,
        })
      }
      else await createAssetSystemGroup(request)
      await selectBuilding(request.buildingId)
    })
  }

  async function removeSystemGroup(systemGroupId: string, buildingId: string) {
    return runCommand(`system-group:delete:${systemGroupId}`, async () => {
      await deleteAssetSystemGroup(systemGroupId)
      await selectBuilding(buildingId)
    })
  }

  async function saveEquipment(equipmentId: string | null, request: AssetEquipmentForm) {
    const key = equipmentId ? `equipment:update:${equipmentId}` : 'equipment:create'
    return runCommand(key, async () => {
      if (equipmentId) {
        await updateAssetEquipment(equipmentId, {
          buildingId: request.buildingId,
          spaceId: request.spaceId,
          systemGroupId: request.systemGroupId,
          equipmentName: request.equipmentName,
          manufacturer: request.manufacturer,
          ratedCapacity: request.ratedCapacity,
          ratedPower: request.ratedPower,
          designCop: request.designCop,
          status: request.status,
        })
      }
      else await createAssetEquipment(request)
      await loadEquipment()
      if (equipmentId) await selectEquipment(equipmentId)
    })
  }

  async function removeEquipment(equipmentId: string) {
    return runCommand(`equipment:delete:${equipmentId}`, async () => {
      await deleteAssetEquipment(equipmentId)
      if (selectedEquipment.value?.equipmentId === equipmentId) await selectEquipment(null)
      await loadEquipment()
    })
  }

  async function savePoint(equipmentId: string, pointId: string, request: AssetDataPointUpdateRequest) {
    return runCommand(`point:update:${pointId}`, async () => {
      await updateAssetEquipmentPoint(equipmentId, pointId, request)
      await selectEquipment(equipmentId)
    })
  }

  async function removePoint(equipmentId: string, pointId: string) {
    return runCommand(`point:delete:${pointId}`, async () => {
      await deleteAssetEquipmentPoint(equipmentId, pointId)
      await selectEquipment(equipmentId)
    })
  }

  return {
    buildingQuery,
    buildingPage,
    buildingsLoading,
    buildingsError,
    selectedBuilding,
    spaces,
    systemGroups,
    buildingContextLoading,
    buildingContextError,
    equipmentQuery,
    equipmentPage,
    equipmentLoading,
    equipmentError,
    selectedEquipment,
    points,
    equipmentDetailLoading,
    equipmentDetailError,
    equipmentScopeSpaces,
    equipmentScopeGroups,
    equipmentScopeLoading,
    buildingOptions,
    pending,
    loadBuildings,
    setBuildingQuery,
    selectBuilding,
    loadEquipment,
    setEquipmentQuery,
    selectEquipment,
    loadEquipmentScope,
    ensureBuildingOptions,
    saveBuilding,
    removeBuilding,
    saveSpace,
    removeSpace,
    saveSystemGroup,
    removeSystemGroup,
    saveEquipment,
    removeEquipment,
    savePoint,
    removePoint,
  }
}

function toErrorState(reason: unknown): ErrorState {
  const status = (reason as { response?: { status?: unknown } } | null)?.response?.status
  return { message: requestErrorMessage(reason), forbidden: status === 403 }
}
