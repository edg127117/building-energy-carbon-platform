import { requestApi } from '@/infrastructure/http/public'
import type { MeterElectricityView } from '../models/meter-electricity'

/** 服务端返回单表已完成自然日用电量；浏览器不从监控读数重算。 */
export function getMeterElectricity(equipmentId: string, buildingId: string, days: 7 | 14 | 30) {
  return requestApi<MeterElectricityView>({
    method: 'get',
    url: `/v1/assets/equipment/${encodeURIComponent(equipmentId)}/electricity-analysis`,
    params: { buildingId, days },
  })
}
