import { requestApi } from '@/infrastructure/http/public'

export interface ReportingAccessibleBuilding {
  buildingId: string
  buildingName: string
}

export interface ReportingAccessibleBuildingPage {
  records: ReportingAccessibleBuilding[]
  total: number
}

export function listReportingBuildings(page: number, keyword: string) {
  return requestApi<ReportingAccessibleBuildingPage>({ method: 'get', url: '/building/list', params: { page, size: 20, keyword } })
}
