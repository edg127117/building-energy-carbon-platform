import { ref } from 'vue'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { parameterPackagesApi } from '../api/parameter-packages'
import type { ParameterPackageCatalog, ParameterPackageImport, ParameterPackagePreview, ParameterPackageRequest } from '../models/parameter-packages'

export function useParameterPackages() {
  const catalog = ref<ParameterPackageCatalog | null>(null)
  const loading = ref(false)
  const saving = ref(false)
  const error = ref('')
  let buildingId = ''
  let catalogGeneration = 0
  let commandGeneration = 0
  let activeCatalogRequest: AbortController | null = null

  async function load(nextBuildingId: string): Promise<ParameterPackageCatalog | null> {
    const targetBuildingId = nextBuildingId.trim()
    const owner = ++catalogGeneration
    activeCatalogRequest?.abort()
    activeCatalogRequest = null
    buildingId = targetBuildingId
    catalog.value = null
    error.value = ''
    if (!targetBuildingId) {
      loading.value = false
      return null
    }
    const controller = new AbortController()
    activeCatalogRequest = controller
    loading.value = true
    try {
      const response = await parameterPackagesApi.catalog(targetBuildingId, controller.signal)
      if (owner !== catalogGeneration || buildingId !== targetBuildingId) return null
      catalog.value = response
      return response
    } catch (reason) {
      if (owner === catalogGeneration && buildingId === targetBuildingId) error.value = requestErrorMessage(reason)
      return null
    } finally {
      if (owner === catalogGeneration) {
        loading.value = false
        if (activeCatalogRequest === controller) activeCatalogRequest = null
      }
    }
  }

  async function preview(request: ParameterPackageRequest): Promise<ParameterPackagePreview | null> {
    return execute(() => parameterPackagesApi.preview(request))
  }

  async function importPackage(request: ParameterPackageRequest): Promise<ParameterPackageImport | null> {
    return execute(() => parameterPackagesApi.importPackage(request))
  }

  async function execute<T>(action: () => Promise<T>): Promise<T | null> {
    if (saving.value) return null
    const owner = ++commandGeneration
    const targetBuildingId = buildingId
    saving.value = true
    error.value = ''
    try {
      const response = await action()
      return owner === commandGeneration && buildingId === targetBuildingId ? response : null
    } catch (reason) {
      if (owner === commandGeneration && buildingId === targetBuildingId) error.value = requestErrorMessage(reason)
      return null
    } finally {
      if (owner === commandGeneration) saving.value = false
    }
  }

  return { catalog, loading, saving, error, load, preview, importPackage }
}
