import { requestApi } from '@/infrastructure/http/public'
import type { ParameterPackageCatalog, ParameterPackageImport, ParameterPackagePreview, ParameterPackageRequest } from '../models/parameter-packages'

const basePath = '/v1/energy-parameter-packages'

export const parameterPackagesApi = {
  catalog(buildingId: string, signal?: AbortSignal) {
    return requestApi<ParameterPackageCatalog>({ method: 'get', url: `${basePath}/catalog`, params: { buildingId }, signal })
  },
  preview(data: ParameterPackageRequest) {
    return requestApi<ParameterPackagePreview>({ method: 'post', url: `${basePath}/preview`, data })
  },
  importPackage(data: ParameterPackageRequest) {
    return requestApi<ParameterPackageImport>({ method: 'post', url: `${basePath}/import`, data })
  },
}
