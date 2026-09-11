import { computed, ref } from 'vue'
import {
  createBuilding,
  createEquipment,
  createSpace,
  createSystemGroup,
  deleteBuilding,
  deleteEquipment,
  deleteEquipmentPoint,
  deleteSpace,
  deleteSystemGroup,
  getBuilding,
  getEquipment,
  listBuildings,
  listEquipment,
  listEquipmentPoints,
  listSpaces,
  listSystemGroups,
  updateBuilding,
  updateEquipment,
  updateEquipmentPoint,
  updateSpace,
  updateSystemGroup,
} from '../api/assets'
import type {
  AssetBuilding,
  AssetBuildingForm,
  AssetEquipment,
  AssetEquipmentDetail,
  AssetEquipmentForm,
  AssetEquipmentQuery,
  AssetPage,
  AssetPoint,
  AssetPointUpdate,
  AssetSpace,
  AssetSpaceForm,
  AssetSystemGroup,
  AssetSystemGroupForm,
} from '../models/assets'
import { requestErrorMessage } from '@/shared/utils/request-error'

type RequestState = { message: string } | null

const emptyPage = <T>(size = 20): AssetPage<T> => ({ page: 1, size, total: 0, items: [] })

/**
 * 资产页面的请求所有权边界。
 *
 * 查询按代次丢弃迟到响应，写操作按对象加锁并在成功后刷新权威服务端状态，避免前端本地猜测档案结果。
 */
export function useAssetManagement() {
  const buildingQuery = ref({ page: 1, size: 20, keyword: '' })
  const buildings = ref<AssetPage<AssetBuilding>>(emptyPage())
  const buildingsLoading = ref(false)
  const buildingsError = ref<RequestState>(null)
  const selectedBuilding = ref<AssetBuilding | null>(null)
  const spaces = ref<AssetSpace[]>([])
  const systemGroups = ref<AssetPage<AssetSystemGroup>>(emptyPage(100))
  const buildingContextLoading = ref(false)
  const buildingContextError = ref<RequestState>(null)

  const equipmentQuery = ref<AssetEquipmentQuery>({ page: 1, size: 20 })
  const equipment = ref<AssetPage<AssetEquipment>>(emptyPage())
  const equipmentLoading = ref(false)
  const equipmentError = ref<RequestState>(null)
  const selectedEquipment = ref<AssetEquipmentDetail | null>(null)
  const points = ref<AssetPoint[]>([])
  const equipmentContextLoading = ref(false)
  const equipmentContextError = ref<RequestState>(null)
  const scopeSpaces = ref<AssetSpace[]>([])
  const scopeSystemGroups = ref<AssetSystemGroup[]>([])
  const scopeLoading = ref(false)
  const scopeError = ref<RequestState>(null)
  const pending = ref(new Set<string>())
  const operationError = ref<RequestState>(null)

  let buildingGeneration = 0
  let buildingContextGeneration = 0
  let equipmentGeneration = 0
  let equipmentContextGeneration = 0
  let scopeGeneration = 0

  const buildingOptions = computed(() => buildings.value.items.map(item => ({ label: item.buildingName, value: item.buildingId })))

  async function loadBuildings() {
    const owner = ++buildingGeneration
    buildingsLoading.value = true
    buildingsError.value = null
    try {
      const page = await listBuildings({
        page: buildingQuery.value.page,
        size: buildingQuery.value.size,
        keyword: trimmed(buildingQuery.value.keyword),
      })
      if (owner === buildingGeneration) buildings.value = page
      return page
    } catch (reason) {
      if (owner === buildingGeneration) buildingsError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === buildingGeneration) buildingsLoading.value = false
    }
  }

  function setBuildingQuery(patch: Partial<typeof buildingQuery.value>, resetPage = true) {
    buildingQuery.value = {
      ...buildingQuery.value,
      ...patch,
      page: resetPage ? 1 : (patch.page ?? buildingQuery.value.page),
    }
    return loadBuildings()
  }

  async function selectBuilding(buildingId: string | null) {
    const owner = ++buildingContextGeneration
    if (!buildingId) {
      selectedBuilding.value = null
      spaces.value = []
      systemGroups.value = emptyPage(100)
      return
    }
    buildingContextLoading.value = true
    buildingContextError.value = null
    try {
      const [building, nextSpaces, nextGroups] = await Promise.all([
        getBuilding(buildingId),
        listSpaces(buildingId),
        listSystemGroups({ page: 1, size: 100, buildingId }),
      ])
      if (owner === buildingContextGeneration) {
        selectedBuilding.value = building
        spaces.value = nextSpaces
        systemGroups.value = nextGroups
      }
    } catch (reason) {
      if (owner === buildingContextGeneration) buildingContextError.value = requestState(reason)
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
      const page = await listEquipment({ ...equipmentQuery.value, keyword: trimmed(equipmentQuery.value.keyword) })
      if (owner === equipmentGeneration) equipment.value = page
      return page
    } catch (reason) {
      if (owner === equipmentGeneration) equipmentError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === equipmentGeneration) equipmentLoading.value = false
    }
  }

  function setEquipmentQuery(patch: Partial<AssetEquipmentQuery>, resetPage = true) {
    equipmentQuery.value = {
      ...equipmentQuery.value,
      ...patch,
      page: resetPage ? 1 : (patch.page ?? equipmentQuery.value.page),
    }
    return loadEquipment()
  }

  async function selectEquipment(equipmentId: string | null) {
    const owner = ++equipmentContextGeneration
    if (!equipmentId) {
      selectedEquipment.value = null
      points.value = []
      return
    }
    equipmentContextLoading.value = true
    equipmentContextError.value = null
    try {
      const [detail, nextPoints] = await Promise.all([getEquipment(equipmentId), listEquipmentPoints(equipmentId)])
      if (owner === equipmentContextGeneration) {
        selectedEquipment.value = detail
        points.value = nextPoints
      }
    } catch (reason) {
      if (owner === equipmentContextGeneration) equipmentContextError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === equipmentContextGeneration) equipmentContextLoading.value = false
    }
  }

  async function loadScope(buildingId: string | undefined) {
    const owner = ++scopeGeneration
    if (!buildingId) {
      scopeSpaces.value = []
      scopeSystemGroups.value = []
      scopeError.value = null
      return
    }
    scopeLoading.value = true
    scopeError.value = null
    try {
      const [nextSpaces, nextGroups] = await Promise.all([
        listSpaces(buildingId),
        listSystemGroups({ page: 1, size: 100, buildingId }),
      ])
      if (owner === scopeGeneration) {
        scopeSpaces.value = nextSpaces
        scopeSystemGroups.value = nextGroups.items
      }
    } catch (reason) {
      if (owner === scopeGeneration) scopeError.value = requestState(reason)
      throw reason
    } finally {
      if (owner === scopeGeneration) scopeLoading.value = false
    }
  }

  async function ensureBuildingOptions() {
    if (buildings.value.items.length >= 100 || buildingQuery.value.size >= 100) return
    try {
      const page = await listBuildings({ page: 1, size: 100 })
      buildings.value = page
    } catch (reason) {
      buildingsError.value = requestState(reason)
      throw reason
    }
  }

  function run(key: string, task: () => Promise<void>) {
    if (pending.value.has(key)) return Promise.resolve()
    pending.value = new Set(pending.value).add(key)
    operationError.value = null
    return task()
      .catch(reason => {
        operationError.value = requestState(reason)
        throw reason
      })
      .finally(() => {
        const next = new Set(pending.value)
        next.delete(key)
        pending.value = next
      })
  }

  function saveBuilding(buildingId: string | null, form: AssetBuildingForm) {
    return run(buildingId ? `building:update:${buildingId}` : 'building:create', async () => {
      if (buildingId) await updateBuilding(buildingId, form)
      else await createBuilding(form)
      await loadBuildings()
      if (buildingId) await selectBuilding(buildingId)
    })
  }

  function removeBuilding(buildingId: string) {
    return run(`building:delete:${buildingId}`, async () => {
      await deleteBuilding(buildingId)
      if (selectedBuilding.value?.buildingId === buildingId) await selectBuilding(null)
      await loadBuildings()
    })
  }

  function saveSpace(spaceId: string | null, form: AssetSpaceForm) {
    return run(spaceId ? `space:update:${spaceId}` : 'space:create', async () => {
      if (spaceId) {
        await updateSpace(spaceId, spaceUpdate(form))
      } else await createSpace(form)
      await selectBuilding(form.buildingId)
    })
  }

  function removeSpace(spaceId: string, buildingId: string) {
    return run(`space:delete:${spaceId}`, async () => {
      await deleteSpace(spaceId)
      await selectBuilding(buildingId)
    })
  }

  function saveSystemGroup(systemGroupId: string | null, form: AssetSystemGroupForm) {
    return run(systemGroupId ? `system:update:${systemGroupId}` : 'system:create', async () => {
      if (systemGroupId) {
        await updateSystemGroup(systemGroupId, systemGroupUpdate(form))
      } else await createSystemGroup(form)
      await selectBuilding(form.buildingId)
    })
  }

  function removeSystemGroup(systemGroupId: string, buildingId: string) {
    return run(`system:delete:${systemGroupId}`, async () => {
      await deleteSystemGroup(systemGroupId)
      await selectBuilding(buildingId)
    })
  }

  function saveEquipment(equipmentId: string | null, form: AssetEquipmentForm) {
    return run(equipmentId ? `equipment:update:${equipmentId}` : 'equipment:create', async () => {
      if (equipmentId) {
        await updateEquipment(equipmentId, equipmentUpdate(form))
      } else await createEquipment(form)
      await loadEquipment()
      if (equipmentId) await selectEquipment(equipmentId)
    })
  }

  function removeEquipment(equipmentId: string) {
    return run(`equipment:delete:${equipmentId}`, async () => {
      await deleteEquipment(equipmentId)
      if (selectedEquipment.value?.equipmentId === equipmentId) await selectEquipment(null)
      await loadEquipment()
    })
  }

  function savePoint(equipmentId: string, pointId: string, update: AssetPointUpdate) {
    return run(`point:update:${pointId}`, async () => {
      await updateEquipmentPoint(equipmentId, pointId, update)
      await selectEquipment(equipmentId)
    })
  }

  function removePoint(equipmentId: string, pointId: string) {
    return run(`point:delete:${pointId}`, async () => {
      await deleteEquipmentPoint(equipmentId, pointId)
      await selectEquipment(equipmentId)
    })
  }

  return {
    buildingQuery,
    buildings,
    buildingsLoading,
    buildingsError,
    selectedBuilding,
    spaces,
    systemGroups,
    buildingContextLoading,
    buildingContextError,
    equipmentQuery,
    equipment,
    equipmentLoading,
    equipmentError,
    selectedEquipment,
    points,
    equipmentContextLoading,
    equipmentContextError,
    scopeSpaces,
    scopeSystemGroups,
    scopeLoading,
    scopeError,
    buildingOptions,
    pending,
    operationError,
    loadBuildings,
    setBuildingQuery,
    selectBuilding,
    loadEquipment,
    setEquipmentQuery,
    selectEquipment,
    loadScope,
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

function trimmed(value: string | undefined): string | undefined {
  const result = value?.trim()
  return result || undefined
}

function spaceUpdate(form: AssetSpaceForm): Omit<AssetSpaceForm, 'buildingId'> {
  return {
    parentSpaceId: form.parentSpaceId,
    spaceName: form.spaceName,
    spaceCode: form.spaceCode,
    spaceType: form.spaceType,
    sortOrder: form.sortOrder,
    usableArea: form.usableArea,
    status: form.status,
  }
}

function systemGroupUpdate(form: AssetSystemGroupForm): Omit<AssetSystemGroupForm, 'buildingId' | 'systemCode'> {
  return { systemName: form.systemName, systemType: form.systemType, status: form.status }
}

function equipmentUpdate(form: AssetEquipmentForm): Omit<AssetEquipmentForm, 'typeCode' | 'productId'> {
  return {
    buildingId: form.buildingId,
    spaceId: form.spaceId,
    systemGroupId: form.systemGroupId,
    equipmentName: form.equipmentName,
    manufacturer: form.manufacturer,
    ratedCapacity: form.ratedCapacity,
    ratedPower: form.ratedPower,
    designCop: form.designCop,
    status: form.status,
  }
}

function requestState(reason: unknown): RequestState {
  return { message: requestErrorMessage(reason) }
}
