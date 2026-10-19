import { requestApi } from '@/infrastructure/http/public'
import type { AccessibleBuildingPage } from '../models/classification'

export function listClassificationBuildings(page: number, keyword: string) {
  return requestApi<AccessibleBuildingPage>({ method: 'get', url: '/building/list', params: { page, size: 20, keyword } })
}
